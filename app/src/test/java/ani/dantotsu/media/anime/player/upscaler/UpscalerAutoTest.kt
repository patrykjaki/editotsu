package ani.dantotsu.media.anime.player.upscaler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscalerAutoTest {

    private val glesIds = listOf("fast", "balanced", "quality")
    private val vulkanIds = glesIds + CunnyUpscalerProvider.VULKAN_PROFILES.map { it.id }

    // GPU table

    @Test
    fun adrenoTiers() {
        assertEquals("3x12", UpscalerGpuTable.startModel("Adreno (TM) 830"))
        assertEquals("2x12", UpscalerGpuTable.startModel("Adreno (TM) 825")) // owner phone, 8s Gen 4
        assertEquals("2x12", UpscalerGpuTable.startModel("Adreno (TM) 750"))
        assertEquals("2x12", UpscalerGpuTable.startModel("Adreno (TM) 740"))
        assertEquals("quality", UpscalerGpuTable.startModel("Adreno (TM) 730"))
        assertEquals("quality", UpscalerGpuTable.startModel("Adreno (TM) 660"))
        assertEquals("balanced", UpscalerGpuTable.startModel("Adreno (TM) 619"))
        assertEquals("fast", UpscalerGpuTable.startModel("Adreno (TM) 605"))
        assertNull(UpscalerGpuTable.startModel("Adreno (TM) 540"))
    }

    @Test
    fun maliImmortalisXclipse() {
        assertEquals("2x12", UpscalerGpuTable.startModel("Immortalis-G720"))
        assertEquals("2x12", UpscalerGpuTable.startModel("Mali-G925-Immortalis MC12"))
        assertEquals("quality", UpscalerGpuTable.startModel("Mali-G715"))
        assertEquals("balanced", UpscalerGpuTable.startModel("Mali-G610 MC6"))
        assertEquals("balanced", UpscalerGpuTable.startModel("Mali-G78 MP20"))
        assertEquals("fast", UpscalerGpuTable.startModel("Mali-G57 MC2"))
        assertNull(UpscalerGpuTable.startModel("Mali-G52 MC2"))
        assertNull(UpscalerGpuTable.startModel("Mali-T880"))
        assertEquals("quality", UpscalerGpuTable.startModel("Samsung Xclipse 940"))
        assertEquals("balanced", UpscalerGpuTable.startModel("Samsung Xclipse 530"))
    }

    @Test
    fun unknownGpusAreOff() {
        assertNull(UpscalerGpuTable.startModel(null))
        assertNull(UpscalerGpuTable.startModel(""))
        assertNull(UpscalerGpuTable.startModel("PowerVR B-Series BXM-8-256"))
        assertNull(UpscalerGpuTable.startModel("Android Emulator OpenGL ES Translator (Apple M2)"))
    }

    // Start model

    @Test
    fun vulkanStartsAtTablePick() {
        assertEquals("2x12", UpscalerAuto.start("2x12", vulkan = true, available = vulkanIds, cap = null))
        assertEquals("3x12", UpscalerAuto.start("3x12", vulkan = true, available = vulkanIds, cap = null))
    }

    @Test
    fun openGlCapsAtQualityThenOneLower() {
        assertEquals("balanced", UpscalerAuto.start("3x12", vulkan = false, available = glesIds, cap = null))
        assertEquals("balanced", UpscalerAuto.start("quality", vulkan = false, available = glesIds, cap = null))
        assertEquals("fast", UpscalerAuto.start("balanced", vulkan = false, available = glesIds, cap = null))
        assertNull(UpscalerAuto.start("fast", vulkan = false, available = glesIds, cap = null))
    }

    @Test
    fun rememberedStepDownWins() {
        assertEquals("quality", UpscalerAuto.start("3x12", vulkan = true, available = vulkanIds, cap = "quality"))
        assertNull(UpscalerAuto.start("3x12", vulkan = true, available = vulkanIds, cap = UpscalerAuto.OFF))
        // A cap above the pick changes nothing.
        assertEquals("2x12", UpscalerAuto.start("2x12", vulkan = true, available = vulkanIds, cap = "4x24"))
    }

    @Test
    fun missingModelsFallToLighterOnes() {
        assertEquals("quality", UpscalerAuto.start("2x12", vulkan = true, available = glesIds, cap = null))
        assertNull(UpscalerAuto.start("2x12", vulkan = true, available = emptyList(), cap = null))
        assertNull(UpscalerAuto.start(null, vulkan = true, available = vulkanIds, cap = null))
    }

    @Test
    fun stepDownWalksTheLadderToOff() {
        assertEquals("2x12", UpscalerAuto.stepDown("3x12", vulkanIds))
        assertEquals("quality", UpscalerAuto.stepDown("2x12", vulkanIds))
        assertEquals("fast", UpscalerAuto.stepDown("balanced", vulkanIds))
        assertNull(UpscalerAuto.stepDown("fast", vulkanIds))
    }

    @Test
    fun capIsTiedToTheRenderer() {
        val stored = UpscalerAuto.encodeCap("quality", "Adreno (TM) 825")
        assertEquals("quality", UpscalerAuto.decodeCap(stored, "Adreno (TM) 825"))
        assertNull(UpscalerAuto.decodeCap(stored, "Adreno (TM) 830"))
        assertEquals(UpscalerAuto.OFF, UpscalerAuto.decodeCap(UpscalerAuto.encodeCap(null, "x"), "x"))
        assertNull(UpscalerAuto.decodeCap("", "x"))
    }

    // Step-down rule

    /** Feeds one sample per second from [startMs], [dropsPerSecond] new drops each. */
    private fun run(
        d: UpscalerStepDown, startMs: Long, seconds: Int, dropsPerSecond: Long, startCount: Long,
        disturbed: (Int) -> Boolean = { false },
    ): Pair<Int?, Long> {
        var count = startCount
        for (i in 0 until seconds) {
            count += dropsPerSecond
            if (d.sample(startMs + i * 1_000L, count, disturbed(i))) return i to count
        }
        return null to count
    }

    @Test
    fun persistentDropsStepDown() {
        val d = UpscalerStepDown()
        d.reset(0)
        val (at, _) = run(d, 0, 30, dropsPerSecond = 5, startCount = 0)
        // Baseline at the end of the 5 s grace, then six bad seconds.
        assertEquals(10, at)
    }

    @Test
    fun cleanPlaybackNeverStepsDown() {
        val d = UpscalerStepDown()
        d.reset(0)
        assertNull(run(d, 0, 120, dropsPerSecond = 0, startCount = 0).first)
        // One drop a second is under the threshold.
        assertNull(run(d, 120_000, 120, dropsPerSecond = 1, startCount = 0).first)
    }

    @Test
    fun shortBurstsDoNotStepDown() {
        val d = UpscalerStepDown()
        d.reset(0)
        var count = 0L
        var stepped = false
        // Bad every third second: never 6 of 10.
        for (i in 0 until 120) {
            if (i % 3 == 0) count += 10
            stepped = stepped || d.sample(i * 1_000L, count, false)
        }
        assertFalse(stepped)
    }

    @Test
    fun seekingAndStallsAreNotCounted() {
        val d = UpscalerStepDown()
        d.reset(0)
        // Heavy drops, but every sample is during a seek / buffering.
        assertNull(run(d, 0, 60, dropsPerSecond = 30, startCount = 0, disturbed = { true }).first)
        // A stall every 4 s keeps resetting the grace period.
        assertNull(run(d, 60_000, 120, dropsPerSecond = 30, startCount = 0, disturbed = { it % 4 == 0 }).first)
    }

    @Test
    fun dropsRightAfterASeekAreIgnored() {
        val d = UpscalerStepDown()
        d.reset(0)
        // Seek at t=0..1 then catch-up drops for 5 s (grace), then clean.
        var count = 0L
        var stepped = false
        for (i in 0 until 40) {
            val seeking = i < 2
            if (i < 7) count += 20
            stepped = stepped || d.sample(i * 1_000L, count, seeking)
        }
        assertFalse(stepped)
    }

    @Test
    fun counterResetRebases() {
        val d = UpscalerStepDown()
        d.reset(0)
        var stepped = false
        // Counter falls back to 0 every 4 s (new file): never enough bad seconds in a row.
        for (i in 0 until 60) {
            val count = (i % 4) * 10L
            stepped = stepped || d.sample(10_000L + i * 1_000L, count, false)
        }
        assertFalse(stepped)
    }

    @Test
    fun longGapRebases() {
        val d = UpscalerStepDown()
        d.reset(0)
        d.sample(6_000, 0, false)
        // 60 s later (backgrounded), +600 drops: not counted as one bad second.
        assertFalse(d.sample(66_000, 600, false))
    }

    // Controller

    private class Fake(var renderer: String? = "Adreno (TM) 825", var vulkan: Boolean = true) {
        var cap = ""
        val controller = AutoUpscalerController({ renderer }, { vulkan }, { cap }, { cap = it })
    }

    @Test
    fun controllerResolvesAutoAndLeavesOtherSelections() {
        val f = Fake()
        assertEquals("cunny" to "2x12", f.controller.concretize("cunny", "auto", { vulkanIds }, 0))
        assertTrue(f.controller.active)
        assertEquals("cunny" to "balanced", f.controller.concretize("cunny", "balanced", { vulkanIds }, 0))
        assertFalse(f.controller.active)
        assertEquals("off" to "", f.controller.concretize("off", "", { vulkanIds }, 0))
    }

    @Test
    fun controllerUnknownGpuIsOff() {
        val f = Fake(renderer = null)
        assertEquals("off" to "", f.controller.concretize("cunny", "auto", { vulkanIds }, 0))
    }

    @Test
    fun controllerStepsDownAndRemembers() {
        val f = Fake()
        f.controller.concretize("cunny", "auto", { vulkanIds }, 0)
        var count = 0L
        var changedAt = -1
        for (i in 0 until 30) {
            count += 10
            if (f.controller.sample(i * 1_000L, count, false) { vulkanIds }) { changedAt = i; break }
        }
        assertTrue(changedAt > 0)
        assertEquals("quality", f.controller.model)
        assertEquals("quality", UpscalerAuto.decodeCap(f.cap, "Adreno (TM) 825"))
        // Re-applying keeps the stepped-down model.
        assertEquals("cunny" to "quality", f.controller.concretize("cunny", "auto", { vulkanIds }, 40_000))
        // A new session starts at the remembered model.
        val again = AutoUpscalerController({ f.renderer }, { true }, { f.cap }, {})
        assertEquals("cunny" to "quality", again.concretize("cunny", "auto", { vulkanIds }, 0))
    }
}
