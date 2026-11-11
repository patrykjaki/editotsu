package ani.dantotsu.settings.screens

import android.content.Context
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.media.anime.player.ActiveVideoPipeline
import ani.dantotsu.media.anime.player.VulkanLibmpv
import ani.dantotsu.media.anime.player.upscaler.GpuInfo
import ani.dantotsu.media.anime.player.upscaler.UpscalerAuto
import ani.dantotsu.media.anime.player.upscaler.UpscalerAutoStatus
import ani.dantotsu.media.anime.player.upscaler.UpscalerGpuTable
import ani.dantotsu.media.anime.player.upscaler.UpscalerMenuModel
import ani.dantotsu.media.anime.player.upscaler.UpscalerMenuModel.Kind
import ani.dantotsu.media.anime.player.upscaler.UpscalerProvider
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString

/** Settings > Player > Upscaler: Off, Auto and the CuNNy models, light to heavy. */
internal object UpscalerMenu {

    private fun vulkan(): UpscalerMenuModel.Vulkan = when {
        !BuildConfig.LIBMPV_VULKAN -> UpscalerMenuModel.Vulkan.NOT_IN_BUILD
        !VulkanLibmpv.supported -> UpscalerMenuModel.Vulkan.NOT_ON_DEVICE
        VulkanLibmpv.failed -> UpscalerMenuModel.Vulkan.FAILED
        else -> UpscalerMenuModel.Vulkan.RUNNING
    }

    private fun entries(provider: UpscalerProvider?) =
        UpscalerMenuModel.entries(provider?.isAvailable() == true, vulkan())

    private fun stored() =
        PrefManager.getVal<String>(PrefName.UpscalerProviderId) to PrefManager.getVal<String>(PrefName.UpscalerProfileId)

    /** Model Auto runs now, or would start with on this GPU; null = Off. */
    private fun autoModel(provider: UpscalerProvider?): String? {
        UpscalerAutoStatus.model?.let { return it.takeIf { m -> m != UpscalerAuto.OFF } }
        val gpu = GpuInfo.renderer
        val available = provider?.takeIf { it.isAvailable() }?.profiles()?.map { it.id }.orEmpty()
        return UpscalerAuto.start(
            UpscalerGpuTable.startModel(gpu), vulkan() == UpscalerMenuModel.Vulkan.RUNNING, available,
            UpscalerAuto.decodeCap(PrefManager.getVal(PrefName.UpscalerAutoCap), gpu),
        )
    }

    private fun label(c: Context, e: UpscalerMenuModel.Entry): String = when (e.kind) {
        Kind.OFF -> c.getString(R.string.upscaler_off)
        Kind.AUTO -> c.getString(R.string.upscaler_auto)
        Kind.MODEL -> modelName(c, e.profileId)
        Kind.SHARPEN -> c.getString(R.string.upscaler_sharpen_name, modelName(c, UpscalerMenuModel.modelOf(e.profileId)))
    }

    private fun modelName(c: Context, id: String) = when (id) {
        "fast" -> c.getString(R.string.upscaler_fast)
        "balanced" -> c.getString(R.string.upscaler_balanced)
        "quality" -> c.getString(R.string.upscaler_quality)
        else -> id
    }

    private fun detail(id: String) = when (id) {
        "fast" -> R.string.upscaler_fast_desc
        "balanced" -> R.string.upscaler_balanced_desc
        "quality" -> R.string.upscaler_quality_desc
        "2x12" -> R.string.upscaler_2x12_desc
        "3x12" -> R.string.upscaler_3x12_desc
        "4x12" -> R.string.upscaler_4x12_desc
        "4x16" -> R.string.upscaler_4x16_desc
        "4x24" -> R.string.upscaler_4x24_desc
        else -> null
    }

    /** Row value: "Auto (2x12)", "Balanced", "Off". */
    fun currentLabel(c: Context, provider: UpscalerProvider?): String {
        val list = entries(provider)
        val (providerId, profileId) = stored()
        val e = list[UpscalerMenuModel.selectedIndex(list, providerId, profileId)]
        if (e.kind != Kind.AUTO) return label(c, e)
        val model = autoModel(provider)?.let { modelName(c, it) } ?: c.getString(R.string.upscaler_off)
        return c.getString(R.string.upscaler_auto_value, model)
    }

    fun show(host: SettingsHost, provider: UpscalerProvider?) {
        val c = host.activity
        val list = entries(provider)
        if (list.size == 1) snackString(c.getString(R.string.upscaler_unavailable))
        val (providerId, profileId) = stored()
        val autoNow = autoModel(provider)?.let { modelName(c, it) } ?: c.getString(R.string.upscaler_off)
        val modelsHeader = c.getString(R.string.upscaler_group_models)
        val sharpenHeader = c.getString(R.string.upscaler_group_sharpen)
        val options = list.map { e ->
            when (e.kind) {
                Kind.OFF -> ChoiceOption(label(c, e))
                Kind.AUTO -> ChoiceOption(label(c, e), c.getString(R.string.upscaler_auto_desc, autoNow))
                Kind.MODEL -> ChoiceOption(
                    label(c, e), detail(e.profileId)?.let(c::getString), e.enabled,
                    group = modelsHeader, groupDetail = c.getString(R.string.upscaler_group_models_desc),
                )
                Kind.SHARPEN -> ChoiceOption(
                    label(c, e), null, e.enabled,
                    group = sharpenHeader, groupDetail = c.getString(R.string.upscaler_group_sharpen_desc),
                )
            }
        }
        val note = when (vulkan()) {
            UpscalerMenuModel.Vulkan.RUNNING -> null
            UpscalerMenuModel.Vulkan.NOT_IN_BUILD -> c.getString(R.string.upscaler_vulkan_not_in_build)
            UpscalerMenuModel.Vulkan.NOT_ON_DEVICE -> c.getString(R.string.upscaler_vulkan_not_on_device)
            UpscalerMenuModel.Vulkan.FAILED -> c.getString(R.string.upscaler_vulkan_failed_note)
        }.takeIf { list.size > 1 }
        ChoiceSheet.single(
            c, c.getString(R.string.upscaler), options,
            UpscalerMenuModel.selectedIndex(list, providerId, profileId), note = note,
        ) { i ->
            val e = list[i]
            PrefManager.setVal(PrefName.UpscalerProviderId, e.providerId)
            PrefManager.setVal(PrefName.UpscalerProfileId, e.profileId)
            // Picking Auto again forgets an earlier step-down, so it retries from the GPU table.
            if (e.kind == Kind.AUTO) PrefManager.setVal(PrefName.UpscalerAutoCap, "")
            ActiveVideoPipeline.refreshFromPrefs()
            host.refresh()
        }
    }
}
