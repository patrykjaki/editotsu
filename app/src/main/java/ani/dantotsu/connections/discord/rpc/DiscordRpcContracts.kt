package ani.dantotsu.connections.discord.rpc

/** Outbound frame sink supplied by the Android client to the controller (Binder `sendFrame`). */
interface DiscordRpcTransport {
    /** Returns true if a live connection accepted the frame. */
    fun send(frame: String): Boolean
}

interface DiscordRpcLogger {
    fun log(message: String)
    fun warn(message: String)
}

object NoopDiscordRpcLogger : DiscordRpcLogger {
    override fun log(message: String) = Unit
    override fun warn(message: String) = Unit
}

/**
 * Logical lifecycle states of the clean-room RPC client.
 *
 * IDLE → BINDING → CONNECTED → READY → (CLOSING)
 *
 * - IDLE: no attempt in progress.
 * - BINDING: `bindService` issued / awaiting `onServiceConnected`.
 * - CONNECTED: Binder handle acquired (`rpc.connect` returned a connection).
 * - READY: `READY` dispatch received — outbound `SET_ACTIVITY` is now permitted.
 * - CLOSING: explicit shutdown in progress.
 */
enum class DiscordRpcState { IDLE, BINDING, CONNECTED, READY, CLOSING }

/**
 * JVM abstraction over a live Discord RPC connection (wraps the AIDL
 * `IDiscordRpcConnection` on Android). Exists so the client's routing/send logic is
 * testable without the Android/AIDL runtime.
 */
interface DiscordRpcConnection {
    /** Returns true iff the underlying transport accepted the frame (false on dead-Binder failure). */
    fun sendFrame(frame: String): Boolean
    fun disconnect()
}

/**
 * JVM abstraction over the Discord RPC callback (wraps the AIDL `IDiscordRpcCallback` on
 * Android). The client creates one instance **per run** so a stale callback from an obsolete
 * connection carries its run identity and can be rejected (review Blocker 2).
 */
interface DiscordRpcCallback {
    fun onFrame(frame: String)
    fun onClose(code: Int, reason: String)
}
