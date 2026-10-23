package com.overdrive.app.navmap.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme
import com.overdrive.app.overlay.glass.shade
import kotlin.math.ceil
import kotlin.math.max

/** A baked turn glyph: [sizeDp] is the glyph box, drawn with [padDp] of glow room on every side. */
internal class GlyphSprite(val bitmap: Bitmap, val sizeDp: Float, val padDp: Float) {
    val boxDp get() = sizeDp + 2f * padDp
}

/** Turn glyphs as tinted glass objects (SPEC §5.1 steps 1–6, gradient fills only), cached per shape, size and theme. */
internal object GlyphSprites {
    private val cache = object : LinkedHashMap<String, GlyphSprite>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GlyphSprite>?) = size > 24
    }

    fun get(spec: GlyphSpec, sizeDp: Float, u: Float, t: GlassTheme, glowK: Float, labelFace: Typeface): GlyphSprite {
        val id = "${spec.key}|$sizeDp|${(u * 100f).toInt()}|${t.night}|$glowK"
        cache[id]?.let { if (!it.bitmap.isRecycled) return it }
        return render(GlyphShapes.build(spec), sizeDp, u, t, glowK, labelFace).also { cache[id] = it }
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    private fun render(shape: GlyphShape, size: Float, u: Float, t: GlassTheme, glowK: Float, labelFace: Typeface): GlyphSprite {
        val pad = max(12f, size * 0.2222f)
        val box = size + 2f * pad
        val px = max(1, ceil(box * u).toInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / box, px / box)
        val s = size / 100f
        val m = Matrix().apply { setScale(s, s); postTranslate(pad, pad) }
        val lit = Path(shape.lit).apply { transform(m) }
        val b = RectF(); lit.computeBounds(b, true)
        val night = t.night
        val hue = if (night) t.glowAccent else t.accent
        val white = HudColors.WHITE
        val cx = b.centerX(); val cy = b.centerY()
        val sp = size * 0.16f
        val maxWH = max(b.width(), b.height())

        halo(c, cx, cy, b.width() / 2f + sp, b.height() / 2f + sp, hue, (if (night) 0.42f else 0.2f) * glowK, 0.42f)
        shape.ghost?.let { g ->
            val gp = Path(g).apply { transform(m) }
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = shape.ghostWidth * s
            p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = if (night) GlassColor.alpha(t.text, 0.2f) else GlassColor.alpha(t.ink, 0.16f)
            c.drawPath(gp, p)
            p.style = Paint.Style.FILL
        }
        halo(c, cx, cy + size * 0.06f, b.width() * 0.46f, b.height() * 0.44f, t.shadow, (if (night) 0.32f else 0.14f) * glowK, 0.55f)
        // drop: the silhouette moved down, a crisp shape-following lift
        val dy = max(1.2f, size * 0.025f)
        val dropA = if (night) 0.6f else 0.28f
        c.save(); c.translate(0f, dy)
        c.drawPath(lit, p.shade(LinearGradient(0f, b.top, 0f, b.bottom,
            intArrayOf(GlassColor.alpha(t.shadow, dropA * 0.25f), GlassColor.alpha(t.shadow, dropA * 0.7f), GlassColor.alpha(t.shadow, dropA)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)))
        c.restore()

        val bright = night
        val body = if (night || bright) hue else GlassColor.darken(hue, 0.12f)
        c.drawPath(lit, p.shade(LinearGradient(0f, b.top, 0f, b.bottom,
            intArrayOf(GlassColor.lighten(body, if (bright) 0.78f else 0.62f), GlassColor.lighten(body, 0.22f), body, GlassColor.darken(body, 0.36f)),
            floatArrayOf(0f, 0.28f, 0.62f, 1f), Shader.TileMode.CLAMP)))

        c.save()
        c.clipPath(lit)
        oval(c, cx, b.top + b.height() * 0.6f, maxWH * 0.55f, maxWH * 0.55f,
            intArrayOf(GlassColor.alpha(GlassColor.lighten(hue, 0.7f), if (bright) 0.7f else 0.42f), GlassColor.clear(hue)), floatArrayOf(0f, 1f))
        p.shade(LinearGradient(0f, b.top, 0f, b.top + b.height() * 0.62f, GlassColor.alpha(white, 0.22f), GlassColor.clear(white), Shader.TileMode.CLAMP))
        c.drawRect(b.left - 2f, b.top - 2f, b.right + 2f, b.bottom + 2f, p)
        oval(c, b.left + b.width() * 0.36f, b.top + b.height() * 0.18f, b.width() * 0.42f, b.height() * 0.2f,
            intArrayOf(GlassColor.alpha(white, 0.62f), GlassColor.alpha(white, 0.2f), GlassColor.clear(white)), floatArrayOf(0f, 0.7f, 1f))
        oval(c, b.left + b.width() * 0.3f, b.top + b.height() * 0.1f, maxWH * 0.7f, maxWH * 0.7f,
            intArrayOf(GlassColor.alpha(white, 0.32f), GlassColor.clear(white)), floatArrayOf(0f, 1f))
        c.restore()
        p.shader = null

        HudPaint.edgeBand(c, lit, max(0.9f, b.height() * 0.045f), GlassColor.alpha(white, 0.82f))
        HudPaint.edgeBand(c, lit, -max(1f, b.height() * 0.05f), GlassColor.alpha(0xFF000000.toInt(), if (night) 0.42f else 0.3f))

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        c.drawPath(lit, p.shade(LinearGradient(b.left, b.top, b.right, b.bottom,
            intArrayOf(GlassColor.alpha(white, 0.75f), GlassColor.alpha(white, 0.18f), GlassColor.alpha(GlassColor.darken(hue, 0.55f), 0.7f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)))
        p.shader = null
        p.style = Paint.Style.FILL

        shape.label?.let { label ->
            p.typeface = labelFace
            p.textSize = 21f * s
            p.textAlign = Paint.Align.CENTER
            p.color = t.text
            val lx = pad + shape.labelX * s
            val ly = pad + shape.labelY * s + 0.35f * p.textSize
            c.drawText(label, lx, ly, p)
            p.textAlign = Paint.Align.LEFT
            p.typeface = null
        }
        return GlyphSprite(bmp, size, pad)
    }

    // a halo or pool: solid out to [inner], then falling to clear (SPEC §5 halo)
    private fun halo(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, a: Float, inner: Float) {
        if (a <= 0f) return
        oval(c, cx, cy, rx, ry,
            intArrayOf(GlassColor.alpha(color, a), GlassColor.alpha(color, a), GlassColor.alpha(color, a * 0.38f), GlassColor.clear(color)),
            floatArrayOf(0f, inner, inner + (1f - inner) * 0.45f, 1f))
    }

    private fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray) {
        c.save()
        c.translate(cx, cy)
        c.scale(max(1e-3f, rx), max(1e-3f, ry))
        c.drawCircle(0f, 0f, 1f, p.shade(RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)))
        c.restore()
        p.shader = null
    }
}
