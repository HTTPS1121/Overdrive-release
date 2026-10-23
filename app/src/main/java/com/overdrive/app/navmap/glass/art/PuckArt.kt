package com.overdrive.app.navmap.glass.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.overdrive.app.navmap.glass.MapGlassPalette
import com.overdrive.app.navmap.glass.art.ArtKit.BLACK
import com.overdrive.app.navmap.glass.art.ArtKit.WHITE
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme
import com.overdrive.app.overlay.glass.shade
import kotlin.math.hypot

/**
 * The location puck (SPEC §4.4, §5.5) for MapLibre's location-indicator layer, and the turn-arrow head (§5.6).
 * The indicator draws each image as a square quad centred on the fix and sized by its width, so both puck
 * images are square and centred on the car point: chevron space (44, 46).
 */
internal object PuckArt {
    /** od-puck: the chevron's 88 × 92 canvas widened to a square about the car point. */
    const val PUCK_SIZE = 92f
    /** od-puck-shadow: the §5.5 pool as a disc, so the indicator rotating it with the bearing changes nothing. */
    const val SHADOW_SIZE = 72f
    const val HEAD_W = 120f
    const val HEAD_H = 112f

    private const val PUCK_DX = (PUCK_SIZE - 88f) / 2f
    // The mock lifts the side 3.5 dp in screen space; baked into the flat image it is foreshortened by cos(tilt), so it is
    // drawn 3.5 / cos 56° behind to keep that thickness at the follow camera's 55–57° tilt.
    const val SIDE_DY = 6.25f
    private const val GLOW_R = 36f
    private const val SHADOW_R = 35.5f
    // circle-blur 1 falls off as smoothstep(1 − r/R); sampled into a gradient
    private val GLOW_STOPS = FloatArray(13) { it / 12f }
    private val glowColors = IntArray(13)

    private val TIP_X = 44f; private val TIP_Y = 9f
    private val R_X = 76f; private val R_Y = 80f
    private val NOTCH_X = 44f; private val NOTCH_Y = 63f
    private val L_X = 12f; private val L_Y = 80f
    private val CHEV = floatArrayOf(TIP_X, TIP_Y, R_X, R_Y, NOTCH_X, NOTCH_Y, L_X, L_Y)
    private val RADII = floatArrayOf(2.6f, 3.8f, 2.4f, 3.8f)

    private val outer = ArcPath()
    private val inner = ArcPath()
    private val inset = FloatArray(8)
    private val lines = FloatArray(16)
    private val left = Path().apply { moveTo(TIP_X, TIP_Y - 4f); lineTo(NOTCH_X, NOTCH_Y + 6f); lineTo(L_X - 6f, L_Y + 4f); close() }
    private val right = Path().apply { moveTo(TIP_X, TIP_Y - 4f); lineTo(R_X + 6f, R_Y + 4f); lineTo(NOTCH_X, NOTCH_Y + 6f); close() }
    private val head = Path().apply { moveTo(60f, 32f); lineTo(98f, 84f); lineTo(22f, 84f); close() }
    private val headFacet = Path().apply { moveTo(60f, 32f); lineTo(102f, 86f); lineTo(60f, 86f); close() }

    init {
        roundedPoly(outer, CHEV)
        insetPoly(CHEV, 4f, inset)
        roundedPoly(inner, inset)
    }

    /** acc = mix(accent, glowAccent, .55). */
    private fun acc(t: GlassTheme) = MapGlassPalette.of(t.night).puck

    /** od-puck (bearing-image): §4.4 glow on the car point, the side baked [SIDE_DY] behind, then the faceted chevron. */
    fun puck(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(PUCK_SIZE, PUCK_SIZE, k, dpi)
        val c = ArtKit.canvas
        glow(c, t, PUCK_SIZE / 2f, PUCK_SIZE / 2f)
        c.translate(PUCK_DX, 0f)
        side(c, t, SIDE_DY)
        chevron(c, t)
        ArtKit.end()
        return bmp
    }

    /** od-puck-shadow (shadow-image): the ground pool, layer opacity baked in; the indicator's tilt displacement drops it. */
    fun shadow(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(SHADOW_SIZE, SHADOW_SIZE, k, dpi)
        val a = if (t.night) 0.62f else 0.36f
        ArtKit.oval(ArtKit.canvas, SHADOW_SIZE / 2f, SHADOW_SIZE / 2f, SHADOW_R, SHADOW_R, GlassColor.alpha(BLACK, a), GlassColor.alpha(BLACK, a * 0.62f),
            GlassColor.alpha(BLACK, a * 0.2f), GlassColor.clear(BLACK), 0.5f, 0.8f)
        ArtKit.end()
        return bmp
    }

    /** od-puck-glow: radius 36 dp, glowAccent at .24 day / .45 night, falling off like MapLibre's circle-blur 1. */
    fun glow(c: Canvas, t: GlassTheme, cx: Float, cy: Float) {
        val a = if (t.night) 0.45f else 0.24f
        for (i in GLOW_STOPS.indices) {
            val u = 1f - GLOW_STOPS[i]
            glowColors[i] = GlassColor.alpha(t.glowAccent, a * u * u * (3f - 2f * u))
        }
        ArtKit.oval(c, cx, cy, GLOW_R, GLOW_R, glowColors, GLOW_STOPS)
    }

    /** od-puck-side: the outer silhouette in darken(acc, .5 day / .62 night), [dy] toward the tail. */
    fun side(c: Canvas, t: GlassTheme, dy: Float) {
        c.save()
        c.translate(0f, dy)
        c.drawPath(outer.path, ArtKit.solid(ArtKit.fill, GlassColor.darken(acc(t), if (t.night) 0.62f else 0.5f)))
        c.restore()
    }

    /** The §5.5 chevron in its own 88 × 92 space: bright rim, two facets, sheen, crest, inner bevel, lit edge. */
    fun chevron(c: Canvas, t: GlassTheme) {
        val acc = acc(t)
        val f = ArtKit.fill
        c.drawPath(outer.path, f.shade(ArtKit.linear(0f, TIP_Y, 0f, L_Y, WHITE, GlassColor.mix(WHITE, t.accent, 0.14f))))
        c.save()
        c.clipPath(inner.path)
        // lit left facet, shaded right one: the glass is lit from the top-left
        c.drawPath(left, f.shade(ArtKit.linear(TIP_X, TIP_Y, L_X, L_Y, GlassColor.lighten(acc, 0.42f), GlassColor.lighten(acc, 0.1f), acc, 0.55f)))
        c.drawPath(right, f.shade(ArtKit.linear(TIP_X, TIP_Y, R_X, R_Y, GlassColor.darken(acc, 0.08f), GlassColor.darken(acc, 0.3f), GlassColor.darken(acc, 0.42f), 0.6f)))
        c.save()
        c.clipPath(left)
        c.translate(32.5f, 37f)
        c.rotate(Math.toDegrees(-0.42).toFloat())
        ArtKit.oval(c, 0f, 0f, 8f, 19.5f, GlassColor.alpha(WHITE, 0.75f), GlassColor.alpha(WHITE, 0.25f), GlassColor.clear(WHITE), 0.5f)
        c.restore()
        val l = ArtKit.line
        l.strokeWidth = 1.4f
        l.strokeCap = Paint.Cap.ROUND
        c.drawLine(TIP_X, TIP_Y + 5f, NOTCH_X, NOTCH_Y - 1f, l.shade(ArtKit.linear(0f, TIP_Y, 0f, NOTCH_Y, GlassColor.alpha(WHITE, 0.95f), GlassColor.alpha(WHITE, 0.15f))))
        l.shader = null
        l.strokeCap = Paint.Cap.BUTT
        c.restore()
        // a hairline of the facet colour just inside the rim reads as the rim's inner bevel
        l.strokeWidth = 1f
        l.strokeJoin = Paint.Join.MITER
        c.drawPath(inner.path, ArtKit.solid(l, GlassColor.alpha(GlassColor.darken(acc, 0.5f), 0.55f)))
        ArtKit.edgeBand(c, outer.path, 1.1f, GlassColor.alpha(WHITE, 0.9f))
    }

    /** od-head-shadow / od-head-rim / od-head: the flat turn-arrow head, base at y 84 where the shaft joins. */
    fun head(p: MapGlassPalette, part: Int, k: Float, dpi: Int): Bitmap {
        val t = p.theme
        val bmp = ArtKit.begin(HEAD_W, HEAD_H, k, dpi)
        val c = ArtKit.canvas
        val f = ArtKit.fill
        val l = ArtKit.line
        when (part) {
            HEAD_SHADOW -> ArtKit.oval(c, 60f, 64f, 50f, 40f, BLACK, GlassColor.alpha(BLACK, 0.6f), GlassColor.clear(BLACK), 0.55f)
            HEAD_RIM -> {
                c.drawPath(head, ArtKit.solid(f, p.arrowRim))
                l.strokeWidth = 6.4f
                l.strokeJoin = Paint.Join.ROUND
                c.drawPath(head, ArtKit.solid(l, p.arrowRim))
            }
            else -> {
                c.drawPath(head, ArtKit.solid(f, p.arrowBody))
                l.strokeWidth = 3f
                l.strokeJoin = Paint.Join.ROUND
                c.drawPath(head, ArtKit.solid(l, p.arrowBody))
                // a shallow crease: the right facet a touch darker, like the puck's
                c.save()
                c.clipPath(head)
                c.drawPath(headFacet, f.shade(ArtKit.linear(60f, 32f, 98f, 84f, GlassColor.alpha(t.edgeDark, if (t.night) 0.08f else 0.05f),
                    GlassColor.alpha(t.edgeDark, if (t.night) 0.24f else 0.15f))))
                c.restore()
            }
        }
        l.strokeJoin = Paint.Join.MITER
        ArtKit.end()
        return bmp
    }

    const val HEAD_BODY = 0
    const val HEAD_RIM = 1
    const val HEAD_SHADOW = 2

    // rounded polygon over (x, y) pairs: starts mid-edge so no corner is left sharp
    private fun roundedPoly(p: ArcPath, pts: FloatArray) {
        val n = pts.size / 2
        p.rewind()
        p.moveTo((pts[0] + pts[2]) / 2f, (pts[1] + pts[3]) / 2f)
        for (i in 1..n) {
            val ci = i % n
            val ni = (i + 1) % n
            p.arcTo(pts[ci * 2], pts[ci * 2 + 1], pts[ni * 2], pts[ni * 2 + 1], RADII[ci])
        }
        p.close()
    }

    // each edge moved [d] along its inward normal (clockwise on screen), neighbours intersected: true for the concave notch too
    private fun insetPoly(pts: FloatArray, d: Float, out: FloatArray) {
        val n = pts.size / 2
        for (i in 0 until n) {
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val j = (i + 1) % n
            val dx = pts[j * 2] - ax; val dy = pts[j * 2 + 1] - ay
            val len = hypot(dx, dy)
            lines[i * 4] = ax - dy / len * d
            lines[i * 4 + 1] = ay + dx / len * d
            lines[i * 4 + 2] = dx
            lines[i * 4 + 3] = dy
        }
        for (i in 0 until n) {
            val a = (i - 1 + n) % n
            val p1x = lines[a * 4]; val p1y = lines[a * 4 + 1]; val d1x = lines[a * 4 + 2]; val d1y = lines[a * 4 + 3]
            val p2x = lines[i * 4]; val p2y = lines[i * 4 + 1]; val d2x = lines[i * 4 + 2]; val d2y = lines[i * 4 + 3]
            val den = d1x * d2y - d1y * d2x
            val tt = ((p2x - p1x) * d2y - (p2y - p1y) * d2x) / den
            out[i * 2] = p1x + d1x * tt
            out[i * 2 + 1] = p1y + d1y * tt
        }
    }
}
