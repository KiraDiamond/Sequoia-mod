#!/usr/bin/env bash
# Compiles the decoder guest: FFmpeg (gif/png/mjpeg/h264 decoders, gif/png/jpeg/mov demuxers,
# swscale) and zlib, statically linked with native/wasm/decoder.c into one WebAssembly module.
# Run it through native/guest.py, which downloads and authenticates the archives and provides
# the pinned wasi-sdk first.
#
# usage: WASI_SDK_PATH=... build-wasm.sh OUTPUT.wasm FFMPEG.tar.xz ZLIB.tar.gz
# JOBS sets make parallelism (default 1).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: build-wasm.sh OUTPUT.wasm FFMPEG.tar.xz ZLIB.tar.gz}"
FFMPEG_ARCHIVE="${2:?FFmpeg archive}"
ZLIB_ARCHIVE="${3:?zlib archive}"
: "${WASI_SDK_PATH:?Set WASI_SDK_PATH to wasi-sdk 34}"
JOBS="${JOBS:-1}"
SDK="$WASI_SDK_PATH"
CC="$SDK/bin/clang"
AR="$SDK/bin/llvm-ar"
RANLIB="$SDK/bin/llvm-ranlib"
BUILD="$ROOT/native/out/guest-build"
FFMPEG="$(basename "$FFMPEG_ARCHIVE" .tar.xz)"
ZLIB="$(basename "$ZLIB_ARCHIVE" .tar.gz)"
# Keep build locations out of the module so it does not depend on where it was built.
MAP="-ffile-prefix-map=$BUILD=/build -ffile-prefix-map=$ROOT=/src"
# clang otherwise runs any wasm-opt found on PATH when linking at -O2, so the output (and
# configure's link probes) would depend on the build machine.
LINK="--no-wasm-opt"
rm -rf "$BUILD"
mkdir -p "$BUILD/prefix"
tar -xf "$ZLIB_ARCHIVE" -C "$BUILD"
tar -xf "$FFMPEG_ARCHIVE" -C "$BUILD"
cd "$BUILD/$ZLIB"
CC="$CC --target=wasm32-wasip1 $LINK" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 $MAP" ./configure --static --prefix="$BUILD/prefix"
make -j"$JOBS" libz.a
make install
cd "$BUILD/$FFMPEG"
./configure --host-cc=cc --cc="$CC" --cxx="$SDK/bin/clang++" --ar="$AR" --nm="$SDK/bin/llvm-nm" --ranlib="$RANLIB" \
 --enable-cross-compile --target-os=none --arch=wasm32 --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-debug \
 --disable-avdevice --disable-avfilter --disable-swresample --disable-network --disable-pthreads --disable-w32threads --disable-os2threads --disable-inline-asm --disable-x86asm \
 --enable-avcodec --enable-avformat --enable-avutil --enable-swscale --enable-zlib --disable-iconv \
 --enable-decoder=gif,png,mjpeg,h264 --enable-demuxer=gif,image_png_pipe,image_jpeg_pipe,mov --enable-parser=gif,png,mjpeg,h264 \
 --extra-cflags="--target=wasm32-wasip1 -O2 $MAP -I$BUILD/prefix/include -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_PROCESS_CLOCKS" \
 --extra-ldflags="--target=wasm32-wasip1 $LINK -L$BUILD/prefix/lib" --extra-libs="-lwasi-emulated-signal -lwasi-emulated-process-clocks" \
 --prefix="$BUILD/prefix"
make -j"$JOBS"
make install
mkdir -p "$(dirname "$OUT")"
"$CC" --target=wasm32-wasip1 -O2 $MAP $LINK -mexec-model=reactor \
 -I"$BUILD/prefix/include" "$ROOT/native/wasm/decoder.c" \
 -L"$BUILD/prefix/lib" -lavformat -lavcodec -lswscale -lavutil -lz -lm -lwasi-emulated-signal -lwasi-emulated-process-clocks \
 -Wl,-z,stack-size=1048576 -Wl,--initial-memory=4194304 -Wl,--max-memory=201326592 -Wl,--strip-all \
 -o "$OUT"
