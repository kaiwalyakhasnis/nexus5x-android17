// SPDX-License-Identifier: GPL-2.0
// reboot-now [bootloader|recovery|normal|poweroff]
//   Restart (default: to LK's fastboot mode, through the qcom-pon reboot
//   mode) or switch off, without sync(). For rootfs/init: when the disks are
//   gone (the PC's NBD server or the USB network died) or were never
//   mounted, sync() would wait on them forever.
// reboot-to [bootloader|recovery|normal|poweroff]
//   The same after a sync(): for the rescue shell, with disks mounted.
@file:OptIn(ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.sync

// include/uapi/linux/reboot.h
private const val MAGIC1 = 0xfee1deadL
private const val MAGIC2 = 672274793L
private const val CMD_RESTART2 = 0xA1B2C3D4L
private const val CMD_POWER_OFF = 0x4321FEDCL

fun rebootNow(args: List<String>): Int {
    val mode = args.firstOrNull() ?: "bootloader"
    if (mode == "poweroff") {
        sys(NR_REBOOT, MAGIC1, MAGIC2, CMD_POWER_OFF, 0)
    } else {
        memScoped {
            sys(NR_REBOOT, MAGIC1, MAGIC2, CMD_RESTART2, mode.cstr.ptr.toLong())
        }
    }
    return fail("reboot")   // only returns on failure
}

fun rebootTo(args: List<String>): Int {
    sync()
    return rebootNow(args)
}
