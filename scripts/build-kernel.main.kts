#!/usr/bin/env kotlin
// The nexus5x-linux-6.12-wip kernel (same patches, DTS and config) plus
// config/android.config and patches/, built in this project's own src/ and
// out/ so the Ubuntu builds are not touched.
//
//   out/kernel/arch/arm64/boot/Image.gz, out/kernel/.../*-learn.dtb
//   out/modules/lib/modules/<ver>/   the =m modules, for /vendor/lib/modules
//
// Environment: BASE (the kernel project), TARBALL (linux-6.12.112.tar.xz).
@file:Import("lib.main.kts")

import java.io.File
import java.io.RandomAccessFile

val VER = "6.12.112"
val TARBALL = File(env("TARBALL", "$HOME/bullhead-linux/downloads/linux-$VER.tar.xz"))
val KSRC = TOP.resolve("src/linux-$VER-android")
val KOUT = OUT.resolve("kernel")
val FRAGMENTS = listOf(BASE.resolve("scripts/kernel.config"), TOP.resolve("config/android.config"))
val CROSS = mapOf("ARCH" to "arm64", "CROSS_COMPILE" to "aarch64-linux-gnu-")
val JOBS = Runtime.getRuntime().availableProcessors()

fun git(vararg args: Any, check: Boolean = true) = run(
    "git", "-C", KSRC, "-c", "gc.auto=0",
    "-c", "user.name=nexus5x-android17", "-c", "user.email=nexus5x-android17@localhost",
    *args, check = check,
)

fun make(vararg targets: Any, quiet: Boolean = false) =
    run("make", "-C", KSRC, "O=$KOUT", *targets, env = CROSS, quiet = quiet)

fun patchesIn(dir: File): List<File> =
    dir.listFiles { f -> f.name.endsWith(".patch") }?.sortedBy { it.name } ?: emptyList()

/** CONFIG_X=... lines of a config file */
fun options(f: File): List<String> = f.readLines().filter { it.startsWith("CONFIG_") }

// 1. pristine tarball + the base project's patches + ours (re-applied each build)
if (!KSRC.resolve(".git").isDirectory) {
    val tmp = TOP.resolve("src/tmp")
    KSRC.removeTree()
    tmp.removeTree()
    tmp.mkdirs()
    run("tar", "xf", TARBALL, "-C", tmp)
    tmp.resolve("linux-$VER").renameTo(KSRC)
    tmp.delete()
    git("init", "-q")
    git("add", "-A")
    git("commit", "-q", "-m", "linux-$VER")
    git("tag", "base")
}
git("am", "--abort", check = false)
git("reset", "-q", "--hard", "base")
git("clean", "-qfd")
git("am", "-q", *patchesIn(BASE.resolve("patches")).toTypedArray())
patchesIn(TOP.resolve("patches")).takeIf { it.isNotEmpty() }?.let { git("am", "-q", *it.toTypedArray()) }
val DTS_DIR = KSRC.resolve("arch/arm64/boot/dts/qcom")
BASE.resolve("dts").listFiles { f -> f.name.contains(".dts") }!!.forEach {
    it.copyTo(DTS_DIR.resolve(it.name), overwrite = true)
}
// this project's own DT additions (dts/*.dtsi, e.g. the sensor hub): copied
// next to the base project's and included by its boards (*-learn.dts)
val ourDtsi = TOP.resolve("dts").listFiles { f -> f.name.endsWith(".dtsi") }?.sortedBy { it.name } ?: emptyList()
ourDtsi.forEach { it.copyTo(DTS_DIR.resolve(it.name), overwrite = true) }
DTS_DIR.listFiles { f -> f.name.endsWith("-learn.dts") }!!.forEach { board ->
    board.appendText(ourDtsi.joinToString("") { "#include \"${it.name}\"\n" })
}

// 2. config: defconfig + the two fragments
KOUT.mkdirs()
make("defconfig", quiet = true)
run(KSRC.resolve("scripts/kconfig/merge_config.sh"), "-m", "-O", KOUT, KOUT.resolve(".config"),
    *FRAGMENTS.toTypedArray(), env = CROSS, quiet = true)
// defconfig's thousands of =m drivers are never used on the phone: keep only
// the modules our fragments ask for (selects bring their helpers back)
val wanted = FRAGMENTS.flatMap(::options).filter { it.endsWith("=m") }.map { it.substringBefore('=') }.toSet()
val config = KOUT.resolve(".config")
config.writeText(config.readLines().joinToString("\n", postfix = "\n") { line ->
    val name = line.substringBefore('=')
    if (line.endsWith("=m") && name !in wanted) "# $name is not set" else line
})
make("olddefconfig", quiet = true)

// fragment options that did not stick (a missing dependency)
val have = config.readLines()
for (opt in FRAGMENTS.flatMap(::options)) {
    if (opt !in have) {
        val name = opt.substringBefore('=')
        val actual = have.firstOrNull { it.startsWith("$name=") || it == "# $name is not set" }
        println("WARNING: not set: $opt (have: ${actual ?: "nothing"})")
    }
}

// 3. build: kernel, DTBs, modules
make("-j$JOBS", "Image.gz", "modules")
for (rev in listOf("rev-101", "rev-10")) make("qcom/msm8992-lg-bullhead-$rev-learn.dtb")
val modules = OUT.resolve("modules")
modules.removeTree()
make("INSTALL_MOD_PATH=$modules", "INSTALL_MOD_STRIP=1", "modules_install", quiet = true)
// (Files.walk doesn't follow modules_install's build/source links into the kernel trees)
val installed = java.nio.file.Files.walk(modules.toPath()).use { s ->
    s.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
}
println("modules: ${installed.count { it.toString().endsWith(".ko") }}, " +
    "${installed.sumOf { java.nio.file.Files.size(it) } shr 10} KiB")

// 4. LK loads a 64-bit kernel at 0x80000 and the DTB at 0x1e00000: the
// Image's full in-memory size (header offset 16, BSS included) must fit in
// between
val image = KOUT.resolve("arch/arm64/boot/Image")
val size = RandomAccessFile(image, "r").use { f ->
    f.seek(16)
    java.lang.Long.reverseBytes(f.readLong())   // little-endian u64
}
val limit = 0x1e00000L - 0x80000L
println("kernel image_size: $size bytes (%.1f MiB), limit $limit (${(limit - size) shr 10} KiB left)"
    .format(size / 1048576.0))
if (size >= limit) die("ERROR: kernel overlaps LK's DTB/ramdisk area")
println("${KOUT.resolve("arch/arm64/boot/Image.gz")}: ${KOUT.resolve("arch/arm64/boot/Image.gz").length()} bytes")
