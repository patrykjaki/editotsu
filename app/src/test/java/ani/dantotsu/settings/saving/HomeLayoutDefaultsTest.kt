package ani.dantotsu.settings.saving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutDefaultsTest {

    @Test
    fun `legacy 9-slot visibility migrates with Watch next on and slot 8 dropped`() {
        val legacy = listOf(true, true, false, true, false, true, false, true, false)

        val migrated = HomeLayoutDefaults.normalizeVisibility(legacy)

        assertEquals(HomeLayoutDefaults.SECTION_COUNT, migrated.size)
        assertEquals(listOf(true, true, true, true, false, true, false, true), migrated)
    }

    @Test
    fun `current 8-slot visibility preserves user turning Watch next off`() {
        val saved = listOf(true, false, false, true, false, false, true, true)

        val normalized = HomeLayoutDefaults.normalizeVisibility(saved)

        assertFalse(normalized[HomeLayoutDefaults.WATCH_NEXT])
        assertEquals(saved, normalized)
    }

    @Test
    fun `legacy migration turns Watch next on even for older 8-slot saved layouts`() {
        val oldEightSlot = listOf(true, false, false, true, false, false, true, false)

        val migrated = HomeLayoutDefaults.migrateLegacyVisibility(oldEightSlot)

        assertTrue(migrated[HomeLayoutDefaults.WATCH_NEXT])
        assertFalse(migrated[HomeLayoutDefaults.SOCIAL_FEEDS])
    }

    @Test
    fun `legacy order keeps Watch next in Planned Anime's slot without losing custom order`() {
        val legacyOrder = listOf(0, 6, 2, 3, 1, 4, 5, 8)

        val normalized = HomeLayoutDefaults.normalizeOrder(legacyOrder)

        assertEquals(listOf(0, 6, 2, 3, 1, 4, 5), normalized)
    }

    @Test
    fun `legacy order missing slot 2 places Watch next where Missed Sequels was`() {
        val legacyOrder = listOf(0, 3, 8, 6, 1, 4, 5)

        val normalized = HomeLayoutDefaults.normalizeOrder(legacyOrder)

        assertEquals(listOf(0, 3, 2, 6, 1, 4, 5), normalized)
    }

    @Test
    fun `empty order returns default reorderable order without fixed Social Feeds slot`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), HomeLayoutDefaults.normalizeOrder(emptyList()))
    }
}
