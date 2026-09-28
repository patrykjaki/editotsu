package ani.dantotsu.forum

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.ForumThread
import ani.dantotsu.connections.anilist.api.typedChildren
import ani.dantotsu.databinding.ActivityThreadViewBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.activity.ActivityItemBuilder
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.AniMarkdown
import ani.dantotsu.openLinkInCustomTab
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadViewBinding
    private val commentsAdapter = GroupieAdapter()
    private val gate = ForumPagedGate()
    private var threadId: Int = -1
    private var thread: ForumThread? = null
    private var isSubscribed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityThreadViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.threadAppBar.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.threadReplyFab.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        threadId = intent.getIntExtra("threadId", -1)
        if (threadId == -1) {
            finish()
            return
        }

        binding.threadBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.commentsRecyclerView.adapter = commentsAdapter
        binding.commentsRecyclerView.layoutManager = LinearLayoutManager(this)

        // MAX MERGE M2 correction (Blocker 1): the comments list lives
        // inside a NestedScrollView, so pagination observes the outer
        // scroll surface near the bottom. The gate serializes page
        // requests (no duplicates) across refresh/pagination races.
        binding.threadScrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY <= oldScrollY) return@setOnScrollChangeListener
            val view = binding.threadScrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            if (scrollY + binding.threadScrollView.height >= view.height - 600) {
                loadComments(false)
            }
        }

        binding.threadReplyFab.visibility =
            if (Anilist.token != null) View.VISIBLE else View.GONE
        binding.threadReplyFab.setOnClickListener {
            startActivity(
                Intent(this, ActivityMarkdownCreator::class.java).apply {
                    putExtra("type", "threadComment")
                    putExtra("threadId", threadId)
                }
            )
        }

        // Blocker 3: mutation-only controls never advertise while logged
        // out (subscribe + like hidden without a session).
        binding.threadSubscribe.visibility =
            if (ForumAuthPolicy.canMutate(Anilist.token)) View.VISIBLE else View.GONE

        binding.threadSubscribe.setOnClickListener {
            if (!ForumAuthPolicy.canMutate(Anilist.token)) return@setOnClickListener
            val newSub = !isSubscribed
            lifecycleScope.launch(Dispatchers.IO) {
                val success = Anilist.mutation.toggleThreadSubscription(threadId, newSub)
                withContext(Dispatchers.Main) {
                    if (success) {
                        isSubscribed = newSub
                        binding.threadSubscribe.alpha = if (isSubscribed) 1.0f else 0.5f
                        snackString(if (isSubscribed) "Subscribed to thread" else "Unsubscribed from thread")
                    } else {
                        snackString("Failed to update thread subscription")
                    }
                }
            }
        }

        loadThreadDetails()
        loadComments(true)
    }

    override fun onResume() {
        super.onResume()
        if (thread != null) {
            loadComments(true)
        }
    }

    private fun loadThreadDetails() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = Anilist.query.getThreadDetails(threadId)
            val t = res?.data?.thread
            withContext(Dispatchers.Main) {
                if (t != null) {
                    thread = t
                    bindThread(t)
                }
            }
        }
    }

    private fun bindThread(t: ForumThread) {
        binding.threadBarTitle.text = t.title ?: ""
        binding.threadViewTitle.text = t.title ?: ""
        binding.threadAuthorAvatar.loadImage(t.user?.avatar?.medium)
        binding.threadAuthorName.text = t.user?.name ?: "Unknown"
        binding.threadTime.text = t.createdAt?.let { ActivityItemBuilder.getDateTime(it) } ?: ""

        isSubscribed = t.isSubscribed ?: false
        binding.threadSubscribe.alpha = if (isSubscribed) 1.0f else 0.5f

        val likeColor = ContextCompat.getColor(this, R.color.yt_red)
        val notLikeColor = ContextCompat.getColor(this, R.color.bg_opp)
        binding.threadLikeIcon.setColorFilter(if (t.isLiked == true) likeColor else notLikeColor)
        binding.threadLikeCount.text = (t.likeCount ?: 0).toString()

        // Blocker 3: no session → no like action (count stays visible).
        binding.threadLikeButton.visibility =
            if (ForumAuthPolicy.canMutate(Anilist.token)) View.VISIBLE else View.GONE
        binding.threadLikeButton.setOnClickListener {
            if (!ForumAuthPolicy.canMutate(Anilist.token)) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                val success = Anilist.mutation.toggleLike(t.id, "THREAD") != null
                withContext(Dispatchers.Main) {
                    if (success) {
                        t.isLiked = !(t.isLiked ?: false)
                        val count = t.likeCount ?: 0
                        t.likeCount = if (t.isLiked == true) count + 1 else (count - 1).coerceAtLeast(0)
                        binding.threadLikeCount.text = (t.likeCount ?: 0).toString()
                        binding.threadLikeIcon.setColorFilter(if (t.isLiked == true) likeColor else notLikeColor)
                    }
                }
            }
        }

        // Delete stays owner-specific: a session AND an owner match are
        // both required (a null viewer never equals an owner).
        if (ForumAuthPolicy.deleteVisibleFor(t.userId, Anilist.userid, Anilist.token)) {
            binding.threadDelete.visibility = View.VISIBLE
            binding.threadDelete.setOnClickListener {
                customAlertDialog().apply {
                    setTitle(R.string.delete)
                    setMessage(R.string.delete_thread_confirm)
                    setPosButton(R.string.delete) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = Anilist.mutation.deleteThread(t.id)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    snackString("Thread deleted")
                                    finish()
                                } else {
                                    snackString("Failed to delete thread")
                                }
                            }
                        }
                    }
                    setNegButton(R.string.cancel)
                    show()
                }
            }
        } else {
            binding.threadDelete.visibility = View.GONE
        }

        binding.threadWebView.settings.apply {
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.threadWebView.webChromeClient = android.webkit.WebChromeClient()
        binding.threadWebView.setInitialScale(1)
        binding.threadWebView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                android.R.color.transparent
            )
        )
        binding.threadWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.threadWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.threadWebView.setBackgroundColor(
                    ContextCompat.getColor(
                        this@ThreadViewActivity,
                        android.R.color.transparent
                    )
                )
            }

            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://www.youtube.com/embed") ||
                    url.startsWith("https://www.youtube-nocookie.com/embed") ||
                    url.startsWith("about:blank")
                ) {
                    return false
                }
                openLinkInCustomTab(url)
                return true
            }
        }
        val styledHtml = AniMarkdown.getFullAniHTML(
            t.body ?: "",
            ContextCompat.getColor(this, R.color.bg_opp)
        )
        binding.threadWebView.loadDataWithBaseURL("https://www.youtube-nocookie.com", styledHtml, "text/html", "UTF-8", null)
    }

    private fun loadComments(refresh: Boolean) {
        // Latest-wins + serialized pagination (Blockers 1+4): a refresh
        // retires any in-flight older page; stale results apply nothing.
        val generation: Int
        val page: Int
        if (refresh) {
            generation = gate.startRefresh()
            page = 1
            binding.commentsProgressBar.visibility = View.VISIBLE
        } else {
            val next = gate.requestNext() ?: return
            generation = next.generation
            page = next.page
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val res = try {
                Anilist.query.getThreadComments(threadId, page)
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    binding.commentsProgressBar.visibility = View.GONE
                    gate.fail(generation)
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (refresh) {
                    binding.commentsProgressBar.visibility = View.GONE
                }
                val comments = res?.data?.page?.threadComments ?: emptyList()
                val hasNext = res?.data?.page?.pageInfo?.hasNextPage ?: false
                if (!gate.finish(generation, page, hasNext)) return@withContext

                if (refresh) {
                    commentsAdapter.clear()
                }

                if (comments.isNotEmpty()) {
                    commentsAdapter.addAll(flattenThreadComments(comments))
                }
            }
        }
    }

    /**
     * Blocker 2: every nested reply becomes a visible item. Top-level
     * comments render at depth 0, their typed children recurse below them
     * in served order with full user/body/like/identity. A refresh rebuilds
     * the whole list, so children can never duplicate.
     */
    private fun flattenThreadComments(
        comments: List<ani.dantotsu.connections.anilist.api.ThreadComment>
    ): List<ThreadCommentItem> {
        return ani.dantotsu.connections.anilist.api.flattenThreadCommentTree(comments).map { flat ->
            ThreadCommentItem(flat.comment, threadId, this, commentsAdapter, lifecycleScope, flat.depth)
        }
    }
}
