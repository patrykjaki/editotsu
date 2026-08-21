package ani.dantotsu.media.anime.player

import kotlin.math.roundToLong

object TimeConverter {

    fun secondsToMs(seconds: Double?): Long {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) {
            return 0L
        }
        return (seconds * 1000.0).roundToLong()
    }

    fun secondsToMs(seconds: Long?): Long {
        if (seconds == null || seconds < 0L) {
            return 0L
        }
        return seconds * 1000L
    }

    fun msToSeconds(ms: Long): Double {
        if (ms <= 0L) {
            return 0.0
        }
        return ms.toDouble() / 1000.0
    }

    fun msToSecondsSigned(ms: Long): Double {
        return ms.toDouble() / 1000.0
    }
}
