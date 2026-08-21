package ani.dantotsu.media.anime.player

import android.graphics.Color
import kotlin.math.roundToInt

object MpvSubtitleStyleMapper {

    fun colorToMpvHex(colorInt: Int): String {
        val alpha = (colorInt ushr 24) and 0xFF
        val red = (colorInt ushr 16) and 0xFF
        val green = (colorInt ushr 8) and 0xFF
        val blue = colorInt and 0xFF

        return if (alpha < 255) {
            String.format("#%02X%02X%02X%02X", alpha, red, green, blue)
        } else {
            String.format("#%02X%02X%02X", red, green, blue)
        }
    }

    fun spToMpvScaledSize(fontSizeSp: Float, scaledDensity: Float = 1.0f): Int {
        // Base reference: MPV default sub-font-size is 55 on a 720p reference canvas.
        // Android default subtitle font size is ~20sp. 20sp * 2.75 = 55.
        val baseSize = (fontSizeSp * 2.75f).roundToInt()
        return baseSize.coerceIn(12, 160)
    }

    fun dpToMpvScaledMargin(marginDp: Int, density: Float = 1.0f): Int {
        // Base reference: 24dp margin maps to ~36 in MPV 720p scaled coordinates.
        val baseMargin = (marginDp * 1.5f).roundToInt()
        return baseMargin.coerceIn(0, 300)
    }
}
