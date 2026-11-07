#!/bin/bash
# tsh.sh 'commands' [seconds]: run commands in the phone's rescue shell
# (BusyBox telnetd from the boot ramdisk, root, 172.16.42.1:23) and print the
# output. Android's files are under /proc/1/root there; run Android's own
# tools with: chroot /proc/1/root /system/bin/logcat -d
WAIT=${2:-4}
{ sleep 1; echo "$1"; echo 'echo __TSH_DONE__'; sleep "$WAIT"; } |
	timeout $((WAIT + 10)) nc 172.16.42.1 23 2>/dev/null |
	tr -d '\r' | sed -e 's/\x1b\[[0-9;]*[a-zA-Z]//g' -e '/__TSH_DONE__/,$d' | sed '1,/^\/ # /{/^\/ # /!d}' | tail -n +2
