package ani.dantotsu.settings

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.screens.TrackingScreen
import ani.dantotsu.startMainActivity

/** Settings > Tracking ([TrackingScreen]). */
class SettingsAccountActivity : SettingsScreenActivity() {
    override val screen get() = TrackingScreen

    /** After a logout, back restarts the main screen so it drops the old account. */
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onScreenCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this, restartMainActivity)
    }

    fun restartMainOnBack() {
        restartMainActivity.isEnabled = true
    }
}
