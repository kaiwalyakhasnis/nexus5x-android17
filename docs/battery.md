# Battery and charging

Android shows the battery level in the status bar and Settings, says whether
it is charging, and tells USB power from battery. The battery charges
through the PMIC's own hardware; Android only reads its state.

```
 status bar / Settings / apps (BatteryManager)
        │ BatteryService (GSI)
 health HAL (the emulator's, generic: it reads /sys/class/power_supply)
        │ sysfs
 /sys/class/power_supply/{battery,usb}
        │ drivers/power/supply/pmi8994_charger_status.c  (patches/0005, read-only)
 PMI8994: charger (SMBCHG, 0x1000…) + fuel gauge (FG, 0x4000…) registers
```

## The hardware

The PMI8994 PMIC holds the **charger** (SMBCHG) and the **fuel gauge** (FG).
Stock 3.10 drove them with `qpnp-smbcharger` and `qpnp-fg`, about 12,000
lines between them. Mainline has neither.

Both keep working without a driver:

- **The charger charges by itself**, from its power-on defaults: USB input in
  500 mA mode, charge current up to 1000 mA, and the JEITA temperature
  limits.
- **The fuel gauge keeps counting the state of charge** inside the PMIC, with
  the battery profile the last stock boot loaded into its SRAM. That SRAM
  keeps it as long as the battery stays connected.

This was found by reading the registers the stock drivers read, one at a time,
from the rescue shell (`/sys/kernel/debug/regmap/0-02/registers`, offset =
register × 9 bytes):

| Register | Read | Meaning (stock driver's decoding) |
|---|---|---|
| `0x1310` USB_CHGPTH RT_STS | `04` | USB power present, no under/over-voltage |
| `0x100e` CHGR_STS | `85` | charge type 2: fast charging |
| `0x1010` CHGR RT_STS | `10` | not done, not inhibited |
| `0x1208` BAT_PRES_STATUS | `80` | battery present |
| `0x1210` BAT_IF RT_STS | `02` | JEITA: warm (soft limit) |
| `0x4009` / `0x400a` FG monotonic SOC | `a2` / `a2` | 162/255 = 64 % (the two copies agree) |
| `0x1340` CMD_IL | `02` | USB 500 mA mode (USB 2.0's budget) |
| `0x10f2` FCC_CFG | `0b` | fast charge current up to 1000 mA |

**Warning: never dump the whole map.** Reading all of
`/sys/kernel/debug/regmap/0-02/registers` (64K registers, including empty or
secure ones) reset the phone and put it into a run of RPM-failure boots.
Read only known registers, one line at a time.

## Kernel

[patches/0005](../patches/0005-power-supply-PMI8994-charger-and-fuel-gauge-status-r.patch)
adds `drivers/power/supply/pmi8994_charger_status.c` (~300 lines).

It **only reads** those registers (it never writes the PMIC) and registers
two power supplies:

| Supply | Property | From |
|---|---|---|
| `battery` | `capacity` | FG monotonic SOC × 100 / 255 (retries until both copies agree) |
| | `status` | Charging / Full / Not charging / Discharging (stock's `get_prop_batt_status`) |
| | `charge_type` | Trickle / Fast / Standard (taper) |
| | `health` | Good / Warm / Cool / Overheat / Cold (the JEITA bits) |
| | `present`, `technology` | BAT_PRES, Li-ion |
| `usb` | `online` | USBIN detected, no UV/OV |

The interrupts belong to a real charger driver, so this one polls instead:
every 5 s it calls `power_supply_changed()` when something changed, which
sends Android a uevent.

DT: [dts/bullhead-battery.dtsi](../dts/bullhead-battery.dtsi), a node in
the PMI8994 (`pmic@2`), `reg = <0x1000>, <0x4000>` (the charger and FG SOC
bases, from the stock DT). Config: `CONFIG_CHARGER_PMI8994_STATUS=y`.

## Android

Nothing to add. The emulator's health HAL is AOSP's generic one: it reads
`/sys/class/power_supply/*` (healthd's BatteryMonitor). With these two
supplies present, Android went from "battery not present, 0 %" to:

```
$ adb shell dumpsys battery
  USB powered: true
  status: 2            (charging)
  health: 2            (good; the kernel's "Warm" counts as good)
  present: true
  level: 62
  technology: Li-ion
```

The status bar shows the level with the charging bolt.

## What it showed: charging is slow, and heat stops it

- **500 mA of USB input** is about 2.5 W. With the screen on and the CPU busy,
  the phone uses about as much, so the level can drop while "charging".
- **Heat.** After a series of reboots with the screen kept on, the battery
  reached the JEITA **hard** hot limit (`0x1210` = `03`). The PMIC stopped
  charging by itself, and Android showed "Not charging · Overheat"
  correctly. Once it cooled (screen off), charging resumed: "Charging · Fast
  · Warm".

## Not done yet

- **Faster charging.** Detecting a wall charger and switching the input to
  high-current mode (1.5–2 A) needs a real charger driver *writing* the
  PMIC (APSD result → `CMD_IL`, `IL_CFG`). Do it carefully, or not at all.
- **Battery voltage, current and temperature** live in the fuel gauge's
  SRAM, which is reached through its memory interface (`0x4400`). That
  access involves writes, so they read 0 in Android for now.
- **Charge-full and low-battery handling** are the hardware's: Android
  shuts down at 0 %, as usual.
