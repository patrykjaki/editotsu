package ani.dantotsu.notifications.subscription

import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.Selected
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.BaseParser
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.MangaChapter
import ani.dantotsu.parsers.MangaParser
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionHelper {
    companion object {
        private fun loadSelected(
            mediaId: Int
        ): Selected {
            val data =
                PrefManager.getNullableCustomVal("Selected-${mediaId}", null, Selected::class.java)
                    ?: Selected().let {
                        it.sourceIndex = 0
                        it.preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
                        it
                    }
            return data
        }

        private fun saveSelected(mediaId: Int, data: Selected) {
            PrefManager.setCustomVal("Selected-${mediaId}", data)
        }

        /**
         * The subscribed source is kept by name: the saved index points into the installed
         * list, which shifts when extensions are added or removed (and the details screen
         * may index a different list, e.g. with adult sources). Null keeps the saved index.
         */
        fun saveSource(mediaId: Int, sourceName: String) {
            if (sourceName.isNotBlank()) PrefManager.setCustomVal("$SOURCE_NAME$mediaId", sourceName)
        }

        internal fun resolveSourceIndex(mediaId: Int, names: List<String>): Int? {
            val name = PrefManager.getNullableCustomVal("$SOURCE_NAME$mediaId", null, String::class.java)
            return sourceIndexFor(name, names)
        }

        internal fun sourceIndexFor(name: String?, names: List<String>): Int? =
            name?.let { names.indexOf(it) }?.takeIf { it >= 0 }

        /**
         * A subscription saved with a source name checks only that source. When it is no
         * longer installed the parser lookup falls back to another one; that check is skipped.
         */
        internal fun checksSource(savedSource: String?, parserName: String): Boolean =
            savedSource == null || savedSource == parserName

        /** Runs [block] with the extension language the subscription was made on, then restores it. */
        private suspend fun <T> withSourceLanguage(parser: BaseParser, lang: Int, block: suspend () -> T): T {
            val anime = parser as? DynamicAnimeParser
            val manga = parser as? DynamicMangaParser
            val previous = anime?.sourceLanguage ?: manga?.sourceLanguage
            anime?.sourceLanguage = lang
            manga?.sourceLanguage = lang
            try {
                return block()
            } finally {
                if (previous != null) {
                    anime?.sourceLanguage = previous
                    manga?.sourceLanguage = previous
                }
            }
        }

        fun subscribedSource(mediaId: Int): String? =
            PrefManager.getNullableCustomVal("$SOURCE_NAME$mediaId", null, String::class.java)

        /**
         * Whether the bell shows as on for [sourceName]: the title is subscribed and the
         * subscription uses this source. Subscriptions from before 0.7 have no saved
         * name and show on every source, as they did.
         */
        fun isSubscribedOn(mediaId: Int, sourceName: String?): Boolean =
            bellOn(getSubscriptions().containsKey(mediaId), subscribedSource(mediaId), sourceName)

        internal fun bellOn(subscribed: Boolean, savedSource: String?, sourceName: String?): Boolean =
            subscribed && (savedSource == null || savedSource == sourceName)

        /**
         * Newest episode/chapter the subscription has seen, kept apart from the watch
         * screen's "Selected" state: browsing another source reset that one and made the
         * next check announce an episode that was not new.
         */
        fun saveLatest(mediaId: Int, latest: Float) =
            PrefManager.setCustomVal("$LATEST$mediaId", latest.toString())

        /**
         * The episode's own title for the notification, without a leading "Episode 5",
         * "Episode 5:", "Ep. 5 -" the source already put in; null when nothing is left.
         */
        internal fun episodeTitle(number: String, title: String?): String? {
            val t = title?.trim().orEmpty()
            val prefix = Regex(
                "^(episode|ep\\.?)\\s*0*" + Regex.escape(number.trim().trimStart('0').ifEmpty { "0" }) +
                    "(?![0-9])\\s*[:\\-–—.]?\\s*",
                RegexOption.IGNORE_CASE,
            )
            return t.replaceFirst(prefix, "").trim().takeIf { it.isNotEmpty() }
        }

        /**
         * How many episodes AniList says have aired: next airing episode - 1 while
         * releasing, the episode count once finished, 0 before release; null if unknown.
         */
        internal fun airedLimit(status: String?, airedBeforeNext: Int?, totalEpisodes: Int?): Int? =
            when (status?.uppercase()) {
                "RELEASING" -> airedBeforeNext
                "FINISHED" -> totalEpisodes
                "NOT_YET_RELEASED" -> 0
                else -> null
            }

        internal fun withinAired(number: Float?, limit: Int?): Boolean =
            limit == null || number == null || number <= limit

        /** Aired limits for the subscribed anime, one AniList request; empty when it fails. */
        suspend fun airedLimits(ids: List<Int>): Map<Int, Int> {
            if (ids.isEmpty()) return emptyMap()
            // Raw AniList fields: Media.status in the app model is a translated label.
            val list = try {
                ids.chunked(50).flatMap { chunk ->
                    ani.dantotsu.connections.anilist.Anilist.executeQuery<ani.dantotsu.connections.anilist.api.Query.MediaList>(
                        "{Page(page:1,perPage:50){media(id_in:[${chunk.joinToString(",")}]){id status episodes nextAiringEpisode{episode}}}}",
                        force = true,
                    )?.data?.page?.media.orEmpty()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("subscription aired check failed: ${e.message}")
                emptyList()
            }
            return list.mapNotNull { m ->
                airedLimit(m.status?.name, m.nextAiringEpisode?.episode?.minus(1), m.episodes)?.let { m.id to it }
            }.toMap()
        }

        /** Only a check with a saved baseline may announce something; the first one seeds it. */
        internal fun announce(baseline: Float?): Boolean = baseline != null

        // Kept as text: a Float pref reads 0 when missing, and missing must mean
        // "nothing seen yet" for subscriptions made before this.
        internal fun loadLatest(mediaId: Int): Float? =
            PrefManager.getNullableCustomVal("$LATEST$mediaId", null, String::class.java)?.toFloatOrNull()

        fun getAnimeParser(id: Int): AnimeParser {
            val sources = AnimeSources
            Logger.log("getAnimeParser size: ${sources.list.size}")
            val selected = loadSelected(id)
            resolveSourceIndex(id, sources.names)?.let { selected.sourceIndex = it }
            if (selected.sourceIndex >= sources.list.size) {
                selected.sourceIndex = 0
                saveSelected(id, selected)
            }
            val parser = sources[selected.sourceIndex]
            parser.selectDub = selected.preferDub
            return parser
        }

        /**
         * [airedLimit]: episodes AniList says have aired (null = unknown, no limit).
         * An episode above it is a mislabelled or early upload: neither announced nor
         * recorded, so it still gets announced once it really airs.
         */
        suspend fun getEpisode(
            parser: AnimeParser,
            subscribeMedia: SubscribeMedia,
            airedLimit: Int? = null,
        ): Episode? {

            val selected = loadSelected(subscribeMedia.id)
            val savedSource = subscribedSource(subscribeMedia.id)
            if (!checksSource(savedSource, parser.name)) {
                Logger.log("subscription ${subscribeMedia.id}: source $savedSource not installed, check skipped")
                return null
            }
            val baseline = loadLatest(subscribeMedia.id)
            val ep = withSourceLanguage(parser, selected.langIndex) { withTimeoutOrNull(10 * 1000) {
                tryWithSuspend {
                    val show = parser.loadSavedShowResponse(subscribeMedia.id)
                        ?: forceLoadShowResponse(subscribeMedia, selected, parser)
                        ?: throw Exception(
                            currContext()?.getString(
                                R.string.failed_to_load_data,
                                subscribeMedia.id
                            )
                        )
                    show.sAnime?.let {
                        parser.getLatestEpisode(
                            show.link, show.extra,
                            it, baseline ?: -1f
                        )
                    }
                }
            } }

            if (ep != null && !withinAired(ep.number.toFloatOrNull(), airedLimit)) {
                Logger.log("subscription ${subscribeMedia.id}: episode ${ep.number} not aired yet (AniList: $airedLimit)")
                return null
            }
            ep?.let { saveLatest(subscribeMedia.id, it.number.toFloatOrNull() ?: 0f) }
            // No baseline yet (subscribed before it existed): this check only records
            // the newest episode. Announcing it would repeat an old episode as new.
            return ep?.takeIf { announce(baseline) }
        }

        fun getMangaParser(id: Int): MangaParser {
            val sources = MangaSources
            Logger.log("getMangaParser size: ${sources.list.size}")
            val selected = loadSelected(id)
            resolveSourceIndex(id, sources.names)?.let { selected.sourceIndex = it }
            if (selected.sourceIndex >= sources.list.size) {
                selected.sourceIndex = 0
                saveSelected(id, selected)
            }
            return sources[selected.sourceIndex]
        }

        suspend fun getChapter(
            parser: MangaParser,
            subscribeMedia: SubscribeMedia
        ): MangaChapter? {
            val selected = loadSelected(subscribeMedia.id)
            val savedSource = subscribedSource(subscribeMedia.id)
            if (!checksSource(savedSource, parser.name)) {
                Logger.log("subscription ${subscribeMedia.id}: source $savedSource not installed, check skipped")
                return null
            }
            val baseline = loadLatest(subscribeMedia.id)
            val chp = withSourceLanguage(parser, selected.langIndex) { withTimeoutOrNull(10 * 1000) {
                tryWithSuspend {
                    val show = parser.loadSavedShowResponse(subscribeMedia.id)
                        ?: forceLoadShowResponse(subscribeMedia, selected, parser)
                        ?: throw Exception(
                            currContext()?.getString(
                                R.string.failed_to_load_data,
                                subscribeMedia.id
                            )
                        )
                    show.sManga?.let {
                        parser.getLatestChapter(
                            show.link, show.extra,
                            it, baseline ?: -1f
                        )
                    }
                }
            } }

            chp?.let { saveLatest(subscribeMedia.id, MediaNameAdapter.findChapterNumber(it.number) ?: 0f) }
            return chp?.takeIf { announce(baseline) }
        }

        private suspend fun forceLoadShowResponse(
            subscribeMedia: SubscribeMedia,
            selected: Selected,
            parser: BaseParser
        ): ShowResponse? {
            val tempMedia = Media(
                id = subscribeMedia.id,
                name = null,
                nameRomaji = subscribeMedia.name,
                userPreferredName = subscribeMedia.name,
                isAdult = subscribeMedia.isAdult,
                isFav = false,
                isListPrivate = false,
                userScore = 0,
                userRepeat = 0,
                format = null,
                selected = selected
            )
            parser.autoSearch(tempMedia)
            return parser.loadSavedShowResponse(subscribeMedia.id)
        }

        data class SubscribeMedia(
            val isAnime: Boolean,
            val isAdult: Boolean,
            val id: Int,
            val name: String,
            val image: String?,
            val banner: String? = null
        ) : java.io.Serializable {
            companion object {
                private const val serialVersionUID = 1L
            }
        }

        private const val SUBSCRIPTIONS = "subscriptions"
        private const val SOURCE_NAME = "SubscriptionSource-"
        private const val LATEST = "SubscriptionLatest-"

        @Suppress("UNCHECKED_CAST")
        fun getSubscriptions(): Map<Int, SubscribeMedia> =
            (PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? Map<Int, SubscribeMedia>)
                ?: mapOf<Int, SubscribeMedia>().also { PrefManager.setCustomVal(SUBSCRIPTIONS, it) }

        @Suppress("UNCHECKED_CAST")
        fun deleteSubscription(id: Int, showSnack: Boolean = false) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, SubscribeMedia>
                ?: mutableMapOf()
            data.remove(id)
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
            if (showSnack) toast(R.string.subscription_deleted)
        }

        @Suppress("UNCHECKED_CAST")
        fun saveSubscription(media: Media, subscribed: Boolean) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, SubscribeMedia>
                ?: mutableMapOf()
            if (subscribed) {
                if (!data.containsKey(media.id)) {
                    val new = SubscribeMedia(
                        media.anime != null,
                        media.isAdult,
                        media.id,
                        media.userPreferredName,
                        media.cover,
                        media.banner
                    )
                    data[media.id] = new
                    val current = PrefManager.getNullableCustomVal(
                        "Selected-${media.id}", null, Selected::class.java
                    )
                    if (current == null) {
                        val selected = Selected().apply {
                            sourceIndex = media.selected?.sourceIndex ?: 0
                            preferDub = media.selected?.preferDub
                                ?: PrefManager.getVal(PrefName.SettingsPreferDub)
                            latest = media.selected?.latest ?: 0f
                        }
                        saveSelected(media.id, selected)
                    }
                }
            } else {
                data.remove(media.id)
            }
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
        }
    }
}
