// SPDX-License-Identifier: Apache-2.0
// /vendor/bin/hw/android.hardware.sensors-service: the Nexus 5X sensors HAL
// (Sensors.h), registered as android.hardware.sensors.ISensors/default.
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>

#include "Sensors.h"

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);
    auto sensors = ndk::SharedRefBase::make<bullhead::Sensors>();
    if (!sensors->start()) return 1;
    const std::string name = std::string(bullhead::Sensors::descriptor) + "/default";
    if (AServiceManager_addService(sensors->asBinder().get(), name.c_str()) != STATUS_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "sensors.bullhead", "addService %s failed", name.c_str());
        return 1;
    }
    ABinderProcess_joinThreadPool();
    return 1;  // not reached
}
