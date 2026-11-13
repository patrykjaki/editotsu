package ani.dantotsu.parsers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import android.graphics.drawable.Drawable
import ani.dantotsu.FileUrl
import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.network.NetworkHelper
import me.xdrop.fuzzywuzzy.FuzzySearch
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.system.measureTimeMillis


abstract class BaseParser {

    /**
     * Name that will be shown in Source Selection
     * **/
    open val name: String = ""

    /**
     * Name used to save the ShowResponse selected by user or by autoSearch
     * **/
    open val saveName: String = ""

    /**
     * The main URL of the Site
     * **/
    open val hostUrl: String = ""

    /**
     * override as `true` if the site **only** has NSFW media
     * **/
    open val isNSFW = false

    /**
     * mostly redundant for official app, But override if you want to add different languages
     * **/
    open val language = "English"

    /**
     * Icon of the site, can be null
     */
    open val icon: Drawable? = null

    /**
     * Icon URL of the site, used if local icon is null
     */
    open val iconUrl: String? = null

    /**
     *  Search for Anime/Manga/Novel, returns a List of Responses
     *
     *  use `encode(query)` to encode the query for making requests
     * **/
    abstract suspend fun search(query: String): List<ShowResponse>

    /**
     * The function app uses to auto find the anime/manga using Media data provided by anilist
     *
     * Isn't necessary to override, but recommended, if you want to improve auto search results
     * **/
    open suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        (this as? DynamicMangaParser)?.let { ext ->
            mediaObj.selected?.langIndex?.let {
                ext.sourceLanguage = it
            }
        }
        var response: ShowResponse? = loadSavedShowResponse(mediaObj.id)
        if (response != null && this !is OfflineMangaParser && this !is OfflineAnimeParser) {
            if (response.sManga == null) {
                response.sManga = SManga.create().apply {
                    url = response.link
                    title = response.name
                    thumbnail_url = response.coverUrl.url
                }
            }
            if (response.sAnime == null) {
                response.sAnime = SAnime.create().apply {
                    url = response.link
                    title = response.name
                    thumbnail_url = response.coverUrl.url
                }
            }
            saveShowResponse(mediaObj.id, response, true)
        } else {
            // The title, the romaji title and the synonyms used to be searched one
            // after another, each a full request to the source. They are asked at
            // the same time now, in two rounds, and judged in the same order as before.
            val mainName = mediaObj.mainName()
            val romaji = mediaObj.nameRomaji
            setUserText("Searching : $mainName")
            Logger.log("Searching : $mainName")
            val (results, romajiResults) = coroutineScope {
                val romajiSearch = if (romaji.isNotBlank() && romaji != mainName) {
                    async { searchOrEmpty(romaji) }
                } else null
                // a failed search for the main title still fails the lookup, as before
                val main = search(mainName)
                main to (romajiSearch?.await() ?: main)
            }
            results.forEach { Logger.log("Result: ${it.name}") }
            response = results.maxByOrNull { titleMatchScore(it.name, mainName) }

            if (response == null || titleMatchScore(response.name, mainName) < 100) {
                val closestRomaji = romajiResults.maxByOrNull { titleMatchScore(it.name, romaji) }
                Logger.log("Closest match from RomajiResults: ${closestRomaji?.name ?: "None"}")
                response = if (response == null) {
                    closestRomaji
                } else {
                    val romajiRatio = titleMatchScore(closestRomaji?.name ?: "", romaji)
                    val mainNameRatio = titleMatchScore(response.name, mainName)
                    if (romajiRatio > mainNameRatio) closestRomaji else response
                }
            }

            if (response == null || titleMatchScore(response.name, mainName) < 80) {
                val synonyms = mediaObj.synonyms.take(MAX_SYNONYM_SEARCHES)
                    .filter { it.isNotBlank() && it != mainName && it != romaji }
                if (synonyms.isNotEmpty()) {
                    setUserText("Searching : ${synonyms.first()}")
                    Logger.log("Searching : $synonyms")
                    val synonymResults = coroutineScope {
                        synonyms.map { synonym -> async { searchOrEmpty(synonym) } }.map { it.await() }
                    }
                    for ((synonym, found) in synonyms.zip(synonymResults)) {
                        val closestSynonym = found.maxByOrNull { titleMatchScore(it.name, synonym) } ?: continue
                        val synonymRatio = titleMatchScore(closestSynonym.name, synonym)
                        if (synonymRatio < 80) continue
                        val currentRatio = response?.let { titleMatchScore(it.name, mainName) } ?: 0
                        if (synonymRatio > currentRatio) {
                            Logger.log("Synonym search found a better match: ${closestSynonym.name}")
                            response = closestSynonym
                            if (synonymRatio >= 95) break
                        }
                    }
                }
            }
            saveShowResponse(mediaObj.id, response)
        }
        return response
    }

    private suspend fun searchOrEmpty(query: String): List<ShowResponse> = try {
        search(query)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.log("Search failed for $query: $e")
        emptyList()
    }

    /**
     * ping the site to check if it's working or not.
     * @return Triple<Int, Int?, String> : First Int is the status code, Second Int is the response time in milliseconds, Third String is the response message.
     */
    fun ping(): Triple<Int, Int?, String> {
        val client = OkHttpClient()
        var statusCode = 0
        var responseTime: Int? = null
        var responseMessage = ""
        println("Pinging $name at $hostUrl")
        try {
            val request = Request.Builder()
                .url(hostUrl)
                .build()
            responseTime = measureTimeMillis {
                client.newCall(request).execute().use { response ->
                    statusCode = response.code
                    responseMessage = response.message.ifEmpty { "None" }
                }
            }.toInt()
        } catch (e: Exception) {
            Logger.log("Failed to ping $name")
            statusCode = -1
            responseMessage = if (e.message.isNullOrEmpty()) "None" else e.message!!
            Logger.log(e)
        }
        return Triple(statusCode, responseTime, responseMessage)
    }

    /**
     * Used to get an existing Search Response which was selected by the user.
     * @param mediaId : The mediaId of the Media object.
     * @return ShowResponse? : The ShowResponse object if found, else null.
     */
    open suspend fun loadSavedShowResponse(mediaId: Int): ShowResponse? {
        checkIfVariablesAreEmpty()
        val resp = PrefManager.getNullableCustomVal(
            "${saveName}_$mediaId",
            null,
            ShowResponse::class.java
        )
        if (resp != null) {
            if (resp.sManga == null) {
                resp.sManga = SManga.create().apply {
                    url = resp.link
                    title = resp.name
                    thumbnail_url = resp.coverUrl.url
                }
            }
            if (resp.sAnime == null) {
                resp.sAnime = SAnime.create().apply {
                    url = resp.link
                    title = resp.name
                    thumbnail_url = resp.coverUrl.url
                }
            }
        }
        return resp
    }

    /**
     * Used to save Shows Response using `saveName`.
     * @param mediaId : The mediaId of the Media object.
     * @param response : The ShowResponse object to save.
     * @param selected : Boolean : If the ShowResponse was selected by the user or not.
     */
    open fun saveShowResponse(mediaId: Int, response: ShowResponse?, selected: Boolean = false) {
        if (response != null) {
            checkIfVariablesAreEmpty()
            setUserText(
                "${
                    if (selected) currContext()!!.getString(R.string.source_selected) else currContext()!!.getString(
                        R.string.found
                    )
                } : ${response.name}"
            )
            PrefManager.setCustomVal("${saveName}_$mediaId", response)
        }
    }

    /**
     * A saved match is keyed by [saveName] only. The host used to be required here too, which
     * made every source without a fixed address (a self-hosted server, an index that is set up
     * in its settings) report "no installed extensions".
     */
    fun checkIfVariablesAreEmpty() {
        if (saveName.isEmpty()) throw UninitializedPropertyAccessException("Cannot find any installed extensions")
    }

    open var showUserText = ""
    open var showUserTextListener: ((String) -> Unit)? = null

    /**
     * Used to show messages & errors to the User, a useful way to convey what's currently happening or what was done.
     * **/
    fun setUserText(string: String) {
        showUserText = string
        showUserTextListener?.invoke(showUserText)
    }

    fun encode(input: String): String = URLEncoder.encode(input, "utf-8").replace("+", "%20")
    fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

    val defaultImage = "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg"
}


/**
 * A single show which contains some episodes/chapters which is sent by the site using their search function.
 *
 * You might wanna include `otherNames` & `total` too, to further improve user experience.
 *
 * You can also store a Map of Strings if you want to save some extra data.
 * **/
data class ShowResponse(
    val name: String,
    val link: String,
    val coverUrl: FileUrl,

    //would be Useful for custom search, ig
    val otherNames: List<String> = listOf(),

    //Total number of Episodes/Chapters in the show.
    val total: Int? = null,

    //In case you want to sent some extra data
    val extra: MutableMap<String, String>? = null,

    //SAnime object from Aniyomi
    @Transient var sAnime: SAnime? = null,

    //SManga object from Aniyomi
    @Transient var sManga: SManga? = null
) : Serializable {
    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null,
        extra: MutableMap<String, String>? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total, extra)

    constructor(
        name: String,
        link: String,
        coverUrl: String,
        otherNames: List<String> = listOf(),
        total: Int? = null
    ) : this(name, link, FileUrl(coverUrl), otherNames, total)

    constructor(name: String, link: String, coverUrl: String, otherNames: List<String> = listOf())
            : this(name, link, FileUrl(coverUrl), otherNames)

    constructor(name: String, link: String, coverUrl: String)
            : this(name, link, FileUrl(coverUrl))

    constructor(name: String, link: String, coverUrl: String, sAnime: SAnime)
            : this(name, link, FileUrl(coverUrl), sAnime = sAnime)

    constructor(name: String, link: String, coverUrl: String, sManga: SManga)
            : this(name, link, FileUrl(coverUrl), sManga = sManga)

    private fun writeObject(out: java.io.ObjectOutputStream) {
        out.defaultWriteObject()
        val anime = sAnime
        if (anime != null) {
            out.writeBoolean(true)
            out.writeObject(anime.url)
            out.writeObject(anime.title)
            out.writeObject(anime.artist)
            out.writeObject(anime.author)
            out.writeObject(anime.description)
            out.writeObject(anime.genre)
            out.writeInt(anime.status)
            out.writeObject(anime.thumbnail_url)
            out.writeObject(anime.background_url)
            out.writeDouble(anime.season_number)
            out.writeBoolean(anime.initialized)
        } else {
            out.writeBoolean(false)
        }
        val manga = sManga
        if (manga != null) {
            out.writeBoolean(true)
            out.writeObject(manga.url)
            out.writeObject(manga.title)
            out.writeObject(manga.artist)
            out.writeObject(manga.author)
            out.writeObject(manga.description)
            out.writeObject(manga.genre)
            out.writeInt(manga.status)
            out.writeObject(manga.thumbnail_url)
            out.writeBoolean(manga.initialized)
        } else {
            out.writeBoolean(false)
        }
    }

    private fun readObject(inStream: java.io.ObjectInputStream) {
        inStream.defaultReadObject()
        if (inStream.readBoolean()) {
            val anime = SAnime.create().apply {
                url = inStream.readObject() as String
                title = inStream.readObject() as String
                artist = inStream.readObject() as? String
                author = inStream.readObject() as? String
                description = inStream.readObject() as? String
                genre = inStream.readObject() as? String
                status = inStream.readInt()
                thumbnail_url = inStream.readObject() as? String
                background_url = inStream.readObject() as? String
                season_number = inStream.readDouble()
                initialized = inStream.readBoolean()
            }
            sAnime = anime
        }
        if (inStream.readBoolean()) {
            val manga = SManga.create().apply {
                url = inStream.readObject() as String
                title = inStream.readObject() as String
                artist = inStream.readObject() as? String
                author = inStream.readObject() as? String
                description = inStream.readObject() as? String
                genre = inStream.readObject() as? String
                status = inStream.readInt()
                thumbnail_url = inStream.readObject() as? String
                initialized = inStream.readBoolean()
            }
            sManga = manga
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
