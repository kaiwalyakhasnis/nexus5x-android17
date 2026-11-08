#!/usr/bin/env kotlin
// Build the vendor's resource overlays (vendor/overlay-*/) into
// out/overlay/Bullhead-*.apk with the Android SDK on this PC (aapt2,
// zipalign, apksigner), for make-vendor.main.kts to put in /vendor/overlay.
//
// Environment: BT (an SDK build-tools dir), JAR (an SDK android.jar).
@file:Import("lib.main.kts")

import java.io.File
import java.nio.file.Files

/** "34.0.0" < "35.0.1": compare dotted version numbers */
fun versionKey(name: String): List<Int> = Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()

fun newest(dir: File, prefix: String = ""): File =
    dir.listFiles { f -> f.name.startsWith(prefix) }!!.maxWithOrNull { a, b ->
        val ka = versionKey(a.name)
        val kb = versionKey(b.name)
        ka.zip(kb).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: ka.size.compareTo(kb.size)
    } ?: die("nothing in $dir")

val SDK = HOME.resolve("Android/Sdk")
val BT = File(env("BT", newest(SDK.resolve("build-tools")).path))
val JAR = File(env("JAR", newest(SDK.resolve("platforms"), "android-").resolve("android.jar").path))
val out = OUT.resolve("overlay")
out.removeTree()
out.mkdirs()

for (dir in TOP.resolve("vendor").listFiles { f -> f.isDirectory && f.name.startsWith("overlay-") }!!.sorted()) {
    val name = dir.name.removePrefix("overlay-")
    val w = Files.createTempDirectory("overlay-").toFile()
    try {
        run(BT.resolve("aapt2"), "compile", "--dir", dir.resolve("res"), "-o", w.resolve("res.zip"))
        run(BT.resolve("aapt2"), "link", "-o", w.resolve("u.apk"), "-I", JAR,
            "--manifest", dir.resolve("AndroidManifest.xml"), w.resolve("res.zip"))
        run(BT.resolve("zipalign"), "-f", "4", w.resolve("u.apk"), w.resolve("a.apk"))
        val apk = out.resolve("Bullhead-$name.apk")
        run(BT.resolve("apksigner"), "sign", "--ks", HOME.resolve(".android/debug.keystore"),
            "--ks-pass", "pass:android", "--out", apk, w.resolve("a.apk"))
        log("built $apk")
    } finally {
        w.removeTree()
    }
}
