package ani.dantotsu.animethemes

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentMediaThemesBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.setBaseline
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * The Themes tab on anime details: AnimeThemes' openings and endings for the show.
 * While AnimeThemes answers (it can take 20-40 s) a spinner shows; when it does not
 * answer, a retry button; when it has nothing, AniList's opening/ending text.
 */
class MediaThemesFragment : Fragment() {
    private var _binding: FragmentMediaThemesBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()
    private var job: Job? = null
    private var shownFor: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaThemesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = requireActivity() as MediaDetailsActivity
        val anchor = activity.binding.mediaBottomBarContainer ?: activity.binding.commentMessageContainer
        binding.themesScroll.setBaseline(anchor, includeSystemNavBar = anchor != activity.binding.mediaBottomBarContainer)
        model.scrolledToTop.observe(viewLifecycleOwner) { if (it) binding.themesScroll.scrollTo(0, 0) }
        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (media?.anime != null && shownFor != media.id) {
                shownFor = media.id
                load(media, force = false)
            }
        }
    }

    private fun load(media: Media, force: Boolean) {
        job?.cancel()
        binding.themesLoading.isVisible = true
        binding.themesFailed.isVisible = false
        binding.themesContent.removeAllViews()
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) || !isOnline(requireContext())
        job = viewLifecycleOwner.lifecycleScope.launch {
            val result = AnimeThemes.repository.lookup(media.id, media.idMAL, forceRefresh = force)
            if (_binding == null) return@launch
            binding.themesLoading.isVisible = false
            when (result) {
                is ThemesLookup.Found -> {
                    val choices = ThemeQueue.choices(result.data, themePrefs())
                        .map { ThemeQueue.withShow(it, media.id, media.userPreferredName) }
                    if (choices.isEmpty()) showAniListText(media) else showList(media, choices)
                }
                ThemesLookup.NoMatch -> showAniListText(media)
                ThemesLookup.Failed -> {
                    binding.themesFailed.isVisible = true
                    binding.themesRetry.isVisible = true
                    binding.themesFailedText.setText(if (offline) R.string.anime_themes_offline else R.string.anime_themes_failed)
                    binding.themesRetry.setOnClickListener { load(media, force = true) }
                    // Something to read while AnimeThemes is down.
                    showAniListText(media)
                }
            }
        }
    }

    private fun showList(media: Media, choices: List<ThemeChoice>) {
        val shieldOn: Boolean = PrefManager.getVal(PrefName.HideEpisodeSpoilers)
        val shielded = { t: ThemeTrack -> ThemeQueue.shielded(shieldOn, t.episodes, media.userProgress) }
        binding.themesContent.addView(
            AnimeThemesList.inflate(
                binding.themesContent, choices, shielded,
                onPlay = { tapped, shown ->
                    val queue = ThemeQueue.playQueue(shown, tapped, shielded)
                    ExoplayerView.openThemes(
                        requireContext(), queue,
                        queue.indexOfFirst { it.themeId == tapped.themeId }.coerceAtLeast(0),
                        media.userPreferredName,
                    )
                },
                onPlayAll = { queue -> ExoplayerView.openThemes(requireContext(), queue, 0, media.userPreferredName) },
                onSettings = { startActivity(Intent(requireContext(), PlayerSettingsActivity::class.java)) },
            )
        )
    }

    /** AniList's own opening/ending lines, each linked to a YouTube search. */
    private fun showAniListText(media: Media) {
        val anime = media.anime ?: return
        val markwon = Markwon.builder(requireContext()).usePlugin(SoftBreakAddsNewLinePlugin.create()).build()

        fun makeLink(a: String): String {
            val first = a.indexOf('"').let { if (it != -1) it else return a } + 1
            val end = a.indexOf('"', first).let { if (it != -1) it else return a }
            val name = a.subSequence(first, end).toString()
            return "${a.subSequence(0, first)}" +
                "[$name](https://www.youtube.com/results?search_query=${URLEncoder.encode(name, "utf-8")})" +
                "${a.subSequence(end, a.length)}"
        }

        fun add(title: Int, lines: List<String>) {
            if (lines.isEmpty()) return
            val bind = ItemTitleTextBinding.inflate(layoutInflater, binding.themesContent, false)
            bind.itemTitle.setText(title)
            val text: TextView = bind.itemText
            text.setOnClickListener {
                ObjectAnimator.ofInt(text, "maxLines", if (text.maxLines == 4) 100 else 4)
                    .setDuration(if (text.maxLines == 4) 950 else 400).start()
            }
            markwon.setMarkdown(text, lines.joinToString("\n") { makeLink(it) })
            binding.themesContent.addView(bind.root)
        }
        add(R.string.opening, anime.op)
        add(R.string.ending, anime.ed)
        if (anime.op.isEmpty() && anime.ed.isEmpty() && !binding.themesFailed.isVisible) {
            binding.themesFailed.isVisible = true
            binding.themesFailedText.setText(R.string.anime_themes_none)
            binding.themesRetry.isVisible = false
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun themePrefs() = ThemePlaybackPreferences(
            resolution = ResolutionPref.fromInt(PrefManager.getVal(PrefName.AnimeThemesPreferredResolution)),
            source = SourcePref.fromInt(PrefManager.getVal(PrefName.AnimeThemesPreferredSource)),
            credits = CreditsPref.fromInt(PrefManager.getVal(PrefName.AnimeThemesPreferredCredits)),
            subtitles = SubtitlesPref.fromInt(PrefManager.getVal(PrefName.AnimeThemesPreferredSubtitles)),
        )
    }
}
