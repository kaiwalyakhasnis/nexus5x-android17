// SPDX-License-Identifier: Apache-2.0
// See Sensors.h.
#include "Sensors.h"

#include <android/log.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <cstring>
#include <set>

#define LOG_TAG "sensors.bullhead"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using aidl::android::hardware::common::fmq::MQDescriptor;
using aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ndk::ScopedAStatus;
using Payload = aidl::android::hardware::sensors::Event::EventPayload;

namespace bullhead {

namespace {

// --- the hub's protocol (contexthub firmware/os/inc/eventnums.h, sensType.h;
// sensorhal/hubconnection.{h,cpp})
constexpr uint32_t EVT_NO_FIRST_SENSOR_EVENT = 0x200;
constexpr uint32_t EVT_NO_SENSOR_CONFIG_EVENT = 0x300;
constexpr uint32_t EVT_RESET_REASON = 0x403;
constexpr uint32_t EVT_APP_TO_SENSOR_HAL_DATA = 0x404;
constexpr uint32_t OS_LOG_EVENT = 0x474F4C41;  // "ALOG"

constexpr uint8_t CONFIG_CMD_DISABLE = 0;
constexpr uint8_t CONFIG_CMD_ENABLE = 1;
constexpr uint32_t SENSOR_RATE_ONCHANGE = 0xFFFFFF01;

// hub sensor types
constexpr uint8_t SENS_ACCEL = 1, SENS_GYRO = 6, SENS_MAG = 8, SENS_BARO = 10, SENS_ALS = 12,
                  SENS_PROX = 13, SENS_GRAVITY = 17, SENS_LINEAR_ACCEL = 18, SENS_ROT_VEC = 19,
                  SENS_GEO_MAG_ROT_VEC = 20, SENS_GAME_ROT_VEC = 21, SENS_STEP_COUNT = 22,
                  SENS_STEP_DETECT = 23, SENS_ACCEL_RAW = 32, SENS_MAG_RAW = 59;

// Android handles: the stock HAL's comms_sensor_t numbers (hubdefs.h)
enum Handle : int {
    ACCEL = 1, GYRO = 2, MAG = 3, PRESSURE = 4, PROXIMITY = 6, LIGHT = 7, STEP_DETECTOR = 9,
    GRAVITY = 14, LINEAR_ACCEL = 15, ROTATION_VECTOR = 16, GEO_MAG = 18, GAME_ROTATION_VECTOR = 19,
    STEP_COUNTER = 23, MAG_UNCALIBRATED = 24, GYRO_UNCALIBRATED = 25, ACCEL_UNCALIBRATED = 47,
};

// bullhead's raw sample scales (device/lge/bullhead/sensorhal/sensorlist.cpp)
constexpr float GRAVITY_EARTH = 9.80665f;
constexpr float kScaleAccel = 16.0f * 9.81f / 32768.0f;
constexpr float kScaleMag = 1.0f / 16.0f;
constexpr float kMinMagSq = 10.0f * 10.0f, kMaxMagSq = 80.0f * 80.0f;

uint32_t le32(const uint8_t* p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return v;
}
uint64_t le64(const uint8_t* p) {
    uint64_t v;
    memcpy(&v, p, 8);
    return v;
}
float f32(const uint8_t* p) {
    float v;
    memcpy(&v, p, 4);
    return v;
}
int16_t i16(const uint8_t* p) {
    int16_t v;
    memcpy(&v, p, 2);
    return v;
}

SensorDef def(int handle, uint8_t hubType, float minHz, float maxHz, const char* name,
              const char* vendor, SensorType type, const char* typeString, float maxRange,
              float resolution, float power, int32_t flags) {
    SensorDef d{handle, hubType, minHz, maxHz, {}};
    SensorInfo& i = d.info;
    i.sensorHandle = handle;
    i.name = name;
    i.vendor = vendor;
    i.version = 1;
    i.type = type;
    i.typeAsString = typeString;
    i.maxRange = maxRange;
    i.resolution = resolution;
    i.power = power;
    // on change: Android's limits are the hub's polling rates too
    i.minDelayUs = maxHz > 0 ? static_cast<int32_t>(1e6f / maxHz) : 0;
    i.maxDelayUs = minHz > 0 ? static_cast<int32_t>(1e6f / minHz) : 0;
    // no batching: events are sent as they come (flush is then immediate)
    i.fifoReservedEventCount = 0;
    i.fifoMaxEventCount = 0;
    i.flags = flags;
    return d;
}

constexpr int32_t CONTINUOUS = SensorInfo::SENSOR_FLAG_BITS_CONTINUOUS_MODE;
constexpr int32_t ON_CHANGE = SensorInfo::SENSOR_FLAG_BITS_ON_CHANGE_MODE;
constexpr int32_t SPECIAL = SensorInfo::SENSOR_FLAG_BITS_SPECIAL_REPORTING_MODE;
constexpr int32_t WAKE_UP = SensorInfo::SENSOR_FLAG_BITS_WAKE_UP;
constexpr int32_t MODE_MASK = 0xe;

Event event(int64_t t, int handle, SensorType type) {
    Event e;
    e.timestamp = t;
    e.sensorHandle = handle;
    e.sensorType = type;
    return e;
}

Event vec3(int64_t t, int handle, SensorType type, float x, float y, float z, SensorStatus s) {
    Event e = event(t, handle, type);
    e.payload.set<Payload::vec3>(Payload::Vec3{x, y, z, s});
    return e;
}

Event uncal(int64_t t, int handle, SensorType type, float x, float y, float z, const float* bias) {
    Event e = event(t, handle, type);
    // x is the calibrated value: uncalibrated = calibrated + bias
    e.payload.set<Payload::uncal>(
            Payload::Uncal{x + bias[0], y + bias[1], z + bias[2], bias[0], bias[1], bias[2]});
    return e;
}

Event scalar(int64_t t, int handle, SensorType type, float v) {
    Event e = event(t, handle, type);
    e.payload.set<Payload::scalar>(v);
    return e;
}

/** w of a unit quaternion from x, y, z (the hub sends three) */
float quatW(float x, float y, float z) {
    float w = x * x + y * y + z * z;
    return w < 1.0f ? std::sqrt(1.0f - w) : 0.0f;
}

}  // namespace

Sensors::Sensors() {
    const float pi = static_cast<float>(M_PI);
    // Rates and ranges from the stock list (device/lge/bullhead/sensorhal)
    mDefs = {
        def(ACCEL, SENS_ACCEL, 6.25f, 400.0f, "BMI160 accelerometer", "Bosch",
            SensorType::ACCELEROMETER, "android.sensor.accelerometer", GRAVITY_EARTH * 16.0f,
            GRAVITY_EARTH * 16.0f / 32768.0f, 0.18f, CONTINUOUS),
        def(ACCEL_UNCALIBRATED, SENS_ACCEL, 6.25f, 400.0f, "BMI160 accelerometer (uncalibrated)",
            "Bosch", SensorType::ACCELEROMETER_UNCALIBRATED,
            "android.sensor.accelerometer_uncalibrated", GRAVITY_EARTH * 16.0f,
            GRAVITY_EARTH * 16.0f / 32768.0f, 0.18f, CONTINUOUS),
        def(GYRO, SENS_GYRO, 6.25f, 400.0f, "BMI160 gyroscope", "Bosch", SensorType::GYROSCOPE,
            "android.sensor.gyroscope", 1000.0f * pi / 180.0f,
            1000.0f * pi / (180.0f * 32768.0f), 0.9f, CONTINUOUS),
        def(GYRO_UNCALIBRATED, SENS_GYRO, 6.25f, 400.0f, "BMI160 gyroscope (uncalibrated)", "Bosch",
            SensorType::GYROSCOPE_UNCALIBRATED, "android.sensor.gyroscope_uncalibrated",
            1000.0f * pi / 180.0f, 1000.0f * pi / (180.0f * 32768.0f), 0.9f, CONTINUOUS),
        def(MAG, SENS_MAG, 3.125f, 50.0f, "BMM150 magnetometer", "Bosch",
            SensorType::MAGNETIC_FIELD, "android.sensor.magnetic_field", 1300.0f, kScaleMag, 0.5f,
            CONTINUOUS),
        def(MAG_UNCALIBRATED, SENS_MAG, 3.125f, 50.0f, "BMM150 magnetometer (uncalibrated)",
            "Bosch", SensorType::MAGNETIC_FIELD_UNCALIBRATED,
            "android.sensor.magnetic_field_uncalibrated", 1300.0f, kScaleMag, 0.5f, CONTINUOUS),
        def(PRESSURE, SENS_BARO, 0.1f, 10.0f, "BMP280 pressure", "Bosch", SensorType::PRESSURE,
            "android.sensor.pressure", 1100.0f, 0.005f, 0.004f, CONTINUOUS),
        def(LIGHT, SENS_ALS, 0.1f, 5.0f, "RPR0521 Light Sensor", "Rohm", SensorType::LIGHT,
            "android.sensor.light", 43000.0f, 10.0f, 0.1f, ON_CHANGE),
        def(PROXIMITY, SENS_PROX, 0.1f, 5.0f, "RPR0521 Proximity Sensor", "Rohm",
            SensorType::PROXIMITY, "android.sensor.proximity", 5.0f, 1.0f, 0.1f,
            ON_CHANGE | WAKE_UP),
        def(GRAVITY, SENS_GRAVITY, 12.5f, 200.0f, "Gravity", "Google", SensorType::GRAVITY,
            "android.sensor.gravity", GRAVITY_EARTH * 16.0f, 0.0f, 1.1f, CONTINUOUS),
        def(LINEAR_ACCEL, SENS_LINEAR_ACCEL, 12.5f, 200.0f, "Linear Acceleration", "Google",
            SensorType::LINEAR_ACCELERATION, "android.sensor.linear_acceleration",
            GRAVITY_EARTH * 16.0f, 0.0f, 1.1f, CONTINUOUS),
        def(ROTATION_VECTOR, SENS_ROT_VEC, 12.5f, 200.0f, "Rotation Vector", "Google",
            SensorType::ROTATION_VECTOR, "android.sensor.rotation_vector", 1.0f, 0.0f, 1.6f,
            CONTINUOUS),
        def(GEO_MAG, SENS_GEO_MAG_ROT_VEC, 12.5f, 200.0f, "Geomagnetic Rotation Vector", "Google",
            SensorType::GEOMAGNETIC_ROTATION_VECTOR, "android.sensor.geomagnetic_rotation_vector",
            1.0f, 0.0f, 0.7f, CONTINUOUS),
        def(GAME_ROTATION_VECTOR, SENS_GAME_ROT_VEC, 12.5f, 200.0f, "Game Rotation Vector",
            "Google", SensorType::GAME_ROTATION_VECTOR, "android.sensor.game_rotation_vector",
            1.0f, 0.0f, 1.1f, CONTINUOUS),
        def(STEP_COUNTER, SENS_STEP_COUNT, 0.1f, 5.0f, "BMI160 Step counter", "Bosch",
            SensorType::STEP_COUNTER, "android.sensor.step_counter", 1.0f, 1.0f, 0.18f,
            ON_CHANGE),
        def(STEP_DETECTOR, SENS_STEP_DETECT, 0.0f, 0.0f, "BMI160 Step detector", "Bosch",
            SensorType::STEP_DETECTOR, "android.sensor.step_detector", 1.0f, 1.0f, 0.18f,
            SPECIAL),
    };
    for (auto& d : mDefs) mState[d.handle] = SensorState{};
}

Sensors::~Sensors() {
    mRun = false;
    if (mReader.joinable()) mReader.join();
    if (mWakeLockReader.joinable()) mWakeLockReader.join();
    if (mFd >= 0) close(mFd);
}

bool Sensors::start() {
    // the hub driver is built into the kernel: the node is there from boot
    for (int i = 0; i < 50 && mFd < 0; i++) {
        mFd = open("/dev/nanohub", O_RDWR | O_CLOEXEC);
        if (mFd < 0) usleep(100'000);
    }
    if (mFd < 0) {
        // still register, with no sensors: the framework waits for this
        // HAL (it is in the VINTF manifest) and would hold up the boot
        ALOGE("open /dev/nanohub: %s: no sensors (a kernel without the hub driver?)",
              strerror(errno));
        mDefs.clear();
        mState.clear();
        return true;
    }
    mReader = std::thread(&Sensors::readLoop, this);
    mWakeLockReader = std::thread(&Sensors::wakeLockLoop, this);
    ALOGI("started: %zu sensors on the nanohub", mDefs.size());
    return true;
}

const SensorDef* Sensors::find(int handle) const {
    for (const auto& d : mDefs)
        if (d.handle == handle) return &d;
    return nullptr;
}

// --- to the hub

/** One ConfigCmd for a hub sensor, from every Android sensor it feeds:
 *  enabled if any is, at the fastest rate any asks for. mLock held. */
bool Sensors::configureHub(uint8_t hubType) {
    bool enable = false;
    bool onChangeOnly = false;
    float hz = 0;
    for (const auto& d : mDefs) {
        if (d.hubType != hubType) continue;
        const SensorState& s = mState[d.handle];
        if (!s.enabled) continue;
        enable = true;
        if (d.maxHz == 0) {
            onChangeOnly = true;
            continue;
        }
        float want = s.periodNs > 0 ? 1e9f / static_cast<float>(s.periodNs) : d.maxHz;
        want = std::fmax(d.minHz, std::fmin(d.maxHz, want));
        hz = std::fmax(hz, want);
    }
    uint32_t rate = onChangeOnly && hz == 0 ? SENSOR_RATE_ONCHANGE
                                            : static_cast<uint32_t>(std::lround(hz * 1024.0f));

    // struct ConfigCmd (packed): evtType u32, latency u64, rate u32 (Hz Q10),
    // sensorType u8, cmd u8, flags u16
    uint8_t cmd[20] = {};
    uint32_t evt = EVT_NO_SENSOR_CONFIG_EVENT;
    uint64_t latency = 0;  // no batching
    memcpy(cmd, &evt, 4);
    memcpy(cmd + 4, &latency, 8);
    memcpy(cmd + 12, &rate, 4);
    cmd[16] = hubType;
    cmd[17] = enable ? CONFIG_CMD_ENABLE : CONFIG_CMD_DISABLE;
    ssize_t n = write(mFd, cmd, sizeof(cmd));
    if (n != static_cast<ssize_t>(sizeof(cmd))) {
        ALOGW("hub sensor %u %s: write: %s", hubType, enable ? "on" : "off",
              n < 0 ? strerror(errno) : "short");
        return false;
    }
    ALOGI("hub sensor %u %s, rate %.2f Hz", hubType, enable ? "on" : "off",
          rate == SENSOR_RATE_ONCHANGE ? 0.0f : rate / 1024.0f);
    return true;
}

void Sensors::restoreHub() {
    std::lock_guard<std::mutex> lock(mLock);
    std::set<uint8_t> types;
    for (const auto& d : mDefs)
        if (mState[d.handle].enabled) types.insert(d.hubType);
    for (uint8_t t : types) configureHub(t);
}

// --- from the hub

void Sensors::readLoop() {
    uint8_t buf[256];
    while (mRun) {
        pollfd p{mFd, POLLIN, 0};
        if (poll(&p, 1, 500) <= 0) continue;
        ssize_t n = read(mFd, buf, sizeof(buf));
        if (n < 0) {
            if (errno != EINTR && errno != EAGAIN) {
                ALOGE("read /dev/nanohub: %s", strerror(errno));
                usleep(100'000);
            }
            continue;
        }
        processBuffer(buf, n);
    }
}

/** One hub event: a log line, a reset, or a sensor's samples
 *  (hubconnection.cpp processBuf, nAxisEvent). */
void Sensors::processBuffer(const uint8_t* buf, size_t len) {
    if (len < 4) return;
    uint32_t evt = le32(buf);
    if (evt == OS_LOG_EVENT) {
        if (len > 5) ALOGI("hub: %c %.*s", buf[4], static_cast<int>(len - 5), buf + 5);
        return;
    }
    if (evt == EVT_RESET_REASON) {
        ALOGI("hub reset, reason 0x%08x: restoring sensors", len >= 8 ? le32(buf + 4) : 0);
        restoreHub();
        return;
    }
    if (evt == EVT_APP_TO_SENSOR_HAL_DATA) return;
    if (evt <= EVT_NO_FIRST_SENSOR_EVENT || evt >= EVT_NO_FIRST_SENSOR_EVENT + 0x100) return;
    uint32_t hubType = evt - EVT_NO_FIRST_SENSOR_EVENT;

    Sample kind;
    switch (hubType) {
        case SENS_ACCEL_RAW:
        case SENS_MAG_RAW:
            kind = Sample::RawThree;
            break;
        case SENS_ACCEL:
        case SENS_GYRO:
        case SENS_MAG:
        case SENS_GRAVITY:
        case SENS_LINEAR_ACCEL:
        case SENS_ROT_VEC:
        case SENS_GEO_MAG_ROT_VEC:
        case SENS_GAME_ROT_VEC:
            kind = Sample::Three;
            break;
        case SENS_BARO:
        case SENS_ALS:
        case SENS_PROX:
        case SENS_STEP_COUNT:
        case SENS_STEP_DETECT:
            kind = Sample::One;
            break;
        default: {
            // a sensor we don't offer (temperature, gestures, ...): say so once
            static std::set<uint32_t> seen;
            if (seen.insert(hubType).second)
                ALOGI("hub events of type %u (%zu bytes): not offered, ignored", hubType, len);
            return;
        }
    }

    // evtType u32, referenceTime u64, then the samples; sample 0's first
    // 4 bytes are the header: numSamples u8, numFlushes u8,
    // highAccuracy:1 biasPresent:1 biasSample:6, pad
    if (len < 16) return;
    int64_t t = static_cast<int64_t>(le64(buf + 4));
    int numSamples = buf[12];
    bool highAccuracy = buf[14] & 1;
    bool biasPresent = (buf[14] >> 1) & 1;
    int biasSample = buf[14] >> 2;
    size_t size = kind == Sample::One ? 8 : kind == Sample::RawThree ? 10 : 16;

    std::map<int, bool> on;
    {
        std::lock_guard<std::mutex> lock(mLock);
        for (auto& [h, s] : mState) on[h] = s.enabled;
    }

    std::vector<Event> out;
    for (int i = 0; i < numSamples; i++) {
        const uint8_t* s = buf + 12 + i * size;
        if (s + size > buf + len) break;
        if (i > 0) {
            // delta time: bit 0 set: in ns, else in 512 ns units
            uint32_t d = le32(s);
            t += (d & 1) ? d : static_cast<int64_t>(d) << 9;
        }
        float x = 0, y = 0, z = 0;
        if (kind == Sample::Three) {
            x = f32(s + 4), y = f32(s + 8), z = f32(s + 12);
        } else if (kind == Sample::RawThree) {
            float k = hubType == SENS_ACCEL_RAW ? kScaleAccel : kScaleMag;
            x = i16(s + 4) * k, y = i16(s + 6) * k, z = i16(s + 8) * k;
        }
        bool bias = biasPresent && biasSample == i;

        switch (hubType) {
            case SENS_ACCEL:
            case SENS_ACCEL_RAW:
                if (bias) {
                    mAccelBias[0] = x, mAccelBias[1] = y, mAccelBias[2] = z;
                    break;
                }
                if (on[ACCEL])
                    out.push_back(vec3(t, ACCEL, SensorType::ACCELEROMETER, x, y, z,
                                       SensorStatus::ACCURACY_HIGH));
                if (on[ACCEL_UNCALIBRATED])
                    out.push_back(uncal(t, ACCEL_UNCALIBRATED,
                                        SensorType::ACCELEROMETER_UNCALIBRATED, x, y, z, mAccelBias));
                break;
            case SENS_GYRO:
                if (bias) {
                    mGyroBias[0] = x, mGyroBias[1] = y, mGyroBias[2] = z;
                    break;
                }
                if (on[GYRO])
                    out.push_back(vec3(t, GYRO, SensorType::GYROSCOPE, x, y, z,
                                       SensorStatus::ACCURACY_HIGH));
                if (on[GYRO_UNCALIBRATED])
                    out.push_back(uncal(t, GYRO_UNCALIBRATED, SensorType::GYROSCOPE_UNCALIBRATED,
                                        x, y, z, mGyroBias));
                break;
            case SENS_MAG:
            case SENS_MAG_RAW: {
                if (bias) {
                    mMagAccuracy = highAccuracy ? SensorStatus::ACCURACY_HIGH
                                                : SensorStatus::ACCURACY_MEDIUM;
                    mMagBias[0] = x, mMagBias[1] = y, mMagBias[2] = z;
                    break;
                }
                // hubconnection.cpp magAccuracyUpdate: an implausible field
                // strength is unreliable until it is plausible again
                float sq = x * x + y * y + z * z;
                if (sq < kMinMagSq || sq > kMaxMagSq) {
                    if (mMagAccuracy != SensorStatus::UNRELIABLE) mMagAccuracyRestore = mMagAccuracy;
                    mMagAccuracy = SensorStatus::UNRELIABLE;
                } else if (mMagAccuracy == SensorStatus::UNRELIABLE) {
                    mMagAccuracy = mMagAccuracyRestore;
                }
                if (on[MAG])
                    out.push_back(vec3(t, MAG, SensorType::MAGNETIC_FIELD, x, y, z, mMagAccuracy));
                if (on[MAG_UNCALIBRATED])
                    out.push_back(uncal(t, MAG_UNCALIBRATED, SensorType::MAGNETIC_FIELD_UNCALIBRATED,
                                        x, y, z, mMagBias));
                break;
            }
            case SENS_GRAVITY:
                if (on[GRAVITY])
                    out.push_back(vec3(t, GRAVITY, SensorType::GRAVITY, x, y, z, mMagAccuracy));
                break;
            case SENS_LINEAR_ACCEL:
                if (on[LINEAR_ACCEL])
                    out.push_back(vec3(t, LINEAR_ACCEL, SensorType::LINEAR_ACCELERATION, x, y, z,
                                       mMagAccuracy));
                break;
            case SENS_ROT_VEC:
            case SENS_GEO_MAG_ROT_VEC: {
                int h = hubType == SENS_ROT_VEC ? ROTATION_VECTOR : GEO_MAG;
                if (!on[h]) break;
                Event e = event(t, h,
                                h == ROTATION_VECTOR ? SensorType::ROTATION_VECTOR
                                                     : SensorType::GEOMAGNETIC_ROTATION_VECTOR);
                Payload::Data data;
                data.values[0] = x, data.values[1] = y, data.values[2] = z;
                data.values[3] = quatW(x, y, z);
                // heading accuracy (rad), from the compass's
                data.values[4] = (4 - static_cast<int>(mMagAccuracy)) * static_cast<float>(M_PI) / 6;
                e.payload.set<Payload::data>(data);
                out.push_back(e);
                break;
            }
            case SENS_GAME_ROT_VEC:
                if (on[GAME_ROTATION_VECTOR]) {
                    Event e = event(t, GAME_ROTATION_VECTOR, SensorType::GAME_ROTATION_VECTOR);
                    e.payload.set<Payload::vec4>(Payload::Vec4{x, y, z, quatW(x, y, z)});
                    out.push_back(e);
                }
                break;
            case SENS_BARO:
                if (on[PRESSURE]) out.push_back(scalar(t, PRESSURE, SensorType::PRESSURE, f32(s + 4)));
                break;
            case SENS_ALS:
                if (on[LIGHT]) out.push_back(scalar(t, LIGHT, SensorType::LIGHT, f32(s + 4)));
                break;
            case SENS_PROX:
                // a wake-up sensor; no wake lock needed: the phone never
                // suspends (rootfs/init blocks it)
                if (on[PROXIMITY])
                    out.push_back(scalar(t, PROXIMITY, SensorType::PROXIMITY, f32(s + 4)));
                break;
            case SENS_STEP_COUNT:
                if (on[STEP_COUNTER]) {
                    Event e = event(t, STEP_COUNTER, SensorType::STEP_COUNTER);
                    e.payload.set<Payload::stepCount>(le32(s + 4));
                    out.push_back(e);
                }
                break;
            case SENS_STEP_DETECT:
                if (on[STEP_DETECTOR])
                    out.push_back(scalar(t, STEP_DETECTOR, SensorType::STEP_DETECTOR, 1.0f));
                break;
        }
    }
    if (!out.empty()) post(out);
}

void Sensors::post(std::vector<Event>& events) {
    std::lock_guard<std::mutex> lock(mQueueLock);
    if (!mEvents.valid()) return;
    if (mEvents.write(events.data(), events.size())) {
        mEvents.wake(ISensors::EVENT_QUEUE_FLAG_BITS_READ_AND_PROCESS);
    } else {
        static int dropped;
        if (dropped++ % 100 == 0) ALOGW("event queue full: %zu events dropped", events.size());
    }
}

/** The framework tells us, through this queue, how many wake-up events it
 *  has handled; we hold no wake lock (the phone never suspends), so just
 *  keep the queue empty. */
void Sensors::wakeLockLoop() {
    int32_t handled[64];
    while (mRun) {
        {
            std::lock_guard<std::mutex> lock(mQueueLock);
            while (mWakeLocks.read(handled, 64) == 64) {
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

// --- ISensors

ScopedAStatus Sensors::getSensorsList(std::vector<SensorInfo>* ret) {
    for (const auto& d : mDefs) ret->push_back(d.info);
    return ScopedAStatus::ok();
}

ScopedAStatus Sensors::initialize(
        const MQDescriptor<Event, SynchronizedReadWrite>& eventQueue,
        const MQDescriptor<int32_t, SynchronizedReadWrite>& wakeLockQueue,
        const std::shared_ptr<ISensorsCallback>& callback) {
    ALOGI("initialize (event size %zu, framework's %d)", sizeof(Event), eventQueue.quantum);
    // sensorservice (re)starting: everything off
    {
        std::lock_guard<std::mutex> lock(mLock);
        std::set<uint8_t> types;
        for (auto& d : mDefs) {
            if (mState[d.handle].enabled) types.insert(d.hubType);
            mState[d.handle].enabled = false;
        }
        for (uint8_t t : types) configureHub(t);
    }
    std::lock_guard<std::mutex> lock(mQueueLock);
    mCallback = callback;
    bool ok = mEvents.init(eventQueue, sizeof(Event)) &&
              mWakeLocks.init(wakeLockQueue, sizeof(int32_t));
    if (!ok || !callback) {
        ALOGE("initialize: unusable queues");
        return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    return ScopedAStatus::ok();
}

ScopedAStatus Sensors::activate(int32_t handle, bool enabled) {
    const SensorDef* d = find(handle);
    if (d == nullptr) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    std::lock_guard<std::mutex> lock(mLock);
    mState[handle].enabled = enabled;
    configureHub(d->hubType);
    return ScopedAStatus::ok();
}

ScopedAStatus Sensors::batch(int32_t handle, int64_t samplingPeriodNs,
                             int64_t /* maxReportLatencyNs: no batching */) {
    const SensorDef* d = find(handle);
    if (d == nullptr) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    std::lock_guard<std::mutex> lock(mLock);
    mState[handle].periodNs = samplingPeriodNs;
    if (mState[handle].enabled) configureHub(d->hubType);
    return ScopedAStatus::ok();
}

/** Nothing is ever held back (latency 0, no FIFO), so a flush is complete
 *  as soon as it is asked for. */
ScopedAStatus Sensors::flush(int32_t handle) {
    const SensorDef* d = find(handle);
    if (d == nullptr) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (!mState[handle].enabled ||
            (d->info.flags & MODE_MASK) == SensorInfo::SENSOR_FLAG_BITS_ONE_SHOT_MODE)
            return ScopedAStatus::fromServiceSpecificError(ISensors::ERROR_BAD_VALUE);
    }
    Event e = event(0, handle, SensorType::META_DATA);
    e.payload.set<Payload::meta>(
            Payload::MetaData{Payload::MetaData::MetaDataEventType::META_DATA_FLUSH_COMPLETE});
    std::vector<Event> v{e};
    post(v);
    return ScopedAStatus::ok();
}

ScopedAStatus Sensors::setOperationMode(ISensors::OperationMode mode) {
    if (mode == ISensors::OperationMode::NORMAL) return ScopedAStatus::ok();
    return ScopedAStatus::fromServiceSpecificError(ISensors::ERROR_BAD_VALUE);
}

ScopedAStatus Sensors::injectSensorData(const Event&) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus Sensors::registerDirectChannel(const ISensors::SharedMemInfo&, int32_t* ret) {
    *ret = EX_UNSUPPORTED_OPERATION;
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus Sensors::unregisterDirectChannel(int32_t) {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus Sensors::configDirectReport(int32_t, int32_t, ISensors::RateLevel, int32_t* ret) {
    *ret = EX_UNSUPPORTED_OPERATION;
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

}  // namespace bullhead
