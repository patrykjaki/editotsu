package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the beta04 field finding: with `sub-font-provider=none`, mpv
 * resolves `sub-font` strictly against bundled internal family names, so a
 * hyphenated guess ("Poppins-SemiBold") renders blank subtitles while the
 * exact family ("Poppins SemiBold") renders. Every selectable index must
 * map to a real family, the bootstrap default must be exact, and only
 * options the bundled libmpv accepts may be bootstrap init options
 * (beta05 proved a rejected option fails entire player init).
 */
class MpvSubtitleFontTest {

    private val expectedFamilies = mapOf(
        0 to "Poppins SemiBold",
        1 to "Poppins",
        2 to "Poppins",
        3 to "Poppins Thin",
        4 to "Century Gothic",
        5 to "Levenim MT",
        6 to "Minecraft"
    )

    private val expectedFiles = mapOf(
        0 to "Poppins-SemiBold.ttf",
        1 to "Poppins-Bold.ttf",
        2 to "Poppins.ttf",
        3 to "Poppins-Thin.ttf",
        4 to "Century-Gothic.ttf",
        5 to "Levenim-MT.ttf",
        6 to "Blocky.ttf"
    )

    @Test
    fun everySelectableIndexResolvesToExactBundledFamily() {
        for (index in 0..6) {
            val font = MpvSubtitleFonts.forIndex(index)
            assertEquals("family for index $index", expectedFamilies[index], font.family)
            assertEquals("file for index $index", expectedFiles[index], font.fileName)
        }
    }

    @Test
    fun unknownIndexFallsBackToDefault() {
        assertEquals(MpvSubtitleFonts.DEFAULT, MpvSubtitleFonts.forIndex(-1))
        assertEquals(MpvSubtitleFonts.DEFAULT, MpvSubtitleFonts.forIndex(7))
        assertEquals(MpvSubtitleFonts.DEFAULT, MpvSubtitleFonts.forIndex(Int.MAX_VALUE))
        assertEquals("Poppins SemiBold", MpvSubtitleFonts.DEFAULT.family)
    }

    @Test
    fun fontFilesCarryExtensionAndMatchStagedSet() {
        for (index in 0..6) {
            val font = MpvSubtitleFonts.forIndex(index)
            assertTrue("file must include .ttf for index $index", font.fileName.endsWith(".ttf"))
        }
        assertEquals(
            expectedFiles.values.toSet(),
            MpvSubtitleFonts.bundledFileNames
        )
    }

    @Test
    fun defaultSubtitleFontOptionsContainOnlyAcceptedOptions() {
        val options = defaultSubtitleFontOptions().associate { option ->
            option.name to (option.value as MpvSettingValue.StringValue).value
        }
        assertEquals("Poppins SemiBold", options["sub-font"])
        // Regression guard for the beta05 field failure: the bundled libmpv
        // rejects sub-fallback-fonts via setOptionString, and bootstrap
        // treats init options as required, so listing it here fails entire
        // player init on device. Never re-add without device proof.
        assertTrue(
            "sub-fallback-fonts must not be a bootstrap init option",
            !options.containsKey("sub-fallback-fonts")
        )
    }

    @Test
    fun defaultSubtitleFontOptionsApplyCleanly() {
        val fake = FakeMpvClient()
        for (option in defaultSubtitleFontOptions()) {
            applySetting(fake, option)
        }
        assertEquals("Poppins SemiBold", fake.stringProperties["sub-font"])
    }
}
