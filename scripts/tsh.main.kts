#!/usr/bin/env kotlin
// tsh.main.kts 'commands' [timeout seconds]: run commands in the phone's
// rescue shell (BusyBox telnetd from the boot ramdisk, root, 172.16.42.1:23)
// and print their output. Android's files are under /proc/1/root there; run
// Android's own tools with: chroot /proc/1/root /system/bin/logcat -d
@file:Import("lib.main.kts")

if (args.isEmpty()) die("usage: tsh.main.kts 'commands' [timeout seconds]")
val out = Phone.sh(args[0], timeoutSec = args.getOrNull(1)?.toInt() ?: 30)
    ?: die("no answer from the rescue shell at ${Phone.IP}:23 (or it timed out)")
println(out)
