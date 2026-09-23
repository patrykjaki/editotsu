package ani.dantotsu.connections.discord.rpc

/**
 * Pure-JVM bounded-retry policy for RPC connection recovery.
 *
 * Addresses review Blockers 1 & 2 (no tight recursive retry storm) and Gap 7 (scenarios 8-10):
 * after `bindService=false`, a null `connect()` handle, or a `connect()` exception, recovery
 * must be **bounded** — it can never recurse synchronously or spin forever.
 *
 * The client performs the actual (re)bind on a `Handler` (post-delayed), which already breaks
 * recursion; this policy caps the total number of recovery attempts so the loop provably
 * terminates. [recordAttempt] is called before each recovery bind; [canRetry] gates it;
 * [reset] is called on a successful READY so a healthy connection does not consume attempts.
 *
 * Deliberately Android-free so it is deterministically host-testable.
 */
class DiscordRpcRecoveryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private var attempts = 0

    @Synchronized
    fun canRetry(): Boolean = attempts < maxAttempts

    /** Record that a recovery bind is about to be attempted. */
    @Synchronized
    fun recordAttempt() {
        attempts += 1
    }

    @Synchronized
    fun reset() {
        attempts = 0
    }

    @Synchronized
    fun attempts(): Int = attempts

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
