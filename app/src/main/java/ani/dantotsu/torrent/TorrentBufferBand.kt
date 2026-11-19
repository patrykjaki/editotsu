package ani.dantotsu.torrent

/**
 * Snapshot of how far the contiguous downloaded run extends in a torrent file.
 */
data class FileDownloadSpan(
    val fileOffset: Long,
    val fileLength: Long,
    val contiguousEndByte: Long,
) {
    /** Contiguous downloaded bytes measured from the start of this file. */
    val downloadedBytesInFile: Long
        get() = (contiguousEndByte - fileOffset).coerceIn(0L, fileLength)
}

/**
 * Pure math for the seek-bar download band on a torrent stream.
 *
 * Walks the contiguous run of downloaded pieces starting at the playhead's piece,
 * stops at the first missing piece, clamps to the file end, and maps that byte
 * boundary linearly onto the episode duration.
 */
object TorrentBufferBand {

    /**
     * End byte (in torrent byte coordinates, exclusive) of the contiguous downloaded run
     * starting at [playheadByte]'s piece, clamped to `[fileOffset, fileOffset + fileLength]`.
     *
     * Returns `null` for unknown or invalid inputs (`fileOffset < 0`, `fileLength <= 0`,
     * `pieceLength <= 0`, or `playheadByte < fileOffset`).
     * When the piece containing [playheadByte] is not downloaded yet, returns [playheadByte]
     * clamped to the file end (zero contiguous bytes ahead of the playhead).
     */
    fun contiguousEndByte(
        fileOffset: Long,
        fileLength: Long,
        pieceLength: Long,
        playheadByte: Long,
        havePiece: (Int) -> Boolean,
    ): Long? {
        if (fileOffset < 0L || fileLength <= 0L || pieceLength <= 0L) return null
        if (fileOffset > Long.MAX_VALUE - fileLength) return null
        if (playheadByte < fileOffset) return null

        val fileEnd = fileOffset + fileLength
        if (playheadByte >= fileEnd) return fileEnd

        val startPiece = playheadByte / pieceLength
        val lastPiece = (fileEnd - 1L) / pieceLength
        if (startPiece > Int.MAX_VALUE || lastPiece > Int.MAX_VALUE) return null

        var piece = startPiece
        while (piece <= lastPiece && havePiece(piece.toInt())) {
            piece++
        }
        if (piece == startPiece) return playheadByte
        if (piece > lastPiece) return fileEnd
        val runEnd = piece * pieceLength
        return minOf(fileEnd, runEnd)
    }

    /**
     * Linearly maps [endByte] (torrent byte coordinate) inside `[fileOffset, fileOffset + fileLength]`
     * to milliseconds in `[0, durationMs]`: `durationMs * (endByte - fileOffset) / fileLength`.
     *
     * Returns `null` for unknown or invalid inputs.
     */
    fun endByteToMs(
        endByte: Long,
        fileOffset: Long,
        fileLength: Long,
        durationMs: Long,
    ): Long? {
        if (fileOffset < 0L || fileLength <= 0L || durationMs <= 0L || endByte < fileOffset) return null
        val bytesIntoFile = (endByte - fileOffset).coerceAtMost(fileLength)
        if (bytesIntoFile >= fileLength) return durationMs
        return mulDiv(durationMs, bytesIntoFile, fileLength)
    }

    /**
     * Linearly maps playback [positionMs] in `[0, durationMs]` to a torrent byte coordinate
     * inside `[fileOffset, fileOffset + fileLength]`.
     *
     * Returns `null` for unknown or invalid inputs.
     */
    fun positionMsToTorrentByte(
        positionMs: Long,
        durationMs: Long,
        fileOffset: Long,
        fileLength: Long,
    ): Long? {
        if (fileOffset < 0L || fileLength <= 0L || durationMs <= 0L || positionMs < 0L) return null
        if (fileOffset > Long.MAX_VALUE - fileLength) return null
        val clampedPos = positionMs.coerceAtMost(durationMs)
        val offsetInFile = if (clampedPos >= durationMs) fileLength else mulDiv(fileLength, clampedPos, durationMs)
        return fileOffset + offsetInFile
    }

    /**
     * End-to-end helper: maps [positionMs] linearly to a byte inside the file, walks the
     * contiguous downloaded pieces starting at that piece, and maps the run end back to ms.
     */
    fun bufferedEndMs(
        fileOffset: Long,
        fileLength: Long,
        pieceLength: Long,
        positionMs: Long,
        durationMs: Long,
        havePiece: (Int) -> Boolean,
    ): Long? {
        val playheadByte = positionMsToTorrentByte(positionMs, durationMs, fileOffset, fileLength) ?: return null
        val endByte = contiguousEndByte(fileOffset, fileLength, pieceLength, playheadByte, havePiece) ?: return null
        return endByteToMs(endByte, fileOffset, fileLength, durationMs)
    }

    private fun mulDiv(a: Long, b: Long, d: Long): Long {
        if (a == 0L || b == 0L) return 0L
        if (b <= Long.MAX_VALUE / a) return (a * b) / d
        return (a.toDouble() * b.toDouble() / d.toDouble()).toLong()
    }
}
