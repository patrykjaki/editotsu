package ani.dantotsu.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextTest {

    private val daySec = 24L * 60 * 60
    private val nowSec = 1_760_000_000L

    private fun sequel(
        targetId: Int,
        isAnime: Boolean = true,
        mediaStatus: WatchNextMediaStatus = WatchNextMediaStatus.FINISHED,
        isAdult: Boolean = false,
    ) = WatchNextRelation(
        targetId = targetId,
        relationType = "SEQUEL",
        isAnime = isAnime,
        mediaStatus = mediaStatus,
        isAdult = isAdult,
    )

    @Test
    fun `completed show with a sequel not on the list lands in tier 1`() {
        val completed = listOf(
            CompletedAnimeEntry(
                mediaId = 100,
                title = "Frieren",
                completedAtSec = nowSec - 10 * daySec,
                relations = listOf(sequel(101)),
            )
        )

        val result = buildWatchNext(
            completed = completed,
            planned = emptyList(),
            nowEpochSec = nowSec,
        )

        assertEquals(
            listOf(
                WatchNextCandidate(
                    mediaId = 101,
                    tier = WatchNextTier.RECENT_SEQUEL,
                    reason = WatchNextReason.SequelTo("Frieren"),
                )
            ),
            result,
        )
    }

    @Test
    fun `sequel already watching, rewatching, completed, dropped or paused is excluded`() {
        val completed = listOf(
            CompletedAnimeEntry(
                mediaId = 1,
                title = "Show A",
                completedAtSec = nowSec - 5 * daySec,
                relations = listOf(
                    sequel(10),
                    sequel(11),
                    sequel(12),
                    sequel(13),
                    sequel(14),
                    sequel(15),
                ),
            )
        )
        val statuses = mapOf(
            1 to WatchNextListStatus.COMPLETED,
            10 to WatchNextListStatus.CURRENT,
            11 to WatchNextListStatus.REPEATING,
            12 to WatchNextListStatus.COMPLETED,
            13 to WatchNextListStatus.DROPPED,
            14 to WatchNextListStatus.PAUSED,
            15 to WatchNextListStatus.PLANNING, // not started yet -> allowed
        )

        val result = buildWatchNext(
            completed = completed,
            planned = emptyList(),
            userStatuses = statuses,
            nowEpochSec = nowSec,
        )

        assertEquals(listOf(15), result.map { it.mediaId })
        assertEquals(WatchNextTier.RECENT_SEQUEL, result.single().tier)
    }

    @Test
    fun `tiers are ordered recent sequels, then older missed sequels, then related planning`() {
        val completed = listOf(
            CompletedAnimeEntry(
                mediaId = 1,
                title = "Old Show",
                completedAtSec = nowSec - 200 * daySec,
                relations = listOf(sequel(200)),
            ),
            CompletedAnimeEntry(
                mediaId = 2,
                title = "Recent Show",
                completedAtSec = nowSec - 14 * daySec,
                relations = listOf(sequel(100)),
            ),
        )
        val planned = listOf(
            PlannedAnimeEntry(
                mediaId = 300,
                updatedAtSec = nowSec - 2 * daySec,
                relations = listOf(
                    WatchNextRelation(targetId = 2, relationType = "SIDE_STORY", isAnime = true)
                ),
            ),
            // Unrelated planning entry must NOT appear in Watch next
            PlannedAnimeEntry(
                mediaId = 999,
                updatedAtSec = nowSec - 1 * daySec,
                relations = emptyList(),
            ),
        )

        val result = buildWatchNext(
            completed = completed,
            planned = planned,
            nowEpochSec = nowSec,
        )

        assertEquals(
            listOf(
                WatchNextCandidate(100, WatchNextTier.RECENT_SEQUEL, WatchNextReason.SequelTo("Recent Show")),
                WatchNextCandidate(200, WatchNextTier.OLDER_SEQUEL, WatchNextReason.SequelTo("Old Show")),
                WatchNextCandidate(300, WatchNextTier.RELATED_PLANNING, WatchNextReason.OnYourList),
            ),
            result,
        )
    }

    @Test
    fun `same entry from two tiers appears once with the higher priority tier`() {
        val completed = listOf(
            CompletedAnimeEntry(
                mediaId = 1,
                title = "Frieren",
                completedAtSec = nowSec - 7 * daySec,
                relations = listOf(sequel(500)),
            ),
            CompletedAnimeEntry(
                mediaId = 2,
                title = "Frieren Special",
                completedAtSec = nowSec - 180 * daySec,
                relations = listOf(sequel(500)),
            ),
        )
        val planned = listOf(
            PlannedAnimeEntry(
                mediaId = 500,
                updatedAtSec = nowSec - 1 * daySec,
                relations = listOf(
                    WatchNextRelation(targetId = 1, relationType = "PREQUEL", isAnime = true)
                ),
            )
        )

        val result = buildWatchNext(
            completed = completed,
            planned = planned,
            nowEpochSec = nowSec,
        )

        assertEquals(1, result.size)
        assertEquals(
            WatchNextCandidate(500, WatchNextTier.RECENT_SEQUEL, WatchNextReason.SequelTo("Frieren")),
            result.single(),
        )
    }

    @Test
    fun `excludes non-anime, unreleased, cancelled, adult when disallowed, hidden and private entries`() {
        val completed = listOf(
            CompletedAnimeEntry(
                mediaId = 1,
                title = "Base",
                completedAtSec = nowSec - 5 * daySec,
                relations = listOf(
                    sequel(10, isAnime = false),
                    sequel(11, mediaStatus = WatchNextMediaStatus.NOT_YET_RELEASED),
                    sequel(12, mediaStatus = WatchNextMediaStatus.CANCELLED),
                    sequel(13, isAdult = true),
                    sequel(14), // hidden via removeList
                    sequel(15, mediaStatus = WatchNextMediaStatus.RELEASING), // valid
                ),
            )
        )
        val planned = listOf(
            PlannedAnimeEntry(
                mediaId = 20,
                isPrivate = true,
                relations = listOf(WatchNextRelation(targetId = 1, relationType = "SPIN_OFF", isAnime = true)),
            )
        )

        val result = buildWatchNext(
            completed = completed,
            planned = planned,
            nowEpochSec = nowSec,
            excludedMediaIds = setOf(14),
            hidePrivate = true,
            allowAdult = false,
        )

        assertEquals(listOf(15), result.map { it.mediaId })
    }

    @Test
    fun `caps merged output at maxItems default 15`() {
        val completed = (1..25).map { idx ->
            CompletedAnimeEntry(
                mediaId = idx,
                title = "Show $idx",
                completedAtSec = nowSec - idx * daySec,
                relations = listOf(sequel(1000 + idx)),
            )
        }

        val result = buildWatchNext(
            completed = completed,
            planned = emptyList(),
            nowEpochSec = nowSec,
        )

        assertEquals(WatchNextDefaults.MAX_ITEMS, result.size)
        assertEquals((1001..1015).toList(), result.map { it.mediaId })
    }

    @Test
    fun `tier 3 picks up planning entry related to currently watching show`() {
        val planned = listOf(
            PlannedAnimeEntry(
                mediaId = 77,
                updatedAtSec = nowSec - 3 * daySec,
                relations = listOf(
                    WatchNextRelation(targetId = 55, relationType = "SPIN_OFF", isAnime = true)
                ),
            )
        )
        val statuses = mapOf(
            55 to WatchNextListStatus.CURRENT,
            77 to WatchNextListStatus.PLANNING,
        )

        val result = buildWatchNext(
            completed = emptyList(),
            planned = planned,
            userStatuses = statuses,
            nowEpochSec = nowSec,
        )

        assertEquals(
            listOf(WatchNextCandidate(77, WatchNextTier.RELATED_PLANNING, WatchNextReason.OnYourList)),
            result,
        )
    }

    @Test
    fun `completedTimestampSec prefers completedAt date and falls back to updatedAt`() {
        val withDate = completedTimestampSec(2026, 11, 10, updatedAtSec = 100L)
        val fallback = completedTimestampSec(null, null, null, updatedAtSec = 1_750_000_000L)
        val unknown = completedTimestampSec(null, null, null, updatedAtSec = null)

        assertTrue(withDate > 1_700_000_000L)
        assertEquals(1_750_000_000L, fallback)
        assertEquals(0L, unknown)
    }

    @Test
    fun `empty result hides the watch next row`() {
        val candidates = buildWatchNext(
            completed = emptyList(),
            planned = emptyList(),
            nowEpochSec = nowSec,
        )
        assertTrue(candidates.isEmpty())

        val vis = watchNextRowVisibility(
            items = candidates,
            enabled = true,
            rescueMode = false,
        )
        assertFalse(vis.containerVisible)
        assertFalse(vis.progressVisible)
        assertFalse(vis.recyclerVisible)
    }

    @Test
    fun `visibility contract handles loading, non-empty, disabled and rescue mode`() {
        assertEquals(
            WatchNextVisibility(containerVisible = true, progressVisible = true, recyclerVisible = false),
            watchNextRowVisibility(items = null, enabled = true, rescueMode = false),
        )
        assertEquals(
            WatchNextVisibility(containerVisible = true, progressVisible = false, recyclerVisible = true),
            watchNextRowVisibility(items = listOf(1), enabled = true, rescueMode = false),
        )
        assertEquals(
            WatchNextVisibility(containerVisible = false, progressVisible = false, recyclerVisible = false),
            watchNextRowVisibility(items = listOf(1), enabled = false, rescueMode = false),
        )
        assertEquals(
            WatchNextVisibility(containerVisible = false, progressVisible = false, recyclerVisible = false),
            watchNextRowVisibility(items = null, enabled = true, rescueMode = true),
        )
        assertEquals(
            WatchNextVisibility(containerVisible = false, progressVisible = false, recyclerVisible = false),
            watchNextRowVisibility(items = listOf(1), enabled = true, rescueMode = true),
        )
    }
}
