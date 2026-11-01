#!/usr/bin/env bash
set -euo pipefail

rm -rf /libtorrent4j/swig/deps/libtorrent/deps/libdatachannel/deps/libjuice/build-*
rm -rf /libtorrent4j/swig/deps/libtorrent/deps/libdatachannel/deps/usrsctp/build-*

export ANDROID_TOOLCHAIN=/android-ndk/toolchains/llvm/prebuilt/linux-x86_64
export BOOST_ROOT=/boost
export OPENSSL_ROOT=/openssl-arm64
export LIBTORRENT_ROOT=/libtorrent4j/swig/deps/libtorrent
export CXX=${ANDROID_TOOLCHAIN}/bin/aarch64-linux-android28-clang++
export CC=${ANDROID_TOOLCHAIN}/bin/aarch64-linux-android28-clang
export AR=${ANDROID_TOOLCHAIN}/bin/llvm-ar
export LD=${ANDROID_TOOLCHAIN}/bin/ld
export RANLIB=${ANDROID_TOOLCHAIN}/bin/llvm-ranlib

cd /libtorrent4j/swig
${BOOST_ROOT}/b2 -j1 --user-config=config/android-arm64-config.jam \
    variant=release debug-symbols=on asserts=production toolset=clang-arm64 \
    target-os=android location=bin/release/android/arm64-v8a

NATIVE_DIR=bin/release/android/arm64-v8a
cp ${NATIVE_DIR}/libtorrent4j.so ${NATIVE_DIR}/libtorrent4j.so.unstripped
${ANDROID_TOOLCHAIN}/bin/llvm-objcopy --only-keep-debug \
    ${NATIVE_DIR}/libtorrent4j.so ${NATIVE_DIR}/libtorrent4j.so.debug
${ANDROID_TOOLCHAIN}/bin/llvm-strip --strip-unneeded -x ${NATIVE_DIR}/libtorrent4j.so
${ANDROID_TOOLCHAIN}/bin/llvm-objcopy \
    --add-gnu-debuglink=${NATIVE_DIR}/libtorrent4j.so.debug ${NATIVE_DIR}/libtorrent4j.so
${ANDROID_TOOLCHAIN}/bin/llvm-readelf -d ${NATIVE_DIR}/libtorrent4j.so
