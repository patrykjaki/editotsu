package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetRecyclerBinding
import ani.dantotsu.databinding.ItemMediaContentBinding
import ani.dantotsu.databinding.ItemWatchOrderBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.others.getSerialized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser

class MediaContentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRecyclerBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRecyclerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mode = arguments?.getString(ARG_MODE) ?: MODE_WATCH_ORDER
        val defaultTitle = if (mode == MODE_WATCH_ORDER) {
            getString(R.string.watch_order)
        } else {
            getString(R.string.news)
        }
        val title = arguments?.getString(ARG_TITLE)?.ifEmpty { defaultTitle } ?: defaultTitle
        val media: Media? = arguments?.getSerialized(ARG_MEDIA) ?: model.getMedia().value

        binding.title.text = title
        if (mode == MODE_WATCH_ORDER) {
            // the shared sheet header has no start inset and a lighter face than the page headers
            binding.title.setPadding((24 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            binding.title.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.poppins_bold)
        }
        binding.subscribeButton.visibility = View.GONE
        binding.replyButton.visibility = View.GONE

        binding.repliesRecyclerView.layoutManager = LinearLayoutManager(context)

        // If items were already provided, display immediately
        val passedWatchOrderItems: ArrayList<MediaDetailsViewModel.WatchOrderItem>? =
            arguments?.getSerialized(ARG_ITEMS)
        val passedNewsItems: ArrayList<MediaDetailsViewModel.NewsItem>? =
            arguments?.getSerialized(ARG_ITEMS)

        if (mode == MODE_WATCH_ORDER && !passedWatchOrderItems.isNullOrEmpty()) {
            displayWatchOrder(passedWatchOrderItems, media)
            return
        } else if (mode == MODE_NEWS && !passedNewsItems.isNullOrEmpty()) {
            displayNews(passedNewsItems)
            return
        }

        // Show loading spinner and fetch in background
        binding.repliesRefresh.visibility = View.VISIBLE
        binding.repliesRecyclerView.visibility = View.GONE
        binding.repliesEmpty.visibility = View.GONE

        if (media == null) {
            binding.repliesRefresh.visibility = View.GONE
            showEmptyState(mode)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            if (mode == MODE_WATCH_ORDER) {
                val items = withContext(Dispatchers.IO) {
                    model.getWatchOrder(media)
                }
                if (_binding == null) return@launch
                binding.repliesRefresh.visibility = View.GONE
                if (items.isEmpty()) {
                    showEmptyState(mode)
                } else {
                    displayWatchOrder(items, media)
                }
            } else {
                val isAnime = media.anime != null || media.format in listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
                val items = withContext(Dispatchers.IO) {
                    if (isAnime) {
                        model.getAnimeNews(media)
                    } else {
                        model.getMangaNovelNews(media)
                    }
                }
                if (_binding == null) return@launch
                binding.repliesRefresh.visibility = View.GONE
                if (items.isEmpty()) {
                    showEmptyState(mode)
                } else {
                    displayNews(items)
                }
            }
        }
    }

    private fun displayWatchOrder(
        items: List<MediaDetailsViewModel.WatchOrderItem>,
        media: Media?
    ) {
        binding.repliesEmpty.visibility = View.GONE
        binding.repliesRecyclerView.visibility = View.VISIBLE
        val adapter = WatchOrderAdapter(items, media)
        binding.repliesRecyclerView.adapter = adapter
        val currentIndex = items.indexOfFirst { it.isCurrent }
        if (currentIndex != -1) {
            val layoutManager = binding.repliesRecyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.scrollToPositionWithOffset(currentIndex, 0)
            binding.repliesRecyclerView.post {
                if (_binding == null) return@post
                (binding.repliesRecyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    private fun displayNews(items: List<MediaDetailsViewModel.NewsItem>) {
        binding.repliesEmpty.visibility = View.GONE
        binding.repliesRecyclerView.visibility = View.VISIBLE
        binding.repliesRecyclerView.adapter = NewsAdapter(items)
    }

    private fun showEmptyState(mode: String) {
        binding.repliesRecyclerView.visibility = View.GONE
        binding.repliesEmpty.visibility = View.VISIBLE
        binding.repliesEmptyText.text = if (mode == MODE_WATCH_ORDER) {
            getString(R.string.no_watch_order_found)
        } else {
            getString(R.string.no_news_found)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class WatchOrderAdapter(
        private val items: List<MediaDetailsViewModel.WatchOrderItem>,
        private val currentMedia: Media?
    ) : RecyclerView.Adapter<WatchOrderAdapter.ViewHolder>() {
        private val currentIndex = items.indexOfFirst { it.isCurrent }
        private val optional = items.map { !it.isCurrent && watchOrderOptional(it.mediaType, it.relationType) }
        private val numbers = watchOrderNumbers(optional)

        inner class ViewHolder(val binding: ItemWatchOrderBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemWatchOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.binding.root.context

            // Title
            holder.binding.watchOrderTitle.text = item.name

            // Cover Image
            val imageUrl = if (item.image.isNotEmpty()) item.image else currentMedia?.cover
            holder.binding.watchOrderImage.loadImage(imageUrl)

            // an optional entry is a small dot on the line, without a number
            val isOptional = optional[position]
            holder.binding.watchOrderNumber.text = numbers[position]?.toString().orEmpty()
            holder.binding.watchOrderNumber.scaleX = if (isOptional) 0.4f else 1f
            holder.binding.watchOrderNumber.scaleY = if (isOptional) 0.4f else 1f
            val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            val line = context.getThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
            val surfaceVariant = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
            val place = watchOrderPlace(position, currentIndex)

            // the stop on the line: filled up to the open title, plain after it
            val (stop, onStop) = when (place) {
                WatchOrderPlace.CURRENT -> primary to context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
                WatchOrderPlace.EARLIER -> context.getThemeColor(com.google.android.material.R.attr.colorSecondaryContainer) to
                    context.getThemeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
                WatchOrderPlace.LATER -> surfaceVariant to
                    context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            holder.binding.watchOrderNumber.backgroundTintList = android.content.res.ColorStateList.valueOf(stop)
            holder.binding.watchOrderNumber.setTextColor(onStop)
            holder.binding.watchOrderLineAbove.setBackgroundColor(if (place == WatchOrderPlace.LATER) line else primary)
            holder.binding.watchOrderLineBelow.setBackgroundColor(if (place == WatchOrderPlace.EARLIER) primary else line)
            holder.binding.watchOrderLineAbove.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
            holder.binding.watchOrderLineBelow.visibility = if (position == items.lastIndex) View.INVISIBLE else View.VISIBLE

            // one label per row: where you are, or how the entry relates when AniList says
            if (item.isCurrent) {
                holder.binding.watchOrderRelation.setText(R.string.watch_order_here)
                holder.binding.watchOrderRelation.visibility = View.VISIBLE
                holder.binding.watchOrderChevron.visibility = View.INVISIBLE
                holder.binding.watchOrderCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(primary, 40))
            } else {
                val optionalWord = context.getString(R.string.watch_order_optional)
                holder.binding.watchOrderRelation.text = when {
                    !isOptional -> item.relationType
                    item.relationType.isBlank() -> optionalWord
                    else -> "$optionalWord · ${item.relationType}"
                }
                holder.binding.watchOrderRelation.visibility =
                    if (holder.binding.watchOrderRelation.text.isNullOrBlank()) View.GONE else View.VISIBLE
                holder.binding.watchOrderChevron.visibility = View.VISIBLE
                holder.binding.watchOrderCard.setCardBackgroundColor(
                    if (isOptional) android.graphics.Color.TRANSPARENT else surfaceVariant,
                )
            }
            holder.binding.watchOrderRelation.setTextColor(
                if (isOptional) context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant) else primary,
            )
            holder.binding.watchOrderCard.strokeWidth = if (isOptional) (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1) else 0
            holder.binding.watchOrderCard.strokeColor = line

            val episodes = watchOrderEpisodeCount(item.episodes)
            holder.binding.watchOrderMeta.text = watchOrderMeta(
                item.mediaType,
                item.airDate,
                episodes?.let { context.resources.getQuantityString(R.plurals.watch_order_episodes, it, it) },
            )
            holder.binding.watchOrderMeta.visibility =
                if (holder.binding.watchOrderMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE

            // Click listener
            holder.binding.watchOrderCard.setOnClickListener {
                if (item.isCurrent) {
                    dismissAllowingStateLoss()
                    return@setOnClickListener
                }
                val anilistId = item.anilistId.toIntOrNull()
                if (anilistId != null && anilistId != 0) {
                    val intent = Intent(requireContext(), MediaDetailsActivity::class.java).apply {
                        putExtra("mediaId", anilistId)
                    }
                    startActivity(intent)
                    dismissAllowingStateLoss()
                } else {
                    val intent = Intent(requireContext(), SearchActivity::class.java).apply {
                        putExtra("type", "ANIME")
                        putExtra("query", item.name)
                        putExtra("search", true)
                    }
                    startActivity(intent)
                    dismissAllowingStateLoss()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class NewsAdapter(
        private val items: List<MediaDetailsViewModel.NewsItem>
    ) : RecyclerView.Adapter<NewsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMediaContentBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemMediaContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val decodedTitle = Parser.unescapeEntities(item.title, false)
            holder.binding.contentTitle.text = decodedTitle

            val timeAgo = item.date?.let { formatTimeAgo(it.time) }
            val readArticle = getString(R.string.read_article)
            holder.binding.contentSubtitle.text = if (timeAgo != null) {
                "$timeAgo • $readArticle"
            } else {
                readArticle
            }
            holder.binding.contentIcon.setImageResource(R.drawable.ic_round_notifications_active_24)

            holder.binding.contentCard.setOnClickListener {
                if (item.url.isNotEmpty()) {
                    runCatching {
                        val uri = Uri.parse(item.url)
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        private fun formatTimeAgo(timeMs: Long): String {
            val diff = System.currentTimeMillis() - timeMs
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                days > 0 -> "${days}d ago"
                hours > 0 -> "${hours}h ago"
                minutes > 0 -> "${minutes}m ago"
                else -> "Just now"
            }
        }
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MODE = "arg_mode"
        private const val ARG_ITEMS = "arg_items"
        private const val ARG_MEDIA = "arg_media"

        const val MODE_WATCH_ORDER = "watch_order"
        const val MODE_NEWS = "news"

        fun newWatchOrderInstance(media: Media): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, MODE_WATCH_ORDER)
                    putSerializable(ARG_MEDIA, media)
                }
            }
        }

        fun newNewsInstance(media: Media): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, MODE_NEWS)
                    putSerializable(ARG_MEDIA, media)
                }
            }
        }

        fun newWatchOrderInstance(
            title: String,
            items: List<MediaDetailsViewModel.WatchOrderItem>
        ): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MODE, MODE_WATCH_ORDER)
                    putSerializable(ARG_ITEMS, ArrayList(items))
                }
            }
        }

        fun newNewsInstance(
            title: String,
            items: List<MediaDetailsViewModel.NewsItem>
        ): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MODE, MODE_NEWS)
                    putSerializable(ARG_ITEMS, ArrayList(items))
                }
            }
        }
    }
}

/** "12ep × 24min." -> 12; unknown or zero counts ("0ep × ? min.", "") -> null. */
internal fun watchOrderEpisodeCount(raw: String): Int? =
    Regex("""^\s*(\d+)\s*ep""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

/** One line: format, air dates without an unknown end ("Oct 9, 2026 – ?" -> "Oct 9, 2026"), episode count. */
internal fun watchOrderMeta(format: String, airDate: String, episodes: String?): String =
    listOf(
        format.trim(),
        airDate.replace(Regex("""\s*[–-]\s*\?\s*$"""), "").trim().takeUnless { it == "?" }.orEmpty(),
        episodes.orEmpty(),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

/** AniList's relation as a label: "SIDE_STORY" -> "Side story"; nothing when there is none. */
internal fun watchOrderRelation(raw: String?): String =
    raw.orEmpty().replace('_', ' ').trim().lowercase().replaceFirstChar { it.uppercase() }

/** where a row sits against the open title; with no open title in the list every row is [LATER] */
internal enum class WatchOrderPlace { EARLIER, CURRENT, LATER }

internal fun watchOrderPlace(position: Int, currentIndex: Int): WatchOrderPlace = when {
    currentIndex < 0 || position > currentIndex -> WatchOrderPlace.LATER
    position == currentIndex -> WatchOrderPlace.CURRENT
    else -> WatchOrderPlace.EARLIER
}

/**
 * Entries the story doesn't need: extras by format (OVA, special, music video) or by what
 * AniList calls them (side story, spin-off, recap, alternative, other). A plain TV season or a
 * film with no such relation is part of the order.
 */
internal fun watchOrderOptional(format: String, relation: String): Boolean {
    val f = format.trim().lowercase()
    val r = relation.trim().lowercase().replace('_', ' ')
    return f in setOf("ova", "special", "tv special", "music", "pv", "cm") ||
        r in setOf("side story", "spin off", "summary", "alternative", "other", "character", "compilation")
}

/** the number on each stop: main entries count 1, 2, 3; optional ones (null) don't take a number */
internal fun watchOrderNumbers(optional: List<Boolean>): List<Int?> {
    var n = 0
    return optional.map { if (it) null else ++n }
}
