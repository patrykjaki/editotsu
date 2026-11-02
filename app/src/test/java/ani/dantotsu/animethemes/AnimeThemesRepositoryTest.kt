package ani.dantotsu.animethemes

import android.content.Context
import ani.dantotsu.Mapper
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Anime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class FakeAnimeThemesApiClient : AnimeThemesApiClient {
    var aniListResult: AnimeThemesApiResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(emptyList())))
    var malResult: AnimeThemesApiResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(emptyList())))
    var aniListCallCount = 0
    var malCallCount = 0

    override suspend fun queryAnimeByExternalSite(site: String, externalId: Int): AnimeThemesApiResult {
        return when (site) {
            "ANILIST" -> {
                aniListCallCount++
                aniListResult
            }
            "MAL" -> {
                malCallCount++
                malResult
            }
            else -> AnimeThemesApiResult.Failure(ApiFailureReason.INVALID_PARAMETERS, "Unknown site $site")
        }
    }
}

class AnimeThemesRepositoryTest {

    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        tempCacheDir = File(System.getProperty("java.io.tmpdir"), "animethemes_test_cache_${System.currentTimeMillis()}")
        tempCacheDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    private fun createMedia(id: Int, idMAL: Int? = null): Media {
        return Media(
            id = id,
            idMAL = idMAL,
            name = "Test Anime $id",
            nameRomaji = "Test Anime Romaji $id",
            userPreferredName = "Test Anime $id",
            isAdult = false,
            anime = Anime()
        )
    }

    @Test
    fun testCacheKeyDerivation() {
        val anilistMedia = createMedia(12345, 67890)
        assertEquals("anilist_12345", AnimeThemesRepository.getCacheKey(anilistMedia))

        val malOnlyMedia = createMedia(0, 67890)
        assertEquals("mal_67890", AnimeThemesRepository.getCacheKey(malOnlyMedia))

        val invalidMedia = createMedia(0, null)
        assertNull("Invalid media with id <= 0 and null idMAL must return null cache key", AnimeThemesRepository.getCacheKey(invalidMedia))
    }

    @Test
    fun testGraphQLResponseDeserialization() {
        val jsonPayload = """
        {
            "data": {
                "findAnimeByExternalSite": [
                    {
                        "id": 521,
                        "slug": "cowboy_bebop",
                        "title": { "romaji": "Cowboy Bebop" },
                        "year": 1998,
                        "season": "SPRING",
                        "format": "TV",
                        "animethemes": [
                            {
                                "id": 578,
                                "type": "OP",
                                "sequence": 1,
                                "slug": "OP1",
                                "song": {
                                    "id": 578,
                                    "title": { "romaji": "Tank!" },
                                    "performances": [
                                        {
                                            "as": null,
                                            "artist": {
                                                "id": 100,
                                                "name": { "main": "Seatbelts" }
                                            }
                                        }
                                    ]
                                },
                                "animethemeentries": [
                                    {
                                        "id": 644,
                                        "version": 1,
                                        "episodes": "1-25",
                                        "nsfw": false,
                                        "spoiler": false,
                                        "videos": {
                                            "nodes": [
                                                {
                                                    "id": 12907,
                                                    "basename": "CowboyBebop-OP1.webm",
                                                    "filename": "CowboyBebop-OP1",
                                                    "resolution": 720,
                                                    "nc": false,
                                                    "subbed": false,
                                                    "source": "BD",
                                                    "overlap": "NONE",
                                                    "tags": "BD",
                                                    "link": "https://v.animethemes.moe/CowboyBebop-OP1.webm"
                                                }
                                            ]
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
        }
        """.trimIndent()

        val parsed = Mapper.json.decodeFromString(AnimeThemesGraphQLResponse.serializer(), jsonPayload)
        assertNotNull(parsed.data)
        assertEquals(1, parsed.data?.findAnimeByExternalSite?.size)

        val anime = parsed.data!!.findAnimeByExternalSite[0]
        assertEquals(521, anime.id)
        assertEquals("cowboy_bebop", anime.slug)
        assertEquals("Cowboy Bebop", anime.title?.romaji)
        assertEquals(1, anime.animethemes.size)

        val op = anime.animethemes[0]
        assertEquals("OP", op.type)
        assertEquals("Tank!", op.song?.title?.romaji)
        assertEquals("Seatbelts", op.song?.performances?.firstOrNull()?.artist?.name?.main)
        assertEquals(1, op.animethemeentries.size)
        assertEquals("https://v.animethemes.moe/CowboyBebop-OP1.webm", op.animethemeentries[0].videos?.nodes?.firstOrNull()?.link)
    }

    @Test
    fun testGraphQLErrorResponseHandling() {
        val errorJson = """
        {
            "errors": [
                {
                    "message": "Field 'invalidField' does not exist on type 'Anime'",
                    "locations": [{ "line": 1, "column": 20 }]
                }
            ],
            "data": null
        }
        """.trimIndent()

        val parsed = Mapper.json.decodeFromString(AnimeThemesGraphQLResponse.serializer(), errorJson)
        assertNotNull(parsed.errors)
        assertEquals(1, parsed.errors?.size)
        assertEquals("Field 'invalidField' does not exist on type 'Anime'", parsed.errors?.get(0)?.message)
        assertNull(parsed.data)
    }

    @Test
    fun testDiskCacheRoundTripSerialization() {
        val originalCache = AnimeThemesDiskCache(
            version = CACHE_VERSION,
            entries = mutableMapOf(
                "anilist_1" to CachedAnimeThemesEntryDto(
                    cacheKey = "anilist_1",
                    mediaId = 1,
                    timestamp = 1700000000000L,
                    isNegativeMatch = false,
                    payload = AnimeThemesCachePayloadDto(
                        animeId = 521,
                        animeSlug = "cowboy_bebop",
                        openings = listOf(
                            ThemeCacheDto(
                                themeId = 578,
                                type = "OP",
                                sequence = 1,
                                displayTag = "OP 1",
                                songTitle = "Tank!",
                                artistDisplay = "Seatbelts",
                                alternateVersions = listOf(
                                    ThemeVersionCacheDto(
                                        entryId = 644,
                                        version = 1,
                                        episodes = "1-25",
                                        isNsfw = false,
                                        isSpoiler = false,
                                        videos = listOf(
                                            ThemeVideoCacheDto(
                                                videoId = 12907,
                                                videoUrl = "https://v.animethemes.moe/CowboyBebop-OP1.webm",
                                                resolution = 720,
                                                isCreditless = false,
                                                source = "BD",
                                                overlap = "NONE",
                                                tags = "BD",
                                                isSubbed = false,
                                                badgeLabel = "720p • BD"
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                        endings = emptyList()
                    )
                ),
                "anilist_2" to CachedAnimeThemesEntryDto(
                    cacheKey = "anilist_2",
                    mediaId = 2,
                    timestamp = 1700000000000L,
                    isNegativeMatch = true,
                    payload = null
                )
            )
        )

        val jsonString = Mapper.json.encodeToString(AnimeThemesDiskCache.serializer(), originalCache)
        val deserialized = Mapper.json.decodeFromString(AnimeThemesDiskCache.serializer(), jsonString)

        assertEquals(originalCache.version, deserialized.version)
        assertEquals(2, deserialized.entries.size)

        val entry1 = deserialized.entries["anilist_1"]
        assertNotNull(entry1)
        assertEquals(1, entry1?.mediaId)
        assertEquals(false, entry1?.isNegativeMatch)
        assertEquals(521, entry1?.payload?.animeId)
        assertEquals("Tank!", entry1?.payload?.openings?.firstOrNull()?.songTitle)

        val entry2 = deserialized.entries["anilist_2"]
        assertNotNull(entry2)
        assertEquals(true, entry2?.isNegativeMatch)
        assertNull(entry2?.payload)
    }

    @Test
    fun testSuccessfulEmptyLookupWritesNegativeCache() = runBlocking {
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(emptyList())))
            malResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(emptyList())))
        }
        val repository = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100, 200)

        val result = repository.getThemesForMedia(media)
        assertNull("Empty lookup must return null result", result)

        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        assertTrue("Cache file must be created on authoritative negative match", cacheFile.exists())

        val diskCache = Mapper.json.decodeFromString(AnimeThemesDiskCache.serializer(), cacheFile.readText())
        val entry = diskCache.entries["anilist_100"]
        assertNotNull("Negative cache entry must be recorded", entry)
        assertTrue("Entry must be marked as negative match", entry!!.isNegativeMatch)
        assertNull(entry.payload)
    }

    @Test
    fun testAmbiguousSuccessfulLookupWritesNegativeCache() = runBlocking {
        val anime1 = AnimeThemesAnimeDto(id = 1, slug = "slug_1")
        val anime2 = AnimeThemesAnimeDto(id = 2, slug = "slug_2")
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(anime1, anime2))))
            malResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(emptyList())))
        }
        val repository = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100, 200)

        val result = repository.getThemesForMedia(media)
        assertNull("Unresolvable ambiguous match must fail closed and return null", result)

        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        assertTrue("Cache file must be created for unresolved ambiguity", cacheFile.exists())

        val diskCache = Mapper.json.decodeFromString(AnimeThemesDiskCache.serializer(), cacheFile.readText())
        val entry = diskCache.entries["anilist_100"]
        assertNotNull("Negative cache entry must be recorded for ambiguity", entry)
        assertTrue(entry!!.isNegativeMatch)
    }

    @Test
    fun testNetworkFailureDoesNotWriteNegativeCache() = runBlocking {
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Failure(ApiFailureReason.NETWORK_ERROR, "Socket timeout")
        }
        val repository = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100, 200)

        val result = repository.getThemesForMedia(media)
        assertNull("Network failure must return null", result)

        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        assertFalse("Cache file must NOT be written on network failure", cacheFile.exists())
    }

    @Test
    fun testGraphQLErrorDoesNotWriteNegativeCache() = runBlocking {
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Failure(ApiFailureReason.GRAPHQL_ERROR, "Rate limit exceeded")
        }
        val repository = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100, 200)

        val result = repository.getThemesForMedia(media)
        assertNull("GraphQL error must return null", result)

        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        assertFalse("Cache file must NOT be written on GraphQL application error", cacheFile.exists())
    }

    @Test
    fun testDecodeFailureDoesNotWriteNegativeCache() = runBlocking {
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Failure(ApiFailureReason.DECODE_ERROR, "Invalid JSON")
        }
        val repository = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100, 200)

        val result = repository.getThemesForMedia(media)
        assertNull("Decode failure must return null", result)

        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        assertFalse("Cache file must NOT be written on decode error", cacheFile.exists())
    }

    @Test
    fun testAniListFailureDoesNotSilentlyFallbackToMal() = runBlocking {
        val malAnime = AnimeThemesAnimeDto(id = 50, slug = "mal_slug", animethemes = listOf(
            AnimeThemesThemeDto(id = 1, type = "OP", slug = "OP1")
        ))
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Failure(ApiFailureReason.NETWORK_ERROR, "AniList connection lost")
            malResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(malAnime))))
        }
        val repository = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100, 200)

        val result = repository.getThemesForMedia(media)
        assertNull("Must return null when AniList fails technically", result)
        assertEquals("AniList API must be called exactly once", 1, fakeApi.aniListCallCount)
        assertEquals("MAL API must NOT be queried when AniList failed technically", 0, fakeApi.malCallCount)

        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        assertFalse("Cache must not be written when primary query fails technically", cacheFile.exists())
    }

    @Test
    fun testAtomicWriteSuccessfulRoundTrip() = runBlocking {
        val anime = AnimeThemesAnimeDto(
            id = 521,
            slug = "cowboy_bebop",
            animethemes = listOf(
                AnimeThemesThemeDto(
                    id = 1,
                    type = "OP",
                    sequence = 1,
                    slug = "OP1",
                    song = AnimeThemesSongDto(id = 1, title = AnimeThemesTitleDto(romaji = "Tank!"))
                )
            )
        )
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(anime))))
        }
        val repo1 = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(521)

        val saved = repo1.getThemesForMedia(media)
        assertNotNull(saved)
        assertEquals("Tank!", saved!!.openings.firstOrNull()?.songTitle)
        assertEquals(1, fakeApi.aniListCallCount)

        // Create independent repository with fresh memory cache to verify disk persistence
        val repo2 = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val loaded = repo2.getThemesForMedia(media)
        assertNotNull(loaded)
        assertEquals("Tank!", loaded!!.openings.firstOrNull()?.songTitle)
        // Must be served from disk cache without re-invoking network API
        assertEquals(1, fakeApi.aniListCallCount)
    }

    @Test
    fun testAtomicWriteReplacementPreservesLatestCompletePayload() = runBlocking {
        val animeV1 = AnimeThemesAnimeDto(
            id = 100,
            slug = "test_anime",
            animethemes = listOf(
                AnimeThemesThemeDto(id = 1, type = "OP", sequence = 1, slug = "OP1", song = AnimeThemesSongDto(id = 1, title = AnimeThemesTitleDto(romaji = "Song 1")))
            )
        )
        val animeV2 = AnimeThemesAnimeDto(
            id = 100,
            slug = "test_anime",
            animethemes = listOf(
                AnimeThemesThemeDto(id = 1, type = "OP", sequence = 1, slug = "OP1", song = AnimeThemesSongDto(id = 1, title = AnimeThemesTitleDto(romaji = "Song 1"))),
                AnimeThemesThemeDto(id = 2, type = "OP", sequence = 2, slug = "OP2", song = AnimeThemesSongDto(id = 2, title = AnimeThemesTitleDto(romaji = "Song 2")))
            )
        )

        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(animeV1))))
        }
        val repo = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(100)

        val initial = repo.getThemesForMedia(media)
        assertNotNull(initial)
        assertEquals(1, initial!!.openings.size)

        // Update API and force refresh to trigger atomic replacement write
        fakeApi.aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(animeV2))))
        val updated = repo.getThemesForMedia(media, forceRefresh = true)
        assertNotNull(updated)
        assertEquals(2, updated!!.openings.size)

        // Verify fresh repository instance reads updated complete payload from disk
        val repoFresh = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val readFromDisk = repoFresh.getThemesForMedia(media)
        assertNotNull(readFromDisk)
        assertEquals(2, readFromDisk!!.openings.size)
        assertEquals("Song 2", readFromDisk.openings[1].songTitle)
    }

    @Test
    fun testInterruptedWritePreservesPreviousValidPayload() = runBlocking {
        val anime = AnimeThemesAnimeDto(
            id = 200,
            slug = "valid_anime",
            animethemes = listOf(
                AnimeThemesThemeDto(id = 1, type = "OP", sequence = 1, slug = "OP1", song = AnimeThemesSongDto(id = 1, title = AnimeThemesTitleDto(romaji = "Good Song")))
            )
        )
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(anime))))
        }
        val repo = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(200)

        // 1. Write initial good cache
        val initial = repo.getThemesForMedia(media)
        assertNotNull(initial)
        assertEquals("Good Song", initial!!.openings.firstOrNull()?.songTitle)

        // 2. Simulate an interrupted atomic write using StandaloneAtomicFileWriter
        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        val writer = StandaloneAtomicFileWriter(cacheFile)
        val outStream = writer.startWrite()
        outStream.write("{\"corrupted_partial_json".toByteArray(Charsets.UTF_8))
        // Fail / abort the write
        writer.failWrite(outStream)

        // 3. Verify original cache file is completely intact and unaffected
        val repoFresh = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val afterAbort = repoFresh.getThemesForMedia(media)
        assertNotNull("Previous valid payload must be preserved after interrupted write", afterAbort)
        assertEquals("Good Song", afterAbort!!.openings.firstOrNull()?.songTitle)
    }

    @Test
    fun testCorruptExistingPayloadFailsOpen() = runBlocking {
        val cacheFile = File(tempCacheDir, AnimeThemesRepository.CACHE_FILE_NAME)
        cacheFile.writeText("{\"invalid_json_garbage: true", Charsets.UTF_8)

        val anime = AnimeThemesAnimeDto(
            id = 300,
            slug = "recovered_anime",
            animethemes = listOf(
                AnimeThemesThemeDto(id = 1, type = "OP", sequence = 1, slug = "OP1", song = AnimeThemesSongDto(id = 1, title = AnimeThemesTitleDto(romaji = "Recovered Song")))
            )
        )
        val fakeApi = FakeAnimeThemesApiClient().apply {
            aniListResult = AnimeThemesApiResult.Success(AnimeThemesGraphQLResponse(data = AnimeThemesDataResponse(listOf(anime))))
        }
        val repo = AnimeThemesRepository(
            api = fakeApi,
            cacheDirProvider = { tempCacheDir },
            atomicFileWriterFactory = { file -> StandaloneAtomicFileWriter(file) }
        )
        val media = createMedia(300)

        // Must not crash on corrupted disk file; must fail open, query API, and self-heal disk cache
        val result = repo.getThemesForMedia(media)
        assertNotNull(result)
        assertEquals("Recovered Song", result!!.openings.firstOrNull()?.songTitle)
    }

    @Test
    fun testCancellationExceptionPropagation() {
        try {
            runBlocking {
                throw CancellationException("Coroutine cancelled")
            }
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("Coroutine cancelled", e.message)
        }
    }
}
