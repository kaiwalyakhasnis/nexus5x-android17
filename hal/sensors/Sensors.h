// SPDX-License-Identifier: Apache-2.0
// The Nexus 5X sensors HAL (AIDL android.hardware.sensors V3) on the
// nanohub: every sensor is behind the hub, reached through the kernel
// driver's /dev/nanohub (patches/0004). The hub protocol and the sensor
// list are the stock HAL's (device/google/contexthub/sensorhal,
// device/lge/bullhead/sensorhal); built with the NDK only (hal/build).
#pragma once

#include <aidl/android/hardware/sensors/BnSensors.h>

#include <atomic>
#include <map>
#include <mutex>
#include <thread>
#include <vector>

#include "Fmq.h"

namespace bullhead {

using aidl::android::hardware::sensors::Event;
using aidl::android::hardware::sensors::ISensors;
using aidl::android::hardware::sensors::ISensorsCallback;
using aidl::android::hardware::sensors::SensorInfo;
using aidl::android::hardware::sensors::SensorStatus;
using aidl::android::hardware::sensors::SensorType;

/** How a hub stream's samples arrive (hubconnection.h nAxisEvent) */
enum class Sample { One, Three, RawThree };

/** One Android sensor: its handle (the stock HAL's COMMS_SENSOR_* number),
 *  the hub sensor type that produces it, and what Android sees. */
struct SensorDef {
    int handle;
    uint8_t hubType;
    float minHz, maxHz;  // the hub's rates; 0, 0: on change only
    SensorInfo info;
};

/** A sensor's requested state */
struct SensorState {
    bool enabled = false;
    int64_t periodNs = 200'000'000;
};

class Sensors : public aidl::android::hardware::sensors::BnSensors {
  public:
    Sensors();
    ~Sensors() override;

    /** Open /dev/nanohub and start reading it (without it: no sensors). */
    bool start();

    ndk::ScopedAStatus activate(int32_t handle, bool enabled) override;
    ndk::ScopedAStatus batch(int32_t handle, int64_t samplingPeriodNs,
                             int64_t maxReportLatencyNs) override;
    ndk::ScopedAStatus configDirectReport(int32_t handle, int32_t channel,
                                          ISensors::RateLevel rate, int32_t* ret) override;
    ndk::ScopedAStatus flush(int32_t handle) override;
    ndk::ScopedAStatus getSensorsList(std::vector<SensorInfo>* ret) override;
    ndk::ScopedAStatus initialize(
            const aidl::android::hardware::common::fmq::MQDescriptor<
                    Event, aidl::android::hardware::common::fmq::SynchronizedReadWrite>& eventQueue,
            const aidl::android::hardware::common::fmq::MQDescriptor<
                    int32_t, aidl::android::hardware::common::fmq::SynchronizedReadWrite>& wakeLockQueue,
            const std::shared_ptr<ISensorsCallback>& callback) override;
    ndk::ScopedAStatus injectSensorData(const Event& event) override;
    ndk::ScopedAStatus registerDirectChannel(const ISensors::SharedMemInfo& mem,
                                             int32_t* ret) override;
    ndk::ScopedAStatus setOperationMode(ISensors::OperationMode mode) override;
    ndk::ScopedAStatus unregisterDirectChannel(int32_t channel) override;

  private:
    const SensorDef* find(int handle) const;
    bool configureHub(uint8_t hubType);  // mLock held
    void restoreHub();                   // after a hub reset
    void readLoop();
    void wakeLockLoop();
    void processBuffer(const uint8_t* buf, size_t len);
    void post(std::vector<Event>& events);

    int mFd = -1;
    std::vector<SensorDef> mDefs;
    std::map<int, SensorState> mState;  // by handle
    std::mutex mLock;                   // mState, hub writes

    std::mutex mQueueLock;              // the queues
    Fmq mEvents;
    Fmq mWakeLocks;
    std::shared_ptr<ISensorsCallback> mCallback;

    std::atomic<bool> mRun{true};
    std::thread mReader;
    std::thread mWakeLockReader;

    // the hub's calibration state (hubconnection.cpp)
    float mAccelBias[3] = {}, mGyroBias[3] = {}, mMagBias[3] = {};
    SensorStatus mMagAccuracy = SensorStatus::UNRELIABLE;
    SensorStatus mMagAccuracyRestore = SensorStatus::ACCURACY_LOW;
};

}  // namespace bullhead
