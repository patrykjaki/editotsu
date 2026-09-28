package ani.dantotsu.forum

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.ThreadComment
import ani.dantotsu.databinding.ItemThreadCommentBinding
import ani.dantotsu.loadImage
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.snackString
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.AniMarkdown
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadCommentItem(
    val comment: ThreadComment,
    val threadId: Int,
    val context: Context,
    val parentAdapter: GroupieAdapter,
    val scope: CoroutineScope,
    val depth: Int = 0
) : BindableItem<ItemThreadCommentBinding>() {

    override fun bind(viewBinding: ItemThreadCommentBinding, position: Int) {
        // Indentation is unconditional (including zero): recycled views
        // must never retain a stale child margin.
        viewBinding.root.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            marginStart = indentForDepth(depth, viewBinding.root.resources.displayMetrics.density)
        }
        viewBinding.commentAvatar.loadImage(comment.user?.avatar?.medium)
        viewBinding.commentAuthor.text = comment.user?.name ?: "Unknown"
        viewBinding.commentTime.text = comment.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""
        val markwon = buildMarkwon(context, false, anilist = true)
        val html = AniMarkdown.getBasicAniHTML(comment.comment ?: "")
        markwon.setMarkdown(viewBinding.commentBody, html)

        val likeColor = ContextCompat.getColor(context, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(context, R.color.bg_opp)
        viewBinding.commentLikeIcon.setColorFilter(if (comment.isLiked == true) likeColor else notLikeColor)
        viewBinding.commentLikeCount.text = (comment.likeCount ?: 0).toString()

        // Blocker 3: mutation-only controls hidden without a session; the
        // click path can never dispatch while logged out.
        val authed = ForumAuthPolicy.canMutate(Anilist.token)
        viewBinding.commentLikeButton.visibility = if (authed) View.VISIBLE else View.GONE
        viewBinding.commentReplyButton.visibility = if (authed) View.VISIBLE else View.GONE
        viewBinding.commentLikeButton.setOnClickListener {
            if (!ForumAuthPolicy.canMutate(Anilist.token)) return@setOnClickListener
            scope.launch(Dispatchers.IO) {
                val success = Anilist.mutation.toggleLike(comment.id, "THREAD_COMMENT") != null
                withContext(Dispatchers.Main) {
                    if (success) {
                        comment.isLiked = !(comment.isLiked ?: false)
                        val count = comment.likeCount ?: 0
                        comment.likeCount = if (comment.isLiked == true) count + 1 else (count - 1).coerceAtLeast(0)
                        viewBinding.commentLikeCount.text = (comment.likeCount ?: 0).toString()
                        viewBinding.commentLikeIcon.setColorFilter(if (comment.isLiked == true) likeColor else notLikeColor)
                    }
                }
            }
        }

        viewBinding.commentDelete.visibility =
            if (ForumAuthPolicy.deleteVisibleFor(comment.userId, Anilist.userid, Anilist.token)) View.VISIBLE else View.GONE
        viewBinding.commentDelete.setOnClickListener {
            context.customAlertDialog().apply {
                setTitle(R.string.delete)
                setMessage(R.string.delete_comment_confirm)
                setPosButton(R.string.delete) {
                    scope.launch(Dispatchers.IO) {
                        val success = Anilist.mutation.deleteThreadComment(comment.id)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                snackString("Comment deleted")
                                // The flattened descendants of this comment
                                // must not orphan: remove the whole rendered
                                // subtree, not just the parent row.
                                removeRenderedSubtree()
                            } else {
                                snackString("Failed to delete comment")
                            }
                        }
                    }
                }
                setNegButton(R.string.cancel)
                show()
            }
        }

        viewBinding.commentReplyButton.setOnClickListener {
            if (!ForumAuthPolicy.canMutate(Anilist.token)) return@setOnClickListener
            context.startActivity(
                Intent(context, ActivityMarkdownCreator::class.java).apply {
                    putExtra("type", "threadComment")
                    putExtra("threadId", threadId)
                    putExtra("parentId", comment.id)
                    putExtra("other", "@${comment.user?.name} ")
                }
            )
        }
    }

    /**
     * Removes exactly the rendered rows of this comment's subtree
     * ([pos, end)) so flattened descendants never orphan after delete.
     */
    private fun removeRenderedSubtree() {
        val pos = parentAdapter.getAdapterPosition(this@ThreadCommentItem)
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val depths = (0 until parentAdapter.itemCount).map { i ->
            (parentAdapter.getItem(i) as? ThreadCommentItem)?.depth ?: -1
        }
        val end = threadSubtreeEnd(depths, pos)
        for (i in end - 1 downTo pos) {
            parentAdapter.remove(parentAdapter.getItem(i))
        }
    }

    override fun getLayout(): Int = R.layout.item_thread_comment

    override fun initializeViewBinding(view: View): ItemThreadCommentBinding =
        ItemThreadCommentBinding.bind(view)
}
