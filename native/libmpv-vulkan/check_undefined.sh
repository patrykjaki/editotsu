#!/bin/bash
# fails unless every strong undefined symbol in libmpv.so is defined by its NEEDED libs
# (the base aar's libs + NDK API $API system libs). usage: check_undefined.sh <libmpv.so>
. "$(dirname "$0")/env.sh"
L="$1"; SYS=$TC/sysroot/usr/lib/aarch64-linux-android/$API; AAR=$ROOT/aar-0.1.12/jni/arm64-v8a
T=$(mktemp -d)
$NM -D --undefined-only "$L" | awk '$1=="U"{print $2}' | sed 's/@.*//' | sort -u > $T/undef
for n in $($TC/bin/llvm-readelf -d "$L" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
  f=$AAR/$n; [ -f "$f" ] || f=$SYS/$n; [ -f "$f" ] || { echo "missing NEEDED $n"; exit 1; }
  $NM -D --defined-only "$f" | awk '{print $NF}' | sed 's/@.*//'
done | sort -u > $T/def
comm -23 $T/undef $T/def > $T/missing
echo "strong undefined: $(wc -l < $T/undef), unresolved at API $API: $(wc -l < $T/missing)"
cat $T/missing; [ ! -s $T/missing ]
