# Wi-Fi

Wi-Fi works as on any Android phone: Settings scans 2.4 and 5 GHz networks,
connects with WPA2, gets an address by DHCP and has internet. The adb/telnet
link over USB stays up next to it.

```
 Settings / apps          WifiService (the Wi-Fi APEX, GSI)
        │ AIDL android.hardware.wifi.IWifi + wifi.supplicant.ISupplicant
 vendor: android.hardware.wifi-service, wpa_supplicant, hostapd
        │ nl80211 (generic Linux Wi-Fi API)
 kernel: cfg80211 + mac80211 + ath10k_pci        (mainline)
        │ PCIe (the base project's MSM8994 PCIe patches)
 QCA6174, with firmware-6.bin (linux-firmware) and the phone's own board.bin
```

## The hardware and the kernel

The Nexus 5X's Wi-Fi is a **QCA6174 on PCIe**. Stock used Qualcomm's
`qcacld-2.0` driver; mainline's `ath10k` drives the same chip.

**The kernel side was already done by the base project**
(`nexus5x-linux-6.12-wip`):

- its patches 0015–0020 add MSM8994 PCIe: clocks, the 20 nm PHY and the
  controller
- `bullhead-wifi.dtsi` adds the chip's power
- `cfg80211`, `mac80211` and `ath10k` are modules

Nothing in the kernel changed for Android.

What `ath10k` needs from the filesystem:

| File | From |
|---|---|
| `ath10k/QCA6174/hw3.0/firmware-6.bin` | linux-firmware (as the base project: tag 20241210) |
| `ath10k/QCA6174/hw3.0/board.bin` | **this phone's calibration**: `/firmware/bdwlan30.bin` on its stock vendor partition. It is phone-specific, so it is never committed: `make-vendor` copies it from the partition backup (`backup/vendor-mmcblk1p39.img`, or `STOCK_VENDOR=`) |
| `regulatory.db` (+ `.p7s`) | linux-firmware, for the radio's country rules |

All three go in `/vendor/firmware`, the kernel's `firmware_class.path`.

### When ath10k is loaded

The ramdisk's `/init` loads the kernel modules from the vendor partition
before Android starts. At that point the kernel resolves
`/vendor/firmware` from the ramdisk's root, where it doesn't exist yet,
and `ath10k` asks for its firmware the moment it probes. So
[make-vendor.main.kts](../scripts/make-vendor.main.kts) leaves `ath10k_core`
and `ath10k_pci` out of `modules.load`. Instead Android's init loads them
in `on post-fs`, once Android's root is `/`
([vendor/wifi/init.wifi.bullhead.rc](../vendor/wifi/init.wifi.bullhead.rc);
`make-vendor` fills in the kernel release). Their dependencies (`cfg80211`,
`mac80211`, `ath`) are already loaded by then. `rootfs/init` is unchanged.

## Android

The emulator's vendor image already contains **Android's generic Wi-Fi
stack**:

- `android.hardware.wifi-service`, AOSP's Wi-Fi HAL
- its legacy library `libwifi-hal.so` (`wifi_emu_hal`: plain netlink/nl80211,
  nothing emulator-specific)
- `wpa_supplicant` and `hostapd`

`make-vendor` used to remove them along with the other emulator-only HALs.
Now it keeps them. It only adds what the emulator's `init.ranchu.rc` (which
`init.bullhead.rc` replaces) used to provide:

- the `wpa_supplicant` service: `-Dnl80211 -iwlan0`, AIDL `ISupplicant/default`
- the `/data/vendor/wifi` directories

The feature `android.hardware.wifi` stays. Wi-Fi Direct (needs a `p2p0`
interface) and Passpoint are left out for now.

## Testing

```
$ adb shell cmd wifi set-wifi-enabled enabled
$ adb shell cmd wifi start-scan; adb shell cmd wifi list-scan-results
  BSSID              Frequency  RSSI  SSID           Flags
  00:2b:f5:27:10:39  5180       -64   Buffalo-A-…    [WPA2-PSK-CCMP-128]…
  1a:e4:ce:0d:23:f0  5220       -31   JCOM_DEIG      [WPA2-PSK-CCMP-128]…
  94:09:37:70:6a:e4  2412       -48   HUMAX-…        [WPA2-PSK-CCMP-128+TKIP]…
  …                                                  (11 networks)
```

Connected from Settings (password typed on the phone):

```
$ adb shell cmd wifi status
Wifi is connected to "JCOM_DEIG"
$ adb shell ip -4 addr show wlan0
    inet 192.168.40.99/24 … dynamic wlan0
$ adb shell ping -c 3 8.8.8.8
3 packets transmitted, 3 received, 0% packet loss … avg 29.9 ms
$ adb shell ping -c 2 google.com
64 bytes from … (142.251.24.138): icmp_seq=1 ttl=110 time=24.2 ms
```

After a reboot it reconnects by itself.

## Not done yet

- Wi-Fi Direct and the hotspot (hostapd is there; untested)
- Wi-Fi's own power saving and wake-on-WLAN: the phone never suspends anyway
  (README: Suspend)
