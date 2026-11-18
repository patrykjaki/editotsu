package ani.dantotsu.settings

/** When settings may ask for a donation: rarely, never twice close together, never on a recreate. */
object DonatePrompt {
    const val MIN_GAP_MS = 30L * 24 * 60 * 60 * 1000

    /** [roll] is 0..99; about three visits in ten ask once the gap has passed. */
    fun shouldShow(nowMs: Long, lastShownMs: Long, roll: Int, recreated: Boolean): Boolean {
        if (recreated) return false
        if (lastShownMs > 0L && nowMs - lastShownMs < MIN_GAP_MS) return false
        return roll > 69
    }
}
