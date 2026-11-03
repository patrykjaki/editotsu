package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.databinding.ItemSettingsAdvancedBinding
import ani.dantotsu.databinding.ItemSettingsHeaderBinding
import ani.dantotsu.databinding.ItemSettingsSwitchBinding
import ani.dantotsu.setAnimation

class SettingsAdapter(
    private val settings: ArrayList<Settings>,
    var highlightKey: String? = null
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    inner class SettingsViewHolder(val binding: ItemSettingsBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsSwitchViewHolder(val binding: ItemSettingsSwitchBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsHeaderViewHolder(val binding: ItemSettingsHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsAdvancedViewHolder(val binding: ItemSettingsAdvancedBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> SettingsViewHolder(
                ItemSettingsBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            2 -> SettingsSwitchViewHolder(
                ItemSettingsSwitchBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            SettingsRowType.HEADER -> SettingsHeaderViewHolder(
                ItemSettingsHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            SettingsRowType.ADVANCED -> SettingsAdvancedViewHolder(
                ItemSettingsAdvancedBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            else -> SettingsViewHolder(
                ItemSettingsBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val settings = settings[position]
        when (settings.type) {
            1 -> {
                val b = (holder as SettingsViewHolder).binding
                setAnimation(b.root.context, b.root)

                if (highlightKey != null && settings.name.equals(highlightKey, ignoreCase = true)) {
                    b.root.alpha = 0.3f
                    b.root.animate().alpha(1f).setDuration(600).start()
                }

                b.settingsTitle.text = settings.name
                b.settingsDesc.text = settings.desc
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(
                        b.root.context, settings.icon
                    )
                )
                b.settingsLayout.setOnClickListener {
                    settings.onClick?.invoke(b)
                }
                b.settingsLayout.setOnLongClickListener {
                    settings.onLongClick?.invoke()
                    true
                }
                if (settings.isVisible) {
                    b.settingsLayout.visibility = View.VISIBLE
                    b.settingsLayout.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (24 * b.root.context.resources.displayMetrics.density).toInt()
                    }
                } else {
                    b.settingsLayout.visibility = View.GONE
                    b.settingsLayout.layoutParams = RecyclerView.LayoutParams(0, 0)
                }
                b.settingsIconRight.visibility =
                    if (settings.isActivity) View.VISIBLE else View.GONE
                b.attachView.visibility = if (settings.attach != null) View.VISIBLE else View.GONE
                settings.attach?.invoke(b)
            }

            2 -> {
                val b = (holder as SettingsSwitchViewHolder).binding
                setAnimation(b.root.context, b.root)

                if (highlightKey != null && settings.name.equals(highlightKey, ignoreCase = true)) {
                    b.root.alpha = 0.3f
                    b.root.animate().alpha(1f).setDuration(600).start()
                }

                b.settingsButton.text = settings.name
                b.settingsDesc.text = settings.desc
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(
                        b.root.context, settings.icon
                    )
                )
                b.settingsButton.isChecked = settings.isChecked
                b.settingsButton.setOnCheckedChangeListener { _, isChecked ->
                    settings.switch?.invoke(isChecked, b)
                }
                b.settingsLayout.setOnLongClickListener {
                    settings.onLongClick?.invoke()
                    true
                }
                if (settings.isVisible) {
                    b.settingsLayout.visibility = View.VISIBLE
                    b.settingsLayout.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (24 * b.root.context.resources.displayMetrics.density).toInt()
                    }
                } else {
                    b.settingsLayout.visibility = View.GONE
                    b.settingsLayout.layoutParams = RecyclerView.LayoutParams(0, 0)
                }
                settings.attachToSwitch?.invoke(b)
            }

            SettingsRowType.HEADER -> bindHeader(holder as SettingsHeaderViewHolder, settings)
            SettingsRowType.ADVANCED -> bindAdvanced(holder as SettingsAdvancedViewHolder, settings)
        }
    }

    private fun bindHeader(holder: SettingsHeaderViewHolder, settings: Settings) {
        val b = holder.binding
        b.settingsHeader.text = settings.name
        if (settings.isVisible) {
            b.settingsHeader.visibility = View.VISIBLE
            b.settingsHeader.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (28 * b.root.context.resources.displayMetrics.density).toInt()
            }
        } else {
            b.settingsHeader.visibility = View.GONE
            b.settingsHeader.layoutParams = RecyclerView.LayoutParams(0, 0)
        }
    }

    /** The screen's one advanced toggle: shows or hides every advanced row. */
    private fun bindAdvanced(holder: SettingsAdvancedViewHolder, settings: Settings) {
        val b = holder.binding
        val context = b.root.context
        b.settingsAdvanced.setText(
            if (settings.isChecked) R.string.settings_advanced_hide else R.string.settings_advanced
        )
        b.settingsAdvanced.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0,
            if (settings.isChecked) R.drawable.ic_round_arrow_drop_up_24 else R.drawable.ic_round_arrow_drop_down_24,
            0
        )
        androidx.core.view.ViewCompat.setStateDescription(
            b.settingsAdvanced,
            context.getString(if (settings.isChecked) R.string.expanded else R.string.collapsed)
        )
        b.settingsAdvanced.setOnClickListener {
            settings.isChecked = !settings.isChecked
            settings.advancedRows.forEachIndexed { i, row ->
                row.isVisible = settings.isChecked && settings.advancedVisibility.getOrElse(i) { true }
            }
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (!highlightKey.isNullOrBlank()) {
            recyclerView.post {
                val index = settings.indexOfFirst {
                    it.name.equals(highlightKey, ignoreCase = true)
                }
                if (index >= 0) {
                    (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(index, 0)
                }
            }
        }
    }

    override fun getItemCount(): Int = settings.size

    override fun getItemViewType(position: Int): Int {
        return settings[position].type
    }
}
