#!/usr/bin/env bash
# On each settings screen with an advanced card: showing advanced adds rows and keeps the
# basic ones, hiding it brings the list back.
cd "$(dirname "$0")" && source ./lib.sh
# About has no advanced rows, so no card.
SCREENS=("Appearance" "Player" "Reading" "Sources" "Data and Storage")
[[ $# -gt 0 ]] && SCREENS=("$@")  # e.g. ./03_settings_advanced_toggle.sh Player
SHOW="Show advanced settings"; HIDE="Hide advanced settings"

echo "advanced toggle"
clear_log
for s in "${SCREENS[@]}"; do
  open_settings; scroll_to_text "$s" >/dev/null; tap_text "$s"; sleep 1
  before="$(all_row_titles)"
  if ! scroll_to_text "$SHOW" exact 15; then fail "$s: no \"$SHOW\" card"; continue; fi
  tap_text "$SHOW"; sleep 1
  if ! has_text "$HIDE" && ! scroll_to_text "$HIDE" exact 15; then fail "$s: card did not switch to \"$HIDE\""; continue; fi
  after="$(all_row_titles)"
  missing="$(comm -23 <(sort <<<"$before") <(sort <<<"$after"))"
  added="$(comm -13 <(sort <<<"$before") <(sort <<<"$after") | wc -l | tr -d ' ')"
  scroll_to_text "$HIDE" exact 15 >/dev/null; tap_text "$HIDE"; sleep 1
  back_again="$(all_row_titles)"
  if crashed; then fail "$s: crashed"; crash_head; clear_log
  elif [[ -n "$missing" ]]; then fail "$s: basic rows gone after showing advanced: $(tr '\n' ',' <<<"$missing")"
  elif [[ "$added" -eq 0 ]]; then fail "$s: showing advanced added no rows"
  elif [[ "$back_again" != "$before" ]]; then fail "$s: hiding advanced did not restore the list"
  else pass "$s: +$added advanced rows, hide restores the list"; fi
done
summary
