#!/usr/bin/env kotlin
// Build the test apps in apps/ (Kotlin, standard SDK API) into
// out/apps/<Name>.apk with the Android SDK alone, no Gradle:
//   aapt2 (manifest) -> kotlinc (against android.jar) -> d8 (+ Kotlin's
//   standard library) -> zipalign -> apksigner (the debug key)
// Install: adb install -r out/apps/SensorTest.apk
//
// Environment: BT (an SDK build-tools dir), JAR (an SDK android.jar).
@file:Import("lib.main.kts")

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun newest(dir: File, prefix: String = ""): File =
    dir.listFiles { f -> f.name.startsWith(prefix) }!!.maxByOrNull { f ->
        Regex("\\d+").findAll(f.name).joinToString(".") { it.value.padStart(4, '0') }
    } ?: die("nothing in $dir")

val SDK = HOME.resolve("Android/Sdk")
val BT = File(env("BT", newest(SDK.resolve("build-tools")).path))
val JAR = File(env("JAR", newest(SDK.resolve("platforms"), "android-").resolve("android.jar").path))
val KOTLIN = TOP.resolve("tools/kotlinc")
// d8 and apksigner run the first `java` on PATH: it must be 11+
val JDK = File(env("JDK", "/usr/lib/jvm/java-21-openjdk-amd64"))
val javaEnv = mapOf("PATH" to "${JDK.resolve("bin")}:${System.getenv("PATH")}", "JAVA_HOME" to JDK.path)
val out = OUT.resolve("apps").apply { mkdirs() }

/** apps/sensortest -> SensorTest */
fun apkName(dir: File) = mapOf("sensortest" to "SensorTest", "lightstest" to "LightsTest")[dir.name]
    ?: dir.name.replaceFirstChar { it.uppercase() }

for (app in TOP.resolve("apps").listFiles { f -> f.isDirectory }!!.sorted()) {
    val w = Files.createTempDirectory("app-").toFile()
    try {
        // resources: just the manifest
        run(BT.resolve("aapt2"), "link", "-o", w.resolve("res.apk"), "-I", JAR,
            "--manifest", app.resolve("AndroidManifest.xml"))
        // code
        val sources = app.resolve("src").walk().filter { it.extension == "kt" }.toList()
        // KOTLIN_RUNNER: set by the `kotlin` running this script; inherited, it
        // makes kotlinc act as a script runner instead of a compiler
        run(KOTLIN.resolve("bin/kotlinc"), "-cp", JAR, "-jvm-target", "17", "-no-reflect",
            "-d", w.resolve("classes.jar"), *sources.toTypedArray(),
            env = mapOf("KOTLIN_RUNNER" to ""), quiet = true)
        w.resolve("dex").mkdirs()
        // --map-diagnostics: d8 warns once per class that it can't rewrite Kotlin
        // 2.2's metadata (only matters for Kotlin reflection, not used)
        run(BT.resolve("d8"), "--release", "--min-api", "34", "--lib", JAR, "--map-diagnostics", "warning", "none", "--map-diagnostics", "info", "none",
            "--output", w.resolve("dex"), w.resolve("classes.jar"), KOTLIN.resolve("lib/kotlin-stdlib.jar"),
            env = javaEnv)
        // the manifest's package + classes.dex
        ZipOutputStream(w.resolve("u.apk").outputStream()).use { z ->
            ZipFile(w.resolve("res.apk")).use { res ->
                for (e in res.entries()) {
                    val data = res.getInputStream(e).use { it.readBytes() }
                    z.putNextEntry(ZipEntry(e.name).apply {
                        // Android 11+ maps resources.arsc: it must be stored
                        // (zipalign then aligns it)
                        if (e.name == "resources.arsc") {
                            method = ZipEntry.STORED
                            size = data.size.toLong()
                            crc = java.util.zip.CRC32().apply { update(data) }.value
                        }
                    })
                    z.write(data)
                    z.closeEntry()
                }
            }
            z.putNextEntry(ZipEntry("classes.dex"))
            w.resolve("dex/classes.dex").inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        run(BT.resolve("zipalign"), "-f", "4", w.resolve("u.apk"), w.resolve("a.apk"))
        val apk = out.resolve("${apkName(app)}.apk")
        run(BT.resolve("apksigner"), "sign", "--ks", HOME.resolve(".android/debug.keystore"),
            "--ks-pass", "pass:android", "--out", apk, w.resolve("a.apk"), env = javaEnv)
        log("built $apk (${apk.length() / 1024} KiB)")
    } finally {
        w.removeTree()
    }
}
