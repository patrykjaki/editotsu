package ani.dantotsu.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.download.DownloadCompat.Companion.removeDownloadCompat
import ani.dantotsu.download.DownloadCompat.Companion.removeMediaCompat
import ani.dantotsu.download.manga.MangaDownloadValidator
import ani.dantotsu.download.manga.MangaDownloadReconciliation
import ani.dantotsu.download.manga.MangaPhysicalDelete
import ani.dantotsu.download.manga.MangaReconcileProbe
import ani.dantotsu.download.manga.MangaSafDelete
import ani.dantotsu.download.manga.MangaStorageResolution
import ani.dantotsu.download.manga.PageFile
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.callback.FolderCallback
import com.anggrayudi.storage.file.deleteRecursively
import com.anggrayudi.storage.file.moveFolderTo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.io.Serializable
import kotlin.math.ln
import kotlin.math.pow

class DownloadsManager(private val context: Context) {
    private val gson = Gson()

    /**
     * The single serialized downloads-metadata store. ALL in-memory mutation,
     * snapshot serialization, and `DownloadsKeys` persistence flow through
     * [DownloadsMetadataStore.transact] as one atomic critical section, so
     * concurrent Manga completions and title/purge cleanups cannot lose each
     * other's persisted state; readers iterate [DownloadsMetadataStore.snapshot]
     * copies. No SAF/network work runs inside a transaction.
     */
    private val metadataStore = DownloadsMetadataStore(
        initial = loadDownloads(),
        persist = { snapshot -> PrefManager.setVal(PrefName.DownloadsKeys, gson.toJson(snapshot)) },
    )

    val mangaDownloadedTypes: List<DownloadedType>
        get() = metadataStore.snapshot().filter { it.type == MediaType.MANGA }
    val animeDownloadedTypes: List<DownloadedType>
        get() = metadataStore.snapshot().filter { it.type == MediaType.ANIME }
    val novelDownloadedTypes: List<DownloadedType>
        get() = metadataStore.snapshot().filter { it.type == MediaType.NOVEL }

    private fun loadDownloads(): List<DownloadedType> {
        val jsonString = PrefManager.getVal(PrefName.DownloadsKeys, null as String?)
        return if (jsonString != null) {
            val type = object : TypeToken<List<DownloadedType>>() {}.type
            gson.fromJson(jsonString, type)
        } else {
            emptyList()
        }
    }

    fun addDownload(downloadedType: DownloadedType) {
        metadataStore.transact { it.add(downloadedType) }
    }

    fun removeDownload(
        downloadedType: DownloadedType,
        toast: Boolean = true,
        onFinished: () -> Unit
    ) {
        // Fail-closed for manga: unguarded manga deletion races live owners (stale
        // COMPLETE resurrection). Manga destruction must travel the service
        // ownership barrier (chapter/title/purge commands), never this path.
        if (downloadedType.type == MediaType.MANGA) {
            Logger.log("removeDownload: refused for MANGA; use the service delete commands")
            return
        }
        removeDownloadCompat(context, downloadedType, toast)
        // Atomic metadata removal + persistence first; the SAF cleanup below runs
        // outside the store lock. Typed identity: an anime/novel removal must
        // never wipe a manga entry sharing title+chapter (or vice versa).
        metadataStore.transact {
            it.removeAll { sameTypedDownload(it, downloadedType.titleName, downloadedType.chapterName, downloadedType.type) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            removeDirectory(downloadedType, toast)
            withContext(Dispatchers.Main) {
                onFinished()
            }
        }
    }

    fun getSize(downloadedType: DownloadedType): Double {
        val matches: (DownloadedType) -> Boolean = {
            sameTypedDownload(it, downloadedType.titleName, downloadedType.chapterName, downloadedType.type)
        }
        val known = metadataStore.snapshot().any(matches)
        if (!known) return 0.0
        if(downloadedType.size == null) {
            val episodeSize = bytesToDouble(
                getDirSize(
                    context,
                    MediaType.ANIME,
                    downloadedType.titleName,
                    downloadedType.chapterName
                )
            )
            // Size computation (SAF) stays outside the store lock; only the
            // field write + persistence are transacted, matched by the same
            // typed predicate as before.
            metadataStore.transact { list ->
                list.firstOrNull(matches)?.size = episodeSize
            }
            return episodeSize
        }
        else
            return downloadedType.size ?: 0.0
    }

    fun removeMedia(title: String, type: MediaType) {
        // Fail-closed for manga: whole-title deletion races live owners. Manga title
        // deletion must travel the service scope barrier (ACTION_DELETE_TITLE).
        if (type == MediaType.MANGA) {
            Logger.log("removeMedia: refused for MANGA; use the service title-delete command")
            return
        }
        removeMediaCompat(context, title, type)
        val baseDirectory = getBaseDirectory(context, type)
        val directory = baseDirectory?.findFolder(title)
        if (directory?.exists() == true) {
            val deleted = directory.deleteRecursively(context, false)
            if (deleted) {
                snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
            cleanDownloads()
        }
        when (type) {
            MediaType.MANGA -> {
                metadataStore.transact { it.removeAll { it.titleName == title && it.type == MediaType.MANGA } }
            }

            MediaType.ANIME -> {
                metadataStore.transact { it.removeAll { it.titleName == title && it.type == MediaType.ANIME } }
            }

            MediaType.NOVEL -> {
                metadataStore.transact { it.removeAll { it.titleName == title && it.type == MediaType.NOVEL } }
            }
        }
    }

    private fun cleanDownloads() {
        // Fail-closed for manga: generic Anime/Novel maintenance must never
        // physically delete Manga data or prune Manga metadata outside the Manga
        // ownership + authoritative reconciliation path. There is no user-visible
        // Manga-clean command routed here, so Manga maintenance is skipped
        // entirely (see cleanDownload's own MANGA refusal).
        cleanDownload(MediaType.ANIME)
        cleanDownload(MediaType.NOVEL)
    }

    private fun cleanDownload(type: MediaType) {
        // Fail-closed: Manga maintenance travels ONLY the CP5 service scope
        // transaction + authoritative reconciliation path (title/purge commands,
        // reconcileIncompleteDownloads). A generic Anime/Novel cleanup reaching
        // here for MANGA — e.g. via removeMedia's missing-dir path — must neither
        // delete Manga folders (whose live/retry owners may be absent from
        // metadata and invisible to a lock-free check) nor prune Manga metadata
        // on lossy-absence signals.
        if (type == MediaType.MANGA) {
            Logger.log("cleanDownload: refused for MANGA; use the CP5 service scope path")
            return
        }
        // remove all folders that are not in the downloads list
        val directory = getBaseDirectory(context, type)
        val downloadsSubLists = when (type) {
            MediaType.MANGA -> mangaDownloadedTypes
            MediaType.ANIME -> animeDownloadedTypes
            else -> novelDownloadedTypes
        }
        if (directory?.exists() == true && directory.isDirectory) {
            val files = directory.listFiles()
            for (file in files) {
                if (!downloadsSubLists.any { it.titleName == file.name }) {
                    // Fence: never wipe a manga title folder with a live owner. An
                    // actively-downloading title is absent from the metadata list until
                    // COMPLETE; deleting its partial files here would corrupt it.
                    if (type == MediaType.MANGA &&
                        ani.dantotsu.download.manga.MangaServiceDataSingleton.downloadOwnership.hasLiveMangaOwner(
                            file.name ?: ""
                        )
                    ) {
                        continue
                    }
                    file.deleteRecursively(context, false)
                }
            }
        }
        //now remove all downloads that do not have a folder. The folder probes
        // (SAF) run against a snapshot outside the store lock; only the final
        // removal + persistence are transacted. The doomed decision itself is
        // the type-gated [isMaintenanceDoomed] — never an inline predicate.
        val doomed = metadataStore.snapshot().filter { download ->
            val downloadDir = directory?.findFolder(download.titleName)
            isMaintenanceDoomed(download.type, type, downloadDir?.exists(), download.titleName.isBlank())
        }
        if (doomed.isNotEmpty()) {
            val doomedSet = doomed.toSet()
            metadataStore.transact { it.removeAll(doomedSet) }
        }
    }

    fun moveDownloadsDir(
        context: Context,
        oldUri: Uri,
        newUri: Uri,
        finished: (Boolean, String) -> Unit
    ) {
        if (oldUri == newUri) {
            Logger.log("Source and destination are the same")
            finished(false, "Source and destination are the same")
            return
        }
        if (oldUri == Uri.EMPTY) {
            Logger.log("Old Uri is empty")
            finished(true, "Old Uri is empty")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val oldBase =
                    DocumentFile.fromTreeUri(context, oldUri) ?: throw Exception("Old base is null")
                val newBase =
                    DocumentFile.fromTreeUri(context, newUri) ?: throw Exception("New base is null")
                val folder =
                    oldBase.findFolder(BASE_LOCATION) ?: throw Exception("Base folder not found")
                folder.moveFolderTo(context, newBase, false, BASE_LOCATION, object :
                    FolderCallback() {
                    override fun onFailed(errorCode: ErrorCode) {
                        when (errorCode) {
                            ErrorCode.CANCELED -> finished(false, "Move canceled")
                            ErrorCode.CANNOT_CREATE_FILE_IN_TARGET -> finished(
                                false,
                                "Cannot create file in target"
                            )

                            ErrorCode.INVALID_TARGET_FOLDER -> finished(
                                true,
                                "Invalid target folder"
                            ) // seems to still work
                            ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH -> finished(
                                false,
                                "No space left on target path"
                            )

                            ErrorCode.UNKNOWN_IO_ERROR -> finished(false, "Unknown IO error")
                            ErrorCode.SOURCE_FOLDER_NOT_FOUND -> finished(
                                false,
                                "Source folder not found"
                            )

                            ErrorCode.STORAGE_PERMISSION_DENIED -> finished(
                                false,
                                "Storage permission denied"
                            )

                            ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER -> finished(
                                false,
                                "Target folder cannot have same path with source folder"
                            )

                            else -> finished(false, "Failed to move downloads: $errorCode")
                        }
                        Logger.log("Failed to move downloads: $errorCode")
                        super.onFailed(errorCode)
                    }

                    override fun onCompleted(result: Result) {
                        finished(true, "Successfully moved downloads")
                        super.onCompleted(result)
                    }

                })

            } catch (e: Exception) {
                snackString("Error: ${e.message}")
                Logger.log("Failed to move downloads: ${e.message}")
                Logger.log(e)
                Logger.log("oldUri: $oldUri, newUri: $newUri")
                finished(false, "Failed to move downloads: ${e.message}")
                return@launch
            }
        }
    }

    fun queryDownload(downloadedType: DownloadedType): Boolean {
        return metadataStore.snapshot().contains(downloadedType)
    }

    /**
     * The ONE shared exception-safe manga storage resolution boundary. Every
     * authoritative manga destructive operation and the reconciliation
     * availability check route through here: a null root and a THROWING resolver
     * (SecurityException/provider exception) both yield [Unavailable] with no
     * metadata mutation, so no coroutine exception can escape past the honest
     * failure result.
     */
    private fun resolveMangaStorageRoot(): MangaStorageResolution.Root<DocumentFile?> =
        MangaStorageResolution.resolve { getBaseDirectory(context, MediaType.MANGA) }

    /**
     * The persisted SAF tree URI for destructive manga probing. Null when no
     * download directory is configured (the storage-resolution boundary already
     * maps that to Unavailable before any probe runs).
     */
    private fun mangaProbeTreeUri(): Uri? {
        val raw = PrefManager.getVal<String>(PrefName.DownloadsDir)
        if (raw.isEmpty() || raw == Uri.EMPTY.toString()) return null
        return Uri.parse(raw)
    }

    /**
     * True when the manga SAF root resolves. When false, absence is unverifiable:
     * destructive manga operations must return Unavailable and reconciliation must
     * preserve metadata (see [chapterDeletePrecheck], [reconcileIncompleteDownloads]).
     *
     * Routes through the shared exception-safe boundary ([resolveMangaStorageRoot]),
     * so a throwing resolver (SecurityException/provider exception) counts as
     * unavailable exactly like a null root.
     */
    fun isMangaStorageAvailable(): Boolean =
        resolveMangaStorageRoot() is MangaStorageResolution.Root.Available<*>

    /**
     * Removes manga download entries whose persisted page set is authoritatively
     * verified incomplete, so the app stops trusting a stale/partial COMPLETE
     * marker. Every entry is decided through [MangaReconcileProbe] over direct
     * provider queries: only a SUCCEEDED query chain proving a missing chapter
     * or an incomplete page set prunes; any probe failure preserves (mirroring
     * the delete pre/post-probe semantics), as does an unresolvable root.
     * Entries without a known page count (legacy) and non-manga entries are
     * never touched.
     */
    fun reconcileIncompleteDownloads() {
        if (!isMangaStorageAvailable()) {
            Logger.log("reconcileIncompleteDownloads: manga SAF root unresolvable; preserving metadata")
            return
        }
        val treeUri = mangaProbeTreeUri() ?: run {
            Logger.log("reconcileIncompleteDownloads: manga SAF tree URI unresolvable; preserving metadata")
            return
        }
        val base = when (val resolution = resolveMangaStorageRoot()) {
            is MangaStorageResolution.Root.Available -> resolution.directory
            is MangaStorageResolution.Root.Unavailable -> {
                Logger.log("reconcileIncompleteDownloads: manga SAF root unresolvable; preserving metadata")
                return
            }
        } ?: run {
            Logger.log("reconcileIncompleteDownloads: manga SAF root unresolvable; preserving metadata")
            return
        }
        val provider = MangaSafDelete.systemProvider(context, treeUri)
        val baseId = DocumentsContract.getDocumentId(base.uri)
        // Unavailable counts as complete (preserve): only VerifiedIncomplete prunes.
        val toRemove = MangaDownloadReconciliation.selectReconcileTargets(
            baseAvailable = true,
            downloads = metadataStore.snapshot(),
            isComplete = { entry ->
                !MangaReconcileProbe.shouldPrune(entry.pageCount) { count ->
                    MangaReconcileProbe.statusOf(
                        query = provider,
                        baseDocumentId = baseId,
                        safeTitle = entry.titleName.findValidName(),
                        safeChapter = entry.chapterName.findValidName(),
                        pageCount = count,
                        pageLister = { chapterId ->
                            provider.listChildren(chapterId)
                                .filter { !it.isDirectory }
                                .map { PageFile(it.displayName ?: "", it.size) }
                        },
                    )
                }
            },
        )
        if (toRemove.isNotEmpty()) {
            val removal = toRemove.toSet()
            metadataStore.transact { it.removeAll(removal) }
        }
    }

    fun queryDownload(title: String, chapter: String, type: MediaType? = null): Boolean {
        return if (type == null) {
            metadataStore.snapshot().any { it.titleName == title && it.chapterName == chapter }
        } else {
            metadataStore.snapshot().any { it.titleName == title && it.chapterName == chapter && it.type == type }
        }
    }

    /**
     * Synchronous, barrier-safe destructive deletion of one manga chapter.
     *
     * This is the ONLY manga chapter delete that may run while a downloader for the
     * same physical key (sanitized title + sanitized chapter) could exist. Callers MUST
     * invoke it from inside the per-key takeover barrier
     * ([ani.dantotsu.download.manga.MangaDownloadCancellation.cancelDeleteChapterTransaction],
     * i.e. inside `MangaDownloadOwnership.cancelAndJoin`'s lock, after the old owner
     * has been cancelled+joined and old queue/job bookkeeping retired), with the
     * invocation-time reservation ([ani.dantotsu.download.manga.MangaDownloadOwnership.reserveDelete])
     * announced beforehand. When this function returns, the destructive step has fully
     * completed — there is no fire-and-forget IO. The per-key takeover lock is still
     * held at return, so no fresh same-key attempt can have published or written files
     * concurrently with this deletion.
     *
     * Ordering (physical-first): an existing chapter directory is deleted BEFORE its
     * metadata entry is removed, so a failed physical delete keeps the valid COMPLETE
     * metadata instead of discarding it and leaving orphaned files behind.
     *
     * Scope: exactly the entries/directory for the sanitized (title, chapter) pair and
     * [MediaType.MANGA]. A different title sharing the same chapter label is never
     * touched. Non-manga entries are never touched.
     *
     * @return [MangaChapterDeleteResult.Deleted] when anything was removed,
     *   [MangaChapterDeleteResult.AlreadyAbsent] when neither metadata nor directory
     *   existed, or [MangaChapterDeleteResult.Failed] when an existing required
     *   directory could not be deleted (metadata is then preserved).
     */
    suspend fun removeMangaChapterBlocking(
        title: String,
        chapter: String,
        toast: Boolean = false,
    ): MangaChapterDeleteResult = withContext(Dispatchers.IO) {
        val safeTitle = title.findValidName()
        val safeChapter = chapter.findValidName()
        // Unresolvable storage first: never claim success, never touch metadata.
        // The shared boundary also maps a THROWING resolver (SecurityException /
        // provider exception) to Unavailable, so no coroutine exception can escape
        // past the honest failure result.
        val baseDirectory = when (val resolution = resolveMangaStorageRoot()) {
            is MangaStorageResolution.Root.Available -> resolution.directory
            is MangaStorageResolution.Root.Unavailable ->
                return@withContext MangaChapterDeleteResult.Unavailable(resolution.reason)
        }
        chapterDeletePrecheck(baseAvailable = baseDirectory != null)?.let { return@withContext it }
        // Legacy external-storage location (best-effort, synchronous; never decisive).
        try {
            removeDownloadCompat(
                context,
                DownloadedType(title, chapter, MediaType.MANGA),
                false,
            )
        } catch (e: Exception) {
            Logger.log("removeMangaChapterBlocking compat delete failed: ${e.message}")
        }
        // Physical FIRST: never discard valid metadata before the required directory
        // deletion has actually succeeded. Authoritative provider-level deletion
        // ([MangaSafDelete]): pre-probe Missing → VerifiedAbsent; Present →
        // direct provider delete (throws → Unavailable); post-probe Missing →
        // Deleted, Present → Failed, throw → Unavailable. The provider's own
        // return value never decides success — only the post-probe does — and
        // `deleteRecursively` is not on this path at all.
        val base = baseDirectory
            ?: return@withContext MangaChapterDeleteResult.Unavailable(
                "manga SAF root unresolvable; absence unverifiable"
            )
        val treeUri = mangaProbeTreeUri()
            ?: return@withContext MangaChapterDeleteResult.Unavailable(
                "manga SAF root unresolvable; absence unverifiable"
            )
        val provider = MangaSafDelete.systemProvider(context, treeUri)
        val baseId = DocumentsContract.getDocumentId(base.uri)
        val target = MangaSafDelete.chapterLookup(provider, baseId, safeTitle, safeChapter)
        val physical = MangaSafDelete.runAuthoritativeDelete(
            preProbe = target,
            deleteTree = { id -> MangaSafDelete.deleteTree(provider, id) },
            postProbe = target,
        )
        when (physical) {
            is MangaPhysicalDelete.Outcome.Unavailable ->
                return@withContext MangaChapterDeleteResult.Unavailable(physical.reason)
            is MangaPhysicalDelete.Outcome.Failed -> {
                if (toast) snackString("Failed to delete directory")
                return@withContext MangaChapterDeleteResult.Failed(physical.reason)
            }
            is MangaPhysicalDelete.Outcome.VerifiedAbsent,
            is MangaPhysicalDelete.Outcome.Deleted,
            -> Unit // verified state: proceed to metadata cleanup below
        }
        val dirExisted = physical is MangaPhysicalDelete.Outcome.Deleted
        val dirDeleted = physical is MangaPhysicalDelete.Outcome.Deleted
        val metadataRemoved =
            metadataStore.transact { it.removeAll { it.titleName == safeTitle && it.chapterName == safeChapter && it.type == MediaType.MANGA } }
        if (toast) {
            if (metadataRemoved || dirDeleted) snackString("Successfully deleted")
            else snackString("Directory does not exist")
        }
        decideMangaChapterDeleteOutcome(
            metadataRemoved = metadataRemoved,
            dirExisted = dirExisted,
            dirDeleted = dirDeleted,
        )
    }

    /**
     * Synchronous, barrier-safe destructive deletion of one manga TITLE (all its
     * chapters). Same contract as [removeMangaChapterBlocking]: callers MUST invoke it
     * from inside a title-scope reservation
     * ([ani.dantotsu.download.manga.MangaDownloadOwnership.reserveMangaScope] +
     * scope transaction), which has already cancelled+joined every live owner in
     * scope. Physical-first; unresolvable storage returns [Unavailable] with metadata
     * preserved. Scope is exactly this title's manga entries/directory.
     */
    suspend fun removeMangaTitleBlocking(
        title: String,
        toast: Boolean = false,
    ): MangaChapterDeleteResult = withContext(Dispatchers.IO) {
        val safeTitle = title.findValidName()
        val baseDirectory = when (val resolution = resolveMangaStorageRoot()) {
            is MangaStorageResolution.Root.Available -> resolution.directory
            is MangaStorageResolution.Root.Unavailable ->
                return@withContext MangaChapterDeleteResult.Unavailable(resolution.reason)
        }
        chapterDeletePrecheck(baseAvailable = baseDirectory != null)?.let { return@withContext it }
        try {
            removeMediaCompat(context, title, MediaType.MANGA)
        } catch (e: Exception) {
            Logger.log("removeMangaTitleBlocking compat delete failed: ${e.message}")
        }
        // Explicit authoritative deletion (see removeMangaChapterBlocking): the
        // title probe, provider-level delete, and post-probe decide; the
        // provider's return value never does.
        val base = baseDirectory
            ?: return@withContext MangaChapterDeleteResult.Unavailable(
                "manga SAF root unresolvable; absence unverifiable"
            )
        val treeUri = mangaProbeTreeUri()
            ?: return@withContext MangaChapterDeleteResult.Unavailable(
                "manga SAF root unresolvable; absence unverifiable"
            )
        val provider = MangaSafDelete.systemProvider(context, treeUri)
        val baseId = DocumentsContract.getDocumentId(base.uri)
        val target = MangaSafDelete.titleLookup(provider, baseId, safeTitle)
        val physical = MangaSafDelete.runAuthoritativeDelete(
            preProbe = target,
            deleteTree = { id -> MangaSafDelete.deleteTree(provider, id) },
            postProbe = target,
        )
        when (physical) {
            is MangaPhysicalDelete.Outcome.Unavailable ->
                return@withContext MangaChapterDeleteResult.Unavailable(physical.reason)
            is MangaPhysicalDelete.Outcome.Failed -> {
                if (toast) snackString("Failed to delete directory")
                return@withContext MangaChapterDeleteResult.Failed(physical.reason)
            }
            is MangaPhysicalDelete.Outcome.VerifiedAbsent,
            is MangaPhysicalDelete.Outcome.Deleted,
            -> Unit // verified state: proceed to metadata cleanup below
        }
        val dirExisted = physical is MangaPhysicalDelete.Outcome.Deleted
        val dirDeleted = physical is MangaPhysicalDelete.Outcome.Deleted
        val metadataRemoved =
            metadataStore.transact { it.removeAll { it.titleName == safeTitle && it.type == MediaType.MANGA } }
        if (toast) {
            if (metadataRemoved || dirDeleted) snackString("Successfully deleted")
            else snackString("Directory does not exist")
        }
        decideMangaChapterDeleteOutcome(
            metadataRemoved = metadataRemoved,
            dirExisted = dirExisted,
            dirDeleted = dirDeleted,
        )
    }

    /**
     * Synchronous, barrier-safe purge of ALL manga downloads. Same contract as
     * [removeMangaTitleBlocking] with a whole-type scope reservation (all-manga gate),
     * after every live manga owner was cancelled+joined. Physical-first;
     * unresolvable storage returns [Unavailable] with metadata preserved.
     */
    suspend fun purgeMangaBlocking(toast: Boolean = false    ): MangaChapterDeleteResult =
        withContext(Dispatchers.IO) {
            val baseDirectory = when (val resolution = resolveMangaStorageRoot()) {
                is MangaStorageResolution.Root.Available -> resolution.directory
                is MangaStorageResolution.Root.Unavailable ->
                    return@withContext MangaChapterDeleteResult.Unavailable(resolution.reason)
            }
            chapterDeletePrecheck(baseAvailable = baseDirectory != null)?.let { return@withContext it }
            // Explicit authoritative purge (see removeMangaChapterBlocking):
            // enumerate the base's children through the provider, delete each
            // subtree at provider level, and post-verify emptiness. The base
            // directory itself is left in place; an emptied base still maps to
            // Deleted via the metadata rule below.
            val base = baseDirectory ?: error("manga SAF root vanished after precheck")
            val treeUri = mangaProbeTreeUri() ?: error("manga SAF tree URI vanished after precheck")
            val provider = MangaSafDelete.systemProvider(context, treeUri)
            val baseId = DocumentsContract.getDocumentId(base.uri)
            val purge = MangaSafDelete.runPurgeChildren(
                listChildren = { provider.listChildren(baseId) },
                deleteTree = { id -> MangaSafDelete.deleteTree(provider, id) },
            )
            val physical = purge.outcome
            when (physical) {
                is MangaPhysicalDelete.Outcome.Unavailable ->
                    return@withContext MangaChapterDeleteResult.Unavailable(physical.reason)
                is MangaPhysicalDelete.Outcome.Failed -> {
                    if (toast) snackString("Failed to delete directory")
                    return@withContext MangaChapterDeleteResult.Failed(physical.reason)
                }
                is MangaPhysicalDelete.Outcome.VerifiedAbsent,
                is MangaPhysicalDelete.Outcome.Deleted,
                -> Unit // verified state: proceed to metadata cleanup below
            }
            // hadChildren drives the honest mapping: an emptied base with removed
            // metadata is Deleted; an already-childless base with no metadata is
            // AlreadyAbsent.
            val dirExisted = purge.hadChildren
            val dirDeleted = physical is MangaPhysicalDelete.Outcome.Deleted && purge.hadChildren
            val metadataRemoved = metadataStore.transact { it.removeAll { it.type == MediaType.MANGA } }
            if (toast) {
                if (metadataRemoved || dirDeleted) snackString("Successfully deleted")
                else snackString("Directory does not exist")
            }
            decideMangaChapterDeleteOutcome(
                metadataRemoved = metadataRemoved,
                dirExisted = dirExisted,
                dirDeleted = dirDeleted,
            )
        }

    private fun removeDirectory(downloadedType: DownloadedType, toast: Boolean) {
        val baseDirectory = getBaseDirectory(context, downloadedType.type)
        val directory =
            baseDirectory?.findFolder(downloadedType.titleName)
                ?.findFolder(downloadedType.chapterName)
        metadataStore.transact { it.removeAll { sameTypedDownload(it, downloadedType.titleName, downloadedType.chapterName, downloadedType.type) } }
        // Check if the directory exists and delete it recursively
        if (directory?.exists() == true) {
            val deleted = directory.deleteRecursively(context, false)
            if (deleted) {
                if (toast) snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
        }
    }

    fun purgeDownloads(type: MediaType) {
        // Fail-closed for manga: whole-type purge races live owners. Manga purge must
        // travel the service scope barrier (ACTION_PURGE_MANGA).
        if (type == MediaType.MANGA) {
            Logger.log("purgeDownloads: refused for MANGA; use the service purge command")
            return
        }
        val directory = getBaseDirectory(context, type)
        if (directory?.exists() == true) {
            val deleted = directory.deleteRecursively(context, false)
            if (deleted) {
                snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
        }

        metadataStore.transact { it.removeAll { it.type == type } }
    }

    companion object {
        private const val BASE_LOCATION = "Dantotsu"
        private const val MANGA_SUB_LOCATION = "Manga"
        private const val ANIME_SUB_LOCATION = "Anime"
        private const val NOVEL_SUB_LOCATION = "Novel"


        /**
         * Get and create a base directory for the given type
         * @param context the context
         * @param type the type of media
         * @return the base directory
         */
        @Synchronized
        private fun getBaseDirectory(context: Context, type: MediaType): DocumentFile? {
            val baseDirectory = Uri.parse(PrefManager.getVal<String>(PrefName.DownloadsDir))
            if (baseDirectory == Uri.EMPTY) return null
            var base = DocumentFile.fromTreeUri(context, baseDirectory) ?: return null
            base = base.findOrCreateFolder(BASE_LOCATION, false) ?: return null
            return when (type) {
                MediaType.MANGA -> {
                    base.findOrCreateFolder(MANGA_SUB_LOCATION, false)
                }

                MediaType.ANIME -> {
                    base.findOrCreateFolder(ANIME_SUB_LOCATION, false)
                }

                else -> {
                    base.findOrCreateFolder(NOVEL_SUB_LOCATION, false)
                }
            }
        }

        /**
         * Get and create a subdirectory for the given type
         * @param context the context
         * @param type the type of media
         * @param title the title of the media
         * @param chapter the chapter of the media
         * @return the subdirectory
         */
        @Synchronized
        fun getSubDirectory(
            context: Context,
            type: MediaType,
            overwrite: Boolean,
            title: String,
            chapter: String? = null
        ): DocumentFile? {
            val baseDirectory = getBaseDirectory(context, type) ?: return null
            val safeTitle = title.findValidName()
            val safeChapter = chapter?.findValidName()
            return if (safeChapter != null && safeChapter.isNotEmpty()) {
                baseDirectory.findOrCreateFolder(safeTitle, false)
                    ?.findOrCreateFolder(safeChapter, overwrite)
            } else {
                baseDirectory.findOrCreateFolder(safeTitle, overwrite)
            }
        }

        fun getDirSize(
            context: Context,
            type: MediaType,
            title: String,
            chapter: String? = null
        ): Long {
            val directory = getSubDirectory(context, type, false, title, chapter) ?: return 0
            var size = 0L
            directory.listFiles().forEach {
                size += it.length()
            }
            return size
        }

        fun addNoMedia(context: Context) {
            val baseDirectory = getBaseDirectory(context) ?: return
            if (baseDirectory.findFile(".nomedia") == null) {
                baseDirectory.createFile("application/octet-stream", ".nomedia")
            }
        }

        @Synchronized
        private fun getBaseDirectory(context: Context): DocumentFile? {
            val baseDirectory = Uri.parse(PrefManager.getVal<String>(PrefName.DownloadsDir))
            if (baseDirectory == Uri.EMPTY) return null
            val base = DocumentFile.fromTreeUri(context, baseDirectory) ?: return null
            return base.findOrCreateFolder(BASE_LOCATION, false)
        }

        private val lock = Any()

        private fun DocumentFile.findOrCreateFolder(
            name: String, overwrite: Boolean
        ): DocumentFile? {
            val validName = name.findValidName()
            synchronized(lock) {
                return if (overwrite) {
                    findFolder(validName)?.delete()
                    createDirectory(validName)
                } else {
                    val folder = findFolder(validName)
                    folder ?: createDirectory(validName)
                }
            }
        }

        /**
         * Read-only legacy directory resolution (includes the compareName fuzzy
         * fallback). Used ONLY by read/write/maintenance paths — never to select
         * a destructive target (destructive selection is exact-family-only via
         * [ani.dantotsu.download.manga.MangaSafProbe.matchChild]).
         */
        private fun DocumentFile.findFolder(name: String): DocumentFile? {
            val direct = findFile(name)
            if (direct != null && direct.isDirectory) return direct
            val list = listFiles()
            return list.find { it.isDirectory && (it.name.equals(name, ignoreCase = true) || it.name?.trim().equals(name.trim(), ignoreCase = true)) }
                ?: list.find { it.isDirectory && it.name != null && name.compareName(it.name!!) }
        }

        private const val RATIO_THRESHOLD = 95
        fun Media.compareName(name: String): Boolean {
            val mainName = mainName().findValidName().lowercase()
            val ratio = FuzzySearch.ratio(mainName, name.lowercase())
            return ratio > RATIO_THRESHOLD
        }

        fun String.compareName(name: String): Boolean {
            val mainName = findValidName().lowercase()
            val compareName = name.findValidName().lowercase()
            val ratio = FuzzySearch.ratio(mainName, compareName)
            return ratio > RATIO_THRESHOLD
        }

        fun buildResumableRequest(url: String, headers: okhttp3.Headers = okhttp3.Headers.Builder().build(), existingSize: Long = 0L): okhttp3.Request {
            return okhttp3.Request.Builder()
                .url(url)
                .headers(headers)
                .apply {
                    if (existingSize > 0) {
                        header("Range", "bytes=$existingSize-")
                    }
                }
                .build()
        }
    }
}

private const val RESERVED_CHARS = "|\\?*<\":>+[]/'"
fun String?.findValidName(): String {
    return this?.replace("/", "_")?.filterNot { RESERVED_CHARS.contains(it) } ?: ""
}

/**
 * Destructive manga-chapter delete outcome. Distinguishes the acceptable terminal
 * states ([Deleted], [AlreadyAbsent]) from [Failed] and [Unavailable], so callers
 * can never report — or purge UI for — a physical deletion that did not actually
 * happen, or one whose target storage could not even be resolved.
 */
sealed interface MangaChapterDeleteResult {
    data object Deleted : MangaChapterDeleteResult
    data object AlreadyAbsent : MangaChapterDeleteResult
    data class Failed(val reason: String) : MangaChapterDeleteResult
    data class Unavailable(val reason: String) : MangaChapterDeleteResult
}

/**
 * Pure outcome mapping for [MangaChapterDeleteResult]: an existing required chapter
 * directory that could not be deleted is [Failed] (even if metadata was removed —
 * callers must therefore delete physical-first); anything removed is [Deleted];
 * neither metadata nor directory present is [AlreadyAbsent].
 */
fun decideMangaChapterDeleteOutcome(
    metadataRemoved: Boolean,
    dirExisted: Boolean,
    dirDeleted: Boolean,
): MangaChapterDeleteResult = when {
    dirExisted && !dirDeleted -> MangaChapterDeleteResult.Failed(
        "chapter directory still present after delete"
    )
    metadataRemoved || dirDeleted -> MangaChapterDeleteResult.Deleted
    else -> MangaChapterDeleteResult.AlreadyAbsent
}

/**
 * Pure storage-resolution precheck (unit-tested). When the SAF manga root cannot be
 * resolved ([baseAvailable] false — Uri.EMPTY, unresolvable tree URI, revoked
 * permission), absence is UNVERIFIABLE: returns [Unavailable] so the caller keeps
 * metadata and reports failure instead of mapping null storage to success.
 * Returns null when resolution succeeded and deletion may proceed.
 */
fun chapterDeletePrecheck(baseAvailable: Boolean): MangaChapterDeleteResult? =
    if (!baseAvailable) {
        MangaChapterDeleteResult.Unavailable("manga SAF root unresolvable; absence unverifiable")
    } else {
        null
    }

/**
 * Typed download identity: a metadata entry identifies the SAME download only
 * when title, chapter, AND media type all match. The old
 * `titleName && chapterName` predicate (without `type`) let an anime/novel
 * removal wipe a manga COMPLETE entry sharing the same title/chapter (and
 * vice versa) while the orphaned files stayed behind. Every typed metadata
 * removal/query path must use this — never the two-field form.
 */
fun sameTypedDownload(entry: DownloadedType, title: String, chapter: String, type: MediaType): Boolean =
    entry.titleName == title && entry.chapterName == chapter && entry.type == type

/**
 * Generic-cleanup doomed decision (pure, unit-tested).
 *
 * EVERY destructive branch is gated on the requested cleanup type: a blank
 * title alone never dooms a Manga entry during Anime/Novel maintenance (or any
 * cross-type combination). `dirExists == null` (no directory handle) never
 * dooms — only a verified `false` does.
 */
fun isMaintenanceDoomed(
    entryType: MediaType,
    cleanupType: MediaType,
    dirExists: Boolean?,
    titleBlank: Boolean,
): Boolean {
    if (entryType != cleanupType) return false
    return dirExists == false || titleBlank
}

data class DownloadedType(
    private val pTitle: String?,
    private val pChapter: String?,
    val type: MediaType,
    @Deprecated("use pTitle instead")
    private val title: String? = null,
    @Deprecated("use pChapter instead")
    private val chapter: String? = null,
    var size: Double? = null,
    val scanlator: String = "Unknown",
    val pageCount: Int? = null
) : Serializable {
    val titleName: String
        get() = title ?: pTitle.findValidName()
    val chapterName: String
        get() = chapter ?: pChapter.findValidName()
    val uniqueName: String
        get() = "$chapterName-${scanlator}"

    /**
     * Identity is based on the logical download (title + chapter + type + scanlator),
     * independent of mutable/derived fields such as [size] or [pageCount]. This keeps
     * lookup/removal stable even as [pageCount] is populated by CP5.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadedType) return false
        return titleName == other.titleName &&
                chapterName == other.chapterName &&
                type == other.type &&
                scanlator == other.scanlator
    }

    override fun hashCode(): Int {
        var result = titleName.hashCode()
        result = 31 * result + chapterName.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + scanlator.hashCode()
        return result
    }
}

private fun bytesToDouble(bytes: Long): Double {
    if (bytes <= 0) return 0.0
    val unit = 1000
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    return bytes / unit.toDouble().pow(exp.toDouble())
}
