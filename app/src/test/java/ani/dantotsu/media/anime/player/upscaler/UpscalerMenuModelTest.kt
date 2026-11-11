package ani.dantotsu.media.anime.player.upscaler

import ani.dantotsu.media.anime.player.upscaler.UpscalerMenuModel.Kind
import ani.dantotsu.media.anime.player.upscaler.UpscalerMenuModel.Vulkan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscalerMenuModelTest {

    @Test
    fun orderIsOffAutoModelsLightToHeavyThenSharpen() {
        val e = UpscalerMenuModel.entries(bundleAvailable = true, vulkan = Vulkan.RUNNING)
        assertEquals(listOf(Kind.OFF, Kind.AUTO), e.take(2).map { it.kind })
        assertEquals(UpscalerAuto.LADDER, e.filter { it.kind == Kind.MODEL }.map { it.profileId })
        assertEquals(
            listOf("2x12-ds", "3x12-ds", "4x12-ds", "4x16-ds", "4x24-ds"),
            e.filter { it.kind == Kind.SHARPEN }.map { it.profileId },
        )
        assertTrue(e.all { it.enabled })
    }

    @Test
    fun withoutVulkanTheLargeModelsAreListedButDisabled() {
        for (v in listOf(Vulkan.NOT_IN_BUILD, Vulkan.NOT_ON_DEVICE, Vulkan.FAILED)) {
            val e = UpscalerMenuModel.entries(true, v)
            assertEquals(listOf("fast", "balanced", "quality"), e.filter { it.kind == Kind.MODEL && it.enabled }.map { it.profileId })
            assertFalse(e.any { it.kind == Kind.SHARPEN && it.enabled })
            assertTrue(e.first { it.kind == Kind.AUTO }.enabled)
        }
    }

    @Test
    fun missingBundleIsOffOnly() {
        assertEquals(listOf(Kind.OFF), UpscalerMenuModel.entries(false, Vulkan.RUNNING).map { it.kind })
    }

    @Test
    fun selectedIndex() {
        val on = UpscalerMenuModel.entries(true, Vulkan.RUNNING)
        assertEquals(1, UpscalerMenuModel.selectedIndex(on, "cunny", "auto"))
        assertEquals(0, UpscalerMenuModel.selectedIndex(on, "off", ""))
        assertEquals(on.indexOfFirst { it.profileId == "4x12-ds" }, UpscalerMenuModel.selectedIndex(on, "cunny", "4x12-ds"))
        // A Vulkan model saved earlier shows as Off when it can't run.
        val off = UpscalerMenuModel.entries(true, Vulkan.NOT_IN_BUILD)
        assertEquals(0, UpscalerMenuModel.selectedIndex(off, "cunny", "4x12"))
        assertEquals(0, UpscalerMenuModel.selectedIndex(on, "cunny", "gone"))
    }
}
