#!/usr/bin/env bash
# Backup then restore brings the settings back.
# 1. flip the first switch on Appearance, Player and Reading (so the backup holds non-defaults)
# 2. Back up now (settings only) -> save through the system file picker
# 3. copy the app's settings files (A), flip the same switches again
# 4. Restore Backup -> pick the file, copy the settings files again (C)
# 5. A and C must hold the same values. Needs `adb root` (google_apis image) to read the files.
cd "$(dirname "$0")" && source ./lib.sh
SCREENS=("Appearance" "Player" "Reading")
PREFS=/data/data/$PKG/shared_prefs
FILES=(ani.dantotsu.general.xml ani.dantotsu.player.xml ani.dantotsu.ui.xml ani.dantotsu.reader.xml)
# Keys that legitimately change between the two copies.
IGNORE='LastAutoBackup|lastBackup|LastBackup|backup_time|SettingsAdvanced'


open_screen() { open_settings; scroll_to_text "$1" >/dev/null; tap_text "$1"; sleep 1; }
flip_first_switch() { # screen
  open_screen "$1"
  local c; c="$(_center resource-id "$PKG:id/rowSwitch")"
  [[ -n "$c" ]] || return 1
  tap_xy $c
}
snapshot() { # dir
  mkdir -p "$1"; sh_ am force-stop "$PKG"; sleep 0.5
  for f in "${FILES[@]}"; do
    sh_ "cat $PREFS/$f 2>/dev/null" | python3 -c '
import sys, re
for l in sys.stdin:
    m = re.search(r"name=\"([^\"]+)\"", l)
    if m and not re.search(sys.argv[1], m.group(1)): print(l.strip())' "$IGNORE" | sort > "$1/$f"
  done
}

echo "backup -> restore round trip"
clear_log
sh_ "rm -f /sdcard/Download/Editotsu_*.ani"
for s in "${SCREENS[@]}"; do flip_first_switch "$s" || fail "$s: no switch on first page"; done

open_screen "Data and Storage"
scroll_to_text "Back up now" >/dev/null; tap_text "Back up now"; sleep 1.5
tap_text "Backup"; sleep 3
tap_text "SAVE" || tap_text "Save" || { fail "file picker did not offer SAVE"; summary; exit; }
sleep 2
file="$(sh_ "ls /sdcard/Download/ | grep '^Editotsu_.*\.ani$' | tail -1" | tr -d '\r')"
[[ -n "$file" ]] && pass "backup saved: $file ($(sh_ "stat -c %s /sdcard/Download/$file" | tr -d '\r') bytes)" || { fail "no backup file"; summary; exit; }

snapshot "$OUT/A"
for s in "${SCREENS[@]}"; do flip_first_switch "$s"; done
snapshot "$OUT/B"
if diff -rq "$OUT/A" "$OUT/B" >/dev/null; then fail "changing settings after the backup changed nothing"; summary; exit; fi

open_screen "Data and Storage"
scroll_to_text "Restore Backup" >/dev/null; tap_text "Restore Backup"; sleep 2.5
tap_text "$file" || { fail "file picker did not list $file"; summary; exit; }
sleep 3
dismiss_prompts
snapshot "$OUT/C"

if crashed; then fail "crashed"; crash_head
elif d="$(diff -r "$OUT/A" "$OUT/C")"; then pass "restored settings match the backup ($(cat "$OUT"/A/* | wc -l | tr -d ' ') values)"
else fail "restored settings differ from the backup:"; echo "$d" | head -20; fi
summary
