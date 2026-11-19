package ani.dantotsu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashedNamesTest {

    private val names = HashedNames(mapOf(HashedNames.hashOf("groupa") to 6, HashedNames.hashOf("idxone") to 6))

    @Test
    fun `hash is the first 16 hex digits of sha-256 over the lowercase name`() {
        // value from an independent sha-256 (python hashlib) of "groupa"
        assertEquals("e94963dea67090df", HashedNames.hashOf("groupa"))
    }

    @Test
    fun `whole token match ignores case and blanks`() {
        assertTrue(names.matches("GroupA"))
        assertTrue(names.matches(" groupa "))
        assertFalse(names.matches("GroupAB"))
        assertFalse(names.matches("Group"))
        assertFalse(names.matches(null))
    }

    @Test
    fun `substring match finds a name inside longer text`() {
        assertTrue(names.occursIn("cdn.IdxOne.example"))
        assertTrue(names.occursIn("eu.example.groupa.ext"))
        assertFalse(names.occursIn("idxon.example"))
        assertFalse(names.occursIn(""))
        assertFalse(names.occursIn(null))
    }
}
