#!/bin/bash
# Reference bench: the same byte ranges as bench.sh, served by TorrServer (Go,
# anacrolix/torrent) running as a plain binary on the phone, for an engine comparison.
#
# usage: tools/torrent-bench/bench_torrserver.sh <list-file> <runs> <out-dir>
#   TS_BIN: path of TorrServer on the phone (default /data/local/tmp/torrserver/ts)
#   The binary must already be pushed there; the caller holds the device lease.
# Per run: (re)start TorrServer, add the torrent through its API, wait for the file
# list (metadata), then head / 30 s read / 50% seek / tail via adb forward + curl,
# then remove it. Torrent/file names are never written out.
set -u

LIST=${1:?list file}; RUNS=${2:?runs}; OUT=${3:?out dir}
SERIAL=${SERIAL:?serial}
TS_BIN=${TS_BIN:-/data/local/tmp/torrserver/ts}
TS_DIR=$(dirname "$TS_BIN")
TS_PORT=${TS_PORT:-8091}
LOCAL_PORT=${LOCAL_PORT:-18766}
ADD_TIMEOUT=${ADD_TIMEOUT:-90}
RANGE_TIMEOUT=${RANGE_TIMEOUT:-120}
MIB=1048576
a() { adb -s "$SERIAL" "$@"; }

mkdir -p "$OUT"
CSV="$OUT/results.csv"
[ -f "$CSV" ] || echo "label,run,keep,hash,files,len,metaMs,head_code,head_ttfb,head_total,tput_bytes,tput_secs,mid_code,mid_ttfb,mid_total,tail_code,tail_ttfb,tail_total,removed,native_abort,pid_changed,note" > "$CSV"

api() { curl -s --max-time 10 -H 'Content-Type: application/json' -d "$1" "http://127.0.0.1:$LOCAL_PORT/torrents"; }
range() {
  curl -s -o /dev/null -r "$2-$3" --max-time "$RANGE_TIMEOUT" \
    -w '%{http_code} %{time_starttransfer} %{time_total} %{size_download}' "$1" 2>/dev/null || true
}
short() { set -- "$1" $2 "$3"; [ "${5:-0}" = "$6" ] || echo "${1}_short=${5:-0}/$6"; }
now_ms() { python3 -I -c 'import time;print(int(time.time()*1000))'; }

stop_ts() { a shell "pkill -f $TS_BIN; pkill -f './ts -p'" >/dev/null 2>&1; sleep 1; }
start_ts() {
  stop_ts
  # setsid + stdin from /dev/null: otherwise adb shell waits for the background child
  timeout 10 adb -s "$SERIAL" shell "cd $TS_DIR && setsid $TS_BIN -p $TS_PORT -d $TS_DIR </dev/null >$TS_DIR/ts.log 2>&1 &" >/dev/null 2>&1
  a forward tcp:$LOCAL_PORT tcp:$TS_PORT >/dev/null
  for _ in $(seq 1 30); do curl -s --max-time 2 "http://127.0.0.1:$LOCAL_PORT/echo" >/dev/null && return 0; sleep 1; done
  return 1
}

n=0
while IFS= read -r uri <&3; do
  n=$((n + 1)); label="$(basename "$LIST" .txt)-$n"
  for run in $(seq 1 "$RUNS"); do
    note=""; head="" tput_b="" tput_s="" mid="" tail="" removed="" hash="" len="" files="" meta=""
    start_ts || note="ts_not_started"
    body=$(python3 -I -c 'import json,sys;print(json.dumps({"action":"add","link":sys.argv[1],"save_to_db":False}))' "$uri")
    t0=$(now_ms)
    added=$(api "$body")
    hash=$(echo "$added" | python3 -I -c 'import json,sys;print(json.load(sys.stdin).get("hash",""))' 2>/dev/null)
    if [ -n "$hash" ]; then
      for _ in $(seq 1 "$ADD_TIMEOUT"); do
        st=$(api "{\"action\":\"get\",\"hash\":\"$hash\"}")
        read -r files len < <(echo "$st" | python3 -I -c 'import json,sys
d=json.load(sys.stdin); f=d.get("file_stats") or []
print(len(f), f[0]["length"] if f else "")' 2>/dev/null)
        [ -n "${len:-}" ] && break
        sleep 1
      done
      meta=$(( $(now_ms) - t0 ))
    fi
    if [ -n "${len:-}" ]; then
      L="http://127.0.0.1:$LOCAL_PORT/stream?link=$hash&index=1&play"
      head=$(range "$L" 0 $((MIB - 1)))
      read -r tput_b tput_s < <(curl -s -o /dev/null -r "$MIB-" --max-time 30 \
        -w '%{size_download} %{time_total}' "$L" 2>/dev/null || true)
      mstart=$((len / 2)); mid=$(range "$L" $mstart $((mstart + MIB - 1)))
      tstart=$((len > MIB ? len - MIB : 0)); tail=$(range "$L" $tstart $((len - 1)))
      for chk in "$(short head "$head" $MIB)" "$(short mid "$mid" $MIB)" "$(short tail "$tail" $((len - tstart)))"; do
        [ -n "$chk" ] && note="${note:+$note;}$chk"
      done
      api "{\"action\":\"rem\",\"hash\":\"$hash\"}" >/dev/null && removed=true || removed=false
    else
      note="${note:+$note;}add_timeout"
    fi
    set -- $head; hc=${1:-}; ht=${2:-}; hT=${3:-}
    set -- $mid;  mc=${1:-}; mt=${2:-}; mT=${3:-}
    set -- $tail; tc=${1:-}; tt=${2:-}; tT=${3:-}
    echo "$label,$run,0,$hash,$files,$len,$meta,$hc,$ht,$hT,$tput_b,$tput_s,$mc,$mt,$mT,$tc,$tt,$tT,$removed,no,no,$note" | tee -a "$CSV"
  done
done 3< <(grep -vE '^[[:space:]]*(#|$)' "$LIST")
stop_ts
a forward --remove tcp:$LOCAL_PORT >/dev/null 2>&1
