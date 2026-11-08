#!/usr/bin/env kotlin
// watch-boot.main.kts [LOG]: as soon as the phone's rescue telnet answers,
// stream its kernel log (/dev/kmsg, from the start of the boot, following)
// to LOG and the terminal. It keeps going until the connection drops (reset,
// power off), so the last lines before a crash end up on the PC.
@file:Import("lib.main.kts")

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val logFile = args.firstOrNull()?.let(::File)
    ?: OUT.resolve("boot-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.log")
while (!Phone.telnetUp()) sleepSec(0.5)
log("== connected, logging to $logFile")
logFile.bufferedWriter().use { w ->
    Phone.stream("cat /dev/kmsg") { text ->
        print(text)
        w.write(text)
        w.flush()
        false
    }
}
log("== connection closed")
