# Batch 3 classification correction (2026-09-24)

Supersedes the future-Batch-3 sketch in `EDITOTSU_UPSTREAM_SYNC_AUDIT_2026-09-24.md`
(§M "Batch 3 — extension compatibility"), which listed `ChapterStorage` /
`EpisodeStorage` / `NovelStorage` alongside additive extension ports.

## Corrected classification

- `ChapterStorage` (upstream `3d0666df`, new file
  `app/src/main/java/ani/dantotsu/media/manga/ChapterStorage.kt`) is
  **CONFLICTING — REVIEW REQUIRED**, not SAFE-PORT. It overlaps the CP5
  `MangaDownloadOwnership` / persistence / cancellation / SAF lane and
  requires the store-vs-store diff (audit §N.2) before any port.
- `EpisodeStorage` (upstream `61e8248b`) and `NovelStorage` (upstream
  `3d0666df`) likewise remain **unintegrated** until their
  ownership/lifecycle implications are reviewed — `EpisodeStorage`
  interacts with the Source Picker v3 episode-list contract, and
  `NovelStorage` with novel persistence ownership.
- **No store implementation is authorized in Batch 1.** None of the three
  files is created or referenced by the Batch 1 branch.

## Batch 1 scope (unchanged)

`576eccba` · approved `de3c87d1` hygiene (ExoplayerView hunk excluded) ·
manual minimal `3f366f69` (desugar/proguard/models/intent only) ·
`7a236d3d` strings-only.
