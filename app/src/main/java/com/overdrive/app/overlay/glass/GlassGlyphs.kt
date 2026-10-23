package com.overdrive.app.overlay.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Icons as small glass objects: a soft contact shadow, a body of polished chrome (or lit mint or
 * amber glass while the thing is on, with a glow), a bright rim where the light catches its top
 * edges, a darker rim underneath, and a specular gloss. Each shape is baked once per tone, theme
 * and scale into a sprite at device resolution; moving parts are their own sprites, moved with the
 * canvas, so animating one costs a bitmap draw.
 */
object GlassGlyphs {
    /** CHROME is polished metal; LIT and WARN are glowing mint and amber glass; TINT is glass in a colour of its own. */
    enum class Tone { CHROME, LIT, WARN, TINT }

    /** [box] is the design-unit rectangle the bitmap covers, shadow and glow included. */
    class Sprite(val bitmap: Bitmap, val box: RectF)

    private val cache = object : LinkedHashMap<String, Sprite>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sprite>?) = size > MAX
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * One drawing's sprite, held by the view that draws it, so a frame never builds a cache key or a
     * lambda: [get] goes to the shared cache only when the key, theme, scale, tone or tint changes,
     * and only then builds the shape. [key] names the shape (the same key must always mean the same shape).
     */
    class Slot {
        @PublishedApi internal var sprite: Sprite? = null
        @PublishedApi internal var key: String? = null
        @PublishedApi internal var stamp = 0L

        /** [tint] colours a TINT; [bright] makes it glow as if lit from inside; [k] is device px per design unit. */
        inline fun get(key: String, theme: GlassTheme, k: Float, tone: Tone, tint: Int = 0, bright: Boolean = false, shape: () -> Path): Sprite {
            val st = stampOf(theme, k, tone, tint, bright)
            val s = sprite
            if (s != null && stamp == st && key == this.key && !s.bitmap.isRecycled) return s
            return lookup(key, tone, theme, k, tint, bright, shape()).also { sprite = it; this.key = key; stamp = st }
        }
    }

    @PublishedApi internal fun stampOf(theme: GlassTheme, k: Float, tone: Tone, tint: Int, bright: Boolean): Long =
        (tint.toLong() shl 32) or ((k * 100f).roundToInt().toLong() and 0xFFFFFF shl 8) or (tone.ordinal.toLong() shl 2) or
            (if (bright) 2L else 0L) or (if (theme.night) 1L else 0L)

    /** The shared cache behind every slot; [shape] is rendered only if no slot has asked for this sprite yet. */
    @PublishedApi internal fun lookup(key: String, tone: Tone, theme: GlassTheme, k: Float, tint: Int, bright: Boolean, shape: Path): Sprite {
        val id = "$key|$tone|$tint|$bright|${theme.night}|${(k * 100f).roundToInt()}"
        cache[id]?.let { if (!it.bitmap.isRecycled) return it }
        return render(shape, tone, theme, k, tint, bright).also { cache[id] = it }
    }

    fun draw(c: Canvas, s: Sprite, alpha: Int = 255) {
        paint.alpha = alpha
        c.drawBitmap(s.bitmap, null, s.box, paint)
        paint.alpha = 255
    }

    /** A stroke as a fillable outline, so a stroked part can be lit and shaded like a solid one. */
    fun outline(stroke: Path, width: Float, round: Boolean = true): Path {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = width
            strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = if (round) Paint.Join.ROUND else Paint.Join.MITER
        }
        val out = Path()
        @Suppress("DEPRECATION") p.getFillPath(stroke, out)
        return out
    }

    private fun render(shape: Path, tone: Tone, t: GlassTheme, k: Float, tint: Int, bright: Boolean): Sprite {
        val b = RectF()
        shape.computeBounds(b, true)
        val pad = if (tone == Tone.CHROME) PAD else PAD_LIT
        val box = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad + 1.5f)
        val w = max(1, ceil(box.width() * k).toInt()); val h = max(1, ceil(box.height() * k).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w / box.width(), h / box.height())
        c.translate(-box.left, -box.top)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // lit glass throws its colour round it; everything sits on a soft contact shadow
        val hue = when (tone) { Tone.LIT -> t.glowAccent; Tone.WARN -> t.glowWarn; Tone.TINT -> tint; Tone.CHROME -> 0 }
        if (tone != Tone.CHROME) {
            val glowA = when {
                tone == Tone.TINT && !bright -> if (t.night) 0.32f else 0.2f
                else -> if (t.night) 0.75f else 0.5f
            }
            val glow = mask(shape, box, w, h, (if (bright) 3.8f else 3.2f) * k)
            p.color = GlassColor.alpha(hue, glowA)
            c.drawBitmap(glow, null, box, p)
            glow.recycle()
        }
        val shadow = mask(shape, box, w, h, 1.4f * k)
        p.color = GlassColor.alpha(t.shadow, if (t.night) 0.6f else 0.32f)
        c.save(); c.translate(0f, 1.4f); c.drawBitmap(shadow, null, box, p); c.restore()
        shadow.recycle()

        // the body: polished chrome on dark glass, deep graphite on light glass, or coloured glass
        val (colors, stops) = body(tone, t, hue, bright)
        // shade() resets the paint's alpha: the shadow's colour left it translucent, and alpha multiplies a shader
        p.shade(LinearGradient(0f, b.top, 0f, b.bottom, colors, stops, Shader.TileMode.CLAMP))
        c.drawPath(shape, p)
        p.shader = null
        // coloured glass holds light in its core
        if (tone != Tone.CHROME) {
            c.save(); c.clipPath(shape)
            p.shade(RadialGradient(b.centerX(), b.centerY() + b.height() * 0.1f, max(b.width(), b.height()) * 0.55f,
                intArrayOf(GlassColor.alpha(GlassColor.lighten(hue, 0.7f), if (bright) 0.75f else 0.45f), GlassColor.clear(hue)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP))
            c.drawRect(b, p)
            p.shader = null
            c.restore()
        }

        // light catches the upper edges and falls off under the lower ones
        val hi = Path(shape).apply { op(Path(shape).apply { offset(0f, max(0.9f, b.height() * 0.045f)) }, Path.Op.DIFFERENCE) }
        p.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (tone == Tone.CHROME) (if (t.night) 0.85f else 0.55f) else 0.8f)
        c.drawPath(hi, p)
        val lo = Path(shape).apply { op(Path(shape).apply { offset(0f, -max(1f, b.height() * 0.05f)) }, Path.Op.DIFFERENCE) }
        p.color = GlassColor.alpha(0xFF000000.toInt(), if (t.night) 0.4f else 0.3f)
        c.drawPath(lo, p)

        // a specular gloss high on the left, the way the widgets' own glass is lit
        c.save()
        c.clipPath(shape)
        val r = max(b.width(), b.height()) * 0.7f
        p.shade(RadialGradient(b.left + b.width() * 0.3f, b.top + b.height() * 0.1f, r,
            intArrayOf(GlassColor.alpha(0xFFFFFFFF.toInt(), if (tone == Tone.CHROME) 0.55f else 0.6f), GlassColor.clear(0xFFFFFFFF.toInt())),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP))
        c.drawRect(b, p)
        p.shader = null
        c.restore()
        return Sprite(bmp, box)
    }

    // chrome has a horizon: bright sky above, a dark band, a lighter ground reflection below
    private fun body(tone: Tone, t: GlassTheme, hue: Int, bright: Boolean): Pair<IntArray, FloatArray> = when (tone) {
        Tone.CHROME -> if (t.night)
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9F0EE.toInt(), 0xFF8D9B97.toInt(), 0xFFC5D0CD.toInt(), 0xFF7B8884.toInt()) to floatArrayOf(0f, 0.4f, 0.52f, 0.66f, 1f)
        else
            intArrayOf(0xFF7D8985.toInt(), 0xFF4D5855.toInt(), 0xFF222A28.toInt(), 0xFF46514E.toInt(), 0xFF1A211F.toInt()) to floatArrayOf(0f, 0.4f, 0.52f, 0.66f, 1f)
        else -> {
            val c = if (t.night || bright) hue else GlassColor.darken(hue, 0.12f)
            intArrayOf(GlassColor.lighten(c, if (bright) 0.78f else 0.62f), GlassColor.lighten(c, 0.22f), c, GlassColor.darken(c, 0.36f)) to floatArrayOf(0f, 0.28f, 0.62f, 1f)
        }
    }

    // the shape's coverage, blurred by [radiusPx], at the sprite's resolution
    private fun mask(shape: Path, box: RectF, w: Int, h: Int, radiusPx: Float): Bitmap {
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        c.scale(w / box.width(), h / box.height())
        c.translate(-box.left, -box.top)
        c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        val blurred = GlassBlur.blurAlpha(src, max(1, (radiusPx / 2f).roundToInt()))
        src.recycle()
        val a = blurred.extractAlpha()
        blurred.recycle()
        return a
    }

    private const val PAD = 4f
    private const val PAD_LIT = 8f
    // room for every widget's parts in both themes, the rotor's frames included
    private const val MAX = 160
}
