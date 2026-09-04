package ani.dantotsu.connections.discord.rpc

/**
 * Pure-JVM, Android-free state machine for the Discord Social SDK RPC transport.
 *
 * Owns the logical lifecycle ([DiscordRpcState]) and the READY-gated presence dispatch.
 * All Android/Binder mechanics live in [DiscordRpcClient], which feeds events in via
 * [attachTransport] + [onBinderConnected] / [onBinderLost] / [onFrame] and reads outbound
 * frames through [DiscordRpcTransport].
 *
 * Design guarantees required by the production handoff + review:
 *  - READY gating: `SET_ACTIVITY` is sent only after a `READY` dispatch is observed.
 *  - Latest-wins: while not READY only the most recent presence is retained and sent once on
 *    READY; updates received after READY each replace the previous one.
 *  - No 15s post-SEND auto-clear: there is no timer here at all. A clear is sent only on an
 *    explicit [clearPresence] / [shutdown].
 *  - Bounded establishment: the client owns the BIND/CONNECT/READY timeout and reports it via
 *    [onEstablishmentTimeout]; this class never hangs on its own.
 *  - **Recovery is explicit, not recursive** (review Blocker 1): [onBinderLost] does NOT call
 *    back into the client to start a new run. It returns a `Boolean` recovery decision and the
 *    client performs the rebind asynchronously/with backoff. Old-run cleanup therefore always
 *    completes before any new run is installed.
 *  - **Correct ACK model** (review Blocker 3): success is a `SET_ACTIVITY` response whose
 *    `nonce` matches an outstanding outgoing nonce — not a synthetic `cmd=ACK`. Unmatched
 *    nonces are logged and ignored.
 *  - The Discord application id is pinned here ([appId]); [DiscordPresence] carries no app id
 *    (review Gap 6).
 *
 * Not a singleton: the Android client owns exactly one instance and drives it.
 */
class DiscordRpcController(
    private val appId: Long,
    private val logger: DiscordRpcLogger = NoopDiscordRpcLogger,
    private val onStateChanged: (DiscordRpcState) -> Unit = {},
) {
    @Volatile private var transport: DiscordRpcTransport? = null
    private val lock = Any()

    @Volatile private var state: DiscordRpcState = DiscordRpcState.IDLE
    private var pid: Int = 0

    // Latest desired presence. `pendingClear == true` means "show nothing".
    // These two are mutually exclusive (clear nulls pending; set nulls pendingClear).
    private var pending: DiscordPresence? = null
    private var pendingClear: Boolean = false

    // Outstanding outgoing SET/CLEAR nonces, for ACK correlation (Blocker 3).
    private val outstandingNonces = mutableSetOf<String>()

    // Hard cap so the nonce set cannot grow without bound across reconnects (review B4A).
    private companion object {
        const val MAX_OUTSTANDING_NONCES = 64
    }

    fun getState(): DiscordRpcState = state

    // ── Outbound API (called by the Android client on a worker/main thread) ──────────

    fun requestPresence(presence: DiscordPresence) {
        synchronized(lock) {
            pending = presence
            pendingClear = false
            when (state) {
                DiscordRpcState.IDLE -> {
                    state = DiscordRpcState.BINDING
                    onStateChanged(DiscordRpcState.BINDING)
                }
                DiscordRpcState.READY -> sendSetActivity(presence)
                else -> { /* BINDING/CONNECTED/CLOSING: keep pending, flush on READY */ }
            }
        }
        // The bind itself is driven by the coordinator (initial + bounded recovery); this method
        // only expresses the intent to show presence and retains the latest desired state.
    }

    fun clearPresence() {
        synchronized(lock) {
            pending = null
            pendingClear = true
            if (state == DiscordRpcState.READY) sendClear()
            // While BINDING/CONNECTED: cancels the pending set; nothing has been shown yet.
            // While IDLE: nothing is shown; no-op (no bind required).
        }
    }

    fun shutdown() {
        synchronized(lock) {
            if (state == DiscordRpcState.READY) sendClear()
            state = DiscordRpcState.CLOSING
            pending = null
            pendingClear = true
            onStateChanged(DiscordRpcState.CLOSING)
        }
    }

    /** Called by the client when its bounded BIND/CONNECT/READY window expires with no READY. */
    fun onEstablishmentTimeout() {
        synchronized(lock) {
            if (state == DiscordRpcState.CONNECTED || state == DiscordRpcState.BINDING) {
                logger.warn("rpc: establishment timeout (no READY) — giving up this attempt")
                resetLocked()
            }
        }
    }

    fun reset() {
        synchronized(lock) { resetLocked() }
    }

    // ── Inbound API (from the Android client) ───────────────────────────────────────

    fun attachTransport(t: DiscordRpcTransport) {
        synchronized(lock) { transport = t }
    }

    fun onBinderConnected(pid: Int) {
        synchronized(lock) {
            if (state == DiscordRpcState.CLOSING) return
            this.pid = pid
            outstandingNonces.clear() // fresh RPC session (review B4A)
            state = DiscordRpcState.CONNECTED
            onStateChanged(DiscordRpcState.CONNECTED)
        }
    }

    /**
     * Report binder loss. Returns `true` iff a pending presence still exists and the client
     * should attempt one bounded recovery bind. It does NOT start the rebind itself (review
     * Blocker 1) — the client owns recovery scheduling/ordering so old-run cleanup always
     * completes before the new run is installed.
     */
    fun onBinderLost(): Boolean {
        synchronized(lock) {
            transport = null
            outstandingNonces.clear() // discard old-run nonce state (review B4A)
            if (state == DiscordRpcState.CLOSING || state == DiscordRpcState.IDLE) return false
            val needRebind = pending != null && !pendingClear
            if (needRebind) {
                state = DiscordRpcState.BINDING
                onStateChanged(DiscordRpcState.BINDING)
            } else {
                resetLocked()
            }
            return needRebind
        }
    }

    fun onFrame(frame: String) {
        val f = DiscordRpcCodec.parseInbound(frame)
        synchronized(lock) {
            if (f.isReady) {
                if (state == DiscordRpcState.CONNECTED) {
                    state = DiscordRpcState.READY
                    onStateChanged(DiscordRpcState.READY)
                    if (!pendingClear && pending != null) sendSetActivity(pending!!)
                } else {
                    logger.warn("rpc: READY received while not CONNECTED (state=$state); ignoring")
                }
                return
            }
            if (f.isError) {
                // ERROR is terminal and takes priority over ordinary SET_ACTIVITY success correlation
                // (review v5). A rejected SET_ACTIVITY echoes our nonce: retire it as a FAILED response
                // so a later replay can never be mistaken for an acknowledgement, but never call it success.
                f.nonce?.let { outstandingNonces.remove(it) }
                logger.warn("rpc: ERROR (code=${f.errorCode}) ${f.errorMessage ?: "<none>"}")
                return
            }
            if (f.isActivityResponse) {
                // Live-proven success shape: SET_ACTIVITY response with a matching nonce.
                if (f.nonce != null && outstandingNonces.remove(f.nonce)) {
                    logger.log("rpc: SET_ACTIVITY acknowledged (nonce=${f.nonce})")
                } else {
                    logger.log("rpc: SET_ACTIVITY response with unmatched nonce (ignored)")
                }
                return
            }
            // Unknown frame: ignore safely.
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────

    private fun resetLocked() {
        transport = null
        pending = null
        pendingClear = false
        outstandingNonces.clear()
        state = DiscordRpcState.IDLE
        onStateChanged(DiscordRpcState.IDLE)
    }

    private fun sendSetActivity(p: DiscordPresence) {
        val frame = DiscordRpcCodec.encodeSetActivity(p, pid, appId)
        val ok = transport?.send(frame) ?: false
        // Record the nonce only after a successful send, so a failed send never leaves a
        // permanently-outstanding (unkillable) nonce (review B4A).
        if (ok) {
            trackNonce(frame)
            logger.log("rpc: SET_ACTIVITY sent (pid=$pid)")
        } else {
            logger.warn("rpc: SET_ACTIVITY dropped (no transport)")
        }
    }

    private fun sendClear() {
        val frame = DiscordRpcCodec.encodeClear(pid)
        val ok = transport?.send(frame) ?: false
        if (ok) {
            trackNonce(frame)
            logger.log("rpc: CLEAR sent (pid=$pid)")
        } else {
            logger.warn("rpc: CLEAR dropped (no transport)")
        }
    }

    private fun trackNonce(frame: String) {
        DiscordRpcCodec.parseInbound(frame).nonce?.let {
            // Bounded: never let the set grow without limit across reconnects (review B4A).
            if (outstandingNonces.size >= MAX_OUTSTANDING_NONCES) {
                val it2 = outstandingNonces.first()
                outstandingNonces.remove(it2)
            }
            outstandingNonces.add(it)
        }
    }
}
