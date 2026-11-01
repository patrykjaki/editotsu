package ani.dantotsu.torrent

import java.io.File

/**
 * Saved libtorrent session state (DHT routing table and node id) between
 * launches, so peer discovery doesn't start cold each time (pure file IO,
 * JVM-tested). Writes go through a temp file and rename, so a crash mid
 * write leaves the previous state, never a half file.
 */
class SessionStateStore(private val file: File) {
    fun load(): ByteArray? = try {
        file.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readBytes()
    } catch (_: Exception) {
        null
    }

    fun save(state: ByteArray?): Boolean {
        if (state == null || state.isEmpty() || state.size > MAX_BYTES) return false
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(state)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            } else true
        } catch (_: Exception) {
            false
        }
    }

    /** Called when loading the saved state failed: start clean next time. */
    fun discard() {
        try { file.delete() } catch (_: Exception) {}
    }

    companion object {
        /** A DHT state is a few KB; anything far bigger isn't ours. */
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
