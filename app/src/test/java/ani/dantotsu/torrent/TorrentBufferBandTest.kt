package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TorrentBufferBandTest {

    @Test
    fun playheadMidPieceWalksFromPlayheadPiece() {
        val have = setOf(1, 2)
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 0L,
            fileLength = 5_000L,
            pieceLength = 1_000L,
            playheadByte = 1_400L,
            havePiece = { it in have },
        )
        assertEquals(3_000L, end)
    }

    @Test
    fun playheadMidPieceWithOnlyCurrentPieceDownloadedExtendsToPieceEnd() {
        val have = setOf(1)
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 0L,
            fileLength = 5_000L,
            pieceLength = 1_000L,
            playheadByte = 1_650L,
            havePiece = { it in have },
        )
        assertEquals(2_000L, end)
    }

    @Test
    fun runStopsAtFirstGapAndIgnoresLaterPieces() {
        val have = setOf(0, 1, 3, 4, 5)
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 0L,
            fileLength = 6_000L,
            pieceLength = 1_000L,
            playheadByte = 250L,
            havePiece = { it in have },
        )
        assertEquals(2_000L, end)
    }

    @Test
    fun missingPlayheadPieceReturnsPlayheadByte() {
        val have = setOf(0, 2, 3)
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 0L,
            fileLength = 5_000L,
            pieceLength = 1_000L,
            playheadByte = 1_500L,
            havePiece = { it in have },
        )
        assertEquals(1_500L, end)
    }

    @Test
    fun lastPieceClampsToFileEndAndNeverProbesBeyondLastPiece() {
        // File is 4_300 bytes -> pieces 0..4; piece 4 only holds 300 bytes of this file.
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 0L,
            fileLength = 4_300L,
            pieceLength = 1_000L,
            playheadByte = 0L,
            havePiece = { p ->
                if (p > 4) fail("probed out-of-file piece $p")
                true
            },
        )
        assertEquals(4_300L, end)
    }

    @Test
    fun playheadOnLastPieceClampsToFileEnd() {
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 0L,
            fileLength = 4_300L,
            pieceLength = 1_000L,
            playheadByte = 4_100L,
            havePiece = { it == 4 },
        )
        assertEquals(4_300L, end)
    }

    @Test
    fun fileNotAtOffsetZeroAligned() {
        // File covers [5_000, 8_500) -> pieces 5, 6, 7, 8.
        val have = setOf(5, 6)
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 5_000L,
            fileLength = 3_500L,
            pieceLength = 1_000L,
            playheadByte = 5_200L,
            havePiece = { it in have },
        )
        assertEquals(7_000L, end)
        assertEquals(
            40_000L,
            TorrentBufferBand.endByteToMs(
                endByte = end!!,
                fileOffset = 5_000L,
                fileLength = 3_500L,
                durationMs = 70_000L,
            ),
        )
    }

    @Test
    fun fileNotAtOffsetZeroUnalignedPieceBoundary() {
        // File covers [2_300, 4_800) -> piece 2 ([2000,3000)), piece 3 ([3000,4000)), piece 4 ([4000,5000)).
        val partialHave = setOf(2, 3)
        val partialEnd = TorrentBufferBand.contiguousEndByte(
            fileOffset = 2_300L,
            fileLength = 2_500L,
            pieceLength = 1_000L,
            playheadByte = 2_550L,
            havePiece = { it in partialHave },
        )
        assertEquals(4_000L, partialEnd)
        assertEquals(
            68_000L,
            TorrentBufferBand.endByteToMs(
                endByte = partialEnd!!,
                fileOffset = 2_300L,
                fileLength = 2_500L,
                durationMs = 100_000L,
            ),
        )

        // All pieces of this file downloaded -> clamps to 4_800 and maps to exact duration.
        val fullEnd = TorrentBufferBand.contiguousEndByte(
            fileOffset = 2_300L,
            fileLength = 2_500L,
            pieceLength = 1_000L,
            playheadByte = 2_550L,
            havePiece = { p ->
                if (p > 4) fail("probed out-of-file piece $p")
                p in 2..4
            },
        )
        assertEquals(4_800L, fullEnd)
        assertEquals(
            100_000L,
            TorrentBufferBand.endByteToMs(
                endByte = fullEnd!!,
                fileOffset = 2_300L,
                fileLength = 2_500L,
                durationMs = 100_000L,
            ),
        )
    }

    @Test
    fun fileSmallerThanSinglePiece() {
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 2_300L,
            fileLength = 200L,
            pieceLength = 1_000L,
            playheadByte = 2_350L,
            havePiece = { it == 2 },
        )
        assertEquals(2_500L, end)
    }

    @Test
    fun playheadAtFileEndReturnsFileEndWithoutQueryingPieces() {
        val end = TorrentBufferBand.contiguousEndByte(
            fileOffset = 1_000L,
            fileLength = 2_000L,
            pieceLength = 1_000L,
            playheadByte = 3_000L,
            havePiece = { fail("should not query pieces past file end"); false },
        )
        assertEquals(3_000L, end)
    }

    @Test
    fun zeroLengthAndUnknownInputsReturnNull() {
        val allTrue: (Int) -> Boolean = { true }
        assertNull(TorrentBufferBand.contiguousEndByte(0L, 0L, 1_000L, 0L, allTrue))
        assertNull(TorrentBufferBand.contiguousEndByte(0L, -10L, 1_000L, 0L, allTrue))
        assertNull(TorrentBufferBand.contiguousEndByte(0L, 1_000L, 0L, 0L, allTrue))
        assertNull(TorrentBufferBand.contiguousEndByte(0L, 1_000L, -5L, 0L, allTrue))
        assertNull(TorrentBufferBand.contiguousEndByte(-1L, 1_000L, 1_000L, 0L, allTrue))
        assertNull(TorrentBufferBand.contiguousEndByte(500L, 1_000L, 1_000L, 499L, allTrue))

        assertNull(TorrentBufferBand.endByteToMs(500L, 0L, 0L, 10_000L))
        assertNull(TorrentBufferBand.endByteToMs(500L, 0L, 1_000L, 0L))
        assertNull(TorrentBufferBand.endByteToMs(500L, 0L, 1_000L, -1L))
        assertNull(TorrentBufferBand.endByteToMs(500L, -1L, 1_000L, 10_000L))
        assertNull(TorrentBufferBand.endByteToMs(400L, 500L, 1_000L, 10_000L))

        assertNull(TorrentBufferBand.positionMsToTorrentByte(-1L, 10_000L, 0L, 1_000L))
        assertNull(TorrentBufferBand.positionMsToTorrentByte(0L, 0L, 0L, 1_000L))
        assertNull(TorrentBufferBand.positionMsToTorrentByte(0L, 10_000L, 0L, 0L))
        assertNull(TorrentBufferBand.positionMsToTorrentByte(0L, 10_000L, -1L, 1_000L))

        assertNull(TorrentBufferBand.bufferedEndMs(0L, 0L, 1_000L, 0L, 10_000L, allTrue))
        assertNull(TorrentBufferBand.bufferedEndMs(0L, 1_000L, 0L, 0L, 10_000L, allTrue))
        assertNull(TorrentBufferBand.bufferedEndMs(0L, 1_000L, 1_000L, 0L, 0L, allTrue))
        assertNull(TorrentBufferBand.bufferedEndMs(0L, 1_000L, 1_000L, -10L, 10_000L, allTrue))
    }

    @Test
    fun endByteToMsMapsLinearlyAndClamps() {
        assertEquals(0L, TorrentBufferBand.endByteToMs(1_000L, 1_000L, 4_000L, 120_000L))
        assertEquals(30_000L, TorrentBufferBand.endByteToMs(2_000L, 1_000L, 4_000L, 120_000L))
        assertEquals(120_000L, TorrentBufferBand.endByteToMs(5_000L, 1_000L, 4_000L, 120_000L))
        // Clamped if endByte exceeds fileEnd.
        assertEquals(120_000L, TorrentBufferBand.endByteToMs(9_000L, 1_000L, 4_000L, 120_000L))
    }

    @Test
    fun endByteToMsHandlesLargeFilesWithoutOverflow() {
        val fiftyGb = 50L * 1024 * 1024 * 1024
        val fourHoursMs = 4L * 60 * 60 * 1_000
        val halfFile = fiftyGb / 2
        assertEquals(
            fourHoursMs / 2,
            TorrentBufferBand.endByteToMs(
                endByte = 10_000L + halfFile,
                fileOffset = 10_000L,
                fileLength = fiftyGb,
                durationMs = fourHoursMs,
            ),
        )
    }

    @Test
    fun bufferedEndMsEndToEnd() {
        // Unaligned file [2_300, 4_800) (len 2_500), 100s episode, playhead at 10s -> byte 2_550 (piece 2).
        val have = setOf(2, 3)
        assertEquals(
            68_000L,
            TorrentBufferBand.bufferedEndMs(
                fileOffset = 2_300L,
                fileLength = 2_500L,
                pieceLength = 1_000L,
                positionMs = 10_000L,
                durationMs = 100_000L,
                havePiece = { it in have },
            ),
        )

        // Missing piece at playhead -> returns playhead time (0 ahead).
        assertEquals(
            10_000L,
            TorrentBufferBand.bufferedEndMs(
                fileOffset = 2_300L,
                fileLength = 2_500L,
                pieceLength = 1_000L,
                positionMs = 10_000L,
                durationMs = 100_000L,
                havePiece = { it == 3 },
            ),
        )

        // Fully downloaded file -> full duration.
        assertEquals(
            100_000L,
            TorrentBufferBand.bufferedEndMs(
                fileOffset = 2_300L,
                fileLength = 2_500L,
                pieceLength = 1_000L,
                positionMs = 10_000L,
                durationMs = 100_000L,
                havePiece = { true },
            ),
        )
    }
}
