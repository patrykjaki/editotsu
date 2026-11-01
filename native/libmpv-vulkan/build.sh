#!/bin/bash
# usage: build.sh <step...>   steps: lua freetype fribidi harfbuzz libass ffmpeg-headers glslang libplacebo mpv
set -euo pipefail
. "$(dirname "$0")/env.sh"
ms() { meson setup _build --cross-file "$PREFIX/crossfile.txt" --prefix=/ --libdir=lib --buildtype=release --default-library=static --wipe "$@" 2>/dev/null || meson setup _build --cross-file "$PREFIX/crossfile.txt" --prefix=/ --libdir=lib --buildtype=release --default-library=static "$@"; ninja -C _build -j$CORES; DESTDIR="$PREFIX" ninja -C _build install; }
for step in "$@"; do echo "=== $step $(date +%H:%M:%S)"; case $step in
lua)
  cd "$SRC/lua-5.2.4"
  make -C src clean >/dev/null
  make -C src liblua.a CC="$CC" AR="$AR rcu" RANLIB="$RANLIB" MYCFLAGS="-fPIC -O2" -j$CORES
  mkdir -p "$PREFIX/include" "$PREFIX/lib"
  cp src/lua.h src/luaconf.h src/lualib.h src/lauxlib.h src/lua.hpp "$PREFIX/include/"
  cp src/liblua.a "$PREFIX/lib/liblua.a"
  printf 'prefix=/\nName: lua\nDescription: lua\nVersion: 5.2.4\nLibs: -L${prefix}/lib -llua -lm\nCflags: -I${prefix}/include\n' > "$PREFIX/lib/pkgconfig/lua52.pc" ;;
freetype)
  cd "$SRC/freetype"; ms -Dzlib=disabled -Dbzip2=disabled -Dpng=disabled -Dharfbuzz=disabled -Dbrotli=disabled ;;
fribidi)
  cd "$SRC/fribidi"; ms -Ddocs=false -Dtests=false -Dbin=false ;;
harfbuzz)
  cd "$SRC/harfbuzz"; ms -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled -Dintrospection=disabled ;;
libass)
  cd "$SRC/libass"; ms -Dfontconfig=disabled -Drequire-system-font-provider=false -Dlibunibreak=disabled -Dtest=disabled -Dprofile=disabled -Dcompare=disabled -Dfuzz=disabled -Dcheckasm=disabled ;;
ffmpeg-headers)
  # headers + pkg-config only; the .so files come unchanged from mpv-android-lib 0.1.12
  cd "$SRC/FFmpeg"
  ./configure --target-os=android --arch=aarch64 --enable-cross-compile --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$STRIP" \
    --prefix=/ --enable-shared --disable-static --disable-programs --disable-doc --enable-jni --enable-mediacodec --disable-asm >/dev/null
  make install-headers DESTDIR="$PREFIX" >/dev/null
  AAR="$ROOT/aar-0.1.12/jni/arm64-v8a"
  for l in avutil avcodec avformat avfilter avdevice swscale swresample; do
    cp "$AAR/lib$l.so" "$PREFIX/lib/"
    U=$(echo $l | tr a-z A-Z | sed 's/^AV/AV/'); H="$PREFIX/include/lib$l/version_major.h"; H2="$PREFIX/include/lib$l/version.h"
    MAJ=$({ grep -h -E "#define LIB${U}_VERSION_MAJOR" "$H" "$H2" 2>/dev/null || true; } | awk '{print $3}' | head -1)
    MIN=$(grep -h -E "#define LIB${U}_VERSION_MINOR" "$H2" | awk '{print $3}' | head -1)
    MIC=$(grep -h -E "#define LIB${U}_VERSION_MICRO" "$H2" | awk '{print $3}' | head -1)
    printf 'prefix=/\nName: lib%s\nDescription: %s (mpv-android-lib 0.1.12 prebuilt)\nVersion: %s.%s.%s\nLibs: -L${prefix}/lib -l%s\nCflags: -I${prefix}/include\n' $l $l "$MAJ" "$MIN" "$MIC" $l > "$PREFIX/lib/pkgconfig/lib$l.pc"
  done
  cat "$PREFIX"/lib/pkgconfig/libav*.pc | grep Version ;;
glslang)
  cd "$SRC/glslang"; rm -rf _build
  cmake -S . -B _build -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=$API -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/ -DBUILD_SHARED_LIBS=OFF -DENABLE_OPT=OFF -DGLSLANG_TESTS=OFF -DENABLE_GLSLANG_BINARIES=OFF -DENABLE_HLSL=OFF -DGLSLANG_ENABLE_INSTALL=ON -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  ninja -C _build -j$CORES; DESTDIR="$PREFIX" ninja -C _build install
  # the NDK toolchain file turns prefix "/" into "/usr"; flatten it
  if [ -d "$PREFIX/usr" ]; then cp -R "$PREFIX/usr/lib/." "$PREFIX/lib/"; cp -R "$PREFIX/usr/include/." "$PREFIX/include/"; rm -rf "$PREFIX/usr"; fi ;;
libplacebo)
  cd "$SRC/libplacebo"; git checkout -q src/glsl/meson.build; git apply "$(dirname "$0")/patches/libplacebo-glslang-dirs.patch" 2>/dev/null || git apply "$ROOT/recipe/patches/libplacebo-glslang-dirs.patch"
  ms -Dvulkan-sdk="$PREFIX" -Dprefer_static=true -Dvulkan=enabled -Dvk-proc-addr=enabled -Dglslang=enabled -Dshaderc=disabled -Dopengl=enabled -Dd3d11=disabled -Ddemos=false -Dtests=false -Dlcms=disabled -Ddovi=disabled -Dlibdovi=disabled -Dxxhash=disabled -Dunwind=disabled \
     -Dvulkan-registry="$SRC/libplacebo/3rdparty/Vulkan-Headers/registry/vk.xml"
  sed -i '' '/^Libs:/ s|$| -lc++_shared|' "$PREFIX/lib/pkgconfig/libplacebo.pc" ;;
mpv)
  cd "$SRC/mpv"; rm -rf _build
  meson setup _build --cross-file "$PREFIX/crossfile.txt" --prefix=/ --libdir=lib --buildtype=release --default-library=shared \
    -Diconv=disabled -Dlua=enabled -Dlibmpv=true -Dcplayer=false -Dmanpage-build=disabled \
    -Dvulkan=enabled -Dgl=enabled -Degl-android=enabled -Dshaderc=disabled \
    -Dc_args="-fPIC -O2 -DNDEBUG -I$PREFIX/include -include $ROOT/recipe/patches/mpv-weak-vk11.h"
  ninja -C _build -j$CORES; DESTDIR="$PREFIX" ninja -C _build install ;;
*) echo "unknown step $step"; exit 2 ;;
esac; done
echo "=== done $(date +%H:%M:%S)"
