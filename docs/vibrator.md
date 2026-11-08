# Vibrator

The vibrator works through Android's normal path: notifications, calls,
touch feedback and apps (`Vibrator` / `VibratorManager`), with amplitude
control and the standard click effects.

```
 apps, haptic feedback, notifications     Vibrator / VibratorManager
        │ VibratorManagerService (GSI)
        │ AIDL android.hardware.vibrator.IVibrator/default, V1
 hal/vibrator (vendor, ours)   /vendor/bin/hw/android.hardware.vibrator-service.bullhead
        │ force feedback: EVIOCSFF (FF_RUMBLE) + EV_FF play/stop on /dev/input/eventN
 drivers/input/misc/pmi8994-haptics.c   (patches/0006, ours)
        │ SPMI registers (0xc000…)
 PMI8994 haptics block → LRA motor (~230 Hz)
```

## The hardware

The Nexus 5X has an **LRA** (linear resonant actuator) motor driven by the
**PMI8994's haptics block**. Stock 3.10 drove it with `qpnp-haptic`
(drivers/platform/msm, 2,370 lines). Mainline has no driver for this block:
`pm8xxx-vibrator` covers only the older, simpler vibrator blocks.

Stock DT (`qcom,haptic@c000`, enabled and tuned in `msm8992-bullhead.dtsi`):

| | |
|---|---|
| actuator | LRA, auto-resonance "QWD", high-Z option 1, calibration every 4 periods |
| play mode | direct (the block drives the motor while PLAY is set) |
| resonance period | 4347 µs (about 230 Hz) |
| drive voltage | 1800 mV ("vmax-mv"); 2500 mV ("vmax-mv-ind") for vibrations of 800 ms or longer: calls, alarms |
| current limit | 400 mA |
| other | sine wave, 505 kHz PWM, short-circuit debounce 8 cycles, active brake `[03 03 03 03]` |

## Kernel

[patches/0006](../patches/0006-Input-add-the-Qualcomm-PMI8994-haptics-LRA-vibrator.patch)
adds `drivers/input/misc/pmi8994-haptics.c` (~310 lines). It is a
force-feedback input device (`FF_RUMBLE` through `ff-memless`), the
mainline way to expose a vibrator, modelled on `pm8xxx-vibrator`:

- **At probe** it writes the stock configuration above, register by register
  as `qpnp_hap_config()` did.
- **To play** it enables the module and sets PLAY, as `qpnp_hap_set()` did:
  - auto-resonance is held off for the first 20 ms, then turned back on
    (`TEST2`, behind the peripheral's secure-access unlock)
  - a latched short circuit is cleared first
  - the effect's strength sets the drive voltage (116 mV steps), up to
    `qcom,vmax-mv`
- **To stop** it clears PLAY, waits for the brake (STATUS busy), then
  disables the module.
- Not ported: buffer, PWM and audio play modes, and stock's driver-side LRA
  frequency correction. The PMIC's own auto-resonance stays on.

DT: [dts/bullhead-vibrator.dtsi](../dts/bullhead-vibrator.dtsi) puts the
node on the PMI8994's second SPMI ID (`pmic@3`; the stock interrupt
specifier is `<0x3 0xc0 …>`), with `qcom,vmax-mv = <2500>` as full scale,
the 400 mA limit and the 4347 µs period.

**Testing without Android**: `vibrate [ms] [strength%]` in the ramdisk's
Kotlin tools ([tools/phone/Vibrate.kt](../tools/phone/Vibrate.kt)) uploads
a rumble effect and plays it, from the rescue shell:

```
$ scripts/tsh.main.kts '/sbin/vibrate 300 72; /sbin/vibrate 1000 100; /sbin/vibrate 200 40'
vibrating 300 ms at 72%
vibrating 1000 ms at 100%
vibrating 200 ms at 40%
```

Felt on the phone. Afterwards the block is idle (STATUS `00`, EN `00`, PLAY
`00`), and VMAX holds 8 steps (~930 mV): the last pulse's 40 % of 2500 mV.

## Android

### The HAL: hal/vibrator

[hal/vibrator/Vibrator.cpp](../hal/vibrator/Vibrator.cpp) is an AIDL
`IVibrator` **V1** HAL (~260 lines). V1 is the simplest version Android 17
accepts (1–4) and has no imports. It is built like the others by
[build-hal.main.kts](../scripts/build-hal.main.kts).

- **The effect.** It finds the `pmi8994-haptics` input device, uploads one
  rumble effect with no length, and plays and stops it itself, so it can
  call `onComplete()` when done.
- **`on(ms)`**: like stock, under 800 ms uses the haptic level (1800 of
  2500 mV), longer uses full strength (2500 mV), each times the amplitude.
- **`setAmplitude()`** updates a running vibration.
- **`perform()`**: click 18 ms, tick 10 ms, texture tick 6 ms, pop 14 ms,
  heavy click 30 ms, thud 40 ms, double click 18 + 100 + 18 ms, scaled by
  the requested strength.
- Composition, external control and always-on effects are not supported (V1
  lets a HAL say so).
- It runs as `system`, with group `input` for `/dev/input`.

### In the vendor image

- the binary, its `.rc` and VINTF fragment (IVibrator/default, version 1)
- a `file_contexts` line giving it the existing `hal_vibrator_default_exec`
  label
- **The emulator's vibrator APEX** (`com.android.hardware.vibrator.apex`,
  AOSP's example, IVibrator V4, does nothing) is **removed**. See below for
  why.

## Testing

```
$ adb shell dumpsys vibrator_manager
  Default HAL Vibrator (id=0):
    capabilities = [ON_CALLBACK, PERFORM_CALLBACK, AMPLITUDE_CONTROL]
    supportedEffects = [CLICK, DOUBLE_CLICK, TICK, THUD, POP, HEAVY_CLICK, TEXTURE_TICK]
$ adb shell cmd vibrator_manager synced -f oneshot 500      # and 1500, prebaked 0 / 1 / 5
```

| Vibration | `dumpsys vibrator_manager` |
|---|---|
| one-shot 500 ms | finished, 508 ms |
| one-shot 1500 ms (full strength) | finished, 1507 ms |
| CLICK / DOUBLE_CLICK / HEAVY_CLICK | finished, 23 / 142 / 35 ms |

Touch feedback: `settings put system haptic_feedback_enabled 1`.

## What it took

- **Two HALs for one instance make the whole manifest invalid.** The first
  vendor image still had the emulator's vibrator APEX. Its fragment declares
  `IVibrator/default` @4, ours @1. hwservicemanager then refused the
  **entire** device manifest ("Conflicting FqInstance … Check whether or
  not multiple modules providing the same HAL are installed"), our HAL's
  `addService` failed, and Android never finished booting. Removing the APEX
  fixed it. The rescue shell (telnet) was untouched by this, so recovery was
  just a re-flash.
- RPM-failure boots (README: Boot reliability) also hit RAM-boot tests:
  after the ramdisk's automatic restart the phone comes back on the
  *flashed* image, so the test is simply repeated.

## Not done yet

- **Composed effects** (`compose()`, primitives) and IVibrator V2+ features
  (resonant frequency, PWLE). Apps fall back to on/off and the effects above.
- **LRA frequency correction.** Stock tracked the motor's actual resonance
  and retuned the drive period; here the PMIC's auto-resonance does it alone.
