package ani.dantotsu.media.anime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R

/**
 * A seek icon with the seek distance written inside the arrow (like replay_10 / forward_10),
 * for any configured value from 1 to 99 seconds. Used by the PiP actions so the buttons say
 * what they do.
 */
internal object SeekIcon {
    private const val SIZE_PX = 96

    fun bitmap(context: Context, seconds: Int, forward: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val arc = ContextCompat.getDrawable(context, R.drawable.ic_seek_back_arc_24)!!.mutate()
        arc.setBounds(0, 0, SIZE_PX, SIZE_PX)
        canvas.save()
        if (forward) canvas.scale(-1f, 1f, SIZE_PX / 2f, SIZE_PX / 2f)
        arc.draw(canvas)
        canvas.restore()

        val label = seekIconLabel(seconds)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.poppins_bold) }
                .getOrNull() ?: Typeface.DEFAULT_BOLD
            // the arc's inner circle is about 10 of 24 units wide
            textSize = SIZE_PX * (if (label.length > 1) 0.30f else 0.36f)
        }
        // centre of the arc's circle is (12, 13) in the 24-unit viewport
        val cy = SIZE_PX * 13f / 24f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, SIZE_PX / 2f, cy, paint)
        return bmp
    }
}

/** The number drawn in the icon: the PiP distance, capped to two digits. */
internal fun seekIconLabel(seconds: Int): String = pipSeekSeconds(seconds).coerceAtMost(99).toString()
