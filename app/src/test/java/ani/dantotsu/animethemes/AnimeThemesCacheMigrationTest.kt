package ani.dantotsu.animethemes

import ani.dantotsu.Mapper
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Anime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnimeThemesCacheMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    private class FakeFailingApiClient : AnimeThemesApiClient {
        override suspend fun queryAnimeByExternalSite(site: String, externalId: Int): AnimeThemesApiResult {
            return AnimeThemesApiResult.Failure(ApiFailureReason.NETWORK_ERROR, "Network disabled in cache migration test")
        }
    }

    @Test
    fun testV1CacheFixture_FailsOpenWithoutCrashing() = runBlocking {
        val cacheDir = tempFolder.newFolder("v1_cache")
        val cacheFile = File(cacheDir, AnimeThemesRepository.CACHE_FILE_NAME)

        // Legacy V1 cache JSON fixture (version 1, defaultVideo present, no isSubbed/overlap)
        val v1Json = """
            {
                "version": 1,
                "entries": {
                    "anilist_1": {
                        "cacheKey": "anilist_1",
                        "mediaId": 1,
                        "timestamp": ${System.currentTimeMillis()},
                        "isNegativeMatch": false,
                        "payload": {
                            "animeId": 1,
                            "animeSlug": "cowboy_bebop",
                            "openings": [
                                {
                                    "themeId": 10,
                                    "type": "OP",
                                    "sequence": 1,
                                    "displayTag": "OP 1",
                                    "songTitle": "Tank!",
                                    "artistDisplay": "The Seatbelts",
                                    "isFlagged": false,
                                    "defaultVideo": {
                                        "videoId": 100,
                                        "videoUrl": "https://v.animethemes.moe/CowboyBebop-OP1.webm",
                                        "resolution": 720,
                                        "isCreditless": true,
                                        "source": "BD",
                                        "badgeLabel": "720p • BD • NC"
                                    },
                                    "alternateVersions": []
                                }
                            ],
                            "endings": []
                        }
                    }
                }
            }
        """.trimIndent()

        cacheFile.writeText(v1Json, Charsets.UTF_8)

        val repo = AnimeThemesRepository(
            api = FakeFailingApiClient(),
            cacheDirProvider = { cacheDir },
            atomicFileWriterFactory = { StandaloneAtomicFileWriter(it) }
        )

        // Loading must fail open (discard v1 version mismatch) and return null from cache rather than stale v1
        val result = repo.getThemesForMedia(createMedia(1))
        assertNull("V1 cache must be discarded due to version mismatch", result)
    }

    @Test
    fun testCacheVersionMismatch_RejectsUnknownVersion() = runBlocking {
        val cacheDir = tempFolder.newFolder("v3_cache")
        val cacheFile = File(cacheDir, AnimeThemesRepository.CACHE_FILE_NAME)

        // Unknown future version (version 3)
        val v3Json = """
            {
                "version": 3,
                "entries": {
                    "anilist_1": {
                        "cacheKey": "anilist_1",
                        "mediaId": 1,
                        "timestamp": ${System.currentTimeMillis()},
                        "isNegativeMatch": false,
                        "payload": null
                    }
                }
            }
        """.trimIndent()

        cacheFile.writeText(v3Json, Charsets.UTF_8)

        val repo = AnimeThemesRepository(
            api = FakeFailingApiClient(),
            cacheDirProvider = { cacheDir },
            atomicFileWriterFactory = { StandaloneAtomicFileWriter(it) }
        )

        val result = repo.getThemesForMedia(createMedia(1))
        assertNull("Unknown cache version must be discarded", result)
    }

    @Test
    fun testV2Cache_PersistsExplicitSubbedAndOverlap() {
        val originalCache = AnimeThemesDiskCache(
            version = CACHE_VERSION,
            entries = mutableMapOf(
                "anilist_123" to CachedAnimeThemesEntryDto(
                    cacheKey = "anilist_123",
                    mediaId = 123,
                    timestamp = System.currentTimeMillis(),
                    isNegativeMatch = false,
                    payload = AnimeThemesCachePayloadDto(
                        animeId = 123,
                        animeSlug = "test-anime",
                        openings = listOf(
                            ThemeCacheDto(
                                themeId = 1,
                                type = "OP",
                                sequence = 1,
                                displayTag = "OP 1",
                                songTitle = "Test Song",
                                artistDisplay = "Test Artist",
                                alternateVersions = listOf(
                                    ThemeVersionCacheDto(
                                        entryId = 1,
                                        version = 1,
                                        episodes = "1-12",
                                        isNsfw = false,
                                        isSpoiler = false,
                                        videos = listOf(
                                            ThemeVideoCacheDto(
                                                videoId = 101,
                                                videoUrl = "https://v.animethemes.moe/test.webm",
                                                resolution = 1080,
                                                isCreditless = true,
                                                source = "BD",
                                                overlap = "TRANS",
                                                tags = "Lyrics",
                                                isSubbed = true,
                                                badgeLabel = "1080p • BD • NC • Sub"
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                        endings = emptyList()
                    )
                )
            )
        )

        val jsonString = Mapper.json.encodeToString(AnimeThemesDiskCache.serializer(), originalCache)
        val deserialized = Mapper.json.decodeFromString(AnimeThemesDiskCache.serializer(), jsonString)

        assertEquals(CACHE_VERSION, deserialized.version)
        val entry = deserialized.entries["anilist_123"]
        assertNotNull(entry)
        assertEquals("test-anime", entry?.payload?.animeSlug)
        val video = entry?.payload?.openings?.get(0)?.alternateVersions?.get(0)?.videos?.get(0)
        assertNotNull(video)
        assertEquals("TRANS", video?.overlap)
        assertEquals(true, video?.isSubbed)
        assertEquals(1080, video?.resolution)
        assertEquals(true, video?.isCreditless)
    }

    @Test
    fun testPreferenceChange_AltersSelectionWithoutNetworkRequest() {
        val theme = ThemeUiModel(
            themeId = 1,
            type = "OP",
            sequence = 1,
            displayTag = "OP 1",
            songTitle = "Test Song",
            artistDisplay = "Test Artist",
            alternateVersions = listOf(
                ThemeVersionUiModel(
                    entryId = 1,
                    version = 1,
                    episodes = "1-12",
                    isNsfw = false,
                    isSpoiler = false,
                    videos = listOf(
                        ThemeVideoUiModel(
                            videoId = 101,
                            videoUrl = "url-1080",
                            resolution = 1080,
                            isCreditless = true,
                            source = "BD",
                            overlap = null,
                            tags = null,
                            isSubbed = false,
                            badgeLabel = "1080p • BD • NC"
                        ),
                        ThemeVideoUiModel(
                            videoId = 102,
                            videoUrl = "url-720",
                            resolution = 720,
                            isCreditless = true,
                            source = "BD",
                            overlap = null,
                            tags = null,
                            isSubbed = false,
                            badgeLabel = "720p • BD • NC"
                        )
                    )
                )
            )
        )

        val variants = AnimeThemeVideoSelector.extractVariants(theme)

        val pref1080 = ThemePlaybackPreferences(resolution = ResolutionPref.P1080)
        val pref720 = ThemePlaybackPreferences(resolution = ResolutionPref.P720)

        // Instant selection change from same cached data
        assertEquals(101, AnimeThemeVideoSelector.selectPreferredVideo(variants, pref1080)?.videoId)
        assertEquals(102, AnimeThemeVideoSelector.selectPreferredVideo(variants, pref720)?.videoId)
    }
}
