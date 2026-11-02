#!/bin/bash
# A/B bench: two bench builds alternated per torrent and round, so both see nearly the
# same swarm state (one build per whole bench pass hours apart showed 23.7 vs 15.1 MB
# for identical code).
#
# usage: tools/torrent-bench/bench_ab.sh <list-file> <rounds> <out-dir> <apk-A> <apk-B>
#   Results: <out-dir>/A/results.csv and <out-dir>/B/results.csv (bench.sh format).
#   Both apks must have the same signer (install -r keeps data). Caller holds the lease.
set -u

LIST=${1:?list}; ROUNDS=${2:?rounds}; OUT=${3:?out}; APK_A=${4:?apk A}; APK_B=${5:?apk B}
SERIAL=${SERIAL:?serial}
HERE=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT/A" "$OUT/B"
echo "A=$(shasum -a 256 "$APK_A" | cut -c1-16) $APK_A" > "$OUT/builds.txt"
echo "B=$(shasum -a 256 "$APK_B" | cut -c1-16) $APK_B" >> "$OUT/builds.txt"

n=0
while IFS= read -r uri <&3; do
  n=$((n + 1))
  one="$TMP/torrents.local-$n.txt"   # label stays torrents.local-<n>-1, names never written
  printf '%s\n' "$uri" > "$one"
  for round in $(seq 1 "$ROUNDS"); do
    # alternate which build goes first, so neither always gets the warmer swarm
    if [ $((round % 2)) = 1 ]; then order="A B"; else order="B A"; fi
    for side in $order; do
      apk=$APK_A; [ "$side" = B ] && apk=$APK_B
      adb -s "$SERIAL" install -r "$apk" >/dev/null 2>&1 || { echo "install $side failed" >&2; exit 1; }
      SERIAL="$SERIAL" bash "$HERE/bench.sh" "$one" 1 "$OUT/$side" >/dev/null 2>&1
      echo "torrent $n round $round $side: $(tail -1 "$OUT/$side/results.csv" | cut -d, -f10,11,15,18,22)"
    done
  done
done 3< <(grep -vE '^[[:space:]]*(#|$)' "$LIST")
