package ani.dantotsu.media.anime.selector

import ani.dantotsu.media.anime.Episode

/**
 * Keeps loaded streams when one [Episode] object replaces another for the same key.
 *
 * The episode list is first shown from the cache and then replaced by the fresh list.
 * The picker may already have loaded videos into the cached object, and the selection
 * may have been written onto either object. Without this the player reads the fresh
 * object, finds no extractors and does not start.
 */
object EpisodeStreamCarryOver {

    /**
     * Copies stream state from [from] onto [into] where [into] has none:
     * the extractor list (with its `allStreams` flag) and the selected extractor
     * (with its video index). State already on [into] is kept.
     */
    fun carry(from: Episode, into: Episode) {
        if (from === into) return
        val loaded = from.extractors
        if (!loaded.isNullOrEmpty() && into.extractors.isNullOrEmpty()) {
            into.extractors = loaded
            into.allStreams = from.allStreams
        }
        if (into.selectedExtractor == null && from.selectedExtractor != null) {
            into.selectedExtractor = from.selectedExtractor
            into.selectedVideo = from.selectedVideo
        }
    }

    /** Applies [carry] from each old episode to the fresh one with the same key. */
    fun carryAll(old: Map<String, Episode>?, fresh: Map<String, Episode>) {
        if (old == null || old === fresh) return
        for ((key, freshEpisode) in fresh) {
            val oldEpisode = old[key] ?: continue
            carry(oldEpisode, freshEpisode)
        }
    }
}
