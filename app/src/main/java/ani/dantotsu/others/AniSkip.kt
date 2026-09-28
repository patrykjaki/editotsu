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