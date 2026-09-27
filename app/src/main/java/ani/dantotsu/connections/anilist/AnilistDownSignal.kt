package ani.dantotsu.connections.anilist

/**
 * MAX MERGE M6 HOLD (Blocker 2): pure down-signal policy behind
 * `Anilist.executeQuery()`. Only host/connect failures mean AniList is
 * down; timeouts are transient (upstream edb1a9eb). Cancellation is not
 * classified here at all — `executeQuery` rethrows it before reaching
 * this check. Framework-free so the contract is unit-proved.
 */
fun shouldSignalAnilistDown(e: Throwable): Boolean =
    e is java.net.UnknownHostException ||
        e is java.net.ConnectException ||
        e.cause is java.net.UnknownHostException ||
        e.cause is java.net.ConnectException
