package ani.dantotsu.screenshots

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.themes.ThemeManager

/** Empty activity with the app theme; tests put real app layouts and adapters in it. */
class ScreenHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
    }
}
