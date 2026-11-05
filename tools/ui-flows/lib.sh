#!/usr/bin/env bash
# Shared helpers for the emulator flows. Source this file; do not run it.
# Talks to one emulator only: $SERIAL (default: the first emulator-* in `adb devices`).
# Refuses anything that is not an emulator so a plugged-in phone is never touched.

set -euo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG="${PKG:-ani.editotsu.beta}"
OUT="${OUT:-${TMPDIR:-/tmp}/editotsu-ui-flows}"
mkdir -p "$OUT"

if [[ -z "${SERIAL:-}" ]]; then
  SERIAL="$("$ADB" devices | awk '/^emulator-[0-9]+\tdevice/{print $1; exit}')"
fi
[[ "$SERIAL" == emulator-* ]] || { echo "no emulator found (SERIAL='$SERIAL'); start one first" >&2; exit 2; }

a() { "$ADB" -s "$SERIAL" "$@"; }
sh_() { a shell "$@"; }

# Non-exported activities and the app's data dir need root (google_apis images allow it).
if [[ "$(a shell id -u | tr -d '\r')" != 0 ]]; then a root >/dev/null 2>&1; sleep 2; a wait-for-device; fi

PASS=0; FAIL=0
pass() { PASS=$((PASS + 1)); echo "  ok   $*"; }
fail() { FAIL=$((FAIL + 1)); echo "  FAIL $*"; shot "fail_$(date +%s)"; }
summary() { echo "$PASS passed, $FAIL failed"; [[ $FAIL -eq 0 ]]; }

shot() { a exec-out screencap -p > "$OUT/$1.png"; }

# Dump the current window hierarchy to $OUT/ui.xml and echo its path.
dump() {
  local f="$OUT/ui.xml"
  rm -f "$f"; sh_ rm -f /sdcard/ui.xml
  for _ in 1 2 3; do
    sh_ uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && a pull /sdcard/ui.xml "$f" >/dev/null 2>&1 && break
    sleep 1
  done
  echo "$f"
}

# Bounds "[x1,y1][x2,y2]" of the first node whose attribute matches, as "x y" centre.
_center() { # attr value [exact|contains]
  python3 - "$(dump)" "$1" "$2" "${3:-exact}" <<'PY'
import sys, re, xml.etree.ElementTree as ET
f, attr, val, mode = sys.argv[1:5]
for n in ET.parse(f).iter('node'):
    v = n.get(attr) or ''
    if (v == val) if mode == 'exact' else (val.lower() in v.lower()):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}

has_text() { [[ -n "$(_center text "$1" "${2:-exact}")" ]]; }
has_id() { [[ -n "$(_center resource-id "$PKG:id/$1")" ]]; }

tap_xy() { sh_ input tap "$1" "$2"; sleep "${3:-1}"; }
tap_text() { # text [contains]
  local c; c="$(_center text "$1" "${2:-exact}")"
  [[ -n "$c" ]] || c="$(_center content-desc "$1" "${2:-exact}")"
  [[ -n "$c" ]] || return 1
  tap_xy $c
}
tap_id() { local c; c="$(_center resource-id "$PKG:id/$1")"; [[ -n "$c" ]] || return 1; tap_xy $c; }

# Scroll down until text shows up (max $3 swipes, default 12).
scroll_to_text() {
  for _ in $(seq 1 "${3:-12}"); do
    has_text "$1" "${2:-exact}" && return 0
    sh_ input swipe 540 1800 540 700 300; sleep 0.7
  done
  has_text "$1" "${2:-exact}"
}
# Fling up until the visible rows stop changing.
scroll_top() {
  local last="" page
  for _ in $(seq 1 30); do
    for _ in 1 2 3; do sh_ input swipe 540 600 540 2100 120; done
    sleep 0.5; page="$(dump >/dev/null; texts_of rowTitle)"
    [[ "$page" == "$last" ]] && return 0
    last="$page"
  done
}

back() { sh_ input keyevent KEYCODE_BACK; sleep 1; }

top_activity() { sh_ dumpsys activity activities | grep -m1 topResumedActivity | grep -o "$PKG/[^ }]*" | sed "s#^$PKG/##"; }

# Text of every node with this resource id, one per line.
texts_of() {
  python3 - "$(dump)" "$PKG:id/$1" <<'PY'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    if n.get('resource-id') == sys.argv[2] and n.get('text'): print(n.get('text'))
PY
}

# All row titles of a scrolling settings list, top to bottom (scrolls the whole list).
all_row_titles() {
  scroll_top
  local seen="" page last=""
  for _ in $(seq 1 40); do
    page="$(texts_of rowTitle)"
    seen="$(printf '%s\n%s' "$seen" "$page")"
    [[ "$page" == "$last" ]] && break
    last="$page"
    sh_ input swipe 540 1900 540 700 400; sleep 0.8
  done
  printf '%s\n' "$seen" | awk 'NF && !s[$0]++'
}

# One-off sheets the app shows on its own (link-handling question on first start, donation
# prompt after a number of starts). Answer "no" so they don't cover the screen.
dismiss_prompts() {
  local f; f="$(dump)"; [[ -f "$f" ]] || return 0
  for t in "no moners :(" "No"; do
    grep -q "text=\"$t\"" "$f" && { tap_text "$t"; return 0; }
  done
  return 0
}

open_settings() { start_activity ani.dantotsu.settings.SettingsActivity; dismiss_prompts; }

# Start a (non-exported) activity of the app. Needs `adb root` on a google_apis image.
start_activity() { sh_ am start -S -W -n "$PKG/$1" "${@:2}" >/dev/null; sleep 1.5; }

crashed() { # true if the app logged a FATAL EXCEPTION since the last clear_log
  a logcat -d -b crash 2>/dev/null | grep -q "FATAL EXCEPTION"
}
clear_log() { a logcat -c -b crash 2>/dev/null || true; a logcat -c 2>/dev/null || true; }
crash_head() { a logcat -d -b crash | grep -A6 "FATAL EXCEPTION" | head -8; }

# Write one boolean into an app prefs file (app is force-stopped first). Needs adb root.
# Example: set_bool_pref ani.dantotsu.ui.xml BannerAnimations false
set_bool_pref() {
  local file="/data/data/$PKG/shared_prefs/$1" tmp="$OUT/pref.xml" owner
  sh_ am force-stop "$PKG"
  owner="$(sh_ "stat -c %U:%G /data/data/$PKG" | tr -d '\r')"
  sh_ "cat $file 2>/dev/null" > "$tmp" || true
  python3 - "$tmp" "$2" "$3" <<'PY'
import sys, re
f, name, val = sys.argv[1:4]
s = open(f).read() if open(f).read().strip() else "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
s = re.sub(r'\s*<boolean name="%s" value="[^"]*" />' % re.escape(name), '', s)
s = s.replace('</map>', '    <boolean name="%s" value="%s" />\n</map>' % (name, val))
open(f, 'w').write(s)
PY
  a push "$tmp" "$file" >/dev/null 2>&1
  sh_ "chown $owner $file && chmod 660 $file"
}

# Animations stop uiautomator from ever seeing an idle screen.
quiet_animations() {
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    sh_ settings put global "$k" 0
  done
  set_bool_pref ani.dantotsu.ui.xml BannerAnimations false
}

# Print the current screen's labelled nodes (text or description, id, class, bounds). For writing flows.
show_screen() {
  python3 - "$(dump)" <<'PY'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    t = n.get('text') or n.get('content-desc'); r = n.get('resource-id').split('/')[-1]
    if t or n.get('checkable') == 'true':
        print(repr(t), r, n.get('class').split('.')[-1], n.get('bounds'))
PY
}
