#!/bin/bash
# fetch pinned sources for the libmpv-vulkan build (run from src/)
set -e
cd "$(dirname "$0")/../src"
( cd libplacebo && git checkout -q v7.360.0 && git submodule update --init --recursive -q )
[ -d FFmpeg ] || git clone -q --filter=blob:none https://github.com/FFmpeg/FFmpeg.git
( cd FFmpeg && git checkout -q a7522f3fef )
[ -d glslang ] || git clone -q --filter=blob:none https://github.com/KhronosGroup/glslang.git
[ -d freetype ] || git clone -q --depth 1 -b VER-2-14-3 https://gitlab.freedesktop.org/freetype/freetype.git
[ -d harfbuzz ] || git clone -q --depth 1 -b 12.3.0 https://github.com/harfbuzz/harfbuzz.git
[ -d fribidi ] || git clone -q --depth 1 -b v1.0.16 https://github.com/fribidi/fribidi.git
[ -d libass ] || git clone -q --depth 1 -b 0.17.4 https://github.com/libass/libass.git
[ -d lua-5.2.4 ] || { curl -sSfLO https://www.lua.org/ftp/lua-5.2.4.tar.gz; tar xzf lua-5.2.4.tar.gz; }
echo FETCH-DONE
