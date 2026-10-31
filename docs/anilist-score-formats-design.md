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

Read rules are checked against AniList itself: the public API returns the same
list entry in any format (`score(format: X)`). Sampled 2026-10-31 from 22 public
lists with 100-point scores plus 12 other users; 55 distinct raw values are in
`ScoreFormatsTest` as a fixture.

| Format | Shown from raw (AniList's rule) | Saved as raw |
|---|---|---|
| POINT_100 | raw | value |
| POINT_10_DECIMAL | raw / 10 | round(value × 10) |
| POINT_10 | floor(raw / 10); raw 1-9 shows 0 | value × 10 |
| POINT_5 | round(raw / 20) half-up, min 1 when scored | stars × 20 |
| POINT_3 | ≤35 🙁, ≤60 😐, else 🙂 | 35 / 60 / 85 |
| MAL mirror | floor(raw / 10), min 1 when scored | — |

- Observed edges: POINT_5 1→1, 25→1, 30→2, 69→3, 70→4, 90→5. POINT_3 30→1,
  40→2, 60→2, 61→3. The 31-39 smiley edge was not in the sample; 35 is Mihon's.
- MAL gets the same value AniList shows in 10-point, except a scored entry never
  sends 0 (MAL 0 clears the score).
- Examples: 7.5 decimal → 7 (10-point) → 4 stars → 🙂 → MAL 7.

## AniList statistics

- `statistics.*.meanScore` is always on the raw 0-100 scale, whatever the format
  (10-point user: stats 80.46 = mean raw 80.46).
- `statistics.*.scores[].score` buckets are in the user's format: 10-point user
  5..10; 10-point decimal user 30, 35 … 100 (tenths, since the field is Int).
  No star/smiley user in the sample had computed stats (AniList leaves many
  accounts at 0), so those bucket scales are inferred, not observed.
- Stats charts now use `ScoreFormats.statsBucketToRaw` instead of the old
  `×10 / ×20 / ×33` guess. Change: smiley buckets plot at 35/60/85, not 33/66/99.
- The profile header shows `meanScore` as-is (0-100). Unchanged.

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
