package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.tryWithSuspend
import kotlinx.serialization.Serializable
import java.net.URLEncoder

object AniSkip {

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun getResult(
        malId: Int,
        episodeNumber: Int,
        episodeLength: Long,
        useProxyForTimeStamps: Boolean
    ): List<Stamp>? {
        val url =
            "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber?types[]=ed&types[]=mixed-ed&types[]=mixed-op&types[]=op&types[]=recap&episodeLength=$episodeLength"
        return tryWithSuspend {
            val a = if (useProxyForTimeStamps)
                client.get(
                    "https://corsproxy.io/?${
                        URLEncoder.encode(url, "utf-8").replace("+", "%20")
                    }"
                )
            else
                client.get(url)
            val res = a.parsed<AniSkipResponse>()
            if (res.found) res.results else null
        }
    }

    @Serializable
    data class AniSkipResponse(
        val found: Boolean,
        val results: List<Stamp>?,
        val message: String?,
        val statusCode: Int
    )

    @Serializable
    data class Stamp(
        val interval: AniSkipInterval,
        val skipType: String,
        val skipId: String,
        val episodeLength: Double
    )


    fun String.getType(): String {
        return when (this) {
            "op" -> "Opening"
            "ed" -> "Ending"
            "recap" -> "Recap"
            "mixed-ed" -> "Mixed Ending"
            "mixed-op" -> "Mixed Opening"
            else -> this
        }
    }

    @Serializable
    data class AniSkipInterval(
        val startTime: Double,
        val endTime: Double
    )
}

/**
 * MAX MERGE M5: provider results are untrusted input — drop reversed,
 * negative or non-finite ranges and order deterministically by start.
 * Valid data passes through unchanged.
 */
internal fun List<AniSkip.Stamp>.sanitizedStamps(): List<AniSkip.Stamp> =
    filter {
        val s = it.interval.startTime
        val e = it.interval.endTime
        s.isFinite() && e.isFinite() && s >= 0 && e > s
    }.sortedBy { it.interval.startTime }

/**
 * MAX MERGE M5 correction: silent null-on-error for suspend mapper
 * calls. Unlike runCatching/tryWith, cancellation is never swallowed —
 * a cancelled request must terminate the fallback chain, not start the
 * next provider.
 */
internal suspend fun <T> suspendNullOnError(block: suspend () -> T): T? {
    return try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * Live AniSkip needs a known playback duration (episodeLength-gated
 * API); CSV/IntroDB bounded ranges may resolve without it.
 */
internal fun shouldQueryLiveSkip(malId: Int?, durationSec: Long?): Boolean =
    malId != null && durationSec != null && durationSec > 0

/**
 * MAX MERGE M5 correction: ordered provider chain. Each provider is
 * tried in turn; the first non-empty result wins. Providers convert
 * ordinary failures to null themselves; CancellationException is never
 * caught here, so a cancelled request aborts the chain instead of
 * starting stale follow-up work. Unit-proved (SkipFallbackChainTest).
 */
internal suspend fun firstNonEmptyProvider(
    vararg providers: suspend () -> List<AniSkip.Stamp>?
): List<AniSkip.Stamp>? {
    for (provider in providers) {
        val result = provider()
        if (!result.isNullOrEmpty()) return result
    }
    return null
}