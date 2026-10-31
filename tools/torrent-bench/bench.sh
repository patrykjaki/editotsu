#!/bin/bash
# Torrent bench driver (task 7 rig). Layer 1: engine + relay, no player UI.
#
# usage: tools/torrent-bench/bench.sh <list-file> <runs> <out-dir>
#   list-file: one magnet or .torrent URL per line; '#' comments ignored.
#   The app must be a build with -Peditotsu.torrentBench=true, installed as
#   $PKG, and the caller must hold the device lease.
#
# Per run: cold start the app, add the torrent through the bench hook, then
# measure the relay with fixed byte ranges via adb forward + curl:
#   head  = bytes 0..1MiB          (start of playback)
#   tput  = 30 s sustained read    (from 1MiB)
#   mid   = 1MiB at 50% of file    (seek)
#   tail  = last 1MiB              (mkv cues / mp4 moov)
# then remove the torrent and delete its payload. Native aborts are detected
# from the crash buffer and the pid. Torrent/file names are never written out.
set -u

LIST=${1:?list file}; RUNS=${2:?runs}; OUT=${3:?out dir}
PKG=${PKG:-ani.editotsu}
SERIAL=${SERIAL:-192.168.1.76:44323}
LOCAL_PORT=${LOCAL_PORT:-18765}
ADD_TIMEOUT=${ADD_TIMEOUT:-90}
RANGE_TIMEOUT=${RANGE_TIMEOUT:-120}
MIB=1048576
A="adb -s $SERIAL"

mkdir -p "$OUT"
CSV="$OUT/results.csv"
[ -f "$CSV" ] || echo "label,run,hash,files,len,metaMs,head_code,head_ttfb,head_total,tput_bytes,tput_secs,mid_code,mid_ttfb,mid_total,tail_code,tail_ttfb,tail_total,removed,native_abort,pid_changed,note" > "$CSV"

phone_epoch() { $A shell date +%s.%3N | tr -d '\r'; }

wait_bench() { # $1 since-epoch, $2 regex, $3 timeout s -> prints matching line
  local since=$1 re=$2 to=$3 t=0 line
  while [ "$t" -lt "$to" ]; do
    line=$($A logcat -d -v epoch -T "$since" -s EditotsuBench:I 2>/dev/null | grep -E "$re" | tail -1)
    [ -n "$line" ] && { echo "$line"; return 0; }
    sleep 1; t=$((t + 1))
  done
  return 1
}

kv() { echo "$1" | tr ' ' '\n' | grep "^$2=" | head -1 | cut -d= -f2-; }

range() { # $1 url, $2 start, $3 end -> "code ttfb total"
  curl -s -o /dev/null -r "$2-$3" --max-time "$RANGE_TIMEOUT" \
    -w '%{http_code} %{time_starttransfer} %{time_total}' "$1" 2>/dev/null || true
}

aborted_since() { # $1 since-epoch
  $A logcat -d -b crash -v epoch -T "$1" 2>/dev/null | grep -E ">>> $PKG <<<|Cmdline: $PKG\$" | head -1
}

n=0
# read the list on fd 3: adb shell would otherwise consume stdin
while IFS= read -r uri <&3; do
  n=$((n + 1)); label="$(basename "$LIST" .txt)-$n"
  for run in $(seq 1 "$RUNS"); do
    note=""
    $A shell am force-stop "$PKG"; sleep 2
    since=$(phone_epoch)
    $A shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_bench "$since" "event=installed" 30 >/dev/null || note="hook_not_installed"
    pid0=$($A shell pidof "$PKG" | tr -d '\r')
    b64=$(printf '%s' "$uri" | base64 | tr -d '\n')
    $A shell am broadcast -a ani.editotsu.bench.TORRENT -p "$PKG" --es cmd add --es uri_b64 "$b64" >/dev/null
    added=$(wait_bench "$since" "event=(added|add_failed|error)" "$ADD_TIMEOUT")
    $A logcat -d -v epoch -T "$since" > "$OUT/$label-run$run.logcat.txt" 2>/dev/null
    hash=$(kv "$added" hash); len=$(kv "$added" len); url=$(kv "$added" url)
    files=$(kv "$added" files); meta=$(kv "$added" metaMs)
    head="" tput_b="" tput_s="" mid="" tail="" removed=""
    if [ -n "$url" ] && [ -n "$len" ]; then
      port=$(echo "$url" | sed -E 's#.*127\.0\.0\.1:([0-9]+)/.*#\1#')
      path=$(echo "$url" | sed -E 's#.*127\.0\.0\.1:[0-9]+##')
      $A forward tcp:$LOCAL_PORT tcp:$port >/dev/null
      L="http://127.0.0.1:$LOCAL_PORT$path"
      head=$(range "$L" 0 $((MIB - 1)))
      read -r tput_b tput_s < <(curl -s -o /dev/null -r "$MIB-" --max-time 30 \
        -w '%{size_download} %{time_total}' "$L" 2>/dev/null || true)
      mstart=$((len / 2)); mid=$(range "$L" $mstart $((mstart + MIB - 1)))
      tstart=$((len > MIB ? len - MIB : 0)); tail=$(range "$L" $tstart $((len - 1)))
      $A forward --remove tcp:$LOCAL_PORT >/dev/null 2>&1
      rsince=$(phone_epoch)
      $A shell am broadcast -a ani.editotsu.bench.TORRENT -p "$PKG" --es cmd remove --es hash "$hash" >/dev/null
      r=$(wait_bench "$rsince" "event=removed" 60) && removed=$(kv "$r" payloadDeleted) || removed="timeout"
    else
      if [ -n "$added" ]; then note="${note:+$note;}$(kv "$added" event)"; else note="${note:+$note;}add_timeout"; fi
    fi
    ab=$(aborted_since "$since"); pid1=$($A shell pidof "$PKG" | tr -d '\r')
    $A logcat -d -b crash -v epoch -T "$since" > "$OUT/$label-run$run.crash.txt" 2>/dev/null
    abort=$([ -n "$ab" ] && echo yes || echo no)
    pidc=$([ "$pid0" != "$pid1" ] && echo yes || echo no)
    set -- $head; hc=${1:-}; ht=${2:-}; hT=${3:-}
    set -- $mid;  mc=${1:-}; mt=${2:-}; mT=${3:-}
    set -- $tail; tc=${1:-}; tt=${2:-}; tT=${3:-}
    echo "$label,$run,$hash,$files,$len,$meta,$hc,$ht,$hT,$tput_b,$tput_s,$mc,$mt,$mT,$tc,$tt,$tT,$removed,$abort,$pidc,$note" | tee -a "$CSV"
    if [ "$abort" = yes ]; then
      echo "native abort on $label run $run; stopping this torrent's series" >&2
      break
    fi
  done
done 3< <(grep -vE '^[[:space:]]*(#|$)' "$LIST")
$A shell am force-stop "$PKG"
