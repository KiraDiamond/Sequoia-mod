#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${WASI_SDK_PATH:?Set WASI_SDK_PATH to wasi-sdk 34}"
SDK="$WASI_SDK_PATH"
SUFFIX=""
[[ -f "$SDK/bin/clang.exe" ]] && SUFFIX=".exe"
CC="$SDK/bin/clang$SUFFIX"
AR="$SDK/bin/llvm-ar$SUFFIX"
RANLIB="$SDK/bin/llvm-ranlib$SUFFIX"
BUILD="$ROOT/build/native-wasm"
mkdir -p "$BUILD" "$BUILD/prefix"
tar -xf "$ROOT/native/vendor/zlib-1.3.2.tar.gz" -C "$BUILD"
tar -xf "$ROOT/native/vendor/ffmpeg-9.0.2.tar.xz" -C "$BUILD"
cd "$BUILD/zlib-1.3.2"
CC="$CC --target=wasm32-wasip1" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2" ./configure --static --prefix="$BUILD/prefix"
make -j4 libz.a
make install
cd "$BUILD/ffmpeg-9.0.2"
./configure --host-cc="$CC --target=wasm32-wasip1" --cc="$CC" --cxx="$SDK/bin/clang++$SUFFIX" --ar="$AR" --nm="$SDK/bin/llvm-nm$SUFFIX" --ranlib="$RANLIB" \
 --enable-cross-compile --target-os=none --arch=wasm32 --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-debug \
 --disable-avdevice --disable-avfilter --disable-swresample --disable-network --disable-pthreads --disable-w32threads --disable-os2threads --disable-inline-asm --disable-x86asm \
 --enable-avcodec --enable-avformat --enable-avutil --enable-swscale --enable-zlib --disable-iconv \
 --enable-decoder=gif,png,mjpeg,h264 --enable-demuxer=gif,image_png_pipe,image_jpeg_pipe,mov --enable-parser=gif,png,mjpeg,h264 \
 --extra-cflags="--target=wasm32-wasip1 -O2 -I$BUILD/prefix/include -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_PROCESS_CLOCKS" \
 --extra-ldflags="--target=wasm32-wasip1 -L$BUILD/prefix/lib" --extra-libs="-lwasi-emulated-signal -lwasi-emulated-process-clocks" \
 --prefix="$BUILD/prefix"
make -j4
make install
"$CC" --target=wasm32-wasip1 -O2 -mexec-model=reactor \
 -I"$BUILD/prefix/include" "$ROOT/native/wasm/decoder.c" \
 -L"$BUILD/prefix/lib" -lavformat -lavcodec -lswscale -lavutil -lz -lm -lwasi-emulated-signal -lwasi-emulated-process-clocks \
 -Wl,-z,stack-size=1048576 -Wl,--initial-memory=4194304 -Wl,--max-memory=201326592 -Wl,--strip-all \
 -o "$ROOT/native/wasm/decoder.wasm"
