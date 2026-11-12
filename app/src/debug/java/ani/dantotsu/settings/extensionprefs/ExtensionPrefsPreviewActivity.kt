package ani.dantotsu.settings.extensionprefs

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.initActivity
import ani.dantotsu.themes.ThemeManager

/**
 * Debug builds only. Feeds a hand-built PreferenceScreen with every type an extension can use
 * into [ExtensionPrefRows], the renderer the extension settings screens use. Values go to a
 * throwaway SharedPreferences file. Start with
 * `adb shell am start -n ani.editotsu.beta/ani.dantotsu.settings.extensionprefs.ExtensionPrefsPreviewActivity`.
 */
class ExtensionPrefsPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val frame = FrameLayout(this).apply { id = android.view.View.generateViewId() }
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        if (savedInstanceState == null) {
            val fragment = PreviewFragment().apply {
                arguments = Bundle().apply { putBoolean(RAW, intent.getBooleanExtra(RAW, false)) }
            }
            supportFragmentManager.beginTransaction().replace(frame.id, fragment).commit()
        }
    }

    class PreviewFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "extension_prefs_preview"
            preferenceScreen = build(requireContext())
            // Dependencies resolve against the attached screen, so set this one afterwards.
            findPreference<Preference>("disabled")?.dependency = "dub"
        }

        /** `--ez raw true` shows the plain androidx list, for before/after shots. */
        override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
            if (arguments?.getBoolean(RAW) == true) super.onCreateAdapter(preferenceScreen)
            else ExtensionPrefRows(this, preferenceScreen).adapter(listView)

        private fun build(c: Context): PreferenceScreen {
            val screen = preferenceManager.createPreferenceScreen(c)
            screen.addPreference(ListPreference(c).apply {
                key = "quality"; title = "Preferred quality"; summary = "%s"
                entries = arrayOf("1080p", "720p", "480p", "360p"); entryValues = entries; setDefaultValue("1080p")
            })
            screen.addPreference(SwitchPreferenceCompat(c).apply {
                key = "dub"; title = "Prefer dub"; summary = "Lists dubbed episodes first"; setDefaultValue(false)
            })
            val account = PreferenceCategory(c).apply { title = "Account" }
            screen.addPreference(account)
            account.addPreference(EditTextPreference(c).apply {
                key = "user"; title = "Username"; summary = "Used for the site login"
            })
            account.addPreference(EditTextPreference(c).apply {
                key = "password"; title = "Password"; summary = "Stored on this device only"
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            })
            account.addPreference(CheckBoxPreference(c).apply {
                key = "remember"; title = "Remember login"; setDefaultValue(true)
            })
            val content = PreferenceCategory(c).apply { title = "Content" }
            screen.addPreference(content)
            content.addPreference(MultiSelectListPreference(c).apply {
                key = "types"; title = "Show types"
                entries = arrayOf("TV", "Movie", "OVA", "ONA", "Special"); entryValues = entries
                setDefaultValue(setOf("TV", "Movie"))
            })
            content.addPreference(ListPreference(c).apply {
                key = "domain"; title = "Mirror"; summary = "%s"
                entries = Array(34) { "Mirror ${it + 1}" }; entryValues = entries; setDefaultValue("Mirror 12")
            })
            content.addPreference(SwitchPreferenceCompat(c).apply {
                key = "long"; title = "Use the alternative episode list"
                summary = "Loads episodes from the alternative API. Slower, but it lists specials and " +
                    "recaps the main list leaves out, and keeps working while the main API is down. " +
                    "Turn it off again if episode numbers look wrong."
            })
            content.addPreference(SwitchPreferenceCompat(c).apply {
                key = "notitle"; summary = "Hide filler episodes"
            })
            content.addPreference(CheckBoxPreference(c).apply { key = "show_raw_titles" })
            content.addPreference(SwitchPreferenceCompat(c).apply {
                key = "disabled"; title = "Hardsub only"; summary = "Needs Prefer dub on"
            })
            content.addPreference(Preference(c).apply {
                key = "info"; title = "Version"; summary = "14.2"; isSelectable = false
            })
            return screen
        }
    }

    companion object {
        const val RAW = "raw"
    }
}
