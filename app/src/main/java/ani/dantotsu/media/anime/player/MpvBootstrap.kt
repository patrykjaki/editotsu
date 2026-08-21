package ani.dantotsu.media.anime.player

import android.content.Context
import java.io.File
import kotlin.math.abs

enum class PresencePolicy {
    REQUIRED_FOR_CURRENT_DANTOTSU_CONFIG,
    OPTIONAL_IF_RESOURCE_UNAVAILABLE
}

enum class BootstrapPhase {
    PRE_INIT,
    POST_INIT
}

enum class SettingApi {
    OPTION_STRING,
    PROPERTY_STRING,
    PROPERTY_INT,
    PROPERTY_DOUBLE,
    PROPERTY_BOOLEAN
}

enum class VerificationTiming {
    IMMEDIATE,
    AFTER_INIT,
    AFTER_BOOTSTRAP
}

sealed interface SettingVerification {
    data object None : SettingVerification
    data class ReadBackString(val expected: String) : SettingVerification
    data class ReadBackInt(val expected: Int) : SettingVerification
    data class ReadBackDouble(val expected: Double, val tolerance: Double = 1e-6) : SettingVerification
    data class ReadBackBoolean(val expected: Boolean) : SettingVerification
    data class CustomInvariant(val check: (MpvClient) -> Boolean) : SettingVerification
}

sealed interface MpvSettingValue {
    data class StringValue(val value: String) : MpvSettingValue
    data class IntValue(val value: Int) : MpvSettingValue
    data class DoubleValue(val value: Double) : MpvSettingValue
    data class BooleanValue(val value: Boolean) : MpvSettingValue
}

data class MpvInitOption(
    val name: String,
    val value: MpvSettingValue,
    val api: SettingApi = SettingApi.OPTION_STRING,
    val verification: SettingVerification = SettingVerification.None,
    val verificationTiming: VerificationTiming = VerificationTiming.IMMEDIATE
)

class MpvOptionApplyException(
    val optionName: String,
    val rc: Int
) : RuntimeException("Option $optionName rejected by libmpv: rc=$rc")

class MpvSettingVerificationException(
    val settingName: String,
    val timing: VerificationTiming,
    val expected: String,
    val actual: String?
) : RuntimeException("Verification mismatch for $settingName at $timing: expected=$expected, actual=$actual")

sealed interface ResolvedInitSetting {
    val name: String
    val policy: PresencePolicy
    val phase: BootstrapPhase

    data class Apply(
        val option: MpvInitOption,
        override val phase: BootstrapPhase = BootstrapPhase.PRE_INIT,
        override val policy: PresencePolicy = PresencePolicy.REQUIRED_FOR_CURRENT_DANTOTSU_CONFIG
    ) : ResolvedInitSetting {
        override val name: String get() = option.name
    }

    data class Skip(
        override val name: String,
        val reason: String,
        override val phase: BootstrapPhase = BootstrapPhase.PRE_INIT,
        override val policy: PresencePolicy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
    ) : ResolvedInitSetting
}

data class ResolvedMpvPaths(
    val fontsDir: File?,
    val cacheDir: File?
)

fun prepareMpvDirectories(context: Context): ResolvedMpvPaths {
    val filesDir = context.filesDir
    val fontsDir = if (filesDir != null) {
        val f = File(filesDir, "fonts")
        if (!f.exists()) f.mkdirs()
        if (f.exists() && f.isDirectory && f.canRead()) f else null
    } else null

    val cacheDir = context.cacheDir
    val validCache = if (cacheDir != null && cacheDir.exists() && cacheDir.isDirectory && cacheDir.canRead() && cacheDir.canWrite()) {
        cacheDir
    } else null

    return ResolvedMpvPaths(fontsDir, validCache)
}

fun buildCurrentBootstrapSettings(context: Context): List<ResolvedInitSetting> {
    val paths = prepareMpvDirectories(context)
    val settings = mutableListOf<ResolvedInitSetting>()

    settings.add(ResolvedInitSetting.Apply(MpvInitOption("config", MpvSettingValue.StringValue("no"))))

    if (paths.fontsDir != null) {
        settings.add(
            ResolvedInitSetting.Apply(
                MpvInitOption("sub-fonts-dir", MpvSettingValue.StringValue(paths.fontsDir.absolutePath)),
                policy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
            )
        )
    } else {
        settings.add(
            ResolvedInitSetting.Skip(
                "sub-fonts-dir",
                "fonts_dir_not_readable_or_missing",
                policy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
            )
        )
    }

    if (paths.cacheDir != null) {
        settings.add(
            ResolvedInitSetting.Apply(
                MpvInitOption("gpu-shader-cache-dir", MpvSettingValue.StringValue(paths.cacheDir.absolutePath)),
                policy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
            )
        )
        settings.add(
            ResolvedInitSetting.Apply(
                MpvInitOption("icc-cache-dir", MpvSettingValue.StringValue(paths.cacheDir.absolutePath)),
                policy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
            )
        )
    } else {
        settings.add(
            ResolvedInitSetting.Skip(
                "gpu-shader-cache-dir",
                "cache_dir_not_writable",
                policy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
            )
        )
        settings.add(
            ResolvedInitSetting.Skip(
                "icc-cache-dir",
                "cache_dir_not_writable",
                policy = PresencePolicy.OPTIONAL_IF_RESOURCE_UNAVAILABLE
            )
        )
    }

    val staticOptions = listOf(
        MpvInitOption("vo", MpvSettingValue.StringValue("gpu")),
        MpvInitOption("hwdec", MpvSettingValue.StringValue("auto")),
        MpvInitOption("hwdec-codecs", MpvSettingValue.StringValue("all")),
        MpvInitOption("sub-auto", MpvSettingValue.StringValue("no")),
        MpvInitOption("keep-open", MpvSettingValue.StringValue("no")),
        MpvInitOption("ytdl", MpvSettingValue.StringValue("no")),
        MpvInitOption("force-window", MpvSettingValue.StringValue("no")),
        MpvInitOption("idle", MpvSettingValue.StringValue("yes"))
    )
    staticOptions.forEach {
        settings.add(ResolvedInitSetting.Apply(it, policy = PresencePolicy.REQUIRED_FOR_CURRENT_DANTOTSU_CONFIG))
    }

    return settings
}

fun redactOptionValue(setting: MpvInitOption): String {
    val rawValue = when (val v = setting.value) {
        is MpvSettingValue.StringValue -> v.value
        is MpvSettingValue.IntValue -> v.value.toString()
        is MpvSettingValue.DoubleValue -> v.value.toString()
        is MpvSettingValue.BooleanValue -> if (v.value) "yes" else "no"
    }
    return redactOptionValue(setting.name, rawValue)
}

fun applySetting(mpvClient: MpvClient, setting: MpvInitOption) {
    val safeValue = redactOptionValue(setting)
    nativeMarker("APPLY_SETTING before ${setting.name}=$safeValue")

    when (setting.api) {
        SettingApi.OPTION_STRING -> {
            val str = when (val v = setting.value) {
                is MpvSettingValue.StringValue -> v.value
                is MpvSettingValue.IntValue -> v.value.toString()
                is MpvSettingValue.DoubleValue -> v.value.toString()
                is MpvSettingValue.BooleanValue -> if (v.value) "yes" else "no"
            }
            val rc = mpvClient.setOptionString(setting.name, str)
            nativeMarker("APPLY_SETTING after ${setting.name} rc=$rc")
            if (rc < 0) {
                throw MpvOptionApplyException(setting.name, rc)
            }
        }
        SettingApi.PROPERTY_STRING -> {
            val str = (setting.value as MpvSettingValue.StringValue).value
            mpvClient.setPropertyString(setting.name, str)
            nativeMarker("APPLY_PROPERTY after ${setting.name}")
        }
        SettingApi.PROPERTY_INT -> {
            val intVal = (setting.value as MpvSettingValue.IntValue).value
            mpvClient.setPropertyInt(setting.name, intVal)
            nativeMarker("APPLY_PROPERTY after ${setting.name}")
        }
        SettingApi.PROPERTY_DOUBLE -> {
            val dblVal = (setting.value as MpvSettingValue.DoubleValue).value
            mpvClient.setPropertyDouble(setting.name, dblVal)
            nativeMarker("APPLY_PROPERTY after ${setting.name}")
        }
        SettingApi.PROPERTY_BOOLEAN -> {
            val boolVal = (setting.value as MpvSettingValue.BooleanValue).value
            mpvClient.setPropertyBoolean(setting.name, boolVal)
            nativeMarker("APPLY_PROPERTY after ${setting.name}")
        }
    }
}

fun verifySetting(mpvClient: MpvClient, setting: MpvInitOption, currentTiming: VerificationTiming) {
    if (setting.verificationTiming != currentTiming) return

    when (val v = setting.verification) {
        is SettingVerification.None -> {}
        is SettingVerification.ReadBackString -> {
            val actual = mpvClient.getPropertyString(setting.name)
                ?: throw MpvSettingVerificationException(setting.name, currentTiming, v.expected, null)
            if (actual != v.expected) {
                throw MpvSettingVerificationException(setting.name, currentTiming, v.expected, actual)
            }
        }
        is SettingVerification.ReadBackInt -> {
            val actual = mpvClient.getPropertyInt(setting.name)
                ?: throw MpvSettingVerificationException(setting.name, currentTiming, v.expected.toString(), null)
            if (actual != v.expected) {
                throw MpvSettingVerificationException(setting.name, currentTiming, v.expected.toString(), actual.toString())
            }
        }
        is SettingVerification.ReadBackDouble -> {
            val actual = mpvClient.getPropertyDouble(setting.name)
                ?: throw MpvSettingVerificationException(setting.name, currentTiming, v.expected.toString(), null)
            if (abs(actual - v.expected) > v.tolerance) {
                throw MpvSettingVerificationException(setting.name, currentTiming, v.expected.toString(), actual.toString())
            }
        }
        is SettingVerification.ReadBackBoolean -> {
            val actual = mpvClient.getPropertyBoolean(setting.name)
                ?: throw MpvSettingVerificationException(setting.name, currentTiming, v.expected.toString(), null)
            if (actual != v.expected) {
                throw MpvSettingVerificationException(setting.name, currentTiming, v.expected.toString(), actual.toString())
            }
        }
        is SettingVerification.CustomInvariant -> {
            if (!v.check(mpvClient)) {
                throw MpvSettingVerificationException(setting.name, currentTiming, "custom_invariant_pass", "failed")
            }
        }
    }
}
