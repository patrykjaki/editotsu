# AniList score formats — design note

Status: converter + tests + MAL mirror done. **UI changes (steps 2-4) need owner OK.**

## How scores work today (checked in code)

- Every AniList query reads `score(format:POINT_100)`; the editor writes `scoreRaw`
  (0-100). So `Media.userScore` is always raw 0-100. Data is correct; only the UI
  ignores the user's format.
- The list editor (`MediaListDialogFragment.kt:122`, `MediaListDialogSmallFragment.kt:133`)
  always shows and takes `x / 10` (0.0-10.0), whatever format is set.
- Save does `(text.toDouble() * 10).toInt()`, which truncates instead of rounding.
- Every save sends the score, even when the user didn't touch it.
- Cards (`MediaAdaptor.kt`, 4 places) and offline lists show `userScore / 10.0`.
- MAL mirror (`MALQueries.kt:204`) sent `round(raw / 10)`; raw 1-4 became MAL 0,
  which clears the score on MAL.
- Upstream Dantotsu has no prior work beyond a stats-chart helper.

## Conversion rules (`connections/anilist/ScoreFormats.kt`)

| Format | Shown from raw | Saved as raw |
|---|---|---|
| POINT_100 | raw | value |
| POINT_10_DECIMAL | raw / 10 (one decimal) | round(value × 10) |
| POINT_10 | round(raw / 10) | value × 10 |
| POINT_5 | round(raw / 20) | stars × 20 |
| POINT_3 | ≤35 🙁, ≤60 😐, else 🙂 | 35 / 60 / 85 |
| MAL mirror | round(raw / 10), clamped 1-10 when scored | — |

- Rounding is half-up. 0 always means unscored; a scored entry never shows as 0.
- The POINT_5 and POINT_3 read rules match Mihon's AniList tracker. AniList doesn't
  document its own cutoffs, so these are not checked against the live site.
- Examples: 7.5 decimal → 4 stars → 🙂 → MAL 8.

## Proposed UI (needs OK)

1. **List editor** (both dialogs): the field uses the user's format.
   - POINT_100: whole number 0-100. POINT_10_DECIMAL: 0.0-10.0. POINT_10: 0-10.
   - POINT_5: 0-5 stars. POINT_3: a dropdown with none / 🙁 / 😐 / 🙂.
   - Suffix shows `/100`, `/10`, `/5`.
   - Send a score only if the user changed it, so opening and saving never rewrites a
     raw score (e.g. raw 77 in 5-star mode would otherwise become 80).
2. **Cards and offline lists:** your own score shows in your format (`77`, `7.7`, `8`,
   `4`, 🙂). Mean score stays as now (`7.7`, AniList average /10).
3. **Other users' scores** (followers on the details page) and review scores stay /10.
   Their API values are already raw 0-100, so this stays consistent.
4. **Format change in settings:** already saves to AniList; after the change, refresh
   lists so cards use the new format.

Not changed: rescue-mode pending updates (they already store raw), stats charts.

## Questions for the owner

- OK with the UI above?
- Stars and smileys: number field or a picker (dropdown)? Default: number for stars,
  dropdown for smileys.
