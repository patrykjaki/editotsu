package ani.dantotsu.media.anime.player

/**
 * Single source of truth for the mpv subtitle font mapping.
 *
 * With `sub-font-provider=none`, mpv/libass resolves `sub-font` strictly
 * against the *internal family names* of the fonts in `sub-fonts-dir` —
 * there is no fontconfig to fuzzy-match display labels. Requesting a name
 * that matches no bundled family (e.g. the hyphenated guess
 * "Poppins-SemiBold" instead of the real family "Poppins SemiBold")
 * leaves libass with zero fonts and subtitles render as blank, even
 * though `sub-add`/`sub-select` succeed.
 *
 * Ground truth below was read with `fc-scan %{family}` from
 * `app/src/main/res/font/`:
 * - poppins_semi_bold.ttf      -> "Poppins", "Poppins SemiBold"
 * - poppins_bold.ttf           -> "Poppins"
 * - poppins.ttf                -> "Poppins"
 * - poppins_thin.ttf           -> "Poppins", "Poppins Thin"
 * - century_gothic_regular.TTF -> "Century Gothic"
 * - levenim_mt_bold.ttf        -> "Levenim MT"
 * - blocky.ttf                 -> "Minecraft"
 *
 * Indices match the font dropdown order in PlayerSettingsActivity and the
 * stored [ani.dantotsu.settings.PrefName.Font] value. UI labels are
 * intentionally untouched; only the mpv-facing family strings are exact.
 */
data class MpvSubtitleFont(
    val family: String,
    val fileName: String
)

object MpvSubtitleFonts {
    const val DEFAULT_INDEX = 0

    val DEFAULT = MpvSubtitleFont(
        family = "Poppins SemiBold",
        fileName = "Poppins-SemiBold.ttf"
    )

    private val byIndex: Map<Int, MpvSubtitleFont> = mapOf(
        0 to MpvSubtitleFont("Poppins SemiBold", "Poppins-SemiBold.ttf"),
        1 to MpvSubtitleFont("Poppins", "Poppins-Bold.ttf"),
        2 to MpvSubtitleFont("Poppins", "Poppins.ttf"),
        3 to MpvSubtitleFont("Poppins Thin", "Poppins-Thin.ttf"),
        4 to MpvSubtitleFont("Century Gothic", "Century-Gothic.ttf"),
        5 to MpvSubtitleFont("Levenim MT", "Levenim-MT.ttf"),
        6 to MpvSubtitleFont("Minecraft", "Blocky.ttf")
    )

    fun forIndex(index: Int): MpvSubtitleFont = byIndex[index] ?: DEFAULT

    /** File names this mapping expects MpvBootstrap to stage into fonts-dir. */
    val bundledFileNames: Set<String> = byIndex.values.map { it.fileName }.toSet()
}

/**
 * Pure helper so unit tests can pin the subtitle-font bootstrap contract
 * without an Android Context.
 *
 * NOTE: only options accepted by the bundled libmpv may be listed here.
 * `sub-fallback-fonts` was removed in beta06: the bundled build rejects it
 * via setOptionString (rc<0), and bootstrap treats init options as
 * required, so a single rejected safety-net option failed entire player
 * init on device (field report, beta05). Exact family names above are the
 * fix; no fallback chain is applied.
 */
fun defaultSubtitleFontOptions(): List<MpvInitOption> = listOf(
    MpvInitOption("sub-font", MpvSettingValue.StringValue(MpvSubtitleFonts.DEFAULT.family))
)
