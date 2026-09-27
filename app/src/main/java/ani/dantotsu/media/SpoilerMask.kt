package ani.dantotsu.media

/**
 * MAX MERGE M3 (upstream e954d9f1): spoiler chip visibility rule.
 * Masked until tapped when the preference is on and the tag is flagged;
 * otherwise revealed. Framework-free, unit-proved (SpoilerMaskTest).
 * Index-parallelism with `tags` is the caller's contract (see
 * `tagsIsSpoiler` + `getOrNull` at call sites).
 */
fun spoilerChipRevealed(hideSpoilerTags: Boolean, isSpoiler: Boolean): Boolean =
    !hideSpoilerTags || !isSpoiler
