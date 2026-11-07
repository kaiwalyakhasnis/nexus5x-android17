// SPDX-License-Identifier: GPL-2.0
/*
 * reboot-now [bootloader|recovery]: like nexus5x-linux-6.12-wip's reboot-to,
 * but without sync(). It is for when the disks are already gone (the PC's
 * NBD server or the USB network died): sync() would wait on them forever.
 * "bootloader" lands in LK's fastboot mode (qcom-pon reboot-mode).
 */
#include <linux/reboot.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	syscall(SYS_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2,
		LINUX_REBOOT_CMD_RESTART2, argc > 1 ? argv[1] : "bootloader");
	perror("reboot");
	return 1;
}
