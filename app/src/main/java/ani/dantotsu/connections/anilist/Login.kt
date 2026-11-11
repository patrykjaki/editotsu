package ani.dantotsu.connections.anilist

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger

class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent()
    }

    private fun handleAuthIntent() {
        ThemeManager(this).applyTheme()
        val token = AnilistLoginGate.parseToken(intent?.data?.toString())
        // Consume the pending login even when the link carries no token: one login, one callback.
        val pending = AnilistLoginGate.consume()
        when {
            token == null -> Logger.log("AniList login callback without a token")
            !pending -> {
                // Not started from this app, or started too long ago: keep the current login.
                Logger.log("AniList login callback ignored: no login pending")
                toast(getString(R.string.anilist_login_not_started))
            }
            else -> accept(token)
        }
        startMainActivity(this)
    }

    private fun accept(token: String) {
        val old = PrefManager.getVal(PrefName.AnilistToken, null as String?)
        if (old != token) {
            // A different account (or a new token) must not reuse the old identity, caches
            // or comments sign-in.
            if (!old.isNullOrEmpty()) Anilist.removeSavedToken() else CommentsAPI.logout()
        }
        Anilist.token = token
        PrefManager.setVal(PrefName.AnilistToken, token)
    }
}
