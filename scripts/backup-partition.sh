#!/bin/bash
# backup-partition.sh NAME DEV: copy the phone's eMMC partition /dev/DEV (raw,
# read-only) to backup/NAME-DEV.img over the USB network, then compare its
# SHA-256 with one the phone computes from the partition itself.
# Needs a root shell on the phone at 172.16.42.1:23 (the BusyBox kernel or
# this project's ramdisk) that doesn't have the partition mounted.
set -euo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
NAME=$1 DEV=$2 PORT=${PORT:-10891}
OUT=$TOP/backup/$NAME-$DEV.img
mkdir -p "$TOP/backup"
nc -l -p "$PORT" > "$OUT" &
L=$!
sleep 1
# the telnet session stays open until the phone has printed its checksum
LOG=$(mktemp)
{
	sleep 1
	echo "dd if=/dev/$DEV bs=1M 2>/dev/null | nc 172.16.42.2 $PORT; echo DD_DONE; sha256sum /dev/$DEV; echo SUM_DONE"
	until grep -aq '^SUM_DONE' "$LOG" 2>/dev/null; do sleep 2; done
} | nc 172.16.42.1 23 > "$LOG"  # unfiltered: a pipe through tr would buffer the lines this waits for
wait $L 2>/dev/null || true
phone=$(tr -d '\r' < "$LOG" | grep -aE "^[0-9a-f]{64}  /dev/$DEV" | cut -d' ' -f1)
pc=$(sha256sum "$OUT" | cut -d' ' -f1)
rm -f "$LOG"
echo "$NAME: $(stat -c %s "$OUT") bytes, phone $phone"
echo "$NAME:                 PC    $pc"
[ -n "$phone" ] && [ "$phone" = "$pc" ] && { echo "$NAME: OK"; echo "$pc  $(basename "$OUT")" >> "$TOP/backup/SHA256SUMS"; } || { echo "$NAME: MISMATCH"; exit 1; }
