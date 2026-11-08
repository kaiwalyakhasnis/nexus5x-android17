#!/usr/bin/env kotlin
// boot.main.kts [rev-10|rev-101]: boot Android 17 on the phone from RAM
// (`fastboot boot`), without flashing the boot partition: for trying a new
// kernel or ramdisk. (With android.disks=nbd, run serve.main.kts first.)
//
// Gets the phone to fastboot from wherever it is (our ramdisk's rescue
// telnet, adb, or already in fastboot), boots out/boot-android-<rev>.img and
// waits until Android's adbd answers. If the phone falls back to fastboot
// by itself, or the ramdisk flags an RPM failure boot (no msm display/GPU),
// it boots it again, up to 5 times.
// Then: adb connect 172.16.42.1:5555, or scripts/tsh.main.kts (rescue shell).
@file:Import("lib.main.kts")

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

var rev: String? = args.firstOrNull()

fun toFastboot(): Boolean {
    if (Phone.inFastboot()) return true
    when {
        Phone.telnetUp() -> {
            log("rescue telnet: reboot-to bootloader")
            Phone.sh("reboot-to bootloader", timeoutSec = 3)
        }
        capture("adb", "devices", check = false).lines().drop(1).any { it.endsWith("\tdevice") } -> {
            log("adb: reboot bootloader")
            run("adb", "reboot", "bootloader", check = false)
        }
    }
    if (waitUntil(60) { Phone.inFastboot() }) return true
    log("no fastboot device (hold Power + Volume down)")
    return false
}

/** rev-10 or rev-101, as fastboot reports the board */
fun boardRev(): String {
    val (_, out) = captureAll("fastboot", "getvar", "version-hardware")
    val hw = out.lines().firstOrNull { it.startsWith("version-hardware: ") }?.substringAfter(": ")
    return if (hw == "rev_1.01") "rev-101" else "rev-10"
}

/** What became of one boot attempt. */
enum class Result { UP, BACK_IN_FASTBOOT, RPM_FAILED, TIMED_OUT }

fun watchBoot(): Result {
    // LK stays in fastboot for a moment after "boot": wait until it's gone,
    // or a later check would look like a fallback
    waitUntil(30) { !Phone.inFastboot() }
    sleepSec(5.0)
    for (i in 1..120) {
        if (Phone.open(5555)) {
            log("Android's adbd is up after ~${i * 2 + 5} s: adb connect ${Phone.IP}:5555")
            return Result.UP
        }
        if (Phone.inFastboot()) return Result.BACK_IN_FASTBOOT
        if (i % 5 == 0 && Phone.telnetUp() &&
            Phone.sh("[ -e /run/rpm-failed ] && echo RPM_FAILED")?.lines()?.contains("RPM_FAILED") == true
        ) return Result.RPM_FAILED
        sleepSec(2.0)
    }
    return Result.TIMED_OUT
}

for (attempt in 1..5) {
    if (!toFastboot()) exitProcess(1)
    val r = rev ?: boardRev().also { rev = it }
    val img = OUT.resolve("boot-android-$r.img")
    log("try $attempt: fastboot boot ${img.relativeTo(TOP)}")
    run("fastboot", "boot", img, check = false)
    when (watchBoot()) {
        Result.UP -> exitProcess(0)
        Result.BACK_IN_FASTBOOT -> log("phone went back to fastboot (lost the PC?): booting again")
        Result.RPM_FAILED -> {
            // the ramdisk waits on telnet: keep its log, then send it back to fastboot
            val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
            val logFile = OUT.resolve("rpm-failed-$time.log")
            logFile.writeText(Phone.sh("dmesg", timeoutSec = 10) ?: "(no log)\n")
            log("RPM failure boot (log: $logFile): back to fastboot, booting again")
            Phone.sh("/sbin/reboot-now bootloader", timeoutSec = 3)
            if (!waitUntil(30) { Phone.inFastboot() })
                die("the reboot hung (it can, after an RPM failure): hold Power + Volume down")
        }
        Result.TIMED_OUT -> die("no adbd after 4 min; rescue shell: scripts/tsh.main.kts")
    }
}
die("gave up after 5 tries")
