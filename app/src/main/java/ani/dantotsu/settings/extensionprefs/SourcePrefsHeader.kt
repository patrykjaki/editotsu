package ani.dantotsu.settings.extensionprefs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import ani.dantotsu.databinding.ViewSourcePrefsHeaderBinding
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.statusBarHeight

/**
 * Header (back + extension name) for an extension's settings when they open
 * over a screen that has no header of its own, such as the media page.
 * The extensions screen keeps its own header and passes no title.
 */
internal object SourcePrefsHeader {
    const val ARG_TITLE = "header_title"

    fun wrap(fragment: Fragment, inflater: LayoutInflater, container: ViewGroup?, content: View): View {
        val title = fragment.arguments?.getString(ARG_TITLE) ?: return content
        val binding = ViewSourcePrefsHeaderBinding.inflate(inflater, container, false)
        binding.sourcePrefsTitle.text = title
        binding.sourcePrefsContent.addView(content)
        // Inside a dialog host the dialog is what closes; elsewhere back pops the settings.
        val hostDialog = generateSequence(fragment.parentFragment) { it.parentFragment }
            .filterIsInstance<DialogFragment>().firstOrNull()
        binding.sourcePrefsBack.setOnClickListener {
            if (hostDialog != null) hostDialog.dismissAllowingStateLoss()
            else fragment.activity?.onBackPressedDispatcher?.onBackPressed()
        }
        if (hostDialog == null) {
            // Activities here draw under the status bar; a dialog window does not.
            binding.root.updatePadding(top = statusBarHeight)
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.updatePadding(top = maxOf(top, statusBarHeight))
                insets
            }
        }
        return binding.root
    }
}

/**
 * Labels for the "which source" list of an extension with several sources.
 * Languages when they tell the sources apart, otherwise the source names,
 * numbered when those repeat too.
 */
internal fun sourceChoiceLabels(names: List<String>, langs: List<String>): List<String> {
    val languages = langs.map { LanguageMapper.getLanguageName(it) }
    if (languages.toSet().size == languages.size) return languages
    if (names.toSet().size == names.size) return names
    return names.mapIndexed { i, name -> "$name · ${i + 1}" }
}
