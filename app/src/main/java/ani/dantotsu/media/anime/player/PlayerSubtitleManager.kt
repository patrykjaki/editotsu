package ani.dantotsu.media.anime.player

import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.connections.subtitles.OpenSubRestItem
import ani.dantotsu.connections.subtitles.OpenSubtitlesRestApi
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.SubSourceSub
import ani.dantotsu.connections.subtitles.SubSourceSubtitles
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale

class PlayerSubtitleManager(
    private val activity: AppCompatActivity,
    private val model: MediaDetailsViewModel,
    private val getEngine: () -> PlaybackEngine?
) {

    fun applySubtitlePreferences() {
        val engine = getEngine() ?: return
        val useSourceStyling = PrefManager.getVal<Boolean>(PrefName.UseSourceSubtitleStyling)

        if (useSourceStyling) {
            engine.applySubtitleStyle(SubtitleStyle(mode = SubtitleStyleMode.SOURCE))
        } else {
            val primaryColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
            val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
            val bottomMargin = PrefManager.getVal<Float>(PrefName.SubBottomMargin).toInt()

            val fontName = when (PrefManager.getVal<Int>(PrefName.Font)) {
                0 -> "Poppins-SemiBold"
                1 -> "Poppins-Bold"
                2 -> "Poppins"
                3 -> "Poppins-Thin"
                4 -> "Century Gothic"
                5 -> "Levenim MT"
                6 -> "Blocky"
                else -> "Poppins-SemiBold"
            }

            val style = SubtitleStyle(
                mode = SubtitleStyleMode.CUSTOM,
                fontFamily = fontName,
                fontSizeSp = fontSize,
                textColor = primaryColor,
                borderColor = secondaryColor,
                bottomMarginDp = bottomMargin
            )
            engine.applySubtitleStyle(style)
        }
    }

    fun clearTransientSubtitleCache(episodeId: String) {
        model.clearFetchedSubtitles(episodeId)
        model.clearLocalSubtitles(episodeId)
        try {
            activity.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "clearTransientSubtitleCache error: ${e.message}")
        }
    }

    fun applyOnlineSubtitleUrl(url: String, id: String, lang: String) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        snackString("Failed to download subtitle: HTTP ${response.code}", activity)
                    }
                    return@launch
                }

                val subtitleContent = response.body?.string()
                if (subtitleContent.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        snackString("Subtitle file is empty", activity)
                    }
                    return@launch
                }

                val detectedFormat = when {
                    subtitleContent.trimStart().startsWith("WEBVTT") -> "vtt"
                    subtitleContent.contains("[Script Info]") || subtitleContent.contains("\\[Events\\]") -> "ass"
                    subtitleContent.contains("<tt ") || subtitleContent.contains("<tt>") -> "ttml"
                    else -> "srt"
                }

                val subtitleFile = File(activity.cacheDir, "online_subtitle_${id.hashCode()}.$detectedFormat")
                subtitleFile.writeText(subtitleContent)

                withContext(Dispatchers.Main) {
                    val engine = getEngine()
                    if (engine != null) {
                        val label = "Online: $lang"
                        engine.addExternalSubtitle(subtitleFile.absolutePath, label, lang, select = true)
                        snackString("Subtitle loaded: $label", activity)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}", activity)
                }
            }
        }
    }

    fun applyOnlineSubtitle(subtitle: StremioSub) {
        applyOnlineSubtitleUrl(subtitle.url, subtitle.id, subtitle.lang)
    }

    fun applySubSourceSubtitle(sub: SubSourceSub) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val downloadUrl = SubSourceSubtitles.getDownloadUrl(sub)
            if (downloadUrl != null) {
                applyOnlineSubtitleUrl(downloadUrl, sub.id, sub.lang)
            } else {
                withContext(Dispatchers.Main) {
                    snackString("Failed to get SubSource download link", activity)
                }
            }
        }
    }

    fun applyOpenSubRestSubtitle(item: OpenSubRestItem) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val downloadUrl = OpenSubtitlesRestApi.getDownloadUrl(item.fileId)
            if (downloadUrl != null) {
                applyOnlineSubtitleUrl(downloadUrl, item.fileId.toString(), item.language)
            } else {
                withContext(Dispatchers.Main) {
                    snackString("Failed to get OpenSubtitles download link", activity)
                }
            }
        }
    }

    fun applyLocalSubtitle(uri: Uri, media: Media?) {
        val engine = getEngine() ?: return
        try {
            val label = "Local Subtitle"
            val contentResolver = activity.applicationContext.contentResolver
            val rawMime = contentResolver.getType(uri)
            val uriStr = uri.toString().lowercase(Locale.ROOT)
            val ext = when {
                rawMime?.contains("vtt") == true || uriStr.contains(".vtt") -> "vtt"
                rawMime?.contains("ssa") == true || uriStr.contains(".ssa") || uriStr.contains(".ass") -> "ass"
                rawMime?.contains("ttml") == true || uriStr.contains(".ttml") || uriStr.contains(".xml") -> "ttml"
                else -> "srt"
            }

            val subtitleBytes = try {
                if (uri.scheme == "file") {
                    File(uri.path ?: "").readBytes()
                } else {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: Exception) {
                null
            }

            if (subtitleBytes == null) {
                snackString("Failed to read subtitle file", activity)
                return
            }

            val cacheFile = File(activity.cacheDir, "local_sub_${uri.toString().hashCode()}.$ext")
            cacheFile.writeBytes(subtitleBytes)

            if (media != null) {
                val mediaId = media.id
                val episodeId = media.anime?.selectedEpisode ?: "1"
                val newLocalSub = Subtitle(
                    language = "[Local] ${uri.lastPathSegment ?: "Custom"}",
                    url = uri.toString()
                )
                model.saveLocalSubtitle("$mediaId-$episodeId", newLocalSub)
                PrefManager.setCustomVal("subLang_$mediaId", newLocalSub.language)
            }

            engine.addExternalSubtitle(cacheFile.absolutePath, label, "und", select = true)
            snackString("Subtitle loaded: $label", activity)
        } catch (e: Exception) {
            snackString("Failed to load subtitle: ${e.message}", activity)
        }
    }

    fun release() {
        // Subtitle cleanup if needed
    }
}
