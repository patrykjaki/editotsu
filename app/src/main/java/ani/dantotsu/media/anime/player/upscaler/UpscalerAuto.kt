package ani.dantotsu.media.anime.player.upscaler

import ani.dantotsu.media.anime.player.UpscalerConfig

/**
 * "Auto" upscaler: start from a model picked by GPU ([UpscalerGpuTable]) and step down one
 * model when playback drops frames for a while ([UpscalerStepDown]). Everything here is pure
 * and JVM-tested; the engine feeds it mpv's counters.
 */
object UpscalerAuto {
    /** Stored as the profile id (provider [CunnyUpscalerProvider.ID]) when Auto is selected. */
    const val PROFILE_ID = "auto"

    /** Off as a step-down target: the lightest model still drops frames. */
    const val OFF = "off"

    /** SOFT models, lightest first. Sharpen variants cost the same and Auto never picks them. */
    val LADDER = listOf("fast", "balanced", "quality", "2x12", "3x12", "4x12", "4x16", "4x24")

    /** Heaviest model that runs on OpenGL (the larger ones need Vulkan, dp4a). */
    const val GLES_MAX = "quality"

    /**
     * Model to start with. [tablePick] comes from the GPU table; [available] are the provider's
     * profile ids (Vulkan models only while Vulkan runs); [cap] is a model an earlier session
     * stepped down to on this GPU, or [OFF]. Returns null for Off. A null [tablePick] (GPU
     * unknown or too weak) is Off.
     */
    fun start(tablePick: String?, vulkan: Boolean, available: Collection<String>, cap: String?): String? {
        if (cap == OFF || tablePick == null) return null
        var index = LADDER.indexOf(tablePick)
        if (index < 0) return null
        if (!vulkan) {
            // The GLES copies run without dp4a and cost more: cap at Quality, then one lower.
            // A GPU whose pick is already the lightest model stays off.
            index = minOf(index, LADDER.indexOf(GLES_MAX)) - 1
            if (index < 0) return null
        }
        cap?.let { LADDER.indexOf(it) }?.takeIf { it >= 0 }?.let { index = minOf(index, it) }
        // Fall to the heaviest available model at or below the pick.
        for (i in index downTo 0) if (LADDER[i] in available) return LADDER[i]
        return null
    }

    /** The next lighter model after [current], or null (Off) below the lightest. */
    fun stepDown(current: String, available: Collection<String>): String? {
        val index = LADDER.indexOf(current)
        for (i in index - 1 downTo 0) if (LADDER[i] in available) return LADDER[i]
        return null
    }

    /** Remembered step-down, stored as `model@renderer` so another GPU ignores it. */
    fun encodeCap(model: String?, renderer: String?) = "${model ?: OFF}@${renderer.orEmpty()}"

    fun decodeCap(stored: String, renderer: String?): String? {
        val at = stored.indexOf('@')
        if (at <= 0) return null
        return stored.substring(0, at).takeIf { stored.substring(at + 1) == renderer.orEmpty() }
    }
}

/**
 * First guess of the model a GPU can run in real time, from the GL_RENDERER string. Tiers are
 * deliberately low: the step-down only goes down, so an under-guess costs some sharpness while
 * an over-guess costs dropped frames until Auto reacts. None of the rows are measured yet.
 * Null means Off: unknown GPUs (emulators, new vendors) and old ones are not upscaled.
 */
object UpscalerGpuTable {
    private val FALLBACK: String? = null

    private val ADRENO = Regex("""adreno\D{0,8}(\d{3})""")
    private val MALI_G = Regex("""mali-?g(\d{2,3})""")
    private val XCLIPSE = Regex("""xclipse\s*(\d{3})""")

    fun startModel(renderer: String?): String? {
        val r = renderer?.lowercase()?.trim().orEmpty()
        if (r.isEmpty()) return FALLBACK
        ADRENO.find(r)?.let { return adreno(it.groupValues[1].toInt()) }
        if ("immortalis" in r) return "2x12"
        MALI_G.find(r)?.let { return maliG(it.groupValues[1]) }
        XCLIPSE.find(r)?.let { return if (it.groupValues[1].toInt() >= 920) "quality" else "balanced" }
        return FALLBACK
    }

    private fun adreno(n: Int) = when {
        n >= 830 -> "3x12"      // 8 Elite and up
        n >= 740 -> "2x12"      // 8 Gen 2 / 8 Gen 3 / 8s Gen 4 (825)
        n >= 700 -> "quality"   // 8 Gen 1, 7-series
        n >= 640 -> "quality"   // 855 / 865 / 888
        n >= 610 -> "balanced"
        n >= 600 -> "fast"      // 6xx below 610
        else -> FALLBACK
    }

    private fun maliG(digits: String): String? {
        val n = digits.toInt()
        return if (digits.length == 3) {
            when {
                n >= 900 -> "2x12"      // G925
                n >= 710 -> "quality"   // G710 / G715 / G720
                n >= 610 -> "balanced"  // G610 / G615 / G620
                n >= 510 -> "fast"      // G510 / G57 class
                else -> FALLBACK
            }
        } else {
            when (n) {
                in 76..79 -> "balanced" // G76 / G77 / G78
                in 57..68 -> "fast"     // G57 / G68
                else -> FALLBACK
            }
        }
    }
}

/**
 * Decides when Auto steps down. Feed it mpv's `frame-drop-count` about once a second while the
 * video plays. A second is "bad" when at least [dropsPerSecond] frames were dropped in it; a
 * step-down needs [badNeeded] bad seconds within the last [window] counted seconds.
 *
 * Seconds are not counted while [sample]'s `disturbed` is true (seeking, buffering, a network
 * stall, paused, a fast playback speed) nor for [graceMs] after it ends or after a step, since
 * mpv drops frames on its own while it catches up. A counter that goes backwards (new file,
 * output re-init) restarts the baseline.
 */
class UpscalerStepDown(
    private val window: Int = 10,
    private val badNeeded: Int = 6,
    private val dropsPerSecond: Int = 2,
    private val graceMs: Long = 5_000,
) {
    private var graceUntil = Long.MIN_VALUE
    private var lastCount = -1L
    private var lastMs = 0L
    private val seconds = ArrayDeque<Boolean>()

    /** Start over, e.g. on a new file or a model change; nothing counts for [graceMs]. */
    fun reset(nowMs: Long) {
        graceUntil = nowMs + graceMs
        lastCount = -1L
        seconds.clear()
    }

    /** Returns true when the model should step down now (the detector then resets itself). */
    fun sample(nowMs: Long, dropCount: Long, disturbed: Boolean): Boolean {
        if (disturbed) {
            reset(nowMs)
            return false
        }
        if (dropCount < lastCount) {
            // The counter restarted (new file, output re-init): earlier seconds no longer apply.
            seconds.clear()
        }
        if (lastCount < 0 || dropCount < lastCount || nowMs < graceUntil) {
            lastCount = dropCount
            lastMs = nowMs
            return false
        }
        val elapsed = nowMs - lastMs
        if (elapsed < 1_000) return false
        if (elapsed > 3_000) {
            // A gap (app in the background, a missed pause event): rebase, count nothing.
            lastCount = dropCount
            lastMs = nowMs
            return false
        }
        val perSecond = (dropCount - lastCount) * 1_000.0 / elapsed
        lastCount = dropCount
        lastMs = nowMs
        seconds.addLast(perSecond >= dropsPerSecond)
        while (seconds.size > window) seconds.removeFirst()
        if (seconds.count { it } >= badNeeded) {
            reset(nowMs)
            return true
        }
        return false
    }
}

/**
 * Auto for one engine: turns a stored "auto" selection into a concrete model and steps it
 * down. Engine thread only. Pure: GPU, Vulkan state and the remembered step-down come in
 * through the lambdas so the JVM tests can drive it.
 */
class AutoUpscalerController(
    private val renderer: () -> String?,
    private val vulkan: () -> Boolean,
    /** Stored `model@renderer` string ("" when nothing was remembered). */
    private val loadCap: () -> String,
    private val saveCap: (String) -> Unit,
    private val detector: UpscalerStepDown = UpscalerStepDown(),
) {
    /** True while the selection is Auto. */
    var active = false
        private set

    /** Model in use while [active]; null means Auto stepped down to Off. */
    var model: String? = null
        private set

    /** Provider and profile to apply: the stored ones, or the model Auto runs. */
    fun concretize(
        providerId: String,
        profileId: String,
        available: () -> Collection<String>,
        nowMs: Long,
    ): Pair<String, String> {
        if (providerId != CunnyUpscalerProvider.ID || profileId != UpscalerAuto.PROFILE_ID) {
            active = false
            model = null
            UpscalerAutoStatus.model = null
            return providerId to profileId
        }
        if (!active) {
            val gpu = renderer()
            model = UpscalerAuto.start(
                UpscalerGpuTable.startModel(gpu), vulkan(), available(),
                UpscalerAuto.decodeCap(loadCap(), gpu),
            )
            active = true
            detector.reset(nowMs)
        }
        UpscalerAutoStatus.model = model ?: UpscalerAuto.OFF
        return model?.let { CunnyUpscalerProvider.ID to it } ?: (UpscalerConfig.OFF_PROVIDER_ID to "")
    }

    /** New file: the first seconds always drop frames while mpv settles. */
    fun onNewFile(nowMs: Long) {
        if (active) detector.reset(nowMs)
    }

    /** Returns true when the model changed and the pipeline must be applied again. */
    fun sample(nowMs: Long, dropCount: Long, disturbed: Boolean, available: () -> Collection<String>): Boolean {
        val current = model ?: return false
        if (!active || !detector.sample(nowMs, dropCount, disturbed)) return false
        model = UpscalerAuto.stepDown(current, available())
        saveCap(UpscalerAuto.encodeCap(model, renderer()))
        UpscalerAutoStatus.model = model ?: UpscalerAuto.OFF
        return true
    }
}

/** What Auto runs right now, for the settings screen; null when Auto is not playing. */
object UpscalerAutoStatus {
    @Volatile var model: String? = null
}
