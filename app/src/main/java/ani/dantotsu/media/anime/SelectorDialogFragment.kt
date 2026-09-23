package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.ui.test.performClick
//import androidx.compose.ui.geometry.isEmpty
//import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
//import androidx.glance.visibility
//import androidx.glance.visibility
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withCreated
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.torrent.TorrentBufferingDialogFragment
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.copyToClipboard
import ani.dantotsu.currActivity
import ani.dantotsu.currContext
import ani.dantotsu.databinding.BottomSheetSelectorBinding
import ani.dantotsu.databinding.ItemStreamBinding
import ani.dantotsu.databinding.ItemUrlBinding
import ani.dantotsu.databinding.ItemUrlStreamInlineBinding
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.video.Helper
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBars
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Download.download
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.media.anime.selector.SourceMetaIcon
import ani.dantotsu.media.anime.selector.SourcePillKind
import ani.dantotsu.media.anime.selector.SourceRowPill
import ani.dantotsu.media.anime.selector.SourceRowSummarizer
import ani.dantotsu.media.anime.selector.SourceRowViewModel
import ani.dantotsu.media.anime.selector.StreamRowLayoutPolicy
import ani.dantotsu.media.anime.selector.FamilyAttemptSuppression
import ani.dantotsu.media.anime.selector.FamilyPayload
import ani.dantotsu.media.anime.selector.RecoveryCoordinator
import ani.dantotsu.media.anime.selector.RecoveryResult
import ani.dantotsu.media.anime.selector.RememberedSelectionPolicy
import ani.dantotsu.media.anime.selector.SelectedFamilyStore
import ani.dantotsu.media.anime.selector.SelectedKeyStore
import ani.dantotsu.media.anime.selector.StableCandidateBuilder
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SettingsAddonActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.tryWith
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat


class SelectorDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var scope: CoroutineScope = lifecycleScope
    private var media: Media? = null
    private var episode: Episode? = null
    private var prevEpisode: String? = null
    private var makeDefault = false
    private var selected: String? = null
    /**
     * Attempt-local family auto-resolution suppression. After the
     * family resolution fires once for an episode and falls through
     * to the manual picker, subsequent `failToList` calls in the
     * same attempt will NOT re-attempt the family resolution. The
     * persisted family preference itself is left intact.
     */
    private val familyAttemptedForEpisode = FamilyAttemptSuppression()
    private var launch: Boolean? = null
    private var isDownloadMenu: Boolean? = null
    private var episodes: ArrayList<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            selected = it.getString("server")
            launch = it.getBoolean("launch", true)
            prevEpisode = it.getString("prev")
            isDownloadMenu = it.getBoolean("isDownload")
            episodes = it.getStringArrayList("episodes")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        return binding.root
    }

    interface EpisodeDownloadListener {
        fun onFinishingUserSelection(selectedServerName: String,
                                     selectedSubtitles: MutableList<String>,
                                     selectedAudioTracks: MutableList<String>)
    }
    class EpisodeDownloadHandler(private val _onFinishingUserSelection: (String, MutableList<String>, MutableList<String>) -> Unit)
        : EpisodeDownloadListener{
        override fun onFinishingUserSelection(selectedServerName: String,
                                              selectedSubtitles: MutableList<String>,
                                              selectedAudioTracks: MutableList<String>) {
            _onFinishingUserSelection(selectedServerName, selectedSubtitles, selectedAudioTracks)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        var loaded = false
        model.getMedia().observe(viewLifecycleOwner) { m ->
            media = m
            if (media != null && !loaded) {
                loaded = true

                // Initialize the REAL remembered-choice preference
                // BEFORE any remembered auto-selection logic can
                // read it. The remembered selected-server auto path
                // below runs before initializeVideoServerSelector()
                // (which also refreshes/binds this same preference
                // for the manual picker UI), so without this the
                // auto path would always see the field default
                // `false` even when the setting is actually ON.
                makeDefault = PrefManager.getVal(PrefName.MakeDefault)

                fun fail(resId: Int){
                    ContextCompat.getMainExecutor(context ?: currContext() ?: return).execute {
                        snackString(getString(resId))
                        tryWith {
                            dismissAllowingStateLoss()
                        }
                    }
                }
                fun initializeVideoServerSelector(ep: Episode, onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) {
                    binding.selectorRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = navBarHeight
                    }
                    binding.selectorRecyclerView.adapter = null
                    binding.selectorProgressBar.visibility = View.VISIBLE
                    makeDefault = PrefManager.getVal(PrefName.MakeDefault)
                    binding.selectorMakeDefault.isChecked = makeDefault
                    binding.selectorMakeDefault.setOnClickListener {
                        makeDefault = binding.selectorMakeDefault.isChecked
                        PrefManager.setVal(PrefName.MakeDefault, makeDefault)
                    }
                    binding.selectorRecyclerView.layoutManager =
                        LinearLayoutManager(
                            requireActivity(),
                            LinearLayoutManager.VERTICAL,
                            false
                        )
                    val adapter = ExtractorAdapter(onEpisodeDownloadHandler)
                    binding.selectorRecyclerView.adapter = adapter
                    if (!ep.allStreams) {
                        ep.extractorCallback = { extractor ->
                            scope.launch(Dispatchers.Main) {
                                if (_binding == null || !isAdded) return@launch
                                adapter.add(extractor)
                                binding.selectorProgressBar.visibility = View.GONE
                            }
                        }
                        scope.launch(Dispatchers.IO) {
                            model.loadEpisodeVideos(ep, media!!.selected!!.sourceIndex)
                            withContext(Dispatchers.Main) {
                                if (_binding == null || !isAdded) return@withContext
                                binding.selectorProgressBar.visibility = View.GONE
                                if (adapter.itemCount == 0) {
                                    fail(R.string.stream_selection_empty)
                                }
                                if (model.watchSources!!.isDownloadedSource(media?.selected!!.sourceIndex)) {
                                    adapter.performClick(0)
                                }
                            }
                        }
                    } else {
                        val epKey = media?.anime?.episodes?.getEpisodeKey(ep.number) ?: media?.anime?.selectedEpisode
                        if (epKey != null) {
                            media!!.anime?.episodes?.set(epKey, ep)
                        }
                        adapter.addAll(ep.extractors)
                        if (ep.extractors?.size == 0) {
                            fail(R.string.stream_selection_empty)
                        }
                        if (model.watchSources!!.isDownloadedSource(media?.selected!!.sourceIndex)) {
                            adapter.performClick(0)
                        }
                        binding.selectorProgressBar.visibility = View.GONE
                    }
                }
                suspend fun loadEpisodeSingleServer(episodeName: String, selectedServerName: String): Boolean{
                    val ep = media?.anime?.episodes?.getEpisode(episodeName) ?: media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode)
                    if (ep == null) return false
                    episode = ep

                    var success = false
                    scope.launch(Dispatchers.IO) {
                        success = model.loadEpisodeSingleVideo(
                            ep,
                            media!!.selected!!,
                            selectedServerName = selectedServerName
                        )
                    }.join()
                    Log.d("AnimeDownloader", "Loading Episode Server State: $success")
                    return success
                }
                fun startEpisodeDownload(episodeName: String, selectedServerName: String,
                                         selectedSubtitles: MutableList<String>,
                                         selectedAudioTracks: MutableList<String>){
                    fun downloadUsingSingleServer(extractor: VideoExtractor, currentEp: Episode): Boolean {
                        currentEp.selectedExtractor = extractor.server.name
                        if (currentEp.selectedVideo >= extractor.videos.size) {
                            currentEp.selectedVideo = 0
                        }
                        val epKey = media?.anime?.episodes?.getEpisodeKey(currentEp.number) ?: currentEp.number
                        media?.anime?.episodes?.get(epKey)?.let { mapEp ->
                            mapEp.selectedExtractor = extractor.server.name
                            mapEp.selectedVideo = currentEp.selectedVideo
                        }
                        if ((PrefManager.getVal(PrefName.DownloadManager) as Int) != 0) {
                            val act = activity ?: currActivity()
                            if (act != null) {
                                download(
                                    act,
                                    currentEp,
                                    media!!.userPreferredName
                                )
                            }
                        }
                        else {
                            val downloadAddonManager: DownloadAddonManager = Injekt.get()
                            if (!downloadAddonManager.isAvailable()) {
                                val context = context ?: currContext()
                                context?.customAlertDialog()?.apply {
                                    setTitle(R.string.download_addon_not_installed)
                                    setMessage(R.string.would_you_like_to_install)
                                    setPosButton(R.string.yes) {
                                        context.startActivity(
                                            Intent(context, SettingsAddonActivity::class.java)
                                        )
                                    }
                                    setNegButton(R.string.no) { }
                                    show()
                                }
                                return false
                            }
                            val subtitles = extractor.subtitles
                            val subtitlesToDownload: MutableList<Pair<String, String>> = mutableListOf()
                            subtitles.forEach {
                                if(it.language in selectedSubtitles || selectedSubtitles.isEmpty()){
                                    subtitlesToDownload.add(Pair(it.file.url, it.language))
                                }
                            }

                            val audioTracks = extractor.audioTracks
                            val audioTracksToDownload: MutableList<Pair<String, String>> = mutableListOf()
                            audioTracks.forEach {
                                if(it.lang in selectedAudioTracks || selectedAudioTracks.isEmpty()){
                                    audioTracksToDownload.add(Pair(it.url, it.lang))
                                }
                            }

                            val selectedVideo =
                                if (extractor.videos.isNotEmpty()) {
                                    if (currentEp.selectedVideo in extractor.videos.indices) extractor.videos[currentEp.selectedVideo] else extractor.videos[0]
                                } else null
                            val activity = activity ?: currActivity()
                            selectedVideo?.file?.url?.let { url ->
                                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                                    val torrentManager = Injekt.get<TorrentServerManager>()
                                    if (!torrentManager.isAvailable()) {
                                        toast(R.string.torrent_addon_not_available)
                                        return false
                                    }
                                    runBlocking {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                torrentManager.activeTorrentHash?.let {
                                                    torrentManager.removeTorrent(it)
                                                }
                                                val index = if (url.contains("index=")) {
                                                    url.substringAfter("index=")
                                                        .toIntOrNull() ?: 0
                                                } else 0
                                                Logger.log("Sending: ${url}, ${selectedVideo.quality}, $index")
                                                val currentTorrent = torrentManager.addTorrent(
                                                    url,
                                                    selectedVideo.quality.toString(),
                                                    "",
                                                    "",
                                                    false
                                                )
                                                torrentManager.activeTorrentHash =
                                                    currentTorrent.hash

                                                // Pre-buffer the first piece and release temporary check lease
                                                val prebufferResult = torrentManager.prebufferWithResult(currentTorrent.hash!!, index)
                                                prebufferResult.lease?.close()

                                                selectedVideo.file.url =
                                                    torrentManager.getLink(currentTorrent, index)
                                                Logger.log("Received: ${selectedVideo.file.url}")
                                            }

                                        } catch (e: Exception) {
                                            Injekt.get<CrashlyticsInterface>()
                                                .logException(e)
                                            Logger.log(e)
                                            toast("Error starting video: ${e.message}")
                                            return@runBlocking
                                        }
                                    }
                                }
                            }
                            val act = activity ?: currActivity()
                            if (selectedVideo != null && act != null) {
                                Helper.startAnimeDownloadService(
                                    act,
                                    media!!.mainName(),
                                    currentEp.number,
                                    selectedVideo,
                                    subtitlesToDownload,
                                    audioTracksToDownload,
                                    media,
                                    currentEp.thumb?.url ?: media!!.banner
                                    ?: media!!.cover
                                )
                                val intent =
                                    Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
                                        putExtra(
                                            AnimeWatchFragment.EXTRA_EPISODE_NUMBER,
                                            currentEp.number,
                                        )
                                        putExtra("mediaId", media?.id)
                                    }
                                act.sendBroadcast(intent)
                            } else if (selectedVideo == null) {
                                snackString(R.string.no_video_selected)
                            }
                        }
                        return true
                    }

                    val ep = media?.anime?.episodes?.getEpisode(episodeName) ?: media?.anime?.episodes?.get(episodeName)
                    if (ep == null) {
                        fail(R.string.auto_select_server_error)
                        return
                    }
                    val epKey = media?.anime?.episodes?.getEpisodeKey(episodeName) ?: episodeName
                    media?.anime?.selectedEpisode = epKey
                    episode = ep

                    Log.d("AnimeDownloader", "Downloading Episode: ${ep.number}, server: $selectedServerName")

                    val selectedExtractor = ep.extractors?.find { it.server.name == selectedServerName }
                    if (selectedExtractor == null)
                        fail(R.string.auto_select_server_error)
                    else {
                        media!!.anime?.episodes?.set(epKey, ep)
                        if(!downloadUsingSingleServer(selectedExtractor, ep))
                            fail(R.string.auto_select_server_error)
                    }
                }

                Log.d("AnimeDownloader", "Selected Server for watching: $selected")
                if(episodes.isNullOrEmpty()){
                    fail(R.string.empty_episodes_list)
                }
                if (isDownloadMenu == false) {
                    val rawKey = episodes?.get(0)
                    val ep = media?.anime?.episodes?.getEpisode(rawKey)
                    val actualKey = media?.anime?.episodes?.getEpisodeKey(rawKey) ?: rawKey
                    media?.anime?.selectedEpisode = actualKey
                    episode = ep
                    if (ep != null) {
                        if (selected != null && media?.format != "LOCAL") {
                            binding.selectorListContainer.visibility = View.GONE
                            binding.selectorAutoListContainer.visibility = View.VISIBLE
                            binding.selectorAutoText.text = selected
                            binding.selectorCancel.setOnClickListener {
                                media!!.selected!!.server = null
                                model.saveSelected(media!!.id, media!!.selected!!)
                                SelectedKeyStore.save(media!!.id, null)
                                tryWith {
                                    dismissAllowingStateLoss()
                                }
                            }

                            fun failToList() {
                                // Recovery after a failed auto-list path:
                                //
                                // 1. If a stored exact key OR a stored family
                                //    preference is present, load all extractors
                                //    and run `RecoveryCoordinator.resolve`.
                                // 2. If a family auto-resolve was already
                                //    attempted for THIS episode in THIS dialog
                                //    attempt, do NOT attempt it again here. The
                                //    persisted family preference is left intact.
                                // 3. On Resolved, drive the auto-list with the
                                //    resolved candidate (write SelectedKey,
                                //    Selected.server, selectedExtractor).
                                // 4. On NoMatch / Ambiguous / suppressed,
                                //    clear Selected.server + SelectedKey,
                                //    KEEP family, fall back to manual picker.
                                val storedKey = SelectedKeyStore.load(media!!.id)
                                val storedFamily = SelectedFamilyStore.load(media!!.id)
                                if ((storedKey != null || storedFamily != null)
                                    && familyAttemptedForEpisode.shouldAttempt(actualKey)
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        if (!ep.allStreams) {
                                            model.loadEpisodeVideos(
                                                ep, media!!.selected!!.sourceIndex,
                                            )
                                        }
                                        withContext(Dispatchers.Main) {
                                            val servers = ep.extractors?.map { it.server }
                                                ?: emptyList()
                                            val recovery = RecoveryCoordinator.resolve(
                                                storedExactKey = storedKey,
                                                storedFamily = storedFamily,
                                                selectedName = null,
                                                servers = servers,
                                            )
                                            if (recovery is RecoveryResult.Resolved) {
                                                val chosen = recovery.server.name
                                                // One coherent auto-recovery
                                                // transaction. The runtime handle
                                                // is ALWAYS applied below.
                                                // Remembered persistence follows
                                                // the real MakeDefault setting
                                                // (loaded before the auto branch):
                                                // ON persists the legacy server
                                                // choice and WRITEs/CLEARs the
                                                // exact slot; OFF persists
                                                // nothing new and leaves the
                                                // family preference untouched.
                                                val autoPersistence =
                                                    RememberedSelectionPolicy.autoRecoveryPersistence(
                                                        makeDefault, recovery.exactKey,
                                                    )
                                                if (autoPersistence.persistLegacyServer) {
                                                    media!!.selected!!.server = chosen
                                                    media!!.selected!!.video = 0
                                                    model.saveSelected(
                                                        media!!.id, media!!.selected!!,
                                                    )
                                                }
                                                when (autoPersistence.exactAction) {
                                                    RememberedSelectionPolicy.ExactSlotAction.WRITE ->
                                                        SelectedKeyStore.save(
                                                            media!!.id, recovery.exactKey,
                                                        )
                                                    RememberedSelectionPolicy.ExactSlotAction.CLEAR ->
                                                        SelectedKeyStore.save(media!!.id, null)
                                                    RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE -> Unit
                                                }
                                                media!!.anime!!.episodes
                                                    ?.getEpisode(actualKey)?.selectedExtractor = chosen
                                                media!!.anime!!.episodes
                                                    ?.getEpisode(actualKey)?.selectedVideo = 0
                                                startExoplayer(media!!)
                                            } else {
                                                snackString(getString(R.string.auto_select_server_error))
                                                media!!.selected!!.server = null
                                                model.saveSelected(
                                                    media!!.id, media!!.selected!!,
                                                )
                                                SelectedKeyStore.save(media!!.id, null)
                                                binding.selectorAutoListContainer.visibility = View.GONE
                                                binding.selectorListContainer.visibility = View.VISIBLE
                                                initializeVideoServerSelector(ep)
                                            }
                                        }
                                    }
                                } else {
                                    snackString(getString(R.string.auto_select_server_error))
                                    media!!.selected!!.server = null
                                    model.saveSelected(media!!.id, media!!.selected!!)
                                    SelectedKeyStore.save(media!!.id, null)
                                    binding.selectorAutoListContainer.visibility = View.GONE
                                    binding.selectorListContainer.visibility = View.VISIBLE
                                    initializeVideoServerSelector(ep)
                                }
                            }

                            fun load() {
                                val size =
                                    if (model.watchSources!!.isDownloadedSource(media!!.selected!!.sourceIndex)) {
                                        ep.extractors?.firstOrNull()?.videos?.size
                                    } else {
                                        ep.extractors?.find { it.server.name == selected }?.videos?.size
                                    }

                                if (size != null && size >= media!!.selected!!.video) {
                                    val currentKey = media!!.anime!!.selectedEpisode ?: actualKey
                                    media!!.anime!!.episodes?.getEpisode(currentKey)?.selectedExtractor = selected
                                    media!!.anime!!.episodes?.getEpisode(currentKey)?.selectedVideo = media!!.selected!!.video
                                    if (makeDefault) {
                                        val chosen = ep.extractors?.find { it.server.name == selected }
                                        if (chosen != null) {
                                            val provider = StableCandidateBuilder.providerOf(chosen.server)
                                            val exact = StableCandidateBuilder.exactKey(provider, chosen.server)
                                            SelectedKeyStore.save(media!!.id, exact)
                                        }
                                    }
                                    startExoplayer(media!!)
                                } else failToList()
                            }

                            if (ep.extractors?.filter { it.server.name == selected } == null) {
                                scope.launch{
                                    val success = withContext(Dispatchers.IO){
                                        loadEpisodeSingleServer(ep.number, selected!!)
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (_binding == null || !isAdded) return@withContext
                                        if (!success) {
                                            failToList()
                                        } else {
                                            load()
                                        }
                                    }
                                }
                            } else load()
                        }
                        else
                            initializeVideoServerSelector(ep)
                    }
                }
                else {
                    binding.selectorMakeDefault.visibility = View.GONE
                    val rawKey = episodes?.get(0)
                    val ep = media?.anime?.episodes?.getEpisode(rawKey)
                    val actualKey = media?.anime?.episodes?.getEpisodeKey(rawKey) ?: rawKey
                    media?.anime?.selectedEpisode = actualKey
                    episode = ep

                    if (ep != null) {
                        val downloadHandler =
                            EpisodeDownloadHandler(_onFinishingUserSelection = { selectedServerName,
                                                                                 selectedSubtitles,
                                                                                 selectedAudioTracks ->
                                binding.selectorListContainer.visibility = View.GONE
                                binding.selectorAutoListContainer.visibility = View.VISIBLE
                                binding.selectorTitle.text = "Starting Download"
                                binding.selectorAutoText.text =
                                    "Starting download using server:\n$selectedServerName"
                                binding.selectorCancel.visibility = View.GONE

                                scope.launch(Dispatchers.IO) {
                                    val currentEpisodes = episodes ?: return@launch
                                    val serverSelectionScope = CoroutineScope(Dispatchers.IO)
                                    val serverSelectionTasks = mutableListOf<Deferred<Unit>>()
                                    for (episodeName in currentEpisodes.drop(1)) {
                                        serverSelectionTasks.add(serverSelectionScope.async {
                                            if(!loadEpisodeSingleServer(episodeName, selectedServerName)){
                                                Log.d("AnimeDownloader", "Error loading server $selectedServerName for episode $episodeName")
                                                fail(R.string.auto_select_server_error)
                                            }
                                        })
                                    }
                                    serverSelectionTasks.awaitAll()

                                    for(episodeName in currentEpisodes){
                                        startEpisodeDownload(episodeName, selectedServerName, selectedSubtitles, selectedAudioTracks)
                                    }
                                    withContext(Dispatchers.Main) {
                                        tryWith{
                                            dismissAllowingStateLoss()
                                        }
                                    }
                                }
                            })
                        initializeVideoServerSelector(ep, downloadHandler)
                    }
                }
            }
        }
        super.onViewCreated(view, savedInstanceState)
    }

    private val externalPlayerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        Logger.log(result.data.toString())
    }

    private fun exportMagnetIntent(episode: Episode, video: Video): Intent {
        val amnis = "com.amnis"
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(amnis, "$amnis.gui.player.PlayerActivity")
            data = Uri.parse(video.file.url)
            putExtra("title", "${media?.name} - ${episode.title}")
            putExtra("position", 0)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("secure_uri", true)
            val headersArray = arrayOf<String>()
            video.file.headers.forEach {
                headersArray.plus(arrayOf(it.key, it.value))
            }
            putExtra("headers", headersArray)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("UnsafeOptInUsageError")
    fun startExoplayer(media: Media) {
        if (!isAdded || _binding == null) return
        prevEpisode = null

        episode?.let { ep ->
            val video = ep.extractors?.find {
                it.server.name == ep.selectedExtractor
            }?.videos?.getOrNull(ep.selectedVideo)
            video?.file?.url?.let { url ->
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    val torrentManager = Injekt.get<TorrentServerManager>()
                    if (torrentManager.isAvailable()) {
                        val act = activity ?: currActivity()
                        dismissAllowingStateLoss()
                        if (launch == true) {
                            stopAddingToList()
                            val epKey = media.anime?.selectedEpisode
                            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: ep
                            model.setEpisode(targetEp, "startExo launch")

                            val index = if (url.contains("index=")) {
                                url.substringAfter("index=").toIntOrNull() ?: 0
                            } else 0

                            val hash = try {
                                if (url.startsWith("magnet:")) torrentManager.parseMagnetHash(url) else ""
                            } catch (_: Exception) { "" }

                            // Trigger immediate background pre-buffering on stream selection
                            torrentManager.prebufferTorrent(url, index, video.quality.toString())

                            if (hash.isNotBlank()) {
                                val link = torrentManager.getLink(hash, index)
                                video.file.url = link
                                targetEp.extractors?.find { it.server.name == targetEp.selectedExtractor }?.videos?.getOrNull(targetEp.selectedVideo)?.file?.url = link
                            }

                            if (act != null && !act.isFinishing && !act.isDestroyed) {
                                val intent = Intent(act, ExoplayerView::class.java).apply {
                                    ExoplayerView.media = media
                                    ExoplayerView.initialized = true
                                }
                                act.startActivity(intent)
                            }
                        } else {
                            val epKey = media.anime?.selectedEpisode
                            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: ep
                            model.setEpisode(targetEp, "startExo no launch")
                        }
                        return
                    } else {
                        try {
                            externalPlayerResult.launch(exportMagnetIntent(ep, video))
                        } catch (e: ActivityNotFoundException) {
                            val amnis = "com.amnis"
                            try {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=$amnis")
                                    )
                                )
                                dismissAllowingStateLoss()
                            } catch (e: ActivityNotFoundException) {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=$amnis")
                                    )
                                )
                            }
                        }
                    }
                    return
                }
            }
        }

        dismissAllowingStateLoss()
        if (launch!!) {
            stopAddingToList()
            val intent = Intent(activity, ExoplayerView::class.java)
            ExoplayerView.media = media
            ExoplayerView.initialized = true
            startActivity(intent)
        } else {
            val epKey = media.anime?.selectedEpisode
            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: episode
            if (targetEp != null) {
                model.setEpisode(targetEp, "startExo no launch")
            }
        }
    }

    private fun stopAddingToList() {
        episode?.extractorCallback = null
        episode?.also {
            it.extractors = it.extractors?.toMutableList()
        }
    }

    private inner class ExtractorAdapter(private val onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) :
        RecyclerView.Adapter<ExtractorAdapter.StreamViewHolder>() {
        val links = mutableListOf<VideoExtractor>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemStreamBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val extractor = links.getOrNull(position) ?: return
            holder.binding.streamName.text = ""//extractor.server.name
            holder.binding.streamName.visibility = View.GONE

            holder.binding.streamRecyclerView.layoutManager = LinearLayoutManager(requireContext())
            holder.binding.streamRecyclerView.adapter = VideoAdapter(extractor, onEpisodeDownloadHandler)
        }

        override fun getItemCount(): Int = links.size

        fun add(videoExtractor: VideoExtractor) {
            if (videoExtractor.videos.isNotEmpty()) {
                val existingIndex = links.indexOfFirst { it.server.name == videoExtractor.server.name }
                if (existingIndex >= 0) {
                    links[existingIndex] = videoExtractor
                    notifyItemChanged(existingIndex)
                } else {
                    links.add(videoExtractor)
                    notifyItemInserted(links.size - 1)
                }
            }
        }

        fun addAll(extractors: List<VideoExtractor>?) {
            links.addAll(extractors ?: return)
            notifyItemRangeInserted(0, extractors.size)
        }

        fun performClick(position: Int) {
            try {
                val extractor = links[position]
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
                    currentEp.selectedExtractor = extractor.server.name
                    currentEp.selectedVideo = 0
                }
                if (epKey != null) {
                    media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                    media?.anime?.episodes?.get(epKey)?.selectedVideo = 0
                }
                startExoplayer(media!!)
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().logException(e)
            }
        }

        private inner class StreamViewHolder(val binding: ItemStreamBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private inner class VideoAdapter(private val extractor: VideoExtractor,private val onEpisodeDownloadHandler: EpisodeDownloadHandler?) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val viewTypeRelease = 0
        private val viewTypeInlineStream = 1

        override fun getItemViewType(position: Int): Int {
            // Every row of one extractor shares server.name as its
            // quality text, so the whole section resolves to one
            // layout deterministically.
            return if (StreamRowLayoutPolicy.isInlineServerName(extractor.server.name)) {
                viewTypeInlineStream
            } else {
                viewTypeRelease
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == viewTypeInlineStream) {
                InlineStreamViewHolder(
                    ItemUrlStreamInlineBinding.inflate(
                        inflater,
                        parent,
                        false
                    )
                )
            } else {
                UrlViewHolder(
                    ItemUrlBinding.inflate(
                        inflater,
                        parent,
                        false
                    )
                )
            }
        }

        /**
         * Subtitle-download dialog for one row's subtitle button. Shared
         * by the full release card and the inline stream card with
         * identical behavior; only the clicked button view differs.
         */
        private fun setupRowSubtitleButton(
            button: android.widget.ImageButton,
            subtitles: List<Subtitle>,
        ) {
            button.setOnClickListener {
                if (subtitles.isNotEmpty()) {
            val subtitleNames = subtitles.map { it.language }
            var subtitleToDownload: Subtitle? = null
            val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
            val epNumber = currentEp?.number ?: media?.anime?.selectedEpisode ?: "1"
            (activity ?: currActivity())?.customAlertDialog()?.apply {
                setTitle(R.string.download_subtitle)
                singleChoiceItems(subtitleNames.toTypedArray(),  dismissOnSelect = false) { which ->
                    subtitleToDownload = subtitles[which]
                }
                setPosButton(R.string.download) {
                    scope.launch(Dispatchers.IO) {
                        if (subtitleToDownload != null) {
                            SubtitleDownloader.downloadSubtitle(
                                context ?: currContext() ?: return@launch,
                                subtitleToDownload.file.url,
                                DownloadedType(
                                    media!!.mainName(),
                                    epNumber,
                                    MediaType.ANIME
                                )
                            )
                        }
                    }
                }
                setNegButton(R.string.cancel) {}
            }?.show()
                } else {
            snackString(R.string.no_subtitles_available)
                }
            }
        }

        /**
         * Download flow for one row's download button. Shared by the
         * full release card and the inline stream card with identical
         * behavior; only the clicked button view differs.
         */
        private fun setupRowDownloadButton(
            button: android.widget.ImageButton,
            position: Int,
            subtitles: List<Subtitle>,
        ) {
            button.setSafeOnClickListener {
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
            currentEp.selectedExtractor = extractor.server.name
            currentEp.selectedVideo = position
                }
                if (epKey != null) {
            media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
            media?.anime?.episodes?.get(epKey)?.selectedVideo = position
                }
                if ((PrefManager.getVal(PrefName.DownloadManager) as Int) != 0) {
            val act = activity ?: currActivity()
            if (act != null && currentEp != null) {
                download(
                    act,
                    currentEp,
                    media!!.userPreferredName
                )
            }
                }
                else {
            val ep = currentEp ?: return@setSafeOnClickListener
            val selectedVideo =
                if (extractor.videos.size > ep.selectedVideo) extractor.videos[ep.selectedVideo] else extractor.videos.getOrNull(0)
            val downloadAddonManager: DownloadAddonManager = Injekt.get()
            if (!downloadAddonManager.isAvailable()) {
                val context = context ?: currContext()
                context?.customAlertDialog()?.apply {
                    setTitle(R.string.download_addon_not_installed)
                    setMessage(R.string.would_you_like_to_install)
                    setPosButton(R.string.yes) {
                        ContextCompat.startActivity(
                            context,
                            Intent(context, SettingsAddonActivity::class.java),
                            null
                        )
                    }
                    setNegButton(R.string.no) {
                        return@setNegButton
                    }
                    show()
                }
                dismissAllowingStateLoss()
                return@setSafeOnClickListener
            }
            selectedVideo?.file?.url?.let { url ->
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    val torrentManager = Injekt.get<TorrentServerManager>()
                    if (!torrentManager.isAvailable()) {
                        toast(R.string.torrent_addon_not_available)
                        return@setSafeOnClickListener
                    }
                }
            }

            val subtitleNames = subtitles.map { it.language }
            var selectedSubtitles: MutableList<String> = mutableListOf()
            var selectedAudioTracks: MutableList<String> = mutableListOf()

            val currContext = currContext() ?: requireContext()

            fun go(){
                onEpisodeDownloadHandler?.onFinishingUserSelection(extractor.server.name, selectedSubtitles, selectedAudioTracks)
            }

            fun checkAudioTracks() {
                val audioTracks = extractor.audioTracks.map { it.lang }
                if (audioTracks.isNotEmpty()) {
                    val audioNamesArray = audioTracks.toTypedArray()
                    val checkedItems = BooleanArray(audioNamesArray.size) { false }

                    currContext.customAlertDialog().apply { // ToTest
                        setTitle(R.string.download_audio_tracks)
                        multiChoiceItems(audioNamesArray, checkedItems) {
                            it.forEachIndexed { index, isChecked ->
                                val audioName = extractor.audioTracks[index].lang
                                if (isChecked) {
                                    selectedAudioTracks.add(audioName)
                                } else {
                                    selectedAudioTracks.remove(audioName)
                                }
                            }
                        }
                        setPosButton(R.string.download) {
                            go()
                        }
                        setNegButton(R.string.skip) {
                            selectedAudioTracks = mutableListOf()
                            go()
                        }
                        setNeutralButton(R.string.cancel) {
                            selectedAudioTracks = mutableListOf()
                        }
                        show()
                    }
                } else {
                    go()
                }
            }
            if (subtitles.isNotEmpty()) { // ToTest
                val subtitleNamesArray = subtitleNames.toTypedArray()
                val checkedItems = BooleanArray(subtitleNamesArray.size) { index ->
                    val name = subtitleNamesArray[index]
                    val isDefaultMatch = name.contains("English", true) || name.contains("en", true) || (subtitles.size == 1)
                    if (isDefaultMatch) {
                        selectedSubtitles.add(subtitles[index].language)
                    }
                    isDefaultMatch
                }

                currContext.customAlertDialog().apply {
                    setTitle(R.string.download_subtitle)
                    multiChoiceItems(subtitleNamesArray, checkedItems) {
                        it.forEachIndexed { index, isChecked ->
                            val subtitleName = subtitles[index].language
                            if (isChecked) {
                                if (!selectedSubtitles.contains(subtitleName)) selectedSubtitles.add(subtitleName)
                            } else {
                                selectedSubtitles.remove(subtitleName)
                            }
                        }
                    }
                    setPosButton(R.string.download) {
                        checkAudioTracks()
                    }
                    setNegButton(R.string.skip) {
                        selectedSubtitles = mutableListOf()
                        checkAudioTracks()
                    }
                    setNeutralButton(R.string.cancel) {
                        selectedSubtitles = mutableListOf()
                    }
                    show()
                }
            } else {
                checkAudioTracks()
            }
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val video = extractor.videos[position]
            val subtitles = extractor.subtitles
            if (holder is InlineStreamViewHolder) {
                bindInlineStreamRow(holder.binding, video, position, subtitles)
                return
            }
            val binding = (holder as UrlViewHolder).binding
            if (isDownloadMenu == true) {
                binding.urlDownload.visibility = View.VISIBLE
            } else {
                binding.urlDownload.visibility = View.GONE
            }
            if (subtitles.isNotEmpty()) {
                binding.urlSub.visibility = View.VISIBLE
            } else {
                binding.urlSub.visibility = View.GONE
            }
            setupRowSubtitleButton(binding.urlSub, subtitles)
            setupRowDownloadButton(binding.urlDownload, position, subtitles)
            // Legacy size/format chrome is hidden for every redesigned row. The new
            // Legacy size/format chrome is hidden for every redesigned row.
            // The new compact row's metadata line is the single source of
            // size truth; transport is surfaced only in the expandable
            // Details panel. The layout no longer carries urlSize/urlNote
            // fields (replaced by the new urlMeta / urlPills row).
            bindSourceRow(holder, extractor, video)
        }

        override fun getItemCount(): Int = extractor.videos.size

        /**
         * Drive the picker row from a [SourceRowViewModel]. Pure display
         * pass; selection semantics live in [UrlViewHolder.init]. The legacy
         * `urlQuality` TextView is reused as the primary row (single-line,
         * ellipsized) so existing styling matches.
         */
        private fun bindSourceRow(
            holder: UrlViewHolder,
            extractor: VideoExtractor,
            video: Video,
        ) {
            val viewModel = SourceRowSummarizer.build(
                displayLabel = extractor.server.name,
                qualityText = extractor.server.name,
                format = video.format,
                url = video.file.url,
            )
            val binding = holder.binding

            // v3.5 hierarchy:
            // 1. Header: release group (RELEASE) or host (STREAM), large.
            // 2. Tracker cue: small right-side text, only when
            //    confidently parsed (never guess).
            // 3. Title: clean human-readable line.
            // 4. Seeders: prominent primary secondary signal.
            // 5. Size + tracker: lightweight secondary metadata.
            // 6. Technical pills: resolution / codec / HDR / audio / subs.
            // 7. Actions: subs / download / info.

            val header = viewModel.header
            if (!header.isNullOrBlank()) {
                binding.urlHeader.text = header
                binding.urlHeader.contentDescription = header
                binding.urlHeader.visibility = View.VISIBLE
            } else {
                binding.urlHeader.text = ""
                binding.urlHeader.contentDescription = null
                binding.urlHeader.visibility = View.INVISIBLE
            }

            // Tracker cue: only the explicitly parsed tracker from the
            // 📂 line. The cue is a small icon (no duplicated text) whose
            // contentDescription is the parsed tracker string. Hidden when
            // no tracker was parsed. No network / favicon / copied logo
            // assets are involved.
            val tracker = viewModel.tracker
            if (!tracker.isNullOrBlank()) {
                binding.urlTrackerCue.contentDescription = tracker
                binding.urlTrackerCue.visibility = View.VISIBLE
            } else {
                binding.urlTrackerCue.contentDescription = null
                binding.urlTrackerCue.visibility = View.GONE
            }

            // Cleaned release title (UI-only).
            val cleanSubtitle = viewModel.cleanSubtitle
            val subtitle = viewModel.subtitle
            val subToShow = if (!cleanSubtitle.isNullOrBlank()) cleanSubtitle else subtitle
            if (!subToShow.isNullOrBlank() && subToShow != viewModel.displayLabel) {
                binding.urlReleaseName.text = subToShow
                binding.urlReleaseName.visibility = View.VISIBLE
            } else {
                binding.urlReleaseName.visibility = View.GONE
            }

            // Seeders (prominent, theme primary).
            val seederChip = viewModel.meta.firstOrNull { it.icon == SourceMetaIcon.SEEDERS }
            if (seederChip != null) {
                binding.urlSeeders.text = seederChip.text
                binding.urlSeeders.visibility = View.VISIBLE
            } else {
                binding.urlSeeders.text = ""
                binding.urlSeeders.visibility = View.GONE
            }

            // Size + tracker (lightweight secondary).
            val sizeTracker = viewModel.meta
                .filter { it.icon == SourceMetaIcon.SIZE || it.icon == SourceMetaIcon.INDEXER }
                .joinToString(" · ") { it.text }
            if (sizeTracker.isNotEmpty()) {
                binding.urlSizeAndTracker.text = "· " + sizeTracker
                binding.urlSizeAndTracker.visibility = View.VISIBLE
            } else {
                binding.urlSizeAndTracker.text = ""
                binding.urlSizeAndTracker.visibility = View.GONE
            }

            // Technical pills (resolution / codec / HDR / audio / subs).
            // These live in their own row in the layout, NOT beside the
            // release-group header. The pill order is enforced inside
            // buildPills(); here we just render whatever was selected.
            bindPills(binding.urlPills, viewModel.pills)

            // Details panel content (always reset on every bind; tap toggles
            // visibility). The content text is plain monospace-style rows
            // joined with newlines for now.
            binding.urlDetails.visibility = View.GONE
            binding.urlDetailsText.text = formatDetailsText(viewModel)
            binding.urlExpand.setOnClickListener {
                val visible = binding.urlDetails.visibility == View.VISIBLE
                binding.urlDetails.visibility = if (visible) View.GONE else View.VISIBLE
            }
        }

        /**
         * Drive one inline direct-stream row. STREAM rows carry only a
         * server name, compact pills, and actions; release-group,
         * tracker-cue, clean-title, seeder, and size rows do not exist
         * in the inline card. Action-button visibility and dialogs go
         * through the same shared setup functions as the full card.
         */
        private fun bindInlineStreamRow(
            binding: ItemUrlStreamInlineBinding,
            video: Video,
            position: Int,
            subtitles: List<Subtitle>,
        ) {
            val viewModel = SourceRowSummarizer.build(
                displayLabel = extractor.server.name,
                qualityText = extractor.server.name,
                format = video.format,
                url = video.file.url,
            )
            val header = viewModel.header
            if (!header.isNullOrBlank()) {
                binding.urlHeader.text = header
                binding.urlHeader.contentDescription = header
                binding.urlHeader.visibility = View.VISIBLE
            } else {
                binding.urlHeader.text = ""
                binding.urlHeader.contentDescription = null
                binding.urlHeader.visibility = View.GONE
            }
            bindPills(binding.urlPills, viewModel.pills)
            if (isDownloadMenu == true) {
                binding.urlDownload.visibility = View.VISIBLE
            } else {
                binding.urlDownload.visibility = View.GONE
            }
            if (subtitles.isNotEmpty()) {
                binding.urlSub.visibility = View.VISIBLE
            } else {
                binding.urlSub.visibility = View.GONE
            }
            setupRowSubtitleButton(binding.urlSub, subtitles)
            setupRowDownloadButton(binding.urlDownload, position, subtitles)
            binding.urlDetails.visibility = View.GONE
            binding.urlDetailsText.text = formatDetailsText(viewModel)
            binding.urlExpand.setOnClickListener {
                val visible = binding.urlDetails.visibility == View.VISIBLE
                binding.urlDetails.visibility = if (visible) View.GONE else View.VISIBLE
            }
        }

        /**
         * Populate the right-aligned pill row. Each pill is a small
         * rounded-corner TextView with a colored background per kind.
         * Existing pills are removed on every bind so recycled holders
         * never leak.
         */
        private fun bindPills(
            container: android.widget.LinearLayout,
            pills: List<SourceRowPill>,
        ) {
            container.removeAllViews()
            for (pill in pills) {
                val tv = makePillTextView(container.context, pill)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.marginStart = pillMarginPx(container.context)
                container.addView(tv, lp)
            }
        }

        /**
         * Flexbox overload for the compact RELEASE card, whose pill row
         * wraps instead of clipping when width/content requires it. Pill
         * appearance is identical to the linear variant; only the
         * container layout params differ. The inline STREAM card keeps
         * using the LinearLayout overload above (frozen v2 behavior).
         */
        private fun bindPills(
            container: com.google.android.flexbox.FlexboxLayout,
            pills: List<SourceRowPill>,
        ) {
            container.removeAllViews()
            for (pill in pills) {
                val tv = makePillTextView(container.context, pill)
                val lp = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.marginStart = pillMarginPx(container.context)
                container.addView(tv, lp)
            }
        }

        private fun pillMarginPx(context: android.content.Context): Int =
            (4 * context.resources.displayMetrics.density).toInt()

        private fun makePillTextView(
            context: android.content.Context,
            pill: SourceRowPill,
        ): android.widget.TextView {
            val tv = android.widget.TextView(context)
            tv.text = pill.text
            tv.setTextColor(pillTextColor(pill.kind))
            tv.setBackgroundResource(pillBackground(pill.kind))
            val padH = (4 * context.resources.displayMetrics.density).toInt()
            val padV = (2 * context.resources.displayMetrics.density).toInt()
            tv.setPadding(padH, padV, padH, padV)
            tv.textSize = 11f
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.maxLines = 1
            tv.setSingleLine(true)
            return tv
        }

        private fun pillBackground(kind: SourcePillKind): Int = when (kind) {
            SourcePillKind.QUALITY -> ani.dantotsu.R.drawable.pill_quality
            SourcePillKind.CODEC -> ani.dantotsu.R.drawable.pill_codec
            SourcePillKind.BIT_DEPTH -> ani.dantotsu.R.drawable.pill_bit_depth
            SourcePillKind.AUDIO_FMT -> ani.dantotsu.R.drawable.pill_audio
            SourcePillKind.SUB_LANG -> ani.dantotsu.R.drawable.pill_sub
            SourcePillKind.AUDIO_MODE -> ani.dantotsu.R.drawable.pill_audio_mode
            SourcePillKind.HDR -> ani.dantotsu.R.drawable.pill_hdr
            SourcePillKind.HDR10 -> ani.dantotsu.R.drawable.pill_hdr10
            SourcePillKind.HDR10_PLUS -> ani.dantotsu.R.drawable.pill_hdr10_plus
            SourcePillKind.DOLBY_VISION -> ani.dantotsu.R.drawable.pill_dolby_vision
        }

        /**
         * Text color for a pill: paired with the pill background per the
         * Material 3 "container" + "onContainer" convention. Theme-aware.
         */
        private fun pillTextColor(kind: SourcePillKind): Int {
            val context = ani.dantotsu.currContext() ?: return 0
            val attr = when (kind) {
                SourcePillKind.QUALITY -> com.google.android.material.R.attr.colorOnPrimaryContainer
                SourcePillKind.CODEC -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.BIT_DEPTH -> com.google.android.material.R.attr.colorOnSecondaryContainer
                SourcePillKind.AUDIO_FMT -> com.google.android.material.R.attr.colorOnSecondaryContainer
                SourcePillKind.SUB_LANG -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.AUDIO_MODE -> com.google.android.material.R.attr.colorOnPrimaryContainer
                SourcePillKind.HDR -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.HDR10 -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.HDR10_PLUS -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.DOLBY_VISION -> com.google.android.material.R.attr.colorOnTertiaryContainer
            }
            val typedValue = android.util.TypedValue()
            val resolved = context.theme.resolveAttribute(attr, typedValue, true)
            return if (resolved) typedValue.data else 0
        }

        private fun formatDetailsText(viewModel: SourceRowViewModel): String =
            viewModel.details.joinToString("\n") { line ->
                val label = line.label?.let { "$it: " } ?: ""
                "$label${line.value}"
            }

        /**
         * Row-click selection transaction shared by the full release
         * card and the inline stream card. Moved verbatim from
         * [UrlViewHolder.init]; behavior is identical for both row
         * kinds. `position` is the adapter position at click time.
         */
        private fun onRowClicked(
            position: Int,
            downloadClick: () -> Unit,
        ) {
            if (isDownloadMenu == true) {
                downloadClick()
                return
            }
            tryWith(true) {
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
                    currentEp.selectedExtractor = extractor.server.name
                    currentEp.selectedVideo = position
                }
                if (epKey != null) {
                    media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                    media?.anime?.episodes?.get(epKey)?.selectedVideo = position
                }
                if (makeDefault) {
                    media!!.selected!!.server = extractor.server.name
                    media!!.selected!!.video = position
                    model.saveSelected(media!!.id, media!!.selected!!)
                    val provider = StableCandidateBuilder.providerOf(extractor.server)
                    val exact = StableCandidateBuilder.exactKey(provider, extractor.server)
                    val candidate = StableCandidateBuilder.familyCandidate(extractor.server)
                    // Exact slot is CURRENT-candidate identity:
                    // OFF leaves it alone, ON writes the
                    // current key, ON with an unkeyable
                    // candidate clears a stale previous key.
                    when (RememberedSelectionPolicy.exactSlotAction(
                        makeDefault, exact,
                    )) {
                        RememberedSelectionPolicy.ExactSlotAction.WRITE ->
                            SelectedKeyStore.save(media!!.id, exact)
                        RememberedSelectionPolicy.ExactSlotAction.CLEAR ->
                            SelectedKeyStore.save(media!!.id, null)
                        RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE -> Unit
                    }
                    // Family slot is independent of the exact
                    // slot: REPLACE on a confident new
                    // family, PRESERVE (leave untouched) when
                    // the pick is not confidently
                    // family-shaped, so parser uncertainty
                    // cannot silently delete the user's
                    // previous family choice.
                    when (RememberedSelectionPolicy.familySlotAction(
                        makeDefault, candidate,
                    )) {
                        RememberedSelectionPolicy.FamilySlotAction.REPLACE -> {
                            val c = candidate!!
                            SelectedFamilyStore.save(media!!.id, FamilyPayload(
                                providerPkg = c.providerPkg,
                                groupKey = c.groupKey,
                                soft = c.soft,
                            ))
                        }
                        RememberedSelectionPolicy.FamilySlotAction.PRESERVE,
                        RememberedSelectionPolicy.FamilySlotAction.NO_CHANGE,
                        -> Unit
                    }
                }
                Log.d("AnimeDownloader", "Should start the player")
                startExoplayer(media!!)
            }
        }

        /**
         * Row long-click (copy link / open externally) shared by the
         * full release card and the inline stream card. Moved verbatim
         * from [UrlViewHolder.init]; behavior is identical.
         */
        private fun onRowLongClicked(position: Int): Boolean {
            val video = extractor.videos[position]
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(video.file.url), "video/*")
            }
            copyToClipboard(video.file.url, true)
            dismissAllowingStateLoss()
            startActivity(Intent.createChooser(intent, "Open Video in :"))
            return true
        }

        private inner class UrlViewHolder(val binding: ItemUrlBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                itemView.setSafeOnClickListener {
                    onRowClicked(bindingAdapterPosition) { binding.urlDownload.performClick() }
                }
                itemView.setOnLongClickListener {
                    onRowLongClicked(bindingAdapterPosition)
                }
            }
        }

        /**
         * Compact single-row holder for direct-stream rows. Click and
         * long-click behavior is shared with [UrlViewHolder] through
         * [onRowClicked]/[onRowLongClicked]; only the bound views differ.
         */
        private inner class InlineStreamViewHolder(val binding: ItemUrlStreamInlineBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                itemView.setSafeOnClickListener {
                    onRowClicked(bindingAdapterPosition) { binding.urlDownload.performClick() }
                }
                itemView.setOnLongClickListener {
                    onRowLongClicked(bindingAdapterPosition)
                }
            }
        }
    }

    companion object {
        fun newInstance(
            server: String? = null,
            la: Boolean = true,
            prev: String? = null,
            isDownload: Boolean,
            episodes: ArrayList<String>
        ): SelectorDialogFragment =
            SelectorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("server", server)
                    putBoolean("launch", la)
                    putString("prev", prev)
                    putBoolean("isDownload", isDownload)
                    putStringArrayList("episodes", episodes)
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {}

    override fun onDismiss(dialog: DialogInterface) {
        if (launch == false) {
            activity?.hideSystemBars()
            model.epChanged.postValue(true)
            if (prevEpisode != null) {
                media?.anime?.selectedEpisode = prevEpisode
                model.setEpisode(media?.anime?.episodes?.get(prevEpisode) ?: return, "prevEp")
            }
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
