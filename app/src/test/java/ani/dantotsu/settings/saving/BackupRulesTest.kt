package ani.dantotsu.settings.saving

import ani.dantotsu.settings.saving.internal.Location
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android backup must not copy the prefs files that hold account tokens. */
class BackupRulesTest {

    // Protected: AniList token, MAL token, app password. Irrelevant: comments sign-in.
    private val tokenFiles = listOf(Location.Protected, Location.Irrelevant).map { "${it.location}.xml" }

    private fun res(name: String): String {
        val file = listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name")).first { it.exists() }
        return file.readText()
    }

    private fun excludes(xml: String): List<String> =
        Regex("""<exclude\s+domain="sharedpref"\s+path="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()

    @Test
    fun fullBackupContentExcludesTokenPrefs() {
        val excluded = excludes(res("backup_rules.xml"))
        for (f in tokenFiles) assertTrue("$f not excluded", f in excluded)
    }

    @Test
    fun dataExtractionRulesExcludeTokenPrefsForCloudAndTransfer() {
        val xml = res("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
            val excluded = excludes(body)
            for (f in tokenFiles) assertTrue("$f not excluded in $section", f in excluded)
        }
    }

    @Test
    fun manifestUsesTheRules() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}
