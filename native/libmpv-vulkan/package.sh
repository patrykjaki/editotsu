#!/bin/bash
# usage: package.sh <rev>   -> $ROOT/<rev>/mpv-android-lib-0.1.12-vulkan-<rev>.aar + provenance.json
# takes the base mpv-android-lib 0.1.12 aar and replaces only jni/arm64-v8a/libmpv.so.
set -euo pipefail
. "$(dirname "$0")/env.sh"
REV="$1"; D=$ROOT/$REV; W=$ROOT/aar-work; BASE=$(ls ~/.gradle/caches/modules-2/files-2.1/io.github.abdallahmehiz/mpv-android-lib/0.1.12/*/mpv-android-lib-0.1.12.aar)
mkdir -p "$ROOT/out" "$D"; $STRIP --strip-unneeded -o "$ROOT/out/libmpv.so" "$PREFIX/lib/libmpv.so"
"$(dirname "$0")/check_undefined.sh" "$ROOT/out/libmpv.so"
# mpv embeds its configure line, which carries the build directory. Overwrite that path with a
# placeholder of the same length so the shipped library doesn't name the build machine.
python3 -I - "$ROOT/out/libmpv.so" "$ROOT" <<'EOF2'
import sys
path, root = sys.argv[1:3]
data, old = open(path, 'rb').read(), root.encode()
open(path, 'wb').write(data.replace(old, (b'/build' + b'_' * len(old))[:len(old)]))
EOF2
rm -rf "$W"; mkdir -p "$W"; (cd "$W" && unzip -q "$BASE"); cp "$ROOT/out/libmpv.so" "$W/jni/arm64-v8a/libmpv.so"
A="$D/mpv-android-lib-0.1.12-vulkan-$REV.aar"; rm -f "$A"
(cd "$W" && TZ=UTC find . -exec touch -t 198001010000 {} + && zip -q -X "$A" $(find . -type f | sed 's|^\./||' | sort))
python3 -I - "$A" "$BASE" "$ROOT" <<'EOF'
import sys, json, hashlib, subprocess, zipfile, os
aar, base, root = sys.argv[1:4]
sha = lambda b: hashlib.sha256(b).hexdigest()
z, o = zipfile.ZipFile(aar), zipfile.ZipFile(base)
changed = [n for n in z.namelist() if n not in o.namelist() or sha(z.read(n)) != sha(o.read(n))]
rev = lambda p: subprocess.run(['git','-C',p,'rev-parse','HEAD'],capture_output=True,text=True).stdout.strip()
src = root + '/src'
prov = {
 "artifact": os.path.basename(aar), "aar_sha256": sha(open(aar,'rb').read()),
 "base_aar": {"coordinate": "io.github.abdallahmehiz:mpv-android-lib:0.1.12", "sha256": sha(open(base,'rb').read())},
 "changed_entries": {n: {"sha256": sha(z.read(n)), "base_sha256": sha(o.read(n)) if n in o.namelist() else None} for n in changed},
 "sources": {"mpv": rev(src+'/mpv'), "libplacebo": rev(src+'/libplacebo') + " (v7.360.0)", "glslang": rev(src+'/glslang') + " (16.2.0)",
             "ffmpeg_headers": rev(src+'/FFmpeg') + " (N-122742-ga7522f3fef; .so files from base aar, not rebuilt)",
             "freetype": "VER-2-14-3", "harfbuzz": "12.3.0", "fribidi": "v1.0.16", "libass": "0.17.4", "lua": "5.2.4"},
 "patches": ["patches/libplacebo-glslang-dirs.patch (search dirs for glslang + glslang-default-resource-limits)",
             "patches/mpv-weak-vk11.h (forced include: weak vkGetPhysicalDeviceProperties2 / QueueFamilyProperties2)"],
 "toolchain": {"ndk": "28.2.13676358", "api": 26, "abi": "arm64-v8a", "clang": "19.0.1 (r530567e)", "meson": "1.12.1"},
 "checks": "check_undefined.sh: every strong undefined symbol resolves against NEEDED libs at API 26",
 "libmpv_needed_added": ["libvulkan.so"],
 "other_abis": "armeabi-v7a, x86, x86_64 unchanged from base (no vulkan); the app requests vulkan only on arm64-v8a, API 28+",
 "recipe": "native/libmpv-vulkan (fetch.sh, env.sh, build.sh, check_undefined.sh, package.sh)",
 "post_processing": "build directory in mpv's embedded configuration string overwritten by a same-length placeholder"
}
open(os.path.join(os.path.dirname(aar),'provenance.json'),'w').write(json.dumps(prov, indent=2)+"\n")
print(prov["aar_sha256"], json.dumps(prov["changed_entries"]))
EOF
