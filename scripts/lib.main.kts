// What every script here shares (pulled in with @file:Import("lib.main.kts")):
// project paths, running commands, and talking to the phone.

import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.system.exitProcess

// --- paths

/** The project: this file is scripts/lib.main.kts. */
val TOP: File = __FILE__.absoluteFile.parentFile.parentFile
val HOME = File(System.getProperty("user.home"))
val OUT = TOP.resolve("out")

/** $name from the environment, or [default] when unset or empty. */
fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotEmpty() } ?: default

/** The kernel project this one builds on (patches, DTS, kernel.config, tools). */
val BASE: File = File(env("BASE", TOP.resolve("../nexus5x-linux-6.12-wip").path)).canonicalFile

fun die(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(1)
}

fun log(msg: String) = println(msg)

// --- commands

class CommandFailed(val cmd: List<String>, val code: Int) :
    RuntimeException("exit $code: ${cmd.joinToString(" ")}")

private fun builder(cmd: Array<out Any>, dir: File?, env: Map<String, String>) =
    ProcessBuilder(cmd.map { it.toString() }).apply {
        if (dir != null) directory(dir)
        environment().putAll(env)
    }

/**
 * Run a command with this terminal as its stdin/stdout/stderr (or [stdout]
 * into a file, or dropped if [quiet]); throws [CommandFailed] unless it exits 0 (or [check] is off).
 * Returns the exit code.
 */
fun run(
    vararg cmd: Any,
    dir: File? = null,
    env: Map<String, String> = emptyMap(),
    stdout: File? = null,
    quiet: Boolean = false,
    check: Boolean = true,
): Int {
    val pb = builder(cmd, dir, env).inheritIO()
    if (stdout != null) pb.redirectOutput(stdout)
    else if (quiet) pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)   // errors still show
    val code = pb.start().waitFor()
    if (check && code != 0) throw CommandFailed(cmd.map { it.toString() }, code)
    return code
}

/** A command's stdout as text (stderr goes to the terminal, or is dropped). */
fun capture(
    vararg cmd: Any,
    dir: File? = null,
    env: Map<String, String> = emptyMap(),
    check: Boolean = true,
    stderr: Boolean = true,
): String {
    val pb = builder(cmd, dir, env)
        .redirectError(if (stderr) ProcessBuilder.Redirect.INHERIT else ProcessBuilder.Redirect.DISCARD)
    val p = pb.start()
    val text = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    if (check && code != 0) throw CommandFailed(cmd.map { it.toString() }, code)
    return text
}

/** A command's stdout and stderr together, and its exit code; never throws. */
fun captureAll(vararg cmd: Any, dir: File? = null): Pair<Int, String> {
    val p = builder(cmd, dir, emptyMap()).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    return p.waitFor() to text
}

/** Start a command in the background, its output on this terminal. */
fun spawn(vararg cmd: Any): Process = builder(cmd, null, emptyMap()).inheritIO().start()

fun sleepSec(s: Double) = Thread.sleep((s * 1000).toLong())

/** Poll [ok] once a second for up to [seconds]; true as soon as it holds. */
fun waitUntil(seconds: Int, ok: () -> Boolean): Boolean {
    repeat(seconds) {
        if (ok()) return true
        Thread.sleep(1000)
    }
    return ok()
}

// --- the phone

/**
 * The phone over the USB network: our boot ramdisk's rescue shell (BusyBox
 * telnetd, root, port 23), Android's adbd (5555), and fastboot (USB).
 */
class PhoneLink {
    val IP = "172.16.42.1"
    /** this PC on the USB network (the phone's DHCP server hands it out) */
    val PC = "172.16.42.2"

    /** Does the phone accept TCP connections on [port]? */
    fun open(port: Int, timeoutMs: Int = 2000): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(IP, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    fun telnetUp() = open(23)
    fun adbdUp() = open(5555)

    fun inFastboot(): Boolean = capture("fastboot", "devices", check = false, stderr = false).isNotBlank()

    /**
     * Run [cmd] in the rescue shell and return what it printed, or null if
     * the shell can't be reached or didn't finish within [timeoutSec].
     * Output is collected between two marker lines, so no guessing how long
     * to wait. (The shell echoes the command line too; in that echo the
     * markers keep their quotes, so only the real ones match a whole line.)
     */
    fun sh(cmd: String, timeoutSec: Int = 15): String? {
        val out = StringBuilder()
        val ok = session("echo __BH_ST''ART__; $cmd\necho __BH_DO''NE__\n", timeoutSec) { text ->
            out.append(text)
            out.lines().any { it == "__BH_DONE__" }
        }
        if (!ok) return null
        return out.lines()
            .dropWhile { it != "__BH_START__" }.drop(1)
            .takeWhile { it != "__BH_DONE__" }
            .filterNot { it.endsWith("echo __BH_DO''NE__") }
            .joinToString("\n")
    }

    /**
     * Run [cmd] in the rescue shell and pass its output on to [sink] as it
     * arrives, until the connection drops (or [sink] returns true to stop).
     */
    fun stream(cmd: String, sink: (String) -> Boolean) {
        session("$cmd\n", Int.MAX_VALUE, sink)
    }

    /** One telnet connection: send [input], feed the cleaned-up output to [sink]. */
    private fun session(input: String, timeoutSec: Int, sink: (String) -> Boolean): Boolean {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(IP, 23), 3000)
        } catch (e: Exception) {
            s.close()
            return false
        }
        s.use {
            it.soTimeout = 500
            val inp = it.getInputStream()
            Thread.sleep(500)   // let telnetd start its shell
            it.getOutputStream().apply { write(input.toByteArray()); flush() }
            val deadline = if (timeoutSec == Int.MAX_VALUE) Long.MAX_VALUE
            else System.currentTimeMillis() + timeoutSec * 1000L
            val telnet = TelnetFilter()
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    inp.read(buf)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    return false
                }
                if (n < 0) return false
                if (sink(telnet.clean(buf, n))) return true
            }
            return false
        }
    }

    /** Strips telnet option negotiation, CRs and terminal colour codes. */
    private class TelnetFilter {
        private var skip = 0
        private var inSub = false

        fun clean(buf: ByteArray, n: Int): String {
            val out = java.io.ByteArrayOutputStream(n)
            var i = 0
            while (i < n) {
                val b = buf[i].toInt() and 0xff
                i++
                when {
                    skip > 0 -> skip--
                    inSub -> if (b == 240) inSub = false            // IAC SE
                    b == 255 -> {                                   // IAC
                        val c = if (i < n) buf[i].toInt() and 0xff else 0
                        when (c) {
                            in 251..254 -> skip = 2                 // WILL/WONT/DO/DONT opt
                            250 -> { inSub = true; skip = 1 }       // SB ...
                            else -> skip = 1
                        }
                    }
                    b == '\r'.code -> {}
                    else -> out.write(b)
                }
            }
            return out.toString(Charsets.UTF_8).replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
        }
    }
}

val Phone = PhoneLink()

/**
 * rm -rf: delete a file or directory tree. Never use File.deleteRecursively()
 * here: it follows symlinks to directories and empties what they point to
 * (modules_install's build/source links point at the kernel trees).
 */
fun File.removeTree() {
    val root = toPath()
    if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(f: java.nio.file.Path, a: java.nio.file.attribute.BasicFileAttributes) =
            java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.delete(f) }
        override fun postVisitDirectory(d: java.nio.file.Path, e: java.io.IOException?) =
            java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.delete(d) }
    })
}

/** InputStream to file, fully. */
fun InputStream.saveTo(f: File): Long = f.outputStream().use { copyTo(it) }
