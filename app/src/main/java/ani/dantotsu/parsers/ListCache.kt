package ani.dantotsu.parsers

import ani.dantotsu.util.Logger
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.security.MessageDigest

/**
 * Last episode/chapter list per source + title, so opening a title shows it at once while
 * the extension is asked again (roadmap A3, after upstream 9723f959). Only a display
 * cache: any read failure (class changed, file damaged) counts as "nothing cached".
 */
class ListCache<T : Serializable>(private val dir: () -> File?) {

    @Suppress("UNCHECKED_CAST")
    fun load(source: String, mediaId: Int): MutableMap<String, T>? {
        val file = file(source, mediaId) ?: return null
        if (!file.isFile) return null
        return try {
            ObjectInputStream(file.inputStream().buffered()).use { input ->
                (input.readObject() as? Map<String, T>)?.toMutableMap()
            }
        } catch (e: Exception) {
            Logger.log("ListCache: dropping unreadable ${file.name}: ${e.message}")
            file.delete()
            null
        }
    }

    fun save(source: String, mediaId: Int, list: Map<String, T>) {
        if (list.isEmpty()) return
        val file = file(source, mediaId) ?: return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            ObjectOutputStream(tmp.outputStream().buffered()).use { it.writeObject(LinkedHashMap(list)) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Logger.log("ListCache: could not save ${file.name}: ${e.message}")
        }
    }

    private fun file(source: String, mediaId: Int): File? {
        val base = dir() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest("$source\u0000$mediaId".toByteArray())
        return File(base, digest.joinToString("") { "%02x".format(it) })
    }

    companion object {
        /** Keys in [fresh] that were not in [cached]; empty when there was no cached list. */
        fun <T> newKeys(cached: Map<String, T>?, fresh: Map<String, T>): List<String> =
            if (cached.isNullOrEmpty()) emptyList() else fresh.keys.filter { it !in cached }
    }
}
