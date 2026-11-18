package ani.dantotsu.media

import ani.dantotsu.others.setDropdownText
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
import ani.dantotsu.databinding.ItemNewsBinding
import ani.dantotsu.databinding.ItemNewsHeaderBinding
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

    /**
     * Kept in memory, not in the arguments: Media holds fields that can't be saved, and saving
     * them when the app went to the background crashed it. After a recreate the page's media
     * comes from the shared view model.
     */
    private var startMedia: Media? = null

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
        val media: Media? = startMedia ?: model.getMedia().value

        binding.title.text = title
        if (mode == MODE_NEWS) {
            // title on the rows' 16dp gutter; the first month header brings its own top space
            binding.title.setPadding((16 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            binding.title.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.poppins_bold)
            binding.repliesRecyclerView.setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
        }
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
        binding.orderChip.setDropdownText(order.name)
        binding.orderChip.isChecked = selected != 0
        binding.orderChip.contentDescription = getString(R.string.watch_order_pick_desc, order.name)
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
        val media: Media? = startMedia ?: model.getMedia().value
        val names = listOfNotNull(media?.name, media?.nameRomaji, media?.nameMAL, media?.userPreferredName)
        binding.repliesRecyclerView.adapter = NewsAdapter(items, names, media?.banner ?: media?.cover)
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
            // finished: a check leads the label line; a row with no other label says "Completed"
            val finished = watchOrderCompleted(item.listStatus)
            if (item.isCurrent) {
                holder.binding.watchOrderRelation.text = context.getString(R.string.watch_order_here)
                holder.binding.watchOrderRelation.visibility = View.VISIBLE
                holder.binding.watchOrderChevron.visibility = View.INVISIBLE
                holder.binding.watchOrderCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(primary, 40))
            } else {
                val coming = if (item.upcoming) {
                    watchOrderComingDate(item.startYear, item.startMonth, item.startDay)
                        ?.let { context.getString(R.string.watch_order_coming, it) }
                        ?: context.getString(R.string.watch_order_coming_soon)
                } else null
                val role = rows[position].alternativeOf?.let { context.getString(R.string.watch_order_alternative, it) }
                    ?: if (isOptional) context.getString(R.string.watch_order_optional) else null
                val upcoming = coming.takeUnless { finished }
                holder.binding.watchOrderRelation.text = when {
                    role != null && upcoming != null -> context.getString(R.string.watch_order_role_coming, role, upcoming.replaceFirstChar { it.lowercase() })
                    role != null -> role
                    upcoming != null -> upcoming
                    finished -> context.getString(R.string.watch_order_completed)
                    else -> ""
                }
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
            // after the colour: the check takes the label's colour
            watchOrderFinishedMark(holder.binding.watchOrderRelation, finished)
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
        items: List<MediaDetailsViewModel.NewsItem>,
        private val showNames: List<String>,
        /** The show's art, for entries whose page has no picture of its own. */
        private val fallbackImage: String?,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val now = System.currentTimeMillis()
        private val rows = newsRows(items, now)
        private val monthFormat = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault())

        inner class EntryHolder(val binding: ItemNewsBinding) : RecyclerView.ViewHolder(binding.root)
        inner class HeaderHolder(val binding: ItemNewsHeaderBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int) = if (rows[position] is NewsRow.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(ItemNewsHeaderBinding.inflate(inflater, parent, false))
            } else {
                EntryHolder(ItemNewsBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is NewsRow.Header -> (holder as HeaderHolder).binding.newsHeader.text = headerText(row)
                is NewsRow.Entry -> bindEntry((holder as EntryHolder).binding, row.item)
            }
        }

        private fun headerText(row: NewsRow.Header): String = when {
            row.month == null -> getString(R.string.news_this_week)
            row.year < 0 -> getString(R.string.news_undated)
            else -> {
                val cal = java.util.Calendar.getInstance().apply {
                    clear(); set(row.year, row.month, 1)
                }
                monthFormat.format(cal.time).replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
            }
        }

        private fun bindEntry(b: ItemNewsBinding, item: MediaDetailsViewModel.NewsItem) {
            val title = newsTitleWithoutShow(Parser.unescapeEntities(item.title, false), showNames)
            b.newsTitle.text = title
            val site = newsSite(item.url)
            val date = item.date?.time?.let { t ->
                if (now - t < 7L * 24 * 60 * 60 * 1000) {
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        t, now, android.text.format.DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                } else {
                    android.text.format.DateUtils.formatDateTime(
                        b.root.context, t,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH
                    )
                }
            }
            b.newsMeta.text = listOfNotNull(site.ifEmpty { null }, date).joinToString(" · ")

            val kind = newsKind(item.url)
            val videoId = if (kind == NewsKind.VIDEO) youtubeId(item.url) else null
            b.newsKindIcon.setImageResource(
                when (kind) {
                    NewsKind.VIDEO -> R.drawable.ic_round_play_arrow_24
                    NewsKind.POST -> R.drawable.ic_round_comment_24
                    NewsKind.ARTICLE -> R.drawable.ic_round_article_24
                }
            )
            if (videoId != null) {
                b.newsPlay.setImageResource(R.drawable.ic_round_play_arrow_24)
                b.newsThumb.visibility = View.VISIBLE
                b.newsPlay.visibility = View.VISIBLE
                b.newsKindIcon.visibility = View.GONE
                b.newsThumb.loadImage("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
            } else {
                b.newsPlay.setImageResource(
                    if (kind == NewsKind.POST) R.drawable.ic_round_comment_24 else R.drawable.ic_round_article_24
                )
                if (fallbackImage != null) {
                    // the show's art with the kind mark on top, until the page's own picture loads
                    b.newsThumb.visibility = View.VISIBLE
                    b.newsPlay.visibility = View.VISIBLE
                    b.newsKindIcon.visibility = View.GONE
                    b.newsThumb.loadImage(fallbackImage)
                } else {
                    b.newsThumb.setImageDrawable(null)
                    b.newsThumb.visibility = View.GONE
                    b.newsPlay.visibility = View.GONE
                    b.newsKindIcon.visibility = View.VISIBLE
                }
                // the page's own preview picture, when it has one; the icon stays otherwise
                b.root.tag = item.url
                val known = NewsPreviews.cached(item.url)
                if (known != null) {
                    if (known.isNotEmpty()) showPreview(b, known)
                } else if (item.url.startsWith("http")) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val img = withContext(Dispatchers.IO) { NewsPreviews.fetch(item.url) }
                        if (img != null && b.root.tag == item.url) showPreview(b, img)
                    }
                }
            }

            b.newsRow.contentDescription = listOf(title, b.newsMeta.text).joinToString(", ")
            b.newsRow.setOnClickListener {
                if (item.url.isNotEmpty()) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                }
            }
        }

        private fun showPreview(b: ItemNewsBinding, image: String) {
            b.newsThumb.visibility = View.VISIBLE
            b.newsKindIcon.visibility = View.GONE
            b.newsPlay.visibility = View.GONE
            b.newsThumb.loadImage(image)
        }

        override fun getItemCount() = rows.size
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MODE = "arg_mode"
        private const val ARG_ITEMS = "arg_items"

        const val MODE_WATCH_ORDER = "watch_order"
        const val MODE_NEWS = "news"

        fun newWatchOrderInstance(media: Media): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, MODE_WATCH_ORDER)
                }
                startMedia = media
            }
        }

        fun newNewsInstance(media: Media): MediaContentBottomSheet {
            return MediaContentBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, MODE_NEWS)
                }
                startMedia = media
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

/** A small check leading the row's label when the entry is finished; read out as "completed". */
private fun watchOrderFinishedMark(label: android.widget.TextView, finished: Boolean) {
    val ctx = label.context
    val density = ctx.resources.displayMetrics.density
    // a filled badge, not a bare tick: it reads as a status mark at small sizes
    val icon = if (finished) androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_round_check_circle_24)?.mutate()?.apply {
        val size = (14 * density).toInt()
        setBounds(0, 0, size, size)
        setTint(label.currentTextColor)
    } else null
    label.setCompoundDrawablesRelative(icon, null, null, null)
    label.compoundDrawablePadding = (4 * density).toInt()
    val completed = ctx.getString(R.string.watch_order_completed)
    label.contentDescription = if (finished && label.text.toString() != completed) {
        ctx.getString(R.string.watch_order_finished_cd, label.text)
    } else null
}

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
