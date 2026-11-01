package ani.dantotsu.media.anime

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment

/**
 * Full-screen host for an extension's own settings, opened from the source
 * picker's Filter panel ("Source filters") so the picker stays open below.
 * [onClosed] runs once when the host goes away. After process death the
 * callback is gone, so a restored host just closes.
 */
class SourceFiltersHostDialog : DialogFragment() {
    private var onClosed: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        if (savedInstanceState != null && onClosed == null) dismissAllowingStateLoss()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply {
            id = R.id.source_filters_host_container
            setBackgroundColor(context.getThemeColor(com.google.android.material.R.attr.colorSurface))
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val sourceId = requireArguments().getLong(ARG_SOURCE_ID)
            childFragmentManager.beginTransaction()
                .replace(R.id.source_filters_host_container, AnimeSourcePreferencesFragment().getInstance(sourceId))
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroy() {
        super.onDestroy()
        onClosed?.invoke()
        onClosed = null
    }

    companion object {
        private const val ARG_SOURCE_ID = "source_id"
        const val TAG = "SourceFiltersHost"

        fun newInstance(sourceId: Long, onClosed: () -> Unit) = SourceFiltersHostDialog().apply {
            arguments = bundleOf(ARG_SOURCE_ID to sourceId)
            this.onClosed = onClosed
        }
    }
}
