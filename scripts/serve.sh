#!/bin/bash
# serve.sh: the phone's disks, served from this PC over the USB network while
# Android runs (keep it running). Only the USB address (172.16.42.2, which the
# phone's DHCP server hands out) listens; nothing is reachable from the LAN.
#
#   10809  system  the GSI's system.img, read-only
#   10810  vendor  out/vendor.img (scripts/make-vendor.py), read-only
#   10811  data    out/data.img, Android's /data (made on first use, sparse)
#   10880  HTTP /time: the clock (the phone has no RTC driver yet)
#
# qemu-nbd serves one client at a time and a rebooted phone never closes its
# old connection, so everything restarts whenever the USB link goes away.
set -euo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
SYSTEM=${SYSTEM:-$TOP/downloads/gsi/extracted/system.img}
VENDOR=$TOP/out/vendor.img
DATA=${DATA:-$TOP/out/data.img}
ADDR=172.16.42.2

[ -f "$SYSTEM" ] || { echo "no $SYSTEM"; exit 1; }
[ -f "$VENDOR" ] || { echo "no $VENDOR: run scripts/make-vendor.py"; exit 1; }
if [ ! -f "$DATA" ]; then
	truncate -s "${DATA_SIZE:-8G}" "$DATA"
	mkfs.ext4 -q -L data "$DATA"
	echo "made $DATA (${DATA_SIZE:-8G}, sparse)"
fi

up() { ip -4 addr show | grep -q "inet ${ADDR//./\\.}/"; }
PIDS=()
stop() { kill "${PIDS[@]}" 2>/dev/null || true; wait 2>/dev/null || true; PIDS=(); }
trap 'stop; exit 0' INT TERM
while true; do
	until up; do sleep 1; done
	echo "$(date +%T) USB link up: serving system, vendor, data on $ADDR"
	qemu-nbd -f raw -r -x system -b $ADDR -p 10809 -t "$SYSTEM" & PIDS+=($!)
	qemu-nbd -f raw -r -x vendor -b $ADDR -p 10810 -t "$VENDOR" & PIDS+=($!)
	# --cache=unsafe: ignore the phone's flushes. apexd and dex2oat fsync
	# after every write, and each flush would be an fdatasync of data.img on
	# this PC (~600 KB/s). The cost: a PC crash can corrupt data.img (just
	# delete it, Android sets /data up again on the next boot).
	qemu-nbd -f raw    -x data   -b $ADDR -p 10811 -t --cache=unsafe "$DATA" & PIDS+=($!)
	python3 -I -c '
import http.server, time, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        b = str(int(time.time())).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers()
        self.wfile.write(b)
    def log_message(self, *a): pass
http.server.HTTPServer((sys.argv[1], 10880), H).serve_forever()' $ADDR & PIDS+=($!)
	while up; do
		for p in "${PIDS[@]}"; do kill -0 "$p" 2>/dev/null || break 2; done
		sleep 1
	done
	stop
	echo "$(date +%T) USB link gone (or a server exited): stopped serving"
done
