package ani.dantotsu.parsers

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.api.NetworkExtensionStore
import eu.kanade.tachiyomi.extension.api.NetworkExtensionStoreMetaOnly
import eu.kanade.tachiyomi.extension.api.NetworkLegacyExtensionRepo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

@Serializable
data class ExtensionRepoMeta(
    val url: String = "",
    val name: String = "",
    val shortName: String? = null,
    val website: String = "",
    val discord: String? = null,
    val githubUrl: String? = null,
)

object ExtensionRepoMetaHelper {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val metaCache = ConcurrentHashMap<String, ExtensionRepoMeta>()
    private val installedRepoMap = ConcurrentHashMap<String, Pair<String, String?>>() // pkgName -> (repoUrl, repoName)
    @Volatile
    private var isLoaded = false

    private fun getClient(): OkHttpClient {
        return runCatching { Injekt.get<NetworkHelper>().client }.getOrElse { OkHttpClient() }
    }

    private fun ensureLoaded() {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                val rawMeta = PrefManager.getVal<String>(PrefName.ExtensionRepoMeta)
                if (!rawMeta.isNullOrBlank() && rawMeta != "{}") {
                    val map = json.decodeFromString<Map<String, ExtensionRepoMeta>>(rawMeta)
                    metaCache.putAll(map)
                }
            } catch (e: Throwable) {
                Logger.log("ExtensionRepoMetaHelper: Failed to load meta cache: $e")
            }

            try {
                val rawInstalled = PrefManager.getVal<String>(PrefName.InstalledExtensionRepos)
                if (!rawInstalled.isNullOrBlank() && rawInstalled != "{}") {
                    val map = json.decodeFromString<Map<String, Pair<String, String?>>>(rawInstalled)
                    installedRepoMap.putAll(map)
                }
            } catch (e: Throwable) {
                Logger.log("ExtensionRepoMetaHelper: Failed to load installed repo map: $e")
            }
            isLoaded = true
        }
    }

    private fun persistMetaCache() {
        try {
            val serialized = json.encodeToString(metaCache.toMap())
            PrefManager.setVal(PrefName.ExtensionRepoMeta, serialized)
        } catch (e: Throwable) {
            Logger.log("ExtensionRepoMetaHelper: Failed to persist meta cache: $e")
        }
    }

    private fun persistInstalledRepos() {
        try {
            val serialized = json.encodeToString(installedRepoMap.toMap())
            PrefManager.setVal(PrefName.InstalledExtensionRepos, serialized)
        } catch (e: Throwable) {
            Logger.log("ExtensionRepoMetaHelper: Failed to persist installed repos: $e")
        }
    }

    fun cleanRepoKey(url: String): String {
        return url.trim()
            .removeSuffix("/")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    fun cleanShownUrl(url: String): String {
        return url.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("raw.githubusercontent.com/")
            .removeSuffix("/")
            .removeSuffix("/repo.json")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/index.pb")
            .removeSuffix("/")
    }

    fun extractGitRepoPage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.trim()

        // Match GitHub raw URLs (e.g. raw.githubusercontent.com/user/repo/...)
        val rawGithubRegex = Regex("""(?:https?://)?raw\.githubusercontent\.com/([^/]+)/([^/]+)""")
        val rawMatch = rawGithubRegex.find(clean)
        if (rawMatch != null) {
            val (owner, repo) = rawMatch.destructured
            return "https://github.com/$owner/$repo"
        }

        // Match GitHub repository URLs
        val githubRegex = Regex("""(?:https?://)?github\.com/([^/]+)/([^/]+)""")
        val ghMatch = githubRegex.find(clean)
        if (ghMatch != null) {
            val (owner, repo) = ghMatch.destructured
            val cleanRepo = repo.removeSuffix(".git").substringBefore("/")
            return "https://github.com/$owner/$cleanRepo"
        }

        // Match GitLab URLs
        val gitlabRegex = Regex("""(?:https?://)?gitlab\.com/([^/]+)/([^/]+)""")
        val glMatch = gitlabRegex.find(clean)
        if (glMatch != null) {
            val (owner, repo) = glMatch.destructured
            val cleanRepo = repo.removeSuffix(".git").substringBefore("/-").substringBefore("/")
            return "https://gitlab.com/$owner/$cleanRepo"
        }

        // Match Codeberg URLs
        val codebergRegex = Regex("""(?:https?://)?codeberg\.org/([^/]+)/([^/]+)""")
        val cbMatch = codebergRegex.find(clean)
        if (cbMatch != null) {
            val (owner, repo) = cbMatch.destructured
            val cleanRepo = repo.removeSuffix(".git").substringBefore("/raw").substringBefore("/")
            return "https://codeberg.org/$owner/$cleanRepo"
        }

        return null
    }

    private fun extractBadgeNameFromUrl(url: String): String {
        val clean = url.trim()
        val rawGithubRegex = Regex("""(?:https?://)?raw\.githubusercontent\.com/([^/]+)/([^/]+)""")
        val rawMatch = rawGithubRegex.find(clean)
        if (rawMatch != null) {
            val (owner, repo) = rawMatch.destructured
            val genericRepos = setOf(
                "extensions", "anime-extensions", "manga-extensions",
                "novel-extensions", "plugins", "tachiyomi-extensions", "aniyomi-extensions"
            )
            if (genericRepos.contains(repo.lowercase())) {
                return when (owner.lowercase()) {
                    "keiyoushi" -> "Keiyoushi"
                    "aniyomiorg" -> "Aniyomi"
                    else -> owner
                }
            }
            return repo
        }
        val cleanShown = cleanShownUrl(url)
        return cleanShown.substringAfterLast("/").ifBlank { cleanShown }
    }

    fun getRepoPageUrl(repoUrl: String, meta: ExtensionRepoMeta? = null): String? {
        val m = meta ?: getCachedMeta(repoUrl)
        val gitPage = m?.githubUrl ?: extractGitRepoPage(repoUrl)
        if (gitPage != null) return gitPage

        val web = m?.website?.trim()
        if (!web.isNullOrBlank() && !web.endsWith(".json", ignoreCase = true) && !web.endsWith(".pb", ignoreCase = true)) {
            return web
        }
        return null
    }

    fun getRepoBadgeName(repoUrl: String, meta: ExtensionRepoMeta? = null): String {
        val m = meta ?: getCachedMeta(repoUrl)
        val badge = m?.shortName?.takeIf { it.isNotBlank() }
            ?: m?.name?.takeIf { it.isNotBlank() }
            ?: extractBadgeNameFromUrl(repoUrl)
        return badge.trim()
    }

    /**
     * Review-HOLD correction (Finding 1): never misrepresent a URL's host.
     * Only GitHub project pages use the GitHub icon; GitLab, Codeberg,
     * Forgejo and generic websites use the generic globe icon (no brand
     * icons for those hosts exist in the app, and no new dependency is
     * warranted). Pure and unit-tested.
     */
    enum class RepoLinkKind { GITHUB, GENERIC_WEB }

    fun classifyRepoPageUrl(pageUrl: String?): RepoLinkKind {
        // Pure string parsing (no android.net.Uri) so this stays JVM-testable.
        // raw.githubusercontent.com is GitHub file infrastructure and may
        // reach the classifier unresolved, so it counts as GitHub too.
        val host = pageUrl?.trim()
            ?.removePrefix("https://")?.removePrefix("http://")
            ?.substringBefore("/")?.substringBefore("?")?.substringBefore(":")
            ?.substringAfterLast("@")?.lowercase()
            ?: return RepoLinkKind.GENERIC_WEB
        return if (host == "github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com"
        ) {
            RepoLinkKind.GITHUB
        } else {
            RepoLinkKind.GENERIC_WEB
        }
    }

    fun saveMeta(
        repoUrl: String,
        name: String = "",
        shortName: String? = null,
        website: String = "",
        discord: String? = null
    ): ExtensionRepoMeta {
        ensureLoaded()
        val key = cleanRepoKey(repoUrl)
        val githubUrl = extractGitRepoPage(repoUrl) ?: extractGitRepoPage(website)
        val existing = metaCache[key]
        val meta = ExtensionRepoMeta(
            url = repoUrl,
            name = name.ifBlank { existing?.name.orEmpty() },
            shortName = shortName?.takeIf { it.isNotBlank() } ?: existing?.shortName,
            website = website.ifBlank { existing?.website.orEmpty() },
            discord = discord ?: existing?.discord,
            githubUrl = githubUrl ?: existing?.githubUrl
        )
        metaCache[key] = meta
        persistMetaCache()
        return meta
    }

    fun saveInstalledExtensionRepo(pkgName: String, repoUrl: String, repoName: String? = null) {
        ensureLoaded()
        val name = repoName?.takeIf { it.isNotBlank() } ?: getRepoBadgeName(repoUrl)
        installedRepoMap[pkgName] = repoUrl to name
        persistInstalledRepos()
    }

    /**
     * Final correction (review HOLD round 2, Finding 3): origin-preferring
     * selection shared by all three extension managers — with a hard
     * isolation rule. Unknown (legacy) origin keeps the first pkg match.
     * KNOWN origin selects the matching origin ONLY and returns null when
     * it is absent: treating another repo as the update source without an
     * explicit install would violate multi-repository isolation.
     *
     * Repository identity is canonicalized with [cleanRepoKey] (strips index
     * filenames/trailing slashes) consistently for stored origins and
     * available entries, so harmless representation differences match while
     * genuinely different repositories never merge (no case folding, no host
     * aliasing).
     */
    fun <T> selectOriginEntry(
        entries: List<T>,
        pkgName: String,
        origin: String?,
        pkgOf: (T) -> String,
        repoOf: (T) -> String?,
    ): T? {
        if (origin == null) {
            return entries.firstOrNull { pkgOf(it) == pkgName }
        }
        val want = cleanRepoKey(origin)
        return entries.firstOrNull { e ->
            pkgOf(e) == pkgName && repoOf(e)?.let { cleanRepoKey(it) } == want
        }
    }

    fun installedOrigin(pkgName: String, fieldRepo: String?): String? {
        return fieldRepo ?: getInstalledRepo(pkgName)
    }

    /**
     * Final provenance correction (review HOLD round 2): generation-scoped
     * provisional overlay.
     *
     * Why provisional: the package may appear via ANY installer path
     * (legacy activity, package-installer session, sideload + later scan)
     * before any success callback reaches the manager, and the loader reads
     * provenance at package-scan time — so the mapping must exist up front.
     *
     * Why overlay + generations instead of compare-and-restore on the
     * persisted map: overlapping attempts (A begins, B begins, A cancels,
     * B fails) can otherwise resurrect the dead attempt A. Here the
     * persisted map holds ONLY committed origins; the in-flight attempt
     * lives in a memory-only single slot tagged with a generation. A
     * rollback/commit applies only when its generation still owns the slot,
     * so stale attempts can neither restore nor overwrite. This mirrors the
     * installer itself, which single-flights one active download per
     * package (`activeDownloads[pkgName]` replaces the previous download).
     *
     * Process death: the provisional slot is memory-only, so a dead
     * in-flight install leaves no false mapping; only committed origins
     * survive restarts (see boundary analysis in the review package).
     */
    private data class ProvisionalTx(
        val generation: Long,
        val repoUrl: String,
        val repoName: String?,
    )

    private val provenanceGenerations = java.util.concurrent.atomic.AtomicLong(0)
    private val provisionalProvenance = java.util.concurrent.ConcurrentHashMap<String, ProvisionalTx>()

    fun beginInstallProvenance(
        pkgName: String,
        repoUrl: String,
        repoName: String? = null
    ): Long {
        ensureLoaded()
        val name = repoName?.takeIf { it.isNotBlank() } ?: getRepoBadgeName(repoUrl)
        val generation = provenanceGenerations.incrementAndGet()
        provisionalProvenance[pkgName] = ProvisionalTx(generation, repoUrl, name)
        return generation
    }

    /** Returns true when this generation owned the slot and committed. */
    fun commitInstallProvenance(pkgName: String, generation: Long): Boolean {
        ensureLoaded()
        val tx = provisionalProvenance[pkgName] ?: return false
        if (tx.generation != generation) return false
        if (!provisionalProvenance.remove(pkgName, tx)) return false
        installedRepoMap[pkgName] = tx.repoUrl to tx.repoName
        persistInstalledRepos()
        return true
    }

    /** Returns true when this generation owned the slot and was dropped. */
    fun rollbackInstallProvenance(pkgName: String, generation: Long): Boolean {
        ensureLoaded()
        val tx = provisionalProvenance[pkgName] ?: return false
        if (tx.generation != generation) return false
        return provisionalProvenance.remove(pkgName, tx)
    }

    /**
     * Wraps an installer source factory so failed/cancelled installs cannot
     * leave false installed-repo provenance, while successful installs
     * commit the provisional mapping the loader already observed.
     * Fully subscription-scoped via [rx.Observable.defer]: merely obtaining
     * the Observable changes nothing; every subscription runs exactly one
     * transaction with exactly-once disposal. Cancellation surfaces as
     * stream disposal without a terminal step, hence the unsubscribe guard
     * keyed on whether [InstallStep.Installed] was observed. A source
     * factory (not a prebuilt Observable) keeps provenance setup and
     * installer creation in order — matching `downloadAndInstall`, which is
     * itself `defer`-cold (enqueue happens per subscription).
     */
    fun trackInstallProvenance(
        pkgName: String,
        repoUrl: String,
        repoName: String? = null,
        sourceFactory: () -> rx.Observable<eu.kanade.tachiyomi.extension.InstallStep>
    ): rx.Observable<eu.kanade.tachiyomi.extension.InstallStep> {
        return rx.Observable.defer {
            val generation = beginInstallProvenance(pkgName, repoUrl, repoName)
            val installed = java.util.concurrent.atomic.AtomicBoolean(false)
            // Final fix (review round 3): a synchronously throwing factory
            // must not leak the provisional slot — the lifecycle operators
            // below would never attach. Convert to a stream error instead.
            val source = try {
                sourceFactory()
            } catch (t: Throwable) {
                rollbackInstallProvenance(pkgName, generation)
                return@defer rx.Observable.error<eu.kanade.tachiyomi.extension.InstallStep>(t)
            }
            source
                .doOnNext { step ->
                    if (step == eu.kanade.tachiyomi.extension.InstallStep.Installed) {
                        installed.set(true)
                        commitInstallProvenance(pkgName, generation)
                    } else if (step == eu.kanade.tachiyomi.extension.InstallStep.Error) {
                        rollbackInstallProvenance(pkgName, generation)
                    }
                }
                .doOnError { rollbackInstallProvenance(pkgName, generation) }
                .doOnUnsubscribe { if (!installed.get()) rollbackInstallProvenance(pkgName, generation) }
        }
    }

    fun getCachedMeta(repoUrl: String): ExtensionRepoMeta? {
        ensureLoaded()
        val key = cleanRepoKey(repoUrl)
        return metaCache[key]
    }

    fun getInstalledExtensionRepo(pkgName: String): Pair<String, String?>? {
        ensureLoaded()
        // In-flight provisional attempt wins while it owns the slot; the
        // persisted map holds only committed origins.
        provisionalProvenance[pkgName]?.let { return it.repoUrl to it.repoName }
        return installedRepoMap[pkgName]
    }

    fun getInstalledRepo(pkgName: String): String? {
        ensureLoaded()
        provisionalProvenance[pkgName]?.let { return it.repoUrl }
        return installedRepoMap[pkgName]?.first
    }

    fun getInstalledRepoName(pkgName: String): String? {
        ensureLoaded()
        provisionalProvenance[pkgName]?.let { return it.repoName }
        return installedRepoMap[pkgName]?.second
    }

    suspend fun getMeta(repoUrl: String): ExtensionRepoMeta = withContext(Dispatchers.IO) {
        ensureLoaded()
        val key = cleanRepoKey(repoUrl)
        val cached = metaCache[key]
        if (cached != null && (cached.name.isNotBlank() || cached.shortName != null)) {
            return@withContext cached
        }

        val client = getClient()
        val candidates = mutableListOf<String>()

        if (repoUrl.endsWith("/repo.json")) {
            candidates.add(repoUrl)
        } else {
            val base = repoUrl.substringBeforeLast("/")
            candidates.add("$base/repo.json")
            if (!candidates.contains(repoUrl)) {
                candidates.add(repoUrl)
            }
        }

        var fetchedMeta: ExtensionRepoMeta? = null

        for (targetUrl in candidates) {
            try {
                val response = client.newCall(GET(targetUrl)).awaitSuccess()
                val rawBytes = response.body.bytes()
                if (rawBytes.isEmpty()) continue
                val responseBytes = rawBytes.decompressIfGzipped()
                if (responseBytes.isEmpty()) continue
                val firstByte = responseBytes[0]

                if (firstByte == 0x7B.toByte()) { // '{'
                    val bodyString = responseBytes.toString(Charsets.UTF_8)
                    val store = runCatching {
                        json.decodeFromString<NetworkExtensionStore>(bodyString)
                    }.getOrNull()

                    val legacyRepo = runCatching {
                        json.decodeFromString<NetworkLegacyExtensionRepo>(bodyString)
                    }.getOrNull()

                    val name = store?.name?.takeIf { it.isNotBlank() }
                        ?: legacyRepo?.meta?.name?.takeIf { it.isNotBlank() }
                        ?: ""
                    val shortName = store?.badgeLabel?.takeIf { it.isNotBlank() }
                        ?: legacyRepo?.meta?.shortName?.takeIf { it.isNotBlank() }
                    val website = store?.contact?.website?.takeIf { it.isNotBlank() }
                        ?: legacyRepo?.meta?.website?.takeIf { it.isNotBlank() }
                        ?: ""
                    val discord = store?.contact?.discord?.takeIf { it.isNotBlank() }

                    if (name.isNotBlank() || !shortName.isNullOrBlank() || website.isNotBlank() || discord != null) {
                        fetchedMeta = saveMeta(
                            repoUrl = repoUrl,
                            name = name,
                            shortName = shortName,
                            website = website,
                            discord = discord
                        )
                        break
                    }
                } else if (firstByte != 0x5B.toByte()) { // Protobuf
                    val metaOnly = runCatching {
                        ProtoBuf.decodeFromByteArray<NetworkExtensionStoreMetaOnly>(responseBytes)
                    }.getOrNull()

                    if (metaOnly != null && (metaOnly.name.isNotBlank() || metaOnly.badgeLabel.isNotBlank())) {
                        fetchedMeta = saveMeta(
                            repoUrl = repoUrl,
                            name = metaOnly.name,
                            shortName = metaOnly.badgeLabel,
                            website = metaOnly.contact.website,
                            discord = metaOnly.contact.discord
                        )
                        break
                    }
                }
            } catch (_: Throwable) {
                // Continue to next candidate
            }
        }

        if (fetchedMeta != null) {
            return@withContext fetchedMeta
        }

        // Fallback to URL-derived metadata
        val fallback = saveMeta(
            repoUrl = repoUrl,
            name = cached?.name?.takeIf { it.isNotBlank() } ?: cleanShownUrl(repoUrl),
            shortName = cached?.shortName ?: extractBadgeNameFromUrl(repoUrl),
            website = cached?.website.orEmpty(),
            discord = cached?.discord
        )
        fallback
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (size < 2) return this
        val isGzip = (this[0] == 0x1f.toByte()) && (this[1] == 0x8b.toByte())
        if (!isGzip) return this
        return try {
            GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
        } catch (_: Exception) {
            this
        }
    }
}
