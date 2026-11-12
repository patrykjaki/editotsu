package ani.dantotsu.settings.extensionprefs

import android.content.Context
import android.content.Intent
import android.text.InputType
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import androidx.preference.getOnBindEditTextListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import ani.dantotsu.R
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.RowAdapter
import ani.dantotsu.settings.rows.ScreenItems
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import java.util.Locale

/**
 * Draws an extension's own PreferenceScreen with the settings rows instead of the raw
 * preference list. The androidx Preference objects stay the model: every change goes through
 * the preference's change listener and its own setter, so values are written to the
 * extension's SharedPreferences exactly as before and listeners, dependencies and summary
 * providers keep working.
 *
 * Switches, lists, multi-selects, text fields, sliders and categories get rows; any other
 * preference is a plain row that runs its click listener, intent or dialog.
 */
internal class ExtensionPrefRows(
    private val fragment: PreferenceFragmentCompat,
    private val screen: PreferenceGroup,
    /** Rows the app adds after the extension's own (anime: download resolutions). */
    private val extra: (SettingsHost) -> List<Row> = { emptyList() },
) : SettingsHost {

    override val activity: AppCompatActivity get() = fragment.requireActivity() as AppCompatActivity

    private lateinit var rows: RowAdapter

    /** The list adapter for [PreferenceFragmentCompat.onCreateAdapter]. */
    fun adapter(list: RecyclerView?): RecyclerView.Adapter<*> {
        list?.let {
            (it.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            if (it.layoutManager == null) it.layoutManager = LinearLayoutManager(it.context)
            it.clipToPadding = false
            val pad = (8 * it.resources.displayMetrics.density).toInt()
            it.setPadding(0, pad, 0, pad * 4)
        }
        fragment.setDivider(null)
        rows = RowAdapter(this, iconSlot = false)
        refresh()
        return rows
    }

    override fun refresh() {
        if (!::rows.isInitialized) return
        rows.submitList(ScreenItems.build(sections(), emptySet()))
    }

    private fun sections(): List<Section> {
        val out = ArrayList<Section>()
        var loose = ArrayList<Row>()
        fun flush() {
            if (loose.isNotEmpty()) out += Section(0, loose)
            loose = ArrayList()
        }
        for (i in 0 until screen.preferenceCount) {
            val pref = screen.getPreference(i)
            if (pref is PreferenceGroup) {
                flush()
                val rows = ArrayList<Row>()
                collect(pref, rows) { pref.isVisible }
                out += Section(0, rows, label = pref.title?.takeIf { it.isNotBlank() })
            } else {
                loose += row(pref) { true }
            }
        }
        val extraRows = extra(this)
        if (extraRows.isNotEmpty()) {
            flush()
            out += Section(R.string.downloads, extraRows)
        } else {
            flush()
        }
        return out
    }

    /** Nested groups are flattened into their category's card. */
    private fun collect(group: PreferenceGroup, into: MutableList<Row>, parentVisible: () -> Boolean) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) collect(pref, into) { parentVisible() && pref.isVisible }
            else into += row(pref, parentVisible)
        }
    }

    private fun id(pref: Preference) = "ext:${pref.key ?: System.identityHashCode(pref)}"

    /** Title, or for a preference without one its summary, else its key in words. */
    private fun label(pref: Preference): CharSequence =
        pref.title?.takeIf { it.isNotBlank() }
            ?: pref.summary?.takeIf { it.isNotBlank() }
            ?: pref.key?.replace('_', ' ')?.replaceFirstChar { it.titlecase(Locale.ROOT) }
            ?: ""

    /** Summary shown as the second line, unless the label already used it. */
    private fun summary(pref: Preference): CharSequence? =
        pref.summary?.takeIf { it.isNotBlank() && pref.title?.isNotBlank() == true }

    private fun row(pref: Preference, parentVisible: () -> Boolean): Row {
        val visible = { parentVisible() && pref.isVisible }
        val enabled = { pref.isEnabled }
        return when (pref) {
            is TwoStatePreference -> Row(
                id = id(pref), title = 0, label = label(pref), visible = visible, enabled = enabled,
                summaryText = {
                    (if (pref.isChecked) pref.summaryOn else pref.summaryOff)?.takeIf { it.isNotBlank() }
                        ?: summary(pref)
                },
                kind = Kind.Switch(
                    get = { pref.isChecked },
                    set = { host, on ->
                        if (pref.callChangeListener(on)) pref.isChecked = on
                        host.refresh()
                    },
                ),
            )

            is ListPreference -> Row(
                id = id(pref), title = 0, label = label(pref), visible = visible, enabled = enabled,
                kind = Kind.Action(current = { summary(pref)?.toString() ?: pref.entry?.toString() }) { host, _ ->
                    val entries = pref.entries ?: return@Action
                    val values = pref.entryValues ?: return@Action
                    ChoiceSheet.single(
                        host.activity, dialogTitle(pref), entries.map { ChoiceOption(it) },
                        pref.findIndexOfValue(pref.value),
                    ) { i ->
                        val value = values.getOrNull(i)?.toString() ?: return@single
                        if (pref.callChangeListener(value)) pref.value = value
                        host.refresh()
                    }
                },
            )

            is MultiSelectListPreference -> Row(
                id = id(pref), title = 0, label = label(pref), visible = visible, enabled = enabled,
                kind = Kind.Action(current = {
                    summary(pref)?.toString() ?: run {
                        val values = pref.entryValues ?: return@run null
                        pref.entries?.filterIndexed { i, _ -> values.getOrNull(i)?.toString() in pref.values }
                            ?.joinToString(", ")?.takeIf { it.isNotEmpty() }
                    }
                }) { host, _ ->
                    val entries = pref.entries ?: return@Action
                    val values = pref.entryValues ?: return@Action
                    val checked = BooleanArray(entries.size) { values.getOrNull(it)?.toString() in pref.values }
                    ChoiceSheet.multi(
                        host.activity, dialogTitle(pref), entries.map { ChoiceOption(it) }, checked,
                        confirm = ChoiceSheet.Confirm(R.string.ok) { now ->
                            val set = values.filterIndexed { i, _ -> now.getOrElse(i) { false } }
                                .map { it.toString() }.toSet()
                            if (pref.callChangeListener(set)) pref.values = set
                            host.refresh()
                        },
                    )
                },
            )

            // Only the extension's summary is shown: the text may be a password or token.
            is EditTextPreference -> Row(
                id = id(pref), title = 0, label = label(pref), visible = visible, enabled = enabled,
                kind = Kind.Action(current = { summary(pref)?.toString() }) { host, _ -> editText(host, pref) },
            )

            is SeekBarPreference -> Row(
                id = id(pref), title = 0, label = label(pref), visible = visible, enabled = enabled,
                summaryText = { summary(pref) },
                kind = Kind.Slider(
                    from = pref.min.toFloat(),
                    to = pref.max.toFloat().coerceAtLeast(pref.min + 1f),
                    step = pref.seekBarIncrement.coerceAtLeast(1).toFloat(),
                    get = { pref.value.toFloat() },
                    set = { host, v ->
                        val value = v.toInt()
                        if (pref.callChangeListener(value)) pref.value = value
                        host.refresh()
                    },
                    format = { _, v -> v.toInt().toString() },
                ),
            )

            else -> Row(
                id = id(pref), title = 0, label = label(pref), visible = visible,
                enabled = { pref.isEnabled && pref.isSelectable },
                kind = Kind.Action(current = { summary(pref)?.toString() }) { host, _ ->
                    click(host, pref)
                },
            )
        }
    }

    private fun dialogTitle(pref: DialogPreference): CharSequence =
        pref.dialogTitle?.takeIf { it.isNotBlank() } ?: label(pref)

    private fun click(host: SettingsHost, pref: Preference) {
        if (pref.onPreferenceClickListener?.onPreferenceClick(pref) == true) {
            host.refresh()
            return
        }
        when {
            // Custom dialog types the fragment can't show are skipped rather than crash.
            pref is DialogPreference -> runCatching { fragment.onDisplayPreferenceDialog(pref) }
            pref.intent != null -> runCatching { host.activity.startActivity(pref.intent as Intent) }
        }
        host.refresh()
    }

    private fun editText(host: SettingsHost, pref: EditTextPreference) {
        val c: Context = host.activity
        val field = TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        val input = TextInputEditText(field.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(pref.text.orEmpty())
        }
        // The extension may set the input type (passwords, numbers) here.
        runCatching { pref.getOnBindEditTextListener()?.onBindEditText(input) }
        input.setIncognito(fragment.lifecycleScope)
        input.setSelection(input.text?.length ?: 0)
        val variation = input.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = input.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        field.endIconMode =
            if (isPassword) TextInputLayout.END_ICON_PASSWORD_TOGGLE else TextInputLayout.END_ICON_CLEAR_TEXT
        field.addView(input)
        val margin = (24 * c.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(c).apply {
            setPadding(margin, margin / 2, margin, 0)
            minimumWidth = (320 * c.resources.displayMetrics.density).toInt()
            addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        c.customAlertDialog().apply {
            setTitle(dialogTitle(pref).toString())
            pref.dialogMessage?.takeIf { it.isNotBlank() }?.let { setMessage(it.toString()) }
            setCustomView(frame)
            setPosButton(R.string.ok) {
                val text = input.text.toString()
                if (pref.callChangeListener(text)) pref.text = text
                host.refresh()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
