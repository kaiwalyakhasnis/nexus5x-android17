# Lights: brightness, auto-brightness, notification LED

The brightness slider, automatic brightness (from the light sensor) and the
notification LED work through Android's normal paths. The emulator's lights
HAL, which only logged, is replaced by ours.

```
 Settings slider / auto-brightness        a notification with a light colour
        │ DisplayManager                         │ NotificationManagerService
        └────────────► LightsService ◄───────────┘
                          │ AIDL android.hardware.light.ILights/default, V2
 hal/lights (vendor, ours)   /vendor/bin/hw/android.hardware.lights-service.bullhead
        │ sysfs
        ├─ /sys/class/backlight/backlight      PMI8994 WLED, 0..4095 (mainline qcom-wled)
        └─ /sys/class/leds/rgb:status          PMI8994 LPG RGB LED (mainline leds-qcom-lpg)
```

## The hardware

| | Chip | Stock source (LineageOS tree) |
|---|---|---|
| Panel backlight | PMI8994 WLED | already working in the base project (`bullhead-display.dtsi`) |
| Notification LED | PMI8994 LPG + TRILED, one RGB LED near the earpiece | `qcom,leds@d000` "rgb" in the stock DT: red on LPG channel 3 (`pwm@b300`), green 2, blue 1, powered from VPH_PWR |

## Kernel

- [dts/bullhead-leds.dtsi](../dts/bullhead-leds.dtsi) enables the PMI8994
  LPG as one multicolor LED. It uses the stock channel mapping, which is the
  same as the mainline Xiaomi msm8996 boards on the same PMIC, and
  `qcom,power-source = <1>` (VPH_PWR, the stock driver's
  `RGB_LED_SOURCE_VPH_PWR`).
- `CONFIG_LEDS_CLASS_MULTICOLOR=y` and `CONFIG_LEDS_QCOM_LPG=y` in
  [config/android.config](../config/android.config). Both drivers are
  mainline; no patch.
- Result: `/sys/class/leds/rgb:status`. `multi_index` gives the order
  "blue green red", `multi_intensity` the three values, and `brightness` is
  0–511. The `timer` trigger blinks it in hardware.

Test without Android, from the rescue shell:
`echo "0 0 511" > /sys/class/leds/rgb:status/multi_intensity; echo 511 > /sys/class/leds/rgb:status/brightness`.

## Android

### The HAL: hal/lights

[hal/lights/Lights.cpp](../hal/lights/Lights.cpp) is a small AIDL
`ILights` V2 HAL (~200 lines), built like the sensors HAL by
[scripts/build-hal.main.kts](../scripts/build-hal.main.kts): SDK `aidl`,
NDK clang, the GSI's `libbinder_ndk`. It offers three lights:

| Light | Writes |
|---|---|
| `BACKLIGHT` | the framework sends a grey, so its level × 4095 / 255 goes to `backlight/brightness` |
| `NOTIFICATIONS`, `ATTENTION` (incoming call) | the colour goes to `multi_intensity` in the LED's own channel order, at full `brightness`; blinking (`FlashMode.TIMED`) uses the `timer` trigger with `delay_on` / `delay_off`. Attention wins while it is on |

Both sysfs nodes are found at start-up, not hard-coded.

Two details:

- **Permissions.** The sysfs files belong to root (0644), and the timer
  trigger's `delay_on` / `delay_off` only appear once blinking starts. The
  HAL runs as `system` with `capabilities DAC_OVERRIDE` (in its `.rc`), so it
  can write them, instead of listing `chown` lines for files that don't
  exist yet.
- **SELinux label.** The emulator's policy has no entry for our binary's
  name. [make-vendor.main.kts](../scripts/make-vendor.main.kts) now appends
  our own lines to the vendor `file_contexts` (`OUR_FILE_CONTEXTS`), giving it
  the existing `hal_light_default_exec` type. This is the general way to add
  further HALs with new names.

### The framework settings: vendor/overlay-lights

[vendor/overlay-lights](../vendor/overlay-lights/res/values/config.xml) is
a static overlay on `android`, with stock bullhead's values:

- `config_intrusiveNotificationLed`, 1 s on and 9 s off: the notification
  LED (Settings shows "Blink light")
- `config_automatic_brightness_available` with stock's lux →
  backlight curve (`config_autoBrightnessLevels`,
  `config_autoBrightnessLcdBacklightValues`): auto-brightness from the
  RPR0521 light sensor (see [sensors](sensors.md))

Every name was checked against Android 17's `framework-res.apk`.
`config_multiColorNotificationLed`, which stock had, no longer exists.

## Testing

[apps/lightstest](../apps/lightstest/) (`LightsTest.apk`, built by
[build-apps.main.kts](../scripts/build-apps.main.kts)) uses the public API
only:

- **a brightness slider** for its own window, which goes through
  DisplayManager → LightsService → the HAL's `BACKLIGHT`
- **notification LED buttons**: red/green/blue/white, steady or blinking.
  Each posts a notification on a channel with that light colour. Android
  lights the LED only while the screen is off, so post, then press power.

```sh
adb shell am start -n dev.nexus5x.lightstest/.MainActivity --es led red --ez blink true
adb shell input keyevent KEYCODE_SLEEP
cat /sys/class/leds/rgb:status/{trigger,multi_intensity,delay_on}   # rescue shell
```

Results:

| | Seen |
|---|---|
| system brightness setting 10 / 60 / 128 / 200 / 255 | backlight 161 / 964 / 2056 / 3212 / 4095 of 4095 |
| auto-brightness on | light sensor enabled, stock curve loaded (`dumpsys display`), backlight follows the ambient level |
| red blinking notification, screen off | `dumpsys lights`: id 1 `ffff0000`; LED `multi_intensity 0 0 511` (blue green red), trigger `[timer]` |
| screen off | backlight 0 |

The "odd" values seen at first were the screen **dimming for inactivity**:
Android ignores brightness changes while dimmed.

## Not done yet

- **Blink timing.** The LPG's hardware blink rounds the timings to what it
  supports: about 1 s on and 0.25 s off for a requested 1 s / 2 s.
- **Battery LED.** The `BATTERY` light isn't offered. There is now a battery
  level ([battery](battery.md)), so it could be added.
- **The light sensor** has only ever reported 0 lux so far. That may be a
  dark room; it is still to be checked with a lamp.
