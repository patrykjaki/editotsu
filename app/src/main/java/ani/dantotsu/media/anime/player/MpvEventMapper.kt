package ani.dantotsu.media.anime.player

object MpvEventMapper {

    data class EndFileResult(
        val state: PlaybackState,
        val isStale: Boolean,
        val reason: String = "unknown"
    )

    fun parseEndFile(
        reason: String?,
        fileError: String?,
        playlistEntryId: Long?,
        activeEntryId: Long?,
        currentPositionMs: Long,
        durationMs: Long
    ): EndFileResult {
        // Guard against stale events from previous playlist entries
        if (activeEntryId != null && playlistEntryId != null && activeEntryId != playlistEntryId) {
            return EndFileResult(
                state = PlaybackState.Idle,
                isStale = true,
                reason = reason ?: "unknown"
            )
        }

        val normalizedReason = reason?.lowercase()?.trim() ?: "unknown"

        return when (normalizedReason) {
            "eof" -> {
                EndFileResult(
                    state = PlaybackState.Ended(
                        reason = "eof",
                        positionMs = currentPositionMs,
                        durationMs = durationMs
                    ),
                    isStale = false,
                    reason = "eof"
                )
            }

            "error" -> {
                val error = classifyError(fileError)
                EndFileResult(
                    state = PlaybackState.Error(error),
                    isStale = false,
                    reason = "error"
                )
            }

            "stop", "quit" -> {
                EndFileResult(
                    state = PlaybackState.Idle,
                    isStale = false,
                    reason = normalizedReason
                )
            }

            "redirect" -> {
                EndFileResult(
                    state = PlaybackState.Buffering,
                    isStale = false,
                    reason = "redirect"
                )
            }

            else -> {
                EndFileResult(
                    state = PlaybackState.Idle,
                    isStale = false,
                    reason = normalizedReason
                )
            }
        }
    }

    fun classifyError(fileError: String?): PlaybackError {
        if (fileError == null) {
            return PlaybackError(
                category = ErrorCategory.UNKNOWN,
                message = "Media playback error",
                fatal = true,
                retryable = true
            )
        }
        val lower = fileError.lowercase()

        // 1. Check specific HTTP non-retryable source / auth status codes FIRST
        val (category, retryable) = when {
            lower.contains("401") || lower.contains("unauthorized") ||
            lower.contains("403") || lower.contains("forbidden") || lower.contains("access denied") ||
            lower.contains("404") || lower.contains("not found") ||
            lower.contains("410") || lower.contains("gone") ||
            lower.contains("416") -> {
                Pair(ErrorCategory.SOURCE, false)
            }

            // 2. Transient HTTP status codes (retryable)
            lower.contains("408") || lower.contains("429") ||
            Regex("""\b5\d{2}\b""").containsMatchIn(lower) ||
            lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504") -> {
                Pair(ErrorCategory.NETWORK, true)
            }

            // 3. Network connection/transport failures
            lower.contains("timeout") || lower.contains("timed out") ||
            lower.contains("connect") || lower.contains("dns") || lower.contains("resolve") ||
            lower.contains("unreachable") || lower.contains("reset by peer") ||
            lower.contains("network") || lower.contains("http") -> {
                Pair(ErrorCategory.NETWORK, true)
            }

            // 4. Codec & decoder errors
            lower.contains("decoder") || lower.contains("codec") || lower.contains("hwdec") -> {
                Pair(ErrorCategory.DECODER, false)
            }

            // 5. Demuxing & container format errors
            lower.contains("demux") || lower.contains("format") -> {
                Pair(ErrorCategory.DEMUX, false)
            }

            // 6. Video / Audio output errors
            lower.contains("vo") || lower.contains("ao") || lower.contains("output") -> {
                Pair(ErrorCategory.OUTPUT, false)
            }

            else -> {
                Pair(ErrorCategory.UNKNOWN, true)
            }
        }

        return PlaybackError(
            category = category,
            message = fileError,
            errorCode = -1,
            fatal = true,
            retryable = retryable
        )
    }
}
