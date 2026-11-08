#!/usr/bin/env kotlin
// out/vendor.img: the Android 17 emulator's vendor image, adapted to bullhead.
//
// The GSI is only /system; a device brings its own /vendor (HALs, SELinux
// policy, init scripts). bullhead's own is Android 8.1-era and speaks 3.10 CAF
// kernel interfaces, so the base here is the vendor partition of Google's
// Android 17 arm64 emulator image (same SELinux version, 202604, as the GSI).
// It is edited in place with debugfs (no root, no loop mount), and every file
// added gets the SELinux label Android's file_contexts give its path:
//
//   /vendor/etc/init/hw/init.bullhead.rc   (vendor/init.bullhead.rc)
//   /vendor/etc/fstab.bullhead             (vendor/fstab.bullhead)
//   /vendor/etc/adb_keys                   this PC's ~/.android/adbkey.pub
//   /vendor/lib/modules/<kver>/            the =m kernel modules + modules.load
//   /vendor/lib64/egl/                     Mesa (scripts/build-mesa.main.kts)
//   /vendor/lib64/libhwc_msm_shim.so       tools/hwc-msm-shim.c, built here
//   /vendor/bin/hw/android.hardware.sensors-service  hal/sensors
//   /vendor/bin/hw/android.hardware.lights-service.bullhead  hal/lights
//   /vendor/bin/hw/android.hardware.vibrator-service.bullhead  hal/vibrator
//                                          (scripts/build-hal.main.kts)
//   /vendor/firmware/ath10k/, init.wifi.bullhead.rc  Wi-Fi (vendor/wifi): the
//                                          emulator's Wi-Fi HAL/supplicant kept
//   /vendor/overlay/                       scripts/build-overlays.main.kts
//   /vendor/etc/selinux/vendor_sepolicy.cil  + typepermissive for every domain
//   (precompiled_sepolicy removed: init compiles the policy at boot)
//
// Environment: VENDOR_SIZE, the filesystem size (the phone's vendor
// partition is 248M; default 384M); STOCK_VENDOR, the stock vendor
// partition's backup, for the Wi-Fi calibration.
@file:Import("lib.main.kts")

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

val EMU = TOP.resolve("downloads/emu/extracted/arm64-v8a")
val GSI = TOP.resolve("downloads/gsi/extracted/system.img")
val SIZE = env("VENDOR_SIZE", "384M")
val NDK_BIN = TOP.resolve("tools/android-ndk-r28c/toolchains/llvm/prebuilt/linux-x86_64/bin")

// Emulator HALs that need emulator hardware (virtio sensors/modem/GNSS,
// goldfish camera and codecs, virtio Bluetooth): declared in the VINTF
// manifest, their clients wait for them forever (system_server blocks on
// ISensors/default). Drop their manifests, services and the features they'd
// advertise, until bullhead has real ones.
val REMOVE = listOf(
    "etc/vintf/manifest/android.hardware.sensors-multihal.xml",
    "etc/init/android.hardware.sensors-service-multihal.rc",
    // the AOSP example lights HAL: it only logs (hal/lights replaces it)
    "etc/vintf/manifest/lights-default.xml",
    "etc/init/lights-default.rc",
    "etc/vintf/manifest/android.hardware.radio.ranchu.xml",
    "etc/init/android.hardware.radio.ranchu.rc",
    "etc/vintf/manifest/android.hardware.gnss-service.ranchu.xml",
    "etc/init/android.hardware.gnss-service.ranchu.rc",
    "etc/vintf/manifest/android.hardware.camera.provider.ranchu.xml",
    "etc/vintf/manifest/android.hardware.camera.provider@2.7-service-google.xml",
    "etc/init/android.hardware.camera.provider.ranchu.rc",
    "etc/init/android.hardware.camera.provider.ranchu_minigbm.rc",
    "etc/init/android.hardware.camera.provider@2.7-service-google.rc",
    "etc/vintf/manifest/android.hardware.biometrics.fingerprint-service.ranchu.xml",
    "etc/init/android.hardware.biometrics.fingerprint-service.ranchu.rc",
    "etc/vintf/manifest/android.hardware.media.c2-service-goldfish.xml",
    "etc/init/android.hardware.media.c2-service-goldfish.rc",
    "etc/vintf/manifest/bluetooth-service-default.xml",
    "etc/vintf/manifest/bluetooth_audio.xml",
    "etc/init/bluetooth-service-default.rc",
    // vendor APEXes for emulator-only hardware: ContextHub (system_server's
    // ContextHubSystemService throws at boot phase 500), UWB (/dev/hvc2), and
    // the Thread network radio simulator
    "apex/com.android.hardware.contexthub.apex",
    "apex/com.android.hardware.uwb.apex",
    "apex/com.android.hardware.threadnetwork-simulation-rcp.apex",
    // the AOSP example vibrator (IVibrator V4, does nothing): hal/vibrator
    // replaces it, and two IVibrator/default make the whole VINTF manifest
    // invalid (Android then can't finish booting)
    "apex/com.android.hardware.vibrator.apex",
) + listOf(
    "android.hardware.bluetooth_le.xml", "android.hardware.bluetooth.xml",
    "android.hardware.camera.concurrent.prebuilt.xml",
    "android.hardware.camera.flash-autofocus.prebuilt.xml",
    "android.hardware.camera.front.prebuilt.xml",
    "android.hardware.camera.full.prebuilt.xml",
    "android.hardware.camera.raw.prebuilt.xml",
    "android.hardware.fingerprint.prebuilt.xml", "android.hardware.uwb.xml",
    "android.hardware.vulkan.compute.xml", "android.hardware.vulkan.level.xml",
    "android.hardware.vulkan.version.xml", "android.software.vulkan.deqp.level.xml",
    "android.hardware.opengles.aep.xml",
    // Wi-Fi itself stays (vendor/wifi); not Wi-Fi Direct (no p2p0) or Passpoint
    "android.hardware.wifi.direct.xml", "android.hardware.wifi.passpoint.xml",
).map { "etc/permissions/$it" }

// The emulator's gralloc and hwcomposer only know virtio-gpu. Same-length
// byte edits make them take the Adreno/MDP5 msm device instead:
//  - minigbm: "vkms" (one of its dumb-buffer drivers) becomes "msm", so msm
//    buffers are dumb buffers (linear, from the VRAM carveout; kernel patch
//    0003 allows them on the render node minigbm opens)
//  - ranchu HWC3: it looks for a DRM device named "virtio_gpu". (The name is
//    also compared inline, so this edit alone isn't enough: the
//    libhwc_msm_shim.so LD_PRELOAD does the real work.)
fun z(s: String) = ("\u0000" + s + "\u0000").toByteArray()
val BINARY_PATCHES = listOf(
    "/lib64/libminigbm_gralloc.so" to listOf(z("vkms") to z("msm\u0000")),
    "/bin/hw/android.hardware.graphics.composer3-service.ranchu" to
        listOf(z("virtio_gpu") to z("msm\u0000\u0000\u0000\u0000\u0000\u0000\u0000")),
)

fun fsck(img: File, vararg extra: String) {
    // 0: clean, 1: errors fixed; anything else is a real problem
    val code = run("e2fsck", "-fy", *extra, img, quiet = true, check = false)
    if (code > 1) die("e2fsck ${extra.joinToString(" ")} $img: exit $code")
}

fun debugfsRead(img: File, path: String): ByteArray {
    val t = File.createTempFile("debugfs-", null)
    try {
        run("debugfs", "-R", "dump $path $t", img, quiet = true)
        return t.readBytes()
    } finally {
        t.delete()
    }
}

fun debugfsLabel(img: File, path: String): String {
    val out = capture("debugfs", "-R", "ea_get $path security.selinux", img, check = false, stderr = false)
    return Regex("(u:object_r:[^\\s\"\\\\]+)").find(out)?.groupValues?.get(1) ?: die("no label: $path: $out")
}

/** (offset, size) in bytes of a GPT partition in an emulator disk image. */
fun gptPartition(disk: File, name: String): Pair<Long, Long> = RandomAccessFile(disk, "r").use { f ->
    fun read(at: Long, n: Int) = ByteArray(n).also { f.seek(at); f.readFully(it) }
    val h = ByteBuffer.wrap(read(512, 92)).order(ByteOrder.LITTLE_ENDIAN)
    check(String(read(512, 8)) == "EFI PART") { "$disk: no GPT" }
    val lba = h.getLong(72)
    val n = h.getInt(80)
    val sz = h.getInt(84)
    for (i in 0 until n) {
        val e = ByteBuffer.wrap(read(lba * 512 + i.toLong() * sz, sz)).order(ByteOrder.LITTLE_ENDIAN)
        val first = e.getLong(32)
        val last = e.getLong(40)
        val entryName = String(e.array(), 56, 72, Charsets.UTF_16LE).trimEnd('\u0000')
        if (entryName == name) return first * 512 to (last - first + 1) * 512
    }
    die("no $name partition in $disk")
}

/** file_contexts lookup: the last matching line wins (as libselinux). */
class Labeler(vararg texts: String) {
    private val rules = texts.flatMap { it.lines() }.mapNotNull { line ->
        var p = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (p.size < 2 || p[0].startsWith("#")) return@mapNotNull null
        if (!(p[0].startsWith("/vendor") || p[0].startsWith("/(vendor|system/vendor)"))) return@mapNotNull null
        if (p.size == 3) p = listOf(p[0], p[2])  // "-d", "--" file-type selectors: ignore the type
        Regex(p[0]) to p[1]
    }

    operator fun invoke(path: String): String =
        rules.lastOrNull { (rx, _) -> rx.matches(path) }?.second ?: "u:object_r:vendor_file:s0"
}

/** every type in the 'domain' attribute (plat + vendor) not yet permissive */
fun permissiveDomains(platCil: String, vendorCil: String): List<String> {
    fun all(rx: String, text: String) =
        Regex(rx, RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
    fun domainMembers(cil: String) =
        all("^\\(typeattributeset domain \\((.*)\\)\\)\\s*$", cil).flatMap { it.split(" ").filter(String::isNotEmpty) }.toSet()
    val types = all("^\\(type (\\S+)\\)", platCil) + all("^\\(type (\\S+)\\)", vendorCil)
    val already = all("^\\(typepermissive (\\S+)\\)", platCil + vendorCil)
    return ((domainMembers(platCil) + domainMembers(vendorCil)) intersect types).minus(already).sorted()
}

fun ByteArray.indexesOf(pat: ByteArray): List<Int> = (0..size - pat.size).filter { i ->
    pat.indices.all { this[i + it] == pat[it] }
}

/** tools/hwc-msm-shim.c -> out/shim/libhwc_msm_shim.so (NDK, when changed) */
fun hwcShim(): File {
    val src = TOP.resolve("tools/hwc-msm-shim.c")
    val so = OUT.resolve("shim/libhwc_msm_shim.so")
    if (so.exists() && so.lastModified() > src.lastModified()) return so
    so.parentFile.mkdirs()
    run(NDK_BIN.resolve("aarch64-linux-android34-clang"), "-O2", "-fPIC", "-shared", "-Wall",
        "-o", so, src, "-ldl")
    run(NDK_BIN.resolve("llvm-strip"), so)
    return so
}

// --- 1. the emulator's vendor partition, grown (room for modules, Mesa)
OUT.mkdirs()
val img = OUT.resolve("vendor.img")
val tmpImg = OUT.resolve("vendor.img.tmp")
val kver = OUT.resolve("kernel/include/config/kernel.release").readText().trim()
val (offset, size) = gptPartition(EMU.resolve("vendor.img"), "vendor")
RandomAccessFile(EMU.resolve("vendor.img"), "r").use { src ->
    tmpImg.outputStream().use { dst ->
        src.seek(offset)
        val buf = ByteArray(1 shl 20)
        var left = size
        while (left > 0) {
            val n = src.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            dst.write(buf, 0, n)
            left -= n
        }
    }
}
fsck(tmpImg)
run("resize2fs", tmpImg, SIZE, quiet = true)
// Android builds this image with ext4 "shared_blocks": identical blocks of
// different files are stored once. debugfs doesn't know: rm frees a shared
// block that other files still use, and the next write lands on them (it
// corrupted the power/thermal APEXes). Give every file its own blocks first
// (this also clears the feature).
fsck(tmpImg, "-E", "unshare_blocks")
Files.move(tmpImg.toPath(), img.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

// SELinux labels for our HAL binaries whose names the emulator's policy
// doesn't know: an existing HAL domain's exec type
val OUR_FILE_CONTEXTS = """
    |# nexus5x-android17 HALs (hal/)
    |/(vendor|system/vendor)/bin/hw/android\.hardware\.lights-service\.bullhead  u:object_r:hal_light_default_exec:s0
    |/(vendor|system/vendor)/bin/hw/android\.hardware\.vibrator-service\.bullhead  u:object_r:hal_vibrator_default_exec:s0
    |""".trimMargin()
val vendorFileContexts = debugfsRead(img, "/etc/selinux/vendor_file_contexts").decodeToString()
    .trimEnd('\n') + "\n\n" + OUR_FILE_CONTEXTS + "\n"
val label = Labeler(
    debugfsRead(GSI, "/system/etc/selinux/plat_file_contexts").decodeToString(),
    vendorFileContexts,
)

// --- the debugfs commands, collected and run in one go at the end
val work: File = Files.createTempDirectory("vendor-").toFile()
val cmds = mutableListOf<String>()
val labelFiles = mutableMapOf<String, File>()
val madeDirs = mutableSetOf<String>()

/** a file holding the label (ea_set -f: the value includes the trailing NUL) */
fun labelFile(ctx: String): File = labelFiles.getOrPut(ctx) {
    work.resolve("label${labelFiles.size}").apply { writeBytes(ctx.toByteArray() + 0) }
}

fun setMeta(vpath: String, mode: Int, uid: Int = 0, gid: Int = 0, ctx: String = label("/vendor$vpath")) {
    cmds += "set_inode_field $vpath mode 0${mode.toString(8)}"
    cmds += "set_inode_field $vpath uid $uid"
    cmds += "set_inode_field $vpath gid $gid"
    cmds += "ea_set -f ${labelFile(ctx)} $vpath security.selinux"
}

fun mkdir(vpath: String) {
    val parts = vpath.trim('/').split('/')
    for (i in 1..parts.size) {
        val d = "/" + parts.take(i).joinToString("/")
        if (!madeDirs.add(d)) continue
        cmds += "mkdir $d"
        setMeta(d, "40755".toInt(8), 0, 2000)
    }
}

fun put(host: File, vpath: String, mode: Int = "100644".toInt(8), gid: Int = 0) {
    cmds += "rm $vpath"
    cmds += "write $host $vpath"
    setMeta(vpath, mode, 0, gid)
}

fun putText(text: String, vpath: String) {
    val f = work.resolve("f${cmds.size}")
    f.writeText(text)
    put(f, vpath)
}

/** os.walk order: a directory's files (sorted), then its subdirectories (sorted) */
fun walkSorted(dir: File, visit: (File, List<File>) -> Unit) {
    val entries = dir.listFiles()!!.filterNot { Files.isSymbolicLink(it.toPath()) }.sortedBy { it.name }
    visit(dir, entries.filter { it.isFile })
    entries.filter { it.isDirectory }.forEach { walkSorted(it, visit) }
}

// --- 2. our init script, fstab, adb key
put(TOP.resolve("vendor/init.bullhead.rc"), "/etc/init/hw/init.bullhead.rc")
put(TOP.resolve("vendor/fstab.bullhead"), "/etc/fstab.bullhead")
put(HOME.resolve(".android/adbkey.pub"), "/etc/adb_keys")

// --- 3. kernel modules (the ramdisk's /init loads them before Android's init)
val modulesRoot = OUT.resolve("modules")
val moduleNames = mutableListOf<String>()
walkSorted(modulesRoot.resolve("lib/modules/$kver")) { dir, files ->
    val rel = dir.relativeTo(modulesRoot).path
    mkdir("/$rel")
    for (f in files) {
        put(f, "/$rel/${f.name}")
        if (f.name.endsWith(".ko")) moduleNames += f.name.removeSuffix(".ko")
    }
}
// ath10k: loaded by Android's init instead (vendor/wifi/init.wifi.bullhead.rc),
// once /vendor/firmware is where the kernel looks
moduleNames -= setOf("ath10k_core", "ath10k_pci")
putText(moduleNames.joinToString("\n", postfix = "\n"), "/lib/modules/$kver/modules.load")

// --- 4. graphics: Mesa as the EGL/GLES driver, the HWC shim, the A420
// microcode, and two of the emulator's blobs taught about msm
val mesa = OUT.resolve("mesa/lib64")
if (mesa.isDirectory) {
    mkdir("/lib64/egl")
    for (f in mesa.resolve("egl").listFiles()!!.sortedBy { it.name }) put(f, "/lib64/egl/${f.name}")
    // libgallium-*.so: next to the EGL libs
    for (f in mesa.listFiles { f -> f.isFile && f.name.endsWith(".so") }!!.sortedBy { it.name })
        put(f, "/lib64/egl/${f.name}")
}
// the HWC3's drmGetVersion() shim (LD_PRELOAD, vendor/init.bullhead.rc)
put(hwcShim(), "/lib64/libhwc_msm_shim.so")
// the sensors HAL (hal/sensors, on the nanohub): this binary name already
// has the hal_sensors_default label in the emulator's file_contexts
val sensorsHal = OUT.resolve("hal/android.hardware.sensors-service")
if (!sensorsHal.isFile) die("no $sensorsHal: run scripts/build-hal.main.kts")
put(sensorsHal, "/bin/hw/android.hardware.sensors-service", "100755".toInt(8), 2000)
put(TOP.resolve("hal/sensors/android.hardware.sensors-service.rc"), "/etc/init/android.hardware.sensors-service.rc")
put(TOP.resolve("hal/sensors/android.hardware.sensors-service.xml"), "/etc/vintf/manifest/android.hardware.sensors-service.xml")
put(TOP.resolve("hal/sensors/android.hardware.sensors.bullhead.xml"), "/etc/permissions/android.hardware.sensors.bullhead.xml")
// the lights HAL (hal/lights): backlight and the RGB notification LED
val lightsHal = OUT.resolve("hal/android.hardware.lights-service.bullhead")
if (!lightsHal.isFile) die("no $lightsHal: run scripts/build-hal.main.kts")
put(lightsHal, "/bin/hw/android.hardware.lights-service.bullhead", "100755".toInt(8), 2000)
put(TOP.resolve("hal/lights/android.hardware.lights-service.bullhead.rc"), "/etc/init/android.hardware.lights-service.bullhead.rc")
put(TOP.resolve("hal/lights/android.hardware.lights-service.bullhead.xml"), "/etc/vintf/manifest/android.hardware.lights-service.bullhead.xml")
// the vibrator HAL (hal/vibrator): the PMI8994 haptics (kernel patches/0006)
val vibratorHal = OUT.resolve("hal/android.hardware.vibrator-service.bullhead")
if (!vibratorHal.isFile) die("no $vibratorHal: run scripts/build-hal.main.kts")
put(vibratorHal, "/bin/hw/android.hardware.vibrator-service.bullhead", "100755".toInt(8), 2000)
put(TOP.resolve("hal/vibrator/android.hardware.vibrator-service.bullhead.rc"), "/etc/init/android.hardware.vibrator-service.bullhead.rc")
put(TOP.resolve("hal/vibrator/android.hardware.vibrator-service.bullhead.xml"), "/etc/vintf/manifest/android.hardware.vibrator-service.bullhead.xml")
putText(vendorFileContexts, "/etc/selinux/vendor_file_contexts")
// the sensor hub's device nodes (kernel patches/0004), for the HAL (system)
putText(debugfsRead(img, "/etc/ueventd.rc").decodeToString() + """
    |
    |# nexus5x-android17: the sensor hub (drivers/staging/nanohub)
    |/dev/nanohub              0660   system     system
    |/dev/nanohub_comms        0660   system     system
    |""".trimMargin(), "/etc/ueventd.rc")
// resource overlays: circular icons, ...
OUT.resolve("overlay").listFiles { f -> f.name.endsWith(".apk") }?.sortedBy { it.name }?.forEach {
    put(it, "/overlay/${it.name}")
}
mkdir("/firmware/qcom")
for (f in listOf("a420_pm4.fw", "a420_pfp.fw", "LICENSE.qcom"))
    put(TOP.resolve("downloads/linux-firmware/$f"), "/firmware/qcom/$f")

// --- Wi-Fi (QCA6174, ath10k): firmware from linux-firmware, the phone's
// own calibration (board data) from its stock vendor partition's backup,
// and the init script (vendor/wifi) that loads ath10k once they're visible
val ATH10K = "/firmware/ath10k/QCA6174/hw3.0"
mkdir(ATH10K)
for (f in listOf("firmware-6.bin", "notice_ath10k_firmware-6.txt"))
    put(TOP.resolve("downloads/linux-firmware/$f"), "$ATH10K/$f")
for (f in listOf("regulatory.db", "regulatory.db.p7s"))
    put(TOP.resolve("downloads/linux-firmware/$f"), "/firmware/$f")
val stockVendor = TOP.resolve(env("STOCK_VENDOR", "backup/vendor-mmcblk1p39.img"))
if (stockVendor.isFile) {
    val board = work.resolve("board.bin").apply { writeBytes(debugfsRead(stockVendor, "/firmware/bdwlan30.bin")) }
    if (board.length() == 0L) die("no /firmware/bdwlan30.bin in $stockVendor")
    put(board, "$ATH10K/board.bin")
} else {
    log("warning: no $stockVendor (STOCK_VENDOR): no Wi-Fi calibration, ath10k will not start")
}
putText(TOP.resolve("vendor/wifi/init.wifi.bullhead.rc").readText().replace("@KVER@", kver),
    "/etc/init/init.wifi.bullhead.rc")
for ((vpath, edits) in BINARY_PATCHES) {
    var data = debugfsRead(img, vpath)
    for ((old, new) in edits) {
        check(old.size == new.size) { "$vpath: edit changes the length" }
        val at = data.indexesOf(old)
        check(at.size == 1) { "$vpath: ${at.size} matches for ${old.decodeToString()}" }
        data = data.copyOf().also { new.copyInto(it, at[0]) }
    }
    val ctx = debugfsLabel(img, vpath)
    val f = work.resolve(File(vpath).name).apply { writeBytes(data) }
    val bin = "/bin/" in vpath
    cmds += "rm $vpath"
    cmds += "write $f $vpath"
    // the blob keeps its own label
    setMeta(vpath, (if (bin) "100755" else "100644").toInt(8), 0, if (bin) 2000 else 0, ctx)
}

for (f in REMOVE) cmds += "rm /$f"

// --- 5. SELinux: every domain permissive; drop the emulator's precompiled
// policy so init compiles plat (GSI) + mapping + this vendor policy
val platCil = debugfsRead(GSI, "/system/etc/selinux/plat_sepolicy.cil").decodeToString()
val vendorCil = debugfsRead(img, "/etc/selinux/vendor_sepolicy.cil").decodeToString()
val domains = permissiveDomains(platCil, vendorCil)
putText(vendorCil + "\n;; nexus5x-android17: bring-up, every domain permissive\n" +
    domains.joinToString("") { "(typepermissive $it)\n" }, "/etc/selinux/vendor_sepolicy.cil")
for (f in listOf("precompiled_sepolicy",
        "precompiled_sepolicy.plat_sepolicy_and_mapping.sha256",
        "precompiled_sepolicy.product_sepolicy_and_mapping.sha256",
        "precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256"))
    cmds += "rm /etc/selinux/$f"

// --- run them
val script = work.resolve("debugfs.cmds").apply { writeText(cmds.joinToString("\n", postfix = "\n")) }
val p = ProcessBuilder("debugfs", "-w", "-f", script.path, img.path)
    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
val errors = p.errorStream.bufferedReader().readLines().filter {
    it.isNotBlank() && !it.startsWith("debugfs ") &&
        "File not found by ext2_lookup" !in it && "Ext2 directory already exists" !in it
}
p.waitFor()
errors.take(20).forEach(System.err::println)
fsck(img)
work.removeTree()
println("$img: ${moduleNames.size} modules, ${domains.size} permissive domains, $SIZE")
