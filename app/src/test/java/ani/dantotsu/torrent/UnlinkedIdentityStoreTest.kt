package ani.dantotsu.torrent

import android.content.SharedPreferences
import ani.dantotsu.media.UnlinkedIdentityException
import ani.dantotsu.media.claimUnlinkedMediaId
import ani.dantotsu.media.isUnlinkedId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7A G2 HOLD5: the REAL [UnlinkedIdentityStore] against a fake
 * [SharedPreferences] — read/type/write failures of the production path,
 * not injected claim-helper exceptions. The fake honors commit/copy
 * semantics so roundtrips prove the adapter actually persists.
 */
class UnlinkedIdentityStoreTest {

    private class FakePrefs(
        val data: MutableMap<String, Any?> = mutableMapOf(),
        var commitResult: Boolean = true,
        var throwOnAll: Boolean = false,
        var throwOnEdit: Boolean = false,
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> {
            if (throwOnAll) throw IllegalStateException("injected read failure")
            return data
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getString(key: String, defValue: String?): String? =
            data[key] as? String ?: defValue

        override fun getInt(key: String, defValue: Int): Int =
            data[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            data[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            data[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                if (throwOnEdit) throw IllegalStateException("injected edit failure")
                puts[key] = values?.toSet()
                return this
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                puts[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                if (throwOnEdit) throw IllegalStateException("injected commit failure")
                if (!commitResult) return false
                if (clearAll) data.clear()
                puts.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                return true
            }

            override fun apply() {
                if (commitResult) {
                    if (clearAll) data.clear()
                    puts.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                }
            }
        }
    }

    private fun storeOf(fake: FakePrefs) = UnlinkedIdentityStore { fake }

    // ---- reads ----

    @Test
    fun `absent key reads as empty registry`() {
        assertEquals(emptySet<String>(), storeOf(FakePrefs()).readEntries())
    }

    @Test
    fun `write then read roundtrips through the adapter`() {
        val fake = FakePrefs()
        val store = storeOf(fake)
        store.writeEntries(setOf("ABC=-42", "DEF=-43"))
        assertEquals(setOf("ABC=-42", "DEF=-43"), store.readEntries())
        // Proves commit-path persistence, not a stub: the raw map holds it.
        assertTrue(fake.data.values.any { it is Set<*> })
    }

    @Test
    fun `wrong-typed value throws instead of degrading to empty`() {
        listOf<Any?>(42, "plain-string", 4.5, true).forEach { wrong ->
            val fake = FakePrefs(mutableMapOf("unlinkedTorrentIds" to wrong))
            try {
                storeOf(fake).readEntries()
                assertTrue("wrong type $wrong must throw", false)
            } catch (e: UnlinkedIdentityException) {
                assertTrue((e.message ?: "").contains("wrong type"))
            }
        }
    }

    @Test
    fun `unavailable prefs throw on read`() {
        try {
            UnlinkedIdentityStore { null }.readEntries()
            assertTrue("null prefs must throw", false)
        } catch (e: UnlinkedIdentityException) {
            assertTrue((e.message ?: "").contains("unreadable"))
        }
        try {
            UnlinkedIdentityStore { FakePrefs(throwOnAll = true) }.readEntries()
            assertTrue("throwing read must throw", false)
        } catch (e: UnlinkedIdentityException) {
            assertTrue((e.message ?: "").contains("unreadable"))
        }
    }

    // ---- writes ----

    @Test
    fun `refused commit throws and persists nothing`() {
        val fake = FakePrefs(commitResult = false)
        try {
            storeOf(fake).writeEntries(setOf("ABC=-42"))
            assertTrue("refused commit must throw", false)
        } catch (e: UnlinkedIdentityException) {
            assertTrue((e.message ?: "").contains("persisted"))
        }
        assertTrue(!fake.data.containsKey("unlinkedTorrentIds"))
    }

    @Test
    fun `throwing editor throws`() {
        try {
            storeOf(FakePrefs(throwOnEdit = true)).writeEntries(setOf("ABC=-42"))
            assertTrue("throwing editor must throw", false)
        } catch (e: UnlinkedIdentityException) {
            assertTrue((e.message ?: "").contains("persisted"))
        }
        try {
            UnlinkedIdentityStore { null }.writeEntries(setOf("ABC=-42"))
            assertTrue("null prefs must throw", false)
        } catch (e: UnlinkedIdentityException) {
            assertTrue((e.message ?: "").contains("persisted"))
        }
    }

    @Test
    fun `write persists a copy not the caller set`() {
        val fake = FakePrefs()
        val store = storeOf(fake)
        val source = mutableSetOf("ABC=-42")
        store.writeEntries(source)
        source.clear()
        assertEquals(setOf("ABC=-42"), store.readEntries())
    }

    // ---- pure coercion core ----

    @Test
    fun `coerce accepts sets and legacy collections`() {
        assertEquals(
            setOf("1", "2"),
            UnlinkedIdentityStore.coerceStringSet(setOf(1, 2))
        )
        assertEquals(
            setOf("A=-1"),
            UnlinkedIdentityStore.coerceStringSet(listOf("A=-1"))
        )
    }

    @Test
    fun `coerce rejects foreign types`() {
        listOf<Any?>(42, "x", null).forEach { raw ->
            try {
                UnlinkedIdentityStore.coerceStringSet(raw)
                assertTrue("coerce($raw) must throw", false)
            } catch (e: UnlinkedIdentityException) {
                assertTrue((e.message ?: "").contains("wrong type"))
            }
        }
    }

    // ---- claim over the real store ----

    @Test
    fun `colliding torrents claim distinct stable ids through the store`() {
        val fake = FakePrefs()
        fun claim(h: String?): Int {
            val store = storeOf(fake)
            return claimUnlinkedMediaId(h, store::readEntries, store::writeEntries)
        }
        val hashA = "000000000000000000000000000000000000F1E5"
        val hashB = "000000000000000000000000000000000001682A"
        val idA = claim(hashA)
        val idB = claim(hashB)
        assertTrue(isUnlinkedId(idA))
        assertTrue(isUnlinkedId(idB))
        assertTrue(idA != idB)
        // Fresh store instance over the same prefs: registry hit, stable.
        assertEquals(idA, claim(hashA))
        assertEquals(idB, claim(hashB))
    }

    @Test
    fun `wrong-typed registry surfaces instead of double-claiming`() {
        val fake = FakePrefs(mutableMapOf("unlinkedTorrentIds" to 42))
        val store = storeOf(fake)
        try {
            claimUnlinkedMediaId("A".repeat(40), store::readEntries, store::writeEntries)
            assertTrue("wrong-typed registry must throw", false)
        } catch (e: UnlinkedIdentityException) {
            assertTrue((e.message ?: "").contains("wrong type"))
        }
    }
}
