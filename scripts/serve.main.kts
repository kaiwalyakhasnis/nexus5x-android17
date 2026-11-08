#!/usr/bin/env kotlin
// serve.main.kts: the phone's disks, served from this PC over the USB
// network while Android runs (keep it running), for boots with
// android.disks=nbd on the kernel command line. Only the USB address
// (172.16.42.2, which the phone's DHCP server hands out) listens; nothing is
// reachable from the LAN.
//
//   10809  system  the GSI's system.img, read-only
//   10810  vendor  out/vendor.img (scripts/make-vendor.main.kts), read-only
//   10811  data    out/data.img, Android's /data (made on first use, sparse)
//   10880  HTTP /time: the clock (the phone has no RTC driver yet)
//
// qemu-nbd serves one client at a time and a rebooted phone never closes its
// old connection, so everything restarts whenever the USB link goes away.
//
// Environment: SYSTEM, DATA (image paths), DATA_SIZE (default 8G).
@file:Import("lib.main.kts")

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val SYSTEM = File(env("SYSTEM", TOP.resolve("downloads/gsi/extracted/system.img").path))
val VENDOR = OUT.resolve("vendor.img")
val DATA = File(env("DATA", OUT.resolve("data.img").path))

if (!SYSTEM.isFile) die("no $SYSTEM")
if (!VENDOR.isFile) die("no $VENDOR: run scripts/make-vendor.main.kts")
if (!DATA.isFile) {
    val size = env("DATA_SIZE", "8G")
    run("truncate", "-s", size, DATA)
    run("mkfs.ext4", "-q", "-L", "data", DATA)
    log("made $DATA ($size, sparse)")
}

fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** Is the USB network up (this PC has 172.16.42.2)? */
fun linkUp(): Boolean = NetworkInterface.getNetworkInterfaces().asSequence()
    .flatMap { it.inetAddresses.asSequence() }.any { it.hostAddress == Phone.PC }

/** The phone's clock: GET /time is the Unix time in seconds. */
fun timeServer(): HttpServer = HttpServer.create(InetSocketAddress(Phone.PC, 10880), 0).apply {
    createContext("/time") { ex ->
        val b = (System.currentTimeMillis() / 1000).toString().toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    start()
}

var servers = listOf<Process>()
var http: HttpServer? = null
fun stop() {
    servers.forEach { it.destroy() }
    servers.forEach { it.waitFor() }
    servers = emptyList()
    http?.stop(0)
    http = null
}
Runtime.getRuntime().addShutdownHook(Thread { stop() })

while (true) {
    while (!linkUp()) sleepSec(1.0)
    log("${now()} USB link up: serving system, vendor, data on ${Phone.PC}")
    val nbd = listOf("qemu-nbd", "-f", "raw", "-b", Phone.PC, "-t")
    servers = listOf(
        spawn(*(nbd + listOf("-r", "-x", "system", "-p", "10809", SYSTEM.path)).toTypedArray()),
        spawn(*(nbd + listOf("-r", "-x", "vendor", "-p", "10810", VENDOR.path)).toTypedArray()),
        // --cache=unsafe: ignore the phone's flushes. apexd and dex2oat fsync
        // after every write, and each flush would be an fdatasync of data.img
        // on this PC (~600 KB/s). The cost: a PC crash can corrupt data.img
        // (just delete it, Android sets /data up again on the next boot).
        spawn(*(nbd + listOf("-x", "data", "-p", "10811", "--cache=unsafe", DATA.path)).toTypedArray()),
    )
    http = timeServer()
    while (linkUp() && servers.all { it.isAlive }) sleepSec(1.0)
    stop()
    log("${now()} USB link gone (or a server exited): stopped serving")
}
