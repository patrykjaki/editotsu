package ani.dantotsu.download.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class HtmlToEpubUtilsTest {

    private fun readZipEntries(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }
        return entries
    }

    @Test
    fun epubIdentifier_isDeterministicAndDistinct() {
        val idA1 = HtmlToEpubUtils.epubIdentifier("https://example.invalid/novel-1/ch-1")
        val idA2 = HtmlToEpubUtils.epubIdentifier("https://example.invalid/novel-1/ch-1")
        val idB = HtmlToEpubUtils.epubIdentifier("https://example.invalid/novel-1/ch-2")

        assertEquals(idA1, idA2)
        assertNotEquals(idA1, idB)
        assertTrue(idA1.startsWith("urn:uuid:"))
        assertNotEquals("urn:uuid:12345", idA1)
    }

    @Test
    fun createEpub_embedsMatchingDeterministicBookIdInOpfAndNcx() {
        val key = "https://example.invalid/series-a/chapter-5"
        val expectedUid = HtmlToEpubUtils.epubIdentifier(key)

        val entries = readZipEntries(
            HtmlToEpubUtils.createEpub(
                title = "Chapter 5",
                htmlContent = "<p>Hello world</p>",
                identifierKey = key,
            )
        )
        val opf = entries["OEBPS/content.opf"] ?: ""
        val ncx = entries["OEBPS/toc.ncx"] ?: ""

        assertTrue(opf.contains("""<dc:identifier id="BookId">$expectedUid</dc:identifier>"""))
        assertTrue(ncx.contains("""<meta name="dtb:uid" content="$expectedUid"/>"""))
        assertTrue(!opf.contains("urn:uuid:12345"))
        assertTrue(!ncx.contains("urn:uuid:12345"))
    }

    @Test
    fun createEpub_differentChaptersNeverCollideEvenWithSameTitle() {
        val epubA = readZipEntries(
            HtmlToEpubUtils.createEpub(
                title = "Chapter 1",
                htmlContent = "<p>Text</p>",
                identifierKey = "https://example.invalid/book-a/ch-1",
            )
        )
        val epubB = readZipEntries(
            HtmlToEpubUtils.createEpub(
                title = "Chapter 1",
                htmlContent = "<p>Text</p>",
                identifierKey = "https://example.invalid/book-b/ch-1",
            )
        )

        assertNotEquals(epubA["OEBPS/content.opf"], epubB["OEBPS/content.opf"])
        assertNotEquals(epubA["OEBPS/toc.ncx"], epubB["OEBPS/toc.ncx"])
    }

    @Test
    fun createEpub_blankOrNullKeyFallsBackDeterministically() {
        val fallback1 = readZipEntries(
            HtmlToEpubUtils.createEpub("Chapter 1", "<p>Body A</p>", identifierKey = null)
        )
        val fallback2 = readZipEntries(
            HtmlToEpubUtils.createEpub("Chapter 1", "<p>Body A</p>", identifierKey = "   ")
        )
        val other = readZipEntries(
            HtmlToEpubUtils.createEpub("Chapter 2", "<p>Body B</p>", identifierKey = null)
        )

        assertEquals(fallback1["OEBPS/content.opf"], fallback2["OEBPS/content.opf"])
        assertNotEquals(fallback1["OEBPS/content.opf"], other["OEBPS/content.opf"])
        assertTrue(!(fallback1["OEBPS/content.opf"] ?: "").contains("urn:uuid:12345"))
    }
}
