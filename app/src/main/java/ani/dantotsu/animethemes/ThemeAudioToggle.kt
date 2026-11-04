package ani.dantotsu.animethemes

import android.content.res.ColorStateList
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast

/**
 * Audio-only icon toggle for the theme lists (Themes tab, Themes screen): themes
 * started from there play the .ogg instead of the video. Tinted when on.
 */
object ThemeAudioToggle {
    fun bind(button: ImageButton) {
        show(button)
        button.setOnClickListener {
            val on = !PrefManager.getVal<Boolean>(PrefName.AnimeThemesAudioOnly)
            PrefManager.setVal(PrefName.AnimeThemesAudioOnly, on)
            show(button)
            toast(button.context.getString(if (on) R.string.theme_audio_only_on else R.string.theme_audio_only_off))
        }
    }

    fun show(button: ImageButton) {
        val on = PrefManager.getVal<Boolean>(PrefName.AnimeThemesAudioOnly)
        val ctx = button.context
        button.setImageResource(R.drawable.ic_round_audiotrack_24)
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(
            ctx.getThemeColor(if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
        ))
        button.isSelected = on
        button.contentDescription = ctx.getString(R.string.theme_audio_only)
        button.tooltipText = ctx.getString(if (on) R.string.theme_audio_only_on else R.string.theme_audio_only_off)
        ViewCompat.setStateDescription(button, ctx.getString(if (on) R.string.theme_state_on else R.string.theme_state_off))
    }
}
