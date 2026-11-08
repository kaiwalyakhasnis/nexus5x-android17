#!/usr/bin/env kotlin
// Mesa (freedreno, with nexus5x-linux-6.12-wip's Adreno 418 patch) as an
// Android vendor EGL/GLES driver, built with the NDK, no AOSP tree:
//
//   out/mesa/lib64/egl/libEGL_mesa.so, libGLESv2_mesa.so  (ro.hardware.egl=mesa)
//   out/mesa/lib64/libgallium-*.so                        (the driver itself)
//
// android-stub: links against stub libcutils/libhardware/liblog/libsync/
// libnativewindow; the phone's real ones (vendor + LLNDK) are used at run time.
// libdrm comes from Mesa's subproject, built static. Buffers are described by
// u_gralloc's "fallback" backend (one linear plane), which fits minigbm's
// dumb buffers; the IMapper5 backend would need libui, which vendor code can't
// link.
@file:Import("lib.main.kts")

val V = "26.0.3"
val API = 34
val SRC = TOP.resolve("src/mesa-$V")
val BUILD = OUT.resolve("mesa-build")
val NDK = TOP.resolve("tools/android-ndk-r28c")
val T = NDK.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
// meson and ninja from tools/venv (meson also finds ninja through PATH)
val VENV = TOP.resolve("tools/venv/bin")
val PATH_ENV = mapOf("PATH" to "$VENV:${System.getenv("PATH")}")

if (!SRC.isDirectory) {
    run("tar", "xJf", TOP.resolve("downloads/mesa/mesa-$V.tar.xz"), "-C", TOP.resolve("src"))
    run("patch", "-p1", "-i", BASE.resolve("patches-mesa/0001-freedreno-add-the-Adreno-418.patch"), dir = SRC)
}

val cross = OUT.resolve("android-aarch64.cross").apply {
    parentFile.mkdirs()
    writeText("""
        [binaries]
        ar = '$T/llvm-ar'
        c = '$T/aarch64-linux-android$API-clang'
        cpp = ['$T/aarch64-linux-android$API-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
        c_ld = 'lld'
        cpp_ld = 'lld'
        strip = '$T/llvm-strip'
        pkg-config = '/bin/false'

        [host_machine]
        system = 'android'
        cpu_family = 'aarch64'
        cpu = 'armv8'
        endian = 'little'
    """.trimIndent() + "\n")
}

val options = listOf(
    "--buildtype=release", "-Db_ndebug=true",
    "-Dplatforms=android", "-Dplatform-sdk-version=$API",
    "-Dandroid-stub=true", "-Dandroid-libbacktrace=disabled",
    "-Dgallium-drivers=freedreno", "-Dfreedreno-kmds=msm",
    "-Dvulkan-drivers=", "-Dllvm=disabled",
    "-Degl=enabled", "-Degl-lib-suffix=_mesa", "-Dgles-lib-suffix=_mesa",
    "-Dgles1=disabled", "-Dgles2=enabled", "-Dopengl=true",
    "-Dgbm=disabled", "-Dglx=disabled", "-Dxmlconfig=disabled",
    "-Dzstd=disabled", "-Dexpat=disabled", "-Dvalgrind=disabled",
    "-Dvideo-codecs=", "-Dbuild-tests=false", "-Dtools=",
    "-Dallow-fallback-for=libdrm",
    "-Dlibdrm:default_library=static", "-Dlibdrm:freedreno=enabled",
    "-Dlibdrm:intel=disabled", "-Dlibdrm:radeon=disabled", "-Dlibdrm:amdgpu=disabled",
    "-Dlibdrm:nouveau=disabled", "-Dlibdrm:vmwgfx=disabled", "-Dlibdrm:tests=false",
    "-Dlibdrm:man-pages=disabled", "-Dlibdrm:valgrind=disabled", "-Dlibdrm:cairo-tests=disabled",
)
if (!BUILD.resolve("build.ninja").isFile)
    run(VENV.resolve("meson"), "setup", BUILD, SRC, "--cross-file", cross, *options.toTypedArray(), env = PATH_ENV)
run(VENV.resolve("ninja"), "-C", BUILD, env = PATH_ENV)

val lib64 = OUT.resolve("mesa/lib64")
val egl = lib64.resolve("egl")
lib64.parentFile.removeTree()
egl.mkdirs()
val built = BUILD.walk().filter { it.isFile }.toList()
for (name in listOf("libEGL_mesa.so", "libGLESv2_mesa.so")) {
    val f = built.first { it.name == name }
    run(T.resolve("llvm-strip"), "-o", egl.resolve(name), f)
}
// the gallium driver lives in libgallium-<ver>.so next to them
for (f in built.filter { it.name.startsWith("libgallium") && it.name.endsWith(".so") })
    run(T.resolve("llvm-strip"), "-o", lib64.resolve(f.name), f)

for (f in (egl.listFiles()!! + lib64.listFiles { f -> f.isFile }!!).sorted()) {
    val needs = capture(T.resolve("llvm-readelf"), "-d", f).lines()
        .mapNotNull { Regex("Shared library: \\[(.*)]").find(it)?.groupValues?.get(1) }
    println("${f.name} (${f.length()} bytes) needs: ${needs.joinToString(" ")}")
}
