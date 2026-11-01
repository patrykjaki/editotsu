package ani.dantotsu.media.anime.player.upscaler

import ani.dantotsu.media.anime.player.UpscalerConfig

/**
 * Settings-dialog model for the upscaler (pure, JVM-tested).
 * Index 0 is always Off; the rest are the provider's profiles in order.
 */
object UpscalerChoices {
    data class Choice(val providerId: String, val profileId: String, val profileName: String?)

    fun choices(provider: UpscalerProvider?): List<Choice> {
        val off = Choice(UpscalerConfig.OFF_PROVIDER_ID, "", null)
        if (provider == null || !provider.isAvailable()) return listOf(off)
        return listOf(off) + provider.profiles().map { Choice(provider.id, it.id, it.displayName) }
    }

    /** Index of the saved selection; an unknown or unavailable selection shows as Off. */
    fun selectedIndex(choices: List<Choice>, providerId: String, profileId: String): Int =
        choices.indexOfFirst { it.providerId == providerId && it.profileId == profileId }
            .coerceAtLeast(0)
}
