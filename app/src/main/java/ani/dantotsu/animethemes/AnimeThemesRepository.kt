package ani.dantotsu.animethemes

import android.content.Context
import android.util.AtomicFile
import ani.dantotsu.App
import ani.dantotsu.Mapper
import ani.dantotsu.media.Media
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

interface AtomicFileWriter {
    fun openRead(): FileInputStream
    fun startWrite(): FileOutputStream
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
    fun exists(): Boolean
}

class AndroidAtomicFileWriter(private val file: File) : AtomicFileWriter {
    private val atomicFile = AtomicFile(file)

    override fun openRead(): FileInputStream = atomicFile.openRead()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun finishWrite(stream: FileOutputStream) = atomicFile.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomicFile.failWrite(stream)
    override fun exists(): Boolean = file.exists() || atomicFile.baseFile.exists()
}

class StandaloneAtomicFileWriter(private val file: File) : AtomicFileWriter {
    private val tempFile = File(file.parentFile, "${file.name}.new")

    override fun openRead(): FileInputStream = FileInputStream(file)

    override fun startWrite(): FileOutputStream {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return FileOutputStream(tempFile)
    }

    override fun finishWrite(stream: FileOutputStream) {
        stream.flush()
        try {
            stream.fd.sync()
        } catch (_: Exception) {}
        stream.close()
        if (!tempFile.renameTo(file)) {
            file.delete()
            tempFile.renameTo(file)
        }
    }

    override fun failWrite(stream: FileOutputStream) {
        try {
            stream.close()
        } catch (_: Exception) {}
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    override fun exists(): Boolean = file.exists()
}

class AnimeThemesRepository(
    private val context: Context? = null,
    private val api: AnimeThemesApiClient = AnimeThemesApi,
    private val cacheDirProvider: () -> File = {
        (context ?: App.context ?: App.instance ?: throw IllegalStateException("Context required")).applicationContext.cacheDir
    },
    private val atomicFileWriterFactory: (File) -> AtomicFileWriter = { file -> AndroidAtomicFileWriter(file) }
) {

    companion object {
        const val MAX_CACHE_ENTRIES = 200
        const val MAX_CACHE_BYTES = 500 * 1024L // 500 KB limit
        const val POSITIVE_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 Days
        const val NEGATIVE_TTL_MS = 24 * 60 * 60 * 1000L // 24 Hours
        const val CACHE_FILE_NAME = "animethemes_metadata_cache.json"

        fun getCacheKey(media: Media): String? = getCacheKey(media.id, media.idMAL)

        fun getCacheKey(id: Int, idMal: Int?): String? {
            return if (id > 0) {
                "anilist_$id"
            } else if (idMal != null && idMal > 0) {
                "mal_$idMal"
            } else {
                null
            }
        }

        fun scoreVideo(video: AnimeThemesVideoDto): Int {
            var score = 0
            if (video.nc) score += 1000
            when (video.source?.uppercase()) {
                "BD" -> score += 500
                "WEB" -> score += 300
                "DVD" -> score += 100
            }
            score += (video.resolution ?: 720) / 10
            if (!video.subbed) score += 200
            if (video.overlap.equals("NONE", ignoreCase = true) || video.overlap.equals("TRANS", ignoreCase = true)) {
                score += 100
            }
            return score
        }

        fun createBadgeLabel(video: AnimeThemesVideoDto): String {
            val parts = mutableListOf<String>()
            if (video.resolution != null && video.resolution > 0) {
                parts.add("${video.resolution}p")
            }
            if (!video.source.isNullOrBlank()) {
                parts.add(video.source)
            }
            if (video.nc) {
                parts.add("NC")
            }
            if (video.subbed) {
                parts.add("Sub")
            }
            return parts.joinToString(" • ")
        }

        fun formatArtistDisplay(performances: List<AnimeThemesPerformanceDto>): String {
            if (performances.isEmpty()) return ""

            val sorted = performances.sortedBy { it.relevance ?: Int.MAX_VALUE }

            val groupNames = sorted.mapNotNull { it.artist?.name?.main?.takeIf { n -> n.isNotBlank() } }.distinct()
            val hasSingleGroup = groupNames.size == 1 && sorted.all { it.artist?.name?.main == groupNames.first() }
            val hasMembers = sorted.any { it.member != null || it.memberAlias != null }

            if (hasSingleGroup && hasMembers) {
                return groupNames.first()
            }

            return sorted
                .mapNotNull { perf ->
                    val mainArtist = perf.alias?.takeIf { it.isNotBlank() }
                        ?: perf.artist?.name?.main?.takeIf { it.isNotBlank() }
                    val artistAs = perf.asAlias?.takeIf { it.isNotBlank() }

                    val memberName = perf.memberAlias?.takeIf { it.isNotBlank() }
                        ?: perf.member?.name?.main?.takeIf { it.isNotBlank() }
                    val memberAs = perf.memberAs?.takeIf { it.isNotBlank() }

                    when {
                        memberName != null && memberAs != null && mainArtist != null ->
                            "$memberName (as $memberAs) [$mainArtist]"
                        memberName != null && memberAs != null ->
                            "$memberName (as $memberAs)"
                        memberName != null && mainArtist != null ->
                            "$memberName [$mainArtist]"
                        memberName != null ->
                            memberName
                        mainArtist != null && artistAs != null ->
                            "$mainArtist (as $artistAs)"
                        mainArtist != null ->
                            mainArtist
                        else -> null
                    }
                }
                .distinct()
                .joinToString(", ")
        }

        fun mapThemeToUiModel(theme: AnimeThemesThemeDto): ThemeUiModel {
            val songTitle = theme.song?.title?.romaji?.takeIf { it.isNotBlank() } ?: theme.slug
            val artistDisplay = formatArtistDisplay(theme.song?.performances ?: emptyList())

            val alternateVersions = theme.animethemeentries.map { entry ->
                ThemeVersionUiModel(
                    entryId = entry.id,
                    version = entry.version ?: 1,
                    episodes = entry.episodes,
                    isNsfw = entry.nsfw,
                    isSpoiler = entry.spoiler,
                    videos = (entry.videos?.nodes ?: emptyList()).map { v ->
                        ThemeVideoUiModel(
                            videoId = v.id,
                            videoUrl = v.link,
                            resolution = v.resolution,
                            isCreditless = v.nc,
                            source = v.source,
                            overlap = v.overlap,
                            tags = v.tags,
                            isSubbed = v.subbed,
                            badgeLabel = createBadgeLabel(v),
                            audioUrl = v.audio?.link?.takeIf { it.isNotBlank() }
                        )
                    }
                )
            }

            val displayTag = ThemeTags.display(theme.type, theme.sequence, theme.slug)

            return ThemeUiModel(
                themeId = theme.id,
                type = theme.type,
                sequence = theme.sequence,
                displayTag = displayTag,
                songTitle = songTitle,
                artistDisplay = artistDisplay,
                alternateVersions = alternateVersions
            )
        }
    }

    private val cacheFile: File by lazy {
        File(cacheDirProvider(), CACHE_FILE_NAME)
    }

    private val atomicCacheWriter: AtomicFileWriter by lazy {
        atomicFileWriterFactory(cacheFile)
    }

    private val memoryCache = object : LinkedHashMap<String, CachedAnimeThemesEntryDto>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAnimeThemesEntryDto>?): Boolean {
            return size > 100
        }
    }
    private val cacheMutex = Mutex()

    /**
     * Resolves AnimeThemes metadata for [media].
     * Uses in-memory & disk cache unless [forceRefresh] is true.
     * Guaranteed fail-closed resolution on ambiguity.
     */
    suspend fun getThemesForMedia(media: Media, forceRefresh: Boolean = false): AnimeThemesData? =
        (lookup(media.id, media.idMAL, forceRefresh) as? ThemesLookup.Found)?.data

    /** [getThemesForMedia] by ids, for lists that only carry them (the shuffle). */
    suspend fun getThemesForIds(id: Int, idMal: Int?): AnimeThemesData? =
        (lookup(id, idMal) as? ThemesLookup.Found)?.data

    /** Lookups outlive the screen that asked: an answer can take 16-38 s and is cached when it comes. */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<ThemesLookup>>()

    /**
     * [lookup] in an app-level scope. Cancelling the caller only stops its wait; the request
     * and the cache write finish, and a caller asking for the same show joins the running lookup.
     */
    fun lookupShared(id: Int, idMal: Int?, forceRefresh: Boolean = false): Deferred<ThemesLookup> {
        val key = getCacheKey(id, idMal) ?: return CompletableDeferred(ThemesLookup.NoMatch)
        synchronized(inFlight) {
            inFlight[key]?.takeIf { it.isActive }?.let { return it }
            val job = lookupScope.async { lookup(id, idMal, forceRefresh) }
            inFlight[key] = job
            job.invokeOnCompletion {
                synchronized(inFlight) { if (inFlight[key] === job) inFlight.remove(key) }
            }
            return job
        }
    }

    /** Like [getThemesForMedia], but tells "AnimeThemes has nothing" from "AnimeThemes did not answer". */
    suspend fun lookup(id: Int, idMal: Int?, forceRefresh: Boolean = false): ThemesLookup {
        val cacheKey = getCacheKey(id, idMal) ?: return ThemesLookup.NoMatch

        if (!forceRefresh) {
            val cached = getCachedEntry(cacheKey)
            if (cached != null) {
                val now = System.currentTimeMillis()
                val ttl = if (cached.isNegativeMatch) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
                if (now < cached.timestamp + ttl) {
                    return if (cached.isNegativeMatch) ThemesLookup.NoMatch
                    else cached.payload?.toDomain()?.let { ThemesLookup.Found(it) } ?: ThemesLookup.NoMatch
                }
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                var selectedAnime: AnimeThemesAnimeDto? = null
                var isAuthoritativeNoMatch = false

                if (id > 0) {
                    when (val aniListResult = api.queryAnimeByExternalSite("ANILIST", id)) {
                        is AnimeThemesApiResult.Failure -> {
                            // Technical / transport failure -> do NOT fallback to MAL as "0 match", do NOT negative cache
                            Logger.log("AnimeThemesRepository: AniList query failed technically: ${aniListResult.reason} - ${aniListResult.message}")
                            return@withContext ThemesLookup.Failed
                        }
                        is AnimeThemesApiResult.Success -> {
                            val aniListResults = aniListResult.response.data?.findAnimeByExternalSite ?: emptyList()

                            if (aniListResults.size == 1) {
                                selectedAnime = aniListResults[0]
                            } else if (aniListResults.isEmpty()) {
                                // Authoritative 0 results from AniList -> attempt MAL fallback if MAL ID exists
                                if (idMal != null && idMal!! > 0) {
                                    when (val malResult = api.queryAnimeByExternalSite("MAL", idMal!!)) {
                                        is AnimeThemesApiResult.Failure -> {
                                            Logger.log("AnimeThemesRepository: MAL fallback query failed technically: ${malResult.reason} - ${malResult.message}")
                                            return@withContext ThemesLookup.Failed
                                        }
                                        is AnimeThemesApiResult.Success -> {
                                            val malResults = malResult.response.data?.findAnimeByExternalSite ?: emptyList()
                                            if (malResults.size == 1) {
                                                selectedAnime = malResults[0]
                                            } else {
                                                isAuthoritativeNoMatch = true
                                            }
                                        }
                                    }
                                } else {
                                    isAuthoritativeNoMatch = true
                                }
                            } else {
                                // Ambiguous AniList (> 1 matches) -> attempt MAL intersection
                                if (idMal != null && idMal!! > 0) {
                                    when (val malResult = api.queryAnimeByExternalSite("MAL", idMal!!)) {
                                        is AnimeThemesApiResult.Failure -> {
                                            Logger.log("AnimeThemesRepository: MAL intersection query failed technically: ${malResult.reason} - ${malResult.message}")
                                            return@withContext ThemesLookup.Failed
                                        }
                                        is AnimeThemesApiResult.Success -> {
                                            val malResults = malResult.response.data?.findAnimeByExternalSite ?: emptyList()
                                            val malIds = malResults.map { it.id }.toSet()
                                            val intersected = aniListResults.filter { malIds.contains(it.id) }
                                            if (intersected.size == 1) {
                                                selectedAnime = intersected[0]
                                            } else {
                                                isAuthoritativeNoMatch = true
                                            }
                                        }
                                    }
                                } else {
                                    isAuthoritativeNoMatch = true
                                }
                            }
                        }
                    }
                } else if (idMal != null && idMal!! > 0) {
                    when (val malResult = api.queryAnimeByExternalSite("MAL", idMal!!)) {
                        is AnimeThemesApiResult.Failure -> {
                            Logger.log("AnimeThemesRepository: MAL-only query failed technically: ${malResult.reason} - ${malResult.message}")
                            return@withContext ThemesLookup.Failed
                        }
                        is AnimeThemesApiResult.Success -> {
                            val malResults = malResult.response.data?.findAnimeByExternalSite ?: emptyList()
                            if (malResults.size == 1) {
                                selectedAnime = malResults[0]
                            } else {
                                isAuthoritativeNoMatch = true
                            }
                        }
                    }
                }

                if (selectedAnime != null) {
                    val openings = selectedAnime.animethemes
                        .filter { it.type.equals("OP", ignoreCase = true) }
                        .sortedBy { it.sequence ?: Int.MAX_VALUE }
                        .map { mapThemeToUiModel(it) }

                    val endings = selectedAnime.animethemes
                        .filter { it.type.equals("ED", ignoreCase = true) }
                        .sortedBy { it.sequence ?: Int.MAX_VALUE }
                        .map { mapThemeToUiModel(it) }

                    if (openings.isEmpty() && endings.isEmpty()) {
                        saveCacheEntry(cacheKey, id, isNegative = true, data = null)
                        ThemesLookup.NoMatch
                    } else {
                        val animeThemesData = AnimeThemesData(
                            animeId = selectedAnime.id,
                            animeSlug = selectedAnime.slug,
                            openings = openings,
                            endings = endings
                        )
                        saveCacheEntry(cacheKey, id, isNegative = false, data = animeThemesData)
                        ThemesLookup.Found(animeThemesData)
                    }
                } else if (isAuthoritativeNoMatch) {
                    saveCacheEntry(cacheKey, id, isNegative = true, data = null)
                    ThemesLookup.NoMatch
                } else {
                    ThemesLookup.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(e)
                ThemesLookup.Failed
            }
        }
    }

    private suspend fun getCachedEntry(cacheKey: String): CachedAnimeThemesEntryDto? {
        synchronized(memoryCache) {
            val mem = memoryCache[cacheKey]
            if (mem != null) return mem
        }

        return withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                val diskCache = loadDiskCacheInternal()
                val diskEntry = diskCache.entries[cacheKey]
                if (diskEntry != null) {
                    synchronized(memoryCache) {
                        memoryCache[cacheKey] = diskEntry
                    }
                }
                diskEntry
            }
        }
    }

    private suspend fun saveCacheEntry(cacheKey: String, mediaId: Int, isNegative: Boolean, data: AnimeThemesData?) {
        val entry = CachedAnimeThemesEntryDto(
            cacheKey = cacheKey,
            mediaId = mediaId,
            timestamp = System.currentTimeMillis(),
            isNegativeMatch = isNegative,
            payload = data?.toCachePayload()
        )

        synchronized(memoryCache) {
            memoryCache[cacheKey] = entry
        }

        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                try {
                    val cache = loadDiskCacheInternal()
                    cache.entries[cacheKey] = entry

                    val now = System.currentTimeMillis()

                    // 1. Prune expired entries
                    val iterator = cache.entries.iterator()
                    while (iterator.hasNext()) {
                        val e = iterator.next().value
                        val ttl = if (e.isNegativeMatch) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
                        if (now > e.timestamp + ttl) {
                            iterator.remove()
                        }
                    }

                    // 2. Prune oldest entries if count exceeds max
                    if (cache.entries.size > MAX_CACHE_ENTRIES) {
                        val sortedKeys = cache.entries.entries
                            .sortedBy { it.value.timestamp }
                            .take(cache.entries.size - MAX_CACHE_ENTRIES)
                            .map { it.key }
                        sortedKeys.forEach { cache.entries.remove(it) }
                    }

                    // 3. Serialize and prune by size if bytes exceed limit
                    var jsonString = Mapper.json.encodeToString(AnimeThemesDiskCache.serializer(), cache)
                    var jsonBytes = jsonString.toByteArray(Charsets.UTF_8)

                    while (jsonBytes.size > MAX_CACHE_BYTES && cache.entries.isNotEmpty()) {
                        val oldestKey = cache.entries.minByOrNull { it.value.timestamp }?.key ?: break
                        cache.entries.remove(oldestKey)
                        jsonString = Mapper.json.encodeToString(AnimeThemesDiskCache.serializer(), cache)
                        jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
                    }

                    // 4. Atomic Write
                    saveDiskCacheInternal(jsonString)
                } catch (e: Exception) {
                    Logger.log(e)
                }
            }
        }
    }

    private fun loadDiskCacheInternal(): AnimeThemesDiskCache {
        return try {
            if (!atomicCacheWriter.exists()) {
                return AnimeThemesDiskCache(version = CACHE_VERSION)
            }
            var stream: FileInputStream? = null
            try {
                stream = atomicCacheWriter.openRead()
                val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (content.isBlank()) {
                    AnimeThemesDiskCache(version = CACHE_VERSION)
                } else {
                    val decoded = Mapper.json.decodeFromString(AnimeThemesDiskCache.serializer(), content)
                    if (decoded.version != CACHE_VERSION) {
                        // Fail open on schema version mismatch (v1 or unknown future version)
                        AnimeThemesDiskCache(version = CACHE_VERSION)
                    } else {
                        decoded
                    }
                }
            } finally {
                stream?.close()
            }
        } catch (e: Exception) {
            Logger.log(e)
            // Fail-open: reset cache on corruption or parse errors
            AnimeThemesDiskCache(version = CACHE_VERSION)
        }
    }

    private fun saveDiskCacheInternal(jsonContent: String) {
        val parentDir = cacheFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }
        var out: FileOutputStream? = null
        try {
            out = atomicCacheWriter.startWrite()
            out.write(jsonContent.toByteArray(Charsets.UTF_8))
            atomicCacheWriter.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) {
                atomicCacheWriter.failWrite(out)
            }
            Logger.log(e)
            throw e
        }
    }
}

/** Result of [AnimeThemesRepository.lookup]. */
sealed interface ThemesLookup {
    data class Found(val data: AnimeThemesData) : ThemesLookup
    /** AnimeThemes answered and has no themes for the show (cached for a day). */
    data object NoMatch : ThemesLookup
    /** AnimeThemes did not answer or answered with an error; not cached. */
    data object Failed : ThemesLookup
}
