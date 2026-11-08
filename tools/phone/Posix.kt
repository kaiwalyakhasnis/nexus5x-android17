// SPDX-License-Identifier: GPL-2.0
// What the programs share: errors, files, raw syscalls.
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

fun eprintln(s: String) {
    fputs(s + "\n", stderr)
}

/** perror(): "what: strerror(errno)" on stderr */
fun fail(what: String): Int {
    eprintln("$what: ${strerror(errno)?.toKString()}")
    return 1
}

/** A whole (small) file as text, or null if it can't be read. */
fun readText(path: String): String? {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    val out = StringBuilder()
    try {
        val buf = ByteArray(4096)
        while (true) {
            val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }
            if (n <= 0) break
            out.append(buf.decodeToString(0, n.toInt()))
        }
    } finally {
        close(fd)
    }
    return out.toString()
}

// aarch64 syscall numbers (include/uapi/asm-generic/unistd.h)
const val NR_REBOOT = 142L
const val NR_BPF = 280L

/** syscall(2) with every argument as a 64-bit register value */
fun sys(nr: Long, vararg a: Long): Long = when (a.size) {
    1 -> syscall(nr, a[0])
    2 -> syscall(nr, a[0], a[1])
    3 -> syscall(nr, a[0], a[1], a[2])
    4 -> syscall(nr, a[0], a[1], a[2], a[3])
    else -> error("sys: ${a.size} arguments")
}
