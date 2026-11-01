FROM --platform=linux/amd64 ubuntu:20.04@sha256:c664f8f86ed5a386b0a340d981b8f81714e21a8b9c73f658c4bea56aa179d54a

ARG UBUNTU_SNAPSHOT=20250404T000000Z
ENV DEBIAN_FRONTEND=noninteractive
RUN sed -i "s|^deb http://archive.ubuntu.com/ubuntu/|deb [snapshot=${UBUNTU_SNAPSHOT}] http://archive.ubuntu.com/ubuntu/|" /etc/apt/sources.list \
    && sed -i "s|^deb http://security.ubuntu.com/ubuntu/|deb [snapshot=${UBUNTU_SNAPSHOT}] http://security.ubuntu.com/ubuntu/|" /etc/apt/sources.list \
    && apt-get update -y \
    && apt-get install -y wget unzip less python perl make cmake g++

ENV BOOST_DOT_VERSION=1.90.0
ENV BOOST_VERSION=1_90_0
ENV BOOST_SHA256=5e93d582aff26868d581a52ae78c7d8edf3f3064742c6e77901a1f18a437eea9
ENV NDK_VERSION=r29
ENV NDK_SHA256=4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf
ENV OPENSSL_VERSION=3.5.5
ENV OPENSSL_SHA256=b28c91532a8b65a1f983b4c28b7488174e4a01008e29ce8e69bd789f28bc2a89
ENV OPENSSL_NO_OPTS="no-deprecated no-shared no-makedepend no-static-engine no-stdio no-posix-io no-threads no-ui-console no-zlib no-zlib-dynamic -fno-strict-aliasing -fvisibility=hidden -O3 -fPIC"

RUN wget -nv -O boost.tar.gz https://archives.boost.io/release/${BOOST_DOT_VERSION}/source/boost_${BOOST_VERSION}.tar.gz \
    && echo "${BOOST_SHA256}  boost.tar.gz" | sha256sum -c - \
    && tar xzf boost.tar.gz \
    && mv boost_${BOOST_VERSION} boost \
    && cd boost \
    && ./bootstrap.sh

RUN wget -nv -O android-ndk.zip https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip \
    && echo "${NDK_SHA256}  android-ndk.zip" | sha256sum -c - \
    && unzip -qq android-ndk.zip \
    && mv android-ndk-${NDK_VERSION} android-ndk

RUN wget -nv -O openssl.tar.gz https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz \
    && echo "${OPENSSL_SHA256}  openssl.tar.gz" | sha256sum -c - \
    && tar xzf openssl.tar.gz \
    && mv openssl-${OPENSSL_VERSION} openssl-src

ENV TOOLCHAIN=/android-ndk/toolchains/llvm/prebuilt/linux-x86_64
ENV AR=${TOOLCHAIN}/bin/llvm-ar
ENV LD=${TOOLCHAIN}/bin/ld
ENV RANLIB=${TOOLCHAIN}/bin/llvm-ranlib

RUN export CC=${TOOLCHAIN}/bin/aarch64-linux-android28-clang \
    && cd openssl-src \
    && ./Configure linux-aarch64 ${OPENSSL_NO_OPTS} -march=armv8-a+crypto -mfpu=neon --prefix=/openssl-arm64 \
    && make clean \
    && make -j8 \
    && make install_sw \
    && make clean

COPY b2-arm64.sh /b2-arm64.sh
RUN chmod 0755 /b2-arm64.sh
