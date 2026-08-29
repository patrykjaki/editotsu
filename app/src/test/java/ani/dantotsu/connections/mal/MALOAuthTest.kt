package ani.dantotsu.connections.mal

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

class MALOAuthTest {

    private class FakeMALTokenStore : MAL.MALTokenStore {
        var storedToken: MAL.ResponseToken? = null
        override var username: String? = null
        override var avatar: String? = null
        var onSaveHook: (() -> Unit)? = null
        var onGetSavedTokenHook: ((MAL.ResponseToken?) -> MAL.ResponseToken?)? = null

        override fun getSavedToken(): MAL.ResponseToken? {
            val tokenSnapshot = storedToken
            return if (onGetSavedTokenHook != null) {
                onGetSavedTokenHook!!.invoke(tokenSnapshot)
            } else {
                tokenSnapshot
            }
        }
        override fun saveToken(token: MAL.ResponseToken): Boolean {
            onSaveHook?.invoke()
            this.storedToken = token
            return true
        }
        override fun removeToken() {
            storedToken = null
            username = null
            avatar = null
        }
    }

    private class FakeMALNetworkClient : MAL.MALNetworkClient {
        var postCount = AtomicInteger(0)
        var responseProvider: suspend (url: String, data: Map<String, String>) -> MAL.MALNetworkResponse = { _, _ ->
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"new_access_token","refresh_token":"new_refresh_token"}"""
            )
        }

        fun reset() {
            postCount.set(0)
            responseProvider = { _, _ ->
                MAL.MALNetworkResponse(
                    code = 200,
                    bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"new_access_token","refresh_token":"new_refresh_token"}"""
                )
            }
        }

        override suspend fun post(url: String, data: Map<String, String>): MAL.MALNetworkResponse {
            postCount.incrementAndGet()
            return responseProvider(url, data)
        }
    }

    private class FakeDurableSessionStorage : MALOAuth.OAuthSessionStorage {
        val diskFile = mutableMapOf<String, String>()

        override fun saveSession(session: MALOAuth.AuthSession): Boolean {
            val json = ani.dantotsu.Mapper.json.encodeToString(MALOAuth.AuthSession.serializer(), session)
            diskFile["session"] = json
            return true
        }

        override fun loadSession(): MALOAuth.AuthSession? {
            val raw = diskFile["session"] ?: return null
            return ani.dantotsu.Mapper.json.decodeFromString(MALOAuth.AuthSession.serializer(), raw)
        }

        override fun clearSession() {
            diskFile.remove("session")
        }
    }

    private val fakeTokenStore = FakeMALTokenStore()
    private val fakeNetworkClient = FakeMALNetworkClient()
    private val inMemorySessionStorage = MALOAuth.InMemorySessionStorage()
    private val softwareKeyProvider = MALKeystore.SoftwareKeyProvider()

    @Before
    fun setUp() {
        fakeTokenStore.onSaveHook = null
        fakeTokenStore.onGetSavedTokenHook = null
        MAL.onPreAuthLockHook = null
        MAL.onProfileCommitHook = null
        MAL.onRefreshWaitingForLock = null
        MAL.onRefreshLockAcquired = null
        MALQueries.testHttpHandler = null
        JikanQueries.testHttpHandler = null
        fakeTokenStore.storedToken = null
        fakeTokenStore.username = null
        fakeTokenStore.avatar = null
        fakeNetworkClient.reset()
        MALKeystore.keyProvider = softwareKeyProvider
        MALOAuth.SessionStore.storage = inMemorySessionStorage
        MALOAuth.SessionStore.clear()
        MAL.resetForTests()
        MAL.testClientId = "test_mal_client_id"
        MAL.tokenStore = fakeTokenStore
        MAL.networkClient = fakeNetworkClient
    }

    @After
    fun tearDown() {
        fakeTokenStore.onSaveHook = null
        fakeTokenStore.onGetSavedTokenHook = null
        MAL.onPreAuthLockHook = null
        MAL.onProfileCommitHook = null
        MAL.onRefreshWaitingForLock = null
        MAL.onRefreshLockAcquired = null
        MALQueries.testHttpHandler = null
        JikanQueries.testHttpHandler = null
        MALKeystore.resetForTests()
        MALOAuth.SessionStore.resetForTests()
        MAL.resetForTests()
        MAL.testClientId = null
        fakeTokenStore.storedToken = null
        fakeTokenStore.username = null
        fakeTokenStore.avatar = null
        fakeNetworkClient.reset()
        Logger.testLogSink = null
        try {
            Anilist.token = null
            Anilist.username = null
            Anilist.avatar = null
            Anilist.userid = null
        } catch (e: Throwable) {}
    }

    private fun createNiceResponse(code: Int, body: String = "", url: String = "https://api.myanimelist.net/v2"): com.lagradost.nicehttp.NiceResponse {
        val mediaType = "application/json".toMediaTypeOrNull()
        val responseBody = body.toResponseBody(mediaType)
        val okResponse = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url(url).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error $code")
            .body(responseBody)
            .build()
        return com.lagradost.nicehttp.NiceResponse(okResponse, ani.dantotsu.Mapper)
    }

    @Test
    fun `MAL clientId is wired to BuildConfig and OAuth URL uses supplied client ID`() {
        MAL.testClientId = null
        // MAL.clientId delegates to configured BuildConfig.MAL_CLIENT_ID
        assertEquals(ani.dantotsu.BuildConfig.MAL_CLIENT_ID, MAL.clientId)
        MAL.testClientId = "test_mal_client_id"

        // Authorize URL uses supplied client ID
        val syntheticClientId = "test_mal_client_id_987"
        val authUrl = MALOAuth.buildAuthorizeUrl(
            clientId = syntheticClientId,
            codeChallenge = "challenge123",
            state = "state123"
        )
        val uri = URI(authUrl)
        val queryParams = uri.rawQuery.split("&").associate {
            val parts = it.split("=")
            parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals(syntheticClientId, queryParams["client_id"])
        assertEquals("plain", queryParams["code_challenge_method"])
        assertEquals("code", queryParams["response_type"])
        assertEquals("challenge123", queryParams["code_challenge"])
        assertEquals("state123", queryParams["state"])
    }

    @Test
    fun `token exchange and refresh post bodies supply client ID`() = runBlocking {
        var capturedExchangeData: Map<String, String>? = null
        var capturedRefreshData: Map<String, String>? = null

        fakeNetworkClient.responseProvider = { _, data ->
            if (data["grant_type"] == "authorization_code") {
                capturedExchangeData = data
                MAL.MALNetworkResponse(
                    code = 200,
                    bodyString = """{"token_type":"Bearer","expires_in":2592000,"access_token":"new_acc","refresh_token":"new_ref"}"""
                )
            } else if (data["grant_type"] == "refresh_token") {
                capturedRefreshData = data
                MAL.MALNetworkResponse(
                    code = 200,
                    bodyString = """{"token_type":"Bearer","expires_in":2592000,"access_token":"refreshed_acc","refresh_token":"refreshed_ref"}"""
                )
            } else {
                MAL.MALNetworkResponse(code = 500, bodyString = "{}")
            }
        }

        MAL.exchangeAuthorizationCode("test_code", "test_verifier")
        assertNotNull(capturedExchangeData)
        assertEquals(MAL.clientId, capturedExchangeData?.get("client_id"))
        assertEquals("test_code", capturedExchangeData?.get("code"))
        assertEquals("test_verifier", capturedExchangeData?.get("code_verifier"))

        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "exp_acc", "valid_refresh_token")
        MAL.refreshToken(force = true)
        assertNotNull(capturedRefreshData)
        assertEquals(MAL.clientId, capturedRefreshData?.get("client_id"))
        assertEquals("valid_refresh_token", capturedRefreshData?.get("refresh_token"))
    }

    @Test
    fun `buildAuthorizeUrl percent-encodes all query parameters`() {
        val customRedirect = "editotsu://mal/callback?param=1&foo=bar"
        val authUrl = MALOAuth.buildAuthorizeUrl(
            clientId = "client id with space",
            codeChallenge = "challenge+with/special",
            state = "state=test",
            redirectUri = customRedirect
        )
        assertTrue(authUrl.contains("client+id+with+space") || authUrl.contains("client%20id%20with%20space"))
        assertTrue(authUrl.contains("redirect_uri=editotsu%3A%2F%2Fmal%2Fcallback%3Fparam%3D1%26foo%3Dbar"))
    }

    @Test
    fun `PKCE code verifier meets RFC 7636 and MAL requirements`() {
        val verifier1 = MALOAuth.generateCodeVerifier()
        val verifier2 = MALOAuth.generateCodeVerifier()

        assertEquals(128, verifier1.length)
        assertEquals(128, verifier2.length)
        assertNotEquals(verifier1, verifier2)

        val validCharsRegex = Regex("^[A-Za-z0-9_-]+$")
        assertTrue(validCharsRegex.matches(verifier1))
        assertTrue(validCharsRegex.matches(verifier2))
    }

    @Test
    fun `PKCE challenge uses plain method identical to verifier`() {
        val verifier = MALOAuth.generateCodeVerifier()
        val challenge = MALOAuth.generateCodeChallenge(verifier)
        assertEquals(verifier, challenge)
    }

    @Test
    fun `OAuth state is cryptographically random and URL safe`() {
        val state1 = MALOAuth.generateState()
        val state2 = MALOAuth.generateState()

        assertEquals(43, state1.length)
        assertEquals(43, state2.length)
        assertNotEquals(state1, state2)

        val validCharsRegex = Regex("^[A-Za-z0-9_-]+$")
        assertTrue(validCharsRegex.matches(state1))
        assertTrue(validCharsRegex.matches(state2))
    }

    @Test
    fun `MALKeystore fails closed on tampering, corrupt data, or wrong key and never returns plaintext`() {
        val secretPlaintext = """{"access_token":"super_secret_token_12345"}"""

        // 1. Valid encryption produces versioned envelope
        val encryptResult = MALKeystore.encrypt(secretPlaintext)
        assertTrue("Encryption must succeed", encryptResult.isSuccess)
        val encryptedEnvelope = encryptResult.getOrThrow()
        assertTrue("Must start with v1: envelope prefix", encryptedEnvelope.startsWith("v1:"))
        assertFalse("Must not contain plaintext secret", encryptedEnvelope.contains("super_secret_token_12345"))

        // 2. Valid decryption succeeds
        val decryptResult = MALKeystore.decrypt(encryptedEnvelope)
        assertTrue("Decryption must succeed", decryptResult.isSuccess)
        assertEquals(secretPlaintext, decryptResult.getOrThrow())

        // 3. Raw plaintext passed to decrypt must fail closed (NEVER accepted as decrypted text)
        val rawPlaintextAttempt = MALKeystore.decrypt(secretPlaintext)
        assertTrue("Plaintext passed to decrypt must fail", rawPlaintextAttempt.isFailure)

        // 4. Tampered ciphertext must fail closed
        val tamperedEnvelope = encryptedEnvelope.substring(0, encryptedEnvelope.length - 4) + "AAAA"
        val tamperedAttempt = MALKeystore.decrypt(tamperedEnvelope)
        assertTrue("Tampered ciphertext must fail decryption", tamperedAttempt.isFailure)

        // 5. Wrong key must fail closed
        val wrongKeyProvider = MALKeystore.SoftwareKeyProvider()
        MALKeystore.keyProvider = wrongKeyProvider
        val wrongKeyAttempt = MALKeystore.decrypt(encryptedEnvelope)
        assertTrue("Decryption with wrong key must fail", wrongKeyAttempt.isFailure)
    }

    @Test
    fun `SessionStore fails closed if storage save fails and does not create session`() {
        val failingStorage = object : MALOAuth.OAuthSessionStorage {
            override fun saveSession(session: MALOAuth.AuthSession): Boolean = false
            override fun loadSession(): MALOAuth.AuthSession? = null
            override fun clearSession() {}
        }
        MALOAuth.SessionStore.storage = failingStorage
        val session = MALOAuth.SessionStore.createSession()
        assertNull("Session creation must fail closed if persistent storage save fails", session)
    }

    @Test
    fun `SessionStore survives simulated process death across external browser round trip with independent storage instance`() {
        val durableDisk = FakeDurableSessionStorage()

        // Process 1: User starts login flow
        MALOAuth.SessionStore.storage = durableDisk
        val session = MALOAuth.SessionStore.createSession(
            verifier = "durable_verifier_process_death",
            state = "durable_state_process_death"
        )
        assertNotNull(session)
        assertEquals("durable_verifier_process_death", session?.codeVerifier)

        // Process 2 starts -> SessionStore backed by independent storage instance accessing durable disk
        val process2Storage = FakeDurableSessionStorage().apply {
            diskFile.putAll(durableDisk.diskFile)
        }
        MALOAuth.SessionStore.storage = process2Storage

        // Callback arrives in Process 2 -> consumes session successfully
        val consumed = MALOAuth.SessionStore.consumeSession("durable_state_process_death")
        assertEquals("durable_verifier_process_death", consumed)

        // Replay in Process 2 fails immediately
        val replay = MALOAuth.SessionStore.consumeSession("durable_state_process_death")
        assertNull("Replay after process recreation must fail", replay)
    }

    @Test
    fun `SessionStore rejects expired persisted session and cleans up storage`() {
        val durableDisk = FakeDurableSessionStorage()
        MALOAuth.SessionStore.storage = durableDisk

        val baseTime = 1_000_000_000_000L
        val session = MALOAuth.SessionStore.createSession(
            verifier = "expired_verifier",
            state = "expired_state",
            createdAtMs = baseTime
        )
        assertNotNull(session)

        // Simulate process recreation with independent storage instance reading from durable disk
        val process2Storage = FakeDurableSessionStorage().apply {
            diskFile.putAll(durableDisk.diskFile)
        }
        MALOAuth.SessionStore.storage = process2Storage

        // Consume after 11 minutes
        val consumed = MALOAuth.SessionStore.consumeSession(
            callbackState = session!!.state,
            currentTimeMs = baseTime + (11 * 60 * 1000L)
        )
        assertNull("Expired session must return null", consumed)
        assertNull("Durable storage must be cleared on expiration", process2Storage.loadSession())
    }

    @Test
    fun `SessionStore retains pending session on mismatched state until timeout and consumes only on matching state`() {
        val durableDisk = FakeDurableSessionStorage()
        MALOAuth.SessionStore.storage = durableDisk

        val session = MALOAuth.SessionStore.createSession(
            verifier = "legitimate_verifier",
            state = "legitimate_state"
        )
        assertNotNull(session)

        // Attacker / invalid callback with mismatched state arrives
        val mismatchedAttempt = MALOAuth.SessionStore.consumeSession("wrong_state_value")
        assertNull("Mismatched state must be rejected", mismatchedAttempt)

        // Legitimate pending session MUST NOT be destroyed by attacker callback
        assertNotNull("Pending session must be retained in storage after mismatch", durableDisk.loadSession())

        // Legitimate callback with matching state arrives -> consumes successfully
        val legitimateConsume = MALOAuth.SessionStore.consumeSession(session!!.state)
        assertEquals("legitimate_verifier", legitimateConsume)

        // Storage is now empty -> replay fails
        assertNull("Storage must be empty after legitimate consume", durableDisk.loadSession())
        val replayAttempt = MALOAuth.SessionStore.consumeSession(session.state)
        assertNull("Replay must fail", replayAttempt)
    }

    @Test
    fun `parseCallback parses valid editotsu and dantotsu scheme URLs`() {
        val editotsuUrl = "editotsu://mal?code=auth_code_123&state=state_abc"
        val res1 = MALOAuth.parseCallback(editotsuUrl)
        assertTrue(res1 is MALOAuth.CallbackResult.Success)
        val success1 = res1 as MALOAuth.CallbackResult.Success
        assertEquals("auth_code_123", success1.code)
        assertEquals("state_abc", success1.state)

        val dantotsuUrl = "dantotsu://mal?code=auth_code_456&state=state_def"
        val res2 = MALOAuth.parseCallback(dantotsuUrl)
        assertTrue(res2 is MALOAuth.CallbackResult.Success)
        val success2 = res2 as MALOAuth.CallbackResult.Success
        assertEquals("auth_code_456", success2.code)
        assertEquals("state_def", success2.state)
    }

    @Test
    fun `parseCallback maps error string to bounded OAuthErrorReason and redacts raw secret markers`() {
        val capturedLogs = mutableListOf<String>()
        Logger.testLogSink = { msg -> capturedLogs.add(msg) }

        val canaryRawError = "SECRET_CANARY_ERROR_PARAM_98765"
        val canaryErrorDescription = "SECRET_CANARY_DESCRIPTION_43210"
        val session = MALOAuth.SessionStore.createSession(state = "valid_denial_state")
        assertNotNull(session)

        // Parse callback with canary secret error string
        val denialUrl = "editotsu://mal?error=$canaryRawError&error_description=$canaryErrorDescription&state=valid_denial_state"
        val result = MALOAuth.parseCallback(denialUrl)
        assertTrue(result is MALOAuth.CallbackResult.Denied)
        val denied = result as MALOAuth.CallbackResult.Denied

        // Assert error is mapped to bounded enum and does NOT contain raw canary string
        assertEquals(MALOAuth.OAuthErrorReason.OTHER, denied.errorReason)
        assertEquals("valid_denial_state", denied.state)

        // Consume session and verify log simulation does not leak raw canary error
        val consumed = MALOAuth.SessionStore.consumeSession(denied.state)
        assertEquals(session!!.codeVerifier, consumed)

        Logger.log("MAL Login: Access denied or cancelled by user (${denied.errorReason.name})")

        for (log in capturedLogs) {
            assertFalse("Log must not contain raw error: $log", log.contains(canaryRawError))
            assertFalse("Log must not contain raw description: $log", log.contains(canaryErrorDescription))
        }
    }

    @Test
    fun `parseCallback never leaks raw query secrets on malformed URI`() {
        val canarySecretCode = "SECRET_CANARY_CODE_98765"
        val canarySecretState = "SECRET_CANARY_STATE_54321"
        val malformedUri = "editotsu://mal?code=$canarySecretCode&state=$canarySecretState&invalid_percent=%%%"

        val result = MALOAuth.parseCallback(malformedUri)
        assertTrue(result is MALOAuth.CallbackResult.Invalid)
        val invalid = result as MALOAuth.CallbackResult.Invalid

        assertEquals(MALOAuth.CallbackErrorReason.MALFORMED_URI, invalid.reason)
        assertFalse(invalid.reason.name.contains(canarySecretCode))
        assertFalse(invalid.reason.name.contains(canarySecretState))
    }

    @Test
    fun `token parsing and refresh error handling never leaks canary tokens to logger`() = runBlocking {
        val capturedLogs = mutableListOf<String>()
        Logger.testLogSink = { msg -> capturedLogs.add(msg) }

        val canaryAccessToken = "CANARY_SECRET_ACCESS_TOKEN_XYZ987"
        val canaryRefreshToken = "CANARY_SECRET_REFRESH_TOKEN_ABC123"

        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "T1", "R1")

        fakeNetworkClient.responseProvider = { _, _ ->
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","access_token":"$canaryAccessToken","refresh_token":"$canaryRefreshToken","invalid_json_trailing":"""
            )
        }

        val result = MAL.refreshToken(force = true, failedAccessToken = "T1")
        assertNull(result)

        assertTrue("At least one log message should have been recorded", capturedLogs.isNotEmpty())
        for (log in capturedLogs) {
            assertFalse("Log must not contain canary access token: $log", log.contains(canaryAccessToken))
            assertFalse("Log must not contain canary refresh token: $log", log.contains(canaryRefreshToken))
        }
    }

    @Test
    fun `saveResponse persists exact epoch millis and does not double-adjust absolute timestamps`() {
        val now = 1_700_000_000_000L

        val relativeToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = 2678400L,
            accessToken = "token_abc",
            refreshToken = "refresh_xyz"
        )
        val saved = MAL.saveResponse(relativeToken, currentTimeMs = now)
        assertNotNull(saved)
        val expectedExpiresAt = now + (2678400L * 1000L)
        assertEquals(expectedExpiresAt, saved?.expiresIn)
        assertEquals("token_abc", fakeTokenStore.storedToken?.accessToken)
        assertEquals("token_abc", MAL.token)

        val savedAgain = MAL.saveResponse(saved!!, currentTimeMs = now + 1000L)
        assertEquals("Absolute timestamp must remain unchanged", expectedExpiresAt, savedAgain?.expiresIn)
    }

    @Test
    fun `refreshToken deduplicates concurrent forced refreshes for the same failed access token at lock contention seam`() = runBlocking {
        val now = 1_700_000_000_000L
        val initialToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = now + 1_000_000L,
            accessToken = "T1_EXPIRED_ON_SERVER",
            refreshToken = "R1"
        )
        fakeTokenStore.storedToken = initialToken
        MAL.token = initialToken.accessToken

        val callerAEnteredNetwork = CompletableDeferred<Unit>()
        val callerBAttemptingLock = CompletableDeferred<Unit>()
        val allowCallerAToFinish = CompletableDeferred<Unit>()

        fakeNetworkClient.responseProvider = { _, _ ->
            callerAEnteredNetwork.complete(Unit)
            allowCallerAToFinish.await()
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T2_FRESH","refresh_token":"R2"}"""
            )
        }

        MAL.onRefreshWaitingForLock = {
            if (callerAEnteredNetwork.isCompleted) {
                callerBAttemptingLock.complete(Unit)
            }
        }

        val deferredA = async(Dispatchers.Default) {
            MAL.refreshToken(force = true, failedAccessToken = "T1_EXPIRED_ON_SERVER")
        }

        withTimeout(5000) { callerAEnteredNetwork.await() }

        val deferredB = async(Dispatchers.Default) {
            MAL.refreshToken(force = true, failedAccessToken = "T1_EXPIRED_ON_SERVER")
        }

        withTimeout(5000) { callerBAttemptingLock.await() }
        allowCallerAToFinish.complete(Unit)

        val results = withTimeout(5000) { awaitAll(deferredA, deferredB) }

        assertEquals("Only one network refresh must be executed", 1, fakeNetworkClient.postCount.get())
        assertEquals("T2_FRESH", results[0]?.accessToken)
        assertEquals("T2_FRESH", results[1]?.accessToken)
        assertEquals("T2_FRESH", MAL.token)
        assertEquals("T2_FRESH", fakeTokenStore.storedToken?.accessToken)
    }

    @Test
    fun `refreshToken clears credentials on permanent 400 or 401 rejection`() = runBlocking {
        val initialToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = 1_700_000_000_000L,
            accessToken = "T1",
            refreshToken = "R1_REVOKED"
        )
        fakeTokenStore.storedToken = initialToken
        fakeTokenStore.username = "mal_test_user"
        MAL.token = "T1"
        MAL.username = "mal_test_user"

        fakeNetworkClient.responseProvider = { _, _ ->
            MAL.MALNetworkResponse(
                code = 400,
                bodyString = """{"error":"invalid_grant","message":"Refresh token is invalid or expired"}"""
            )
        }

        val result = MAL.refreshToken(force = true, failedAccessToken = "T1")
        assertNull("Refresh must return null on 400 rejection", result)
        assertNull("In-memory token must be cleared", MAL.token)
        assertNull("In-memory username must be cleared", MAL.username)
        assertNull("Token store must be cleared", fakeTokenStore.storedToken)
        assertFalse("isLoggedIn must be false", MAL.isLoggedIn())
    }

    @Test
    fun `refreshToken preserves stored refresh token on transient network failure`() = runBlocking {
        val initialToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = 1_700_000_000_000L,
            accessToken = "T1",
            refreshToken = "R1_PERSISTED"
        )
        fakeTokenStore.storedToken = initialToken
        MAL.token = "T1"

        fakeNetworkClient.responseProvider = { _, _ ->
            throw java.io.IOException("Connection reset by peer")
        }

        val result = MAL.refreshToken(force = true, failedAccessToken = "T1")
        assertNull("Failed network refresh returns null", result)
        assertEquals("Stored refresh token must NOT be cleared on transient network error", "R1_PERSISTED", fakeTokenStore.storedToken?.refreshToken)
    }

    @Test
    fun `getSavedToken returns false and sets token to null on expired token transient refresh failure without clearing stored refresh token`() = runBlocking {
        val now = 1_700_000_000_000L
        val expiredToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = now - 1000L,
            accessToken = "T_EXPIRED",
            refreshToken = "R_VALID"
        )
        fakeTokenStore.storedToken = expiredToken
        fakeTokenStore.username = "user_offline"

        fakeNetworkClient.responseProvider = { _, _ ->
            throw java.io.IOException("No route to host")
        }

        val loaded = MAL.getSavedToken()
        assertFalse("getSavedToken must return false when token is expired and cannot be refreshed", loaded)
        assertNull("Expired token must NOT be set as active MAL.token", MAL.token)
        assertEquals("Stored refresh token must remain intact for future retries", "R_VALID", fakeTokenStore.storedToken?.refreshToken)
    }

    @Test
    fun `removeSavedToken clears all auth state, profiles, and pending sessions`() {
        MAL.token = "active_token"
        MAL.username = "active_user"
        MAL.avatar = "https://example.com/avatar.jpg"
        MAL.userid = 12345
        MAL.episodesWatched = 100
        MAL.chaptersRead = 50
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "active_token", "active_refresh")
        fakeTokenStore.username = "active_user"
        fakeTokenStore.avatar = "https://example.com/avatar.jpg"

        MALOAuth.SessionStore.createSession()
        assertNotNull(MALOAuth.SessionStore.getActiveSession())

        MAL.removeSavedToken()

        assertNull(MAL.token)
        assertNull(MAL.username)
        assertNull(MAL.avatar)
        assertNull(MAL.userid)
        assertNull(MAL.episodesWatched)
        assertNull(MAL.chaptersRead)
        assertNull(fakeTokenStore.storedToken)
        assertNull(fakeTokenStore.username)
        assertNull(fakeTokenStore.avatar)
        assertNull(MALOAuth.SessionStore.getActiveSession())
        assertFalse(MAL.isLoggedIn())
    }

    @Test
    fun `logout during in-flight refresh discards response and prevents token resurrection`() = runBlocking {
        val initialToken = MAL.ResponseToken("Bearer", 1000L, "T1", "R1")
        fakeTokenStore.storedToken = initialToken
        fakeTokenStore.username = "mal_user"
        MAL.token = "T1"
        MAL.username = "mal_user"

        val networkEntered = CompletableDeferred<Unit>()
        val allowNetworkToFinish = CompletableDeferred<Unit>()

        fakeNetworkClient.responseProvider = { _, _ ->
            networkEntered.complete(Unit)
            allowNetworkToFinish.await()
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T2_STALE","refresh_token":"R2_STALE"}"""
            )
        }

        val refreshJob = async(Dispatchers.Default) {
            MAL.refreshToken(force = true, failedAccessToken = "T1")
        }

        withTimeout(5000) { networkEntered.await() }

        MAL.removeSavedToken()
        assertNull(MAL.token)
        assertNull(fakeTokenStore.storedToken)
        assertFalse(MAL.isLoggedIn())

        allowNetworkToFinish.complete(Unit)
        val result = withTimeout(5000) { refreshJob.await() }

        assertNull("Stale refresh response must be discarded", result)
        assertNull("In-memory token must remain null", MAL.token)
        assertNull("Stored token must remain null", fakeTokenStore.storedToken)
        assertFalse("isLoggedIn must remain false", MAL.isLoggedIn())
    }

    @Test
    fun `logout during in-flight authorization code exchange discards response and prevents token resurrection`() = runBlocking {
        val networkEntered = CompletableDeferred<Unit>()
        val allowNetworkToFinish = CompletableDeferred<Unit>()

        fakeNetworkClient.responseProvider = { _, _ ->
            networkEntered.complete(Unit)
            allowNetworkToFinish.await()
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T_EXCHANGED","refresh_token":"R_EXCHANGED"}"""
            )
        }

        val exchangeJob = async(Dispatchers.Default) {
            MAL.exchangeAuthorizationCode("code123", "verifier123")
        }

        withTimeout(5000) { networkEntered.await() }
        MAL.removeSavedToken()
        allowNetworkToFinish.complete(Unit)
        val success = withTimeout(5000) { exchangeJob.await() }

        assertFalse("Stale exchange must be rejected", success)
        assertNull("In-memory token must remain null", MAL.token)
        assertNull("Stored token must remain null", fakeTokenStore.storedToken)
    }

    @Test
    fun `true concurrent token commit vs logout test proves logout waits for authLock then clears storage`() {
        val tokenToSave = MAL.ResponseToken("Bearer", 1000L, "T_CONCURRENT", "R_CONCURRENT")
        val saveEntered = java.util.concurrent.CountDownLatch(1)
        val logoutAttempting = java.util.concurrent.CountDownLatch(1)
        val allowSaveToFinish = java.util.concurrent.CountDownLatch(1)
        val logoutFinished = java.util.concurrent.atomic.AtomicBoolean(false)

        fakeTokenStore.onSaveHook = {
            saveEntered.countDown()
            allowSaveToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }

        MAL.onPreAuthLockHook = {
            logoutAttempting.countDown()
        }

        val threadA = Thread {
            try {
                MAL.saveResponse(tokenToSave)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        val threadB = Thread {
            try {
                MAL.removeSavedToken()
                logoutFinished.set(true)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        try {
            threadA.start()
            val entered = saveEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("saveResponse must enter saveToken within timeout", entered)

            threadB.start()
            val attempting = logoutAttempting.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("removeSavedToken must attempt lock entry", attempting)
            assertFalse("Logout cannot finish while saveResponse owns authLock", logoutFinished.get())
        } finally {
            allowSaveToFinish.countDown()
            threadA.join(5000)
            threadB.join(5000)
        }

        assertFalse("Thread A must not be alive", threadA.isAlive)
        assertFalse("Thread B must not be alive", threadB.isAlive)
        assertTrue("Logout must complete after save finishes", logoutFinished.get())
        assertNull("Stored token must be null after logout completes", fakeTokenStore.storedToken)
        assertNull("Active token must be null after logout completes", MAL.token)
        assertFalse("isLoggedIn must be false", MAL.isLoggedIn())
    }

    @Test
    fun `true concurrent profile commit vs logout test proves logout waits for authLock then clears profile state`() {
        val gen = MAL.currentAuthGeneration
        val commitEntered = java.util.concurrent.CountDownLatch(1)
        val logoutAttempting = java.util.concurrent.CountDownLatch(1)
        val allowCommitToFinish = java.util.concurrent.CountDownLatch(1)
        val logoutFinished = java.util.concurrent.atomic.AtomicBoolean(false)

        MAL.onProfileCommitHook = {
            commitEntered.countDown()
            allowCommitToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }

        MAL.onPreAuthLockHook = {
            logoutAttempting.countDown()
        }

        val threadA = Thread {
            try {
                MAL.commitProfile(
                    userId = 12345,
                    username = "concurrent_user",
                    avatar = "https://example.com/concurrent.jpg",
                    episodesWatched = 42,
                    chaptersRead = 84,
                    generation = gen
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        val threadB = Thread {
            try {
                MAL.removeSavedToken()
                logoutFinished.set(true)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        try {
            threadA.start()
            val entered = commitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("commitProfile must enter hook within timeout", entered)

            threadB.start()
            val attempting = logoutAttempting.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("removeSavedToken must attempt lock entry", attempting)
            assertFalse("Logout cannot finish while commitProfile owns authLock", logoutFinished.get())
        } finally {
            allowCommitToFinish.countDown()
            threadA.join(5000)
            threadB.join(5000)
        }

        assertFalse("Thread A must not be alive", threadA.isAlive)
        assertFalse("Thread B must not be alive", threadB.isAlive)
        assertTrue("Logout must complete after commit finishes", logoutFinished.get())
        assertNull("Username must be null after logout", MAL.username)
        assertNull("Avatar must be null after logout", MAL.avatar)
        assertNull("User ID must be null after logout", MAL.userid)
        assertNull("Stored username must be null after logout", fakeTokenStore.username)
        assertNull("Stored avatar must be null after logout", fakeTokenStore.avatar)
    }

    @Test
    fun `actual getSavedToken concurrent logout race prevents token and profile restoration`() = runBlocking {
        val now = System.currentTimeMillis()
        val savedToken = MAL.ResponseToken("Bearer", now + 1_000_000_000L, "T_RESTORE_RACE", "R_RESTORE")
        fakeTokenStore.storedToken = savedToken
        fakeTokenStore.username = "stale_user"
        fakeTokenStore.avatar = "https://example.com/stale.jpg"

        val storeReadEntered = java.util.concurrent.CountDownLatch(1)
        val allowReadToFinish = java.util.concurrent.CountDownLatch(1)

        fakeTokenStore.onGetSavedTokenHook = { token ->
            storeReadEntered.countDown()
            allowReadToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
            token
        }

        val restoreJob = async(Dispatchers.Default) {
            MAL.getSavedToken()
        }

        try {
            val entered = storeReadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("getSavedToken must enter token store read within timeout", entered)

            // Concurrent logout runs and fully clears state while getSavedToken is in-flight
            MAL.removeSavedToken()
            assertNull("Active token must be cleared on logout", MAL.token)
            assertNull("Active username must be cleared on logout", MAL.username)
            assertNull("Active avatar must be cleared on logout", MAL.avatar)
            assertNull("Stored token must be cleared on logout", fakeTokenStore.storedToken)
            assertNull("Stored username must be cleared on logout", fakeTokenStore.username)
            assertNull("Stored avatar must be cleared on logout", fakeTokenStore.avatar)
            assertFalse("isLoggedIn must be false", MAL.isLoggedIn())
        } finally {
            allowReadToFinish.countDown()
        }

        val restored = withTimeout(5000) { restoreJob.await() }

        assertFalse("getSavedToken must return false when generation changed during read", restored)
        assertNull("Active token must remain null", MAL.token)
        assertNull("Active username must remain null", MAL.username)
        assertNull("Active avatar must remain null", MAL.avatar)
        assertNull("Stored token must remain null", fakeTokenStore.storedToken)
        assertNull("Stored username must remain null", fakeTokenStore.username)
        assertNull("Stored avatar must remain null", fakeTokenStore.avatar)
        assertFalse("isLoggedIn must remain false", MAL.isLoggedIn())
    }

    @Test
    fun `actual refreshToken force-dedup fast path concurrent logout race prevents token resurrection`() = runBlocking {
        val now = 1_700_000_000_000L
        val refreshedToken = MAL.ResponseToken("Bearer", now + 1_000_000L, "T_NEW_DEDUP", "R_NEW")
        fakeTokenStore.storedToken = refreshedToken
        fakeTokenStore.username = "mal_user"

        val storeReadEntered = java.util.concurrent.CountDownLatch(1)
        val allowReadToFinish = java.util.concurrent.CountDownLatch(1)

        fakeTokenStore.onGetSavedTokenHook = { token ->
            storeReadEntered.countDown()
            allowReadToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
            token
        }

        val refreshJob = async(Dispatchers.Default) {
            // failedAccessToken differs from storedToken.accessToken ("T_OLD" vs "T_NEW_DEDUP"), forcing dedup fast path
            MAL.refreshToken(force = true, failedAccessToken = "T_OLD")
        }

        try {
            val entered = storeReadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("refreshToken must enter token store read within timeout", entered)

            // Concurrent logout runs while dedup fast path is in-flight
            MAL.removeSavedToken()
            assertNull(MAL.token)
            assertNull(fakeTokenStore.storedToken)
            assertFalse(MAL.isLoggedIn())
        } finally {
            allowReadToFinish.countDown()
        }

        val result = withTimeout(5000) { refreshJob.await() }

        assertNull("refreshToken must return null on stale generation in dedup path", result)
        assertNull("Active token must remain null", MAL.token)
        assertNull("Stored token must remain null", fakeTokenStore.storedToken)
        assertFalse("isLoggedIn must remain false", MAL.isLoggedIn())
        assertEquals("Zero network calls must be made on fast path", 0, fakeNetworkClient.postCount.get())
    }

    @Test
    fun `actual refreshToken non-force unexpired fast path concurrent logout race prevents token resurrection`() = runBlocking {
        val now = System.currentTimeMillis()
        val validToken = MAL.ResponseToken("Bearer", now + 1_000_000_000L, "T_VALID", "R_VALID")
        fakeTokenStore.storedToken = validToken
        fakeTokenStore.username = "mal_user"

        val storeReadEntered = java.util.concurrent.CountDownLatch(1)
        val allowReadToFinish = java.util.concurrent.CountDownLatch(1)

        fakeTokenStore.onGetSavedTokenHook = { token ->
            storeReadEntered.countDown()
            allowReadToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
            token
        }

        val refreshJob = async(Dispatchers.Default) {
            // force = false with unexpired token triggers unexpired fast path
            MAL.refreshToken(force = false)
        }

        try {
            val entered = storeReadEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("refreshToken must enter token store read within timeout", entered)

            // Concurrent logout runs while unexpired fast path is in-flight
            MAL.removeSavedToken()
            assertNull(MAL.token)
            assertNull(fakeTokenStore.storedToken)
            assertFalse(MAL.isLoggedIn())
        } finally {
            allowReadToFinish.countDown()
        }

        val result = withTimeout(5000) { refreshJob.await() }

        assertNull("refreshToken must return null on stale generation in unexpired path", result)
        assertNull("Active token must remain null", MAL.token)
        assertNull("Stored token must remain null", fakeTokenStore.storedToken)
        assertFalse("isLoggedIn must remain false", MAL.isLoggedIn())
        assertEquals("Zero network calls must be made on fast path", 0, fakeNetworkClient.postCount.get())
    }

    @Test
    fun `getSavedToken helper cannot restore token or profile after logout`() = runBlocking {
        val now = 1_700_000_000_000L
        val savedToken = MAL.ResponseToken("Bearer", now + 1_000_000L, "T_RESTORE_RACE", "R_RESTORE")
        fakeTokenStore.storedToken = savedToken
        fakeTokenStore.username = "stale_username"
        fakeTokenStore.avatar = "https://example.com/stale.jpg"

        val genBefore = MAL.currentAuthGeneration
        // Simulate logout occurring before commitRestoredSession
        MAL.removeSavedToken()

        val restored = MAL.commitRestoredSession(
            accessToken = savedToken.accessToken,
            username = "stale_username",
            avatar = "https://example.com/stale.jpg",
            generation = genBefore
        )

        assertFalse("Restoration must fail when generation is stale", restored)
        assertNull("Token must remain null", MAL.token)
        assertNull("Username must remain null", MAL.username)
        assertNull("Avatar must remain null", MAL.avatar)
        assertFalse("isLoggedIn must be false", MAL.isLoggedIn())
    }

    @Test
    fun `refreshToken force-dedup helper cannot restore token after logout`() = runBlocking {
        val savedToken = MAL.ResponseToken("Bearer", 1000L, "T_DEDUP_RACE", "R_DEDUP")
        val staleGen = MAL.currentAuthGeneration
        MAL.removeSavedToken()

        val activated = MAL.activateSavedToken(savedToken, staleGen)
        assertNull("activateSavedToken must return null on stale generation", activated)
        assertNull("Token must not be resurrected", MAL.token)
        assertFalse("isLoggedIn must be false", MAL.isLoggedIn())
    }

    @Test
    fun `refreshToken non-force unexpired helper cannot restore token after logout`() = runBlocking {
        val now = 1_700_000_000_000L
        val savedToken = MAL.ResponseToken("Bearer", now + 1_000_000L, "T_UNEXPIRED_RACE", "R_UNEXPIRED")
        val staleGen = MAL.currentAuthGeneration
        MAL.removeSavedToken()

        val activated = MAL.activateSavedToken(savedToken, staleGen)
        assertNull("activateSavedToken must return null on stale generation", activated)
        assertNull("Token must not be resurrected", MAL.token)
        assertFalse("isLoggedIn must be false", MAL.isLoggedIn())
    }

    @Test
    fun `cancellation propagates during exchangeAuthorizationCode`() = runBlocking {
        val enteredNetwork = CompletableDeferred<Unit>()
        fakeNetworkClient.responseProvider = { _, _ ->
            enteredNetwork.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        val exchangeJob = launch(Dispatchers.Default) {
            MAL.exchangeAuthorizationCode("code123", "verifier123")
        }
        withTimeout(5000) { enteredNetwork.await() }
        exchangeJob.cancelAndJoin()
        assertTrue("exchangeJob must be cancelled", exchangeJob.isCancelled)
        assertNull("Token must not be stored on cancellation", fakeTokenStore.storedToken)
    }

    @Test
    fun `cancellation propagates during getSavedToken entering refresh`() = runBlocking {
        val now = 1_700_000_000_000L
        val initialToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = now - 1000L,
            accessToken = "T_EXPIRED",
            refreshToken = "R_INIT"
        )
        fakeTokenStore.storedToken = initialToken

        val enteredRefresh = CompletableDeferred<Unit>()
        fakeNetworkClient.responseProvider = { _, _ ->
            enteredRefresh.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        val getSavedJob = launch(Dispatchers.Default) {
            MAL.getSavedToken()
        }
        withTimeout(5000) { enteredRefresh.await() }
        getSavedJob.cancelAndJoin()
        assertTrue("getSavedJob must be cancelled", getSavedJob.isCancelled)
        assertEquals("T_EXPIRED", fakeTokenStore.storedToken?.accessToken)
        assertNull("Active in-memory token must not be set", MAL.token)
    }

    @Test
    fun `cancellation propagates while waiting on refreshMutex with zero tokenStore mutation`() = runBlocking {
        val now = 1_700_000_000_000L
        val initialToken = MAL.ResponseToken(
            tokenType = "Bearer",
            expiresIn = now + 1_000_000L,
            accessToken = "T1",
            refreshToken = "R1"
        )
        fakeTokenStore.storedToken = initialToken
        MAL.token = "T1"

        val holderEnteredNetwork = CompletableDeferred<Unit>()
        val waiterAttemptingLock = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        fakeNetworkClient.responseProvider = { _, _ ->
            holderEnteredNetwork.complete(Unit)
            releaseHolder.await()
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T_NEW","refresh_token":"R_NEW"}"""
            )
        }

        MAL.onRefreshWaitingForLock = {
            if (holderEnteredNetwork.isCompleted) {
                waiterAttemptingLock.complete(Unit)
            }
        }

        val jobHolder = launch(Dispatchers.Default) {
            MAL.refreshToken(force = true, failedAccessToken = "T1")
        }
        withTimeout(5000) { holderEnteredNetwork.await() }

        val jobWaiter = launch(Dispatchers.Default) {
            MAL.refreshToken(force = true, failedAccessToken = "T1")
        }
        withTimeout(5000) { waiterAttemptingLock.await() }

        jobWaiter.cancelAndJoin()
        assertTrue("jobWaiter must be cancelled while waiting on mutex", jobWaiter.isCancelled)

        releaseHolder.complete(Unit)
        withTimeout(5000) { jobHolder.join() }

        assertEquals("T_NEW", fakeTokenStore.storedToken?.accessToken)
        assertEquals("T_NEW", MAL.token)
    }

    @Test
    fun `cancellation propagates during 401-triggered refresh with zero tokenStore mutation`() = runBlocking {
        MAL.token = "T1"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "T1", "R1")

        val refreshEntered = CompletableDeferred<Unit>()
        fakeNetworkClient.responseProvider = { _, _ ->
            refreshEntered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        val queries = MALQueries()
        val queryJob = launch(Dispatchers.Default) {
            queries.executeRequest {
                createNiceResponse(401, """{"error":"token_expired"}""")
            }
        }

        withTimeout(5000) { refreshEntered.await() }
        queryJob.cancelAndJoin()
        assertTrue("queryJob must be cancelled during 401 refresh", queryJob.isCancelled)
        assertEquals("T1", fakeTokenStore.storedToken?.accessToken)
    }

    @Test
    fun `getUserData cancellation during network call leaves zero profile mutations and propagates CancellationException`() = runBlocking {
        MAL.token = "valid_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")

        val enteredNetwork = CompletableDeferred<Unit>()
        MALQueries.testHttpHandler = { url, _, _, _ ->
            if (url.contains("/users/@me")) {
                if (enteredNetwork.isActive) {
                    enteredNetwork.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } else {
                    createNiceResponse(200, "{}")
                }
            } else {
                createNiceResponse(200, "{}")
            }
        }

        val job = launch(Dispatchers.Default) {
            MAL.query.getUserData()
        }

        withTimeout(5000) { enteredNetwork.await() }
        job.cancelAndJoin()

        assertTrue("getUserData job must be cancelled", job.isCancelled)
        assertNull("username must not be populated on cancellation", MAL.username)
        assertNull("avatar must not be populated on cancellation", MAL.avatar)
        assertNull("userid must not be populated on cancellation", MAL.userid)
        assertNull("episodesWatched must not be populated on cancellation", MAL.episodesWatched)
        assertNull("chaptersRead must not be populated on cancellation", MAL.chaptersRead)
        assertNull("stored username must not be populated on cancellation", fakeTokenStore.username)
        assertNull("stored avatar must not be populated on cancellation", fakeTokenStore.avatar)
    }

    @Test
    fun `getUserData cancellation during Jikan avatar fallback leaves zero profile mutations`() = runBlocking {
        MAL.token = "valid_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")

        val enteredJikan = CompletableDeferred<Unit>()
        MALQueries.testHttpHandler = { url, _, _, _ ->
            if (url.contains("/users/@me")) {
                createNiceResponse(200, """{"id":100,"name":"jikan_cancel_user","picture":null,"anime_statistics":{"num_episodes":10}}""")
            } else {
                createNiceResponse(200, "{}")
            }
        }
        JikanQueries.testHttpHandler = { _ ->
            if (enteredJikan.isActive) {
                enteredJikan.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } else {
                createNiceResponse(200, "{}")
            }
        }

        val job = launch(Dispatchers.Default) {
            MAL.query.getUserData()
        }

        withTimeout(5000) { enteredJikan.await() }
        job.cancelAndJoin()
        assertTrue("getUserData job must be cancelled", job.isCancelled)
        assertNull("username must not be populated", MAL.username)
        assertNull("avatar must not be populated", MAL.avatar)
        assertNull("stored username must not be populated", fakeTokenStore.username)
    }

    @Test
    fun `getUserData cancellation during statistics estimate fallback leaves zero profile mutations`() = runBlocking {
        MAL.token = "valid_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")

        val enteredEstimate = CompletableDeferred<Unit>()
        MALQueries.testHttpHandler = { url, _, _, _ ->
            if (url.contains("/animelist")) {
                if (enteredEstimate.isActive) {
                    enteredEstimate.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } else {
                    createNiceResponse(200, "{}")
                }
            } else if (url.contains("/users/@me")) {
                createNiceResponse(200, """{"id":500,"name":"estimate_cancel_user","picture":"https://example.com/pfp.jpg","anime_statistics":null,"manga_statistics":null}""")
            } else {
                createNiceResponse(200, "{}")
            }
        }

        val job = launch(Dispatchers.Default) {
            MAL.query.getUserData()
        }

        withTimeout(5000) { enteredEstimate.await() }
        job.cancelAndJoin()

        assertTrue("getUserData job must be cancelled during estimate fallback", job.isCancelled)
        assertNull("username must not be committed", MAL.username)
        assertNull("avatar must not be committed", MAL.avatar)
        assertNull("userid must not be committed", MAL.userid)
        assertNull("episodesWatched must not be committed", MAL.episodesWatched)
        assertNull("chaptersRead must not be committed", MAL.chaptersRead)
        assertNull("stored username must not be committed", fakeTokenStore.username)
        assertNull("stored avatar must not be committed", fakeTokenStore.avatar)
    }

    @Test
    fun `official MAL picture wins when non-blank without invoking Jikan`() = runBlocking {
        MAL.token = "valid_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")

        MALQueries.testHttpHandler = { url, _, _, _ ->
            if (url.contains("/users/@me")) {
                createNiceResponse(
                    200,
                    """{"id":200,"name":"official_picture_user","picture":"https://cdn.myanimelist.net/images/userimages/official.jpg","anime_statistics":{"num_episodes":50},"manga_statistics":{"num_chapters_read":25}}"""
                )
            } else {
                createNiceResponse(404, "{}")
            }
        }
        JikanQueries.testHttpHandler = { _ ->
            throw AssertionError("Jikan should not be invoked when official picture is non-blank")
        }

        val success = MAL.query.getUserData()
        assertTrue("getUserData must succeed", success)
        assertEquals("official_picture_user", MAL.username)
        assertEquals("https://cdn.myanimelist.net/images/userimages/official.jpg", MAL.avatar)
        assertEquals(200, MAL.userid)
        assertEquals(50, MAL.episodesWatched)
        assertEquals(25, MAL.chaptersRead)
        assertEquals("official_picture_user", fakeTokenStore.username)
        assertEquals("https://cdn.myanimelist.net/images/userimages/official.jpg", fakeTokenStore.avatar)
    }

    @Test
    fun `official MAL picture absent falls back gracefully to Jikan user profile picture`() = runBlocking {
        MAL.token = "valid_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")

        MALQueries.testHttpHandler = { url, _, _, _ ->
            if (url.contains("/users/@me")) {
                createNiceResponse(
                    200,
                    """{"id":300,"name":"jikan_fallback_user","picture":null,"anime_statistics":{"num_episodes":10},"manga_statistics":{"num_chapters_read":5}}"""
                )
            } else {
                createNiceResponse(404, "{}")
            }
        }
        JikanQueries.testHttpHandler = { _ ->
            createNiceResponse(
                200,
                """{"data":{"username":"jikan_fallback_user","images":{"jpg":{"image_url":"https://cdn.myanimelist.net/images/userimages/jikan.jpg"}}}}"""
            )
        }

        val success = MAL.query.getUserData()
        assertTrue("getUserData must succeed", success)
        assertEquals("jikan_fallback_user", MAL.username)
        assertEquals("https://cdn.myanimelist.net/images/userimages/jikan.jpg", MAL.avatar)
        assertEquals(300, MAL.userid)
        assertEquals(10, MAL.episodesWatched)
        assertEquals(5, MAL.chaptersRead)
        assertEquals("jikan_fallback_user", fakeTokenStore.username)
        assertEquals("https://cdn.myanimelist.net/images/userimages/jikan.jpg", fakeTokenStore.avatar)
    }

    @Test
    fun `official MAL picture absent and Jikan failure succeeds login with null avatar and placeholder fallback`() = runBlocking {
        MAL.token = "valid_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")

        MALQueries.testHttpHandler = { url, _, _, _ ->
            if (url.contains("/users/@me")) {
                createNiceResponse(
                    200,
                    """{"id":400,"name":"no_picture_user","picture":null,"anime_statistics":{"num_episodes":0},"manga_statistics":{"num_chapters_read":0}}"""
                )
            } else {
                createNiceResponse(404, "{}")
            }
        }
        JikanQueries.testHttpHandler = { _ ->
            throw java.io.IOException("Jikan network failure")
        }

        val success = MAL.query.getUserData()
        assertTrue("getUserData must succeed even when Jikan avatar resolution fails", success)
        assertEquals("no_picture_user", MAL.username)
        assertNull("Avatar must be null when both MAL and Jikan return no picture", MAL.avatar)
        assertNull("Stored avatar must be null", fakeTokenStore.avatar)
    }

    @Test
    fun `logout clears resolved avatar from memory, tokenStore, and preferences`() = runBlocking {
        MAL.token = "valid_token"
        MAL.username = "avatar_logout_user"
        MAL.avatar = "https://example.com/avatar.jpg"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "valid_token", "valid_refresh")
        fakeTokenStore.username = "avatar_logout_user"
        fakeTokenStore.avatar = "https://example.com/avatar.jpg"

        assertTrue(MAL.isLoggedIn())

        MAL.removeSavedToken()

        assertNull(MAL.token)
        assertNull(MAL.username)
        assertNull(MAL.avatar)
        assertNull(fakeTokenStore.username)
        assertNull(fakeTokenStore.avatar)
        assertNull(fakeTokenStore.storedToken)
        assertFalse(MAL.isLoggedIn())
    }

    @Test
    fun `executeRequest refreshes token on 401 and retries with new token once`() = runBlocking {
        MAL.token = "T1_EXPIRED"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "T1_EXPIRED", "R1")

        fakeNetworkClient.responseProvider = { _, _ ->
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T2_FRESH","refresh_token":"R2"}"""
            )
        }

        var attempts = 0
        val queries = MALQueries()
        val response = queries.executeRequest {
            attempts++
            if (attempts == 1) {
                assertEquals("T1_EXPIRED", MAL.token)
                createNiceResponse(401, """{"error":"token_expired"}""")
            } else {
                assertEquals("T2_FRESH", MAL.token)
                createNiceResponse(200, """{"data":"ok"}""")
            }
        }

        assertEquals(2, attempts)
        assertEquals(200, response.code)
        assertEquals("T2_FRESH", MAL.token)
        assertEquals(1, fakeNetworkClient.postCount.get())
    }

    @Test
    fun `executeRequest does not loop infinitely if retried request also returns 401`() = runBlocking {
        MAL.token = "T1_EXPIRED"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "T1_EXPIRED", "R1")

        fakeNetworkClient.responseProvider = { _, _ ->
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T2_FRESH","refresh_token":"R2"}"""
            )
        }

        var attempts = 0
        val queries = MALQueries()
        val response = queries.executeRequest {
            attempts++
            createNiceResponse(401, """{"error":"permanently_unauthorized"}""")
        }

        assertEquals(2, attempts)
        assertEquals(401, response.code)
        assertEquals(1, fakeNetworkClient.postCount.get())
    }

    @Test
    fun `downstream MAL call site refreshes token on 401 and retries with refreshed Authorization header`() = runBlocking {
        MAL.token = "T_OLD_BEARER"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "T_OLD_BEARER", "R_REFRESH")

        fakeNetworkClient.responseProvider = { _, _ ->
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T_NEW_BEARER","refresh_token":"R_NEW"}"""
            )
        }

        val capturedAuthHeaders = mutableListOf<String?>()
        var callCount = 0

        MALQueries.testHttpHandler = { url, headers, _, _ ->
            callCount++
            capturedAuthHeaders.add(headers["Authorization"])
            if (callCount == 1) {
                createNiceResponse(401, """{"error":"expired_token"}""")
            } else {
                createNiceResponse(200, """{"data":[{"node":{"id":1,"title":"Cowboy Bebop"}}]}""")
            }
        }

        val result = MAL.query.getUserAnimeList(status = "watching", limit = 10)

        assertNotNull("Downstream operation must succeed after 401 retry", result)
        assertEquals(1, result?.data?.size)
        assertEquals("Cowboy Bebop", result?.data?.get(0)?.node?.title)

        assertEquals(2, callCount)
        assertEquals("Bearer T_OLD_BEARER", capturedAuthHeaders[0])
        assertEquals("Bearer T_NEW_BEARER", capturedAuthHeaders[1])
        assertEquals("T_NEW_BEARER", MAL.token)
    }

    @Test
    fun `MAL rescue mode compatibility test exercises suggestions, user lists, and rankings with 401 recovery`() = runBlocking {
        MAL.token = "T_RESCUE_EXPIRED"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "T_RESCUE_EXPIRED", "R_RESCUE")

        fakeNetworkClient.responseProvider = { _, _ ->
            MAL.MALNetworkResponse(
                code = 200,
                bodyString = """{"token_type":"Bearer","expires_in":2678400,"access_token":"T_RESCUE_ACTIVE","refresh_token":"R_NEW"}"""
            )
        }

        var suggestionAttempts = 0
        val capturedAuthHeaders = mutableListOf<String?>()

        MALQueries.testHttpHandler = { url, headers, _, _ ->
            capturedAuthHeaders.add(headers["Authorization"])
            if (url.contains("/suggestions")) {
                suggestionAttempts++
                if (suggestionAttempts == 1) {
                    createNiceResponse(401, """{"error":"expired"}""")
                } else {
                    createNiceResponse(200, """{"data":[{"node":{"id":10,"title":"Steins;Gate"}}]}""")
                }
            } else if (url.contains("/animelist")) {
                createNiceResponse(200, """{"data":[{"node":{"id":20,"title":"Fullmetal Alchemist"}}]}""")
            } else if (url.contains("/ranking")) {
                createNiceResponse(200, """{"data":[{"node":{"id":30,"title":"Frieren"}}]}""")
            } else {
                createNiceResponse(200, """{"data":[]}""")
            }
        }

        // 1. Suggestions recovers via 401 retry and refreshes token
        val suggestions = MAL.query.getAnimeSuggestions(limit = 10)
        assertNotNull("Rescue suggestions must succeed after 401 recovery", suggestions)
        assertEquals(1, suggestions?.data?.size)
        assertEquals("Steins;Gate", suggestions?.data?.get(0)?.node?.title)
        assertEquals("T_RESCUE_ACTIVE", MAL.token)

        // 2. Downstream user anime list succeeds with refreshed token
        val userList = MAL.query.getUserAnimeList(status = "completed", limit = 10)
        assertNotNull("Rescue user anime list query must succeed", userList)
        assertEquals(1, userList?.data?.size)
        assertEquals("Fullmetal Alchemist", userList?.data?.get(0)?.node?.title)

        // 3. Global anime ranking query succeeds with preferred header
        val ranking = MAL.query.getAnimeRanking(rankingType = "all", limit = 10)
        assertNotNull("Rescue global ranking query must succeed", ranking)
        assertEquals(1, ranking?.data?.size)
        assertEquals("Frieren", ranking?.data?.get(0)?.node?.title)

        assertEquals("Bearer T_RESCUE_EXPIRED", capturedAuthHeaders[0])
        assertEquals("Bearer T_RESCUE_ACTIVE", capturedAuthHeaders[1])
        assertEquals("Bearer T_RESCUE_ACTIVE", capturedAuthHeaders[2])
    }

    @Test
    fun `MAL logout isolation leaves AniList session completely intact`() {
        Anilist.token = "anilist_primary_jwt_token_123"
        Anilist.username = "anilist_user"
        Anilist.avatar = "https://anilist.co/avatar.jpg"
        Anilist.userid = 4444

        MAL.token = "mal_secondary_token_567"
        MAL.username = "mal_user"
        MAL.avatar = "https://myanimelist.net/avatar.jpg"
        MAL.userid = 8888
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "mal_secondary_token_567", "mal_ref")

        MAL.removeSavedToken()

        assertNull("MAL token must be null", MAL.token)
        assertNull("MAL username must be null", MAL.username)
        assertNull("MAL avatar must be null", MAL.avatar)
        assertFalse("MAL isLoggedIn must be false", MAL.isLoggedIn())

        assertEquals("anilist_primary_jwt_token_123", Anilist.token)
        assertEquals("anilist_user", Anilist.username)
        assertEquals("https://anilist.co/avatar.jpg", Anilist.avatar)
        assertEquals(4444, Anilist.userid)
    }

    @Test
    fun `transient MAL network failure during downstream operation does not affect AniList session`() = runBlocking {
        Anilist.token = "anilist_valid_token"
        Anilist.username = "anilist_user"

        MAL.token = "mal_token"
        fakeTokenStore.storedToken = MAL.ResponseToken("Bearer", 1000L, "mal_token", "mal_ref")

        val queries = MALQueries()
        try {
            queries.executeRequest {
                throw java.io.IOException("MAL API connection timed out")
            }
        } catch (e: Exception) {
            // Expected downstream network failure
        }

        assertEquals("anilist_valid_token", Anilist.token)
        assertEquals("anilist_user", Anilist.username)
        assertEquals("mal_ref", fakeTokenStore.storedToken?.refreshToken)
    }

    @Test
    fun `logged out state has null username and isLoggedIn returns false`() {
        MAL.removeSavedToken()
        assertNull("username must be null after logout", MAL.username)
        assertNull("token must be null after logout", MAL.token)
        assertNull("avatar must be null after logout", MAL.avatar)
        assertFalse("isLoggedIn must return false", MAL.isLoggedIn())
    }

    @Test
    fun `missing client ID behavior is deterministic and safe across all OAuth paths`() = runBlocking {
        MAL.testClientId = ""

        // Query header behavior: emptyMap when clientId is blank
        val queries = MALQueries()
        MALQueries.testHttpHandler = { _, headers, _, _ ->
            assertFalse("Unconfigured build must not include X-MAL-CLIENT-ID header", headers.containsKey("X-MAL-CLIENT-ID"))
            createNiceResponse(200, """{"data":[]}""")
        }
        val res = queries.getSeasonalAnime(2026, "summer")
        assertNotNull(res)

        // Token exchange and refresh with missing credentials fail safely without crashing
        val exchangeSuccess = MAL.exchangeAuthorizationCode("some_code", "some_verifier")
        assertFalse(exchangeSuccess)

        val refreshResult = MAL.refreshToken(force = true)
        assertNull(refreshResult)
    }
}
