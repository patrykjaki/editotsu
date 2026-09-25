package ani.dantotsu.parsers

import eu.kanade.tachiyomi.extension.InstallStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rx.Observable

/**
 * Review HOLD Finding 2: install-origin lifecycle. The package may appear
 * via any installer path before success callbacks fire (loader timing
 * requires provisional provenance), so failure/cancel must roll back to
 * the previous mapping instead of leaving false origin data.
 * Runs on plain RxJava1 observables — no package installation involved.
 */
class InstallProvenanceTest {

    private fun pkg() = "test.pkg.${System.nanoTime()}"
    private fun repo(name: String) = "https://example.test/$name/index.pb"

    private fun tracked(
        pkg: String,
        repo: String,
        steps: Observable<InstallStep>
    ): List<InstallStep> {
        val seen = mutableListOf<InstallStep>()
        ExtensionRepoMetaHelper.trackInstallProvenance(steps, pkg, repo, null)
            .subscribe({ seen.add(it) }, { })
        return seen
    }

    @Test
    fun `successful install records selected repository`() {
        val pkg = pkg()
        val seen = tracked(
            pkg, repo("a"),
            Observable.just(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installed)
        )
        assertEquals(
            listOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installed),
            seen
        )
        // Invariant 1+6: mapping present for the loader after success.
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `failed fresh install leaves no mapping`() {
        val pkg = pkg()
        tracked(pkg, repo("a"), Observable.just(InstallStep.Pending, InstallStep.Error))
        // Invariant 3.
        assertNull(ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `failed update restores previous origin`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("a"), "A")
        tracked(pkg, repo("b"), Observable.just(InstallStep.Pending, InstallStep.Error))
        // Invariant 4.
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `stream error restores previous origin`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("a"), "A")
        ExtensionRepoMetaHelper.trackInstallProvenance(
            Observable.error(IllegalStateException("boom")), pkg, repo("b"), null
        ).subscribe({ }, { })
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `cancelled install restores previous origin`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("a"), "A")
        val sub = ExtensionRepoMetaHelper.trackInstallProvenance(
            Observable.never<InstallStep>(), pkg, repo("b"), null
        ).subscribe({ }, { })
        // Provisional mapping visible while the install is in flight (loader timing).
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
        sub.unsubscribe()
        // Invariant 5.
        assertEquals(repo("a"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `rollback never clobbers a newer legitimate write`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("a"), "A")
        val sub = ExtensionRepoMetaHelper.trackInstallProvenance(
            Observable.never<InstallStep>(), pkg, repo("b"), null
        ).subscribe({ }, { })
        // A later legitimate write (e.g. overlapping install, update-check).
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("c"), "C")
        sub.unsubscribe()
        assertEquals(repo("c"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }

    @Test
    fun `update success preserves the intended repository`() {
        val pkg = pkg()
        ExtensionRepoMetaHelper.saveInstalledExtensionRepo(pkg, repo("a"), "A")
        tracked(pkg, repo("b"), Observable.just(InstallStep.Installed))
        // Invariant 2.
        assertEquals(repo("b"), ExtensionRepoMetaHelper.getInstalledRepo(pkg))
    }
}
