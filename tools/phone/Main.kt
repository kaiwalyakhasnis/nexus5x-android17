// SPDX-License-Identifier: GPL-2.0
// bullhead-tools: the small programs the boot ramdisk's /init (rootfs/init)
// and its rescue shell use, as one Kotlin/Native binary (one copy of the
// runtime in the ramdisk). Like BusyBox, it picks the program by the name it
// was started as, so /sbin/reboot-now etc. are links to it; or
// `bullhead-tools reboot-now bootloader`.
//
// Built static for linux_arm64 by scripts/build-bootimg.main.kts: the
// ramdisk has no C library of its own.
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.system.exitProcess

private val programs: Map<String, (List<String>) -> Int> = mapOf(
    "reboot-now" to ::rebootNow,
    "reboot-to" to ::rebootTo,
    "wait-power-key" to ::waitPowerKey,
    "bpf-map-stub" to ::bpfMapStub,
)

fun main(args: Array<String>) {
    // Kotlin/Native's args leave argv[0] out: the kernel still has it
    val argv0 = readText("/proc/self/cmdline")?.substringBefore('\u0000') ?: ""
    val name = argv0.substringAfterLast('/')
    val (program, rest) = when {
        name in programs -> programs.getValue(name) to args.toList()
        args.isNotEmpty() && args[0] in programs -> programs.getValue(args[0]) to args.drop(1)
        else -> {
            eprintln("usage: bullhead-tools {${programs.keys.joinToString("|")}} [args]")
            exitProcess(2)
        }
    }
    exitProcess(program(rest))
}
