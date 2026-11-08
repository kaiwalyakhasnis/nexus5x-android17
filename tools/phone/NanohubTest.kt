// SPDX-License-Identifier: GPL-2.0
// nanohub-test [SENSOR [HZ [SECONDS]]]: talk to the sensor hub through the
// kernel driver (/dev/nanohub, drivers/staging/nanohub), without Android:
// switch one sensor on, print its events, switch it off again.
//   nanohub-test              the hub's firmware version and apps
//   nanohub-test accel 10 5   accelerometer at 10 Hz for 5 s
// SENSOR: accel gyro mag baro temp light prox. light and prox only report
// changes (cover the sensor at the top of the screen).
//
// The messages are the stock sensors HAL's
// (device/google/contexthub/sensorhal: ConfigCmd, nAxisEvent).
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

private const val DEV = "/dev/nanohub"
private const val SYSFS = "/sys/class/nanohub/nanohub"

// firmware/os/inc/eventnums.h, sensType.h; sensorhal/hubconnection.h
private const val EVT_NO_FIRST_SENSOR_EVENT = 0x200
private const val EVT_NO_SENSOR_CONFIG_EVENT = 0x300
private const val EVT_RESET_REASON = 0x403
private const val OS_LOG_EVENT = 0x474F4C41     // "ALOG": a log line from the hub
private const val CONFIG_CMD_DISABLE = 0
private const val CONFIG_CMD_ENABLE = 1
private const val SENSOR_RATE_ONCHANGE = 0xFFFFFF01L.toInt()

/** hub sensor type, values per sample (3: x y z, 1: one value), unit; some
 *  report raw int16 samples (event type rawType) to be multiplied by scale */
private class HubSensor(
    val type: Int, val axes: Int, val unit: String, val onChange: Boolean = false,
    val rawType: Int = -1, val scale: Float = 1f,
)

// bullhead's scales: device/lge/bullhead/sensorhal/sensorlist.cpp
private val sensors = mapOf(
    "accel" to HubSensor(1, 3, "m/s^2", rawType = 32, scale = 16f * 9.81f / 32768f),
    "gyro" to HubSensor(6, 3, "rad/s"),
    "mag" to HubSensor(8, 3, "uT", rawType = 59, scale = 1f / 16f),
    "baro" to HubSensor(10, 1, "hPa"),
    "temp" to HubSensor(11, 1, "C"),
    "light" to HubSensor(12, 1, "lux", onChange = true),
    "prox" to HubSensor(13, 1, "cm", onChange = true),
)

/** struct ConfigCmd: evtType u32, latency u64, rate u32 (Hz, Q10),
 *  sensorType u8, cmd u8, flags u16; little-endian, packed: 20 bytes */
private fun configCmd(type: Int, enable: Boolean, rateQ10: Int): ByteArray {
    val b = ByteArray(20)
    putLe(b, 0, EVT_NO_SENSOR_CONFIG_EVENT.toLong(), 4)
    putLe(b, 4, 0, 8)                   // latency: report at once
    putLe(b, 12, rateQ10.toLong() and 0xffffffffL, 4)
    b[16] = type.toByte()
    b[17] = (if (enable) CONFIG_CMD_ENABLE else CONFIG_CMD_DISABLE).toByte()
    return b
}

private fun putLe(b: ByteArray, at: Int, v: Long, n: Int) {
    for (i in 0 until n) b[at + i] = (v ushr (8 * i)).toByte()
}

private fun le(b: ByteArray, at: Int, n: Int): Long {
    var v = 0L
    for (i in 0 until n) v = v or ((b[at + i].toLong() and 0xff) shl (8 * i))
    return v
}

private fun f32(b: ByteArray, at: Int) = Float.fromBits(le(b, at, 4).toInt())

private fun send(fd: Int, msg: ByteArray): Boolean {
    val n = msg.usePinned { write(fd, it.addressOf(0), msg.size.convert()) }
    if (n != msg.size.toLong()) {
        fail("write $DEV")
        return false
    }
    return true
}

/** One nAxisEvent: evtType u32, referenceTime u64 (ns, boot time), then the
 *  samples; sample 0's first 4 bytes are the header (numSamples, numFlushes,
 *  flags), each later one starts with a delta time. */
private fun printEvent(b: ByteArray, len: Int, s: HubSensor) {
    val evt = le(b, 0, 4).toInt()
    if (evt == EVT_RESET_REASON) {
        println("hub reset, reason 0x${le(b, 4, 4).toString(16)}")
        return
    }
    if (evt == OS_LOG_EVENT) {
        // [ALOG][level char][text]
        if (len > 5) println("hub log ${b[4].toInt().toChar()}: " + b.decodeToString(5, len).trimEnd('\u0000', '\n'))
        return
    }
    val raw = evt == EVT_NO_FIRST_SENSOR_EVENT + s.rawType
    if ((evt != EVT_NO_FIRST_SENSOR_EVENT + s.type && !raw) || len < 16) {
        println("event 0x${evt.toString(16)}, $len bytes")
        return
    }
    var time = le(b, 4, 8)
    val count = b[12].toInt() and 0xff
    // sample: delta time u32, then 3 int16 (raw), 3 floats or 1 float
    val size = if (raw) 10 else if (s.axes == 3) 16 else 8
    for (i in 0 until count) {
        val at = 12 + i * size
        if (at + size > len) break
        if (i > 0) {
            // bit 0 set: delta in ns, else in units of 512 ns (hubconnection.cpp)
            val d = le(b, at, 4)
            time += if (d and 1L == 1L) d else d shl 9
        }
        val values = (0 until s.axes).joinToString(" ") { k ->
            if (raw) dec3(le(b, at + 4 + 2 * k, 2).toShort() * s.scale) else dec3(f32(b, at + 4 + 4 * k))
        }
        println("t=${time / 1_000_000} ms  $values ${s.unit}")
    }
}

/** v with 3 decimals (Kotlin/Native has no String.format) */
private fun dec3(v: Float): String {
    val neg = v < 0
    val m = kotlin.math.round(kotlin.math.abs(v) * 1000).toLong()
    return (if (neg) "-" else "") + "${m / 1000}." + (m % 1000).toString().padStart(3, '0')
}

fun nanohubTest(args: List<String>): Int {
    if (args.isEmpty()) {
        print(readText("$SYSFS/firmware_version") ?: "no $SYSFS/firmware_version\n")
        print(readText("$SYSFS/app_info") ?: "")
        return 0
    }
    val s = sensors[args[0]] ?: run {
        eprintln("usage: nanohub-test [${sensors.keys.joinToString("|")} [HZ [SECONDS]]]")
        return 2
    }
    val hz = args.getOrNull(1)?.toIntOrNull() ?: 10
    val seconds = args.getOrNull(2)?.toIntOrNull() ?: 5
    val rate = if (s.onChange) SENSOR_RATE_ONCHANGE else hz * 1024

    val fd = open(DEV, O_RDWR)
    if (fd < 0) return fail("open $DEV")
    try {
        if (!send(fd, configCmd(s.type, true, rate))) return 1
        println("${args[0]} on" + (if (s.onChange) " (on change)" else " at $hz Hz") + " for $seconds s")
        val buf = ByteArray(256)
        val end = time(null) + seconds
        memScoped {
            val p = alloc<pollfd>().apply { this.fd = fd; events = POLLIN.toShort() }
            while (time(null) < end) {
                if (poll(p.ptr, 1u, 500) <= 0) continue
                val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }
                if (n <= 0) return fail("read $DEV")
                printEvent(buf, n.toInt(), s)
            }
        }
        send(fd, configCmd(s.type, false, rate))
        println("${args[0]} off")
    } finally {
        close(fd)
    }
    return 0
}
