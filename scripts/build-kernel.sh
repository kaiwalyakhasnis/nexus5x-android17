#!/bin/bash
# The nexus5x-linux-6.12-wip kernel (same patches, DTS and config) plus
# config/android.config, built in this project's own src/ and out/ so the
# Ubuntu builds are not touched.
#
#   out/kernel/arch/arm64/boot/Image.gz, out/kernel/.../*-learn.dtb
#   out/modules/lib/modules/<ver>/   the =m modules, for /vendor/lib/modules
set -euo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
BASE=${BASE:-$TOP/../nexus5x-linux-6.12-wip}
TARBALL=${TARBALL:-$HOME/bullhead-linux/downloads/linux-6.12.112.tar.xz}
VER=6.12.112
KSRC=$TOP/src/linux-$VER-android
KOUT=$TOP/out/kernel
export ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu-
GIT=(git -C "$KSRC" -c gc.auto=0 -c user.name=nexus5x-android17 -c user.email=nexus5x-android17@localhost)

# pristine tarball + the base project's patches (re-applied each build)
if [ ! -d "$KSRC/.git" ]; then
	rm -rf "$KSRC" "$TOP/src/tmp"
	mkdir -p "$TOP/src/tmp"
	tar xf "$TARBALL" -C "$TOP/src/tmp"
	mv "$TOP/src/tmp/linux-$VER" "$KSRC"
	rmdir "$TOP/src/tmp"
	"${GIT[@]}" init -q
	"${GIT[@]}" add -A
	"${GIT[@]}" commit -q -m "linux-$VER"
	"${GIT[@]}" tag base
fi
"${GIT[@]}" am --abort 2>/dev/null || true
"${GIT[@]}" reset -q --hard base
"${GIT[@]}" clean -qfd
"${GIT[@]}" am -q "$BASE"/patches/*.patch
[ -d "$TOP/patches" ] && ls "$TOP"/patches/*.patch >/dev/null 2>&1 && "${GIT[@]}" am -q "$TOP"/patches/*.patch
cp "$BASE"/dts/*.dts* "$KSRC/arch/arm64/boot/dts/qcom/"

mkdir -p "$KOUT"
make -C "$KSRC" O="$KOUT" defconfig >/dev/null
"$KSRC/scripts/kconfig/merge_config.sh" -m -O "$KOUT" "$KOUT/.config" \
	"$BASE/scripts/kernel.config" "$TOP/config/android.config" >/dev/null
# defconfig's thousands of =m drivers are never used on the phone: keep only
# the modules our fragments ask for (selects bring their helpers back)
WANT=$(grep -hE '^CONFIG_[A-Z0-9_]+=m' "$BASE/scripts/kernel.config" "$TOP/config/android.config" | cut -d= -f1 | sort -u)
grep -E '^CONFIG_[A-Z0-9_]+=m' "$KOUT/.config" | cut -d= -f1 | while read -r o; do
	grep -qx "$o" <<<"$WANT" || sed -i "s/^$o=m\$/# $o is not set/" "$KOUT/.config"
done
make -C "$KSRC" O="$KOUT" olddefconfig >/dev/null

# fragment options that did not stick (a missing dependency)
for frag in "$BASE/scripts/kernel.config" "$TOP/config/android.config"; do
	grep -E '^CONFIG_' "$frag" | while read -r opt; do
		grep -qxF "$opt" "$KOUT/.config" || echo "WARNING: not set: $opt (have: $(grep -E "^(# )?${opt%%=*}[= ]" "$KOUT/.config"))"
	done
done

make -C "$KSRC" O="$KOUT" -j"$(nproc)" Image.gz modules
for rev in rev-101 rev-10; do
	make -C "$KSRC" O="$KOUT" qcom/msm8992-lg-bullhead-$rev-learn.dtb
done
KVER=$(cat "$KOUT/include/config/kernel.release")
rm -rf "$TOP/out/modules"
make -C "$KSRC" O="$KOUT" INSTALL_MOD_PATH="$TOP/out/modules" INSTALL_MOD_STRIP=1 modules_install >/dev/null
echo "modules: $(find "$TOP/out/modules" -name '*.ko' | wc -l), $(du -sh "$TOP/out/modules" | cut -f1)"

# LK loads a 64-bit kernel at 0x80000 and the DTB at 0x1e00000: the Image's
# full in-memory size (header offset 16, BSS included) must fit in between
size=$(od -An -t u8 -j 16 -N 8 "$KOUT/arch/arm64/boot/Image" | tr -d ' ')
limit=$((0x1e00000 - 0x80000))
printf 'kernel image_size: %d bytes (%d.%d MiB), limit %d (%d KiB left)\n' \
	"$size" $((size >> 20)) $(((size & 0xfffff) * 10 >> 20)) "$limit" $(((limit - size) >> 10))
[ "$size" -lt "$limit" ] || { echo "ERROR: kernel overlaps LK's DTB/ramdisk area"; exit 1; }
ls -la "$KOUT/arch/arm64/boot/Image.gz"
