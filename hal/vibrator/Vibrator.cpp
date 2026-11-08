// SPDX-License-Identifier: Apache-2.0
// The Nexus 5X vibrator HAL (AIDL android.hardware.vibrator V1) on the
// kernel's force-feedback device "pmi8994-haptics" (patches/0006): an LRA
// motor on the PMI8994. A rumble effect is uploaded once and played with no
// length; this HAL times it (so it can call back when done).
//
// Strength: the driver's full scale is the stock "indicator" voltage
// (2500 mV, calls and alarms); like the stock driver, vibrations shorter
// than 800 ms get the haptic one (1800 mV), times the amplitude.
// Effects: the common clicks and ticks, as short pulses.
// Built with the NDK only by scripts/build-hal.main.kts.
#include <aidl/android/hardware/vibrator/BnVibrator.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "vibrator.bullhead"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using aidl::android::hardware::vibrator::BnVibrator;
using aidl::android::hardware::vibrator::CompositeEffect;
using aidl::android::hardware::vibrator::CompositePrimitive;
using aidl::android::hardware::vibrator::Effect;
using aidl::android::hardware::vibrator::EffectStrength;
using aidl::android::hardware::vibrator::IVibrator;
using aidl::android::hardware::vibrator::IVibratorCallback;
using ndk::ScopedAStatus;

namespace {

constexpr const char* kDevice = "pmi8994-haptics";
constexpr int kLongMs = 800;              // stock CALL_ALARM_TIME_THRESHOLD
constexpr float kHapticScale = 1800.0f / 2500.0f;  // stock vmax-mv / vmax-mv-ind

/** /dev/input/eventN of the input device called name, or "" */
std::string findInput(const char* name) {
    std::string found;
    if (DIR* d = opendir("/sys/class/input")) {
        while (dirent* e = readdir(d)) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            std::string path = std::string("/sys/class/input/") + e->d_name + "/device/name";
            char buf[64] = {};
            int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;
            ssize_t n = read(fd, buf, sizeof(buf) - 1);
            close(fd);
            if (n > 0 && strncmp(buf, name, strlen(name)) == 0 && (buf[strlen(name)] == '\n' || buf[strlen(name)] == 0)) {
                found = std::string("/dev/input/") + e->d_name;
                break;
            }
        }
        closedir(d);
    }
    return found;
}

/** A pulse pattern: on/off times in ms, starting with on */
struct Pattern {
    std::vector<int> ms;
    float strength;  // of the haptic level
};

Pattern effectPattern(Effect e) {
    switch (e) {
        case Effect::TEXTURE_TICK: return {{6}, 0.5f};
        case Effect::TICK:         return {{10}, 0.7f};
        case Effect::CLICK:        return {{18}, 1.0f};
        case Effect::POP:          return {{14}, 0.8f};
        case Effect::HEAVY_CLICK:  return {{30}, 1.0f};
        case Effect::THUD:         return {{40}, 1.0f};
        case Effect::DOUBLE_CLICK: return {{18, 100, 18}, 1.0f};
        default:                   return {{}, 0};
    }
}

class Vibrator : public BnVibrator {
  public:
    Vibrator() {
        std::string dev = findInput(kDevice);
        if (!dev.empty()) mFd = open(dev.c_str(), O_RDWR | O_CLOEXEC);
        if (mFd < 0)
            ALOGW("no %s input device (%s): vibration does nothing", kDevice, dev.c_str());
        else
            ALOGI("%s on %s", kDevice, dev.c_str());
    }

    ScopedAStatus getCapabilities(int32_t* caps) override {
        *caps = static_cast<int32_t>(IVibrator::CAP_ON_CALLBACK) |
                static_cast<int32_t>(IVibrator::CAP_PERFORM_CALLBACK) |
                static_cast<int32_t>(IVibrator::CAP_AMPLITUDE_CONTROL);
        return ScopedAStatus::ok();
    }

    ScopedAStatus off() override {
        mGeneration++;
        std::lock_guard<std::mutex> lock(mLock);
        stopLocked();
        return ScopedAStatus::ok();
    }

    ScopedAStatus on(int32_t timeoutMs, const std::shared_ptr<IVibratorCallback>& cb) override {
        if (timeoutMs <= 0) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        float scale = timeoutMs >= kLongMs ? 1.0f : kHapticScale;
        run({{timeoutMs}, scale}, cb, true);
        return ScopedAStatus::ok();
    }

    ScopedAStatus perform(Effect effect, EffectStrength strength,
                          const std::shared_ptr<IVibratorCallback>& cb, int32_t* ms) override {
        Pattern p = effectPattern(effect);
        if (p.ms.empty()) return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        float s = strength == EffectStrength::LIGHT ? 0.5f : strength == EffectStrength::MEDIUM ? 0.75f : 1.0f;
        p.strength *= s * kHapticScale;
        *ms = 0;
        for (int t : p.ms) *ms += t;
        run(p, cb, false);
        return ScopedAStatus::ok();
    }

    ScopedAStatus getSupportedEffects(std::vector<Effect>* ret) override {
        *ret = {Effect::CLICK, Effect::DOUBLE_CLICK, Effect::TICK, Effect::THUD,
                Effect::POP, Effect::HEAVY_CLICK, Effect::TEXTURE_TICK};
        return ScopedAStatus::ok();
    }

    ScopedAStatus setAmplitude(float amplitude) override {
        if (amplitude <= 0.0f || amplitude > 1.0f)
            return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        std::lock_guard<std::mutex> lock(mLock);
        mAmplitude = amplitude;
        if (mPlaying) upload(mScale * mAmplitude);  // changes the running one
        return ScopedAStatus::ok();
    }

    // not supported (V1 lets a HAL say so)
    ScopedAStatus setExternalControl(bool) override { return unsupported(); }
    ScopedAStatus getCompositionDelayMax(int32_t*) override { return unsupported(); }
    ScopedAStatus getCompositionSizeMax(int32_t*) override { return unsupported(); }
    ScopedAStatus getSupportedPrimitives(std::vector<CompositePrimitive>* ret) override {
        ret->clear();
        return ScopedAStatus::ok();
    }
    ScopedAStatus getPrimitiveDuration(CompositePrimitive, int32_t*) override { return unsupported(); }
    ScopedAStatus compose(const std::vector<CompositeEffect>&,
                          const std::shared_ptr<IVibratorCallback>&) override { return unsupported(); }
    ScopedAStatus getSupportedAlwaysOnEffects(std::vector<Effect>* ret) override {
        ret->clear();
        return ScopedAStatus::ok();
    }
    ScopedAStatus alwaysOnEnable(int32_t, Effect, EffectStrength) override { return unsupported(); }
    ScopedAStatus alwaysOnDisable(int32_t) override { return unsupported(); }

  private:
    static ScopedAStatus unsupported() { return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION); }

    /** Play a pattern on a thread of its own; a newer call or off() ends it. */
    void run(Pattern p, std::shared_ptr<IVibratorCallback> cb, bool useAmplitude) {
        unsigned gen = ++mGeneration;
        std::thread([this, p, cb, useAmplitude, gen] {
            for (size_t i = 0; i < p.ms.size(); i++) {
                {
                    std::lock_guard<std::mutex> lock(mLock);
                    if (mGeneration != gen) return;  // superseded: no callback
                    if (i % 2 == 0) {
                        mScale = p.strength;
                        start(useAmplitude ? p.strength * mAmplitude : p.strength);
                    } else {
                        stopLocked();
                    }
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(p.ms[i]));
            }
            {
                std::lock_guard<std::mutex> lock(mLock);
                if (mGeneration != gen) return;
                stopLocked();
            }
            if (cb) cb->onComplete();
        }).detach();
    }

    /** Upload (or update) the rumble effect: magnitude 0..1 of full scale */
    bool upload(float magnitude) {
        if (mFd < 0) return false;
        ff_effect e{};
        e.type = FF_RUMBLE;
        e.id = static_cast<int16_t>(mEffectId);
        e.replay.length = 0;  // until stopped
        e.u.rumble.strong_magnitude = static_cast<uint16_t>(std::min(1.0f, magnitude) * 0xffff);
        if (ioctl(mFd, EVIOCSFF, &e) < 0) {
            ALOGW("EVIOCSFF: %s", strerror(errno));
            return false;
        }
        mEffectId = e.id;
        return true;
    }

    void play(bool on) {
        input_event ev{};
        ev.type = EV_FF;
        ev.code = static_cast<uint16_t>(mEffectId);
        ev.value = on ? 1 : 0;
        if (write(mFd, &ev, sizeof(ev)) != sizeof(ev)) ALOGW("EV_FF: %s", strerror(errno));
    }

    void start(float magnitude) {  // mLock held
        if (upload(magnitude)) {
            play(true);
            mPlaying = true;
        }
    }

    void stopLocked() {
        if (mFd < 0 || !mPlaying) return;
        play(false);
        mPlaying = false;
    }

    int mFd = -1;
    int mEffectId = -1;
    std::mutex mLock;
    std::atomic<unsigned> mGeneration{0};
    bool mPlaying = false;
    float mAmplitude = 1.0f;
    float mScale = 1.0f;
};

}  // namespace

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();
    auto vibrator = ndk::SharedRefBase::make<Vibrator>();
    const std::string name = std::string(Vibrator::descriptor) + "/default";
    if (AServiceManager_addService(vibrator->asBinder().get(), name.c_str()) != STATUS_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "addService %s failed", name.c_str());
        return 1;
    }
    ABinderProcess_joinThreadPool();
    return 1;  // not reached
}
