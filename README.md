# Android 17 on the Nexus 5X (mainline Linux 6.12)

> **Disclaimer.** This is an experiment: I'm trying out what Claude can do
> with an old phone. The scripts, patches and text were written with Claude.
> They boot on one Nexus 5X, but they may be wrong, unsafe or complete
> garbage, and nothing here has been reviewed by anyone who knows these
> drivers. Read it as notes from an experiment, not a reference, and use it at
> your own risk.

Google's official **Android 17 GSI** (`aosp_arm64-exp`, build
`CP3A.260905.010`, a *user* build) running on the LG Nexus 5X (bullhead,
Snapdragon 808 / MSM8992). It
boots on the kernel.org **Linux 6.12** port from
[nexus5x-linux-6.12-ubuntu-26.04LTS](https://github.com/kaiwalyakhasnis/nexus5x-linux-6.12-ubuntu-26.04LTS),
and the **Adreno 418 GPU** draws it with **Mesa freedreno**, built here with
the Android NDK. No AOSP tree and no framework build: the PC this was made on
is far too slow for that.

| Lock screen | Home screen (round icons, 3-button nav) | Settings → About phone |
|:---:|:---:|:---:|
| <img src="media/lockscreen.png" width="260"> | <img src="media/home-round-icons.png" width="260"> | <img src="media/about-android17.png" width="260"> |

<img src="media/bubble-contacts.gif" width="300">

*Android 17's "bubble any app": Contacts opened from the app drawer as a
bubble, expanded, then collapsed to the edge. Recorded on the phone with
`screenrecord` ([mp4](media/bubble-contacts.mp4)); it's choppy because the
video is encoded in software (no hardware encoder yet), the screen itself is
smoother. [Gallery as a bubble](media/bubble-gallery.gif).*

## What works and what doesn't

| | Part | How |
|---|---|---|
| ✅ | Official Android 17 GSI, user build, SELinux enforcing | GSI on `system`; every domain made permissive in the vendor policy (bring-up) |
| ✅ | Boot to lock screen and home screen, apps, Settings, bubbles | `sys.boot_completed=1`, system_server stable |
| ✅ | GPU rendering (SurfaceFlinger, HWUI) | Mesa 26.0.3 freedreno, `renderer: FD418`, OpenGL ES 3.1 |
| ✅ | Display on the panel | the emulator's HWC3 in "client" mode on the msm MDP5 (KMS) |
| ✅ | Touchscreen | mainline `rmi4` |
| ✅ | adb (over the USB network), rescue root shell | `adb connect 172.16.42.1:5555`, `telnet 172.16.42.1` |
| ✅ | KeyMint, Gatekeeper, health, power, thermal, lights (software HALs) | from Google's Android 17 emulator vendor image |
| ✅ | zram swap | half the RAM, compressed |
| ✅ | Boots by itself | `boot`, `system`, `vendor`, `userdata` all flashed on the eMMC; no PC needed |
| ⚠️ | Boot reliability | ~1 boot in 6 the RPM doesn't answer (base project's known issue), worse after button resets; the ramdisk detects it and restarts after 30 s (or `boot.sh` retries from the PC) |
| ❌ | Suspend | blocked with a wakelock: the phone never wakes from s2idle |
| ❌ | Wi-Fi, Bluetooth, modem, audio, camera, sensors, GNSS, battery level, RTC | no HAL yet (emulator ones removed) |
| ❌ | Hardware video codecs | software codecs only |

## How it fits together

```
 PC (USB cable)                         Nexus 5X
 ──────────────                         ───────────────────────────────────────────
 fastboot boot ─────────────────────▶   LK ─▶ Linux 6.12 + this project's ramdisk
                                              /init (rootfs/init) = first-stage init:
                                              USB network + rescue telnet, modules,
                                              mounts system + vendor, then
                                              chroot … /system/bin/init selinux_setup
 adb / telnet  ◀────  USB NCM  ────▶          │
                                              ▼
                                        eMMC:  system   = Android 17 GSI (as released)
                                               vendor   = out/vendor.img (below)
                                               userdata = /data
```

* **GKI doesn't fit.** A real GKI kernel (built in a side project) is 40 MiB
  in memory; the Nexus 5X's bootloader (LK) only loads ~29.5 MiB. So it's the
  mainline 6.12 kernel with Android's options added
  ([config/android.config](config/android.config)): binder, SELinux, PSI,
  cgroup v1 cpuset/memcg, EROFS, device-mapper, ... It comes to 27.9 MiB; the
  networking stack netd needs (IPv6, netfilter, xfrm, tc) is modules on
  `/vendor`, loaded by the ramdisk.
* **The vendor image** is the vendor partition of Google's Android 17 arm64
  *emulator* image: the only prebuilt Android 17 vendor there is, with the same
  SELinux version (202604) as the GSI and pure-software HALs that work on any
  hardware. [scripts/make-vendor.py](scripts/make-vendor.py) edits it with
  `debugfs` (no root needed) and labels every file it adds the way Android's
  file_contexts say: our init script and fstab, kernel modules, Mesa, the A420
  microcode, a circular-icon overlay, permissive SELinux domains, and it
  removes the emulator-only HALs (sensors, modem, GNSS, camera, ...).
* **Mesa** ([scripts/build-mesa.sh](scripts/build-mesa.sh)): freedreno only,
  with the base project's Adreno 418 patch, `-Dplatforms=android
  -Dandroid-stub=true`, libdrm static. Buffers are described by u_gralloc's
  fallback path (one linear plane).
* **gralloc and composer** are the emulator's minigbm and ranchu HWC3, taught
  about msm: minigbm's dumb-buffer driver list gets "msm" (a same-length
  string edit), and an `LD_PRELOAD` shim ([tools/hwc-msm-shim.c](tools/hwc-msm-shim.c))
  tells the composer the msm device is the virtio-gpu it looks for. Stopgaps
  until minigbm's msm backend and drm_hwcomposer are built properly.

## What it took

Each of these stopped the boot somewhere; most were found from the kernel log
streamed over the USB network ([scripts/watch-boot.sh](scripts/watch-boot.sh)).

1. **apexd**: some APEX payloads are EROFS, and compressed APEXes are mounted
   through dm-verity (both modules).
2. **bpfloader** is `reboot_on_failure`: Android 17's netd program for kernels
   ≥ 6.12.75 calls `bpf_get_netns_cookie()` from cgroup_skb programs, which
   Android's common kernel allows and kernel.org doesn't
   ([patches/0002](patches/0002-bpf-allow-bpf_get_netns_cookie-in-cgroup_skb-and-tc-.patch)).
3. **netd** flushes every `ip rule` at start, which took the route to the PC
   (and adb) with it: the ramdisk keeps a rule for the USB subnet.
4. **cgroups**: 6.12 only builds the v1 cpuset/memcg controllers Android still
   mounts with `CPUSETS_V1`/`MEMCG_V1`.
5. **ashmem** is gone from kernel.org Linux, and Android 17 only picks memfd by
   itself when SELinux has the `memfd_class` capability (6.12 hasn't):
   `sys.use_memfd=true`.
6. **Missing HALs that are declared** make system_server wait forever
   (sensors): the emulator ones are removed from the VINTF manifest.
7. **The emulator vendor image uses ext4 `shared_blocks`** (deduplicated
   blocks). Deleting a file with debugfs freed blocks other files still used,
   and the next write corrupted the power/thermal APEXes:
   `e2fsck -E unshare_blocks` first.
8. **GPU memory accounting**: Android 17 aborts system_server when the
   `gpuMem` BPF map is missing, and its program can't load here (its
   tracepoint is Android-kernel-only). The ramdisk pins an empty stand-in
   ([tools/bpf-map-stub.c](tools/bpf-map-stub.c)), but only after bpfloader
   has finished: a map already there makes bpfloader fail, which reboots.
9. **The A418 doesn't come back from runtime suspend** (GPU hang on resume,
   and it autosuspends after 66 ms): kept powered.
10. **Graphics memory**: there's no IOMMU, so every buffer (windows, textures)
    comes from one contiguous carveout; 256 MB ran out once a few apps were
    open ("drawRenderNode called on a context with no surface!"). Now
    `msm.vram=448m cma=512M`, plus zram.
11. **Dumb buffers on the render node** ([patches/0003](patches/0003-drm-allow-the-dumb-buffer-ioctls-on-render-nodes.patch)),
    so gralloc never opens the primary node and takes DRM master away from the
    composer.
12. **Suspend**: once the screen goes off, Android suspends the system and the
    phone never wakes: a kernel wakelock blocks it.
13. **The navigation bar** needs `qemu.hw.mainkeys=0` (no hardware keys), and
    **round icons** are a vendor overlay on `config_icon_mask`
    ([vendor/overlay-iconcircle](vendor/overlay-iconcircle)).
14. Don't `pm disable-user com.android.bluetooth`: Android 17's system_server
    then refuses to start ("Conflicting system configuration detected").

## Build and boot

Needs, next to this repository, the base project checked out as
`../nexus5x-linux-6.12-wip` (its patches, DTS, kernel config, BusyBox build
and `mkbootimg.py`; or set `BASE=`), the kernel.org 6.12.112 tarball and a
static BusyBox from it (`TARBALL=`, `BUSYBOX=`), the Android SDK (aapt2,
apksigner) and the ~5 GB of downloads below.

```sh
# downloads (not in git): accept the Android 17 GSI licence on
# developer.android.com first
#   downloads/gsi/aosp_arm64-exp-CP3A.260905.010-*.zip   -> extracted/system.img
#   downloads/emu/arm64-v8a-37.0_r06.zip (SDK repo)     -> extracted/arm64-v8a/
#   downloads/mesa/mesa-26.0.3.tar.xz, tools/android-ndk-r28c (NDK)
python3 -m venv tools/venv && tools/venv/bin/pip install meson ninja mako packaging pyyaml

scripts/build-kernel.sh          # kernel + modules (out/kernel, out/modules)
scripts/build-mesa.sh            # out/mesa/lib64/egl/*_mesa.so
scripts/build-overlays.sh        # out/overlay/*.apk
VENDOR_SIZE=248M scripts/make-vendor.py   # out/vendor.img, the partition's size
scripts/build-bootimg.sh         # out/boot-android-rev-{10,101}.img

# once: flash system, vendor, userdata (back them up first:
# scripts/backup-partition.sh, from the base project's BusyBox boot)
fastboot flash boot    out/boot-android-rev-10.img  # or rev-101: fastboot getvar version-hardware
fastboot flash system  downloads/gsi/extracted/system.img
fastboot flash vendor  out/vendor.img
fastboot flash userdata out/userdata.simg   # an empty ext4, 11771295744 bytes

scripts/boot.sh                  # or, without flashing boot: fastboot boot, with retries
adb connect 172.16.42.1:5555     # telnet 172.16.42.1 for a root shell
```

`android.disks=nbd` on the kernel command line serves system/vendor/data from
the PC instead ([scripts/serve.sh](scripts/serve.sh)), with nothing on the
phone written; that's how the first boots were done.

## Layout

| Path | What it is |
|---|---|
| [config/android.config](config/android.config) | Android's kernel options, on top of the base project's `kernel.config` |
| [patches/](patches/) | three kernel patches on top of the base project's series |
| [rootfs/init](rootfs/init) | the ramdisk's first-stage init: USB network, rescue telnet, disks, watchdogs, BPF stand-in, wakelock |
| [vendor/](vendor/) | `init.bullhead.rc`, `fstab.bullhead`, the icon-shape overlay |
| [scripts/make-vendor.py](scripts/make-vendor.py) | emulator vendor image → bullhead vendor image |
| [scripts/build-mesa.sh](scripts/build-mesa.sh) | Mesa freedreno for Android with the NDK |
| [scripts/build-kernel.sh](scripts/build-kernel.sh), [build-bootimg.sh](scripts/build-bootimg.sh) | kernel and boot image |
| [scripts/boot.sh](scripts/boot.sh), [watch-boot.sh](scripts/watch-boot.sh), [tsh.sh](scripts/tsh.sh) | boot with retries, stream the kernel log, run commands in the rescue shell |
| [scripts/serve.sh](scripts/serve.sh), [backup-partition.sh](scripts/backup-partition.sh) | disks over NBD from the PC; raw partition backups with checksums |
| [tools/](tools/) | `bpf-map-stub.c`, `hwc-msm-shim.c`, `reboot-now.c` |

Kernel patches are GPL-2.0, like the kernel they apply to.
