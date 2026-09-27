package ani.dantotsu.download.video

import ani.dantotsu.parsers.Video

/**
 * MAX MERGE M3 (upstream e954d9f1): download-local preferred-resolution
 * matching. Exact-int quality match first (`1080p` ↔ quality 1080), then
 * note/URL substring fallback, then best quality. Never assumes parser
 * tokens: no match simply falls through to the next preference and
 * finally to max quality. Framework-free, unit-proved
 * (DownloadResolutionTest).
 */
fun findBestVideoForDownload(
    videos: List<Video>,
    preferredResolutions: List<String>,
): Video? {
    if (videos.isEmpty()) return null
    if (preferredResolutions.isEmpty()) {
        return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()
    }

    for (preferred in preferredResolutions) {
        val resNumber = Regex("""\d+""").find(preferred)?.value?.toIntOrNull()
        if (resNumber != null) {
            val match = videos.firstOrNull { it.quality == resNumber }
            if (match != null) return match
        }
        val cleanPreferred = preferred.lowercase().replace("p", "").trim()
        val matchFallback = videos.firstOrNull { video ->
            val note = video.extraNote?.lowercase() ?: ""
            val url = video.file.url.lowercase()
            note.contains(preferred.lowercase()) || note.contains(cleanPreferred) ||
                url.contains("${cleanPreferred}p") || url.contains(cleanPreferred)
        }
        if (matchFallback != null) return matchFallback
    }

    return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()
}
