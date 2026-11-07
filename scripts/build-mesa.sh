#!/bin/bash
# Mesa (freedreno, with nexus5x-linux-6.12-wip's Adreno 418 patch) as an
# Android vendor EGL/GLES driver, built with the NDK, no AOSP tree:
#
#   out/mesa/lib64/egl/libEGL_mesa.so, libGLESv2_mesa.so  (ro.hardware.egl=mesa)
#
# android-stub: links against stub libcutils/libhardware/liblog/libsync/
# libnativewindow; the phone's real ones (vendor + LLNDK) are used at run time.
# libdrm comes from Mesa's subproject, built static. Buffers are described by
# u_gralloc's "fallback" backend (one linear plane), which fits minigbm's
# dumb buffers; the IMapper5 backend would need libui, which vendor code can't
# link.
set -euo pipefail
TOP=$(cd "$(dirname "$0")/.." && pwd)
V=26.0.3
SRC=$TOP/src/mesa-$V
BUILD=$TOP/out/mesa-build
NDK=$TOP/tools/android-ndk-r28c
export PATH=$TOP/tools/venv/bin:$PATH
API=34

if [ ! -d "$SRC" ]; then
	tar xJf "$TOP/downloads/mesa/mesa-$V.tar.xz" -C "$TOP/src"
	(cd "$SRC" && patch -p1 < "$TOP/../nexus5x-linux-6.12-wip/patches-mesa/0001-freedreno-add-the-Adreno-418.patch")
fi

T=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CROSS=$TOP/out/android-aarch64.cross
mkdir -p "$TOP/out"
cat > "$CROSS" <<EOF
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
EOF

if [ ! -f "$BUILD/build.ninja" ]; then
	meson setup "$BUILD" "$SRC" --cross-file "$CROSS" \
		--buildtype=release -Db_ndebug=true \
		-Dplatforms=android -Dplatform-sdk-version=$API \
		-Dandroid-stub=true -Dandroid-libbacktrace=disabled \
		-Dgallium-drivers=freedreno -Dfreedreno-kmds=msm \
		-Dvulkan-drivers= -Dllvm=disabled \
		-Degl=enabled -Degl-lib-suffix=_mesa -Dgles-lib-suffix=_mesa \
		-Dgles1=disabled -Dgles2=enabled -Dopengl=true \
		-Dgbm=disabled -Dglx=disabled -Dxmlconfig=disabled \
		-Dzstd=disabled -Dexpat=disabled -Dvalgrind=disabled \
		-Dvideo-codecs= -Dbuild-tests=false -Dtools= \
		-Dallow-fallback-for=libdrm \
		-Dlibdrm:default_library=static -Dlibdrm:freedreno=enabled \
		-Dlibdrm:intel=disabled -Dlibdrm:radeon=disabled -Dlibdrm:amdgpu=disabled \
		-Dlibdrm:nouveau=disabled -Dlibdrm:vmwgfx=disabled -Dlibdrm:tests=false \
		-Dlibdrm:man-pages=disabled -Dlibdrm:valgrind=disabled -Dlibdrm:cairo-tests=disabled
fi
ninja -C "$BUILD"

OUT=$TOP/out/mesa/lib64/egl
rm -rf "$TOP/out/mesa"
mkdir -p "$OUT"
for l in libEGL_mesa.so libGLESv2_mesa.so; do
	f=$(find "$BUILD" -name "$l" -type f | head -1)
	"$T/llvm-strip" -o "$OUT/$l" "$f"
done
# the gallium driver lives in libgallium-<ver>.so next to them
for f in $(find "$BUILD" -name 'libgallium*.so' -type f); do
	"$T/llvm-strip" -o "$TOP/out/mesa/lib64/$(basename "$f")" "$f"
done
ls -la "$TOP/out/mesa/lib64" "$OUT"
for f in "$OUT"/*.so "$TOP"/out/mesa/lib64/*.so; do
	echo "$(basename "$f") needs: $("$T/llvm-readelf" -d "$f" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | tr '\n' ' ')"
done
