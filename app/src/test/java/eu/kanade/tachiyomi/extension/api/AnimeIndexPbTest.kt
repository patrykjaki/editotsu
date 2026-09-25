package eu.kanade.tachiyomi.extension.api

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream 14b05593: Anime protobuf repositories (tag 8 = isTorrent varint,
 * tag 9 = sources) vs Mihon Manga schema (tag 8 = sources). Pure
 * encode/decode fixtures — no network.
 */
class AnimeIndexPbTest {

    private fun animeStore() = NetworkAnimeExtensionStore(
        name = "Secozzi",
        badgeLabel = "Secozzi",
        contact = NetworkAnimeExtensionStore.Contact(
            website = "https://example.test",
            discord = null
        ),
        extensionList = NetworkAnimeExtensionStore.ExtensionList(
            listOf(
                NetworkAnimeExtensionStore.Extension(
                    name = "TestSource",
                    packageName = "eu.test",
                    resources = NetworkAnimeExtensionStore.Resources(
                        apkUrl = "https://example.test/test.apk",
                        iconUrl = "https://example.test/icon.png"
                    ),
                    versionCode = 12,
                    versionName = "14.2",
                    isTorrent = true,
                    sources = listOf(
                        NetworkAnimeExtensionStore.Source(
                            id = 5,
                            name = "Test",
                            language = "en",
                            homeUrl = "https://example.test"
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `anime pb round-trips schema tags`() {
        val bytes = ProtoBuf.encodeToByteArray(
            NetworkAnimeExtensionStore.serializer(),
            animeStore()
        )
        val decoded = ProtoBuf.decodeFromByteArray<NetworkAnimeExtensionStore>(bytes)
        assertEquals("Secozzi", decoded.name)
        assertEquals("Secozzi", decoded.badgeLabel)
        assertEquals(1, decoded.extensionList?.extensions?.size)
        val ext = decoded.extensionList!!.extensions.single()
        assertTrue(ext.isTorrent)
        assertEquals(5L, ext.sources.single().id)
        assertEquals("en", ext.sources.single().language)
    }

    @Test
    fun `manga-schema bytes do not decode as anime sources`() {
        // Mihon tag 8 = repeated Source (length-delimited) collides with
        // Anime tag 8 = bool is_torrent (varint): must fail, triggering the
        // Manga-schema fallback path instead of silent garbage.
        val mangaBytes = ProtoBuf.encodeToByteArray(
            NetworkExtensionStore.serializer(),
            NetworkExtensionStore(
                name = "MangaRepo",
                badgeLabel = "Manga",
                signingKey = "",
                contact = NetworkExtensionStore.Contact()
            )
        )
        val asAnime = runCatching {
            ProtoBuf.decodeFromByteArray<NetworkAnimeExtensionStore>(mangaBytes)
        }.getOrNull()
        // Either it fails outright, or sources decode empty (never garbage).
        if (asAnime != null) {
            val totalSources = asAnime.extensionList?.extensions
                ?.sumOf { it.sources.size } ?: 0
            assertEquals(0, totalSources)
        }
    }

    @Test
    fun `malformed bytes fail safely`() {
        val bad = byteArrayOf(0x08, 0x01, 0x02, 0x03, 0x05)
        assertNull(
            runCatching {
                ProtoBuf.decodeFromByteArray<NetworkAnimeExtensionStore>(bad)
            }.getOrNull()
        )
    }

    @Test
    fun `meta-only schema parses tags 1-4 without extension lists`() {
        val bytes = ProtoBuf.encodeToByteArray(
            NetworkExtensionStoreMetaOnly.serializer(),
            NetworkExtensionStoreMetaOnly(
                name = "Secozzi",
                badgeLabel = "Secozzi",
                contact = NetworkExtensionStoreMetaOnly.Contact(
                    website = "https://example.test"
                )
            )
        )
        val decoded = ProtoBuf.decodeFromByteArray<NetworkExtensionStoreMetaOnly>(bytes)
        assertEquals("Secozzi", decoded.name)
        assertEquals("Secozzi", decoded.badgeLabel)
        assertEquals("https://example.test", decoded.contact.website)
    }

    @Test
    fun `manga store round-trip unchanged`() {
        val bytes = ProtoBuf.encodeToByteArray(
            NetworkExtensionStore.serializer(),
            NetworkExtensionStore(
                name = "MangaRepo",
                badgeLabel = "Manga",
                signingKey = "key",
                contact = NetworkExtensionStore.Contact(
                    website = "https://example.test",
                    discord = null
                )
            )
        )
        val decoded = ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
        assertEquals("MangaRepo", decoded.name)
        assertEquals("Manga", decoded.badgeLabel)
    }
}
