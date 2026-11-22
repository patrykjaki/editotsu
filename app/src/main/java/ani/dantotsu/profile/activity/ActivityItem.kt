package ani.dantotsu.profile.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Activity
import ani.dantotsu.databinding.ItemActivityBinding
import ani.dantotsu.home.status.AnilistLinkPreviewView
import ani.dantotsu.loadImage
import ani.dantotsu.profile.User
import ani.dantotsu.profile.UsersDialogFragment
import ani.dantotsu.setAnimation
import ani.dantotsu.snackString
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.AniMarkdown.Companion.getBasicAniHTML
import ani.dantotsu.util.AnilistLinkParser
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.color.MaterialColors
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ActivityItem(
    private val activity: Activity,
    private val parentAdapter: GroupieAdapter,
    val clickCallback: (Int, type: String) -> Unit,
) : BindableItem<ItemActivityBinding>() {
    private lateinit var binding: ItemActivityBinding
    private var previewJob: Job? = null

    override fun bind(viewBinding: ItemActivityBinding, position: Int) {
        binding = viewBinding
        // Cancel any in-flight preview fetch from a previous bind (recycled view)
        previewJob?.cancel()
        previewJob = null
        binding.activityLinkPreviewContainer.removeAllViews()
        binding.activityLinkPreviewContainer.visibility = View.GONE
        val context = binding.root.context
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        setAnimation(binding.root.context, binding.root)

        val primaryColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
        val variantColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val likeColor = ContextCompat.getColor(context, R.color.yt_red)

        binding.activityUserName.text = activity.user?.name ?: activity.messenger?.name
        binding.activityUserAvatar.loadImage(
            activity.user?.avatar?.medium ?: activity.messenger?.avatar?.medium
        )
        binding.activityTime.text = ActivityItemBuilder.getDateTime(activity.createdAt)

        fun renderLikeState() {
            val liked = activity.isLiked == true
            binding.activityLike.setImageResource(
                if (liked) R.drawable.ic_round_favorite_24 else R.drawable.ic_round_favorite_border_24
            )
            ImageViewCompat.setImageTintList(
                binding.activityLike,
                ColorStateList.valueOf(if (liked) likeColor else variantColor)
            )
            binding.activityLikeCount.text = (activity.likeCount ?: 0).toString()
        }

        renderLikeState()

        val userList = arrayListOf<User>()
        activity.likes?.forEach { i ->
            userList.add(
                User(
                    i.id,
                    i.name.toString(),
                    i.avatar?.medium,
                    i.bannerImage,
                    isFollowing = i.isFollowing,
                    isFollower = i.isFollower
                )
            )
        }
        binding.activityRepliesContainer.setOnClickListener {
            RepliesBottomDialog.newInstance(activity.id)
                .show((context as FragmentActivity).supportFragmentManager, "replies")
        }
        binding.replyCount.text = activity.replyCount.toString()
        ImageViewCompat.setImageTintList(binding.activityReplies, ColorStateList.valueOf(variantColor))

        binding.activityLikeContainer.setOnLongClickListener {
            UsersDialogFragment().apply {
                userList(userList)
                show((context as FragmentActivity).supportFragmentManager, "dialog")
            }
            true
        }
        binding.activityLikeContainer.setOnClickListener {
            scope.launch {
                val res = Anilist.mutation.toggleLike(activity.id, "ACTIVITY")
                withContext(Dispatchers.Main) {
                    if (res != null) {
                        if (activity.isLiked == true) {
                            activity.likeCount = activity.likeCount?.minus(1)
                        } else {
                            activity.likeCount = activity.likeCount?.plus(1)
                        }
                        activity.isLiked = !(activity.isLiked ?: false)
                        renderLikeState()
                    } else {
                        snackString(R.string.activity_like_failed)
                    }
                }
            }
        }

        ImageViewCompat.setImageTintList(binding.activityEdit, ColorStateList.valueOf(variantColor))
        ImageViewCompat.setImageTintList(binding.activityDelete, ColorStateList.valueOf(variantColor))

        binding.activityDelete.isVisible =
            activity.userId == Anilist.userid ||
            activity.messenger?.id == Anilist.userid ||
            activity.recipientId == Anilist.userid ||
            activity.recipient?.id == Anilist.userid
        binding.activityDelete.setOnClickListener {
            binding.root.context.customAlertDialog().apply {
                setTitle(R.string.delete)
                setMessage(binding.root.context.getString(R.string.delete_activity_confirm))
                setPosButton(R.string.delete) {
                    scope.launch {
                        val res = Anilist.mutation.deleteActivity(activity.id)
                        withContext(Dispatchers.Main) {
                            if (res) {
                                snackString(R.string.activity_deleted)
                                parentAdapter.remove(this@ActivityItem)
                            } else {
                                snackString(R.string.activity_delete_failed)
                            }
                        }
                    }
                }
                setNegButton(R.string.cancel)
                show()
            }
        }

        fun renderPinState() {
            val pinned = activity.isPinned == true
            val label = context.getString(if (pinned) R.string.feed_unpin else R.string.feed_pin)
            binding.activityPin.contentDescription = label
            binding.activityPin.tooltipText = label
            binding.activityPin.alpha = if (pinned) 1f else 0.7f
            ImageViewCompat.setImageTintList(
                binding.activityPin,
                ColorStateList.valueOf(if (pinned) primaryColor else variantColor)
            )
        }

        binding.activityPin.isVisible = activity.userId == Anilist.userid
        renderPinState()
        binding.activityPin.setOnClickListener {
            scope.launch {
                val newPinned = !(activity.isPinned ?: false)
                val success = Anilist.mutation.toggleActivityPin(activity.id, newPinned)
                withContext(Dispatchers.Main) {
                    if (success) {
                        activity.isPinned = newPinned
                        renderPinState()
                        snackString(if (newPinned) "Pinned activity" else "Unpinned activity")
                    } else {
                        snackString(R.string.activity_pin_failed)
                    }
                }
            }
        }

        fun renderSubscribeState() {
            val sub = activity.isSubscribed == true
            val label = context.getString(if (sub) R.string.feed_unsubscribe else R.string.feed_subscribe)
            binding.activitySubscribe.setImageResource(
                if (sub) R.drawable.ic_round_notifications_active_24
                else R.drawable.ic_round_notifications_none_24
            )
            binding.activitySubscribe.contentDescription = label
            binding.activitySubscribe.tooltipText = label
            ImageViewCompat.setImageTintList(
                binding.activitySubscribe,
                ColorStateList.valueOf(if (sub) primaryColor else variantColor)
            )
        }

        binding.activitySubscribe.isVisible = Anilist.token != null
        renderSubscribeState()
        binding.activitySubscribe.setOnClickListener {
            scope.launch {
                val newSub = !(activity.isSubscribed ?: false)
                val success = Anilist.mutation.toggleActivitySubscription(activity.id, newSub)
                withContext(Dispatchers.Main) {
                    if (success) {
                        activity.isSubscribed = newSub
                        renderSubscribeState()
                        snackString(if (newSub) "Subscribed to activity" else "Unsubscribed from activity")
                    } else {
                        snackString(R.string.activity_subscription_failed)
                    }
                }
            }
        }

        when (activity.typename) {
            "ListActivity" -> {
                val cover = activity.media?.coverImage?.large
                val banner = activity.media?.bannerImage
                binding.activityContent.visibility = View.GONE
                binding.activityBannerContainer.visibility = View.VISIBLE
                binding.activityPrivate.visibility = View.GONE
                binding.activityMediaName.text = activity.media?.title?.userPreferred
                val statusText = activity.status.orEmpty()
                    .trim()
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                binding.activityText.text = if (!activity.progress.isNullOrBlank()) {
                    "$statusText ${activity.progress}"
                } else {
                    statusText
                }
                binding.activityCover.loadImage(cover)
                blurImage(binding.activityBannerImage, banner ?: cover)
                binding.activityAvatarContainer.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityUserName.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityCoverContainer.setOnClickListener {
                    clickCallback(activity.media?.id ?: -1, "MEDIA")
                }
                binding.activityMediaName.setOnClickListener {
                    clickCallback(activity.media?.id ?: -1, "MEDIA")
                }
                binding.activityBannerContainer.setOnClickListener {
                    clickCallback(activity.media?.id ?: -1, "MEDIA")
                }
                binding.activityEdit.isVisible = false
            }

            "TextActivity" -> {
                binding.activityBannerContainer.visibility = View.GONE
                binding.activityContent.visibility = View.VISIBLE
                binding.activityPrivate.visibility = View.GONE
                if (!(context as android.app.Activity).isDestroyed) {
                    val rawText = activity.text ?: ""
                    val anilistLinks = AnilistLinkParser.extractAnilistLinks(rawText)
                    val html = getBasicAniHTML(rawText)
                    val cleanedHtml = AnilistLinkParser.removeAnilistUrlsFromHtml(html)
                    val markwon = buildMarkwon(context, false)
                    markwon.setMarkdown(binding.activityContent, cleanedHtml)
                    addLinkPreviews(anilistLinks, rawText)
                }
                binding.activityAvatarContainer.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityUserName.setOnClickListener {
                    clickCallback(activity.userId ?: -1, "USER")
                }
                binding.activityEdit.isVisible = activity.userId == Anilist.userid
                binding.activityEdit.setOnClickListener {
                    context.startActivity(
                        Intent(context, ActivityMarkdownCreator::class.java).apply {
                            putExtra("type", "activity")
                            putExtra("other", activity.text)
                            putExtra("edit", activity.id)
                        }
                    )
                }
            }

            "MessageActivity" -> {
                binding.activityBannerContainer.visibility = View.GONE
                binding.activityContent.visibility = View.VISIBLE
                binding.activityPrivate.visibility =
                    if (activity.isPrivate == true) View.VISIBLE else View.GONE
                if (!(context as android.app.Activity).isDestroyed) {
                    val rawMessage = activity.message ?: ""
                    val anilistLinks = AnilistLinkParser.extractAnilistLinks(rawMessage)
                    val html = getBasicAniHTML(rawMessage)
                    val cleanedHtml = AnilistLinkParser.removeAnilistUrlsFromHtml(html)
                    val markwon = buildMarkwon(context, false)
                    markwon.setMarkdown(binding.activityContent, cleanedHtml)
                    addLinkPreviews(anilistLinks, rawMessage)
                }
                binding.activityAvatarContainer.setOnClickListener {
                    clickCallback(activity.messengerId ?: -1, "USER")
                }
                binding.activityUserName.setOnClickListener {
                    clickCallback(activity.messengerId ?: -1, "USER")
                }
                binding.activityEdit.isVisible = activity.messenger?.id == Anilist.userid
                binding.activityEdit.setOnClickListener {
                    context.startActivity(
                        Intent(context, ActivityMarkdownCreator::class.java).apply {
                            putExtra("type", "message")
                            putExtra("other", activity.message)
                            putExtra("edit", activity.id)
                            putExtra("userId", activity.recipientId)
                        }
                    )
                }
            }
        }
    }

    private fun addLinkPreviews(links: List<AnilistLinkParser.AnilistLink>, originalText: String) {
        binding.activityLinkPreviewContainer.removeAllViews()
        if (links.isEmpty()) {
            binding.activityLinkPreviewContainer.visibility = View.GONE
            return
        }
        binding.activityLinkPreviewContainer.visibility = View.VISIBLE
        val mediaIds = links.map { it.id }.distinct()
        val fragmentActivity = binding.root.context as? FragmentActivity ?: return
        previewJob = fragmentActivity.lifecycleScope.launch {
            val mediaList = withContext(Dispatchers.IO) {
                Anilist.query.getMediaList(mediaIds)
            }
            if (!fragmentActivity.isDestroyed) {
                val mediaMap = mediaList?.associateBy { it.id } ?: emptyMap()
                if (mediaMap.isNotEmpty()) {
                    val titleMap = mediaMap.mapValues { (_, media) -> media.userPreferredName }
                    val htmlWithTitles = AnilistLinkParser.replaceAnilistUrlsInHtml(
                        getBasicAniHTML(originalText), titleMap
                    )
                    val markwon = buildMarkwon(fragmentActivity, false)
                    markwon.setMarkdown(binding.activityContent, htmlWithTitles)
                }
                binding.activityLinkPreviewContainer.removeAllViews()
                links.forEach { link ->
                    val media = mediaMap[link.id]
                    if (media != null) {
                        val previewView = AnilistLinkPreviewView(fragmentActivity)
                        previewView.setMediaData(media)
                        binding.activityLinkPreviewContainer.addView(previewView)
                    }
                }
                binding.activityLinkPreviewContainer.visibility =
                    if (binding.activityLinkPreviewContainer.isNotEmpty()) View.VISIBLE
                    else View.GONE
            }
        }
    }

    override fun getLayout(): Int = R.layout.item_activity

    override fun initializeViewBinding(view: View): ItemActivityBinding =
        ItemActivityBinding.bind(view)
}
