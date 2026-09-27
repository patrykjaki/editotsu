package ani.dantotsu.media.anime

/**
 * MAX MERGE M6 HOLD (PiP seek contract): the PiP transport actions must
 * describe the distance they actually seek. The user's configured
 * `SeekTime` (seconds) is honored; non-positive values fall back to 10s.
 * The same [pipSeekSeconds] result feeds both the RemoteAction label and
 * the `seekRelative` distance, so UI and behavior cannot diverge.
 * Framework-free so the contract is unit-proved (PipSeekPolicyTest).
 */
internal fun pipSeekSeconds(configuredSeconds: Int): Int =
    configuredSeconds.takeIf { it > 0 } ?: 10

internal fun pipSeekMs(configuredSeconds: Int): Long =
    pipSeekSeconds(configuredSeconds) * 1000L
