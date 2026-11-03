package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsGroupBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.search.SettingsSearchAdapter
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager

/** A settings group made only of row sections (currently Sources). */
class SettingsGroupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsGroupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsGroupLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.settingsGroupBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.settingsGroupTitle.setText(R.string.settings_sources)
        binding.settingsGroupIcon.setImageResource(R.drawable.ic_extension)
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        showRows(intent.getStringExtra(SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY))
    }

    private fun showRows(highlightKey: String? = null) {
        val sections = SourcesSettings.sections(this) { showRows() }
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            SettingsSections.flatten(sections, getString(R.string.settings_advanced), highlightKey),
            highlightKey = highlightKey
        )
    }

    companion object {
        fun sources(context: Context) = Intent(context, SettingsGroupActivity::class.java)
    }
}
