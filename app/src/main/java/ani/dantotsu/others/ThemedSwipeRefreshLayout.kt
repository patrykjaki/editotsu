package ani.dantotsu.others

import android.content.Context
import android.util.AttributeSet
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ani.dantotsu.getThemeColor

/**
 * Pull-to-refresh whose spinner takes the app theme: primary colour arrow on a surface disc.
 * The stock one is a black arrow on white whatever the theme.
 */
class ThemedSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {
    init {
        setColorSchemeColors(context.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
        setProgressBackgroundColorSchemeColor(
            context.getThemeColor(com.google.android.material.R.attr.colorSurface)
        )
    }
}
