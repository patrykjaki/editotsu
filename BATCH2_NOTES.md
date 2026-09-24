# Batch 2 working notes — network/auth resilience (2026-09-24)

Base: `016d639c` (frozen Batch 1). No Batch 1 modification. No merge.

## Ported (manual minimal ports; `Upstream <sha>` comments in code)

- `8966c9bd` network subset: `AnilistInterceptor` (new, exact),
  `NetworkHelper` import + wiring, image-only `noStore` interceptor,
  `downloadClient.cache(null)`, `UncaughtExceptionInterceptor` OOM catch,
  `Anilist.kt` + `MediaNameFetch.kt` Referer/Origin headers.
- `c03efc37` re-login subset: `initialized=false` + user-id/name pref
  removal + home/stories cache invalidation in `removeSavedToken`;
  `getUserId` avatar-null re-fetch guard.
- `e70fdc56` error plumbing: `lastError` surface, manual OkHttp JSON body
  (NiceHttp reflection bypass), validation-error extraction,
  `extractErrorMessage` + `postReview` conversion. `force → cacheTime = 0`
  restored (see 5ffa682b note).
- `5ffa682b` resilience subset: `invalidateHomePageCache` /
  `invalidateUserStatusCache`, `getUserStatus(forceRefresh)` + `myStatus` +
  `MyActivities` field, `initUserStatus(forceRefresh)` + HomeFragment PTR,
  mutation invalidation sites, `UpdateProgress` invalidation + removeList,
  PrefManager 5-site sanitize + write normalization, stories/hidden-items
  harmonization, `Jikan.kt` + `JikanQueries` mirrors + `fetchWithFallback`,
  AnimeFragment/MangaFragment PTR-clear EXCLUDED (out of scope, see below).
- `e5f41fe9`: idMAL backfill, simplified rate-limited get (seam kept),
  MalScraper rewrite (UA, parseSongs, Jikan fallback).
- `ac76c79e`: dual-key id readers (MainActivity, UrlMedia), notification
  dual extras + UPDATE_CURRENT (UrlMedia routing kept as local invariant).

## STOPPED / excluded (for later review, not rejected)

- `8966c9bd` noStore video-segment half (.ts/.m4s/.m3u8/.mp4/.mkv,
  /segment, Range) → player-network review bundle.
- `8966c9bd` MediaDetails recreation rework (save/restore, re-put extras,
  bindingReady guards) → contradicts frozen Batch 1 removeExtra; needs a
  dedicated design decision (recreation-crash claim vs Assist leak).
- `8966c9bd` player/manga/RTL/extraction/build/UI hunks → protected lanes.
- `c03efc37` selector-dialog + subtitle-mime + torrent hunks → protected lanes.
- `e70fdc56` forum/review UI + SaveThread default category → deferred
  forum surface (no saveThread locally).
- `5ffa682b` Shizuku AIDL, -1 buttons, widget, downloader SAF matching,
  AnimeWatch force flags, Exo/Selector/Player/MangaReader hunks,
  Media.kt weeks guard, MediaDetailsViewModel nextAiring/force loaders,
  getUserId nullability + requireContext call sites, Anime/MangaFragment
  PTR-clear → out of scope or protected lanes.
- `Media.kt` removeList call sites (733-748): untouched by upstream;
  crash-free via sanitize; left as-is (no broadening).
- Stores (Chapter/Episode/Novel): untouched per Batch 3 correction.

## Local invariants preserved

- `JikanQueries.testHttpHandler` seam kept (fallback test uses it).
- `NiceHttp 0.4.4` (no upgrade); `force` cache-bypass restored because the
  authorized PTR behavior requires it.
- UrlMedia routing for notifications (not MainActivity-direct).
- `extractErrorMessage` widened private→internal for unit tests.
