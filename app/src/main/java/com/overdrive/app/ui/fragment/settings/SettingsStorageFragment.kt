package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.overdrive.app.ui.fragment.WebViewFragment

/**
 * Settings → Storage pane.
 *
 * Hosts a [WebViewFragment] pointed at the daemon's "/storage" page (storage
 * location, space limits and the OEM dashcam reserve for every recording type),
 * the same way [SettingsRecordingFragment] wraps "/recording".
 */
class SettingsStorageFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            id = HOST_ID
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(HOST_ID) != null) return
        val webFragment = WebViewFragment().apply {
            arguments = Bundle().apply {
                putString(WebViewFragment.ARG_PAGE_PATH, "/storage")
            }
        }
        childFragmentManager.commitNow {
            replace(HOST_ID, webFragment, TAG_WEB)
        }
    }

    companion object {
        // Stable host-id for the inner WebViewFragment (Recording 01, Surveillance 02, Daemons 03).
        private const val HOST_ID = 0x0F100004
        private const val TAG_WEB = "settings_storage_web"
    }
}
