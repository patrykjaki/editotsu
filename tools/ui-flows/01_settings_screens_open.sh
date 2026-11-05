#!/usr/bin/env bash
# Every settings screen opens from the settings home and shows rows, without a crash.
cd "$(dirname "$0")" && source ./lib.sh
SCREENS=("Appearance" "Player" "Reading" "Sources" "Tracking" "Data and Storage" "About")

echo "settings screens open"
clear_log
for s in "${SCREENS[@]}"; do
  open_settings
  if ! scroll_to_text "$s"; then fail "$s: card not on settings home"; continue; fi
  tap_text "$s"; sleep 1
  # Tracking, logged out, shows only the login cards (no rows) under "Services".
  rows="$(texts_of rowTitle | wc -l | tr -d ' ')"
  if crashed; then fail "$s: crashed"; crash_head; clear_log
  elif has_text "$s" && { [[ "$rows" -gt 0 ]] || has_text "Services"; }; then pass "$s ($(top_activity), $rows rows on first page)"; shot "screen_${s// /_}"
  else fail "$s: title or rows missing ($rows rows)"; fi
done
summary
