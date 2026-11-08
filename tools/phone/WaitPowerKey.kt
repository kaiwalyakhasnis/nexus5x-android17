// SPDX-License-Identifier: GPL-2.0
// wait-power-key: return once the power key (pm8941_pwrkey) has been held
// for a second. For the charger mode in rootfs/init: LK starts the boot
// image with androidboot.mode=charger when the phone is off and a cable is
// plugged in, and holding power then starts Android, as on the stock phone.
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

private const val KEY_NAME = "pm8941_pwrkey"
private const val HOLD_MS = 1000

// include/uapi/linux/input.h, input-event-codes.h
private const val EV_KEY = 1
private const val KEY_POWER = 116
private const val EVENT_SIZE = 24       // struct input_event on arm64
private fun eviocgname(len: Int): ULong =  // _IOC(_IOC_READ, 'E', 0x06, len)
    ((2L shl 30) or (len.toLong() shl 16) or ('E'.code.toLong() shl 8) or 0x06L).toULong()

/** The power key's /dev/input/event* device, opened; -1 if not there (yet). */
private fun openKey(): Int {
    val dir = opendir("/dev/input") ?: return -1
    try {
        while (true) {
            val name = readdir(dir)?.pointed?.d_name?.toKString() ?: return -1
            if (!name.startsWith("event")) continue
            val fd = open("/dev/input/$name", O_RDONLY)
            if (fd < 0) continue
            val dev = ByteArray(64)
            val n = dev.usePinned { ioctl(fd, eviocgname(dev.size), it.addressOf(0)) }
            if (n > 0 && dev.decodeToString(0, n).trimEnd('\u0000') == KEY_NAME) return fd
            close(fd)
        }
    } finally {
        closedir(dir)
    }
}

/** The next power key event: 1 down, 0 up; -1 if none within ms (ms -1: wait). */
private fun next(fd: Int, ms: Int): Int = memScoped {
    val p = alloc<pollfd>().apply { this.fd = fd; events = POLLIN.toShort() }
    val ev = ByteArray(EVENT_SIZE)
    var result: Int? = null
    while (result == null) {
        if (poll(p.ptr, 1u, ms) <= 0) {
            result = -1
            break
        }
        val n = ev.usePinned { read(fd, it.addressOf(0), EVENT_SIZE.convert()) }
        if (n != EVENT_SIZE.toLong()) {
            result = -1
            break
        }
        val type = u16(ev, 16)
        val code = u16(ev, 18)
        val value = u16(ev, 20) or (u16(ev, 22) shl 16)
        if (type == EV_KEY && code == KEY_POWER) result = if (value != 0) 1 else 0
    }
    result!!
}

private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

fun waitPowerKey(args: List<String>): Int {
    // the power key driver may still be probing when the ramdisk starts
    var fd = openKey()
    var tries = 0
    while (fd < 0) {
        if (++tries == 30) {
            eprintln("wait-power-key: no $KEY_NAME")
            return 1
        }
        sleep(1u)
        fd = openKey()
    }
    while (true) {
        if (next(fd, -1) != 1) continue
        // down: held long enough unless released within HOLD_MS
        if (next(fd, HOLD_MS) == -1) return 0
    }
}
