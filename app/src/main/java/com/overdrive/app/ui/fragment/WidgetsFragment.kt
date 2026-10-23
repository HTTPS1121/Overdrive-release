package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow

/** Widgets pane: hosts the daemon's "/widgets" settings page inline, like the RoadSense pane. */
class WidgetsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            id = HOST_ID
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(HOST_ID) != null) return
        val webFragment = WebViewFragment().apply {
            arguments = Bundle().apply { putString(WebViewFragment.ARG_PAGE_PATH, "/widgets") }
        }
        childFragmentManager.commitNow { replace(HOST_ID, webFragment, TAG_WEB) }
    }

    companion object {
        private const val HOST_ID = 0x0F100010
        private const val TAG_WEB = "widgets_web"
    }
}
