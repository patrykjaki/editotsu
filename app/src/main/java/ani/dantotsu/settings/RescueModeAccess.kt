package ani.dantotsu.settings

/**
 * Rescue mode runs on MAL, so it can only be turned on with a MAL account signed in.
 * An already enabled switch stays usable without one so it can always be turned off.
 */
internal fun rescueSwitchEnabled(malSignedIn: Boolean, rescueModeOn: Boolean): Boolean =
    malSignedIn || rescueModeOn
