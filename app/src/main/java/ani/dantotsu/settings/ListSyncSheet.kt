package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.mal.ListSyncWorker
import ani.dantotsu.connections.mal.MalListSync
import ani.dantotsu.connections.mal.MalListSync.Side
import ani.dantotsu.connections.mal.MalListSync.What
import ani.dantotsu.databinding.BottomSheetListSyncBinding
import ani.dantotsu.databinding.ItemListSyncChangeBinding
import com.google.android.material.checkbox.MaterialCheckBox
import ani.dantotsu.loadImage
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * List sync: pick the direction, see every entry the other list would get and what changes on
 * it, untick what should stay as it is, then write the rest. Nothing is written before the
 * button is pressed.
 */
class ListSyncSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetListSyncBinding? = null
    private val binding get() = _binding!!
    private var main = Side.ANILIST
    private var changes: List<MalListSync.Change> = emptyList()
    private val skipped = HashSet<MalListSync.Change>()
    private var writing = false
    private var retrying = false
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetListSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        main = if (PrefManager.getVal<Int>(PrefName.ListSyncMain) == 1) Side.MAL else Side.ANILIST
        binding.listSyncChanges.layoutManager = LinearLayoutManager(requireContext())
        binding.listSyncSwap.setOnClickListener {
            if (writing) return@setOnClickListener
            main = other
            PrefManager.setVal(PrefName.ListSyncMain, if (main == Side.MAL) 1 else 0)
            compare()
        }
        binding.listSyncAuto.isChecked = PrefManager.getVal(PrefName.ListSyncAuto)
        binding.listSyncAuto.setOnCheckedChangeListener { _, on ->
            PrefManager.setVal(PrefName.ListSyncAuto, on)
            ListSyncWorker.apply(requireContext().applicationContext)
        }
        binding.listSyncAutoRow.setOnClickListener { binding.listSyncAuto.toggle() }
        binding.listSyncSelect.setOnClickListener {
            if (writing) return@setOnClickListener
            if (skipped.isEmpty()) skipped.addAll(changes) else skipped.clear()
            binding.listSyncChanges.adapter?.notifyDataSetChanged()
            showSelection()
        }
        binding.listSyncApply.setOnClickListener { write() }
        // the button stays clear of the gesture bar
        val base = binding.listSyncRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.listSyncRoot) { v, insets ->
            v.updatePadding(bottom = base + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        compare()
    }

    override fun onStart() {
        super.onStart()
        // the list and the button are the point of the sheet: open at full height
        (view?.parent as? View)?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun name(side: Side) = getString(if (side == Side.MAL) R.string.myanimelist else R.string.anilist)
    private val other get() = if (main == Side.MAL) Side.ANILIST else Side.MAL
    private val selected get() = changes.filterNot { it in skipped }

    private fun compare() {
        job?.cancel()
        retrying = false
        binding.listSyncFrom.text = name(main)
        binding.listSyncTo.text = name(other)
        binding.listSyncExplain.text = getString(R.string.list_sync_explain, name(other), name(main))
        binding.listSyncStatus.setText(R.string.list_sync_comparing)
        binding.listSyncApply.visibility = View.GONE
        binding.listSyncSelect.visibility = View.GONE
        binding.listSyncLoading.visibility = View.VISIBLE
        show(emptyList())
        job = viewLifecycleOwner.lifecycleScope.launch {
            val side = main
            val loaded = withContext(Dispatchers.IO) {
                val anime = MalListSync.load(true, side)
                val manga = MalListSync.load(false, side)
                if (anime == null || manga == null) null else anime + manga
            }
            if (_binding == null) return@launch
            binding.listSyncLoading.visibility = View.GONE
            if (loaded == null) {
                binding.listSyncStatus.setText(R.string.list_sync_read_failed)
                return@launch
            }
            show(loaded)
            if (loaded.isEmpty()) {
                binding.listSyncStatus.text = getString(R.string.list_sync_same, name(other), name(main))
            } else {
                binding.listSyncSelect.visibility = View.VISIBLE
                binding.listSyncApply.visibility = View.VISIBLE
                showSelection()
            }
        }
    }

    /** the count lives on the selection line; the header checkbox is all, none or some of the rows */
    private fun showSelection() {
        val count = selected.size
        val total = changes.size
        if (!retrying) binding.listSyncStatus.text = getString(R.string.list_sync_selected, count, total)
        binding.listSyncSelect.checkedState = when {
            skipped.isEmpty() -> MaterialCheckBox.STATE_CHECKED
            count == 0 -> MaterialCheckBox.STATE_UNCHECKED
            else -> MaterialCheckBox.STATE_INDETERMINATE
        }
        binding.listSyncApply.isEnabled = count > 0 && !writing
        binding.listSyncApply.text = when {
            count == 0 -> getString(R.string.list_sync_nothing_selected)
            retrying -> getString(R.string.list_sync_retry)
            else -> getString(R.string.list_sync_update, name(other))
        }
    }

    private fun write() {
        val side = main
        val todo = selected
        if (todo.isEmpty()) return
        writing = true
        binding.listSyncApply.isEnabled = false
        binding.listSyncSelect.visibility = View.GONE
        isCancelable = false
        job = viewLifecycleOwner.lifecycleScope.launch {
            val failed = ArrayList<MalListSync.Change>()
            todo.forEachIndexed { i, change ->
                binding.listSyncStatus.text = getString(R.string.list_sync_progress, i + 1, todo.size)
                val ok = withContext(Dispatchers.IO) { MalListSync.write(change, side) }
                if (!ok) failed += change
                if (_binding == null) return@launch
            }
            writing = false
            isCancelable = true
            show(failed)
            if (failed.isEmpty()) {
                binding.listSyncStatus.text = getString(R.string.list_sync_done, todo.size, name(other))
                binding.listSyncApply.visibility = View.GONE
            } else {
                retrying = true
                binding.listSyncStatus.text =
                    getString(R.string.list_sync_done_failed, todo.size - failed.size, failed.size)
                showSelection()
            }
        }
    }

    private fun show(list: List<MalListSync.Change>) {
        changes = list
        skipped.clear()
        binding.listSyncChanges.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val missing = list.filter { it.target == null }
        val different = list.filter { it.target != null }
        val rows = ArrayList<Any>()
        if (missing.isNotEmpty()) {
            rows.add(getString(R.string.list_sync_group_missing, name(other), missing.size))
            rows.addAll(missing)
        }
        if (different.isNotEmpty()) {
            rows.add(getString(R.string.list_sync_group_changed, different.size))
            rows.addAll(different)
        }
        binding.listSyncChanges.adapter = Adapter(rows)
    }

    private fun status(status: String, isAnime: Boolean) = getString(
        when (status) {
            "CURRENT" -> if (isAnime) R.string.list_sync_watching else R.string.list_sync_reading
            "PLANNING" -> R.string.list_sync_planning
            "COMPLETED" -> R.string.list_sync_completed
            "PAUSED" -> R.string.list_sync_paused
            "DROPPED" -> R.string.list_sync_dropped
            else -> if (isAnime) R.string.list_sync_rewatching else R.string.list_sync_rereading
        },
    )

    private fun detail(change: MalListSync.Change): String {
        if (change.target == null) {
            return getString(R.string.list_sync_missing, status(change.main.status, change.isAnime), change.progress)
        }
        return change.fields.joinToString(" · ") { f ->
            val (from, to) = if (f.what == What.STATUS) status(f.from, change.isAnime) to status(f.to, change.isAnime)
            else f.from to f.to
            val label = getString(
                when (f.what) {
                    What.STATUS -> R.string.list_sync_field_status
                    What.PROGRESS -> R.string.list_sync_field_progress
                    What.SCORE -> R.string.list_sync_field_score
                    What.START -> R.string.list_sync_field_start
                    What.END -> R.string.list_sync_field_end
                },
            )
            getString(R.string.list_sync_field, label, from, to)
        }
    }

    /** [rows] holds section titles (String) and entries (Change), in display order */
    private inner class Adapter(private val rows: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class Row(val b: ItemListSyncChangeBinding) : RecyclerView.ViewHolder(b.root)
        inner class Header(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) Header(inflater.inflate(R.layout.item_list_sync_header, parent, false) as TextView)
            else Row(ItemListSyncChangeBinding.inflate(inflater, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (holder is Header) {
                holder.text.text = row as String
                return
            }
            val change = row as MalListSync.Change
            val b = (holder as Row).b
            b.listSyncTitle.text = change.main.title
            b.listSyncDetail.text = detail(change)
            b.listSyncCover.loadImage(change.main.cover ?: change.target?.cover)
            b.listSyncCheck.isChecked = change !in skipped
            b.root.contentDescription = "${change.main.title}, ${b.listSyncDetail.text}"
            b.root.setOnClickListener {
                if (writing) return@setOnClickListener
                if (!skipped.remove(change)) skipped += change
                b.listSyncCheck.isChecked = change !in skipped
                showSelection()
            }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        _binding = null
        super.onDestroyView()
    }
}
