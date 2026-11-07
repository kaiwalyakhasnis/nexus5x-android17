#!/bin/bash
# watch-boot.sh [LOG]: as soon as the phone's rescue telnet answers, stream its
# kernel log (/dev/kmsg, from the start of the boot, following) to LOG and the
# terminal. It keeps going until the connection drops (reset, power off), so
# the last lines before a crash end up on the PC.
LOG=${1:-$(dirname "$0")/../out/boot-$(date +%H%M%S).log}
until timeout 1 bash -c '</dev/tcp/172.16.42.1/23' 2>/dev/null; do sleep 0.5; done
echo "== connected, logging to $LOG"
{ sleep 1; echo 'cat /dev/kmsg'; sleep 100000; } | nc 172.16.42.1 23 | tr -d '\r' | tee "$LOG"
