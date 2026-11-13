package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceVariantLabelsTest {
    @Test
    fun `languages are used when they differ`() {
        assertEquals(
            listOf("English", "Polish"),
            SourcePicker.variantLabels(listOf("English", "Polish"), listOf("A", "A")),
        )
    }

    @Test
    fun `source names replace repeated languages`() {
        assertEquals(
            listOf("Home", "Work"),
            SourcePicker.variantLabels(listOf("Multi", "Multi"), listOf("Home", "Work")),
        )
    }

    @Test
    fun `repeated names are numbered`() {
        assertEquals(
            listOf("Home 1", "Home 2"),
            SourcePicker.variantLabels(listOf("Multi", "Multi"), listOf("Home", "Home")),
        )
    }
}
