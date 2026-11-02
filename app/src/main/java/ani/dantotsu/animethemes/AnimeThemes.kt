package ani.dantotsu.animethemes

/** Process-wide repository so the memory cache survives leaving and reopening a details screen. */
object AnimeThemes {
    val repository: AnimeThemesRepository by lazy { AnimeThemesRepository() }
}
