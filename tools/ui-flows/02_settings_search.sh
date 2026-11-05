#!/usr/bin/env bash
# Settings search: typing "backup" lists results without crashing; tapping the first opens
# Data and Storage with that row on screen.
cd "$(dirname "$0")" && source ./lib.sh
QUERY="${1:-backup}"

echo "settings search \"$QUERY\""
clear_log
open_settings
tap_id settingsSearchBarText
sh_ input text "$QUERY"; sleep 2
if crashed; then fail "search crashed while typing"; crash_head; summary; exit; fi
first="$(texts_of searchResultTitle | head -1)"
if [[ -z "$first" ]]; then fail "no results for \"$QUERY\""; summary; exit; fi
pass "results listed, first: \"$first\""
tap_id searchResultTitle; sleep 1.5
act="$(top_activity)"
if crashed; then fail "crashed opening the result"; crash_head
elif [[ "$act" == *SettingsDataActivity ]] && texts_of rowTitle | grep -qxF "$first"; then pass "opened $act with \"$first\" on screen"; shot search_result
else fail "landed on $act without \"$first\" on screen"; fi
summary
