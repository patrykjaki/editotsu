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
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.defaultHeaders
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.SubtitleType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.Locale

class PlayerSubtitleManager(
    private val activity: AppCompatActivity,
    private val model: MediaDetailsViewModel,
    private val getEngine: () -> PlaybackEngine?
) {

    var subtitleDelayMs: Long = 0L
        private set
    var audioDelayMs: Long = 0L
        private set

    var activeSubtitleDisplayName: String? = null
        private set
    var activeSubtitleId: String? = null
        private set

    private var currentActiveSubFile: File? = null
    private var currentActiveSubRawContent: String? = null
    private var currentActiveSubFormat: String = "SRT"
    private var currentActiveSubLang: String = ""
    private var serverSubJob: Job? = null

    @Volatile var pendingSubtitleLabel: String? = null
    @Volatile var pendingTrackId: String? = null
    @Volatile var initialSubtitleLabel: String? = null

    fun applySubtitlePreferences() {
        val engine = getEngine() ?: return
        val useSourceStyling = PrefManager.getVal<Boolean>(PrefName.UseSourceSubtitleStyling)

        if (useSourceStyling) {
            engine.applySubtitleStyle(SubtitleStyle(mode = SubtitleStyleMode.SOURCE))
        } else {
            val fontSize = PrefManager.getVal<Int>(PrefName.FontSize).toFloat()
            val textColor = PrefManager.getVal<Int>(PrefName.PrimaryColor)
            val borderColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
            val bgColor = PrefManager.getVal<Int>(PrefName.SubBackground)
            val borderWidth = PrefManager.getVal<Float>(PrefName.SubStroke)
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
            val fontFile = File(activity.filesDir, "fonts/$fontName")

            engine.applySubtitleStyle(
                SubtitleStyle(
                    mode = SubtitleStyleMode.CUSTOM,
                    fontFamily = fontName,
                    fontFile = if (fontFile.exists()) fontFile else null,
                    fontSizeSp = fontSize,
                    textColor = textColor,
                    borderColor = borderColor,
                    backgroundColor = bgColor,
                    borderWidth = borderWidth,
                    bottomMarginDp = bottomMargin
                )
            )
        }
    }

    fun setActiveServerSubtitle(sub: Subtitle?) {
        if (sub == null) {
            currentActiveSubFile = null
            currentActiveSubRawContent = null
            activeSubtitleDisplayName = null
            activeSubtitleId = null
            serverSubJob?.cancel()
            return
        }
        val formatStr = when (sub.type) {
            SubtitleType.ASS -> "ASS"
            SubtitleType.VTT -> "VTT"
            SubtitleType.SRT -> "SRT"
            else -> "SRT"
        }
        currentActiveSubFormat = formatStr
        currentActiveSubLang = sub.language
        activeSubtitleDisplayName = "${sub.language} [Server]"
        activeSubtitleId = sub.language

        serverSubJob?.cancel()
        val rawUrl = sub.file.url
        val resolvedUrl = resolveSubtitleUrl(rawUrl, "", "")
        if (resolvedUrl.isNotBlank()) {
            serverSubJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val client = Injekt.get<NetworkHelper>().client
                    val requestBuilder = Request.Builder().url(resolvedUrl)
                    defaultHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                    sub.file.headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val content = response.body?.string()
                            if (!content.isNullOrBlank()) {
                                val ext = when (formatStr) {
                                    "ASS" -> "ass"
                                    "VTT" -> "vtt"
                                    else -> "srt"
                                }
                                val langName = sub.language
                                val file = File(activity.cacheDir, "server_sub_${langName.hashCode()}.$ext")
                                file.writeText(content)
                                currentActiveSubFile = file
                                currentActiveSubRawContent = content
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("PlayerSubtitleManager: Server sub cache error: ${e.message}")
                }
            }
        }
    }

    fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
        Logger.log("PlayerSubtitleManager: Subtitle delay set to ${delayMs}ms")
        getEngine()?.setSubtitleDelay(delayMs)
    }

    fun setAudioDelay(delayMs: Long) {
        audioDelayMs = delayMs
        Logger.log("PlayerSubtitleManager: Audio delay set to ${delayMs}ms")
        getEngine()?.setAudioDelay(delayMs)
    }

    fun shiftSubtitleTimestamps(content: String, format: String, delayMs: Long): String {
        if (delayMs == 0L) return content

        fun shiftSrtVttTime(timeStr: String): String {
            val isComma = timeStr.contains(",")
            val delimiter = if (isComma) "," else "."
            val cleanStr = timeStr.trim().replace(",", ".")
            val parts = cleanStr.split(":", ".")
            val hours: Long
            val mins: Long
            val secs: Long
            val millis: Long
            if (parts.size == 4) {
                hours = parts[0].toLongOrNull() ?: 0L
                mins = parts[1].toLongOrNull() ?: 0L
                secs = parts[2].toLongOrNull() ?: 0L
                millis = parts[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else if (parts.size == 3) {
                hours = 0L
                mins = parts[0].toLongOrNull() ?: 0L
                secs = parts[1].toLongOrNull() ?: 0L
                millis = parts[2].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else return timeStr

            val totalMs = (hours * 3600000L) + (mins * 60000L) + (secs * 1000L) + millis
            val newTotalMs = (totalMs + delayMs).coerceAtLeast(0L)

            val newHours = newTotalMs / 3600000L
            val newMins = (newTotalMs % 3600000L) / 60000L
            val newSecs = (newTotalMs % 60000L) / 1000L
            val newMillis = newTotalMs % 1000L

            return "%02d:%02d:%02d%s%03d".format(newHours, newMins, newSecs, delimiter, newMillis)
        }

        fun shiftAssTime(timeStr: String): String {
            val parts = timeStr.trim().split(":", ".")
            if (parts.size != 4) return timeStr
            val hours = parts[0].toLongOrNull() ?: 0L
            val mins = parts[1].toLongOrNull() ?: 0L
            val secs = parts[2].toLongOrNull() ?: 0L
            val centis = parts[3].padEnd(2, '0').take(2).toLongOrNull() ?: 0L

            val totalMs = (hours * 3600000L) + (mins * 60000L) + (secs * 1000L) + (centis * 10L)
            val newTotalMs = (totalMs + delayMs).coerceAtLeast(0L)

            val newHours = newTotalMs / 3600000L
            val newMins = (newTotalMs % 3600000L) / 60000L
            val newSecs = (newTotalMs % 60000L) / 1000L
            val newCentis = (newTotalMs % 1000L) / 10L

            return "%d:%02d:%02d.%02d".format(newHours, newMins, newSecs, newCentis)
        }

        val srtVttRegex = Regex("""^(\d{1,2}:\d{2}:\d{2}[,\.]\d{3}|\d{2}:\d{2}[,\.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,\.]\d{3}|\d{2}:\d{2}[,\.]\d{3})(.*)$""")
        val assDialogueRegex = Regex("""^(Dialogue:\s*[^,]+,)(\d+:\d{2}:\d{2}\.\d{2}),(\d+:\d{2}:\d{2}\.\d{2})(,.*)$""", RegexOption.IGNORE_CASE)

        return when (format.uppercase(Locale.ROOT)) {
            "ASS", "SSA" -> {
                content.lines().joinToString("\n") { line ->
                    val match = assDialogueRegex.find(line.trim())
                    if (match != null) {
                        val prefix = match.groupValues[1]
                        val start = shiftAssTime(match.groupValues[2])
                        val end = shiftAssTime(match.groupValues[3])
                        val suffix = match.groupValues[4]
                        "$prefix$start,$end$suffix"
                    } else {
                        line
                    }
                }
            }
            else -> {
                content.lines().joinToString("\n") { line ->
                    val trimmed = line.trim()
                    val match = srtVttRegex.find(trimmed)
                    if (match != null) {
                        val start = shiftSrtVttTime(match.groupValues[1])
                        val end = shiftSrtVttTime(match.groupValues[2])
                        val extra = match.groupValues[3]
                        "$start --> $end$extra"
                    } else {
                        line
                    }
                }
            }
        }
    }

    fun clearTransientSubtitleCache(episodeId: String) {
        model.clearFetchedSubtitles(episodeId)
        model.clearLocalSubtitles(episodeId)
        try {
            activity.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("online_subtitle_") || file.name.startsWith("local_sub_") || file.name.startsWith("shifted_") || file.name.startsWith("server_sub_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerSubtitleManager", "clearTransientSubtitleCache error: ${e.message}")
        }
    }

    fun applyOnlineSubtitleUrl(url: String, id: String, lang: String, displayName: String = lang, provider: String = "Online") {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = Injekt.get<NetworkHelper>().client
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
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
                        subtitleContent.trimStart().startsWith("WEBVTT") -> "VTT"
                        subtitleContent.contains("[Script Info]") || subtitleContent.contains("\\[Events\\]") -> "ASS"
                        subtitleContent.contains("<tt ") || subtitleContent.contains("<tt>") -> "TTML"
                        else -> "SRT"
                    }

                    val cleanedContent = if (detectedFormat == "ASS") {
                        stripAssPositioning(subtitleContent)
                    } else {
                        subtitleContent
                    }

                    val extension = when (detectedFormat) {
                        "VTT" -> "vtt"
                        "ASS" -> "ass"
                        "TTML" -> "ttml"
                        else -> "srt"
                    }

                    val subtitleFile = File(activity.cacheDir, "online_subtitle_${id.hashCode()}.$extension")
                    subtitleFile.writeText(cleanedContent)

                    withContext(Dispatchers.Main) {
                        applySubtitleFromFile(subtitleFile, lang, detectedFormat, displayName, id, provider)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Failed to load subtitle: ${e.message}", activity)
                }
            }
        }
    }

    fun applyOnlineSubtitle(subtitle: StremioSub, displayName: String = subtitle.lang, provider: String = "OpenSubtitles") {
        applyOnlineSubtitleUrl(subtitle.url, subtitle.id.ifBlank { subtitle.url }, subtitle.lang, displayName, provider)
    }

    fun applyWyzieSubtitle(subtitle: WyzieSub) {
        val display = subtitle.displayLabel.ifBlank { subtitle.language }
        applyOnlineSubtitleUrl(subtitle.url, subtitle.url, subtitle.language, display, "Wyzie")
    }

    fun applySubSourceSubtitle(sub: SubSourceSub) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = SubSourceSubtitles.downloadSubtitleContent(sub.id)
            if (result != null) {
                val (filename, content) = result
                val detectedFormat = when {
                    filename.endsWith(".vtt", ignoreCase = true) || content.trimStart().startsWith("WEBVTT") -> "VTT"
                    filename.endsWith(".ass", ignoreCase = true) || filename.endsWith(".ssa", ignoreCase = true) || content.contains("[Script Info]") -> "ASS"
                    filename.endsWith(".ttml", ignoreCase = true) || content.contains("<tt>") -> "TTML"
                    else -> "SRT"
                }
                val cleaned = if (detectedFormat == "ASS") stripAssPositioning(content) else content
                val ext = when (detectedFormat) {
                    "VTT" -> "vtt"
                    "ASS" -> "ass"
                    "TTML" -> "ttml"
                    else -> "srt"
                }
                val cacheFile = File(activity.cacheDir, "online_subtitle_${sub.id.hashCode()}.$ext")
                cacheFile.writeText(cleaned)
                val display = sub.releaseName.ifBlank { sub.lang }
                withContext(Dispatchers.Main) {
                    applySubtitleFromFile(cacheFile, sub.lang, detectedFormat, display, sub.id, "SubSource")
                }
            } else {
                withContext(Dispatchers.Main) {
                    snackString("Failed to download SubSource subtitle", activity)
                }
            }
        }
    }

    fun applyOpenSubRestSubtitle(item: OpenSubRestItem) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val downloadUrl = OpenSubtitlesRestApi.getDownloadUrl(item.fileId)
            if (downloadUrl != null) {
                val display = item.fileName.ifBlank { item.language }
                applyOnlineSubtitleUrl(downloadUrl, item.fileId.toString(), item.language, display, "OpenSubtitles")
            } else {
                withContext(Dispatchers.Main) {
                    snackString("Failed to get OpenSubtitles download link", activity)
                }
            }
        }
    }

    private fun applySubtitleFromFile(
        file: File,
        lang: String,
        mimeType: String,
        displayName: String = lang,
        id: String = file.name,
        provider: String = "Online"
    ) {
        val engine = getEngine() ?: return
        currentActiveSubFile = file
        currentActiveSubRawContent = runCatching { file.readText() }.getOrNull()
        currentActiveSubFormat = when {
            file.extension.equals("vtt", ignoreCase = true) -> "VTT"
            file.extension.equals("ass", ignoreCase = true) || file.extension.equals("ssa", ignoreCase = true) -> "ASS"
            file.extension.equals("ttml", ignoreCase = true) -> "TTML"
            else -> "SRT"
        }
        currentActiveSubLang = lang
        activeSubtitleDisplayName = "$displayName ($provider)"
        activeSubtitleId = id

        val label = "Online: $displayName"
        engine.addExternalSubtitle(file.absolutePath, label, lang.ifBlank { "und" }, select = true)
        snackString("Subtitle loaded: $label", activity)
    }

    fun applyLocalSubtitle(uri: Uri, media: Media?) {
        val engine = getEngine() ?: return
        try {
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
            if (ext == "ass") {
                cacheFile.writeText(stripAssPositioning(subtitleBytes.toString(Charsets.UTF_8)))
            } else {
                cacheFile.writeBytes(subtitleBytes)
            }

            val fileName = uri.lastPathSegment ?: "Custom"
            val label = "Local: $fileName"

            currentActiveSubFile = cacheFile
            currentActiveSubRawContent = if (ext == "ass") stripAssPositioning(subtitleBytes.toString(Charsets.UTF_8)) else subtitleBytes.toString(Charsets.UTF_8)
            currentActiveSubFormat = ext.uppercase(Locale.ROOT)
            currentActiveSubLang = "und"
            activeSubtitleDisplayName = "[Local] $fileName"
            activeSubtitleId = "local_sub_${uri.toString().hashCode()}"

            if (media != null) {
                val mediaId = media.id
                val episodeId = media.anime?.selectedEpisode ?: "1"
                val newLocalSub = Subtitle(
                    language = "[Local] $fileName",
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

    private fun stripAssPositioning(assContent: String): String {
        val lines = assContent.lines().toMutableList()
        var inEvents = false
        var inStyles = false
        val styleFormatMap = mutableMapOf<String, Int>()

        for (i in lines.indices) {
            val line = lines[i]
            val trimmedLine = line.trim()
            if (trimmedLine.equals("[Events]", ignoreCase = true)) {
                inEvents = true
                inStyles = false
                continue
            } else if (trimmedLine.equals("[V4+ Styles]", ignoreCase = true) ||
                trimmedLine.equals("[V4 Styles]", ignoreCase = true)
            ) {
                inStyles = true
                inEvents = false
                continue
            } else if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]")) {
                inEvents = false
                inStyles = false
                continue
            }

            if (inStyles) {
                if (trimmedLine.startsWith("Format:", ignoreCase = true)) {
                    val parts = trimmedLine.substringAfter(":").split(",")
                    styleFormatMap.clear()
                    parts.forEachIndexed { index, name ->
                        styleFormatMap[name.trim().lowercase(Locale.ROOT)] = index
                    }
                } else if (trimmedLine.startsWith("Style:", ignoreCase = true) && styleFormatMap.isNotEmpty()) {
                    val styleContent = trimmedLine.substringAfter("Style:")
                    val parts = styleContent.split(",").toMutableList()
                    val alignIdx = styleFormatMap["alignment"]
                    if (alignIdx != null && alignIdx < parts.size) {
                        parts[alignIdx] = "2"
                    }
                    val marginVIdx = styleFormatMap["marginv"]
                    if (marginVIdx != null && marginVIdx < parts.size) {
                        parts[marginVIdx] = "0"
                    }
                    lines[i] = "Style: ${parts.joinToString(",")}"
                }
            }

            if (inEvents && (trimmedLine.startsWith("Dialogue:", ignoreCase = true) ||
                        trimmedLine.startsWith("Comment:", ignoreCase = true))
            ) {
                var modifiedLine = line
                modifiedLine = modifiedLine.replace(Regex("\\\\pos\\([^)]*\\)"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\move\\([^)]*\\)"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\an[1-9]"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\a[1-9]+"), "")
                modifiedLine = modifiedLine.replace(Regex("\\\\org\\([^)]*\\)"), "")
                lines[i] = modifiedLine
            }
        }
        return lines.joinToString("\n")
    }

    fun resolveSubtitleUrl(subtitleUrl: String, vararg baseUrls: String): String {
        val subtitleUri = runCatching { URI(subtitleUrl) }.getOrElse {
            Logger.log("Failed to parse subtitle URL '$subtitleUrl': ${it.message}")
            return subtitleUrl
        }
        if (subtitleUri.isAbsolute) return subtitleUri.toString()

        baseUrls.forEach { baseUrl ->
            val resolved = runCatching {
                if (baseUrl.isBlank()) null else URI(baseUrl).resolve(subtitleUri).takeIf { it.isAbsolute }?.toString()
            }.getOrNull()
            if (!resolved.isNullOrBlank()) return resolved
        }
        return subtitleUrl
    }

    fun buildSubtitleId(index: Int, language: String, url: String): String {
        val normalizedLanguage = language.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
        val normalizedUrlTail = runCatching { URI(url).path.substringAfterLast('/').ifBlank { "track" } }
            .getOrDefault("track")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
        return "ext_sub_${index}_${normalizedLanguage}_${normalizedUrlTail}"
    }

    fun release() {
        serverSubJob?.cancel()
        serverSubJob = null
    }
}
