#!/usr/bin/env kotlin
// Build the HALs in hal/ with the Android SDK and NDK only, no AOSP tree:
//
//   out/hal/android.hardware.sensors-service         hal/sensors (the nanohub)
//   out/hal/android.hardware.lights-service.bullhead  hal/lights (backlight, LED)
//
// for make-vendor.main.kts to put in /vendor/bin/hw. How, without AOSP:
//   - the interfaces (AIDL, frozen versions) and binder's C++ helper headers
//     come from AOSP git, a few MB (downloads/aosp, sparse, fetched here)
//   - the SDK's aidl turns the AIDL into NDK-backend C++ (stable binder C API)
//   - the NDK's clang builds it; libc++ is linked in statically, so only C
//     APIs cross into Android: libbinder_ndk (linked against the GSI's own
//     copy, which has the service manager calls the NDK stub leaves out),
//     liblog, libc
//
// Environment: AIDL (the SDK's aidl), NDK (an NDK dir).
@file:Import("lib.main.kts")

import java.io.File
import java.nio.file.Files

val SDK = HOME.resolve("Android/Sdk")
val AIDL = File(env("AIDL", SDK.resolve("build-tools").listFiles()!!.maxBy { it.name }.resolve("aidl").path))
val NDK = File(env("NDK", TOP.resolve("tools/android-ndk-r28c").path))
val NDK_BIN = NDK.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
val GSI = TOP.resolve("downloads/gsi/extracted/system.img")
val AOSP = TOP.resolve("downloads/aosp")
val AOSP_TAG = "android-17.0.0_r1"   // the GSI: CP3A.260905.010
val API = 35                         // the NDK's newest; the phone runs 37
val HAL_OUT = OUT.resolve("hal")

// --- 1. AOSP sources: sparse, shallow clones of just what is needed
fun fetch(name: String, repo: String, vararg paths: String) {
    val dir = AOSP.resolve(name)
    if (dir.resolve(".git").isDirectory) return
    AOSP.mkdirs()
    run("git", "clone", "-q", "--filter=blob:none", "--sparse", "--depth", "1", "--branch", AOSP_TAG,
        "https://android.googlesource.com/$repo", dir)
    run("git", "-C", dir, "sparse-checkout", "set", "--no-cone", *paths)
}
fetch("interfaces", "platform/hardware/interfaces", "/sensors/aidl", "/common/aidl", "/common/fmq/aidl", "/light/aidl")
fetch("native", "platform/frameworks/native", "/libs/binder/ndk/include_cpp",
    "/libs/binder/ndk/include_platform", "/libs/binder/ndk/include_ndk")

// --- 2. AIDL -> C++ (NDK backend), from the frozen API versions
val api = AOSP.resolve("interfaces")
class Iface(val dir: File, val version: Int)
val sensorsIfaces = listOf(
    Iface(api.resolve("sensors/aidl/aidl_api/android.hardware.sensors/3"), 3),
    Iface(api.resolve("common/aidl/aidl_api/android.hardware.common/2"), 2),
    Iface(api.resolve("common/fmq/aidl/aidl_api/android.hardware.common.fmq/1"), 1),
)

fun generate(gen: File, ifaces: List<Iface>) {
    gen.removeTree()
    for (i in ifaces) {
        val files = Files.walk(i.dir.toPath()).use { s ->
            s.filter { it.toString().endsWith(".aidl") }.map { it.toFile() }.sorted().toList()
        }
        run(AIDL, "--lang=ndk", "--structured", "--stability=vintf", "--version=${i.version}",
            "--hash=${i.dir.resolve(".hash").readLines().last().trim()}",
            *ifaces.flatMap { listOf("-I", it.dir.path) }.toTypedArray(),
            "-h", gen.resolve("include"), "-o", gen.resolve("src"), *files.toTypedArray())
    }
}

// --- 3. the GSI's libbinder_ndk.so, to link against
val libDir = HAL_OUT.resolve("lib").apply { mkdirs() }
val binderLib = libDir.resolve("libbinder_ndk.so")
if (!binderLib.exists()) run("debugfs", "-R", "dump /system/lib64/libbinder_ndk.so $binderLib", GSI, quiet = true)
if (!binderLib.exists()) die("could not get libbinder_ndk.so out of $GSI")

fun cppFiles(dir: File): List<File> = Files.walk(dir.toPath()).use { s ->
    s.filter { it.toString().endsWith(".cpp") }.map { it.toFile() }.sorted().toList()
}

/** One HAL: the .cpp files in hal/<name> + its generated interfaces -> out/hal/<binary> */
fun build(name: String, binary: String, ifaces: List<Iface>) {
    val src = TOP.resolve("hal/$name")
    val gen = HAL_OUT.resolve("gen-$name")
    generate(gen, ifaces)
    val objDir = HAL_OUT.resolve("obj-$name").apply { removeTree(); mkdirs() }
    val binder = AOSP.resolve("native/libs/binder/ndk")
    // vendor code, with binder stability (as Soong builds a vendor HAL):
    // marks our binder VINTF-stable, or servicemanager refuses it
    val flags = listOf("-std=c++20", "-O2", "-Wall", "-Wno-unused-parameter", "-fPIE",
        "-D__ANDROID_VENDOR__", "-DBINDER_STABILITY_SUPPORT",
        "-I", gen.resolve("include").path,
        "-I", binder.resolve("include_cpp").path,
        "-I", binder.resolve("include_ndk").path,
        "-I", binder.resolve("include_platform").path)
    val cxx = NDK_BIN.resolve("aarch64-linux-android$API-clang++")
    val objs = (cppFiles(gen.resolve("src")) + cppFiles(src)).mapIndexed { i, f ->
        objDir.resolve("$i-${f.nameWithoutExtension}.o").also { o ->
            run(cxx, *flags.toTypedArray(), "-c", f, "-o", o)
        }
    }
    val out = HAL_OUT.resolve(binary)
    run(cxx, "-pie", "-static-libstdc++", "-o", out, *objs.toTypedArray(), binderLib, "-llog")
    run(NDK_BIN.resolve("llvm-strip"), out)
    log("built $out (${out.length() / 1024} KiB)")
}

val lightsIfaces = listOf(Iface(api.resolve("light/aidl/aidl_api/android.hardware.light/2"), 2))

build("sensors", "android.hardware.sensors-service", sensorsIfaces)
build("lights", "android.hardware.lights-service.bullhead", lightsIfaces)
