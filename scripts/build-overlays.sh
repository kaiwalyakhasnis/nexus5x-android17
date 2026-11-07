#!/bin/bash
# Build the vendor's resource overlays (vendor/overlay-*/) into
# out/overlay/*.apk with the Android SDK on this PC (aapt2, apksigner), for
# make-vendor.py to put in /vendor/overlay.
set -euo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
BT=${BT:-$(ls -d "$HOME"/Android/Sdk/build-tools/* | sort -V | tail -1)}
JAR=${JAR:-$(ls -d "$HOME"/Android/Sdk/platforms/android-* | sort -V | tail -1)/android.jar}
OUT=$TOP/out/overlay
rm -rf "$OUT" && mkdir -p "$OUT"
for d in "$TOP"/vendor/overlay-*/; do
	n=$(basename "$d"); n=${n#overlay-}
	W=$(mktemp -d)
	"$BT/aapt2" compile --dir "$d/res" -o "$W/res.zip"
	"$BT/aapt2" link -o "$W/u.apk" -I "$JAR" --manifest "$d/AndroidManifest.xml" "$W/res.zip"
	"$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
	"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
		--out "$OUT/Bullhead-$n.apk" "$W/a.apk"
	rm -rf "$W"
	echo "built $OUT/Bullhead-$n.apk"
done
