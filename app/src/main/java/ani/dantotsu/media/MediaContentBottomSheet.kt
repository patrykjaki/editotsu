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
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
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

        // the watch order loads behind a skeleton shaped like its own rows
        if (mode == MODE_WATCH_ORDER) binding.repliesRefresh.tag = "order"
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
                val orders = withContext(Dispatchers.IO) {
                    model.getWatchOrders(media)
                }
                if (_binding == null) return@launch
                binding.repliesRefresh.visibility = View.GONE
                when {
                    orders == null -> showEmptyState(mode, failed = true)
                    orders.isEmpty() -> showEmptyState(mode)
                    else -> showOrders(orders, 0, media)
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
        // AniList's prequel/sequel chain decides when it could be read; the format guess otherwise
        val optional = items.map {
            !it.isCurrent && (it.mainStory?.not() ?: watchOrderOptional(it.mediaType, it.relationType))
        }
        val numbers = watchOrderNumbers(optional)
        val numberById = items.indices.mapNotNull { i -> numbers[i]?.let { items[i].anilistId.trim() to it } }.toMap()
        val hide = PrefManager.getVal<Boolean>(PrefName.WatchOrderHideOptional)
        val rows = items.indices
            .filter { !(hide && optional[it]) }
            .map { WatchOrderRow(items[it], optional[it], numbers[it], items[it].alternativeTo?.let(numberById::get)) }
        binding.repliesRecyclerView.adapter = WatchOrderAdapter(rows, media)

        // header toggle: the icon shows what a tap does
        binding.subscribeButton.visibility = if (optional.any { it }) View.VISIBLE else View.GONE
        binding.subscribeButton.setBackgroundResource(R.drawable.icon_button_ripple)
        binding.subscribeButton.imageTintList = android.content.res.ColorStateList.valueOf(
            requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
        )
        // a standard 48dp icon button, 16dp from the edge (the shared header's 36dp one sat too close)
        val dp = resources.displayMetrics.density
        binding.subscribeButton.setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        binding.subscribeButton.layoutParams = (binding.subscribeButton.layoutParams as ViewGroup.MarginLayoutParams)
            .apply {
                width = (48 * dp).toInt()
                height = (48 * dp).toInt()
                marginEnd = (16 * dp).toInt()
            }
        binding.subscribeButton.setImageResource(
            if (hide) R.drawable.ic_round_remove_red_eye_24 else R.drawable.ic_round_visibility_off_24,
        )
        binding.subscribeButton.contentDescription = getString(
            if (hide) R.string.watch_order_show_optional else R.string.watch_order_hide_optional,
        )
        binding.subscribeButton.setOnClickListener {
            PrefManager.setVal(PrefName.WatchOrderHideOptional, !hide)
            displayWatchOrder(items, media)
        }

        val currentIndex = rows.indexOfFirst { it.item.isCurrent }
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

    /** A franchise with several orders (airing, novel, chronological) gets a picker under the title. */
    private fun showOrders(orders: List<MediaDetailsViewModel.WatchOrder>, selected: Int, media: Media?) {
        val order = orders[selected]
        binding.orderChip.visibility = if (orders.size > 1) View.VISIBLE else View.GONE
        // like the calendar filter: the chip names the choice and is filled when it isn't the first order
        binding.orderChip.text = order.name
        binding.orderChip.isChecked = selected != 0
        binding.orderChip.contentDescription = getString(R.string.watch_order_pick_desc, order.name)
        binding.orderChip.setOnCloseIconClickListener { binding.orderChip.performClick() }
        binding.orderChip.setOnClickListener {
            binding.orderChip.isChecked = selected != 0 // the chip toggles itself on a tap

            ani.dantotsu.settings.rows.ChoiceSheet.single(
                requireContext(), getString(R.string.watch_order_pick),
                orders.map { ani.dantotsu.settings.rows.ChoiceOption(it.name) }, selected,
            ) { i -> if (_binding != null) showOrders(orders, i, media) }
        }
        displayWatchOrder(order.items, media)
    }

    private fun displayNews(items: List<MediaDetailsViewModel.NewsItem>) {
        binding.repliesEmpty.visibility = View.GONE
        binding.repliesRecyclerView.visibility = View.VISIBLE
        binding.repliesRecyclerView.adapter = NewsAdapter(items)
    }

    private fun showEmptyState(mode: String, failed: Boolean = false) {
        binding.repliesRecyclerView.visibility = View.GONE
        binding.repliesEmpty.visibility = View.VISIBLE
        binding.repliesEmptyText.text = if (mode == MODE_WATCH_ORDER) {
            getString(if (failed) R.string.watch_order_failed else R.string.no_watch_order_found)
        } else {
            getString(R.string.no_news_found)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class WatchOrderRow(
        val item: MediaDetailsViewModel.WatchOrderItem,
        val optional: Boolean,
        val number: Int?,
        /** the number of the entry this one can replace (a recap movie of a season) */
        val alternativeOf: Int? = null,
    )

    private inner class WatchOrderAdapter(
        private val rows: List<WatchOrderRow>,
        private val currentMedia: Media?
    ) : RecyclerView.Adapter<WatchOrderAdapter.ViewHolder>() {
        private val currentIndex = rows.indexOfFirst { it.item.isCurrent }

        inner class ViewHolder(val binding: ItemWatchOrderBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemWatchOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = rows[position]
            val item = row.item
            val context = holder.binding.root.context
            val density = context.resources.displayMetrics.density

            // Title
            holder.binding.watchOrderTitle.text = item.name

            // Cover Image
            val imageUrl = if (item.image.isNotEmpty()) item.image else currentMedia?.cover
            holder.binding.watchOrderImage.loadImage(imageUrl)

            // an optional entry is a small dot on the line, without a number
            val isOptional = row.optional
            holder.binding.watchOrderNumber.text = row.number?.toString().orEmpty()
            // a real 10dp dot, not a scaled-down circle: the line has to run into it with no gap
            holder.binding.watchOrderNumber.layoutParams = holder.binding.watchOrderNumber.layoutParams.apply {
                val size = ((if (isOptional) 10 else 28) * density).toInt()
                width = size
                height = size
            }
            val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            val line = context.getThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
            val surfaceVariant = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
            val place = watchOrderPlace(position, currentIndex)

            // the stop on the line: filled up to the open title, plain after it; a dot takes the line's own colour
            val (stop, onStop) = if (isOptional) {
                (if (place == WatchOrderPlace.EARLIER) primary else line) to line
            } else when (place) {
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
            holder.binding.watchOrderLineBelow.visibility = if (position == rows.lastIndex) View.INVISIBLE else View.VISIBLE

            // the numbers carry the order, so a row says only where you are or that it is optional
            if (item.isCurrent) {
                // "You are here" keeps the accent; a finished state after it is muted like on the other rows
                val here = context.getString(R.string.watch_order_here)
                holder.binding.watchOrderRelation.text = if (watchOrderCompleted(item.listStatus)) {
                    android.text.SpannableString("$here · ${context.getString(R.string.watch_order_completed)}").apply {
                        setSpan(
                            android.text.style.ForegroundColorSpan(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)),
                            here.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                } else here
                holder.binding.watchOrderRelation.visibility = View.VISIBLE
                holder.binding.watchOrderChevron.visibility = View.INVISIBLE
                holder.binding.watchOrderCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(primary, 40))
            } else {
                val coming = if (item.upcoming) {
                    watchOrderComingDate(item.startYear, item.startMonth, item.startDay)
                        ?.let { context.getString(R.string.watch_order_coming, it) }
                        ?: context.getString(R.string.watch_order_coming_soon)
                } else null
                val completed = if (watchOrderCompleted(item.listStatus)) context.getString(R.string.watch_order_completed) else null
                holder.binding.watchOrderRelation.text = listOfNotNull(
                    rows[position].alternativeOf?.let { context.getString(R.string.watch_order_alternative, it) }
                        ?: if (isOptional) context.getString(R.string.watch_order_optional) else null,
                    completed ?: coming,
                ).joinToString(" · ")
                holder.binding.watchOrderRelation.visibility =
                    if (holder.binding.watchOrderRelation.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                holder.binding.watchOrderChevron.visibility = View.VISIBLE
                holder.binding.watchOrderCard.setCardBackgroundColor(
                    if (isOptional) android.graphics.Color.TRANSPARENT else surfaceVariant,
                )
            }
            holder.binding.watchOrderRelation.setTextColor(
                if (!item.isCurrent && (isOptional || watchOrderCompleted(item.listStatus))) {
                    context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                } else primary,
            )
            // seen already: the row steps back, the order stays readable
            holder.binding.watchOrderContent.alpha =
                if (!item.isCurrent && watchOrderCompleted(item.listStatus)) 0.5f else 1f
            holder.binding.watchOrderCard.strokeWidth = 0

            // optional rows stay quieter than the order: smaller cover, shorter row, muted title
            holder.binding.watchOrderCover.layoutParams = holder.binding.watchOrderCover.layoutParams.apply {
                width = ((if (isOptional) 40 else 56) * density).toInt()
                height = ((if (isOptional) 56 else 80) * density).toInt()
            }
            holder.binding.watchOrderContent.minimumHeight = ((if (isOptional) 72 else 100) * density).toInt()
            holder.binding.watchOrderTitle.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                context, if (isOptional) R.font.poppins else R.font.poppins_semi_bold,
            )
            holder.binding.watchOrderTitle.setTextColor(
                context.getThemeColor(
                    if (isOptional) com.google.android.material.R.attr.colorOnSurfaceVariant
                    else com.google.android.material.R.attr.colorOnSurface,
                ),
            )

            val episodes = watchOrderEpisodeCount(item.episodes)
            holder.binding.watchOrderMeta.text = watchOrderMeta(
                item.mediaType,
                // an unreleased entry carries its date in the label
                if (item.upcoming) "" else item.airDate,
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

        override fun getItemCount() = rows.size
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

/** One line: format, the year or years it aired ("Apr 12 – Jun 28, 2023" -> "2023"), episode count. */
internal fun watchOrderMeta(format: String, airDate: String, episodes: String?): String =
    listOf(format.trim(), watchOrderYears(airDate), episodes.orEmpty())
        .filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * AniList's start date as precise as it is known: "Jul 8, 2027", "Jul 2027", "2027";
 * null when there is no year (the row then says "Coming soon").
 */
internal fun watchOrderComingDate(
    year: Int?,
    month: Int?,
    day: Int?,
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String? {
    if (year == null) return null
    if (month == null || month !in 1..12) return year.toString()
    val date = runCatching { java.time.LocalDate.of(year, month, day ?: 1) }.getOrNull()
        ?: return year.toString()
    val formatter = if (day == null) {
        java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", locale)
    } else {
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
    }
    return date.format(formatter)
}

/** on the user's list as seen: completed, or completed and being rewatched */
internal fun watchOrderCompleted(listStatus: String?): Boolean =
    listStatus == "COMPLETED" || listStatus == "REPEATING"

/** "Jul 3 – Oct 6, 2024" -> "2024", "Oct 2025 – Mar 2026" -> "2025–2026"; nothing when no year is known. */
internal fun watchOrderYears(airDate: String): String {
    val years = Regex("""\b(19|20)\d{2}\b""").findAll(airDate).map { it.value }.distinct().toList()
    return when (years.size) {
        0 -> ""
        1 -> years[0]
        else -> "${years.first()}–${years.last()}"
    }
}

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
