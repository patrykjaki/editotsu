package ani.dantotsu.forum

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityForumBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.ActivityMarkdownCreator
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ForumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForumBinding
    private val adapter = GroupieAdapter()
    private val gate = ForumPagedGate()
    private var selectedCategoryId: Int? = null
    private var currentSearch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityForumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.forumAppBar.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.forumRecyclerView.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        binding.forumBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.forumRecyclerView.adapter = adapter
        binding.forumRecyclerView.layoutManager = LinearLayoutManager(this)

        binding.forumRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                    loadThreads(false)
                }
            }
        })

        binding.forumSwipeRefresh.setOnRefreshListener {
            loadThreads(true)
        }

        binding.forumCategoryChips.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategoryId = when (checkedIds.firstOrNull()) {
                R.id.chipGeneral -> 1
                R.id.chipAnime -> 2
                R.id.chipManga -> 3
                R.id.chipRelease -> 4
                R.id.chipNews -> 5
                else -> null
            }
            loadThreads(true)
        }

        binding.forumSearch.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Search threads..."
                setText(currentSearch ?: "")
            }
            customAlertDialog().apply {
                setTitle(R.string.search)
                setCustomView(input)
                setPosButton(R.string.search) {
                    currentSearch = input.text.toString().trim().ifBlank { null }
                    loadThreads(true)
                }
                setNeutralButton("Clear") {
                    currentSearch = null
                    loadThreads(true)
                }
                setNegButton(R.string.cancel)
                show()
            }
        }

        binding.forumCreateThreadFab.visibility =
            if (Anilist.token != null) View.VISIBLE else View.GONE
        binding.forumCreateThreadFab.setOnClickListener {
            startActivity(
                Intent(this, ActivityMarkdownCreator::class.java).apply {
                    putExtra("type", "thread")
                    putIntegerArrayListExtra(
                        "categories",
                        arrayListOf(selectedCategoryId ?: 1)
                    )
                }
            )
        }

        loadThreads(true)
    }

    override fun onResume() {
        super.onResume()
        if (adapter.itemCount == 0) {
            loadThreads(true)
        }
    }

    private fun loadThreads(refresh: Boolean) {
        // MAX MERGE M2 correction (Blocker 4): latest-state-wins. A refresh
        // bumps the generation so any in-flight older request is discarded
        // on arrival instead of painting stale rows; pagination stays
        // serialized through the gate (no duplicate same-page requests).
        val generation: Int
        val page: Int
        if (refresh) {
            generation = gate.startRefresh()
            page = 1
            binding.forumProgressBar.visibility = View.VISIBLE
            binding.forumEmptyText.visibility = View.GONE
        } else {
            val next = gate.requestNext() ?: return
            generation = next.generation
            page = next.page
        }
        val category = selectedCategoryId
        val search = currentSearch

        lifecycleScope.launch(Dispatchers.IO) {
            val res = try {
                Anilist.query.getThreads(
                    categoryId = category,
                    search = search,
                    page = page
                )
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { gate.fail(generation) }
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.forumProgressBar.visibility = View.GONE
                binding.forumSwipeRefresh.isRefreshing = false

                val threads = res?.data?.page?.threads ?: emptyList()
                val hasNext = res?.data?.page?.pageInfo?.hasNextPage ?: false
                if (!gate.finish(generation, page, hasNext)) return@withContext

                if (refresh) {
                    adapter.clear()
                }

                if (threads.isNotEmpty()) {
                    val items = threads.map { ForumThreadItem(it, this@ForumActivity) }
                    adapter.addAll(items)
                }

                binding.forumEmptyText.visibility =
                    if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        }
    }
}
