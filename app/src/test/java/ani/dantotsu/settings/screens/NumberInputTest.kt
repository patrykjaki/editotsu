package ani.dantotsu.settings.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberInputTest {
    private val ports = 0..65535

    @Test
    fun portsInRangeAreAccepted() {
        assertEquals(0, SourcesScreen.parseNumber("0", ports))
        assertEquals(6881, SourcesScreen.parseNumber(" 6881 ", ports))
        assertEquals(65535, SourcesScreen.parseNumber("65535", ports))
    }

    @Test
    fun outOfRangeOrGarbageIsRejected() {
        assertNull(SourcesScreen.parseNumber("65536", ports))
        assertNull(SourcesScreen.parseNumber("-1", ports))
        assertNull(SourcesScreen.parseNumber("", ports))
        assertNull(SourcesScreen.parseNumber("99999999999", ports))
    }
}
