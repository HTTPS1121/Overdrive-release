package com.overdrive.app.navmap.glass.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.overdrive.app.navmap.glass.art.ArtKit.WHITE
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassFonts
import com.overdrive.app.overlay.glass.GlassPaths
import com.overdrive.app.overlay.glass.GlassRoadSenseView
import com.overdrive.app.overlay.glass.GlassTheme

/** Hazard gems, contact shadow, cluster orb and route pins (SPEC §5.1–5.4, sprites.js), in dp on [ArtKit.canvas]. */
internal object MarkerArt {
    const val POTHOLE = 0
    const val BREAKER = 1
    const val ROUGH = 2
    const val UNKNOWN = 3

    const val GEM_W = 100f
    const val GEM_H = 104f
    const val CONTACT_SIZE = 44f
    const val ORB_SIZE = 90f
    const val PIN_W = 84f
    const val PIN_H = 104f

    private const val TIP_Y = 84f
    private val SEV_S = floatArrayOf(0.95f, 1.1f, 1.28f)
    private val SEV_GLOW = floatArrayOf(0.30f, 0.48f, 0.72f)
    private val SEV_SPREAD = floatArrayOf(11f, 14f, 18f)
    // the RoadSense widget's pictograms (±23 units); "unknown" is the map's own mark
    private const val PICTO_UNKNOWN = "M-4.6 -17 H4.6 L3.1 5 H-3.1 Z M0 9.8 A4.6 4.6 0 1 1 -0.01 9.8 Z"
    private val PICTO_Y0 = floatArrayOf(-17f, -9f, -5f, -17f)
    private val PICTO_Y1 = floatArrayOf(20f, 14f, 14f, 14.4f)
    private const val FLAG = "M2.6 0.6 C7 -0.8 10 2.2 15.6 0.9 V11.6 C10 12.9 7 9.9 2.6 11.3 Z"

    private const val ORB_CX = 45f
    private const val ORB_CY = 41f
    private const val ORB_R = 24f
    private const val VIA_CX = 42f
    private const val VIA_CY = 53f
    private const val VIA_R = 17f
    private const val VIA_H = 14f
    // Strokes (dp) thickening Barlow 700 to the mock's rendered weight: the count is 700 under macOS's text dilation,
    // the stop number 800, which is not bundled.
    private const val CLUSTER_BOLDEN = 0.4f
    private const val VIA_BOLDEN = 0.75f

    private val spec = TintSpec()
    private val gem = ArcPath()
    private val pic = Path()
    private val orb = Path().apply { addCircle(ORB_CX, ORB_CY, ORB_R, Path.Direction.CW) }
    private val orbBox = RectF(ORB_CX - ORB_R, ORB_CY - ORB_R, ORB_CX + ORB_R, ORB_CY + ORB_R)
    private val ringHi = RectF(ORB_CX - ORB_R - 1.6f, ORB_CY - ORB_R - 1.6f, ORB_CX + ORB_R + 1.6f, ORB_CY + ORB_R + 1.6f)
    private val drop = Path()
    private val dropBox = RectF()
    private val m = Matrix()

    private fun picto(kind: Int): String = when (kind) {
        POTHOLE -> GlassRoadSenseView.Kind.POTHOLE.picto
        BREAKER -> GlassRoadSenseView.Kind.BREAKER.picto
        ROUGH -> GlassRoadSenseView.Kind.ROUGH.picto
        else -> PICTO_UNKNOWN
    }

    /** Glass tint per kind: the glyph carries the kind, the colour backs it up (never the only cue). */
    fun hue(t: GlassTheme, kind: Int): Int = when (kind) {
        POTHOLE -> t.glowDanger
        BREAKER -> t.glowWarn
        ROUGH -> t.fuel
        else -> if (t.night) GlassColor.mix(t.text, t.glassTop, 0.45f) else GlassColor.mix(t.text, t.glassBot, 0.35f)
    }

    /** od-gem-{kind}-{sev}: a tinted glass badge whose drip tip sits 20 dp above the bottom for every severity. */
    fun gem(t: GlassTheme, kind: Int, sev: Int, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(GEM_W, GEM_H, k, dpi)
        val c = ArtKit.canvas
        val i = sev.coerceIn(1, 3) - 1
        val s = SEV_S[i]
        val spread = SEV_SPREAD[i]
        val top = TIP_Y - 45f * s
        val box = ArtKit.bb
        gemPath(GEM_W / 2f, top, s, box)
        val cx = GEM_W / 2f
        val half = 18f * s
        val cy = top + half
        val hue = hue(t, kind)
        val day = if (t.night) 1f else 0.62f
        if (i == 2) {
            // urgent: a thin outer ring under everything, the frame the pulse would rest on
            ArtKit.line.strokeWidth = 2f
            c.drawCircle(cx, cy, half + 7.5f, ArtKit.solid(ArtKit.line, GlassColor.alpha(hue, 0.55f * day + 0.12f)))
        }
        spec.glow = true
        spec.glowCx = cx; spec.glowCy = cy + 2f; spec.glowRx = half + spread; spec.glowRy = half + spread + 3f
        spec.glowA = SEV_GLOW[i] * day; spec.glowInner = half / (half + spread)
        spec.shadow = true
        spec.shCx = cx; spec.shCy = cy + 5f; spec.shRx = half + 3f; spec.shRy = half + 5f; spec.shA = if (t.night) 0.5f else 0.28f
        spec.drop = true
        spec.dropDy = 1.6f; spec.dropA = if (t.night) 0.55f else 0.3f
        spec.bodyA = 0.93f
        spec.bright = i == 2
        spec.gloss = true
        spec.glossCx = cx; spec.glossCy = top + 6.5f * s; spec.glossRx = 13.5f * s; spec.glossRy = 8f * s
        ArtKit.glassTint(c, t, gem.path, box, hue, spec)
        // the white pictogram over a tinted copy 1.1 dp lower, so it holds on the lighter glass
        val gk = (if (kind == UNKNOWN) 0.66f else 0.52f) * s
        val gy = cy - (PICTO_Y0[kind] + PICTO_Y1[kind]) / 2f * gk
        drawPicto(c, kind, gk, cx, gy + 1.1f, GlassColor.alpha(GlassColor.darken(hue, 0.6f), 0.5f))
        drawPicto(c, kind, gk, cx, gy, WHITE)
        ArtKit.end()
        return bmp
    }

    private fun drawPicto(c: Canvas, kind: Int, scale: Float, x: Float, y: Float, color: Int) {
        m.setScale(scale, scale)
        m.postTranslate(x, y)
        GlassPaths.svg(picto(kind)).transform(m, pic)
        pic.fillType = Path.FillType.EVEN_ODD
        c.drawPath(pic, ArtKit.solid(ArtKit.fill, color))
    }

    // squircle badge with a short drip-shaped pointer whose tip is the hazard's location
    private fun gemPath(cx: Float, top: Float, s: Float, out: RectF) {
        val side = 36f * s
        val r = 12f * s
        val x0 = cx - side / 2f; val x1 = cx + side / 2f
        val y0 = top; val y1 = top + side
        val pw = 8f * s; val ph = 9f * s
        val p = gem
        p.rewind()
        p.moveTo(x0 + r, y0); p.lineTo(x1 - r, y0); p.arcTo(x1, y0, x1, y0 + r, r)
        p.lineTo(x1, y1 - r); p.arcTo(x1, y1, x1 - r, y1, r)
        p.lineTo(cx + pw, y1)
        p.quadTo(cx + pw * 0.3f, y1 + 0.6f * s, cx + 1.9f * s, y1 + ph - 1.8f * s)
        p.quadTo(cx, y1 + ph + 0.5f * s, cx - 1.9f * s, y1 + ph - 1.8f * s)
        p.quadTo(cx - pw * 0.3f, y1 + 0.6f * s, cx - pw, y1)
        p.lineTo(x0 + r, y1); p.arcTo(x0, y1, x0, y1 - r, r)
        p.lineTo(x0, y0 + r); p.arcTo(x0, y0, x0 + r, y0, r)
        p.close()
        out.set(x0, y0, x1, y1 + ph)
    }

    /** od-contact: the flat contact shadow under a standing pin (map-aligned, so perspective squashes it). */
    fun contact(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(CONTACT_SIZE, CONTACT_SIZE, k, dpi)
        ArtKit.oval(ArtKit.canvas, 22f, 22f, 21f, 21f, GlassColor.alpha(t.shadow, if (t.night) 0.7f else 0.42f),
            GlassColor.alpha(t.shadow, if (t.night) 0.35f else 0.2f), GlassColor.clear(t.shadow), 0.45f)
        ArtKit.end()
        return bmp
    }

    /** Everything of od-cluster-N under the count: amber halo and the neutral glass shell (tint .92). */
    fun clusterBack(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(ORB_SIZE, ORB_SIZE, k, dpi)
        val c = ArtKit.canvas
        ArtKit.halo(c, ORB_CX, ORB_CY, ORB_R + 15f, ORB_R + 15f, t.glowWarn, if (t.night) 0.5f else 0.34f, ORB_R / (ORB_R + 15f))
        ArtKit.shellBack(c, t, orb, orbBox, 0.92f, ORB_CX, ORB_CY, ORB_R)
        ArtKit.end()
        return bmp
    }

    /** Everything of od-cluster-N over the count: front glass and the amber ring with its lit arc. */
    fun clusterFront(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(ORB_SIZE, ORB_SIZE, k, dpi)
        val c = ArtKit.canvas
        ArtKit.shellFront(c, t, orb, orbBox)
        val p = ArtKit.line
        p.strokeWidth = 2.8f
        c.drawCircle(ORB_CX, ORB_CY, ORB_R + 1.6f, ArtKit.solid(p, t.glowWarn))
        p.strokeWidth = 1f
        c.drawArc(ringHi, 189f, 117f, false, ArtKit.solid(p, GlassColor.alpha(GlassColor.lighten(t.glowWarn, 0.6f), 0.9f)))
        ArtKit.end()
        return bmp
    }

    /** The count: Barlow 700 look, 24 sp (19 sp for three characters), baseline 8.6 dp below the orb's centre. */
    fun clusterLabel(context: Context, c: Canvas, t: GlassTheme, label: String, start: Int, end: Int) {
        val p = ArtKit.text
        p.typeface = GlassFonts.get(context, 700)
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = CLUSTER_BOLDEN
        p.strokeJoin = Paint.Join.ROUND
        p.textSize = if (end - start > 2) 19f else 24f
        p.color = t.text
        c.drawText(label, start, end, ORB_CX, ORB_CY + 8.6f, p)
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
    }

    /** od-pin-dest: lit glowAccent glass teardrop with a white chequered flag. */
    fun destination(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(PIN_W, PIN_H, k, dpi)
        val c = ArtKit.canvas
        val r = 21f
        val h = 17f
        val cy = TIP_Y - r - h
        val cx = PIN_W / 2f
        ArtKit.dropPath(drop, cx, cy, r, h, dropBox)
        spec.glow = true
        spec.glowCx = cx; spec.glowCy = cy + 3f; spec.glowRx = r + 14f; spec.glowRy = r + 17f
        spec.glowA = if (t.night) 0.55f else 0.36f; spec.glowInner = r / (r + 14f)
        spec.shadow = true
        spec.shCx = cx; spec.shCy = cy + 6f; spec.shRx = r + 3f; spec.shRy = r + 6f; spec.shA = if (t.night) 0.5f else 0.28f
        spec.drop = true
        spec.dropDy = 1.6f; spec.dropA = if (t.night) 0.55f else 0.3f
        spec.bodyA = 0.95f
        spec.bright = true
        spec.gloss = true
        spec.glossCx = cx; spec.glossCy = cy - 10f; spec.glossRx = 14.5f; spec.glossRy = 8.5f
        ArtKit.glassTint(c, t, drop, dropBox, t.glowAccent, spec)
        c.save()
        c.translate(cx - 9f, cy - 11.5f)
        c.scale(1.18f, 1.18f)
        val f = ArtKit.fill
        c.drawRect(0.4f, 1.2f, 3f, 20.7f, ArtKit.solid(f, GlassColor.alpha(GlassColor.darken(t.glowAccent, 0.6f), 0.45f)))
        c.drawRect(0f, 0f, 2.6f, 19.5f, ArtKit.solid(f, WHITE))
        val flag = GlassPaths.svg(FLAG)
        c.drawPath(flag, f)
        c.clipPath(flag)
        ArtKit.solid(f, GlassColor.darken(t.glowAccent, 0.45f))
        for (i in 0 until 4) for (j in 0 until 3) {
            if ((i + j) % 2 == 1) c.drawRect(2.6f + i * 3.25f, 0.2f + j * 3.75f, 2.6f + (i + 1) * 3.25f, 0.2f + (j + 1) * 3.75f, f)
        }
        c.restore()
        ArtKit.end()
        return bmp
    }

    /** Everything of od-via-N under the number: the accent glow and the neutral glass shell (tint .96). */
    fun viaBack(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(PIN_W, PIN_H, k, dpi)
        val c = ArtKit.canvas
        ArtKit.dropPath(drop, VIA_CX, VIA_CY, VIA_R, VIA_H, dropBox)
        ArtKit.halo(c, VIA_CX, VIA_CY + 2f, VIA_R + 12f, VIA_R + 14f, t.glowAccent, if (t.night) 0.42f else 0.24f, VIA_R / (VIA_R + 12f))
        ArtKit.shellBack(c, t, drop, dropBox, 0.96f, VIA_CX, VIA_CY + 4f, VIA_R + 3f)
        ArtKit.end()
        return bmp
    }

    /** Everything of od-via-N over the number: front glass and the 3 dp accent outline. */
    fun viaFront(t: GlassTheme, k: Float, dpi: Int): Bitmap {
        val bmp = ArtKit.begin(PIN_W, PIN_H, k, dpi)
        val c = ArtKit.canvas
        ArtKit.dropPath(drop, VIA_CX, VIA_CY, VIA_R, VIA_H, dropBox)
        ArtKit.shellFront(c, t, drop, dropBox)
        val p = ArtKit.line
        p.strokeWidth = 3f
        p.strokeJoin = Paint.Join.ROUND
        c.drawPath(drop, ArtKit.solid(p, viaInk(t)))
        p.strokeJoin = Paint.Join.MITER
        ArtKit.end()
        return bmp
    }

    /** The stop number: Barlow 800 look, 23 sp (17 sp from three digits), centred, baseline 8.2 dp below the circle's centre. */
    fun viaLabel(context: Context, c: Canvas, t: GlassTheme, label: String, start: Int, end: Int) {
        val p = ArtKit.text
        p.typeface = GlassFonts.get(context, 700)
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = VIA_BOLDEN
        p.strokeJoin = Paint.Join.ROUND
        p.textSize = if (end - start > 2) 17f else 23f
        p.color = viaInk(t)
        c.drawText(label, start, end, VIA_CX, VIA_CY + 8.2f, p)
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
    }

    private fun viaInk(t: GlassTheme) = if (t.night) t.glowAccent else t.accent

}
