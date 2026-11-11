package ani.dantotsu.animethemes

import ani.dantotsu.Mapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One liked opening or ending, with the show it belongs to and the version that was shown. */
@Serializable
data class FavouriteTheme(
    val mediaId: Int,
    val animeTitle: String,
    val track: ThemeTrack,
    val addedAt: Long = 0L,
)

/** Pure list operations; one entry per theme (the heart is per row, not per version). */
object ThemeFavouriteList {
    fun contains(list: List<FavouriteTheme>, themeId: Int): Boolean = list.any { it.track.themeId == themeId }

    /** Adds [fav] at the top, or removes the theme when it is already in the list. */
    fun toggle(list: List<FavouriteTheme>, fav: FavouriteTheme): List<FavouriteTheme> =
        if (contains(list, fav.track.themeId)) list.filterNot { it.track.themeId == fav.track.themeId }
        else listOf(fav) + list

    fun remove(list: List<FavouriteTheme>, themeId: Int): List<FavouriteTheme> =
        list.filterNot { it.track.themeId == themeId }

    fun decode(json: String?): List<FavouriteTheme> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { Mapper.json.decodeFromString(ListSerializer(FavouriteTheme.serializer()), json) }
            .getOrDefault(emptyList())
            // Favourites saved before tags were normalised read "ED1".
            .map { it.copy(track = it.track.copy(tag = ThemeTags.normalize(it.track.tag))) }
    }

    fun encode(list: List<FavouriteTheme>): String =
        Mapper.json.encodeToString(ListSerializer(FavouriteTheme.serializer()), list)
}

/** Favourite themes, kept in the app's preferences (so they are part of the backup). */
object ThemeFavourites {
    private val state = MutableStateFlow<List<FavouriteTheme>?>(null)

    val flow: StateFlow<List<FavouriteTheme>?> get() = state.also { if (it.value == null) it.value = load() }

    fun all(): List<FavouriteTheme> = state.value ?: load().also { state.value = it }

    fun isFavourite(themeId: Int): Boolean = ThemeFavouriteList.contains(all(), themeId)

    /**
     * Returns true when the theme is a favourite afterwards. Edits start from the stored list,
     * not the in-memory copy, so a restore in the same process is never saved over.
     */
    fun toggle(mediaId: Int, animeTitle: String, track: ThemeTrack): Boolean {
        val next = ThemeFavouriteList.toggle(load(), FavouriteTheme(mediaId, animeTitle, track, System.currentTimeMillis()))
        save(next)
        return ThemeFavouriteList.contains(next, track.themeId)
    }

    fun remove(themeId: Int) = save(ThemeFavouriteList.remove(load(), themeId))

    /** Re-reads the stored list, e.g. after a backup was restored. */
    fun reload() {
        state.value = load()
    }

    private fun load(): List<FavouriteTheme> =
        ThemeFavouriteList.decode(PrefManager.getVal<String>(PrefName.AnimeThemesFavourites))

    private fun save(list: List<FavouriteTheme>) {
        PrefManager.setVal(PrefName.AnimeThemesFavourites, ThemeFavouriteList.encode(list))
        state.value = list
    }
}
