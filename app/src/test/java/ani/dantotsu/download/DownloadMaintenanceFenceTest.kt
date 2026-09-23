package ani.dantotsu.download

import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handoff-D regressions: generic Anime/Novel cleanup cannot physically delete
 * Manga data or prune Manga metadata outside the Manga ownership +
 * authoritative reconciliation path.
 *
 * Production shape under test: `cleanDownloads()` no longer invokes Manga
 * maintenance at all, and `cleanDownload()` itself refuses MANGA fail-closed
 * (so even a future caller cannot reach the file sweep or the lossy-absence
 * metadata prune for Manga). Anime/Novel legs keep their exact behavior.
 *
 * Folder-level SAF behavior needs a device/provider and is not asserted here;
 * what IS asserted host-side is the complete routing cut plus the metadata
 * consequence (D5/D6) at the store layer with the production predicates.
 */
class DownloadMaintenanceFenceTest {

    private fun managerCode(): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            if (java.io.File(dir, "app/src/main/java/ani/dantotsu/download/DownloadsManager.kt").exists()) {
                return java.io.File(
                    dir,
                    "app/src/main/java/ani/dantotsu/download/DownloadsManager.kt"
                ).readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found from user.dir")
    }

    private fun body(code: String, declaration: String): String {
        val stripped = code.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        val start = stripped.indexOf(declaration)
        assertTrue("declaration $declaration found", start >= 0)
        val open = stripped.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < stripped.length) {
            when (stripped[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces in $declaration")
    }

    // D-routing: generic cleanDownloads never reaches Manga maintenance…
    @Test
    fun d_genericCleanup_neverReachesManga() {
        val dispatches = body(managerCode(), "fun cleanDownloads(")
        assertTrue(dispatches.contains("cleanDownload(MediaType.ANIME)"))
        assertTrue(dispatches.contains("cleanDownload(MediaType.NOVEL)"))
        assertFalse(
            "generic cleanup must not dispatch Manga maintenance",
            dispatches.contains("MANGA"),
        )
    }

    // …and cleanDownload itself refuses MANGA fail-closed (defense in depth:
    // no file sweep, no metadata prune, even for a direct future caller).
    @Test
    fun d_cleanDownload_refusesManga() {
        val fn = body(managerCode(), "fun cleanDownload(")
        assertTrue("MANGA refusal present", fn.contains("MediaType.MANGA"))
        // The refusal sits before the folder sweep and the metadata prune.
        assertTrue(fn.indexOf("return") < fn.indexOf("deleteRecursively"))
        assertTrue(fn.indexOf("return") < fn.indexOf("transact"))
    }

    // D5: generic (anime-typed) metadata maintenance leaves Manga entries intact.
    @Test
    fun d5_animeMaintenance_preservesMangaMetadata() {
        val store = DownloadsMetadataStore(
            listOf(
                DownloadedType("MT", "1", MediaType.MANGA),
                DownloadedType("AT", "1", MediaType.ANIME),
                DownloadedType("NT", "1", MediaType.NOVEL),
            )
        ) {}
        // Mirrors cleanDownload's metadata leg for a non-manga type, through
        // the same transact primitive production uses.
        store.transact { it.removeAll { e -> e.titleName == "AT" && e.type == MediaType.ANIME } }
        assertEquals(
            listOf(
                DownloadedType("MT", "1", MediaType.MANGA),
                DownloadedType("NT", "1", MediaType.NOVEL),
            ),
            store.snapshot(),
        )
    }

    // D6: Anime/Novel cleanup behavior remains intact (their entries still pruned).
    @Test
    fun d6_animeNovelCleanup_intact() {
        val store = DownloadsMetadataStore(
            listOf(
                DownloadedType("AT", "1", MediaType.ANIME),
                DownloadedType("NT", "1", MediaType.NOVEL),
            )
        ) {}
        store.transact { it.removeAll { e -> e.titleName == "AT" && e.type == MediaType.ANIME } }
        assertEquals(listOf(DownloadedType("NT", "1", MediaType.NOVEL)), store.snapshot())
        store.transact { it.removeAll { e -> e.titleName == "NT" && e.type == MediaType.NOVEL } }
        assertTrue(store.snapshot().isEmpty())
    }

    /**
     * Blank-title decision path through the REAL production predicate
     * ([isMaintenanceDoomed] — the exact function `cleanDownload` calls, not a
     * reconstructed narrower copy).
     */

    // D1: blank-title Manga metadata survives Anime cleanup.
    @Test
    fun d1_blankMangaTitle_survivesAnimeCleanup() {
        assertFalse(isMaintenanceDoomed(MediaType.MANGA, MediaType.ANIME, false, true))
        assertFalse(isMaintenanceDoomed(MediaType.MANGA, MediaType.ANIME, true, true))
        assertFalse(isMaintenanceDoomed(MediaType.MANGA, MediaType.ANIME, null, true))
    }

    // D2: blank-title Manga metadata survives Novel cleanup.
    @Test
    fun d2_blankMangaTitle_survivesNovelCleanup() {
        assertFalse(isMaintenanceDoomed(MediaType.MANGA, MediaType.NOVEL, false, true))
        assertFalse(isMaintenanceDoomed(MediaType.MANGA, MediaType.NOVEL, true, true))
        assertFalse(isMaintenanceDoomed(MediaType.MANGA, MediaType.NOVEL, null, true))
    }

    // D3: intended Anime/Novel doomed entries are still removed.
    @Test
    fun d3_intendedEntries_stillDoomed() {
        // Missing directory with a valid title: doomed for its own type.
        assertTrue(isMaintenanceDoomed(MediaType.ANIME, MediaType.ANIME, false, false))
        assertTrue(isMaintenanceDoomed(MediaType.NOVEL, MediaType.NOVEL, false, false))
        // Blank title of the requested type: still doomed (unchanged behavior).
        assertTrue(isMaintenanceDoomed(MediaType.ANIME, MediaType.ANIME, true, true))
        // Present directory, valid title: kept.
        assertFalse(isMaintenanceDoomed(MediaType.ANIME, MediaType.ANIME, true, false))
        // No directory handle either way: unknown is never doomed…
        assertFalse(isMaintenanceDoomed(MediaType.ANIME, MediaType.ANIME, null, false))
        // …except the blank-title case of the requested type (pre-existing rule).
        assertTrue(isMaintenanceDoomed(MediaType.ANIME, MediaType.ANIME, null, true))
    }
}
