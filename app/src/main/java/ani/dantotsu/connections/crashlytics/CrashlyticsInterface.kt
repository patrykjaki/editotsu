package ani.dantotsu.connections.crashlytics

import android.content.Context

interface CrashlyticsInterface {
    fun initialize(context: Context)
    fun logException(e: Throwable)
    fun log(message: String)
    fun setUserId(id: String)
    fun setCustomKey(key: String, value: String)
    fun setCrashlyticsCollectionEnabled(enabled: Boolean)

    /** CP4-C: wipe previously attached custom keys so opt-out takes effect in-session. */
    fun clearCustomKeys()
}