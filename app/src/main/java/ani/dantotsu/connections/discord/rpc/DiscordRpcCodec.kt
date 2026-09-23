package ani.dantotsu.connections.discord.rpc

import kotlinx.serialization.json.*

/**
 * Pure-JVM frame codec for the Discord Social SDK RPC wire format.
 *
 * Encodes [DiscordPresence] into the `SET_ACTIVITY` JSON envelope and decodes inbound
 * frames (READY / SET_ACTIVITY-response / ERROR) into a bounded, redacted [InboundFrame].
 *
 * Key guarantees (per production review):
 *  - **No synthetic `ACK`**: the live-proven success shape is a `SET_ACTIVITY` frame whose
 *    `nonce` matches an outstanding outgoing nonce. The controller correlates that; this
 *    codec only reports `isActivityResponse` + `nonce`.
 *  - **Malformed input never throws**: every inbound field is read through safe casts; a
 *    non-JSON / wrong-shaped payload yields [InboundFrame.EMPTY] or a safely-typed frame.
 *  - **Bounded, sanitized strings**: all outbound user/media strings are truncated to
 *    documented Discord limits and stripped of control characters before encoding. Inbound
 *    strings that may reach logs are length-bounded and control-sanitized.
 *  - No raw inbound payload is retained or exposed.
 *
 * Deliberately free of Android / AIDL imports so it is unit-testable on a plain host JVM.
 */
object DiscordRpcCodec {

    /** Injected logger — defaults to noop; set to a real logger on Android for debug builds. */
    var logger: DiscordRpcLogger = NoopDiscordRpcLogger

    // ── Outbound field bounds (review Gap 5) ────────────────────────────────────────
    const val NAME_MAX = 128
    const val DETAILS_MAX = 128
    const val STATE_MAX = 128
    const val IMAGE_KEY_MAX = 300
    const val IMAGE_TEXT_MAX = 128
    const val BUTTON_LABEL_MAX = 64
    const val BUTTON_URL_MAX = 512

    // Max characters (code points) of any inbound string that may reach a log line.
    private const val LOG_STRING_MAX = 200

    // Deterministic safe fallback when a required name sanitizes to blank (review B4C).
    const val FALLBACK_NAME: String = "Editotsu"

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun encodeSetActivity(presence: DiscordPresence, pid: Int, applicationId: Long): String {
        val activityJson = activityToJson(presence, applicationId)
        logger.log(
            "SET_ACTIVITY: appId=$applicationId name='${presence.name}' type=${presence.type.value} " +
                "large_image=${redactImageForLog(presence.largeImageKey)} " +
                "small_image=${redactImageForLog(presence.smallImageKey)} " +
                "small_text='${presence.smallImageText}'"
        )
        val frame = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", pid)
                put("activity", activityJson)
            }
            put("nonce", uuid())
        }
        return json.encodeToString(JsonObject.serializer(), frame)
    }

    fun encodeClear(pid: Int): String {
        val frame = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", pid)
                put("activity", JsonNull)
            }
            put("nonce", uuid())
        }
        return json.encodeToString(JsonObject.serializer(), frame)
    }

    fun parseInbound(frame: String): InboundFrame {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
            ?: return InboundFrame.EMPTY
        val command = obj.safeStr("cmd")?.let { sanitize(it, LOG_STRING_MAX) }
        val event = obj.safeStr("evt")?.let { sanitize(it, LOG_STRING_MAX) }
        // Inbound strings that may reach logs are control-stripped AND length-bounded (review B4B).
        val nonce = obj.safeStr("nonce")?.let { sanitize(it, LOG_STRING_MAX) }
        val isReady = event == "READY"
        val isError = event == "ERROR" || command == "ERROR"
        // A SET_ACTIVITY frame that also carries evt=ERROR is a REJECTION, not a success correlation.
        // Keep ERROR terminal: an activity response is only a success when it is NOT an error (review v5).
        val isActivityResponse = command == "SET_ACTIVITY" && !isError
        var errorCode: Int? = null
        var errorMessage: String? = null
        if (isError) {
            val data = obj["data"] as? JsonObject
            errorCode = (data?.get("code") as? JsonPrimitive)?.intOrNull
            errorMessage = (data?.get("message") as? JsonPrimitive)?.safeContent()
                ?.let { sanitize(it, LOG_STRING_MAX) }
        }
        return InboundFrame(command, event, nonce, isReady, isActivityResponse, isError, errorCode, errorMessage).also {
            if (it.isError || it.isActivityResponse) {
                logger.log(
                    "INBOUND: cmd=${it.command} evt=${it.event} error=${it.isError} " +
                        "errorCode=${it.errorCode} errorMessage=${it.errorMessage} " +
                        "activityResponse=${it.isActivityResponse}"
                )
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    private fun activityToJson(p: DiscordPresence, applicationId: Long): JsonObject = buildJsonObject {
        put("application_id", applicationId)
        // Never fall back to unsanitized input (review B4C): use a deterministic constant.
        put("name", sanitize(p.name, NAME_MAX) ?: FALLBACK_NAME)
        put("type", p.type.value)
        sanitize(p.details, DETAILS_MAX)?.let { put("details", it) }
        sanitize(p.state, STATE_MAX)?.let { put("state", it) }
        if (p.startTimestamp != null || p.endTimestamp != null) {
            putJsonObject("timestamps") {
                p.startTimestamp?.let { put("start", it) }
                p.endTimestamp?.let { put("end", it) }
            }
        }
        if (p.largeImageKey != null || p.largeImageText != null ||
            p.smallImageKey != null || p.smallImageText != null
        ) {
            putJsonObject("assets") {
                sanitize(p.largeImageKey, IMAGE_KEY_MAX)?.let { put("large_image", it) }
                sanitize(p.largeImageText, IMAGE_TEXT_MAX)?.let { put("large_text", it) }
                sanitize(p.smallImageKey, IMAGE_KEY_MAX)?.let { put("small_image", it) }
                sanitize(p.smallImageText, IMAGE_TEXT_MAX)?.let { put("small_text", it) }
            }
        }
        if (p.buttons.isNotEmpty()) {
            putJsonArray("buttons") {
                p.buttons.forEach { b ->
                    addJsonObject {
                        put("label", sanitize(b.label, BUTTON_LABEL_MAX) ?: "")
                        put("url", sanitize(b.url, BUTTON_URL_MAX) ?: "")
                    }
                }
            }
        }
    }

    /**
     * Trim, strip control characters (C0/C1), and truncate to [max] **code points** (review B4D —
     * `String.take` would split a surrogate pair). Returns `null` when the result is blank so
     * optional fields are omitted rather than sent empty. Emoji/surrogate pairs are preserved;
     * only control characters are removed.
     */
    private fun sanitize(s: String?, max: Int): String? {
        if (s == null) return null
        val cleaned = s.trim().replace(CONTROL_CHARS, "")
        if (cleaned.isEmpty()) return null
        return if (max <= 0) "" else cleaned.takeCodePoints(max)
    }

    /** Code-point-safe truncation (does not split a surrogate pair at the boundary). */
    private fun String.takeCodePoints(max: Int): String {
        val cps = this.codePoints().limit(max.toLong()).toArray()
        return String(cps, 0, cps.size)
    }

    /** Safe string read: only true JSON strings are returned; objects/arrays/numbers → null. */
    private fun JsonObject.safeStr(key: String): String? {
        val v = this[key] ?: return null
        return (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Safe string read for inbound error fields (already known to be a JsonPrimitive). */
    private fun JsonPrimitive.safeContent(): String = content

    private fun uuid(): String = java.util.UUID.randomUUID().toString()

    /**
     * Redact an image value for debug logging. External URLs show scheme/host/path only
     * (query/fragment stripped). Application Asset keys show the key name with kind=asset_key.
     * No raw signed URLs or query data reaches the log.
     */
    private fun redactImageForLog(value: String?): String {
        if (value.isNullOrBlank()) return "null"
        val trimmed = value.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val uri = java.net.URI(trimmed)
                val host = uri.host ?: ""
                val path = uri.path ?: ""
                "kind=external_url host=$host path=$path"
            } catch (_: Exception) {
                "kind=external_url host=parse_error"
            }
        } else {
            "kind=asset_key key=$trimmed"
        }
    }

    data class InboundFrame(
        val command: String?,
        val event: String?,
        val nonce: String?,
        val isReady: Boolean,
        val isActivityResponse: Boolean,
        val isError: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
    ) {
        companion object {
            val EMPTY = InboundFrame(null, null, null, false, false, false, null, null)
        }
    }

    private val CONTROL_CHARS = Regex("[\\p{Cc}\\p{Cf}]")
}
