package ani.dantotsu.connections.mal

import android.net.Uri
import ani.dantotsu.Mapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

object MALOAuth {
    private val secureRandom = SecureRandom()
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Generates a cryptographically random PKCE code verifier (128 chars).
     * RFC 7636 allows 43 to 128 characters from [A-Za-z0-9\-_.~].
     * 96 random bytes encoded with unpadded base64url produces exactly 128 characters.
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(96)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    /**
     * MyAnimeList OAuth2 implementation requires the 'plain' transformation method for PKCE.
     * Code challenge is identical to code verifier.
     */
    fun generateCodeChallenge(verifier: String): String = verifier

    /**
     * Generates a cryptographically random OAuth2 state string (43 chars).
     * 32 random bytes encoded with unpadded base64url produces 43 characters.
     */
    fun generateState(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    /**
     * Builds the authorization URL for MyAnimeList OAuth2 public client.
     * Encodes all query parameters using UTF-8 percent-encoding.
     */
    fun buildAuthorizeUrl(
        clientId: String = MAL.clientId,
        codeChallenge: String,
        state: String,
        redirectUri: String? = null
    ): String {
        val encodedClientId = URLEncoder.encode(clientId, "UTF-8")
        val encodedChallenge = URLEncoder.encode(codeChallenge, "UTF-8")
        val encodedState = URLEncoder.encode(state, "UTF-8")
        val base = "https://myanimelist.net/v1/oauth2/authorize?response_type=code" +
                "&client_id=$encodedClientId" +
                "&code_challenge=$encodedChallenge" +
                "&code_challenge_method=plain" +
                "&state=$encodedState"
        return if (!redirectUri.isNullOrBlank()) {
            val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
            "$base&redirect_uri=$encodedRedirect"
        } else {
            base
        }
    }

    enum class OAuthErrorReason {
        ACCESS_DENIED,
        UNAUTHORIZED_CLIENT,
        INVALID_REQUEST,
        UNSUPPORTED_RESPONSE_TYPE,
        SERVER_ERROR,
        TEMPORARILY_UNAVAILABLE,
        OTHER,
    }

    enum class CallbackErrorReason {
        EMPTY_URI,
        INVALID_SCHEME,
        INVALID_HOST,
        MALFORMED_URI,
        MISSING_CODE,
        MISSING_STATE,
    }

    sealed class CallbackResult {
        data class Success(val code: String, val state: String) : CallbackResult()
        data class Denied(val errorReason: OAuthErrorReason, val state: String?) : CallbackResult()
        data class Invalid(val reason: CallbackErrorReason) : CallbackResult()
    }

    /**
     * Validates and parses a string URI for callback redirect parameters.
     * Uses standard java.net.URI for deterministic behavior across JVM tests and Android.
     * Never leaks raw query parameters or URI contents into error results.
     */
    fun parseCallback(uriString: String?): CallbackResult {
        if (uriString.isNullOrBlank()) return CallbackResult.Invalid(CallbackErrorReason.EMPTY_URI)
        return try {
            val javaUri = URI(uriString)
            val scheme = javaUri.scheme?.lowercase()
            if (scheme != "editotsu" && scheme != "dantotsu") {
                return CallbackResult.Invalid(CallbackErrorReason.INVALID_SCHEME)
            }
            val host = javaUri.host?.lowercase()
            if (host != "mal") {
                return CallbackResult.Invalid(CallbackErrorReason.INVALID_HOST)
            }

            val rawQuery = javaUri.rawQuery ?: ""
            val queryParams = rawQuery.split("&").filter { it.isNotEmpty() }.associate { param ->
                val parts = param.split("=", limit = 2)
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                key to value
            }

            val state = queryParams["state"]
            val error = queryParams["error"]
            if (!error.isNullOrBlank()) {
                val errorReason = when (error.lowercase()) {
                    "access_denied" -> OAuthErrorReason.ACCESS_DENIED
                    "unauthorized_client" -> OAuthErrorReason.UNAUTHORIZED_CLIENT
                    "invalid_request" -> OAuthErrorReason.INVALID_REQUEST
                    "unsupported_response_type" -> OAuthErrorReason.UNSUPPORTED_RESPONSE_TYPE
                    "server_error" -> OAuthErrorReason.SERVER_ERROR
                    "temporarily_unavailable" -> OAuthErrorReason.TEMPORARILY_UNAVAILABLE
                    else -> OAuthErrorReason.OTHER
                }
                return CallbackResult.Denied(errorReason = errorReason, state = state)
            }

            val code = queryParams["code"]

            if (code.isNullOrBlank()) {
                return CallbackResult.Invalid(CallbackErrorReason.MISSING_CODE)
            }
            if (state.isNullOrBlank()) {
                return CallbackResult.Invalid(CallbackErrorReason.MISSING_STATE)
            }

            CallbackResult.Success(code = code, state = state)
        } catch (e: Exception) {
            CallbackResult.Invalid(CallbackErrorReason.MALFORMED_URI)
        }
    }

    /**
     * Validates and parses the Android deep-link callback URI from MyAnimeList OAuth.
     */
    fun parseCallback(uri: Uri?): CallbackResult {
        if (uri == null) return CallbackResult.Invalid(CallbackErrorReason.EMPTY_URI)
        return parseCallback(uri.toString())
    }

    @Serializable
    data class AuthSession(
        val codeVerifier: String,
        val state: String,
        val createdAtMs: Long = System.currentTimeMillis()
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    interface OAuthSessionStorage {
        fun saveSession(session: AuthSession): Boolean
        fun loadSession(): AuthSession?
        fun clearSession()
    }

    class PrefSessionStorage : OAuthSessionStorage {
        override fun saveSession(session: AuthSession): Boolean {
            return try {
                val json = Mapper.json.encodeToString(AuthSession.serializer(), session)
                val encrypted = MALKeystore.encrypt(json).getOrNull() ?: return false
                PrefManager.setVal(PrefName.MALAuthSession, encrypted)
                true
            } catch (e: Throwable) {
                false
            }
        }

        override fun loadSession(): AuthSession? {
            return try {
                val encrypted = PrefManager.getVal<String>(PrefName.MALAuthSession, "")
                if (encrypted.isBlank()) return null
                val decrypted = MALKeystore.decrypt(encrypted).getOrNull() ?: return null
                Mapper.json.decodeFromString(AuthSession.serializer(), decrypted)
            } catch (e: Throwable) {
                null
            }
        }

        override fun clearSession() {
            try {
                PrefManager.removeVal(PrefName.MALAuthSession)
                PrefManager.removeVal(PrefName.MALCodeChallenge)
            } catch (e: Throwable) {
                // Safe in headless tests
            }
        }
    }

    class InMemorySessionStorage : OAuthSessionStorage {
        private var session: AuthSession? = null
        override fun saveSession(session: AuthSession): Boolean {
            this.session = session
            return true
        }
        override fun loadSession(): AuthSession? = session
        override fun clearSession() {
            this.session = null
        }
    }

    object SessionStore {
        private val lock = Any()
        internal var storage: OAuthSessionStorage = PrefSessionStorage()
        const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes

        internal fun resetForTests() {
            storage = PrefSessionStorage()
            clear()
        }

        fun createSession(
            verifier: String = generateCodeVerifier(),
            state: String = generateState(),
            createdAtMs: Long = System.currentTimeMillis()
        ): AuthSession? {
            synchronized(lock) {
                val session = AuthSession(verifier, state, createdAtMs)
                val saved = storage.saveSession(session)
                if (!saved) {
                    return null
                }
                return session
            }
        }

        fun getActiveSession(): AuthSession? {
            synchronized(lock) {
                val session = storage.loadSession() ?: return null
                if (System.currentTimeMillis() - session.createdAtMs > SESSION_TIMEOUT_MS) {
                    storage.clearSession()
                    return null
                }
                return session
            }
        }

        /**
         * Validates callback state and atomically consumes the pending session.
         * Once consumed, the session cannot be replayed. Survives process recreation.
         * On mismatch, the legitimate pending session is retained until timeout.
         * Returns code_verifier if state matches and session is valid, or null otherwise.
         */
        fun consumeSession(
            callbackState: String?,
            currentTimeMs: Long = System.currentTimeMillis()
        ): String? {
            if (callbackState.isNullOrBlank()) return null
            synchronized(lock) {
                val session = storage.loadSession() ?: return null
                if (currentTimeMs - session.createdAtMs > SESSION_TIMEOUT_MS) {
                    storage.clearSession()
                    return null
                }
                if (session.state != callbackState) {
                    return null
                }
                storage.clearSession() // Atomic consume on match only: cannot be replayed
                return session.codeVerifier
            }
        }

        fun clear() {
            synchronized(lock) {
                storage.clearSession()
            }
        }
    }
}
