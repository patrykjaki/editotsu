package ani.dantotsu.media.anime.player.upscaler

import ani.dantotsu.media.anime.player.UpscalerConfig

/**
 * The upscaler menu as data (pure, JVM-tested): Off, Auto, the SOFT models light to heavy,
 * then the Sharpen variants. Models that need Vulkan stay listed but disabled without it, so
 * the menu shows what exists and the sheet's note says why they are greyed out.
 */
object UpscalerMenuModel {
    enum class Vulkan { RUNNING, NOT_IN_BUILD, NOT_ON_DEVICE, FAILED }
    enum class Kind { OFF, AUTO, MODEL, SHARPEN }

    data class Entry(val providerId: String, val profileId: String, val kind: Kind, val enabled: Boolean)

    /** Ids of the CuNNy profiles that run on OpenGL too. */
    val GLES_MODELS = CunnyUpscalerProvider.PROFILES.map { it.id }

    fun entries(bundleAvailable: Boolean, vulkan: Vulkan): List<Entry> {
        val off = Entry(UpscalerConfig.OFF_PROVIDER_ID, "", Kind.OFF, true)
        if (!bundleAvailable) return listOf(off)
        val id = CunnyUpscalerProvider.ID
        val vk = vulkan == Vulkan.RUNNING
        val soft = UpscalerAuto.LADDER.map { Entry(id, it, Kind.MODEL, it in GLES_MODELS || vk) }
        val sharpen = CunnyUpscalerProvider.VULKAN_PROFILES.filter { it.id.endsWith("-ds") }
            .map { Entry(id, it.id, Kind.SHARPEN, vk) }
        return listOf(off, Entry(id, UpscalerAuto.PROFILE_ID, Kind.AUTO, true)) + soft + sharpen
    }

    /** Index of the stored selection; unknown, or a model that can't run now, shows as Off. */
    fun selectedIndex(entries: List<Entry>, providerId: String, profileId: String): Int =
        entries.indexOfFirst { it.enabled && it.providerId == providerId && it.profileId == profileId }
            .coerceAtLeast(0)

    /** "4x12-ds" -> "4x12". */
    fun modelOf(profileId: String) = profileId.removeSuffix("-ds")
}
