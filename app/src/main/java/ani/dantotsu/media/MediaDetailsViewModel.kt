package ani.dantotsu.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.currContext
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.others.firstNonEmptyProvider
import ani.dantotsu.others.shouldQueryLiveSkip
import ani.dantotsu.others.suspendNullOnError
import ani.dantotsu.media.anime.SelectorDialogFragment
import ani.dantotsu.media.anime.getEpisode
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.media.mangaupdates.MangaAnimeUtil
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.Anify
import ani.dantotsu.others.AniskipCsvFallback
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.IntroDbService
import ani.dantotsu.others.Jikan
import ani.dantotsu.others.Kitsu
import ani.dantotsu.others.TmdbService
import ani.dantotsu.others.sanitizedStamps
import ani.dantotsu.parsers.ListCache
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.HMangaSources
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.parsers.MangaReadSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.OfflineAnimeParser
import ani.dantotsu.parsers.OfflineMangaParser
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.hasExplicitServer
import ani.dantotsu.parsers.WatchSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

class MediaDetailsViewModel : ViewModel() {
    val scrolledToTop = MutableLiveData(true)

    fun saveSelected(id: Int, data: Selected, of: Media? = null) {
        PrefManager.setCustomVal("Selected-$id", data)
        val shown = (of ?: media.value ?: shownMedia)?.takeIf { it.id == id }
        val name = shown?.let {
            val (names, ready) = sourceNamesFor(it)
            SelectedSource.nameFor(data.sourceIndex, names, ready)
        }
        // an unknown name must not outlive the position it was saved for
        if (name != null) PrefManager.setCustomVal("${SelectedSource.KEY}$id", name)
        else PrefManager.removeCustomVal("${SelectedSource.KEY}$id")
    }

    /** The source list a show's saved position points into, and whether its extensions have loaded. */
    private fun sourceNamesFor(m: Media): Pair<List<String>, Boolean> = when {
        m.anime != null ->
            (watchSources ?: if (m.isAdult) HAnimeSources else AnimeSources).names to AnimeSources.isInitialized
        m.format == "MANGA" || m.format == "ONE_SHOT" ->
            (mangaReadSources ?: if (m.isAdult) HMangaSources else MangaSources).names to MangaSources.isInitialized
        else -> NovelSources.names.let { it to it.isNotEmpty() }
    }


    fun loadSelected(media: Media, isDownload: Boolean = false): Selected {
        if ((media.format == "LOCAL" || media.format == "LOCAL_NOVEL") && media.selected != null) {
            return media.selected!!
        }
        val data =
            PrefManager.getNullableCustomVal("Selected-${media.id}", null, Selected::class.java)
                ?: Selected().let {
                    it.sourceIndex = 0
                    it.preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
                    saveSelected(media.id, it)
                    it
                }
        if (!isDownload) {
            val (names, ready) = sourceNamesFor(media)
            val savedName = PrefManager.getNullableCustomVal("${SelectedSource.KEY}${media.id}", null, String::class.java)
            if (savedName != null) {
                if (ready) data.sourceIndex = SelectedSource.resolve(savedName, data.sourceIndex, names)
            } else {
                // a choice saved before names were kept: remember what its position means today
                SelectedSource.nameFor(data.sourceIndex, names, ready)
                    ?.let { PrefManager.setCustomVal("${SelectedSource.KEY}${media.id}", it) }
            }
        }
        if (isDownload) {
            data.sourceIndex = when {
                media.anime != null -> {
                    AnimeSources.list.size - 1
                }

                media.format == "MANGA" || media.format == "ONE_SHOT" -> {
                    MangaSources.list.size - 1
                }

                else -> {
                    NovelSources.list.size - 1
                }
            }
        }
        return data
    }

    var continueMedia: Boolean? = null
    var loading = false

    private val media: MutableLiveData<Media> = MutableLiveData<Media>(null)

    /** The media the details screen shows; reused when the activity is recreated. */
    var shownMedia: Media? = null
    fun getMedia(): LiveData<Media> = media
    fun loadMedia(m: Media) {
        if (!loading) {
            loading = true
            val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (m.id == 0 && m.format?.startsWith("LOCAL") == true) {
                    m.folderName = m.folderName ?: m.name
                    media.postValue(m)

                    val mapKeyStr = m.folderName ?: m.name
                    var mappedId = PrefManager.getCustomVal<Int>("local_mapping_$mapKeyStr", 0)
                    if (mappedId == 0) {
                        try {
                            val searchType = if (m.manga != null) "MANGA" else "ANIME"
                            val searchFormat = if (m.format == "LOCAL_NOVEL") "NOVEL" else null
                            var results = Anilist.query.searchAniManga(searchType, search = m.name, format = searchFormat)
                            if (results == null || results.results.isEmpty()) {
                                if (m.folderName != null && m.folderName != m.name) {
                                    results = Anilist.query.searchAniManga(searchType, search = m.folderName!!, format = searchFormat)
                                }
                            }
                            if (results != null && results.results.isNotEmpty()) {
                                mappedId = results.results[0].id
                                PrefManager.setCustomVal("local_mapping_$mapKeyStr", mappedId)
                            }
                        } catch (e: Exception) {
                            ani.dantotsu.util.Logger.log(e)
                        }
                    }

                    if (mappedId != 0) {
                        val newMedia = m.copy(id = mappedId)
                        val fetchedMedia = Anilist.query.mediaDetails(newMedia)
                        fetchedMedia?.format = m.format 
                        
                        // Cache
                        fetchedMedia?.cover?.let { ani.dantotsu.settings.saving.PrefManager.setCustomVal("local_cover_$mapKeyStr", it) }
                        fetchedMedia?.banner?.let { ani.dantotsu.settings.saving.PrefManager.setCustomVal("local_banner_$mapKeyStr", it) }

                        fetchedMedia?.folderName = m.folderName ?: m.name
                        fetchedMedia?.selected = m.selected
                        media.postValue(fetchedMedia)
                    }
                } else if (m.id == 0) {
                    m.folderName = m.folderName ?: m.name
                    media.postValue(m)
                } else if (rescueMode && m.idMAL != null) {
                    tryWithSuspend {
                        val isAnime = m.anime != null
                        val malId = m.idMAL!!
                        val malNode = if (isAnime)
                            MAL.query.getAnimeDetails(malId)
                        else
                            MAL.query.getMangaDetails(malId)
                        if (malNode != null) {
                            val detailed = Media(malNode, isAnime)
                            detailed.userProgress = m.userProgress ?: detailed.userProgress
                            detailed.userStatus = m.userStatus ?: detailed.userStatus
                            detailed.userScore = if (m.userScore != 0) m.userScore else detailed.userScore
                            detailed.isListPrivate = m.isListPrivate
                            detailed.userListId = m.userListId
                            detailed.userRepeat = m.userRepeat
                            detailed.userUpdatedAt = m.userUpdatedAt ?: detailed.userUpdatedAt
                            detailed.userCompletedAt = m.userCompletedAt
                            detailed.userStartedAt = m.userStartedAt
                            detailed.cameFromContinue = m.cameFromContinue
                            detailed.selected = m.selected
                            detailed.isFav = m.isFav
                            detailed.shareLink = "https://myanimelist.net/${if (isAnime) "anime" else "manga"}/$malId"
                            if (isAnime) {
                                detailed.anime?.episodes = m.anime?.episodes
                            } else {
                                detailed.manga?.chapters = m.manga?.chapters
                            }
                            enrichRescueModeDetails(detailed)
                            media.postValue(detailed)
                            launchBackgroundEnrichment(detailed)
                        } else {
                            val jikanData = if (isAnime)
                                MAL.jikan.getAnimeById(malId)
                            else
                                MAL.jikan.getMangaById(malId)
                            if (jikanData != null) {
                                val detailed = Media(jikanData, isAnime)
                                detailed.userProgress = m.userProgress ?: detailed.userProgress
                                detailed.userStatus = m.userStatus ?: detailed.userStatus
                                detailed.userScore = if (m.userScore != 0) m.userScore else detailed.userScore
                                detailed.isListPrivate = m.isListPrivate
                                detailed.userListId = m.userListId
                                detailed.userRepeat = m.userRepeat
                                detailed.userUpdatedAt = m.userUpdatedAt
                                detailed.userCompletedAt = m.userCompletedAt
                                detailed.userStartedAt = m.userStartedAt
                                detailed.cameFromContinue = m.cameFromContinue
                                detailed.selected = m.selected
                                detailed.isFav = m.isFav
                                detailed.shareLink = "https://myanimelist.net/${if (isAnime) "anime" else "manga"}/$malId"
                                if (isAnime) {
                                    detailed.anime?.episodes = m.anime?.episodes
                                } else {
                                    detailed.manga?.chapters = m.manga?.chapters
                                }
                                enrichRescueModeDetails(detailed)
                                media.postValue(detailed)
                                launchBackgroundEnrichment(detailed)
                            } else {
                                media.postValue(m)
                            }
                        }
                    }
                } else if (rescueMode) {
                    media.postValue(m)
                } else {
                    media.postValue(Anilist.query.mediaDetails(m))
                }
                loading = false
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (m.idIMDB == null) {
                    m.idIMDB = if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                        m.idMAL?.let { ani.dantotsu.others.IdMappers.getImdbIdFromMal(it) }
                    } else {
                        ani.dantotsu.others.IdMappers.getImdbId(m.id)
                    }
                }
            } catch (e: Exception) {
                ani.dantotsu.util.Logger.log(e)
            }
        }
    }

    private suspend fun enrichRescueModeDetails(media: Media) {
        val malId = media.idMAL ?: return
        supervisorScope {
            val isAnime = media.anime != null
            val fullDeferred = async {
                if (isAnime) MAL.jikan.getAnimeById(malId) else MAL.jikan.getMangaById(malId)
            }
            val charactersDeferred = async {
                if (isAnime) MAL.jikan.getAnimeCharacters(malId) else MAL.jikan.getMangaCharacters(malId)
            }
            val staffDeferred = async {
                if (isAnime) MAL.jikan.getAnimeStaff(malId) else emptyList()
            }
            val reviewsDeferred = async {
                if (isAnime) MAL.jikan.getAnimeReviews(malId) else MAL.jikan.getMangaReviews(malId)
            }
            val recommendationsDeferred = async {
                MAL.jikan.getRecommendations(isAnime, malId)
            }

            val fullData = fullDeferred.await()
            if (fullData != null) {
                val fullMapped = Media(fullData, isAnime)
                if (media.description.isNullOrBlank() && !fullMapped.description.isNullOrBlank()) {
                    media.description = fullMapped.description
                }
                if (media.synonyms.isEmpty() && fullMapped.synonyms.isNotEmpty()) media.synonyms = fullMapped.synonyms
                if (media.genres.isEmpty() && fullMapped.genres.isNotEmpty()) media.genres = fullMapped.genres
                if (media.externalLinks.isNullOrEmpty() && !fullMapped.externalLinks.isNullOrEmpty()) media.externalLinks = fullMapped.externalLinks
                if ((media.meanScore == null || media.meanScore == 0) && fullMapped.meanScore != null) {
                    media.meanScore = fullMapped.meanScore
                }
                if (media.source.isNullOrBlank() && !fullMapped.source.isNullOrBlank()) {
                    media.source = fullMapped.source
                }
                if (!fullMapped.relations.isNullOrEmpty()) {
                    if (media.relations.isNullOrEmpty() || (fullMapped.relations?.size ?: 0) > (media.relations?.size ?: 0)) {
                        media.relations = fullMapped.relations
                    }
                    if (media.prequel == null) media.prequel = fullMapped.prequel
                    if (media.sequel == null) media.sequel = fullMapped.sequel
                }
                if (!fullMapped.staff.isNullOrEmpty()) {
                    media.staff = ArrayList(
                        ((media.staff ?: arrayListOf()) + fullMapped.staff!!).distinctBy { it.id }
                    )
                }
                if (!fullMapped.recommendations.isNullOrEmpty() &&
                    (fullMapped.recommendations?.size ?: 0) > (media.recommendations?.size ?: 0)) {
                    media.recommendations = fullMapped.recommendations
                }
                if (media.trailer.isNullOrBlank() && !fullMapped.trailer.isNullOrBlank()) media.trailer = fullMapped.trailer
                if (isAnime) {
                    fullMapped.anime?.let { anime ->
                        if (anime.op.isNotEmpty()) media.anime?.op = anime.op
                        if (anime.ed.isNotEmpty()) media.anime?.ed = anime.ed
                        if (media.anime?.mainStudio == null) {
                            anime.mainStudio?.let { media.anime?.mainStudio = it }
                        }
                        if (media.anime?.producers.isNullOrEmpty() && !anime.producers.isNullOrEmpty()) {
                            media.anime?.producers = anime.producers
                        }
                        if (media.anime?.season.isNullOrBlank()) anime.season?.let { media.anime?.season = it }
                        if (media.anime?.seasonYear == null) anime.seasonYear?.let { media.anime?.seasonYear = it }
                        if (media.anime?.nextAiringEpisodeTime == null && anime.nextAiringEpisodeTime != null) {
                            media.anime?.nextAiringEpisodeTime = anime.nextAiringEpisodeTime
                        }
                        val estimated = anime.nextAiringEpisode ?: 0
                        val watched = media.userProgress ?: 0
                        val nextAiring = if (watched > 0) {
                            if (watched >= (estimated + 1)) watched else estimated
                        } else {
                            estimated
                        }
                        media.anime?.nextAiringEpisode = nextAiring
                    }
                } else {
                    fullMapped.manga?.author?.let { media.manga?.author = it }
                }
            }

            val jRecommendations = try { recommendationsDeferred.await() } catch (_: Exception) { null }
            val mappedRecommendations = jRecommendations
                ?.mapNotNull { it.entry }
                ?.map {
                    Media(
                        id = it.malId,
                        idMAL = it.malId,
                        name = it.title,
                        nameRomaji = it.title ?: "",
                        userPreferredName = it.title ?: "",
                        cover = it.images?.jpg?.largeImageUrl ?: it.images?.jpg?.imageUrl,
                        banner = it.images?.jpg?.largeImageUrl,
                        isAdult = false,
                        status = null,
                        meanScore = null,
                        popularity = null,
                        format = null,
                    )
                }
                ?.distinctBy { it.id }
                ?.let { ArrayList(it) }
            
            if (!mappedRecommendations.isNullOrEmpty()) {
                if (media.recommendations.isNullOrEmpty() || mappedRecommendations.size > (media.recommendations?.size ?: 0)) {
                    media.recommendations = mappedRecommendations
                }
            }

            val mappedCharacters = charactersDeferred.await()
                .mapNotNull { jChar ->
                    val character = jChar.character ?: return@mapNotNull null
                    Character(
                        id = character.malId,
                        name = character.name,
                        image = character.images?.jpg?.largeImageUrl ?: character.images?.jpg?.imageUrl,
                        banner = media.banner ?: media.cover,
                        role = jChar.role ?: "",
                        isFav = false,
                        voiceActor = jChar.voiceActors
                            ?.mapNotNull { va ->
                                va.person?.let { person ->
                                    Author(
                                        id = person.malId,
                                        name = person.name,
                                        image = person.images?.jpg?.largeImageUrl ?: person.images?.jpg?.imageUrl,
                                        role = va.language
                                    )
                                }
                            }
                            ?.let { ArrayList(it) }
                    )
                }
            if (mappedCharacters.isNotEmpty()) {
                media.characters = ArrayList(mappedCharacters.distinctBy { it.id })
            }

            val mappedStaff = staffDeferred.await()
                .mapNotNull { staff ->
                    val person = staff.person ?: return@mapNotNull null
                    Author(
                        id = person.malId,
                        name = person.name,
                        image = person.images?.jpg?.largeImageUrl ?: person.images?.jpg?.imageUrl,
                        role = staff.positions?.joinToString(", ")
                    )
                }

            val mangaAuthors = if (!isAnime && fullData != null) {
                fullData.authors?.mapNotNull { author ->
                    val person = author.person ?: return@mapNotNull null
                    Author(
                        id = person.malId,
                        name = person.name,
                        image = person.images?.jpg?.largeImageUrl ?: person.images?.jpg?.imageUrl,
                        role = author.position
                    )
                } ?: emptyList()
            } else emptyList()

            val allStaff = (mappedStaff + mangaAuthors).distinctBy { it.id }
            if (allStaff.isNotEmpty()) {
                media.staff = ArrayList(
                    ((media.staff ?: arrayListOf()) + allStaff).distinctBy { it.id }
                )
            }

            mapJikanReviews(media, reviewsDeferred.await(), isAnime, malId)
        }
    }

    private fun launchBackgroundEnrichment(media: Media) {
        val malId = media.idMAL ?: return
        val isAnime = media.anime != null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fetchRelationCovers(media, isAnime)

                enrichRecommendationDetails(media, isAnime)

                this@MediaDetailsViewModel.media.postValue(media)
            } catch (_: Exception) {}
        }
    }

    private suspend fun enrichRecommendationDetails(media: Media, isAnime: Boolean) {
        val recs = media.recommendations?.take(15) ?: return
        val recsToEnrich = recs.filter { rec ->
            rec.meanScore == null || rec.meanScore == 0 ||
            (rec.anime != null && rec.anime?.totalEpisodes == null) ||
            (rec.manga != null && rec.manga?.totalChapters == null)
        }
        if (recsToEnrich.isEmpty()) return
        kotlinx.coroutines.supervisorScope {
            val deferreds = recsToEnrich.map { rec ->
                async {
                    try {
                        val recMalId = rec.idMAL ?: return@async
                        val isRecAnime = rec.anime != null
                        
                        val coverUrl: String?
                        val score: Int?
                        val statusStr: String?
                        val episodesCount: Int?
                        val chaptersCount: Int?

                        val node = if (isRecAnime) MAL.query.getAnimeDetails(recMalId) else MAL.query.getMangaDetails(recMalId)
                        if (node != null) {
                            coverUrl = node.mainPicture?.large ?: node.mainPicture?.medium
                            score = ((node.mean ?: 0f) * 10f).toInt()
                            statusStr = node.status?.replace("_", " ")?.uppercase(java.util.Locale.US)
                            episodesCount = node.numEpisodes
                            chaptersCount = node.numChapters
                        } else {
                            val jikanNode = if (isRecAnime) MAL.jikan.getAnimeById(recMalId) else MAL.jikan.getMangaById(recMalId)
                            if (jikanNode != null) {
                                coverUrl = jikanNode.images?.jpg?.largeImageUrl ?: jikanNode.images?.jpg?.imageUrl
                                score = ((jikanNode.score ?: 0f) * 10f).toInt()
                                statusStr = jikanNode.status?.replace("_", " ")?.uppercase(java.util.Locale.US)
                                episodesCount = jikanNode.episodes
                                chaptersCount = jikanNode.chapters
                            } else {
                                coverUrl = null
                                score = null
                                statusStr = null
                                episodesCount = null
                                chaptersCount = null
                            }
                        }

                        if (coverUrl != null || score != null || statusStr != null) {
                            if (coverUrl != null) {
                                rec.cover = rec.cover ?: coverUrl
                            }
                            if (score != null) {
                                rec.meanScore = score
                            }
                            if (statusStr != null) {
                                rec.status = statusStr
                            }
                            if (isRecAnime) {
                                if (episodesCount != null) {
                                    rec.anime?.totalEpisodes = episodesCount
                                }
                            } else {
                                if (chaptersCount != null) {
                                    rec.manga?.totalChapters = chaptersCount
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            deferreds.forEach { it.await() }
        }
        media.recommendations = ArrayList(recs)
    }


    private fun mapJikanReviews(
        media: Media,
        jikanReviews: List<ani.dantotsu.connections.mal.JikanReview>,
        isAnime: Boolean,
        malId: Int
    ) {
        if (jikanReviews.isEmpty()) return
        val mapped = jikanReviews.mapNotNull { review ->
            val reviewText = review.review ?: return@mapNotNull null
            val summary = if (reviewText.length > 200) reviewText.take(200) + "…" else reviewText
            val userName = review.user?.username ?: "Anonymous"
            val userAvatar = review.user?.images?.jpg?.imageUrl
            val createdAt = try {
                java.time.Instant.parse(review.date ?: "").epochSecond.toInt()
            } catch (_: Exception) { 0 }
            ani.dantotsu.connections.anilist.api.Query.Review(
                id = review.malId,
                mediaId = malId,
                mediaType = if (isAnime) "ANIME" else "MANGA",
                summary = summary,
                body = reviewText,
                rating = review.reactions?.overall ?: 0,
                ratingAmount = review.reactions?.overall ?: 0,
                userRating = "NO_VOTE",
                score = (review.score ?: 0) * 10,
                private = false,
                siteUrl = review.url ?: "https://myanimelist.net",
                createdAt = createdAt,
                updatedAt = null,
                user = ani.dantotsu.connections.anilist.api.User(
                    id = review.malId,
                    name = userName,
                    avatar = ani.dantotsu.connections.anilist.api.UserAvatar(
                        large = userAvatar,
                        medium = userAvatar
                    ),
                    bannerImage = null,
                    isFollowing = null,
                    isFollower = null,
                    options = null,
                    mediaListOptions = null,
                    favourites = null,
                    statistics = null,
                    unreadNotificationCount = null,
                )
            )
        }
        if (mapped.isNotEmpty()) {
            media.review = ArrayList(mapped.take(5))
        }
    }

    private suspend fun fetchRelationCovers(media: Media, isAnime: Boolean) {
        val relationsToFetch = mutableListOf<Media>()
        media.prequel?.let { if (it.cover == null) relationsToFetch.add(it) }
        media.sequel?.let { if (it.cover == null) relationsToFetch.add(it) }
        media.relations?.forEach { rel ->
            if (rel.cover == null && !relationsToFetch.contains(rel)) relationsToFetch.add(rel)
        }
        if (relationsToFetch.isEmpty()) return

        kotlinx.coroutines.supervisorScope {
            val deferreds = relationsToFetch.take(8).map { rel ->
                async {
                    val relMalId = rel.idMAL ?: return@async
                    val relIsAnime = rel.anime != null || rel.relation?.contains("ANIME", true) == true
                            || (rel.manga == null)
                    try {
                        val coverUrl: String?
                        val score: Int?
                        val statusStr: String?
                        val episodesCount: Int?
                        val chaptersCount: Int?
                        var resolvedFmt: String? = null

                        val node = if (relIsAnime) MAL.query.getAnimeDetails(relMalId) else MAL.query.getMangaDetails(relMalId)
                        if (node != null) {
                            coverUrl = node.mainPicture?.large ?: node.mainPicture?.medium
                            score = ((node.mean ?: 0f) * 10f).toInt()
                            statusStr = node.status?.replace("_", " ")?.uppercase(java.util.Locale.US)
                            episodesCount = node.numEpisodes
                            chaptersCount = node.numChapters
                            resolvedFmt = node.mediaType?.uppercase(java.util.Locale.US)
                        } else {
                            val jikanNode = if (relIsAnime) MAL.jikan.getAnimeById(relMalId) else MAL.jikan.getMangaById(relMalId)
                            if (jikanNode != null) {
                                coverUrl = jikanNode.images?.jpg?.largeImageUrl ?: jikanNode.images?.jpg?.imageUrl
                                score = ((jikanNode.score ?: 0f) * 10f).toInt()
                                statusStr = jikanNode.status?.replace("_", " ")?.uppercase(java.util.Locale.US)
                                episodesCount = jikanNode.episodes
                                chaptersCount = jikanNode.chapters
                                val typeStr = jikanNode.type?.uppercase(java.util.Locale.US)
                                resolvedFmt = when (typeStr) {
                                    "LIGHT NOVEL", "NOVEL" -> "NOVEL"
                                    "ONE-SHOT" -> "ONE_SHOT"
                                    "DOUJINSHI" -> "DOUJINSHI"
                                    "MANHWA" -> "MANHWA"
                                    "MANHUA" -> "MANHUA"
                                    else -> typeStr ?: if (relIsAnime) "TV" else "MANGA"
                                }
                            } else {
                                coverUrl = null
                                score = null
                                statusStr = null
                                episodesCount = null
                                chaptersCount = null
                            }
                        }

                        if (coverUrl != null || score != null || statusStr != null) {
                            if (coverUrl != null) {
                                rel.cover = coverUrl
                                rel.banner = coverUrl
                            }
                            if (score != null && rel.meanScore == null) {
                                rel.meanScore = score
                            }
                            if (statusStr != null && rel.status == null) {
                                rel.status = statusStr
                            }
                            if (relIsAnime) {
                                if (episodesCount != null) {
                                    rel.anime?.totalEpisodes = episodesCount
                                }
                            } else {
                                if (chaptersCount != null) {
                                    rel.manga?.totalChapters = chaptersCount
                                }
                            }
                            if (resolvedFmt != null) {
                                rel.format = resolvedFmt
                                val rawRelation = rel.relation?.substringBefore("\n") ?: ""
                                if (rawRelation.isNotEmpty()) {
                                    rel.relation = "$rawRelation\n$resolvedFmt"
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            deferreds.forEach { it.await() }
        }
    }

    fun setMedia(m: Media) {
        media.postValue(m)
        // M7A G2: synthetic entries skip the catalog ID lookup — a bogus
        // network call with a reserved id, with nothing to resolve.
        if (m.isUnlinkedPlayback()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (m.idIMDB == null) {
                    m.idIMDB = if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                        m.idMAL?.let { ani.dantotsu.others.IdMappers.getImdbIdFromMal(it) }
                    } else {
                        ani.dantotsu.others.IdMappers.getImdbId(m.id)
                    }
                }
            } catch (e: Exception) {
                ani.dantotsu.util.Logger.log(e)
            }
        }
    }

    val responses = MutableLiveData<List<ShowResponse>?>(null)


    //Anime
    private val kitsuEpisodes: MutableLiveData<Map<String, Episode>> =
        MutableLiveData<Map<String, Episode>>(null)

    fun getKitsuEpisodes(): LiveData<Map<String, Episode>> = kitsuEpisodes
    suspend fun loadKitsuEpisodes(s: Media) {
        tryWithSuspend {
            if (kitsuEpisodes.value == null) kitsuEpisodes.postValue(Kitsu.getKitsuEpisodesDetails(s))
        }
    }

    private val anifyEpisodes: MutableLiveData<Map<String, Episode>> =
        MutableLiveData<Map<String, Episode>>(null)

    fun getAnifyEpisodes(): LiveData<Map<String, Episode>> = anifyEpisodes
    suspend fun loadAnifyEpisodes(s: Media) {
        tryWithSuspend {
            if (anifyEpisodes.value == null) {
                val anilistId = s.id.takeIf { it != 0 }
                val malId = s.idMAL
                var episodes = Anify.fetchAndParseMetadata(anilistId = anilistId, malId = malId)
                // MAX MERGE M5 correction 3 (upstream 36d57c56 TMDB
                // fallback, fixed): resolve every relevant local episode
                // through EpisodeMapper, fetch each required TMDB season
                // once, and translate rows back to local keys AND local
                // Episode.number. Local domain: authoritative total when
                // known, else already-known local episode keys; with
                // neither, omit rather than inject unverified S1 data.
                if (episodes.isEmpty()) {
                    val tmdbId = s.idTMDB?.toIntOrNull()
                        ?: suspendNullOnError { IdMappers.getIds(s.id)?.tmdbId }
                    if (tmdbId != null && tmdbId > 0) {
                        val total = s.anime?.totalEpisodes ?: 0
                        val localKeys = if (total > 0) {
                            (1..total).map { it.toString() }
                        } else {
                            s.anime?.episodes?.keys?.toList().orEmpty()
                        }
                        val tmdbEpisodes = if (localKeys.isNotEmpty()) {
                            TmdbService.resolveTmdbEpisodes(
                                localKeys,
                                mapEpisode = { key ->
                                    EpisodeMapper.mapEpisode(s, key.toIntOrNull() ?: 1)
                                },
                                fetchSeason = { season ->
                                    TmdbService.getEpisodeDetails(tmdbId, season)
                                }
                            )
                        } else {
                            emptyMap()
                        }
                        if (tmdbEpisodes.isNotEmpty()) {
                            episodes = tmdbEpisodes
                        }
                    } else {
                        // no id mapping yet (a new season, usually): find the
                        // season by title and the show's own start date
                        val start = s.startDate?.let { d ->
                            val (y, m, day) = Triple(d.year, d.month, d.day)
                            if (y != null && m != null && day != null) "%04d-%02d-%02d".format(y, m, day) else null
                        }
                        if (start != null) {
                            TmdbService.findEpisodesByAirDate(listOf(s.name, s.nameRomaji), start)
                                ?.let { episodes = it }
                        }
                    }
                }
                anifyEpisodes.postValue(episodes)
            }
        }
    }
    suspend fun loadAnifyEpisodes(s: Int) {
        tryWithSuspend {
            if (anifyEpisodes.value == null) anifyEpisodes.postValue(Anify.fetchAndParseMetadata(anilistId = s, malId = null))
        }
    }

    private val fillerEpisodes: MutableLiveData<Map<String, Episode>> =
        MutableLiveData<Map<String, Episode>>(null)

    fun getFillerEpisodes(): LiveData<Map<String, Episode>> = fillerEpisodes
    suspend fun loadFillerEpisodes(s: Media) {
        tryWithSuspend {
            if (fillerEpisodes.value == null) fillerEpisodes.postValue(
                Jikan.getEpisodes(
                    s.idMAL ?: return@tryWithSuspend
                )
            )
        }
    }

    var watchSources: WatchSources? = null

    private val episodes = MutableLiveData<MutableMap<Int, MutableMap<String, Episode>>>(null)
    private val epsLoaded = mutableMapOf<Int, MutableMap<String, Episode>>()
    fun getEpisodes(): LiveData<MutableMap<Int, MutableMap<String, Episode>>> = episodes
    fun invalidateSource(sourceIndex: Int) {
        epsLoaded.remove(sourceIndex)
    }

    /** "N new episodes/chapters" after a cached list was shown and the fresh one had more. */
    private fun announceNew(count: Int, plural: Int) {
        if (count <= 0) return
        val context = ani.dantotsu.currContext() ?: return
        ani.dantotsu.snackString(context.resources.getQuantityString(plural, count, count))
    }

    suspend fun loadEpisodes(media: Media, i: Int, invalidate: Boolean = false) {
        val isOffline = watchSources?.get(i) is OfflineAnimeParser
        if (!epsLoaded.containsKey(i) || invalidate || isOffline) {
            var shown: Map<String, Episode>? = null
            val onCached: ((MutableMap<String, Episode>) -> Unit)? =
                if (invalidate || isOffline) null else { cached ->
                    shown = cached
                    epsLoaded[i] = cached
                    episodes.postValue(epsLoaded)
                }
            val fresh = watchSources?.loadEpisodesFromMedia(i, media, onCached) ?: mutableMapOf()
            epsLoaded[i] = fresh
            announceNew(ListCache.newKeys(shown, fresh).size, R.plurals.new_episodes_found)
        }
        episodes.postValue(epsLoaded)
    }

    /**
     * Posts the already-loaded episode list again so the episode observer merges
     * metadata that arrived later. Never fetches from the extension: metadata can
     * arrive before the extension answers, and a fetch here would duplicate it.
     */
    fun remergeEpisodeMetadata(i: Int) {
        if (epsLoaded.containsKey(i)) episodes.postValue(epsLoaded)
    }

    suspend fun forceLoadEpisode(media: Media, i: Int) {
        epsLoaded[i] = watchSources?.loadEpisodesFromMedia(i, media) ?: mutableMapOf()
        episodes.postValue(epsLoaded)
    }

    suspend fun overrideEpisodes(i: Int, source: ShowResponse, id: Int) {
        if (source.sAnime == null) {
            source.sAnime = SAnime.create().apply {
                url = source.link
                title = source.name
                thumbnail_url = source.coverUrl.url
            }
        }
        watchSources?.saveResponse(i, id, source)
        epsLoaded[i] =
            watchSources?.loadEpisodes(i, source.link, source.extra, source.sAnime) ?: return
        episodes.postValue(epsLoaded)
    }

    private var episode = MutableLiveData<Episode?>(null)
    fun getEpisode(): LiveData<Episode?> = episode

    suspend fun loadEpisodeVideos(ep: Episode, i: Int, post: Boolean = true) {
        val link = ep.link ?: return
        if (!ep.allStreams || ep.extractors.isNullOrEmpty()) {
            val existingExtractors = ep.extractors?.toMutableList() ?: mutableListOf()
            val list = mutableListOf<VideoExtractor>()
            ep.extractors = list
            watchSources?.get(i)?.apply {
                if (!post && !allowsPreloading) return@apply
                ep.sEpisode?.let {
                    loadByVideoServers(link, ep.extra, it) { extractor ->
                        if (extractor.videos.isNotEmpty()) {
                            list.add(extractor)
                            ep.extractorCallback?.invoke(extractor)
                        }
                    }
                }
                ep.extractorCallback = null
                if (list.isNotEmpty())
                    ep.allStreams = true
                else if (existingExtractors.isNotEmpty())
                    ep.extractors = existingExtractors
            }
        }


        if (post) {
            episode.postValue(ep)
            MainScope().launch(Dispatchers.Main) {
                episode.value = null
            }
        }
    }

    val timeStamps = MutableLiveData<List<AniSkip.Stamp>?>()
    private val timeStampsCache = ani.dantotsu.others.AniskipCache<AniSkip.Stamp>()
    // Episode the player last asked about; a slower answer for an earlier episode is dropped.
    @Volatile private var timeStampsEpisode: Int? = null
    suspend fun loadTimeStamps(
        malId: Int?,
        episodeNum: Int?,
        duration: Long,
        useProxyForTimeStamps: Boolean,
        extensionTimestamps: List<eu.kanade.tachiyomi.animesource.model.TimeStamp> = emptyList(),
        episode: Episode? = null
    ) {
        episodeNum ?: return
        timeStampsEpisode = episodeNum
        fun post(value: List<AniSkip.Stamp>?) {
            if (timeStampsEpisode == episodeNum) timeStamps.postValue(value)
        }
        val cached = timeStampsCache.lookup(episodeNum)
        if (cached is ani.dantotsu.others.AniskipCache.Lookup.Hit) {
            return post(cached.stamps)
        }

        // Extension timestamps take priority.
        if (extensionTimestamps.isNotEmpty()) {
            val result = extensionTimestamps.map { it.toAniSkipStamp() }
            timeStampsCache.store(episodeNum, result, durationKnown = duration > 0)
            return post(result)
        }

        // Provider fallback chain (upstream 36d57c56): live AniSkip API →
        // offline CSV dump DB → IntroDB APIs. Live AniSkip needs a known
        // duration; CSV/IntroDB bounded ranges may run without one, while
        // open-ended credits still require duration (handled inside
        // IntroDbService). Cancellation aborts the chain via rethrow.
        val durationSec = duration.takeIf { it > 0 }
        val durationMs = durationSec?.times(1000)
        val result = firstNonEmptyProvider(
            {
                // Non-null asserted: shouldQueryLiveSkip is the single
                // definition of this gate (unit-proved).
                if (shouldQueryLiveSkip(malId, durationSec)) {
                    AniSkip.getResult(malId!!, episodeNum, durationSec!!, useProxyForTimeStamps)
                } else {
                    null
                }
            },
            {
                if (malId != null) {
                    val ctx = currContext()
                    if (ctx != null) {
                        AniskipCsvFallback.getInstance(ctx).lookup(malId, episodeNum, durationSec)
                    } else {
                        null
                    }
                } else {
                    null
                }
            },
            {
                val mediaVal = media.value
                val imdbId = mediaVal?.idIMDB
                    ?: mediaVal?.id?.let { suspendNullOnError { IdMappers.getImdbId(it) } }
                // One mapper lookup serves both ids (TVDB is TheIntroDB v4's key).
                val mappedIds = mediaVal?.id?.let { suspendNullOnError { IdMappers.getIds(it) } }
                val tmdbId = mediaVal?.idTMDB?.toIntOrNull() ?: mappedIds?.tmdbId
                val tvdbId = mappedIds?.tvdbId
                // External season/episode via the existing mapper seam;
                // S1 + local number is the documented fallback when the
                // mapper cannot resolve (previous behavior).
                val mapped = if (mediaVal != null) {
                    suspendNullOnError {
                        EpisodeMapper.mapEpisode(mediaVal, episodeNum, episode)
                    }
                } else {
                    null
                }
                val season = (mapped?.season ?: 1).toString()
                val ep = (mapped?.episode ?: episodeNum).toString()
                IntroDbService.getSkipTimes(
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    tvdbId = tvdbId,
                    season = season,
                    episode = ep,
                    durationMs = durationMs
                )
            }
        )?.sanitizedStamps()
        timeStampsCache.store(episodeNum, result, durationKnown = duration > 0)
        post(result)
    }

    private fun eu.kanade.tachiyomi.animesource.model.TimeStamp.toAniSkipStamp(): AniSkip.Stamp {
        val skipType = when (type) {
            eu.kanade.tachiyomi.animesource.model.ChapterType.Opening -> "op"
            eu.kanade.tachiyomi.animesource.model.ChapterType.Ending -> "ed"
            eu.kanade.tachiyomi.animesource.model.ChapterType.Recap -> "recap"
            eu.kanade.tachiyomi.animesource.model.ChapterType.MixedOp -> "mixed-op"
            eu.kanade.tachiyomi.animesource.model.ChapterType.Other ->
                name.lowercase().replace(" ", "-").ifEmpty { "other" }
        }
        return AniSkip.Stamp(
            interval = AniSkip.AniSkipInterval(start, end),
            skipType = skipType,
            skipId = name,
            // episodeLength represents total episode duration; use 0.0 as a sentinel since
            // extension timestamps don't carry the full episode length
            episodeLength = 0.0
        )
    }

    suspend fun loadEpisodeSingleVideo(
        ep: Episode,
        selected: Selected,
        post: Boolean = true,
        selectedServerName: String? = null
    ): Boolean {

        val server = selectedServerName ?: selected.server ?: return false
        val link = ep.link ?: return false

        // FINAL-PORT C4 correction (HOLD1-A): only the explicit named
        // server satisfies this gate. A preferred-but-unselected entry
        // must not skip the named load below.
        if (!hasExplicitServer(ep.extractors, server)) {
            Log.d("AnimeDownloader", "Loading Video Server for episode: ${ep.number}, selected server: $server")
            if(ep.extractors == null){
                ep.extractors = mutableListOf(watchSources?.get(selected.sourceIndex)?.let {
                    selected.sourceIndex = selected.sourceIndex
                    if (!post && !it.allowsPreloading) null
                    else ep.sEpisode?.let { it1 ->
                        it.loadSingleVideoServer(
                            server, link, ep.extra,
                            it1, post
                        )
                    }
                } ?: return false)
            }
            else{
                ep.extractors!!.add(watchSources?.get(selected.sourceIndex)?.let {
                    selected.sourceIndex = selected.sourceIndex
                    if (!post && !it.allowsPreloading) null
                    else ep.sEpisode?.let { it1 ->
                        it.loadSingleVideoServer(
                            server, link, ep.extra,
                            it1, post
                        )
                    }
                } ?: return false)
            }
            //ep.extractors?.forEach { Log.d("AnimeDownloader", "Extractor episode ${ep.number}: ${it.server.name}") }
            ep.allStreams = false
        }
        if (post) {
            episode.postValue(ep)
            MainScope().launch(Dispatchers.Main) {
                episode.value = null
            }
        }
        return true
    }

    fun setEpisode(ep: Episode?, who: String) {
        Logger.log("set episode ${ep?.number} - $who")
        episode.postValue(ep)
        MainScope().launch(Dispatchers.Main) {
            episode.value = null
        }
    }

    val epChanged = MutableLiveData(true)
    fun onEpisodeClick(
        media: Media,
        i: String = "",
        manager: FragmentManager,
        launch: Boolean = true,
        prevEp: String? = null,
        isDownload: Boolean = false,
        episodes: ArrayList<String> = arrayListOf(), // used for handling an array of episodes to download or to view a single episode
        forcePicker: Boolean = false, // show the list even when a release is remembered
    ) {
        Handler(Looper.getMainLooper()).post {
            if (manager.findFragmentByTag("dialog") == null && !manager.isDestroyed) {
                if(episodes.isEmpty()){
                    episodes.add(i)
                }
                for (ep in episodes){
                    if (media.anime?.episodes?.getEpisode(ep) == null) {
                        snackString(currContext()?.getString(R.string.episode_not_found, ep))
                        return@post
                    }
                }
                media.selected = this.loadSelected(media)
                val selector =
                    SelectorDialogFragment.newInstance(
                        media.selected!!.server,
                        launch,
                        prevEp,
                        isDownload,
                        episodes,
                        forcePicker,
                    )
                selector.show(manager, "dialog")
            }
        }
    }

    //Manga
    var mangaReadSources: MangaReadSources? = null

    private val mangaChapters =
        MutableLiveData<MutableMap<Int, MutableMap<String, MangaChapter>>>(null)
    private val mangaLoaded = mutableMapOf<Int, MutableMap<String, MangaChapter>>()
    fun getMangaChapters(): LiveData<MutableMap<Int, MutableMap<String, MangaChapter>>> =
        mangaChapters

    fun invalidateMangaSource(sourceIndex: Int) {
        mangaLoaded.remove(sourceIndex)
    }

    suspend fun loadMangaChapters(media: Media, i: Int, invalidate: Boolean = false) {
        Logger.log("Loading Manga Chapters : $mangaLoaded")
        val isOffline = mangaReadSources?.get(i) is OfflineMangaParser
        if (!mangaLoaded.containsKey(i) || invalidate || isOffline) tryWithSuspend {
            var shown: Map<String, MangaChapter>? = null
            val onCached: ((MutableMap<String, MangaChapter>) -> Unit)? =
                if (invalidate || isOffline) null else { cached ->
                    shown = cached
                    mangaLoaded[i] = cached
                    mangaChapters.postValue(mangaLoaded)
                }
            val fresh = mangaReadSources?.loadChaptersFromMedia(i, media, onCached) ?: mutableMapOf()
            mangaLoaded[i] = fresh
            announceNew(ListCache.newKeys(shown, fresh).size, R.plurals.new_chapters_found)
        }
        mangaChapters.postValue(mangaLoaded)
    }

    suspend fun overrideMangaChapters(i: Int, source: ShowResponse, id: Int) {
        if (source.sManga == null) {
            source.sManga = SManga.create().apply {
                url = source.link
                title = source.name
                thumbnail_url = source.coverUrl.url
            }
        }
        mangaReadSources?.saveResponse(i, id, source)
        tryWithSuspend {
            mangaLoaded[i] = mangaReadSources?.loadChapters(i, source) ?: return@tryWithSuspend
        }
        mangaChapters.postValue(mangaLoaded)
    }

    private val mangaChapter = MutableLiveData<MangaChapter?>(null)
    fun getMangaChapter(): LiveData<MangaChapter?> = mangaChapter
    suspend fun loadMangaChapterImages(
        chapter: MangaChapter,
        selected: Selected,
        post: Boolean = true
    ): Boolean {

        return tryWithSuspend(true) {
            chapter.addImages(
                mangaReadSources?.get(selected.sourceIndex)
                    ?.loadImages(chapter.link, chapter.sChapter) ?: return@tryWithSuspend false
            )
            if (post) mangaChapter.postValue(chapter)
            true
        } ?: false
    }

    fun loadTransformation(mangaImage: MangaImage, source: Int): BitmapTransformation? {
        return if (mangaImage.useTransformation) mangaReadSources?.get(source)
            ?.getTransformation() else null
    }

    val novelSources = NovelSources
    val novelResponses = MutableLiveData<List<ShowResponse>>(null)

    private val novelChapters = MutableLiveData<MutableMap<Int, List<ShowResponse>>>(null)
    private val novelLoaded = mutableMapOf<Int, List<ShowResponse>>()
    fun getNovelChapters(): LiveData<MutableMap<Int, List<ShowResponse>>> = novelChapters

    suspend fun searchNovels(query: String, i: Int) {
        val position = if (i >= novelSources.list.size) 0 else i
        val source = novelSources[position]
        tryWithSuspend(post = true) {
            if (source != null) {
                novelResponses.postValue(source.search(query))
            }
        }
    }

    suspend fun autoSearchNovels(media: Media) {
        val source = novelSources[media.selected?.sourceIndex ?: 0]
        tryWithSuspend(post = true) {
            if (source != null) {
                novelResponses.postValue(source.sortedSearch(media))
            }
        }
    }

    suspend fun loadNovelChapters(media: Media, i: Int, invalidate: Boolean = false) {
        if (!novelLoaded.containsKey(i) || invalidate) {
            tryWithSuspend {
                val source = novelSources[i]
                if (source == null) {
                    novelLoaded[i] = emptyList()
                    return@tryWithSuspend
                }
                val novelResponse = source.autoSearch(media)
                if (novelResponse == null) {
                    novelLoaded[i] = emptyList()
                    return@tryWithSuspend
                }
                val book = source.loadBook(novelResponse.link, novelResponse.extra)
                if (book == null || book.links.isEmpty()) {
                    novelLoaded[i] = emptyList()
                    return@tryWithSuspend
                }
                val chapterResponses = book.links.mapIndexed { index, fileUrl ->
                    val chapterName = fileUrl.headers?.get("X-Chapter-Name") ?: "Chapter ${index + 1}"
                    val releaseTime = fileUrl.headers?.get("X-Release-Time")
                    val chapterNumber = fileUrl.headers?.get("X-Chapter-Number")
                    ShowResponse(
                        name = chapterName,
                        link = fileUrl.url,
                        coverUrl = novelResponse.coverUrl,
                        extra = mutableMapOf<String, String>().apply {
                            releaseTime?.let { put("releaseTime", it) }
                            chapterNumber?.let { put("chapterNumber", it) }
                            put("sourceName", source.name)
                        }
                    )
                }
                novelLoaded[i] = chapterResponses
            }
        }
        novelChapters.postValue(novelLoaded)
    }

    suspend fun overrideNovelChapters(i: Int, source: ShowResponse, mediaId: Int) {
        novelSources.saveResponse(i, mediaId, source)
        novelLoaded.remove(i)
    }

    val book: MutableLiveData<Book> = MutableLiveData(null)
    suspend fun loadBook(novel: ShowResponse, i: Int) {
        tryWithSuspend {
            book.postValue(
                novelSources[i]?.loadBook(novel.link, novel.extra) ?: return@tryWithSuspend
            )
        }
    }

    private val fetchedOnlineSubtitles = mutableMapOf<String, List<Any>>()

    fun saveFetchedSubtitles(id: String, subs: List<Any>) {
        fetchedOnlineSubtitles[id] = subs
    }

    fun getFetchedSubtitles(id: String): List<Any>? {
        return fetchedOnlineSubtitles[id]
    }

    fun clearFetchedSubtitles(id: String) {
        fetchedOnlineSubtitles.remove(id)
    }

    private val localSubtitlesMap = mutableMapOf<String, MutableList<Any>>()

    fun saveLocalSubtitle(id: String, sub: Any) {
        val list = localSubtitlesMap.getOrPut(id) { mutableListOf() }
        val isDuplicate = list.any { existing ->
            existing is ani.dantotsu.parsers.Subtitle &&
            sub is ani.dantotsu.parsers.Subtitle &&
            existing.file.url == sub.file.url
        }
        if (!isDuplicate) list.add(sub)
    }

    fun getLocalSubtitles(id: String): List<Any> {
        return localSubtitlesMap[id] ?: emptyList()
    }

    fun removeLocalSubtitle(id: String, sub: Any) {
        val list = localSubtitlesMap[id] ?: return
        list.removeAll { existing ->
            if (existing is ani.dantotsu.parsers.Subtitle && sub is ani.dantotsu.parsers.Subtitle) {
                existing.file.url == sub.file.url
            } else {
                existing == sub
            }
        }
        if (list.isEmpty()) {
            localSubtitlesMap.remove(id)
        }
    }

    fun clearLocalSubtitles(id: String) {
        localSubtitlesMap.remove(id)
    }

    val adaptation = MutableLiveData<MangaAnimeUtil.AnimeAdaptation?>()
    val nextRelease = MutableLiveData<MangaAnimeUtil.NextRelease?>()
    fun loadMangaExtras(media: Media) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val seriesDeferred = async {
                    MangaAnimeUtil.getSeriesFromMedia(media)
                }

                val adaptationDeferred = async {
                    MangaAnimeUtil.getAnimeAdaptation(seriesDeferred.await())
                }

                val nextReleaseDeferred = async {
                    MangaAnimeUtil.getNextChapterPrediction(
                        media,
                        seriesDeferred.await()
                    )
                }

                adaptation.postValue(adaptationDeferred.await())
                nextRelease.postValue(nextReleaseDeferred.await())

            } catch (e: Exception) {
                Logger.log("MangaExtras error: $e")
            }
        }
    }

    // Watch Order and News integration
    data class WatchOrderItem(
        val id: String,
        val anilistId: String,
        val image: String,
        val name: String,
        val relationType: String,
        val isCurrent: Boolean = false,
        val airDate: String = "",
        val mediaType: String = "",
        val episodes: String = "",
        val rating: String = "",
        /** AniList says it has not started; the start date parts are AniList's, any of them may be missing */
        val upcoming: Boolean = false,
        val startYear: Int? = null,
        val startMonth: Int? = null,
        val startDay: Int? = null,
        /** the user's AniList list status for this entry ("COMPLETED", "CURRENT", ...), when on their list */
        val listStatus: String? = null,
        /**
         * true when AniList joins this entry to the open title through prequel/sequel links only,
         * false when it doesn't; null when that couldn't be checked (the format/relation guess is used)
         */
        val mainStory: Boolean? = null,
        /** AniList id of the main entry this one replaces (a recap movie of a season); placed right after it */
        val alternativeTo: String? = null,
        /** false when the order only names it in its notes, so its place there means nothing */
        val placed: Boolean = true,
        /** AniList id of the entry it belongs with (a short or OVA that ran with a film); placed after it */
        val sideOf: String? = null,
    ) : java.io.Serializable

    data class NewsItem(
        val title: String,
        val url: String,
        val date: java.util.Date?
    ) : java.io.Serializable

    /** One named order of a franchise ("Airing Order", "Novel Release Order"); the name is empty for a single order. */
    data class WatchOrder(val name: String, val items: List<WatchOrderItem>) : java.io.Serializable

    /**
     * Hand-written orders first (AniList ids, optional entries marked by people),
     * the order site for anything the wiki doesn't cover. Empty: no order exists. Null: nothing could be loaded.
     */
    suspend fun getWatchOrders(media: Media): List<WatchOrder>? {
        val wiki = try {
            WatchOrderWiki.ordersFor(media.id, ani.dantotsu.currContext()?.cacheDir ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("watch order wiki failed: ${e.message}")
            null
        }
        if (!wiki.isNullOrEmpty()) {
            val orders = wiki.map { WatchOrder(it.name, watchOrderWikiItems(it, media.id)) }.filter { it.items.isNotEmpty() }
            if (orders.isNotEmpty()) {
                // one AniList request for every entry in every order
                val all = withAnilistState(orders.flatMap { it.items })
                var at = 0
                return orders.map { o -> o.copy(items = all.subList(at, at + o.items.size).also { at += o.items.size }) }
            }
        }
        val site = getWatchOrder(media) ?: return null
        return if (site.isEmpty()) emptyList() else listOf(WatchOrder("", site))
    }

    /** The order site's order; null when it didn't answer (rate limit, network). */
    suspend fun getWatchOrder(media: Media): List<WatchOrderItem>? {
        return tryWithSuspend(snackbar = false) {
            val headers = mapOf(
                "Referer" to "https://chiaki.site/?/tools/watch_order",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "X-Requested-With" to "XMLHttpRequest"
            )

            val malId = media.idMAL ?: ani.dantotsu.others.IdMappers.getMalId(media.id)
            if (media.idMAL == null && malId != null) {
                media.idMAL = malId
            }
            var targetId = malId?.toString()

            if (targetId == null) {
                val searchName = media.userPreferredName.ifEmpty { media.mainName() }
                val encodedTerm = java.net.URLEncoder.encode(searchName, "UTF-8")
                val searchUrl = "https://chiaki.site/?/tools/autocomplete_series&term=$encodedTerm"
                val res = client.get(searchUrl, headers = headers).text
                val json = org.json.JSONArray(res)
                if (json.length() > 0) {
                    targetId = json.getJSONObject(0).opt("id")?.toString()
                }
            }

            if (targetId == null) return@tryWithSuspend emptyList()

            val orderUrl = "https://chiaki.site/?/tools/watch_order/id/$targetId"
            val response = client.get(orderUrl, headers = headers)
            // a rate limit answers 429 with a page that has no rows: that is "couldn't load", not "no order"
            if (response.code !in 200..299) throw java.io.IOException("watch order site: HTTP ${response.code}")
            val html = response.text
            val doc = org.jsoup.Jsoup.parse(html)

            val rows = doc.select("tr[data-id]")
            val relationsMap = media.relations?.associateBy { it.id.toString() } ?: emptyMap()

            rows.mapNotNull { row ->
                val title = row.select("span.wo_title").text().trim()
                    .ifEmpty { row.select(".wo_title").text().trim() }
                if (title.isEmpty() || title.equals("Unknown title", ignoreCase = true)) return@mapNotNull null

                val id = row.attr("data-id").ifEmpty { targetId }
                val anilistId = row.attr("data-anilist-id")

                // 1. Cover image from style background-image
                val style = row.select("div.wo_avatar_big").attr("style")
                val imgMatch = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
                var imageUrl = imgMatch?.groupValues?.get(1)?.let { path ->
                    when {
                        path.startsWith("http") -> path
                        path.startsWith("/") -> "https://chiaki.site$path"
                        else -> "https://chiaki.site/$path"
                    }
                } ?: ""

                if (imageUrl.isEmpty()) {
                    if (anilistId == media.id.toString()) {
                        imageUrl = media.cover ?: ""
                    } else if (relationsMap.containsKey(anilistId)) {
                        imageUrl = relationsMap[anilistId]?.cover ?: ""
                    }
                }

                // 2. Metadata text: Air Date | Media Type | Episodes | Rating
                val metaText = row.select("span.uk-text-muted.uk-text-small").text().trim()
                    .ifEmpty { row.select(".wo_meta").text().trim() }
                val parts = metaText.split('|').map { it.trim() }
                val airDate = if (parts.isNotEmpty()) parts[0] else ""
                val format = if (parts.size > 1) parts[1] else ""
                val eps = if (parts.size > 2) parts[2].substringBefore("★").substringBefore("(").trim() else ""
                val rating = ""

                // 3. Is current anime
                val cleanId = id.trim()
                val cleanAnilistId = anilistId.trim()
                val currentMalId = media.idMAL ?: malId
                val isCurrent = (cleanAnilistId.isNotEmpty() && cleanAnilistId == media.id.toString()) ||
                        (cleanId.isNotEmpty() && currentMalId != null && cleanId == currentMalId.toString()) ||
                        (cleanId.isNotEmpty() && targetId != null && cleanId == targetId.trim() && (currentMalId == null || currentMalId.toString() == targetId.trim())) ||
                        (title.isNotEmpty() && (title.equals(media.userPreferredName, ignoreCase = true) ||
                                title.equals(media.mainName(), ignoreCase = true) ||
                                title.equals(media.name, ignoreCase = true) ||
                                title.equals(media.nameRomaji, ignoreCase = true)))

                // 4. How the entry relates to the open title. Only AniList's own relation is
                // used; the order itself says what comes before and after, and a guessed word
                // ("Sequel" for any TV entry) was wrong more often than right.
                val relationType = if (isCurrent) "" else watchOrderRelation(relationsMap[anilistId]?.relation)


                WatchOrderItem(
                    id = id,
                    anilistId = anilistId,
                    image = imageUrl,
                    name = title,
                    relationType = relationType,
                    isCurrent = isCurrent,
                    airDate = airDate,
                    mediaType = format,
                    episodes = eps,
                    rating = rating,
                )
            }.let { withAnilistState(it) }
        }
    }

    /**
     * One AniList request for every row: release status, start date and the user's list entry.
     * Raw API values (the app's Media.status is a translated label). Rows stay as they are when
     * it fails.
     */
    private suspend fun withAnilistState(items: List<WatchOrderItem>): List<WatchOrderItem> {
        val ids = items.mapNotNull { it.anilistId.trim().toIntOrNull()?.takeIf { id -> id > 0 } }.distinct()
        if (ids.isEmpty()) return items
        val byId = try {
            ids.chunked(50).flatMap { chunk ->
                Anilist.executeQuery<ani.dantotsu.connections.anilist.api.Query.MediaList>(
                    "{Page(page:1,perPage:50){media(id_in:[${chunk.joinToString(",")}]){id format title{romaji english native userPreferred} status(version:2) startDate{year month day} mediaListEntry{status} relations{edges{relationType(version:2) node{id idMal type format title{romaji english native userPreferred} coverImage{large} seasonYear episodes status(version:2) startDate{year month day} mediaListEntry{status}}}}}}}",
                    force = true,
                )?.data?.page?.media.orEmpty()
            }.associateBy { it.id }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("watch order state lookup failed: ${e.message}")
            return items
        }
        val links = byId.mapValues { (_, m) ->
            m.relations?.edges.orEmpty().mapNotNull { edge ->
                val type = edge.relationType?.name
                if (type == "PREQUEL" || type == "SEQUEL") edge.node?.id else null
            }
        }
        // recaps and alternate versions of an entry in the list (see watchOrderAlternatives)
        // a hand-written order's main entries stay main: AniList only labels what the order calls extra
        val mainIds = items.filter { it.mainStory == true }.mapNotNull { it.anilistId.trim().toIntOrNull() }.toSet()
        val alternatives = watchOrderAlternatives(
            order = ids,
            format = byId.mapValues { it.value.format?.name },
            edges = byId.mapValues { (_, m) ->
                m.relations?.edges.orEmpty().mapNotNull { e ->
                    val to = e.node?.id ?: return@mapNotNull null
                    (e.relationType?.name ?: return@mapNotNull null) to to
                }
            },
        ).filterKeys { it !in mainIds }
        // recap films AniList ties to a season that the order leaves out (Attack on Titan's S1 films)
        val nodes = byId.values.flatMap { m -> m.relations?.edges.orEmpty().mapNotNull { it.node } }
            .filter { it.type?.name == "ANIME" }.associateBy { it.id }
        val missing = watchOrderMissingAlternatives(
            order = ids,
            format = byId.mapValues { it.value.format?.name },
            edges = byId.mapValues { (_, m) ->
                m.relations?.edges.orEmpty().mapNotNull { e ->
                    val node = e.node?.takeIf { it.id in nodes } ?: return@mapNotNull null
                    Triple(e.relationType?.name ?: return@mapNotNull null, node.id, node.format?.name)
                }
            },
            replaced = alternatives.keys,
        )
        val extra = missing.mapNotNull { (id, of) ->
            val m = nodes[id] ?: return@mapNotNull null
            WatchOrderItem(
                id = (m.idMal ?: 0).toString(),
                anilistId = id.toString(),
                image = m.coverImage?.large ?: "",
                name = m.title?.userPreferred ?: m.title?.romaji ?: return@mapNotNull null,
                relationType = "",
                airDate = m.seasonYear?.toString() ?: m.startDate?.year?.toString() ?: "",
                mediaType = watchOrderFormat(m.format?.name),
                episodes = m.episodes?.let { "$it eps" } ?: "",
                mainStory = false,
                alternativeTo = of.toString(),
                listStatus = m.mediaListEntry?.status?.name,
            )
        }
        // extras the order only names in its notes go next to what AniList ties them to
        val sides = watchOrderSides(
            order = ids,
            unplaced = items.filter { !it.placed && it.mainStory != true }.mapNotNull { it.anilistId.trim().toIntOrNull() }.toSet() - alternatives.keys,
            format = byId.mapValues { it.value.format?.name },
            edges = byId.mapValues { (_, m) ->
                m.relations?.edges.orEmpty().mapNotNull { e ->
                    val to = e.node?.id ?: return@mapNotNull null
                    (e.relationType?.name ?: return@mapNotNull null) to to
                }
            },
        )
        val start = items.firstOrNull { it.isCurrent }?.anilistId?.trim()?.toIntOrNull()
        val story = if (start != null && start in byId) watchOrderStory(start, links) else null
        return items.map { item ->
            val id = item.anilistId.trim().toIntOrNull()
            val m = byId[id] ?: return@map item
            val upcoming = m.status?.name == "NOT_YET_RELEASED"
            item.copy(
                // the title in the user's AniList title language, not the order site's romaji
                name = m.title?.userPreferred?.takeIf { it.isNotBlank() } ?: item.name,
                // a hand-written order's own optional mark wins over the prequel/sequel chain
                mainStory = if (id in alternatives) false else item.mainStory ?: story?.contains(id),
                alternativeTo = alternatives[id]?.toString(),
                sideOf = sides[id]?.toString(),
                upcoming = upcoming,
                startYear = if (upcoming) m.startDate?.year else null,
                startMonth = if (upcoming) m.startDate?.month else null,
                startDay = if (upcoming) m.startDate?.day else null,
                listStatus = m.mediaListEntry?.status?.name,
            )
        }.plus(extra).let { watchOrderPlaceAlternatives(it) }
    }


    suspend fun getAnimeNews(media: Media): List<NewsItem> {
        return tryWithSuspend {
            var malId = media.idMAL
            if (malId == null || malId == 0) {
                malId = ani.dantotsu.others.IdMappers.getMalId(media.id)
            }
            if (malId == null || malId == 0) {
                malId = runCatching {
                    Anilist.query.getMedia(media.id)?.idMAL
                }.getOrNull()
                if (malId != null && malId != 0) {
                    media.idMAL = malId
                }
            }
            val targetId = malId?.takeIf { it != 0 } ?: return@tryWithSuspend emptyList()

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/plain, */*"
            )

            suspend fun fetchKuroiruNews(id: Int): List<NewsItem> {
                val jsonStr = runCatching {
                    val res = client.get("https://kuroiru.co/api/anime/$id", headers = headers)
                    if (res.isSuccessful) res.text else null
                }.getOrNull() ?: return emptyList()

                val root = runCatching { org.json.JSONObject(jsonStr) }.getOrNull()
                    ?: return emptyList()
                val newsArr = root.optJSONArray("news") ?: return emptyList()
                val list = mutableListOf<NewsItem>()
                for (i in 0 until newsArr.length()) {
                    val itemObj = newsArr.getJSONObject(i)
                    val timeMs = itemObj.optLong("time", 0L)
                    val rawTitle = itemObj.optString("title", "")
                    val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false)
                    val rawLink = itemObj.optString("link", "")
                    val link = normalizeKuroiruLink(rawLink)
                    if (title.isNotEmpty() && link.isNotEmpty()) {
                        list.add(
                            NewsItem(
                                title = title,
                                url = link,
                                date = if (timeMs > 0) java.util.Date(timeMs * 1000L) else null
                            )
                        )
                    }
                }
                return list
            }

            var result = fetchKuroiruNews(targetId)
            if (result.isEmpty()) {
                val candidateIds = mutableListOf<Int>()
                media.prequel?.idMAL?.let { candidateIds.add(it) }
                media.relations?.forEach { rel ->
                    rel.idMAL?.let { candidateIds.add(it) }
                }
                for (candId in candidateIds) {
                    if (candId != targetId && candId != 0) {
                        result = fetchKuroiruNews(candId)
                        if (result.isNotEmpty()) break
                    }
                }
            }

            result.sortedByDescending { it.date }
        } ?: emptyList()
    }

    private fun normalizeKuroiruLink(rawLink: String): String {
        val link = rawLink.trim()
        if (link.isEmpty()) return ""
        if (link.startsWith("http://", ignoreCase = true) ||
            link.startsWith("https://", ignoreCase = true)
        ) return link
       
        if (link.all { it.isDigit() }) {
            return "https://www.animenewsnetwork.com/news/.$link"
        }
        return link
    }

    suspend fun getMangaNovelNews(media: Media): List<NewsItem> {
        return tryWithSuspend {
            val bakaBase = "https://api.mangabaka.org/v1"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/plain, */*"
            )
            suspend fun getBakaId(): Int? {
                if (media.id != 0) {
                    val id = runCatching {
                        val res = client.get("$bakaBase/source/anilist/${media.id}", headers = headers)
                        if (!res.isSuccessful) return@runCatching null
                        val root = org.json.JSONObject(res.text)
                        val seriesArr = root.optJSONObject("data")?.optJSONArray("series")
                        if (seriesArr != null && seriesArr.length() > 0) {
                            seriesArr.getJSONObject(0).optInt("id").takeIf { it != 0 }
                        } else null
                    }.getOrNull()
                    if (id != null && id != 0) return id
                }

                if (media.idMAL != null && media.idMAL != 0) {
                    val id = runCatching {
                        val res = client.get("$bakaBase/source/my-anime-list/${media.idMAL}", headers = headers)
                        if (!res.isSuccessful) return@runCatching null
                        val root = org.json.JSONObject(res.text)
                        val seriesArr = root.optJSONObject("data")?.optJSONArray("series")
                        if (seriesArr != null && seriesArr.length() > 0) {
                            seriesArr.getJSONObject(0).optInt("id").takeIf { it != 0 }
                        } else null
                    }.getOrNull()
                    if (id != null && id != 0) return id
                }

                val titleToSearch = media.userPreferredName.ifEmpty { media.mainName() }
                if (titleToSearch.isNotEmpty()) {
                    val encodedTitle = java.net.URLEncoder.encode(titleToSearch, "UTF-8")
                    val id = runCatching {
                        val res = client.get("$bakaBase/series/search?q=$encodedTitle", headers = headers)
                        if (!res.isSuccessful) return@runCatching null
                        val root = org.json.JSONObject(res.text)
                        val seriesArr = root.optJSONArray("data")
                        if (seriesArr != null && seriesArr.length() > 0) {
                            seriesArr.getJSONObject(0).optInt("id").takeIf { it != 0 }
                        } else null
                    }.getOrNull()
                    if (id != null && id != 0) return id
                }

                return null
            }

            val bakaId = getBakaId() ?: return@tryWithSuspend emptyList()

            val jsonStr = runCatching {
                val res = client.get("$bakaBase/series/$bakaId/news", headers = headers)
                if (res.isSuccessful) res.text else null
            }.getOrNull() ?: return@tryWithSuspend emptyList()

            val root = runCatching { org.json.JSONObject(jsonStr) }.getOrNull()
                ?: return@tryWithSuspend emptyList()
            val newsArr = root.optJSONArray("data") ?: return@tryWithSuspend emptyList()
            val result = mutableListOf<NewsItem>()

            fun parseDate(dateStr: String): java.util.Date? {
                if (dateStr.isEmpty()) return null
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val instantDate = runCatching {
                        java.util.Date(java.time.Instant.parse(dateStr).toEpochMilli())
                    }.getOrNull()
                    if (instantDate != null) return instantDate
                }
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd"
                )
                for (fmt in formats) {
                    val parsed = runCatching {
                        val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                        sdf.parse(dateStr)
                    }.getOrNull()
                    if (parsed != null) return parsed
                }
                return null
            }

            for (i in 0 until newsArr.length()) {
                val itemObj = newsArr.getJSONObject(i)
                val pubAt = itemObj.optString("published_at", "")
                val date = parseDate(pubAt)
                val rawTitle = itemObj.optString("title", "")
                val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false)
                val url = itemObj.optString("url", "")
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    result.add(
                        NewsItem(
                            title = title,
                            url = url,
                            date = date
                        )
                    )
                }
            }
            result.sortByDescending { it.date }
            result
        } ?: emptyList()
    }
}

/** every entry reachable from [start] through prequel/sequel links: the story itself */
internal fun watchOrderStory(start: Int, links: Map<Int, List<Int>>): Set<Int> {
    val seen = mutableSetOf(start)
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty()) {
        for (next in links[queue.removeFirst()].orEmpty()) {
            if (next in links && seen.add(next)) queue.addLast(next)
        }
    }
    return seen
}

/**
 * Each alternative moves to right after the entry it replaces, then each side entry after the entry it
 * belongs with, keeping their order; the rest stay put.
 */
internal fun watchOrderPlaceAlternatives(items: List<MediaDetailsViewModel.WatchOrderItem>): List<MediaDetailsViewModel.WatchOrderItem> {
    val ids = items.map { it.anilistId.trim() }.toSet()
    fun anchor(it: MediaDetailsViewModel.WatchOrderItem) = (it.alternativeTo ?: it.sideOf)
        ?.takeIf { a -> a in ids && a != it.anilistId.trim() }
    val moving = items.filter { anchor(it) != null }
    if (moving.isEmpty()) return items
    val rest = items.filter { it !in moving }
    val out = ArrayList<MediaDetailsViewModel.WatchOrderItem>(items.size)
    for (item in rest) {
        out += item
        val here = moving.filter { anchor(it) == item.anilistId.trim() }
        out += here.filter { it.alternativeTo != null } + here.filter { it.alternativeTo == null }
    }
    // an alternative of an alternative (or of a row that moved) lands at the end rather than vanishing
    out += moving.filter { it !in out }
    return out
}

/**
 * Which listed entry is a recap or alternate version of which (id -> the id it can replace).
 * AniList links them both ways, so the direction is decided here:
 *  - a season lists its recap as SUMMARY: the recap replaces the season
 *  - PARENT alone is not used: side-story films (Inuyasha, Hunter x Hunter) point at their show that way
 *  - ALTERNATIVE goes both ways: a movie/special/OVA replaces the TV series; two films, the later one
 *    replaces the earlier; two TV series are separate takes and replace nothing
 * An entry that is replaced is never itself an alternative, so there are no cycles.
 */
internal fun watchOrderAlternatives(
    order: List<Int>,
    format: Map<Int, String?>,
    edges: Map<Int, List<Pair<String, Int>>>,
): Map<Int, Int> {
    val listed = order.toSet()
    val position = order.withIndex().associate { it.value to it.index }
    fun series(id: Int) = format[id] in setOf("TV", "TV_SHORT", "ONA")
    val found = LinkedHashMap<Int, Int>()
    fun claim(alt: Int, of: Int) {
        if (alt == of || alt !in listed || of !in listed) return
        found.putIfAbsent(alt, of)
    }
    for (id in order) for ((type, to) in edges[id].orEmpty()) when (type) {
        "SUMMARY" -> claim(to, id)
        "ALTERNATIVE" -> when {
            series(id) && series(to) -> Unit // another TV take (FMA 2003, Cinderella Girls), not a recap
            series(to) && !series(id) -> claim(id, to)
            series(id) && !series(to) -> claim(to, id)
            (position[id] ?: 0) > (position[to] ?: 0) -> claim(id, to)
            else -> claim(to, id)
        }
    }
    val targets = found.values.toSet()
    return found.filterKeys { it !in targets }
}

/**
 * Recaps and retelling films AniList links to a season of the order that the order itself doesn't list:
 * new id -> the season it stands in for. Only films, specials and OVAs (a TV remake is its own show),
 * only off a series entry that isn't itself replaced, first link wins.
 */
internal fun watchOrderMissingAlternatives(
    order: List<Int>,
    format: Map<Int, String?>,
    edges: Map<Int, List<Triple<String, Int, String?>>>,
    replaced: Set<Int> = emptySet(),
): Map<Int, Int> {
    val listed = order.toSet()
    val found = LinkedHashMap<Int, Int>()
    for (id in order) {
        if (id in replaced || format[id] !in setOf("TV", "TV_SHORT", "ONA")) continue
        for ((type, to, toFormat) in edges[id].orEmpty()) {
            if (to in listed || type !in setOf("SUMMARY", "ALTERNATIVE")) continue
            // ALTERNATIVE also covers PVs, pilots and spin-off OVAs; only films are retellings there
            if (toFormat !in (if (type == "SUMMARY") setOf("MOVIE", "SPECIAL", "OVA") else setOf("MOVIE"))) continue
            found.putIfAbsent(to, id)
        }
    }
    return found
}

/**
 * Extras the order names only in its notes (Made in Abyss's Marulk shorts): id -> the listed entry they
 * belong with, from their own PARENT link or a listed entry's SIDE_STORY link to them. Whole TV/ONA
 * series stay where they are; they are shows of their own, not companions.
 */
internal fun watchOrderSides(
    order: List<Int>,
    unplaced: Set<Int>,
    format: Map<Int, String?>,
    edges: Map<Int, List<Pair<String, Int>>>,
): Map<Int, Int> {
    val listed = order.toSet()
    val out = LinkedHashMap<Int, Int>()
    for (id in order) {
        if (id !in unplaced || format[id] in setOf("TV", "ONA")) continue
        val anchor = edges[id].orEmpty().firstOrNull { (t, to) -> t == "PARENT" && to in listed && to != id }?.second
            ?: order.firstOrNull { o -> o != id && edges[o].orEmpty().any { (t, to) -> t == "SIDE_STORY" && to == id } }
        if (anchor != null && anchor !in unplaced) out[id] = anchor
    }
    return out
}
