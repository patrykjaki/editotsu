package ani.dantotsu.parsers

import eu.kanade.tachiyomi.extension.InstallStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rx.Observable

/**
 * Final provenance correction (review HOLD round 2): generation-scoped,
 * subscription-scoped transactions over a memory-only provisional overlay.
 * The persisted map holds ONLY committed origins.
 */
class InstallProvenanceTest {

    private fun pkg() = "test.pkg.${System.nanoTime()}"
    private fun repo(name: String) = "https://example.test/$name/index.pb"

    private fun install(vararg steps: InstallStep) = Observable.from(steps.toList())

    @Test
    fun `constructing without subscribing changes nothing`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("a"), null) {
            install(InstallStep.Installed)
        }
        assertNull(ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `first subscription begins provisional provenance`() {
        val pkg = pkg()
        var inFlight: String? = null
        ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("a"), null) {
            Observable.just(InstallStep.Pending).doOnNext {
                inFlight = ExtensionRepoMetaHelper.getInstalledRepo(pkg)
            }.concatWith(Observable.never())
        }.subscribe({ }, { })
        assertEquals(repo("a"), inFlight)
    }

    @Test
    fun `successful completion retains selected origin`() {
        val pkg = pkg()
        val seen = mutableListOf<InstallStep>()
        ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("a"), null) {
            install(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installed)
        }.subscribe({ seen.add(it) }, { })
        assertEquals(
            listOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installed),
            seen
        )
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `error value leaves committed map untouched`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("a"), null) {
            install(InstallStep.Pending, InstallStep.Error)
        }.subscribe({ }, { })
        assertNull(ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `stream error leaves committed map untouched`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("a"), null) {
            Observable.error(IllegalStateException("boom"))
        }.subscribe({ }, { })
        assertNull(ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `cancellation restores committed origin`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("old"), "Old")
        val sub = ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("b"), null) {
            Observable.never<InstallStep>()
        }.subscribe({ }, { })
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
        sub.unsubscribe()
        assertEquals(repo("old"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `two subscriptions do not share transaction state`() {
        val pkg = pkg()
        val first = ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("a"), null) {
            Observable.never<InstallStep>()
        }
        val sub1 = first.subscribe({ }, { })
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
        sub1.unsubscribe()
        assertNull(ExtensionRepoMetaHelper.getInstalledRepo(pkg))

        ExtensionRepoMetaHelper.trackInstallProvenance(pkg, repo("b"), null) {
            install(InstallStep.Installed)
        }.subscribe({ }, { })
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `A cancels then B fails leaves OLD, never A`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("old"), "Old")
        val genA = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("a"), null)
        val genB = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("b"), null)
        // A cancels: stale generation, must not touch B's slot.
        assertEquals(false, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genA))
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
        // B fails: drops its slot; committed OLD remains, A never resurrected.
        assertEquals(true, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genB))
        assertEquals(repo("old"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `A cancels then B succeeds leaves B`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("old"), "Old")
        val genA = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("a"), null)
        val genB = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("b"), null)
        assertEquals(false, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genA))
        assertEquals(true, ExtensionRepoMetaHelper.commitInstallProvenance(pkg, genB))
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `B fails while A active leaves committed untouched`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("old"), "Old")
        ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("a"), null)
        val genB = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("b"), null)
        assertEquals(true, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genB))
        assertEquals(repo("old"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `older rollback never overwrites newer commit`() {
        val pkg = pkg()
        val genA = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("a"), null)
        val genB = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("b"), null)
        assertEquals(true, ExtensionRepoMetaHelper.commitInstallProvenance(pkg, genB))
        assertEquals(false, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genA))
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `repeated urls do not ABA-match across generations`() {
        val pkg = pkg()
        val genA = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("a"), null)
        val genB = ExtensionRepoMetaHelper.beginInstallProvenance(pkg, repo("a"), null)
        // Same URL twice: only the owning generation may drop the slot.
        assertEquals(false, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genA))
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
        assertEquals(true, ExtensionRepoMetaHelper.rollbackInstallProvenance(pkg, genB))
        assertNull(ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }
}
