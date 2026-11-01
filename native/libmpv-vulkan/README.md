# libmpv with Vulkan (arm64-v8a), opt-in

Why: CuNNy's mpv shaders are compute shaders. On Android GLES neither mpv renderer runs
compute (mpv `vo=gpu` loads no glDispatchCompute; libplacebo v7.360.0 `src/opengl/gpu.c:161`
enables compute only on desktop GL 4.3+ / GLSL 420). libplacebo's Vulkan backend does. The
Maven `io.github.abdallahmehiz:mpv-android-lib:0.1.12` is built with `-Dvulkan=disabled`.

What: the same 0.1.12 aar with only `jni/arm64-v8a/libmpv.so` rebuilt (same mpv 76a5eba99,
libplacebo v7.360.0, ffmpeg headers N-122742-ga7522f3fef; the ffmpeg .so files, libplayer.so and
libc++_shared.so are the base aar's). Adds libplacebo Vulkan + glslang 16.2.0 (static).
Other ABIs are untouched; the app requests Vulkan only on arm64-v8a and API 28+.

Use: `-Peditotsu.libmpvAar=/abs/path/mpv-android-lib-0.1.12-vulkan-<rev>.aar` (provenance.json
must sit next to it). Without it the build uses Maven and nothing changes.

Build (macOS host, NDK 28.2.13676358, meson in a venv, see env.sh):

    ./fetch.sh            # pinned sources into $ROOT/src
    ./build.sh lua freetype fribidi harfbuzz libass ffmpeg-headers glslang libplacebo mpv
    ./package.sh r2       # strips, runs check_undefined.sh, writes aar + provenance.json

Patches: `patches/libplacebo-glslang-dirs.patch` (meson static lookups ignore -L; give glslang
and glslang-default-resource-limits the -Dvulkan-sdk dir), `patches/mpv-weak-vk11.h` (forced
include; mpv calls two Vulkan 1.1 functions directly, which libvulkan.so exports only from API 28;
weak refs keep libmpv loadable on API 26/27).

r1 was broken (GetDefaultResources unresolved -> UnsatisfiedLinkError on player open);
check_undefined.sh now gates packaging.
