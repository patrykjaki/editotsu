#!/usr/bin/env bash
# Themes tab + favourites: open Cowboy Bebop (AniList 1) from an anilist.co link, open the
# Themes tab, heart OP 1, check it is saved and listed on the Themes screen, remove it there.
# Needs network (AniList + AnimeThemes) and adb root.
#
# The media page never goes idle for `uiautomator dump` (its ViewPager keeps sending
# accessibility "subtree changed" events), so the two taps on that page go by position.
# Positions are for the editotsu_uiflows AVD (1080x2392, 440dpi). The result is checked
# through the saved favourites and the Themes screen, which can be read normally.
cd "$(dirname "$0")" && source ./lib.sh
THEMES_TAB=(675 2244)   # 3rd bottom-nav item
OP1_HEART=(675 1742)    # heart on the first opening row
GENERAL=/data/data/$PKG/shared_prefs/ani.dantotsu.general.xml

favs() { sh_ "grep -h AnimeThemesFavourites $GENERAL 2>/dev/null" | tr -d '\r'; }

echo "themes tab + favourites"
clear_log
quiet_animations
# Start from no favourites.
sh_ "sed -i '/AnimeThemesFavourites/d' $GENERAL" 2>/dev/null || true

sh_ am start -W -a android.intent.action.VIEW -d https://anilist.co/anime/1 -p "$PKG" >/dev/null
sleep 8; dismiss_prompts
[[ "$(top_activity)" == *MediaDetailsActivity ]] && pass "anilist.co link opens the media page" || fail "media page did not open ($(top_activity))"
tap_xy "${THEMES_TAB[@]}" 6
shot themes_tab
tap_xy "${OP1_HEART[@]}" 2
f="$(favs)"
if [[ "$f" == *"Tank!"* ]]; then pass "heart on OP 1 saved \"Tank!\" to favourites"; else fail "favourite not saved: ${f:0:120}"; fi

start_activity ani.dantotsu.animethemes.ThemesActivity; sleep 2
if has_text "Tank!" && has_text "Favourites · 1"; then pass "Themes screen lists the favourite"; shot themes_favourites
else fail "Themes screen does not list the favourite"; fi
tap_id favHeart; sleep 1.5
f="$(favs)"
if [[ "$f" != *"Tank!"* ]] && ! has_text "Tank!"; then pass "removing it on the Themes screen clears it"
else fail "favourite still there after removing it"; fi

crashed && { fail "crashed"; crash_head; }
summary
