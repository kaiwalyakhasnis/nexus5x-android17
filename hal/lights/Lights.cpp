// SPDX-License-Identifier: Apache-2.0
// The Nexus 5X lights HAL (AIDL android.hardware.light V2), on sysfs:
//
//   BACKLIGHT      /sys/class/backlight/*   the panel (PMI8994 WLED, 0..4095)
//   NOTIFICATIONS  /sys/class/leds/rgb:*    the RGB LED (PMI8994 LPG,
//   ATTENTION                               dts/bullhead-leds.dtsi)
//
// Notifications and attention (incoming call) share the LED; attention
// wins while it is on. Blinking (FlashMode TIMED) uses the kernel's timer
// trigger. Built with the NDK only by scripts/build-hal.main.kts.
#include <aidl/android/hardware/light/BnLights.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>

#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "lights.bullhead"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using aidl::android::hardware::light::BnLights;
using aidl::android::hardware::light::FlashMode;
using aidl::android::hardware::light::HwLight;
using aidl::android::hardware::light::HwLightState;
using aidl::android::hardware::light::LightType;
using ndk::ScopedAStatus;

namespace {

bool writeFile(const std::string& path, const std::string& value) {
    int fd = open(path.c_str(), O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        ALOGW("open %s: %s", path.c_str(), strerror(errno));
        return false;
    }
    ssize_t n = write(fd, value.data(), value.size());
    close(fd);
    if (n != static_cast<ssize_t>(value.size())) {
        ALOGW("write %s = %s: %s", path.c_str(), value.c_str(), strerror(errno));
        return false;
    }
    return true;
}

std::string readFile(const std::string& path) {
    char buf[128];
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "";
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return "";
    buf[n] = 0;
    std::string s(buf);
    while (!s.empty() && (s.back() == '\n' || s.back() == ' ')) s.pop_back();
    return s;
}

/** The first entry of a sysfs class directory whose name starts with prefix */
std::string findIn(const char* dir, const char* prefix) {
    std::string found;
    if (DIR* d = opendir(dir)) {
        while (dirent* e = readdir(d)) {
            if (e->d_name[0] == '.' || strncmp(e->d_name, prefix, strlen(prefix)) != 0) continue;
            found = std::string(dir) + "/" + e->d_name;
            break;
        }
        closedir(d);
    }
    return found;
}

int r(int c) { return (c >> 16) & 0xff; }
int g(int c) { return (c >> 8) & 0xff; }
int b(int c) { return c & 0xff; }

enum Id : int { BACKLIGHT_ID = 0, NOTIFICATIONS_ID = 1, ATTENTION_ID = 2 };

class Lights : public BnLights {
  public:
    Lights() {
        mBacklight = findIn("/sys/class/backlight", "");
        if (!mBacklight.empty()) mBacklightMax = std::max(1, atoi(readFile(mBacklight + "/max_brightness").c_str()));
        mLed = findIn("/sys/class/leds", "rgb:");
        if (!mLed.empty()) {
            mLedMax = std::max(1, atoi(readFile(mLed + "/max_brightness").c_str()));
            // the order of multi_intensity's values, e.g. "blue green red"
            std::string index = readFile(mLed + "/multi_index");
            size_t pos = 0;
            for (int i = 0; i < 3; i++) {
                size_t end = index.find(' ', pos);
                std::string c = index.substr(pos, end == std::string::npos ? std::string::npos : end - pos);
                mOrder[i] = c == "red" ? 0 : c == "green" ? 1 : 2;
                pos = end == std::string::npos ? index.size() : end + 1;
            }
        }
        ALOGI("backlight %s (max %d), LED %s (max %d)", mBacklight.empty() ? "none" : mBacklight.c_str(),
              mBacklightMax, mLed.empty() ? "none" : mLed.c_str(), mLedMax);
    }

    ScopedAStatus getLights(std::vector<HwLight>* lights) override {
        if (!mBacklight.empty()) lights->push_back({BACKLIGHT_ID, 0, LightType::BACKLIGHT});
        if (!mLed.empty()) {
            lights->push_back({NOTIFICATIONS_ID, 0, LightType::NOTIFICATIONS});
            lights->push_back({ATTENTION_ID, 0, LightType::ATTENTION});
        }
        return ScopedAStatus::ok();
    }

    ScopedAStatus setLightState(int id, const HwLightState& state) override {
        std::lock_guard<std::mutex> lock(mLock);
        switch (id) {
            case BACKLIGHT_ID: {
                if (mBacklight.empty()) break;
                // the framework sends a grey: its level is the brightness
                int c = state.color;
                int level = (77 * r(c) + 150 * g(c) + 29 * b(c)) >> 8;
                int value = (level * mBacklightMax + 127) / 255;
                if (level > 0 && value == 0) value = 1;
                writeFile(mBacklight + "/brightness", std::to_string(value));
                return ScopedAStatus::ok();
            }
            case NOTIFICATIONS_ID:
                mNotification = state;
                updateLed();
                return ScopedAStatus::ok();
            case ATTENTION_ID:
                mAttention = state;
                updateLed();
                return ScopedAStatus::ok();
        }
        return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

  private:
    static bool lit(const HwLightState& s) { return (s.color & 0xffffff) != 0; }

    void updateLed() {
        if (mLed.empty()) return;
        const HwLightState& s = lit(mAttention) ? mAttention : mNotification;
        int c = s.color;
        if (!lit(s)) {
            writeFile(mLed + "/trigger", "none");
            writeFile(mLed + "/brightness", "0");
            return;
        }
        // the colour goes in multi_intensity, at full brightness: the
        // framework already scales the colour (alpha is unused)
        int rgb[3] = {r(c), g(c), b(c)};
        std::string mi;
        for (int i = 0; i < 3; i++) mi += (i ? " " : "") + std::to_string(rgb[mOrder[i]] * mLedMax / 255);
        writeFile(mLed + "/trigger", "none");
        writeFile(mLed + "/multi_intensity", mi);
        writeFile(mLed + "/brightness", std::to_string(mLedMax));
        if (s.flashMode != FlashMode::NONE && s.flashOnMs > 0 && s.flashOffMs > 0) {
            // timer trigger: the kernel blinks it (delay_* appear once it is set)
            writeFile(mLed + "/trigger", "timer");
            writeFile(mLed + "/delay_on", std::to_string(s.flashOnMs));
            writeFile(mLed + "/delay_off", std::to_string(s.flashOffMs));
        }
    }

    std::mutex mLock;
    std::string mBacklight, mLed;
    int mBacklightMax = 255, mLedMax = 255;
    int mOrder[3] = {0, 1, 2};  // multi_intensity position -> 0 red, 1 green, 2 blue
    HwLightState mNotification, mAttention;
};

}  // namespace

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);
    auto lights = ndk::SharedRefBase::make<Lights>();
    const std::string name = std::string(Lights::descriptor) + "/default";
    if (AServiceManager_addService(lights->asBinder().get(), name.c_str()) != STATUS_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "addService %s failed", name.c_str());
        return 1;
    }
    ABinderProcess_joinThreadPool();
    return 1;  // not reached
}
