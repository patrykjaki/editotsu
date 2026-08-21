package ani.dantotsu.media.anime.player

import java.net.URI

sealed interface SubtitleSelection {
    data object Off : SubtitleSelection
    data class Embedded(val stableTrackKey: String) : SubtitleSelection
    data class External(val stableSubtitleKey: String) : SubtitleSelection
    data class Auto(val preferredLanguage: String?) : SubtitleSelection
}

object SubtitleSelectionResolver {

    fun parsePreference(pref: String?): SubtitleSelection {
        if (pref == null) return SubtitleSelection.Auto(null)
        val trimmed = pref.trim()
        return when {
            trimmed.equals("None", ignoreCase = true) || trimmed.equals("Off", ignoreCase = true) -> {
                SubtitleSelection.Off
            }
            trimmed.startsWith("Embedded:", ignoreCase = true) -> {
                val sub = trimmed.substringAfter("Embedded:").trim()
                if (sub.isBlank()) SubtitleSelection.Off else SubtitleSelection.Embedded(sub)
            }
            trimmed.startsWith("External:", ignoreCase = true) -> {
                val sub = trimmed.substringAfter("External:").trim()
                if (sub.isBlank()) SubtitleSelection.Off else SubtitleSelection.External(sub)
            }
            else -> {
                SubtitleSelection.Auto(trimmed)
            }
        }
    }

    fun resolve(selection: SubtitleSelection, tracks: List<PlayerTrack>): Int? {
        val subTracks = tracks.filter { it.type == TrackType.SUBTITLE }
        if (subTracks.isEmpty()) return null

        return when (selection) {
            is SubtitleSelection.Off -> null
            is SubtitleSelection.Embedded -> {
                val match = subTracks.firstOrNull { track ->
                    track.id.toString() == selection.stableTrackKey ||
                            track.name.equals(selection.stableTrackKey, ignoreCase = true) ||
                            track.language.equals(selection.stableTrackKey, ignoreCase = true)
                }
                match?.id
            }
            is SubtitleSelection.External -> {
                val match = subTracks.firstOrNull { track ->
                    track.id.toString() == selection.stableSubtitleKey ||
                            track.name?.contains(selection.stableSubtitleKey, ignoreCase = true) == true ||
                            track.language.equals(selection.stableSubtitleKey, ignoreCase = true)
                }
                match?.id
            }
            is SubtitleSelection.Auto -> {
                val lang = selection.preferredLanguage?.trim()
                if (lang.isNullOrBlank()) {
                    subTracks.firstOrNull { it.default }?.id ?: subTracks.firstOrNull()?.id
                } else {
                    val matchingTracks = subTracks.filter {
                        it.language.equals(lang, ignoreCase = true) || it.name?.contains(lang, ignoreCase = true) == true
                    }
                    matchingTracks.firstOrNull { it.default }?.id ?: matchingTracks.firstOrNull()?.id ?: subTracks.firstOrNull { it.default }?.id ?: subTracks.firstOrNull()?.id
                }
            }
        }
    }

    fun resolveSubtitleUri(subUrl: String, embedUrl: String?, mediaUrl: String?): String {
        val trimmed = subUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("file://") || trimmed.startsWith("content://")
        ) {
            return trimmed
        }

        if (trimmed.startsWith("//")) {
            val baseScheme = when {
                embedUrl?.startsWith("https://") == true -> "https:"
                embedUrl?.startsWith("http://") == true -> "http:"
                mediaUrl?.startsWith("https://") == true -> "https:"
                else -> "https:"
            }
            return "$baseScheme$trimmed"
        }

        val baseUriStr = embedUrl ?: mediaUrl
        if (baseUriStr != null) {
            try {
                val base = URI(baseUriStr)
                return base.resolve(trimmed).toString()
            } catch (_: Exception) {}
        }

        return trimmed
    }
}
