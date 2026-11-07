#!/bin/bash
# out/boot-android-<rev>.img: this project's kernel + DTB + a small BusyBox
# ramdisk whose /init (rootfs/init) attaches system/vendor/data from the PC
# and hands over to Android's init. For `fastboot boot` only (RAM).
set -euo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
BASE=${BASE:-$TOP/../nexus5x-linux-6.12-wip}
BUSYBOX=${BUSYBOX:-$HOME/bullhead-linux/out/busybox/busybox}
KOUT=$TOP/out/kernel
K=$KOUT/arch/arm64/boot
STAGE=$TOP/out/ramdisk

rm -rf "$STAGE"
mkdir -p "$STAGE"/{bin,sbin,usr/bin,usr/sbin,dev,proc,sys,run,tmp,newroot,lib/modules,etc}
cp -a "$TOP"/rootfs/. "$STAGE"/
mkdir -p "$STAGE/usr/share/learn"
cp "$BASE/rootfs/usr/share/learn/usb-gadget.sh" "$STAGE/usr/share/learn/"
cp "$BASE/rootfs/etc/udhcpd.conf" "$STAGE/etc/"
cp "$BUSYBOX" "$STAGE/bin/busybox"
ln -sf busybox "$STAGE/bin/sh"
aarch64-linux-gnu-gcc -static -Os -s -o "$STAGE/sbin/reboot-to" "$BASE/tools/reboot-to/reboot-to.c"
# reboot-now: no sync(), for the watchdog in rootfs/init (disks may be gone)
aarch64-linux-gnu-gcc -static -Os -s -o "$STAGE/sbin/reboot-now" "$TOP/tools/reboot-now.c"
# bpf-map-stub: the GPU memory BPF map Android 17 insists on (rootfs/init)
aarch64-linux-gnu-gcc -static -Os -s -o "$STAGE/sbin/bpf-map-stub" "$TOP/tools/bpf-map-stub.c"
# The A418's microcode (the A420's, from linux-firmware): drm/msm is built in
# and asks for it while probing, before /vendor exists
FW=${FW:-$TOP/downloads/linux-firmware}
mkdir -p "$STAGE/lib/firmware/qcom"
cp "$FW"/{a420_pm4.fw,a420_pfp.fw,LICENSE.qcom} "$STAGE/lib/firmware/qcom/"

LIST=$TOP/out/ramdisk.list
{
	cd "$STAGE"
	find . -mindepth 1 | sort | while read -r f; do
		p=${f#.}
		m=$(stat -c %a "$f")
		if [ -L "$f" ]; then echo "slink $p $(readlink "$f") 0777 0 0"
		elif [ -d "$f" ]; then echo "dir $p $m 0 0"
		else echo "file $p $STAGE$p $m 0 0"; fi
	done
	echo "nod /dev/console 0600 0 0 c 5 1"
} > "$LIST"
"$KOUT/usr/gen_init_cpio" "$LIST" | gzip -9 > "$TOP/out/ramdisk.cpio.gz"

# Android's init reads androidboot.* from the command line as ro.boot.*.
# printk.devkmsg=on: init logs a lot to /dev/kmsg, don't rate-limit it.
# firmware_class.path: the GPU microcode and Wi-Fi firmware on /vendor.
# The rest is the Ubuntu kernel's (nexus5x-linux-6.12-wip/scripts/build-bootimg.sh).
CMDLINE="console=tty0 earlycon clk_ignore_unused pd_ignore_unused regulator_ignore_unused cma=512M msm.vram=448m msm.allow_vram_carveout=1 consoleblank=0 fbcon=font:TER16x32 arm64.late_cpu_conflicts=warn rdinit=/init panic=10 printk.devkmsg=on firmware_class.path=/vendor/firmware androidboot.hardware=bullhead androidboot.serialno=bullhead-a17 androidboot.verifiedbootstate=orange androidboot.veritymode=disabled androidboot.selinux=permissive"
for rev in rev-101 rev-10; do
	cat "$K/Image.gz" "$K/dts/qcom/msm8992-lg-bullhead-$rev-learn.dtb" > "$TOP/out/Image.gz-dtb-$rev"
	python3 "$BASE/scripts/mkbootimg.py" \
		--kernel "$TOP/out/Image.gz-dtb-$rev" --ramdisk "$TOP/out/ramdisk.cpio.gz" \
		--cmdline "$CMDLINE" -o "$TOP/out/boot-android-$rev.img"
done
ls -la "$TOP"/out/boot-android-*.img
