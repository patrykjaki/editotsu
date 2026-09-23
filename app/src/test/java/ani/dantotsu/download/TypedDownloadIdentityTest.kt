package ani.dantotsu.download

import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handoff-C regressions: typed download identity. The old
 * `titleName && chapterName` predicate (without `type`) let an anime/novel
 * removal wipe a manga COMPLETE entry sharing the same title/chapter (and
 * vice versa) while the orphaned files stayed behind.
 *
 * Every typed metadata removal uses [sameTypedDownload]; the store-level tests
 * below drive the exact predicate production passes to `removeAll`, and the
 * wiring test pins its use at all three call sites (plus the preserved
 * fail-closed MANGA refusal).
 */
class TypedDownloadIdentityTest {

    private fun entry(title: String, chapter: String, type: MediaType) =
        DownloadedType(title, chapter, type)

    private fun storeOf(vararg entries: DownloadedType): DownloadsMetadataStore =
        DownloadsMetadataStore(entries.toList()) {}

    // C1: ANIME and MANGA share title/chapter; removing ANIME keeps Manga.
    @Test
    fun c1_removeAnime_keepsManga() {
        val store = storeOf(
            entry("One Piece", "1", MediaType.MANGA),
            entry("One Piece", "1", MediaType.ANIME),
        )
        val removed = store.transact {
            it.removeAll { e -> sameTypedDownload(e, "One Piece", "1", MediaType.ANIME) }
        }
        assertTrue(removed)
        assertEquals(listOf(entry("One Piece", "1", MediaType.MANGA)), store.snapshot())
    }

    // C2: NOVEL and MANGA share title/chapter; removing NOVEL keeps Manga.
    @Test
    fun c2_removeNovel_keepsManga() {
        val store = storeOf(
            entry("One Piece", "1", MediaType.MANGA),
            entry("One Piece", "1", MediaType.NOVEL),
        )
        store.transact {
            it.removeAll { e -> sameTypedDownload(e, "One Piece", "1", MediaType.NOVEL) }
        }
        assertEquals(listOf(entry("One Piece", "1", MediaType.MANGA)), store.snapshot())
    }

    // C3: the correct typed entry is removed (and only it).
    @Test
    fun c3_correctTypedEntryRemoved() {
        val store = storeOf(
            entry("T", "1", MediaType.MANGA),
            entry("T", "2", MediaType.MANGA),
            entry("T", "1", MediaType.ANIME),
        )
        val removed = store.transact {
            it.removeAll { e -> sameTypedDownload(e, "T", "1", MediaType.MANGA) }
        }
        assertTrue(removed)
        assertEquals(
            listOf(entry("T", "2", MediaType.MANGA), entry("T", "1", MediaType.ANIME)),
            store.snapshot(),
        )
        // A non-matching typed removal removes nothing.
        val removedNothing = store.transact {
            it.removeAll { e -> sameTypedDownload(e, "T", "9", MediaType.MANGA) }
        }
        assertFalse(removedNothing)
    }

    // C4 + wiring: Manga fail-closed removeDownload remains refused, and all
    // three typed removal sites route through sameTypedDownload.
    @Test
    fun c4_wiring_typedPredicateAndMangaRefusal() {
        val text = java.io.File(repoRoot(), "app/src/main/java/ani/dantotsu/download/DownloadsManager.kt").readText()
        val code = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        // Fail-closed MANGA refusal still precedes any work in removeDownload.
        val removeDownload = body(code, "fun removeDownload(")
        assertTrue(removeDownload.contains("refused for MANGA"))
        assertTrue(
            "refusal precedes mutation",
            removeDownload.indexOf("refused for MANGA") < removeDownload.indexOf("transact"),
        )
        // All three typed removal sites use the shared predicate…
        for (fn in listOf("fun removeDownload(", "fun removeDirectory(", "fun getSize(")) {
            assertTrue("$fn uses sameTypedDownload", body(code, fn).contains("sameTypedDownload"))
        }
        // …and no untyped titleName/chapterName removal predicate remains.
        assertFalse(
            "no untyped removeAll predicate",
            body(code, "fun removeDownload(").contains("it.titleName ==") ||
                body(code, "fun removeDirectory(").contains("it.titleName =="),
        )
    }

    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            if (java.io.File(dir, "app/src/main/java/ani/dantotsu/download/DownloadsManager.kt").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found from user.dir")
    }

    private fun body(code: String, declaration: String): String {
        val start = code.indexOf(declaration)
        assertTrue("declaration $declaration found", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces in $declaration")
    }
}
