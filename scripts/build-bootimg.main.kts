#!/usr/bin/env kotlin
// out/boot-android-<rev>.img: this project's kernel + DTB + a small BusyBox
// ramdisk whose /init (rootfs/init) finds system/vendor/data and hands over
// to Android's init. For `fastboot flash boot` or `fastboot boot`.
//
// Environment: BASE (the kernel project), BUSYBOX (a static arm64 BusyBox),
// FW (linux-firmware), KONAN_HOME (Kotlin/Native, for tools/phone).
@file:Import("lib.main.kts")

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream

val BUSYBOX = File(env("BUSYBOX", "$HOME/bullhead-linux/out/busybox/busybox"))
val FW = File(env("FW", TOP.resolve("downloads/linux-firmware").path))
val KONAN = File(env("KONAN_HOME", "$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-2.2.21"))
val KOUT = OUT.resolve("kernel")
val BOOT = KOUT.resolve("arch/arm64/boot")
val STAGE = OUT.resolve("ramdisk")

// Android's init reads androidboot.* from the command line as ro.boot.*.
// printk.devkmsg=on: init logs a lot to /dev/kmsg, don't rate-limit it.
// firmware_class.path: the GPU microcode and Wi-Fi firmware on /vendor.
// cma/msm.vram: graphics buffers come from a carveout (no IOMMU on this port).
// The rest is the Ubuntu kernel's (nexus5x-linux-6.12-wip/scripts/build-bootimg.sh).
val CMDLINE = listOf(
    "console=tty0", "earlycon", "clk_ignore_unused", "pd_ignore_unused", "regulator_ignore_unused",
    "cma=512M", "msm.vram=448m", "msm.allow_vram_carveout=1",
    "consoleblank=0", "fbcon=font:TER16x32", "arm64.late_cpu_conflicts=warn",
    "rdinit=/init", "panic=10", "printk.devkmsg=on", "firmware_class.path=/vendor/firmware",
    "androidboot.hardware=bullhead", "androidboot.serialno=bullhead-a17",
    "androidboot.verifiedbootstate=orange", "androidboot.veritymode=disabled",
    "androidboot.selinux=permissive",
).joinToString(" ")

/**
 * tools/phone/ -> out/phone/bullhead-tools: one static arm64 binary for the
 * ramdisk's small programs (it picks one by the name it's started as).
 * Rebuilt only when a source changed: Kotlin/Native takes about a minute.
 */
fun phoneTools(): File {
    val sources = TOP.resolve("tools/phone").listFiles { f -> f.name.endsWith(".kt") }!!.sorted()
    val dir = OUT.resolve("phone")
    val bin = dir.resolve("bullhead-tools")
    if (bin.exists() && sources.all { it.lastModified() < bin.lastModified() }) return bin
    dir.mkdirs()
    println("building ${bin.relativeTo(TOP)} (Kotlin/Native, linux_arm64, static)")
    // Kotlin/Native's linux_arm64 profile links glibc dynamically, and the
    // ramdisk has no glibc: link everything static instead. libpthread goes
    // in whole (its static archive and libc's would otherwise clash), and
    // the -lgcc_s that konanc always adds finds an empty archive.
    val stub = dir.resolve("stub").apply { mkdirs() }
    stub.resolve("libgcc_s.a").writeText("!<arch>\n")
    // konanc needs Java 11+; this script may run on an older default java
    val javaHome = if (System.getProperty("java.specification.version").startsWith("1."))
        env("JAVA_HOME_KONAN", "/usr/lib/jvm/java-21-openjdk-amd64") else System.getProperty("java.home")
    run(
        KONAN.resolve("bin/kotlinc-native"), "-target", "linux_arm64", "-opt",
        *sources.toTypedArray(), "-o", dir.resolve("bullhead-tools"),
        "-Xoverride-konan-properties=linkerKonanFlags.linux_arm64=-Bstatic -lstdc++ --defsym __cxa_demangle=Konan_cxa_demangle",
        "-linker-options", "-static --no-dynamic-linker -L$stub --whole-archive -lpthread --no-whole-archive " +
            "--start-group -lc -lm -ldl -lgcc -lgcc_eh --end-group",
        env = mapOf("JAVA_HOME" to javaHome),
    )
    run("aarch64-linux-gnu-strip", "-o", bin, dir.resolve("bullhead-tools.kexe"))
    return bin
}

fun mode(f: File): Int = Files.getPosixFilePermissions(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    .sumOf { p ->
        when (p) {
            PosixFilePermission.OWNER_READ -> 256; PosixFilePermission.OWNER_WRITE -> 128
            PosixFilePermission.OWNER_EXECUTE -> 64; PosixFilePermission.GROUP_READ -> 32
            PosixFilePermission.GROUP_WRITE -> 16; PosixFilePermission.GROUP_EXECUTE -> 8
            PosixFilePermission.OTHERS_READ -> 4; PosixFilePermission.OTHERS_WRITE -> 2
            PosixFilePermission.OTHERS_EXECUTE -> 1
            else -> 0
        }.toInt()
    }

fun link(target: String, at: File) {
    at.delete()
    Files.createSymbolicLink(at.toPath(), File(target).toPath())
}

// 1. the ramdisk's files
val tools = phoneTools()
STAGE.removeTree()
for (d in listOf("bin", "sbin", "usr/bin", "usr/sbin", "dev", "proc", "sys", "run", "tmp", "newroot",
        "lib/modules", "etc", "usr/share/learn", "lib/firmware/qcom"))
    STAGE.resolve(d).mkdirs()
run("cp", "-a", "$TOP/rootfs/.", "$STAGE/")     // cp -a: keeps /init executable
// the USB network gadget and DHCP server setup, as in the Ubuntu boot
run("cp", BASE.resolve("rootfs/usr/share/learn/usb-gadget.sh"), STAGE.resolve("usr/share/learn/"))
run("cp", BASE.resolve("rootfs/etc/udhcpd.conf"), STAGE.resolve("etc/"))
run("cp", BUSYBOX, STAGE.resolve("bin/busybox"))
link("busybox", STAGE.resolve("bin/sh"))
// tools/phone: reboot-now, reboot-to, wait-power-key, bpf-map-stub, nanohub-test, vibrate
run("cp", tools, STAGE.resolve("sbin/bullhead-tools"))
for (p in listOf("reboot-now", "reboot-to", "wait-power-key", "bpf-map-stub", "nanohub-test", "vibrate"))
    link("bullhead-tools", STAGE.resolve("sbin/$p"))
// The A418's microcode (the A420's, from linux-firmware): drm/msm is built in
// and asks for it while probing, before /vendor exists
for (f in listOf("a420_pm4.fw", "a420_pfp.fw", "LICENSE.qcom"))
    FW.resolve(f).copyTo(STAGE.resolve("lib/firmware/qcom/$f"), overwrite = true)

// 2. the cpio: every file owned by root, plus /dev/console (needed before
// devtmpfs is mounted)
val staged = Files.walk(STAGE.toPath()).use { s -> s.skip(1).map { it.toFile() }.toList() }  // links not followed
val list = staged.sortedBy { it.path }.map { f ->
    val p = "/" + f.relativeTo(STAGE).path
    when {
        Files.isSymbolicLink(f.toPath()) -> "slink $p ${Files.readSymbolicLink(f.toPath())} 0777 0 0"
        f.isDirectory -> "dir $p ${mode(f).toString(8)} 0 0"
        else -> "file $p $f ${mode(f).toString(8)} 0 0"
    }
}.toList() + "nod /dev/console 0600 0 0 c 5 1"
val listFile = OUT.resolve("ramdisk.list").apply { writeText(list.joinToString("\n", postfix = "\n")) }
val ramdisk = OUT.resolve("ramdisk.cpio.gz")
val cpio = ProcessBuilder(KOUT.resolve("usr/gen_init_cpio").path, listFile.path)
    .redirectError(ProcessBuilder.Redirect.INHERIT).start()
object : GZIPOutputStream(ramdisk.outputStream()) {
    init { def.setLevel(9) }
}.use { cpio.inputStream.copyTo(it) }
if (cpio.waitFor() != 0) die("gen_init_cpio failed")

// 3. the boot images: kernel with the board's DTB appended (what LK expects)
for (rev in listOf("rev-101", "rev-10")) {
    val kernel = OUT.resolve("Image.gz-dtb-$rev")
    kernel.writeBytes(BOOT.resolve("Image.gz").readBytes() +
        BOOT.resolve("dts/qcom/msm8992-lg-bullhead-$rev-learn.dtb").readBytes())
    val img = OUT.resolve("boot-android-$rev.img")
    run("python3", BASE.resolve("scripts/mkbootimg.py"), "--kernel", kernel, "--ramdisk", ramdisk,
        "--cmdline", CMDLINE, "-o", img)
    println("$img: ${img.length()} bytes (ramdisk ${ramdisk.length()})")
}
