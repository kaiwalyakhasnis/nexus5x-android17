// SPDX-License-Identifier: GPL-2.0
// vibrate [MS [STRENGTH%]]: run the vibrator (the pmi8994-haptics
// force-feedback input device, kernel patches/0006) for MS milliseconds
// (default 500) at STRENGTH percent of the driver's full drive voltage
// (default 72: the stock 1800 of 2500 mV). Without Android: from the
// rescue shell.
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

private const val NAME = "pmi8994-haptics"

// include/uapi/linux/input.h on arm64: struct ff_effect is 48 bytes
// (type u16, id s16, direction u16, trigger 2*u16, replay {length, delay}
// u16, then the union at 16, 8-aligned); the rumble effect's strong/weak
// magnitudes are its first two u16
private const val EV_FF = 0x15
private const val FF_RUMBLE = 0x50
private const val FF_EFFECT_SIZE = 48
private fun ioc(dir: Long, nr: Long, size: Long) = ((dir shl 30) or (size shl 16) or ('E'.code.toLong() shl 8) or nr).toULong()
private val EVIOCSFF = ioc(1, 0x80, FF_EFFECT_SIZE.toLong())   // _IOW('E', 0x80, struct ff_effect)
private val EVIOCRMFF = ioc(1, 0x81, 4)                         // _IOW('E', 0x81, int)
private fun eviocgname(len: Int) = ioc(2, 0x06, len.toLong())

/** The haptics input device, opened read-write; -1 if not there. */
private fun openHaptics(): Int {
    val dir = opendir("/dev/input") ?: return -1
    try {
        while (true) {
            val name = readdir(dir)?.pointed?.d_name?.toKString() ?: return -1
            if (!name.startsWith("event")) continue
            val fd = open("/dev/input/$name", O_RDWR)
            if (fd < 0) continue
            val buf = ByteArray(64)
            val n = buf.usePinned { ioctl(fd, eviocgname(buf.size), it.addressOf(0)) }
            if (n > 0 && buf.decodeToString(0, n).trimEnd('\u0000') == NAME) return fd
            close(fd)
        }
    } finally {
        closedir(dir)
    }
}

private fun put16(b: ByteArray, at: Int, v: Int) {
    b[at] = v.toByte()
    b[at + 1] = (v shr 8).toByte()
}

fun vibrate(args: List<String>): Int {
    val ms = args.getOrNull(0)?.toIntOrNull() ?: 500
    val percent = (args.getOrNull(1)?.toIntOrNull() ?: 72).coerceIn(1, 100)
    if (ms !in 1..65535) {
        eprintln("usage: vibrate [MS (1..65535) [STRENGTH% (1..100)]]")
        return 2
    }
    val fd = openHaptics()
    if (fd < 0) {
        eprintln("vibrate: no $NAME input device")
        return 1
    }
    try {
        val effect = ByteArray(FF_EFFECT_SIZE)
        put16(effect, 0, FF_RUMBLE)
        put16(effect, 2, -1)                    // id -1: a new effect
        put16(effect, 10, ms)                   // replay.length
        put16(effect, 16, 0xffff * percent / 100)  // strong_magnitude
        if (effect.usePinned { ioctl(fd, EVIOCSFF, it.addressOf(0)) } < 0) return fail("EVIOCSFF")
        val id = (effect[2].toInt() and 0xff) or (effect[3].toInt() shl 8)

        // play: struct input_event { timeval (16), type u16, code u16, value s32 }
        val ev = ByteArray(24)
        put16(ev, 16, EV_FF)
        put16(ev, 18, id)
        ev[20] = 1
        if (ev.usePinned { write(fd, it.addressOf(0), 24u) } != 24L) return fail("write EV_FF")
        println("vibrating ${ms} ms at $percent%")
        usleep(((ms + 100) * 1000).toUInt())    // ff-memless stops it after ms
        ioctl(fd, EVIOCRMFF, id)
    } finally {
        close(fd)
    }
    return 0
}
