package com.overdrive.app.navmap.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme
import com.overdrive.app.overlay.glass.GlassWidgetView
import com.overdrive.app.overlay.glass.shade
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One offset rounded-rect shadow layer in dp: alpha = theme.shadowA × [alphaK], falling to clear over [spread]. */
internal class HudShadow(val dx: Float, val dy: Float, val spread: Float, val alphaK: Float) {
    val padL get() = max(0f, spread - dx)
    val padT get() = max(0f, spread - dy)
    val padR get() = max(0f, spread + dx)
    val padB get() = max(0f, spread + dy)
}

/** SPEC §6 shadow layers, back to front. */
internal object HudShadows {
    val PANEL = arrayOf(HudShadow(10f, 18f, 38f, 1f), HudShadow(2f, 3f, 7f, 0.55f))
    val PILL = arrayOf(HudShadow(8f, 14f, 30f, 1f), HudShadow(2f, 3f, 7f, 0.55f))
    val KEY = arrayOf(HudShadow(0f, 6f, 14f, 0.55f), HudShadow(0f, 1f, 2f, 0.5f))
}

/** Colours the mock defines outside [GlassTheme]: key glass, the End key's red, the lit accent (CTA) glass. */
internal object HudColors {
    fun keyTop(t: GlassTheme) = if (t.night) 0xFF2E3C36.toInt() else 0xFFFFFFFF.toInt()
    fun keyBot(t: GlassTheme) = if (t.night) 0xFF0A120F.toInt() else 0xFFE2EAE5.toInt()
    fun endTop(t: GlassTheme) = if (t.night) 0xFFC24A41.toInt() else 0xFFD2302A.toInt()
    fun endBot(t: GlassTheme) = if (t.night) 0xFF8E2A24.toInt() else 0xFFA01616.toInt()
    fun ctaTop(t: GlassTheme) = if (t.night) GlassColor.darken(t.glowAccent, 0.42f) else t.accent
    fun ctaBot(t: GlassTheme) = if (t.night) GlassColor.darken(t.glowAccent, 0.62f) else GlassColor.darken(t.accent, 0.3f)
    fun ctaHalo(t: GlassTheme) = GlassColor.alpha(t.glowAccent, if (t.night) 0.16f else 0.14f)
    const val WHITE = 0xFFFFFFFF.toInt()
}

/** Bake-time painting shared by panels and keys. Allocates shaders: call only while baking, on the UI thread. */
internal object HudPaint {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val flat = Paint().apply { isDither = true }
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val edge = Path()
    private val moved = Path()
    private val inner = RectF()
    private val radii = FloatArray(8)

    fun roundRect(out: Path, r: RectF, rTop: Float, rBottom: Float) {
        radii[0] = rTop; radii[1] = rTop; radii[2] = rTop; radii[3] = rTop
        radii[4] = rBottom; radii[5] = rBottom; radii[6] = rBottom; radii[7] = rBottom
        out.rewind()
        out.addRoundRect(r, radii, Path.Direction.CW)
    }

    /**
     * CardView-style shadow: four corner RadialGradients on the arc centres and four edge LinearGradients, all
     * solid inside the offset shape and clear [s].spread beyond it. Piece edges snap to whole px so they tile seamlessly.
     */
    fun shadow(c: Canvas, panel: RectF, rTopPx: Float, rBottomPx: Float, s: HudShadow, u: Float, color: Int) {
        val sp = s.spread * u
        val l = panel.left + s.dx * u; val t = panel.top + s.dy * u
        val r = panel.right + s.dx * u; val b = panel.bottom + s.dy * u
        val half = min(r - l, b - t) / 2f
        val rt = rTopPx.coerceIn(0f, half); val rb = rBottomPx.coerceIn(0f, half)
        val xL = snap(l - sp); val xl = snap(l); val xr = snap(r); val xR = snap(r + sp)
        val xlt = snap(l + rt); val xrt = snap(r - rt); val xlb = snap(l + rb); val xrb = snap(r - rb)
        val yT = snap(t - sp); val yt = snap(t); val yb = snap(b); val yB = snap(b + sp)
        val ytr = snap(t + rt); val ybr = snap(b - rb)
        corner(c, l + rt, t + rt, rt, sp, color, xL, yT, xlt, ytr)
        corner(c, r - rt, t + rt, rt, sp, color, xrt, yT, xR, ytr)
        corner(c, l + rb, b - rb, rb, sp, color, xL, ybr, xlb, yB)
        corner(c, r - rb, b - rb, rb, sp, color, xrb, ybr, xR, yB)
        edge(c, color, xlt, yT, xrt, yt, 0f, t, 0f, t - sp)
        edge(c, color, xlb, yb, xrb, yB, 0f, b, 0f, b + sp)
        edge(c, color, xL, ytr, xl, ybr, l, 0f, l - sp, 0f)
        edge(c, color, xr, ytr, xR, ybr, r, 0f, r + sp, 0f)
        flat.shader = null; flat.color = color
        fill(c, xl, ytr, xr, ybr); fill(c, xlt, yt, xrt, ytr); fill(c, xlb, ybr, xrb, yb)
    }

    private fun snap(v: Float) = v.roundToInt().toFloat()

    private fun corner(c: Canvas, cx: Float, cy: Float, rc: Float, sp: Float, color: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (x1 <= x0 || y1 <= y0) return
        val radius = rc + sp
        val g = if (rc < 0.5f) RadialGradient(cx, cy, radius, color, GlassColor.clear(color), Shader.TileMode.CLAMP)
        else RadialGradient(cx, cy, radius, intArrayOf(color, color, GlassColor.clear(color)), floatArrayOf(0f, rc / radius, 1f), Shader.TileMode.CLAMP)
        c.drawRect(x0, y0, x1, y1, flat.shade(g))
        flat.shader = null
    }

    private fun edge(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, gx0: Float, gy0: Float, gx1: Float, gy1: Float) {
        if (x1 <= x0 || y1 <= y0) return
        c.drawRect(x0, y0, x1, y1, flat.shade(LinearGradient(gx0, gy0, gx1, gy1, color, GlassColor.clear(color), Shader.TileMode.CLAMP)))
        flat.shader = null
    }

    private fun fill(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (x1 > x0 && y1 > y0) c.drawRect(x0, y0, x1, y1, flat)
    }

    /** CSS box-shadow never shows under the box itself. */
    fun eraseInside(c: Canvas, path: Path) = c.drawPath(path, erase)

    /** A solid ring [ring] px wide round the shape (the CTA's 0 0 0 6px halo). */
    fun haloRing(c: Canvas, panel: RectF, rTop: Float, rBottom: Float, ring: Float, color: Int) {
        inner.set(panel); inner.inset(-ring, -ring)
        roundRect(edge, inner, rTop + ring, rBottom + ring)
        p.shader = null; p.color = color
        c.drawPath(edge, p)
    }

    /** b. tinted body: the 166° glassTop → glassBot ramp (stop .72) under the top-left radial. */
    fun glassBody(c: Canvas, panel: RectF, path: Path, t: GlassTheme, tint: Float) {
        c.drawPath(path, p.shade(GlassWidgetView.angleGradient(166f, panel,
            intArrayOf(GlassColor.alpha(t.glassTop, tint), GlassColor.alpha(t.glassBot, tint)), floatArrayOf(0f, 0.72f))))
        c.drawPath(path, p.shade(GlassWidgetView.ellipseGradient(panel.left + panel.width() * 0.06f, panel.top - panel.height() * 0.12f,
            panel.width() * 1.3f, panel.height() * 0.95f,
            intArrayOf(GlassColor.alpha(t.glassTop, tint * 0.75f), GlassColor.clear(t.glassTop)), floatArrayOf(0f, 0.62f))))
        p.shader = null
    }

    fun linearBody(c: Canvas, panel: RectF, path: Path, deg: Float, top: Int, bottom: Int) {
        c.drawPath(path, p.shade(GlassWidgetView.angleGradient(deg, panel, intArrayOf(top, bottom), floatArrayOf(0f, 1f))))
        p.shader = null
    }

    /** d. front glass of a panel: top highlight, corner radial, bottom thickness, edge bands, graded outline. */
    fun panelFront(c: Canvas, panel: RectF, path: Path, rTop: Float, rBottom: Float, t: GlassTheme, u: Float) {
        val white = HudColors.WHITE
        c.save()
        c.clipPath(path)
        p.shade(LinearGradient(0f, panel.top, 0f, panel.top + panel.height() * 0.36f, GlassColor.alpha(white, 0.075f), GlassColor.clear(white), Shader.TileMode.CLAMP))
        c.drawRect(panel, p)
        p.shade(GlassWidgetView.ellipseGradient(panel.left, panel.top, panel.width() * 0.85f, panel.height() * 0.7f,
            intArrayOf(GlassColor.alpha(white, 0.07f), GlassColor.clear(white)), floatArrayOf(0f, 0.56f)))
        c.drawRect(panel, p)
        p.shade(LinearGradient(0f, panel.bottom - 8f * u, 0f, panel.bottom, GlassColor.clear(t.edgeDark), GlassColor.alpha(t.edgeDark, t.thickA * 0.45f), Shader.TileMode.CLAMP))
        c.drawRect(panel, p)
        p.shader = null
        c.restore()
        edgeBand(c, path, -3f * u, GlassColor.alpha(t.edgeDark, t.thickA))
        edgeBand(c, path, 1.2f * u, GlassColor.alpha(white, t.litA))
        outline(c, panel, rTop, rBottom, u, intArrayOf(
            GlassColor.alpha(white, t.outlineHi), GlassColor.alpha(white, t.outlineHi * 0.22f),
            GlassColor.alpha(t.edgeDark, t.outlineLo * 0.5f), GlassColor.alpha(t.edgeDark, t.outlineLo)), floatArrayOf(0f, 0.34f, 0.7f, 1f))
    }

    /** A key's front: lit top band, thick bottom band, graded outline (no highlights). */
    fun keyFront(c: Canvas, panel: RectF, path: Path, r: Float, t: GlassTheme, u: Float) {
        val white = HudColors.WHITE
        edgeBand(c, path, -2.5f * u, GlassColor.alpha(t.edgeDark, t.thickA))
        edgeBand(c, path, 1.2f * u, GlassColor.alpha(white, t.litA))
        outline(c, panel, r, r, u, intArrayOf(
            GlassColor.alpha(white, t.outlineHi), GlassColor.alpha(white, t.outlineHi * 0.2f),
            GlassColor.alpha(t.edgeDark, t.outlineLo * 0.6f)), floatArrayOf(0f, 0.4f, 1f))
    }

    private fun outline(c: Canvas, panel: RectF, rTop: Float, rBottom: Float, u: Float, colors: IntArray, stops: FloatArray) {
        val h = 0.5f * u
        inner.set(panel.left + h, panel.top + h, panel.right - h, panel.bottom - h)
        roundRect(edge, inner, max(0f, rTop - h), max(0f, rBottom - h))
        p.style = Paint.Style.STROKE
        p.strokeWidth = u
        c.drawPath(edge, p.shade(GlassWidgetView.angleGradient(150f, panel, colors, stops)))
        p.shader = null
        p.style = Paint.Style.FILL
    }

    /** The CSS inset-shadow band: the shape minus itself moved by [dy]. */
    fun edgeBand(c: Canvas, path: Path, dy: Float, color: Int) {
        moved.set(path); moved.offset(0f, dy)
        edge.set(path); edge.op(moved, Path.Op.DIFFERENCE)
        p.shader = null; p.color = color
        c.drawPath(edge, p)
    }
}

/**
 * A panel's glass in its view: the glass rect sits [padL]/[padT] dp in, the shadows draw in the pads. The back
 * (shadows + body) and front glass are baked into bitmaps, rebuilt only when size, shape, theme or tint change.
 */
internal class HudShell(private val shadows: Array<HudShadow>, extraPad: Float = 0f) {
    /** GLASS: tinted panel glass; CTA: lit accent glass; KEY: neutral key glass; END: the End key's red glass. */
    enum class Body { GLASS, CTA, KEY, END }
    enum class Front { PANEL, KEY }

    val padL = shadows.maxOf { it.padL } + extraPad
    val padT = shadows.maxOf { it.padT } + extraPad
    val padR = shadows.maxOf { it.padR } + extraPad
    val padB = shadows.maxOf { it.padB } + extraPad

    var body = Body.GLASS
        set(v) { if (field != v) { field = v; dirty = true } }
    var front = Front.PANEL
        set(v) { if (field != v) { field = v; dirty = true } }
    var tint = 0.88f
        set(v) { if (field != v) { field = v; dirty = true } }
    var radiusTop = 26f
        set(v) { if (field != v) { field = v; dirty = true } }
    var radiusBottom = 26f
        set(v) { if (field != v) { field = v; dirty = true } }
    /** CTA halo ring width in dp; 0 for none. */
    var halo = 0f
        set(v) { if (field != v) { field = v; dirty = true } }

    /** The glass in view px. */
    val panel = RectF()
    val path = Path()

    private var back: Bitmap? = null
    private var frontLayer: Bitmap? = null
    private var frontX = 0f
    private var frontY = 0f
    private var dirty = true
    private var bW = 0
    private var bH = 0
    private var bU = 0f
    private var bPW = 0f
    private var bPH = 0f
    private var bNight = false
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun invalidate() { dirty = true }

    /** Places the glass ([wDp]×[hDp]) and re-bakes if anything it shows changed. */
    fun update(viewW: Int, viewH: Int, wDp: Float, hDp: Float, u: Float, t: GlassTheme) {
        if (!dirty && back != null && viewW == bW && viewH == bH && u == bU && wDp == bPW && hDp == bPH && t.night == bNight) return
        dirty = false; bW = viewW; bH = viewH; bU = u; bPW = wDp; bPH = hDp; bNight = t.night
        place(wDp, hDp, u)
        if (viewW <= 0 || viewH <= 0) return
        val rt = radiusTop * u; val rb = radiusBottom * u
        back = reuse(back, viewW, viewH).also { bmp ->
            val c = Canvas(bmp)
            if (halo > 0f) HudPaint.haloRing(c, panel, rt, rb, halo * u, HudColors.ctaHalo(t))
            for (s in shadows) HudPaint.shadow(c, panel, rt, rb, s, u, GlassColor.alpha(t.shadow, t.shadowA * s.alphaK))
            HudPaint.eraseInside(c, path)
            when (body) {
                Body.GLASS -> HudPaint.glassBody(c, panel, path, t, tint)
                Body.CTA -> HudPaint.linearBody(c, panel, path, 170f, HudColors.ctaTop(t), HudColors.ctaBot(t))
                Body.KEY -> HudPaint.linearBody(c, panel, path, 170f,
                    GlassColor.alpha(HudColors.keyTop(t), 0.96f), GlassColor.alpha(HudColors.keyBot(t), 0.96f))
                Body.END -> HudPaint.linearBody(c, panel, path, 170f, HudColors.endTop(t), HudColors.endBot(t))
            }
        }
        frontX = floor(panel.left); frontY = floor(panel.top)
        val fw = ceil(panel.right).toInt() - frontX.toInt()
        val fh = ceil(panel.bottom).toInt() - frontY.toInt()
        frontLayer = reuse(frontLayer, fw, fh).also { bmp ->
            val c = Canvas(bmp)
            c.translate(-frontX, -frontY)
            when (front) {
                Front.PANEL -> HudPaint.panelFront(c, panel, path, rt, rb, t, u)
                Front.KEY -> HudPaint.keyFront(c, panel, path, rt, t, u)
            }
        }
    }

    /** Geometry only, for hit tests before the first draw. */
    fun place(wDp: Float, hDp: Float, u: Float) {
        panel.set(padL * u, padT * u, (padL + wDp) * u, (padT + hDp) * u)
        HudPaint.roundRect(path, panel, radiusTop * u, radiusBottom * u)
    }

    fun drawBack(c: Canvas) { back?.let { c.drawBitmap(it, 0f, 0f, bitmapPaint) } }
    fun drawFront(c: Canvas) { frontLayer?.let { c.drawBitmap(it, frontX, frontY, bitmapPaint) } }

    fun release() {
        back?.recycle(); frontLayer?.recycle()
        back = null; frontLayer = null; dirty = true
    }

    private fun reuse(b: Bitmap?, w: Int, h: Int): Bitmap {
        if (b != null && !b.isRecycled && b.width == w && b.height == h) { b.eraseColor(0); return b }
        b?.recycle()
        return Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    }
}
