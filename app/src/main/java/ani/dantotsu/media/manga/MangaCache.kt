package ani.dantotsu.media.manga

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import ani.dantotsu.media.manga.mangareader.BaseImageAdapter
import ani.dantotsu.media.manga.mangareader.MAX_PAGE_DIMENSION
import ani.dantotsu.media.manga.mangareader.pageInSampleSize
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.createDataSaver
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

data class ImageData(
    val page: Page,
    val source: HttpSource
) {
    /**
     * Downloads the page once through the source's own client (its
     * interceptors, referer and cookies) and decodes those bytes. Formats
     * BitmapFactory cannot read (JXL, AVIF before Android 12) are decoded from
     * the same bytes with libvips. Out-of-memory is not caught here, so the
     * reader can log it and show the page error state.
     */
    suspend fun fetchAndProcessImage(
        page: Page,
        httpSource: HttpSource
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val dataSaver = createDataSaver()
                val originalUrl = page.imageUrl
                val compressedUrl = dataSaver.compress(originalUrl ?: "")

                if (compressedUrl != originalUrl) {
                    val saved = try {
                        page.imageUrl = compressedUrl
                        fetchPage(page, httpSource, "DataSaver")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log("DataSaver failed, falling back to original: ${e.message}")
                        null
                    } finally {
                        page.imageUrl = originalUrl
                    }
                    if (saved != null) return@withContext saved
                    Logger.log("DataSaver failed or was blocked; falling back to the original URL")
                }

                fetchPage(page, httpSource, "Page")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("MangaCache image fetch error: ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchPage(page: Page, httpSource: HttpSource, label: String): Bitmap? =
        httpSource.getImage(page).use { response ->
            Logger.log("$label response: ${response.code} - ${response.message}")
            if (!response.isSuccessful) return@use null
            decodePageBytes(response.body.bytes())
        }
}

/**
 * BitmapFactory first; libvips on the same bytes when BitmapFactory cannot
 * read the format. Long strips are sampled down while decoding and then
 * scaled to [MAX_PAGE_DIMENSION]; ordinary pages decode at full size.
 */
fun decodePageBytes(bytes: ByteArray): Bitmap? = decodeWithFallback(
    bytes,
    primary = { decodeWithBitmapFactory(it) },
    fallback = { BaseImageAdapter.decodeWithLibvips(ByteArrayInputStream(it)) },
)

private fun decodeWithBitmapFactory(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inSampleSize = pageInSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?.let { BaseImageAdapter.capPageBitmap(it) }
}

/** Tries [primary], then [fallback] on the same bytes; nothing is downloaded twice. */
inline fun <T : Any> decodeWithFallback(
    bytes: ByteArray,
    primary: (ByteArray) -> T?,
    fallback: (ByteArray) -> T?,
): T? = primary(bytes) ?: fallback(bytes)

fun saveImage(
    bitmap: Bitmap,
    contentResolver: ContentResolver,
    filename: String,
    format: Bitmap.CompressFormat,
    quality: Int
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/${format.name.lowercase()}")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Dantotsu/Manga"
                )
            }

            val uri: Uri? =
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(format, quality, os)
                } ?: throw FileNotFoundException("Failed to open output stream for URI: $uri")
            }
        } else {
            val directory =
                File("${Environment.getExternalStorageDirectory()}${File.separator}Dantotsu${File.separator}Manga")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val file = File(directory, filename)

            // Check if the file already exists
            if (file.exists()) {
                println("File already exists: ${file.absolutePath}")
                return
            }

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
        }
    } catch (e: FileNotFoundException) {
        println("File not found: ${e.message}")
    } catch (e: Exception) {
        println("Exception while saving image: ${e.message}")
    }
}

class MangaCache {
    // HOLD2 bitmap-cache correction: the M4 candidate added a Bitmap LRU
    // here, but its budget was 30 KiB (sizeOf KiB vs maxSize 30), it had no
    // put callers, and sharing decoded Bitmaps across adapter/dialog owners
    // that recycle() them risks recycled-bitmap crashes. The unused path is
    // removed rather than claimed; ImageData caching is unchanged.
    private val maxEntries = 500
    private val cache = LruCache<String, ImageData>(maxEntries)

    @Synchronized
    fun put(key: String, imageDate: ImageData) {
        cache.put(key, imageDate)
    }

    @Synchronized
    fun get(key: String): ImageData? = cache.get(key)

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }

    fun size(): Int = cache.size()


}
