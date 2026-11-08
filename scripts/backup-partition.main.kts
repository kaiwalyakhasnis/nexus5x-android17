#!/usr/bin/env kotlin
// backup-partition.main.kts NAME DEV: copy the phone's eMMC partition
// /dev/DEV (raw, read-only) to backup/NAME-DEV.img over the USB network, then
// compare its SHA-256 with one the phone computes from the partition itself.
// Needs a root shell on the phone at 172.16.42.1:23 (the BusyBox kernel or
// this project's ramdisk) that doesn't have the partition mounted.
@file:Import("lib.main.kts")

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

if (args.size != 2) die("usage: backup-partition.main.kts NAME DEV   (e.g. vendor mmcblk1p39)")
val (name, dev) = args
val port = env("PORT", "10891").toInt()
val backup = TOP.resolve("backup").apply { mkdirs() }
val out = backup.resolve("$name-$dev.img")

// the PC listens on the USB network; the phone's dd sends it the partition
val server = ServerSocket().apply { bind(InetSocketAddress(Phone.PC, port)) }
var received = 0L
val receiver = thread {
    server.accept().use { s -> received = s.getInputStream().saveTo(out) }
}
log("$name: copying /dev/$dev to $out")
// one shell session: the copy, then the phone's checksum (can take minutes)
val phoneOut = Phone.sh(
    "dd if=/dev/$dev bs=1M 2>/dev/null | nc ${Phone.PC} $port; sha256sum /dev/$dev",
    timeoutSec = 4 * 3600,
) ?: die("$name: the rescue shell didn't answer or finish")
receiver.join()
server.close()
val phoneSum = Regex("^([0-9a-f]{64})  /dev/$dev$", RegexOption.MULTILINE).find(phoneOut)?.groupValues?.get(1)

val md = MessageDigest.getInstance("SHA-256")
out.inputStream().use { inp ->
    val buf = ByteArray(1 shl 20)
    while (true) {
        val n = inp.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
}
val pcSum = md.digest().joinToString("") { "%02x".format(it) }
println("$name: $received bytes, phone $phoneSum")
println("$name:                 PC    $pcSum")
if (phoneSum == null || phoneSum != pcSum) die("$name: MISMATCH")
println("$name: OK")
backup.resolve("SHA256SUMS").appendText("$pcSum  ${out.name}\n")
