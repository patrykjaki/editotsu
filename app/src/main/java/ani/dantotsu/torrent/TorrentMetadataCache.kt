package ani.dantotsu.torrent

import java.io.File
import java.net.URLDecoder

/**
 * Bencoded torrent metadata (the .torrent built from a magnet's info dict) saved per
 * info hash, so playing a release again skips the metadata fetch from peers (0.8-6 s
 * on the owner's bench). Pure file IO, JVM-tested. Writes go through a temp file and
 * rename; the oldest entries are pruned past [maxEntries].
 */
class TorrentMetadataCache(private val dir: File, private val maxEntries: Int = MAX_ENTRIES) {

    fun load(infoHash: String): ByteArray? {
        val file = fileFor(infoHash) ?: return null
        return try {
            file.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readBytes()?.also {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(infoHash: String, bencoded: ByteArray?): Boolean {
        val file = fileFor(infoHash) ?: return false
        if (bencoded == null || bencoded.isEmpty() || bencoded.size > MAX_BYTES) return false
        return try {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bencoded)
            val ok = tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
            if (ok) prune()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Drop a broken entry (it failed to parse or had another info hash). */
    fun discard(infoHash: String) {
        try { fileFor(infoHash)?.delete() } catch (_: Exception) {}
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach {
            try { it.delete() } catch (_: Exception) {}
        }
    }

    private fun fileFor(infoHash: String): File? {
        val h = infoHash.trim().uppercase()
        if (!HASH.matches(h)) return null
        return File(dir, h + SUFFIX)
    }

    companion object {
        const val MAX_ENTRIES = 300
        /** Metadata of a big batch is a few MB at most. */
        const val MAX_BYTES = 8L * 1024 * 1024
        private const val SUFFIX = ".torrent"
        private val HASH = Regex("^[0-9A-F]{40}$")

        /**
         * A minimal .torrent around a raw bencoded info dict: `d4:info<info>e`. The info
         * hash is the hash of the info dict, so it is the same as the magnet's.
         */
        fun wrapInfoSection(info: ByteArray): ByteArray? {
            if (info.isEmpty() || info.size > MAX_BYTES || info.first() != 'd'.code.toByte() || info.last() != 'e'.code.toByte()) return null
            return "d4:info".toByteArray(Charsets.US_ASCII) + info + "e".toByteArray(Charsets.US_ASCII)
        }

        /** The magnet's own trackers (tr=), which a cached .torrent may not carry. */
        fun magnetTrackers(magnet: String): List<String> {
            if (!magnet.startsWith("magnet:", ignoreCase = true)) return emptyList()
            return magnet.substringAfter('?', "").split('&')
                .filter { it.startsWith("tr=", ignoreCase = true) }
                .mapNotNull { runCatching { URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }
}
