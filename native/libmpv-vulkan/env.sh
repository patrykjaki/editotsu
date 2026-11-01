# shared env for the libmpv-vulkan build (source it)
export NDK=$HOME/Library/Android/sdk/ndk/28.2.13676358
export API=26
export TC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64
export ROOT=$HOME/.editotsu/vendor/libmpv-vulkan
export SRC=$ROOT/src PREFIX=$ROOT/prefix/arm64 LOGS=$ROOT/logs
export PATH=$HOME/.editotsu/vendor/tools/meson-venv/bin:$PATH
export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig PKG_CONFIG_PATH=
export CORES=4
export CC=$TC/bin/aarch64-linux-android$API-clang CXX=$TC/bin/aarch64-linux-android$API-clang++
export AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip NM=$TC/bin/llvm-nm
export PKG_CONFIG_SYSROOT_DIR=$PREFIX
