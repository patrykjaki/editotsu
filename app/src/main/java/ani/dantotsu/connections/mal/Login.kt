package ani.dantotsu.connections.mal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.returnFromLogin
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        MalCallback.handle(this, intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MalCallback.handle(this, intent.data)
    }
}

/** Finishes a MAL login from its redirect, then returns to the screen that started it. */
internal object MalCallback {
    /** [quiet]: the caller shows its own loading state, so no progress snackbars. */
    fun handle(activity: AppCompatActivity, uri: Uri?, quiet: Boolean = false) {
        with(activity) {
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
                returnFromLogin(activity)
            }

            is MALOAuth.CallbackResult.Invalid -> {
                Logger.log("MAL Login: Invalid callback URI reason: ${callbackResult.reason.name}")
                snackString(getString(R.string.mal_login_uri_not_found))
                returnFromLogin(activity)
            }

            is MALOAuth.CallbackResult.Success -> {
                val verifier = MALOAuth.SessionStore.consumeSession(callbackResult.state)
                if (verifier == null) {
                    Logger.log("MAL Login: State mismatch, expired session, or replayed callback")
                    snackString(getString(R.string.mal_login_session_invalid))
                    returnFromLogin(activity)
                    return@with
                }

                if (!quiet) snackString(getString(R.string.logging_in_mal))
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val exchangeSuccess = MAL.exchangeAuthorizationCode(callbackResult.code, verifier)
                        if (exchangeSuccess) {
                            if (!quiet) snackString(getString(R.string.getting_user_data))
                            // Name first so the login returns at once; avatar and stats after.
                            MAL.query.getUserData(quick = true)
                            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                ani.dantotsu.tryWithSuspend { MAL.query.getUserData() }
                                ani.dantotsu.MainActivity.accountChanged()
                            }
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
                            returnFromLogin(activity)
                        }
                    }
                }
            }
        }
    }
}
}
