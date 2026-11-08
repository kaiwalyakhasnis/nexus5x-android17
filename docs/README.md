# How each piece of hardware was brought to Android

One page per integrated service: what the hardware is, the kernel side, the
Android (vendor HAL) side, how it was tested, and what went wrong on the way.
The main [README](../README.md) has the overall picture (how the GSI, the
kernel and the vendor image fit together) and the build steps.

| Service | Page | Status |
|---|---|---|
| Sensors (accelerometer, gyroscope, compass, barometer, light, proximity, steps, fusion) | [sensors.md](sensors.md) | ✅ all 16 sensors through `SensorManager` |
| Lights (brightness slider, auto-brightness, notification LED) | [lights.md](lights.md) | ✅ |
| Wi-Fi (QCA6174, 2.4 + 5 GHz) | [wifi.md](wifi.md) | ✅ scans, connects, internet |
| Battery and charging (level, charging state, temperature zone) | [battery.md](battery.md) | ✅ level and state; ⚠️ charging only at USB 500 mA |
| Vibrator (LRA motor: notifications, calls, touch feedback) | [vibrator.md](vibrator.md) | ✅ on/off, amplitude, click effects |

## Writing a new page

Keep the same outline, so the pages can be compared:

1. **The hardware**: what the phone has, and where that was found out (stock
   device tree, stock HAL sources in the LineageOS tree).
2. **Kernel**: driver (mainline or a port in `patches/`), device tree
   (`dts/*.dtsi`), config (`config/android.config`); how to test it from the
   rescue shell, without Android.
3. **Android**: the HAL in `hal/<name>/`, built by `scripts/build-hal.main.kts`
   with the SDK and NDK only; what `make-vendor.main.kts` adds to the vendor
   image (binary, init `.rc`, VINTF fragment, features, permissions).
4. **Testing**: the tool or app in `apps/` and what it showed.
5. **What it took**: the problems hit and how they were found.
6. **Not done yet**.
