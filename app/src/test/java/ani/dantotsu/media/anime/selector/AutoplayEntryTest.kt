package ani.dantotsu.media.anime.selector

import ani.dantotsu.media.anime.selector.RememberedSelectionPolicy.AutoplayEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Episode list, resume and the player's next episode all open the dialog through
 * [RememberedSelectionPolicy.autoplayEntry]. r12 (2026-11-02): the player's next episode arrived
 * with no saved server name and showed the list although a family was remembered.
 */
class AutoplayEntryTest {
    private fun entry(
        forcePicker: Boolean = false,
        isLocal: Boolean = false,
        makeDefault: Boolean = true,
        name: String? = null,
        key: Boolean = false,
        family: Boolean = false,
        downloaded: Boolean = false,
    ) = RememberedSelectionPolicy.autoplayEntry(forcePicker, isLocal, makeDefault, name, key, family, downloaded).entry

    @Test
    fun remembered_name_plays_by_name() {
        assertEquals(AutoplayEntry.NAMED, entry(name = "[Judas] Cyberpunk Edgerunners (Season 2) (Batch)", key = true, family = true))
        assertEquals(AutoplayEntry.NAMED, entry(name = "Server 1", makeDefault = false))
    }

    @Test
    fun no_name_but_key_or_family_recovers() {
        assertEquals(AutoplayEntry.RECOVER, entry(family = true))
        assertEquals(AutoplayEntry.RECOVER, entry(key = true))
        assertEquals(AutoplayEntry.RECOVER, entry(name = "  ", key = true, family = true))
    }

    @Test
    fun list_when_asked_or_nothing_usable() {
        assertEquals(AutoplayEntry.PICKER, entry(forcePicker = true, name = "x", key = true, family = true))
        assertEquals(AutoplayEntry.PICKER, entry(isLocal = true, name = "x"))
        assertEquals(AutoplayEntry.PICKER, entry(makeDefault = false, key = true, family = true))
        assertEquals(AutoplayEntry.PICKER, entry(downloaded = true, family = true))
        assertEquals(AutoplayEntry.PICKER, entry())
    }
}
