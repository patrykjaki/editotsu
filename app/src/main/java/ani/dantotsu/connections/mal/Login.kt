package ani.dantotsu.connections.mal

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        handleAuthCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data
        val callbackResult = MALOAuth.parseCallback(uri)

        when (callbackResult) {
            is MALOAuth.CallbackResult.Denied -> {
                // Validate state before treating denial as legitimate for the active pending session
                val verifier = MALOAuth.SessionStore.consumeSession(callbackResult.state)
                if (verifier == null) {
                    Logger.log("MAL Login: Denial callback rejected due to missing, mismatched, or expired state")
                    snackString(getString(R.string.mal_login_session_invalid))
                } else {
                    Logger.log("MAL Login: Access denied or cancelled by user (${callbackResult.errorReason.name})")
                    snackString(getString(R.string.mal_login_cancelled))
                }
                startMainActivity(this)
                finish()
            }

            is MALOAuth.CallbackResult.Invalid -> {
                Logger.log("MAL Login: Invalid callback URI reason: ${callbackResult.reason.name}")
                snackString(getString(R.string.mal_login_uri_not_found))
                startMainActivity(this)
                finish()
            }

            is MALOAuth.CallbackResult.Success -> {
                val verifier = MALOAuth.SessionStore.consumeSession(callbackResult.state)
                if (verifier == null) {
                    Logger.log("MAL Login: State mismatch, expired session, or replayed callback")
                    snackString(getString(R.string.mal_login_session_invalid))
                    startMainActivity(this)
                    finish()
                    return
                }

                snackString(getString(R.string.logging_in_mal))
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val exchangeSuccess = MAL.exchangeAuthorizationCode(callbackResult.code, verifier)
                        if (exchangeSuccess) {
                            snackString(getString(R.string.getting_user_data))
                            MAL.query.getUserData()
                        } else {
                            snackString(getString(R.string.mal_login_failed))
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log("MAL Login: Authentication callback error (${e.javaClass.simpleName})")
                        snackString(getString(R.string.mal_login_failed))
                    } finally {
                        withContext(Dispatchers.Main) {
                            startMainActivity(this@Login)
                            finish()
                        }
                    }
                }
            }
        }
    }
}