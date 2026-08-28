package ani.dantotsu.connections.mal

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object MAL {
    val query: MALQueries = MALQueries()
    val jikan: JikanQueries = JikanQueries()
    const val clientId = "b70e05dccba4c13e5174a7858426ee47"
    var username: String? = null
    var avatar: String? = null
    var token: String? = null
    var userid: Int? = null
    var episodesWatched: Int? = null
    var chaptersRead: Int? = null

    private val refreshMutex = Mutex()
    private val authGeneration = java.util.concurrent.atomic.AtomicLong(0)
    val currentAuthGeneration: Long get() = authGeneration.get()

    internal var tokenStore: MALTokenStore = DefaultMALTokenStore()
    internal var networkClient: MALNetworkClient = DefaultMALNetworkClient()
    internal var onRefreshWaitingForLock: (() -> Unit)? = null
    internal var onRefreshLockAcquired: (() -> Unit)? = null
    internal var onProfileCommitHook: (() -> Unit)? = null
    internal var onPreAuthLockHook: (() -> Unit)? = null

    internal fun resetForTests() {
        tokenStore = DefaultMALTokenStore()
        networkClient = DefaultMALNetworkClient()
        onRefreshWaitingForLock = null
        onRefreshLockAcquired = null
        onProfileCommitHook = null
        onPreAuthLockHook = null
        MALQueries.testHttpHandler = null
        JikanQueries.testHttpHandler = null
        removeSavedToken()
    }

    fun loginIntent(context: Context) {
        val session = MALOAuth.SessionStore.createSession()
        if (session == null) {
            Logger.log("MAL: Failed to securely persist OAuth session, aborting login")
            snackString(context.getString(R.string.mal_login_failed))
            return
        }
        val request = MALOAuth.buildAuthorizeUrl(
            clientId = clientId,
            codeChallenge = session.codeVerifier,
            state = session.state
        )

        try {
            CustomTabsIntent.Builder().build().launchUrl(
                context,
                Uri.parse(request)
            )
        } catch (e: ActivityNotFoundException) {
            openLinkInBrowser(request)
        }
    }

    suspend fun handleCallback(uri: Uri): Boolean {
        val callbackUrl = uri.toString()
        return when (val parsed = MALOAuth.parseCallback(callbackUrl)) {
            is MALOAuth.CallbackResult.Success -> {
                val verifier = MALOAuth.SessionStore.consumeSession(parsed.state)
                if (verifier == null) {
                    Logger.log("MAL Login: State verification failed or session expired (CSRF protection)")
                    return false
                }
                exchangeAuthorizationCode(parsed.code, verifier)
            }
            is MALOAuth.CallbackResult.Denied -> {
                MALOAuth.SessionStore.consumeSession(parsed.state)
                Logger.log("MAL Login: Access denied or cancelled by user (${parsed.errorReason.name})")
                false
            }
            is MALOAuth.CallbackResult.Invalid -> {
                Logger.log("MAL Login: Invalid callback URI structure (${parsed.reason.name})")
                false
            }
        }
    }

    suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String): Boolean {
        val generationAtStart = currentAuthGeneration
        return try {
            val response = networkClient.post(
                "https://myanimelist.net/v1/oauth2/token",
                data = mapOf(
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to codeVerifier,
                    "grant_type" to "authorization_code"
                )
            )
            if (!response.isSuccessful) {
                Logger.log("MAL: Token exchange failed with HTTP status ${response.code}")
                return false
            }
            if (currentAuthGeneration != generationAtStart) {
                Logger.log("MAL: Authorization code exchange discarded due to logout during in-flight network call")
                return false
            }
            val res = response.parsed<ResponseToken>()
            val saved = saveResponse(res, generationAtStart = generationAtStart)
            saved != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("MAL: Token exchange error (${e.javaClass.simpleName})")
            false
        }
    }

    suspend fun refreshToken(
        force: Boolean = false,
        failedAccessToken: String? = null,
    ): ResponseToken? {
        onRefreshWaitingForLock?.invoke()
        return refreshMutex.withLock {
            onRefreshLockAcquired?.invoke()
            val generationAtLock = authGeneration.get()
            val savedToken = tokenStore.getSavedToken()
            if (savedToken == null || savedToken.refreshToken.isBlank()) {
                return@withLock null
            }
            // Single-flight deduplication: If a concurrent coroutine already refreshed the token
            // while this caller was waiting for the lock, reuse the newly saved token.
            if (force && failedAccessToken != null && savedToken.accessToken != failedAccessToken) {
                return@withLock activateSavedToken(savedToken, generationAtLock)
            }
            if (!force && !savedToken.isExpired()) {
                return@withLock activateSavedToken(savedToken, generationAtLock)
            }
            try {
                val response = networkClient.post(
                    "https://myanimelist.net/v1/oauth2/token",
                    data = mapOf(
                        "client_id" to clientId,
                        "grant_type" to "refresh_token",
                        "refresh_token" to savedToken.refreshToken
                    )
                )
                if (authGeneration.get() != generationAtLock) {
                    Logger.log("MAL: Token refresh discarded due to logout during in-flight network call")
                    return@withLock null
                }
                if (response.code == 400 || response.code == 401) {
                    Logger.log("MAL: Token refresh rejected with status ${response.code}, clearing credentials")
                    removeSavedToken()
                    return@withLock null
                }
                if (!response.isSuccessful) {
                    Logger.log("MAL: Token refresh failed with status ${response.code}")
                    return@withLock null
                }
                val res = response.parsed<ResponseToken>()
                saveResponse(res, generationAtStart = generationAtLock)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("MAL: Token refresh error (${e.javaClass.simpleName})")
                null
            }
        }
    }

    suspend fun getSavedToken(): Boolean {
        val generationAtStart = currentAuthGeneration
        return try {
            val savedToken = tokenStore.getSavedToken()
                ?: return false

            val savedUsername = tokenStore.username
            val savedAvatar = tokenStore.avatar

            if (savedToken.isExpired()) {
                val refreshed = refreshToken(force = true, failedAccessToken = savedToken.accessToken)
                if (refreshed != null) {
                    return commitRestoredSession(
                        accessToken = refreshed.accessToken,
                        username = savedUsername,
                        avatar = savedAvatar,
                        generation = generationAtStart
                    )
                } else {
                    // Stored credentials preserved for subsequent retry, but expired token is not marked active
                    return false
                }
            } else {
                return commitRestoredSession(
                    accessToken = savedToken.accessToken,
                    username = savedUsername,
                    avatar = savedAvatar,
                    generation = generationAtStart
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("MAL: Error in getSavedToken (${e.javaClass.simpleName})")
            false
        }
    }

    private val authLock = Any()

    fun removeSavedToken() {
        onPreAuthLockHook?.invoke()
        synchronized(authLock) {
            authGeneration.incrementAndGet()
            token = null
            username = null
            userid = null
            avatar = null
            episodesWatched = null
            chaptersRead = null
            tokenStore.removeToken()
            MALOAuth.SessionStore.clear()
        }
    }

    fun isLoggedIn(): Boolean {
        return token != null && !username.isNullOrBlank()
    }

    fun activateSavedToken(
        savedToken: ResponseToken,
        generation: Long
    ): ResponseToken? {
        synchronized(authLock) {
            if (authGeneration.get() != generation) {
                return null
            }
            this.token = savedToken.accessToken
            return savedToken
        }
    }

    fun commitRestoredSession(
        accessToken: String,
        username: String?,
        avatar: String?,
        generation: Long
    ): Boolean {
        synchronized(authLock) {
            if (authGeneration.get() != generation) {
                return false
            }
            this.token = accessToken
            this.username = username
            this.avatar = avatar
            return true
        }
    }

    fun saveResponse(
        res: ResponseToken,
        currentTimeMs: Long = System.currentTimeMillis(),
        generationAtStart: Long? = null
    ): ResponseToken? {
        synchronized(authLock) {
            if (generationAtStart != null && authGeneration.get() != generationAtStart) {
                token = null
                return null
            }
            // If expiresIn is in relative seconds (< 1 trillion), convert to absolute epoch millis
            if (res.expiresIn < 1_000_000_000_000L) {
                res.expiresIn = currentTimeMs + (res.expiresIn * 1000L)
            }
            val saved = tokenStore.saveToken(res)
            if (saved && (generationAtStart == null || authGeneration.get() == generationAtStart)) {
                token = res.accessToken
                return res
            } else {
                // If generation invalidated during save, roll back tokenStore
                tokenStore.removeToken()
                token = null
                return null
            }
        }
    }

    fun commitProfile(
        userId: Int,
        username: String,
        avatar: String?,
        episodesWatched: Int?,
        chaptersRead: Int?,
        generation: Long
    ): Boolean {
        synchronized(authLock) {
            onProfileCommitHook?.invoke()
            if (authGeneration.get() != generation) {
                return false
            }
            this.userid = userId
            this.username = username
            this.avatar = avatar
            this.episodesWatched = episodesWatched
            this.chaptersRead = chaptersRead
            tokenStore.username = username
            tokenStore.avatar = avatar
            try {
                PrefManager.setVal(PrefName.MALUserName, username)
                PrefManager.setVal(PrefName.MALAvatar, avatar ?: "")
            } catch (e: Throwable) {}
            return true
        }
    }

    @Serializable
    data class ResponseToken(
        @SerialName("token_type") val tokenType: String,
        @SerialName("expires_in") var expiresIn: Long,
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
            private const val EXPIRY_BUFFER_MS = 5 * 60 * 1000L // 5 minutes buffer
        }

        fun isExpired(bufferMs: Long = EXPIRY_BUFFER_MS, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
            if (expiresIn < 1_000_000_000_000L) {
                // Raw seconds or legacy invalid format -> treat as expired
                return true
            }
            return currentTimeMs >= (expiresIn - bufferMs)
        }
    }

    interface MALTokenStore {
        fun getSavedToken(): ResponseToken?
        fun saveToken(token: ResponseToken): Boolean
        fun removeToken()
        var username: String?
        var avatar: String?
    }

    class DefaultMALTokenStore : MALTokenStore {
        override fun getSavedToken(): ResponseToken? {
            // Read exclusively from encrypted storage; fail-closed on corrupt/tampered data
            try {
                val encrypted = PrefManager.getVal<String>(PrefName.MALEncryptedToken, "")
                if (encrypted.isNotBlank()) {
                    val decrypted = MALKeystore.decrypt(encrypted).getOrNull()
                    if (!decrypted.isNullOrBlank()) {
                        return Mapper.json.decodeFromString<ResponseToken>(decrypted)
                    } else {
                        Logger.log("MAL: Decryption failed for MALEncryptedToken, purging corrupt record")
                        removeToken()
                        return null
                    }
                }
            } catch (e: Throwable) {
                // Safe in tests
            }

            // Cleanup legacy plaintext token without deserializing it (eliminates Java deserialization risk)
            try {
                PrefManager.removeVal(PrefName.MALToken)
            } catch (t: Throwable) {}

            return null
        }

        override fun saveToken(token: ResponseToken): Boolean {
            return try {
                val json = Mapper.json.encodeToString(ResponseToken.serializer(), token)
                val encrypted = MALKeystore.encrypt(json).getOrNull()
                if (encrypted != null) {
                    PrefManager.setVal(PrefName.MALEncryptedToken, encrypted)
                    // Purge legacy plaintext token if present
                    try { PrefManager.removeVal(PrefName.MALToken) } catch (t: Throwable) {}
                    true
                } else {
                    Logger.log("MAL: Token encryption failed, fail-closed without saving plaintext")
                    false
                }
            } catch (e: Throwable) {
                false
            }
        }

        override fun removeToken() {
            try {
                PrefManager.removeVal(PrefName.MALEncryptedToken)
                PrefManager.removeVal(PrefName.MALToken)
                PrefManager.removeVal(PrefName.MALUserName)
                PrefManager.removeVal(PrefName.MALAvatar)
                PrefManager.removeVal(PrefName.MALCodeChallenge)
                PrefManager.removeVal(PrefName.MALAuthSession)
            } catch (e: Throwable) {
                // Safe in headless tests
            }
        }

        override var username: String?
            get() = try { PrefManager.getVal(PrefName.MALUserName, null as String?) } catch (e: Throwable) { null }
            set(value) {
                try {
                    if (value == null) PrefManager.removeVal(PrefName.MALUserName)
                    else PrefManager.setVal(PrefName.MALUserName, value)
                } catch (e: Throwable) {}
            }

        override var avatar: String?
            get() = try { PrefManager.getVal(PrefName.MALAvatar, null as String?) } catch (e: Throwable) { null }
            set(value) {
                try {
                    if (value == null) PrefManager.removeVal(PrefName.MALAvatar)
                    else PrefManager.setVal(PrefName.MALAvatar, value)
                } catch (e: Throwable) {}
            }
    }

    interface MALNetworkClient {
        suspend fun post(url: String, data: Map<String, String>): MALNetworkResponse
    }

    data class MALNetworkResponse(
        val code: Int,
        val bodyString: String?,
        val isSuccessful: Boolean = code in 200..299
    ) {
        inline fun <reified T> parsed(): T {
            val body = bodyString ?: throw IllegalStateException("Response body is null")
            return Mapper.json.decodeFromString(body)
        }
    }

    class DefaultMALNetworkClient : MALNetworkClient {
        override suspend fun post(url: String, data: Map<String, String>): MALNetworkResponse {
            val res = client.post(url, data = data)
            return MALNetworkResponse(
                code = res.code,
                bodyString = res.text,
                isSuccessful = res.isSuccessful
            )
        }
    }
}
