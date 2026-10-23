package com.overdrive.app.overlay.glass

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour tokens for the glass widgets. Text colours come from the app theme; the glow colours are
 * saturated versions used only for light-emitting parts (liquid, sign glow, flow), because the
 * pale night text reds look weak as light.
 */
class GlassTheme private constructor(val night: Boolean) {
    val accent = if (night) 0xFF5DDBB6.toInt() else 0xFF007A62.toInt()
    val good = if (night) 0xFF5BD382.toInt() else 0xFF1F7A3F.toInt()
    val warn = if (night) 0xFFFFB870.toInt() else 0xFFA6601C.toInt()
    val danger = if (night) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
    val glowAccent = if (night) 0xFF2FF0BD.toInt() else 0xFF00A884.toInt()
    val glowGood = if (night) 0xFF3BE07A.toInt() else 0xFF1FA650.toInt()
    val glowWarn = if (night) 0xFFFFA23A.toInt() else 0xFFE08A12.toInt()
    val glowDanger = if (night) 0xFFFF5A4F.toInt() else 0xFFE5342B.toInt()
    val fuel = if (night) 0xFFF6C343.toInt() else 0xFFCF9410.toInt()
    val regen = if (night) 0xFF58B9FF.toInt() else 0xFF1C6FD6.toInt()
    val regenText = if (night) 0xFF8CCBFF.toInt() else 0xFF1759B3.toInt()
    val text = if (night) 0xFFE7F2ED.toInt() else 0xFF0B1A15.toInt()
    val text2 = if (night) 0xA8E7F2ED.toInt() else 0xA80B1A15.toInt()
    val text3 = if (night) 0x66E7F2ED.toInt() else 0x6B0B1A15.toInt()
    val ink = if (night) Color.WHITE else 0xFF0B281E.toInt()
    val shadow = if (night) Color.BLACK else 0xFF12291F.toInt()
    val shadowA = if (night) 0.55f else 0.26f
    val etch = if (night) 0x38FFFFFF else 0x3D0B281E

    // glass shell
    val glassTop = if (night) 0xFF24302B.toInt() else Color.WHITE
    val glassBot = if (night) 0xFF010A07.toInt() else 0xFFE9F0EB.toInt()
    val edgeDark = if (night) Color.BLACK else 0xFF183026.toInt()
    val litA = if (night) 0.30f else 1f
    val thickA = if (night) 0.34f else 0.10f
    val outlineHi = if (night) 0.42f else 0.95f
    val outlineLo = if (night) 0.60f else 0.20f
    val fog = if (night) 0xFFB2C2BC.toInt() else Color.WHITE
    val fogA = if (night) 0.32f else 0.55f
    val fogPlate = if (night) 0xFF08100D.toInt() else Color.WHITE
    val sheenA = if (night) 0.24f else 0.9f

    companion object {
        val NIGHT = GlassTheme(true)
        val DAY = GlassTheme(false)
        fun of(night: Boolean) = if (night) NIGHT else DAY
    }
}

/** Colour maths shared by the widgets. All inputs and outputs are ARGB ints. */
object GlassColor {
    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

    /** [c] fully transparent. Gradients fade to this, never to 0: Android interpolates unpremultiplied, so 0 greys the fade. */
    fun clear(c: Int): Int = c and 0x00FFFFFF

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).roundToInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).roundToInt(),
        )
    }

    fun lighten(c: Int, t: Float) = mix(c, alpha(Color.WHITE, Color.alpha(c) / 255f), t)
    fun darken(c: Int, t: Float) = mix(c, alpha(Color.BLACK, Color.alpha(c) / 255f), t)

    private val hslA = FloatArray(3)
    private val hslB = FloatArray(3)

    /** Hue-path blend: amber to mint passes through vivid lime, never muddy olive. */
    fun mixHue(a: Int, b: Int, t: Float): Int {
        toHsl(a, hslA); toHsl(b, hslB)
        var dh = hslB[0] - hslA[0]
        if (dh > 180f) dh -= 360f
        if (dh < -180f) dh += 360f
        val k = t.coerceIn(0f, 1f)
        return fromHsl((hslA[0] + dh * k + 360f) % 360f, hslA[1] + (hslB[1] - hslA[1]) * k, hslA[2] + (hslB[2] - hslA[2]) * k)
    }

    /** Piecewise hue ramp over (x, colour) stops sorted by x. */
    fun rampHue(xs: FloatArray, cs: IntArray, x: Float): Int {
        if (x <= xs[0]) return cs[0]
        for (i in 1 until xs.size) {
            if (x <= xs[i]) return mixHue(cs[i - 1], cs[i], (x - xs[i - 1]) / max(1e-6f, xs[i] - xs[i - 1]))
        }
        return cs[cs.size - 1]
    }

    fun hue(c: Int): Float { toHsl(c, hslA); return hslA[0] }

    private fun toHsl(c: Int, out: FloatArray) {
        val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2f
        if (mx == mn) { out[0] = 0f; out[1] = 0f; out[2] = l; return }
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        out[0] = h * 60f; out[1] = s; out[2] = l
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        val a = s * min(l, 1f - l)
        fun f(n: Float): Float { val k = (n + h / 30f) % 12f; return l - a * max(-1f, min(k - 3f, min(9f - k, 1f))) }
        return Color.rgb((f(0f) * 255f).roundToInt(), (f(8f) * 255f).roundToInt(), (f(4f) * 255f).roundToInt())
    }
}
