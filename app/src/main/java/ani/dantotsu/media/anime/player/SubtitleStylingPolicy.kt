package ani.dantotsu.media.anime.player

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Subtitle-styling policy (beta07 UX refinement).
 *
 * The old single global `UseSourceSubtitleStyling` boolean forced one
 * behavior everywhere: streaming extensions usually want Editotsu's
 * consistent configured fonts (which beta05/beta06 made render
 * correctly), while torrents/downloads/local files usually want the
 * original ASS/SSA source styling (typesetting, signs, effects).
 *
 * This file centralizes the entire policy in pure functions. Player code
 * must NOT scatter source-type conditionals; it resolves one boolean via
 * [shouldUseSourceSubtitleStyling] and applies it.
 *
 * Constraints inherited from the beta05 field incident:
 * - Settings/policy ONLY. No mpv bootstrap/init-option changes.
 * - No changes to subtitle URL/header transport, CP3 selection state,
 *   external audio behavior, or ExtensionMpvArgs.
 */
enum class SubtitleStylingMode {
    AUTO_BY_SOURCE,
    EDITOTSU_ALWAYS,
    SOURCE_ALWAYS,
    CUSTOM
}

/**
 * Playback origin. Classified by WHERE playback comes from, never by
 * whether an individual subtitle URL happens to be remote or local.
 */
enum class PlaybackOrigin {
    EXTENSION_STREAM,
    TORRENT,
    DOWNLOAD,
    LOCAL
}

data class CustomStylingPrefs(
    val streamingUseSource: Boolean = false,
    val localUseSource: Boolean = true
)

/**
 * Maps the existing transport-level [PlaybackSourceClass] plus the
 * download-launch signal (`ext.server.offline` in ExoplayerView) to a
 * policy [PlaybackOrigin]. Reuses the existing classification instead of
 * introducing parallel identity logic.
 */
fun originFromSourceClass(
    sourceClass: PlaybackSourceClass,
    isDownload: Boolean
): PlaybackOrigin = when {
    sourceClass == PlaybackSourceClass.TORRENT_LOCALHOST -> PlaybackOrigin.TORRENT
    isDownload -> PlaybackOrigin.DOWNLOAD
    sourceClass == PlaybackSourceClass.HLS ||
        sourceClass == PlaybackSourceClass.DIRECT_HTTP -> PlaybackOrigin.EXTENSION_STREAM
    else -> PlaybackOrigin.LOCAL
}

/**
 * Pure policy resolver. Returns true when original source (ASS/SSA)
 * styling must be preserved, false when Editotsu's configured styling
 * must be applied.
 */
fun shouldUseSourceSubtitleStyling(
    mode: SubtitleStylingMode,
    origin: PlaybackOrigin,
    custom: CustomStylingPrefs = CustomStylingPrefs()
): Boolean = when (mode) {
    SubtitleStylingMode.EDITOTSU_ALWAYS -> false
    SubtitleStylingMode.SOURCE_ALWAYS -> true
    SubtitleStylingMode.CUSTOM -> when (origin) {
        PlaybackOrigin.EXTENSION_STREAM -> custom.streamingUseSource
        PlaybackOrigin.TORRENT,
        PlaybackOrigin.DOWNLOAD,
        PlaybackOrigin.LOCAL -> custom.localUseSource
    }
    SubtitleStylingMode.AUTO_BY_SOURCE -> when (origin) {
        PlaybackOrigin.EXTENSION_STREAM -> false
        PlaybackOrigin.TORRENT,
        PlaybackOrigin.DOWNLOAD,
        PlaybackOrigin.LOCAL -> true
    }
}

/**
 * Legacy migration (pure). Existing users MUST retain behavior:
 * - legacy key present + true  -> SOURCE_ALWAYS
 * - legacy key present + false -> EDITOTSU_ALWAYS
 * - legacy key absent (new install) -> AUTO_BY_SOURCE
 *
 * Never auto-migrate existing users into AUTO.
 */
fun migrateLegacyStylingMode(
    legacyPresent: Boolean,
    legacyValue: Boolean
): SubtitleStylingMode = when {
    !legacyPresent -> SubtitleStylingMode.AUTO_BY_SOURCE
    legacyValue -> SubtitleStylingMode.SOURCE_ALWAYS
    else -> SubtitleStylingMode.EDITOTSU_ALWAYS
}

fun subtitleStylingModeFromInt(value: Int): SubtitleStylingMode =
    SubtitleStylingMode.entries.getOrElse(value) { SubtitleStylingMode.AUTO_BY_SOURCE }

/**
 * Android glue over [PrefManager]. Thin by design: all decisions live in
 * the pure functions above so unit tests pin the policy without Android.
 */
object SubtitleStylingStore {

    fun currentMode(): SubtitleStylingMode {
        if (PrefManager.contains(PrefName.SubtitleStylingMode)) {
            return subtitleStylingModeFromInt(PrefManager.getVal<Int>(PrefName.SubtitleStylingMode))
        }
        if (PrefManager.contains(PrefName.UseSourceSubtitleStyling)) {
            val migrated = migrateLegacyStylingMode(
                legacyPresent = true,
                legacyValue = PrefManager.getVal<Boolean>(PrefName.UseSourceSubtitleStyling)
            )
            PrefManager.setVal(PrefName.SubtitleStylingMode, migrated.ordinal)
            PrefManager.setVal<Boolean>(PrefName.UseSourceSubtitleStyling, null)
            return migrated
        }
        PrefManager.setVal(PrefName.SubtitleStylingMode, SubtitleStylingMode.AUTO_BY_SOURCE.ordinal)
        return SubtitleStylingMode.AUTO_BY_SOURCE
    }

    fun setMode(mode: SubtitleStylingMode) {
        PrefManager.setVal(PrefName.SubtitleStylingMode, mode.ordinal)
    }

    fun customPrefs(): CustomStylingPrefs = CustomStylingPrefs(
        streamingUseSource = PrefManager.getVal<Boolean>(PrefName.CustomStreamSourceStyling),
        localUseSource = PrefManager.getVal<Boolean>(PrefName.CustomLocalSourceStyling)
    )

    fun shouldUseSource(origin: PlaybackOrigin): Boolean =
        shouldUseSourceSubtitleStyling(currentMode(), origin, customPrefs())
}
