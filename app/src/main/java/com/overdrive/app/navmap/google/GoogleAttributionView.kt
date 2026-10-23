package com.overdrive.app.navmap.google

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.overdrive.app.R

/** Official Google Maps logo (18 dp) + one-line copyright, with Google's 10 dp clear space around the logo. */
class GoogleAttributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val logo = ImageView(context)
    private val text = TextView(context)
    private var tonedNight: Boolean? = null

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(10f), dp(10f), dp(10f), dp(5f))

        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logo.contentDescription = context.getString(R.string.navmap_google_maps_cd)
        addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18f)))

        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        text.setSingleLine(true)
        text.ellipsize = TextUtils.TruncateAt.END
        text.maxWidth = dp(240f)
        text.visibility = View.GONE
        addView(
            text,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6f) }
        )

        applyTone(night = false)
    }

    fun bind(visible: Boolean, night: Boolean, copyright: String?) {
        if (!visible) {
            visibility = View.GONE
            return
        }
        if (tonedNight != night) applyTone(night)
        val line = copyright?.trim().orEmpty()
        text.text = line
        text.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        visibility = View.VISIBLE
    }

    private fun applyTone(night: Boolean) {
        tonedNight = night
        logo.setImageResource(if (night) R.drawable.google_maps_logo_dark_outline else R.drawable.google_maps_logo_light_outline)
        text.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
        text.setShadowLayer(1.5f * resources.displayMetrics.density, 0f, 0f, if (night) NIGHT_SHADOW else DAY_SHADOW)
    }

    private fun dp(v: Float): Int = Math.round(v * resources.displayMetrics.density)

    private companion object {
        const val DAY_TEXT = 0xFF3C4043.toInt()
        const val NIGHT_TEXT = 0xFFE8EAED.toInt()
        const val DAY_SHADOW = 0xFFFFFFFF.toInt()
        const val NIGHT_SHADOW = 0xFF202124.toInt()
    }
}
