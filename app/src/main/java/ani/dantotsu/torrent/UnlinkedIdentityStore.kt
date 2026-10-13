package ani.dantotsu.torrent

import android.content.Context
import android.content.SharedPreferences
import ani.dantotsu.media.UNLINKED_ID_MAP_PREF
import ani.dantotsu.media.UnlinkedIdentityException
import ani.dantotsu.settings.saving.internal.Location

/**
 * Strict narrow storage adapter for the unlinked-identity registry (M7A G2
 * HOLD5). Production claim storage goes through THIS — never through the
 * lenient `PrefManager` custom vals, whose `getCustomVal` swallows every
 * read exception into the default (an unreadable registry would look like
 * a fresh one, inviting double-claims) and whose `setCustomVal` persists
 * with fire-and-forget `apply()` (a failed write is unobservable).
 *
 * - Reads fetch the raw value and validate its shape ([coerceStringSet]);
 *   unavailable prefs, unreadable prefs, and present-but-wrong-typed
 *   values each throw [UnlinkedIdentityException] with a distinct message.
 *   Only a genuinely absent key reads as empty (fresh registry).
 * - Writes use `commit()` and throw when persistence is not confirmed.
 *
 * The same prefs FILE as the irrelevant custom vals is used
 * ([Location.Irrelevant]), preserving backup/export semantics — but
 * `PrefManager` itself is never called, so global preference behavior is
 * unchanged. The injectable prefs supplier plus the pure coercion core
 * keep every failure mode JVM-testable against a fake [SharedPreferences].
 */
class UnlinkedIdentityStore(private val prefs: () -> SharedPreferences?) {

    fun readEntries(): Set<String> {
        val sp = try {
            prefs()
        } catch (e: Exception) {
            throw UnlinkedIdentityException("unlinked identity registry unreadable: ${e.message}")
        } ?: throw UnlinkedIdentityException("unlinked identity registry unreadable: preferences unavailable")
        val raw: Any? = try {
            sp.all[UNLINKED_ID_MAP_PREF]
        } catch (e: Exception) {
            throw UnlinkedIdentityException("unlinked identity registry unreadable: ${e.message}")
        } ?: return emptySet()
        return coerceStringSet(raw)
    }

    fun writeEntries(entries: Set<String>) {
        val sp = try {
            prefs()
        } catch (e: Exception) {
            throw UnlinkedIdentityException("unlinked identity not persisted: ${e.message}")
        } ?: throw UnlinkedIdentityException("unlinked identity not persisted: preferences unavailable")
        val confirmed = try {
            sp.edit().putStringSet(UNLINKED_ID_MAP_PREF, entries.toSet()).commit()
        } catch (e: Exception) {
            throw UnlinkedIdentityException("unlinked identity not persisted: ${e.message}")
        }
        if (!confirmed) throw UnlinkedIdentityException("unlinked identity not persisted: commit refused")
    }

    companion object {
        /**
         * Pure shape check (JVM-tested): real string sets — plus legacy
         * int/long collections, mirroring the custom-val sanitizer — pass
         * through as strings. Anything else throws instead of silently
         * degrading to an empty registry.
         */
        internal fun coerceStringSet(raw: Any?): Set<String> = when (raw) {
            is Set<*> -> raw.mapNotNull { it?.toString() }.toSet()
            is Collection<*> -> raw.mapNotNull { it?.toString() }.toSet()
            else -> throw UnlinkedIdentityException(
                "unlinked identity registry has wrong type: ${raw?.javaClass?.name ?: "null"}"
            )
        }

        /** Production supplier over an app context (same file, no PrefManager). */
        fun forContext(context: Context): UnlinkedIdentityStore = UnlinkedIdentityStore {
            try {
                context.applicationContext.getSharedPreferences(
                    Location.Irrelevant.location, Context.MODE_PRIVATE
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
