package com.overdrive.app.navmap.glass.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme
import com.overdrive.app.overlay.glass.shade
import com.overdrive.app.overlay.glass.unitOval
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Canvas plumbing for the map bitmaps (SPEC §5): every shadow and glow is a gradient fill, nothing blurs.
 * One shared canvas, paints and paths; callers hold MapGlassArt's lock. All sizes are dp; the canvas is scaled by k.
 */
internal object ArtKit {
    const val WHITE = Color.WHITE
    const val BLACK = Color.BLACK

    val canvas = Canvas()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    // Canvas 2D stroke defaults: butt caps, miter joins, miter limit 10
    val line = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.STROKE; strokeMiter = 10f }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isSubpixelText = true }
    val bb = RectF()
    private val band = Path()
    private val moved = Path()
    private val c2 = IntArray(2)
    private val s2 = FloatArray(2)
    private val c3 = IntArray(3)
    private val s3 = FloatArray(3)
    private val c4 = IntArray(4)
    private val s4 = FloatArray(4)
    // Canvas 2D interpolates gradient stops unpremultiplied, Android (Q+) premultiplied; where colour and alpha both
    // change, the stops are resampled SUB times per span so either interpolation gives the mock's ramp.
    private const val SUB = 8
    private val u2c = IntArray(SUB + 1)
    private val u2s = FloatArray(SUB + 1)
    private val u3c = IntArray(2 * SUB + 1)
    private val u3s = FloatArray(2 * SUB + 1)
    private val u4c = IntArray(3 * SUB + 1)
    private val u4s = FloatArray(3 * SUB + 1)

    /** A [w] × [h] dp bitmap (ceil'd px, density [dpi]) with [canvas] on it, scaled to dp. */
    fun begin(w: Float, h: Float, k: Float, dpi: Int): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, ceil(w.toDouble() * k).toInt()), max(1, ceil(h.toDouble() * k).toInt()), Bitmap.Config.ARGB_8888)
        bmp.density = dpi
        canvas.setBitmap(bmp)
        canvas.scale(k, k)
        return bmp
    }

    fun end() = canvas.setBitmap(null)

    fun solid(p: Paint, color: Int): Paint { p.shader = null; p.color = color; return p }

    fun linear(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int): LinearGradient {
        c2[0] = a; c2[1] = b; s2[0] = 0f; s2[1] = 1f
        return LinearGradient(x0, y0, x1, y1, c2, s2, Shader.TileMode.CLAMP)
    }

    fun linear(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int, c: Int, sb: Float): LinearGradient {
        c3[0] = a; c3[1] = b; c3[2] = c; s3[0] = 0f; s3[1] = sb; s3[2] = 1f
        return LinearGradient(x0, y0, x1, y1, c3, s3, Shader.TileMode.CLAMP)
    }

    fun linear(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int, c: Int, d: Int, sb: Float, sc: Float): LinearGradient {
        c4[0] = a; c4[1] = b; c4[2] = c; c4[3] = d; s4[0] = 0f; s4[1] = sb; s4[2] = sc; s4[3] = 1f
        return LinearGradient(x0, y0, x1, y1, c4, s4, Shader.TileMode.CLAMP)
    }

    /** [linear] with stops resampled unpremultiplied (colour and alpha both change along it). */
    fun linearU(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int, c: Int, sb: Float): LinearGradient {
        c3[0] = a; c3[1] = b; c3[2] = c; s3[0] = 0f; s3[1] = sb; s3[2] = 1f
        resample(c3, s3, u3c, u3s)
        return LinearGradient(x0, y0, x1, y1, u3c, u3s, Shader.TileMode.CLAMP)
    }

    fun linearU(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int, c: Int, d: Int, sb: Float, sc: Float): LinearGradient {
        c4[0] = a; c4[1] = b; c4[2] = c; c4[3] = d; s4[0] = 0f; s4[1] = sb; s4[2] = sc; s4[3] = 1f
        resample(c4, s4, u4c, u4s)
        return LinearGradient(x0, y0, x1, y1, u4c, u4s, Shader.TileMode.CLAMP)
    }

    /** [oval] with stops resampled unpremultiplied. */
    fun ovalU(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, a: Int, b: Int) {
        c2[0] = a; c2[1] = b; s2[0] = 0f; s2[1] = 1f
        resample(c2, s2, u2c, u2s)
        c.unitOval(fill, RadialGradient(0f, 0f, 1f, u2c, u2s, Shader.TileMode.CLAMP), cx, cy, rx, ry)
    }

    private fun resample(colors: IntArray, stops: FloatArray, outC: IntArray, outS: FloatArray) {
        var k = 0
        for (i in 0 until colors.size - 1) for (j in 0 until SUB) {
            val f = j / SUB.toFloat()
            outC[k] = GlassColor.mix(colors[i], colors[i + 1], f)
            outS[k] = stops[i] + (stops[i + 1] - stops[i]) * f
            k++
        }
        outC[k] = colors[colors.size - 1]
        outS[k] = stops[stops.size - 1]
    }

    /** sprites.js oval(): a unit radial gradient drawn as an ellipse by moving the canvas (GlassKit.unitOval). */
    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, a: Int, b: Int) {
        c2[0] = a; c2[1] = b; s2[0] = 0f; s2[1] = 1f
        c.unitOval(fill, RadialGradient(0f, 0f, 1f, c2, s2, Shader.TileMode.CLAMP), cx, cy, rx, ry)
    }

    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, a: Int, b: Int, cc: Int, sb: Float) {
        c3[0] = a; c3[1] = b; c3[2] = cc; s3[0] = 0f; s3[1] = sb; s3[2] = 1f
        c.unitOval(fill, RadialGradient(0f, 0f, 1f, c3, s3, Shader.TileMode.CLAMP), cx, cy, rx, ry)
    }

    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, a: Int, b: Int, cc: Int, d: Int, sb: Float, sc: Float) {
        c4[0] = a; c4[1] = b; c4[2] = cc; c4[3] = d; s4[0] = 0f; s4[1] = sb; s4[2] = sc; s4[3] = 1f
        c.unitOval(fill, RadialGradient(0f, 0f, 1f, c4, s4, Shader.TileMode.CLAMP), cx, cy, rx, ry)
    }

    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray) =
        c.unitOval(fill, RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP), cx, cy, rx, ry)

    /** Solid to [inner] of the radius, then falling to clear: replaces every blurred glow and shadow. */
    fun halo(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, a: Float, inner: Float) {
        val i = inner.coerceIn(0f, 0.999f)
        oval(c, cx, cy, rx, ry, GlassColor.alpha(color, a), GlassColor.alpha(color, a), GlassColor.alpha(color, a * 0.38f), GlassColor.clear(color),
            i, i + (1f - i) * 0.45f)
    }

    /** The shape minus itself moved by [dy] (GlassGlyphs' lit / shaded edge), in [color]. */
    fun edgeBand(c: Canvas, shape: Path, dy: Float, color: Int) {
        band.set(shape)
        moved.set(shape)
        moved.offset(0f, dy)
        band.op(moved, Path.Op.DIFFERENCE)
        c.drawPath(band, solid(fill, color))
    }

    /** The silhouette moved down [dy] under a top-to-bottom fade: a crisp, shape-following lift. */
    fun drop(c: Canvas, shape: Path, box: RectF, dy: Float, color: Int, a: Float) {
        c.save()
        c.translate(0f, dy)
        c.drawPath(shape, fill.shade(linear(0f, box.top, 0f, box.bottom,
            GlassColor.alpha(color, a * 0.25f), GlassColor.alpha(color, a * 0.7f), GlassColor.alpha(color, a), 0.6f)))
        c.restore()
    }

    /** sprites.js glassTint (GlassGlyphs TINT): glow, shadow, drop, tinted body, core light, frost, gloss, edges, hairline. */
    fun glassTint(c: Canvas, t: GlassTheme, path: Path, box: RectF, hue: Int, o: TintSpec) {
        if (o.glow) halo(c, o.glowCx, o.glowCy, o.glowRx, o.glowRy, hue, o.glowA, o.glowInner)
        if (o.shadow) halo(c, o.shCx, o.shCy, o.shRx, o.shRy, t.shadow, o.shA, 0.55f)
        if (o.drop) drop(c, path, box, o.dropDy, t.shadow, o.dropA)
        val body = if (t.night || o.bright) hue else GlassColor.darken(hue, 0.12f)
        fill.shade(linear(0f, box.top, 0f, box.bottom, GlassColor.lighten(body, if (o.bright) 0.78f else 0.62f), GlassColor.lighten(body, 0.22f),
            body, GlassColor.darken(body, 0.36f), 0.28f, 0.62f)).alpha = (o.bodyA * 255f).roundToInt()
        c.drawPath(path, fill)
        fill.alpha = 255
        val m = max(box.width(), box.height())
        c.save()
        c.clipPath(path)
        // coloured glass holds light in its core; a milky frost thins out downward
        ovalU(c, box.centerX(), box.top + box.height() * 0.6f, m * 0.55f, m * 0.55f,
            GlassColor.alpha(GlassColor.lighten(hue, 0.7f), if (o.bright) 0.7f else 0.42f), GlassColor.clear(hue))
        c.drawRect(box.left - 2f, box.top - 2f, box.right + 2f, box.bottom + 2f,
            fill.shade(linear(0f, box.top, 0f, box.top + box.height() * 0.62f, GlassColor.alpha(WHITE, 0.22f), GlassColor.clear(WHITE))))
        if (o.gloss) oval(c, o.glossCx, o.glossCy, o.glossRx, o.glossRy, GlassColor.alpha(WHITE, 0.62f), GlassColor.alpha(WHITE, 0.2f), GlassColor.clear(WHITE), 0.7f)
        oval(c, box.left + box.width() * 0.3f, box.top + box.height() * 0.1f, m * 0.7f, m * 0.7f, GlassColor.alpha(WHITE, 0.32f), GlassColor.clear(WHITE))
        c.restore()
        edgeBand(c, path, max(0.9f, box.height() * 0.045f), GlassColor.alpha(WHITE, 0.82f))
        edgeBand(c, path, -max(1f, box.height() * 0.05f), GlassColor.alpha(BLACK, if (t.night) 0.42f else 0.3f))
        line.strokeWidth = 1f
        line.strokeJoin = Paint.Join.MITER
        c.drawPath(path, line.shade(linearU(box.left, box.top, box.right, box.bottom, GlassColor.alpha(WHITE, 0.75f), GlassColor.alpha(WHITE, 0.18f),
            GlassColor.alpha(GlassColor.darken(hue, 0.55f), 0.7f), 0.45f)))
        line.shader = null
    }

    /** Neutral glass shell (GlassWidgetView body) for a round shape, its shadows as halos about ([cx], [cy], [r]). */
    fun shellBack(c: Canvas, t: GlassTheme, path: Path, box: RectF, tint: Float, cx: Float, cy: Float, r: Float) {
        halo(c, cx + 2f, cy + 3f, r + 4f, r + 4.5f, t.shadow, t.shadowA * 0.55f, r / (r + 4f))
        halo(c, cx + 4f, cy + 8f, r + 11f, r + 12f, t.shadow, t.shadowA * 0.7f, r * 0.8f / (r + 11f))
        c.save()
        c.clipPath(path)
        c.drawRect(box.left - 1f, box.top - 1f, box.right + 1f, box.bottom + 1f, fill.shade(linear(
            box.left + box.width() * 0.38f, box.top, box.left + box.width() * 0.62f, box.bottom,
            GlassColor.alpha(t.glassTop, tint), GlassColor.alpha(t.glassBot, tint), GlassColor.alpha(t.glassBot, tint), 0.72f)))
        oval(c, box.left + box.width() * 0.06f, box.top - box.height() * 0.12f, box.width() * 1.3f, box.height() * 0.95f,
            GlassColor.alpha(t.glassTop, tint * 0.75f), GlassColor.clear(t.glassTop), GlassColor.clear(t.glassTop), 0.62f)
        c.restore()
    }

    /** The shell's front glass, drawn over the content: top highlight, radial sheen, thickness, edges, graded outline. */
    fun shellFront(c: Canvas, t: GlassTheme, path: Path, box: RectF) {
        c.save()
        c.clipPath(path)
        c.drawRect(box, fill.shade(linear(0f, box.top, 0f, box.top + box.height() * 0.36f, GlassColor.alpha(WHITE, 0.075f), GlassColor.clear(WHITE))))
        oval(c, box.left, box.top, box.width() * 0.85f, box.height() * 0.7f, GlassColor.alpha(WHITE, 0.07f), GlassColor.clear(WHITE), GlassColor.clear(WHITE), 0.56f)
        c.drawRect(box, fill.shade(linear(0f, box.bottom - 12f, 0f, box.bottom, GlassColor.clear(t.edgeDark), GlassColor.alpha(t.edgeDark, t.thickA * 0.45f))))
        c.restore()
        edgeBand(c, path, 1.2f, GlassColor.alpha(WHITE, t.litA))
        edgeBand(c, path, -3f, GlassColor.alpha(t.edgeDark, t.thickA))
        line.strokeWidth = 1f
        line.strokeJoin = Paint.Join.MITER
        c.drawPath(path, line.shade(linearU(box.left, box.top, box.right, box.bottom,
            GlassColor.alpha(WHITE, t.outlineHi), GlassColor.alpha(WHITE, t.outlineHi * 0.22f),
            GlassColor.alpha(t.edgeDark, t.outlineLo * 0.5f), GlassColor.alpha(t.edgeDark, t.outlineLo), 0.34f, 0.7f)))
        line.shader = null
    }

    /** Teardrop: a circle of radius [r] over a rounded tip [h] below it; [out] gets its box (tip at the bottom). */
    fun dropPath(p: Path, cx: Float, cy: Float, r: Float, h: Float, out: RectF) {
        val d = r + h
        val phi = acos((r / d).toDouble())
        val th = PI / 2 - phi
        val tipY = cy + d
        val sx = cx + r * cos(th).toFloat()
        val sy = cy + r * sin(th).toFloat()
        p.rewind()
        p.moveTo(sx, sy)
        p.arcTo(cx - r, cy - r, cx + r, cy + r, Math.toDegrees(th).toFloat(), Math.toDegrees(2 * phi).toFloat() - 360f, false)
        p.quadTo(cx - 0.35f * h, tipY - 0.45f * h, cx - 1.2f, tipY - 1f)
        p.quadTo(cx, tipY + 0.4f, cx + 1.2f, tipY - 1f)
        p.quadTo(cx + 0.35f * h, tipY - 0.45f * h, sx, sy)
        p.close()
        out.set(cx - r, cy - r, cx + r, tipY)
    }
}

/** Options of [ArtKit.glassTint]; one reused instance, filled in before each draw. */
internal class TintSpec {
    var glow = false
    var glowCx = 0f; var glowCy = 0f; var glowRx = 0f; var glowRy = 0f; var glowA = 0f; var glowInner = 0f
    var shadow = false
    var shCx = 0f; var shCy = 0f; var shRx = 0f; var shRy = 0f; var shA = 0f
    var drop = false
    var dropDy = 0f; var dropA = 0f
    var bodyA = 1f
    var bright = false
    var gloss = false
    var glossCx = 0f; var glossCy = 0f; var glossRx = 0f; var glossRy = 0f
}

/** Path builder with Canvas 2D's arcTo(x1, y1, x2, y2, r): the corner at (x1, y1) rounded by a tangent arc. */
internal class ArcPath(val path: Path = Path()) {
    private var x = 0f
    private var y = 0f

    fun rewind() = path.rewind()
    fun moveTo(px: Float, py: Float) { path.moveTo(px, py); x = px; y = py }
    fun lineTo(px: Float, py: Float) { path.lineTo(px, py); x = px; y = py }
    fun quadTo(qx: Float, qy: Float, px: Float, py: Float) { path.quadTo(qx, qy, px, py); x = px; y = py }
    fun close() = path.close()

    fun arcTo(x1: Float, y1: Float, x2: Float, y2: Float, r: Float) {
        val ax = x - x1; val ay = y - y1
        val bx = x2 - x1; val by = y2 - y1
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        // a degenerate or straight corner is a plain line to it (the Canvas 2D rule)
        if (r <= 0f || la < 1e-6f || lb < 1e-6f || abs(ax * by - ay * bx) < 1e-6f * la * lb) { lineTo(x1, y1); return }
        val ux = ax / la; val uy = ay / la
        val vx = bx / lb; val vy = by / lb
        val half = acos((ux * vx + uy * vy).coerceIn(-1f, 1f)) / 2f
        val d = r / tan(half)
        val t1x = x1 + ux * d; val t1y = y1 + uy * d
        val t2x = x1 + vx * d; val t2y = y1 + vy * d
        val wl = hypot(ux + vx, uy + vy)
        val cd = r / sin(half)
        val ccx = x1 + (ux + vx) / wl * cd
        val ccy = y1 + (uy + vy) / wl * cd
        path.lineTo(t1x, t1y)
        val a0 = atan2(t1y - ccy, t1x - ccx)
        var sweep = atan2(t2y - ccy, t2x - ccx) - a0
        if (sweep > PI.toFloat()) sweep -= (2 * PI).toFloat()
        if (sweep < -PI.toFloat()) sweep += (2 * PI).toFloat()
        path.arcTo(ccx - r, ccy - r, ccx + r, ccy + r, Math.toDegrees(a0.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false)
        x = t2x; y = t2y
    }
}
