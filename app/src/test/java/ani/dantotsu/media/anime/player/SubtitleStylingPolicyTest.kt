package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beta07 subtitle-styling policy contract.
 *
 * Pins the full decision matrix for [shouldUseSourceSubtitleStyling],
 * playback-origin classification, and legacy migration. Policy-only:
 * these tests must never touch subtitle URL/header transport, CP3
 * selection, external audio, ExtensionMpvArgs, or mpv bootstrap options
 * (asserted structurally by the absence of those collaborators here —
 * this test depends only on the pure policy unit).
 */
class SubtitleStylingPolicyTest {

    private val allOrigins = PlaybackOrigin.entries.toList()

    @Test
    fun auto_extensionUsesEditotsu_localOriginsUseSource() {
        assertFalse(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.AUTO_BY_SOURCE,
                PlaybackOrigin.EXTENSION_STREAM
            )
        )
        assertTrue(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.AUTO_BY_SOURCE,
                PlaybackOrigin.TORRENT
            )
        )
        assertTrue(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.AUTO_BY_SOURCE,
                PlaybackOrigin.DOWNLOAD
            )
        )
        assertTrue(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.AUTO_BY_SOURCE,
                PlaybackOrigin.LOCAL
            )
        )
    }

    @Test
    fun alwaysEditotsu_isFalseForAllOrigins() {
        for (origin in allOrigins) {
            assertFalse(
                "EDITOTSU_ALWAYS must be false for $origin",
                shouldUseSourceSubtitleStyling(SubtitleStylingMode.EDITOTSU_ALWAYS, origin)
            )
        }
    }

    @Test
    fun alwaysSource_isTrueForAllOrigins() {
        for (origin in allOrigins) {
            assertTrue(
                "SOURCE_ALWAYS must be true for $origin",
                shouldUseSourceSubtitleStyling(SubtitleStylingMode.SOURCE_ALWAYS, origin)
            )
        }
    }

    @Test
    fun custom_respectsEachIndependentSetting() {
        val streamSource = CustomStylingPrefs(streamingUseSource = true, localUseSource = false)
        assertTrue(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.CUSTOM, PlaybackOrigin.EXTENSION_STREAM, streamSource
            )
        )
        for (origin in listOf(PlaybackOrigin.TORRENT, PlaybackOrigin.DOWNLOAD, PlaybackOrigin.LOCAL)) {
            assertFalse(
                "CUSTOM with localUseSource=false must be false for $origin",
                shouldUseSourceSubtitleStyling(SubtitleStylingMode.CUSTOM, origin, streamSource)
            )
        }

        val localSource = CustomStylingPrefs(streamingUseSource = false, localUseSource = true)
        assertFalse(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.CUSTOM, PlaybackOrigin.EXTENSION_STREAM, localSource
            )
        )
        for (origin in listOf(PlaybackOrigin.TORRENT, PlaybackOrigin.DOWNLOAD, PlaybackOrigin.LOCAL)) {
            assertTrue(
                "CUSTOM with localUseSource=true must be true for $origin",
                shouldUseSourceSubtitleStyling(SubtitleStylingMode.CUSTOM, origin, localSource)
            )
        }
    }

    @Test
    fun custom_defaults_streamEditotsu_localSource() {
        val defaults = CustomStylingPrefs()
        assertFalse(defaults.streamingUseSource)
        assertTrue(defaults.localUseSource)
        assertFalse(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.CUSTOM, PlaybackOrigin.EXTENSION_STREAM, defaults
            )
        )
        assertTrue(
            shouldUseSourceSubtitleStyling(
                SubtitleStylingMode.CUSTOM, PlaybackOrigin.TORRENT, defaults
            )
        )
    }

    @Test
    fun migration_preservesLegacyBehavior_neverAuto() {
        assertEquals(
            SubtitleStylingMode.SOURCE_ALWAYS,
            migrateLegacyStylingMode(legacyPresent = true, legacyValue = true)
        )
        assertEquals(
            SubtitleStylingMode.EDITOTSU_ALWAYS,
            migrateLegacyStylingMode(legacyPresent = true, legacyValue = false)
        )
        assertEquals(
            SubtitleStylingMode.AUTO_BY_SOURCE,
            migrateLegacyStylingMode(legacyPresent = false, legacyValue = true)
        )
        assertEquals(
            SubtitleStylingMode.AUTO_BY_SOURCE,
            migrateLegacyStylingMode(legacyPresent = false, legacyValue = false)
        )
    }

    @Test
    fun originClassification_usesPlaybackOrigin() {
        assertEquals(
            PlaybackOrigin.TORRENT,
            originFromSourceClass(PlaybackSourceClass.TORRENT_LOCALHOST, isDownload = false)
        )
        // Torrent identity wins even if a download flag were set.
        assertEquals(
            PlaybackOrigin.TORRENT,
            originFromSourceClass(PlaybackSourceClass.TORRENT_LOCALHOST, isDownload = true)
        )
        assertEquals(
            PlaybackOrigin.EXTENSION_STREAM,
            originFromSourceClass(PlaybackSourceClass.HLS, isDownload = false)
        )
        assertEquals(
            PlaybackOrigin.EXTENSION_STREAM,
            originFromSourceClass(PlaybackSourceClass.DIRECT_HTTP, isDownload = false)
        )
        assertEquals(
            PlaybackOrigin.DOWNLOAD,
            originFromSourceClass(PlaybackSourceClass.LOCAL_FILE, isDownload = true)
        )
        assertEquals(
            PlaybackOrigin.DOWNLOAD,
            originFromSourceClass(PlaybackSourceClass.CONTENT_FD, isDownload = true)
        )
        // A downloaded stream URL still classifies by playback origin.
        assertEquals(
            PlaybackOrigin.DOWNLOAD,
            originFromSourceClass(PlaybackSourceClass.DIRECT_HTTP, isDownload = true)
        )
        assertEquals(
            PlaybackOrigin.LOCAL,
            originFromSourceClass(PlaybackSourceClass.LOCAL_FILE, isDownload = false)
        )
        assertEquals(
            PlaybackOrigin.LOCAL,
            originFromSourceClass(PlaybackSourceClass.CONTENT_FD, isDownload = false)
        )
    }

    @Test
    fun modeIntRoundTrip_unknownFallsBackToAuto() {
        for (mode in SubtitleStylingMode.entries) {
            assertEquals(mode, subtitleStylingModeFromInt(mode.ordinal))
        }
        assertEquals(SubtitleStylingMode.AUTO_BY_SOURCE, subtitleStylingModeFromInt(-1))
        assertEquals(SubtitleStylingMode.AUTO_BY_SOURCE, subtitleStylingModeFromInt(99))
    }

    @Test
    fun resolver_isPure_sameInputsSameOutputs() {
        val custom = CustomStylingPrefs(streamingUseSource = true, localUseSource = true)
        for (mode in SubtitleStylingMode.entries) {
            for (origin in allOrigins) {
                val first = shouldUseSourceSubtitleStyling(mode, origin, custom)
                val second = shouldUseSourceSubtitleStyling(mode, origin, custom.copy())
                assertEquals("resolver must be pure for $mode/$origin", first, second)
            }
        }
    }
}
