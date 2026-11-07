#!/bin/bash
# boot.sh [rev-10|rev-101]: boot Android 17 on the phone from RAM.
# Run scripts/serve.sh in another terminal first (it serves the disks).
#
# Gets the phone to fastboot from wherever it is (Android/our ramdisk over
# telnet, the installed kernel, adb, or already in fastboot), `fastboot boot`s
# out/boot-android-<rev>.img and waits until Android's adbd answers. If the
# phone falls back to fastboot by itself (the ramdisk does that on an RPM
# failure boot, or when it loses the PC), it boots it again, up to 5 times.
# Then: adb connect 172.16.42.1:5555, or telnet 172.16.42.1 (rescue shell).
set -uo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
REV=${1:-}
PHONE=172.16.42.1

to_fastboot() {
	[ -n "$(fastboot devices)" ] && return 0
	if timeout 5 bash -c "</dev/tcp/$PHONE/23" 2>/dev/null; then
		echo "rescue telnet: reboot-to bootloader"
		{ sleep 1; echo 'reboot-to bootloader'; sleep 3; } | timeout 8 nc $PHONE 23 >/dev/null 2>&1
	elif adb devices | sed 1d | grep -qw device; then
		echo "adb: reboot bootloader"
		adb reboot bootloader
	fi
	for i in $(seq 1 60); do
		[ -n "$(fastboot devices)" ] && return 0
		sleep 1
	done
	echo "no fastboot device (hold Power + Volume down)"
	return 1
}

for try in 1 2 3 4 5; do
	to_fastboot || exit 1
	if [ -z "$REV" ]; then
		hw=$(fastboot getvar version-hardware 2>&1 | sed -n 's/^version-hardware: //p')
		case "$hw" in rev_1.01) REV=rev-101 ;; *) REV=rev-10 ;; esac
	fi
	echo "try $try: fastboot boot out/boot-android-$REV.img"
	fastboot boot "$TOP/out/boot-android-$REV.img" 2>&1 | tail -1
	# LK stays in fastboot for a moment after "boot": wait until it's gone,
	# or a later "fastboot devices" would look like a fallback
	for i in $(seq 1 30); do
		[ -z "$(fastboot devices)" ] && break
		sleep 1
	done
	sleep 5
	for i in $(seq 1 120); do
		if timeout 2 bash -c "</dev/tcp/$PHONE/5555" 2>/dev/null; then
			echo "Android's adbd is up after ~$((i * 2 + 5)) s: adb connect $PHONE:5555"
			exit 0
		fi
		if [ -n "$(fastboot devices)" ]; then
			echo "phone went back to fastboot (lost the PC?): booting again"
			continue 2
		fi
		# the ramdisk flags an RPM failure boot (no msm display/GPU) and
		# waits on telnet: keep its log, then send it back to fastboot
		if [ $((i % 5)) -eq 0 ] && timeout 3 bash -c "</dev/tcp/$PHONE/23" 2>/dev/null; then
			r=$({ sleep 1; echo '[ -e /run/rpm-failed ] && echo RPM_FAILED'; sleep 2; } |
				timeout 6 nc $PHONE 23 2>/dev/null | tr -d '\r' | grep -x RPM_FAILED || true)
			if [ -n "$r" ]; then
				log=$TOP/out/rpm-failed-$(date +%H%M%S).log
				{ sleep 1; echo 'dmesg'; sleep 4; } | timeout 8 nc $PHONE 23 2>/dev/null | tr -d '\r' > "$log"
				echo "RPM failure boot (log: $log): back to fastboot, booting again"
				{ sleep 1; echo '/sbin/reboot-now bootloader'; sleep 2; } | timeout 5 nc $PHONE 23 >/dev/null 2>&1
				for j in $(seq 1 30); do
					[ -n "$(fastboot devices)" ] && continue 3
					sleep 1
				done
				echo "the reboot hung (it can, after an RPM failure): hold Power + Volume down"
				exit 1
			fi
		fi
		sleep 2
	done
	echo "no adbd after 4 min; rescue shell: telnet $PHONE"
	exit 1
done
echo "gave up after 5 tries"
exit 1
