package ani.dantotsu.media

/**
 * M1 FINAL CLOSE (V4 Blocker 1): carousel-logo identity policy.
 *
 * A MAL/Rescue-constructed [Media] carries the MAL identity in `id`
 * (`idMAL == id`), so `id` must NEVER enter an AniList-ID mapper
 * ([ani.dantotsu.others.IdMappers.getIds]) — it would resolve the wrong
 * title/logo. AniList-mode media may use the mapper freely. An already
 * known `idTMDB` is identity-free and usable in either mode.
 *
 * Framework-free so the contract is unit-proved (CarouselLogoPolicyTest).
 */
internal fun isMalIdentityMode(rescueMode: Boolean, id: Int, idMAL: Int?): Boolean =
    rescueMode || (idMAL != null && idMAL == id)

internal fun mayUseAnilistIdMapper(rescueMode: Boolean, id: Int, idMAL: Int?): Boolean =
    !isMalIdentityMode(rescueMode, id, idMAL)
