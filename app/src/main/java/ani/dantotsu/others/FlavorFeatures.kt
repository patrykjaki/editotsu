package ani.dantotsu.others

import ani.dantotsu.BuildConfig

/**
 * Features that need Google libraries. The fdroid build ships neither
 * Chromecast (play-services-cast) nor reader OCR (ML Kit); their code lives
 * in src/google and their entry points are hidden there.
 */
object FlavorFeatures {
    val googleServices: Boolean = !BuildConfig.FLAVOR.contains("fdroid")
}
