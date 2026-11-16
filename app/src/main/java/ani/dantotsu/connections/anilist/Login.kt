package ani.dantotsu.connections.anilist

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.returnFromLogin
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            else -> {
                accept(token)
                // The screen we return to shows the account: load it first (the app used to
                // restart here, which loaded it).
                lifecycleScope.launch(Dispatchers.IO) {
                    tryWithSuspend { Anilist.query.getUserData() }
                    withContext(Dispatchers.Main) { returnFromLogin(this@Login) }
                }
                return
            }
        }
        returnFromLogin(this)
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
