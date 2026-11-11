package ani.dantotsu.animethemes

import kotlin.random.Random

/** Which themes a shuffle or "play favourites" uses; stored as [PrefName.AnimeThemesRadioKinds]. */
enum class ThemeKinds(val value: Int) {
    BOTH(0), OPENINGS(1), ENDINGS(2);

    fun accepts(isEnding: Boolean): Boolean = when (this) {
        BOTH -> true
        OPENINGS -> !isEnding
        ENDINGS -> isEnding
    }

    companion object {
        fun fromInt(value: Int): ThemeKinds = entries.find { it.value == value } ?: BOTH
    }
}

/** A show the shuffle can draw from. */
data class RadioShow(val mediaId: Int, val idMal: Int?, val title: String)

/** Pure parts of the shuffle, so the order and the picks can be tested with a seeded [Random]. */
object ThemeRadio {
    /** Every show once, in random order. */
    fun order(shows: List<RadioShow>, random: Random): List<RadioShow> =
        shows.distinctBy { it.mediaId }.shuffled(random)

    /** One theme of the show matching [kinds], at its preferred version; null when none matches. */
    fun pick(choices: List<ThemeChoice>, kinds: ThemeKinds, random: Random): ThemeTrack? =
        choices.filter { kinds.accepts(it.isEnding) }.randomOrNull(random)?.auto

    /** Favourites matching [kinds], shuffled when [shuffle] is on. */
    fun favouritesQueue(
        favourites: List<FavouriteTheme>,
        kinds: ThemeKinds,
        shuffle: Boolean,
        random: Random,
    ): List<ThemeTrack> {
        val tracks = favourites.map { it.track }.filter { kinds.accepts(it.isEnding) }
        return if (shuffle) tracks.shuffled(random) else tracks
    }
}

/**
 * The running "shuffle completed" queue. The Themes screen starts it with the user's
 * completed shows; the theme player asks it for the next theme when its queue runs out.
 * Shows are looked up one at a time (cache first), so AnimeThemes' rate limit holds.
 */
object ThemeRadioSession {
    private var order: List<RadioShow> = emptyList()
    private var cursor = 0
    private var kinds = ThemeKinds.BOTH
    private var prefs = ThemePlaybackPreferences()
    private var random: Random = Random.Default

    @Volatile
    var active = false
        private set

    fun start(shows: List<RadioShow>, kinds: ThemeKinds, prefs: ThemePlaybackPreferences, seed: Long = System.nanoTime()) {
        random = Random(seed)
        order = ThemeRadio.order(shows, random)
        cursor = 0
        this.kinds = kinds
        this.prefs = prefs
        active = order.isNotEmpty()
    }

    fun stop() {
        active = false
        order = emptyList()
    }

    /**
     * The next playable theme, looking up at most [maxLookups] shows; null when the
     * list is used up or nothing was found. [lookup] returns a show's themes, null when the
     * show has none, and throws when the lookup failed. A show is passed over only after its
     * lookup answered: a failed or cancelled one is tried again on the next call.
     */
    suspend fun next(
        maxLookups: Int = 8,
        lookup: suspend (RadioShow) -> AnimeThemesData? = ::lookupShow,
    ): ThemeTrack? {
        if (!active) return null
        var tries = 0
        while (active && cursor < order.size && tries < maxLookups) {
            val show = order[cursor]
            tries++
            val data = try {
                lookup(show)
            } catch (e: LookupFailed) {
                return null
            }
            cursor++
            if (data == null) continue
            val choices = ThemeQueue.choices(data, prefs).map { ThemeQueue.withShow(it, show.mediaId, show.title) }
            ThemeRadio.pick(choices, kinds, random)?.let { return it }
        }
        if (cursor >= order.size) active = false
        return null
    }

    /** AnimeThemes did not answer for a show. */
    class LookupFailed : Exception()

    private suspend fun lookupShow(show: RadioShow): AnimeThemesData? =
        when (val result = AnimeThemes.repository.lookupShared(show.mediaId, show.idMal).await()) {
            is ThemesLookup.Found -> result.data
            ThemesLookup.NoMatch -> null
            ThemesLookup.Failed -> throw LookupFailed()
        }
}
