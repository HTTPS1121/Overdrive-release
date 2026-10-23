package com.overdrive.app.navmap.ui

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.overdrive.app.overlay.glass.GlassGlyphs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** θ = ((after − before + 540) mod 360) − 180 in degrees, NaN when either bearing is unknown. */
internal fun turnAngle(before: Int, after: Int): Float =
    if (before < 0 || after < 0) Float.NaN else ((((after - before) % 360 + 540) % 360) - 180).toFloat()

/** Which glyph a maneuver draws. [angle] is the signed turn in degrees, or the side (+1 right, −1 left). */
internal data class GlyphSpec(val kind: Kind, val angle: Int, val exit: Int, val lht: Boolean) {
    enum class Kind { STRAIGHT, TURN, UTURN, KEEP, KEEP_STRAIGHT, EXIT, MERGE, ROUNDABOUT, ARRIVE, ARRIVE_SIDE, DEPART, FERRY }

    val key: String get() = "$kind:$angle:$exit:${if (lht) 1 else 0}"

    companion object {
        fun of(m: HudManeuver): GlyphSpec {
            val theta = turnAngle(m.bearingBefore, m.bearingAfter)
            val lht = m.leftHandTraffic
            return when (m.type) {
                9 -> GlyphSpec(Kind.TURN, sided(theta, 1, 15f, 70f, 35f), 0, lht)
                10 -> GlyphSpec(Kind.TURN, sided(theta, 1, 45f, 130f, 90f), 0, lht)
                11 -> GlyphSpec(Kind.TURN, sided(theta, 1, 110f, 165f, 140f), 0, lht)
                16 -> GlyphSpec(Kind.TURN, sided(theta, -1, 15f, 70f, 35f), 0, lht)
                15 -> GlyphSpec(Kind.TURN, sided(theta, -1, 45f, 130f, 90f), 0, lht)
                14 -> GlyphSpec(Kind.TURN, sided(theta, -1, 110f, 165f, 140f), 0, lht)
                12 -> GlyphSpec(Kind.UTURN, 1, 0, lht)
                13 -> GlyphSpec(Kind.UTURN, -1, 0, lht)
                23 -> GlyphSpec(Kind.KEEP, 1, 0, lht)
                24 -> GlyphSpec(Kind.KEEP, -1, 0, lht)
                22 -> GlyphSpec(Kind.KEEP_STRAIGHT, if (lht) -1 else 1, 0, lht)
                18, 20 -> GlyphSpec(Kind.EXIT, 1, 0, lht)
                19, 21 -> GlyphSpec(Kind.EXIT, -1, 0, lht)
                25 -> GlyphSpec(Kind.MERGE, if (lht) 1 else -1, 0, lht)
                37 -> GlyphSpec(Kind.MERGE, 1, 0, lht)
                38 -> GlyphSpec(Kind.MERGE, -1, 0, lht)
                26, 27 -> {
                    val a = if (theta.isNaN()) exitGuess(m.roundaboutExit, lht) else theta
                    GlyphSpec(Kind.ROUNDABOUT, a.coerceIn(-ROUNDABOUT_MAX, ROUNDABOUT_MAX).roundToInt(), m.roundaboutExit.coerceAtLeast(0), lht)
                }
                4 -> GlyphSpec(Kind.ARRIVE, 0, 0, lht)
                5 -> GlyphSpec(Kind.ARRIVE_SIDE, 1, 0, lht)
                6 -> GlyphSpec(Kind.ARRIVE_SIDE, -1, 0, lht)
                1, 2, 3 -> GlyphSpec(Kind.DEPART, 0, 0, lht)
                28, 29 -> GlyphSpec(Kind.FERRY, 0, 0, lht)
                else -> GlyphSpec(Kind.STRAIGHT, 0, 0, lht)
            }
        }

        // a bearing pair that disagrees with the type's side falls back to the type's nominal angle
        private fun sided(theta: Float, sign: Int, lo: Float, hi: Float, nominal: Float): Int {
            val a = if (theta.isNaN() || theta * sign <= 0f) nominal else abs(theta).coerceIn(lo, hi)
            return (a * sign).roundToInt()
        }

        // no bearings: assume four arms, first exit to the near side (right in right-hand traffic)
        private fun exitGuess(exit: Int, lht: Boolean): Float {
            if (exit <= 0) return 0f
            val a = 90f * (2 - exit)
            return if (lht) -a else a
        }

        const val ROUNDABOUT_MAX = 135f
    }
}

/** A glyph in a 100-unit box: the lit glass outline, a flat dim [ghost] stroke (other roads), an optional label. */
internal class GlyphShape(val lit: Path, val ghost: Path?, val ghostWidth: Float, val label: String?, val labelX: Float, val labelY: Float)

/** SPEC §6 turn-glyph geometry, extended to every maneuver kind. */
internal object GlyphShapes {
    private const val W = 15f
    private const val HEAD = 17f
    private const val BACK = 3f
    private const val HALF = 18.6f

    fun build(spec: GlyphSpec): GlyphShape = when (spec.kind) {
        GlyphSpec.Kind.TURN -> turn(spec.angle.toFloat())
        GlyphSpec.Kind.UTURN -> uturn(spec.angle.toFloat())
        GlyphSpec.Kind.KEEP -> keep(spec.angle.toFloat())
        GlyphSpec.Kind.KEEP_STRAIGHT -> keepStraight(spec.angle.toFloat())
        GlyphSpec.Kind.EXIT -> exit(spec.angle.toFloat())
        GlyphSpec.Kind.MERGE -> merge(spec.angle.toFloat())
        GlyphSpec.Kind.ROUNDABOUT -> roundabout(spec.angle.toFloat(), spec.lht, spec.exit)
        GlyphSpec.Kind.ARRIVE -> arrive()
        GlyphSpec.Kind.ARRIVE_SIDE -> arriveSide(spec.angle.toFloat())
        GlyphSpec.Kind.DEPART -> depart()
        GlyphSpec.Kind.FERRY -> ferry()
        GlyphSpec.Kind.STRAIGHT -> GlyphShape(straight(50f), null, 0f, null, 0f, 0f)
    }

    private fun solid(stroke: Path, width: Float = W, round: Boolean = true) = GlassGlyphs.outline(stroke, width, round)

    /** Triangle head: base centred [back] behind (ex, ey) along u, tip [len] ahead, [half] wide either side. */
    private fun head(ex: Float, ey: Float, ux: Float, uy: Float, len: Float = HEAD, back: Float = BACK, half: Float = HALF): Path {
        val bx = ex - ux * back; val by = ey - uy * back
        val nx = -uy; val ny = ux
        return Path().apply {
            moveTo(ex + ux * len, ey + uy * len)
            lineTo(bx + nx * half, by + ny * half)
            lineTo(bx - nx * half, by - ny * half)
            close()
        }
    }

    private fun union(a: Path, b: Path) = a.apply { op(b, Path.Op.UNION) }

    private fun straight(x: Float): Path {
        val s = Path().apply { moveTo(x, 95f); lineTo(x, 26f) }
        return union(solid(s), head(x, 26f, 0f, -1f))
    }

    // stem up to a junction, a 16-unit bend, then the exit along u (SPEC §6); up to 120°
    private fun branch(sx: Float, jy: Float, thetaDeg: Float, bend: Float, len: Float): Path {
        val th = Math.toRadians(thetaDeg.toDouble())
        val ux = sin(th).toFloat(); val uy = (-cos(th)).toFloat()
        val s = Path().apply {
            moveTo(sx, 95f); lineTo(sx, jy + bend)
            quadTo(sx, jy, sx + ux * bend, jy + uy * bend)
            lineTo(sx + ux * len, jy + uy * len)
        }
        return union(solid(s), head(sx + ux * len, jy + uy * len, ux, uy))
    }

    private fun ghostBranch(sx: Float, jy: Float, thetaDeg: Float, bend: Float, len: Float): Path {
        val th = Math.toRadians(thetaDeg.toDouble())
        val ux = sin(th).toFloat(); val uy = (-cos(th)).toFloat()
        return Path().apply {
            moveTo(sx, jy + bend)
            quadTo(sx, jy, sx + ux * bend, jy + uy * bend)
            lineTo(sx + ux * len, jy + uy * len)
        }
    }

    private fun turn(theta: Float): GlyphShape {
        if (abs(theta) <= 120f) {
            val sx = 50f - 17f * sin(Math.toRadians(theta.toDouble())).toFloat()
            return GlyphShape(branch(sx, 55f, theta, 16f, 31f), null, 0f, null, 0f, 0f)
        }
        return centred(hook(theta), null, 0f)
    }

    // past 120° the exit would fold back onto the stem: go up, across, then back down at θ
    private fun hook(theta: Float): Path {
        val sg = if (theta > 0f) 1f else -1f
        val a = abs(theta).coerceAtMost(170f)
        val r1 = 12f; val r2 = 10f; val jy = 40f; val le = 22f
        val delta = a - 90f
        val p = Path()
        p.moveTo(0f, 95f); p.lineTo(0f, jy)
        val c1x = sg * r1
        p.arcTo(RectF(c1x - r1, jy - r1, c1x + r1, jy + r1), if (sg > 0f) 180f else 0f, sg * 90f)
        val c2x = c1x; val c2y = jy - r1 + r2
        p.arcTo(RectF(c2x - r2, c2y - r2, c2x + r2, c2y + r2), 270f, sg * delta)
        val end = Math.toRadians((270f + sg * delta).toDouble())
        val p4x = c2x + r2 * cos(end).toFloat(); val p4y = c2y + r2 * sin(end).toFloat()
        val th = Math.toRadians((sg * a).toDouble())
        val ux = sin(th).toFloat(); val uy = (-cos(th)).toFloat()
        val ex = p4x + ux * le; val ey = p4y + uy * le
        p.lineTo(ex, ey)
        return union(solid(p), head(ex, ey, ux, uy))
    }

    private fun uturn(sg: Float): GlyphShape {
        val r = 15f; val top = 45f
        val sx = if (sg > 0f) 29.5f else 70.5f
        val ex = sx + sg * 2f * r
        val p = Path().apply {
            moveTo(sx, 95f); lineTo(sx, top)
            arcTo(RectF(sx + sg * r - r, top - r, sx + sg * r + r, top + r), if (sg > 0f) 180f else 0f, sg * 180f)
            lineTo(ex, 58f)
        }
        return GlyphShape(union(solid(p), head(ex, 58f, 0f, 1f)), null, 0f, null, 0f, 0f)
    }

    private fun keep(sg: Float) = centred(branch(50f, 60f, sg * 38f, 14f, 27f), ghostBranch(50f, 60f, -sg * 38f, 14f, 30f), GHOST_W)

    private fun keepStraight(ghostSide: Float) = centred(straight(50f), ghostBranch(50f, 62f, ghostSide * 40f, 14f, 30f), GHOST_W)

    // the road carries on (ghost) while the exit leaves it at 45°
    private fun exit(sg: Float): GlyphShape {
        val gx = 50f
        val ghost = Path().apply { moveTo(gx, 95f); lineTo(gx, 14f) }
        return centred(branch(gx, 68f, sg * 45f, 14f, 34f), ghost, GHOST_W)
    }

    // sg > 0: the lane joins a road to its right
    private fun merge(sg: Float): GlyphShape {
        val mx = 50f + sg * 12f
        val fx = 50f - sg * 14f
        val ghost = Path().apply { moveTo(mx, 95f); lineTo(mx, 40f) }
        val p = Path().apply {
            moveTo(fx, 95f); lineTo(fx, 76f)
            cubicTo(fx, 60f, mx, 64f, mx, 48f)
            lineTo(mx, 30f)
        }
        return centred(union(solid(p), head(mx, 30f, 0f, -1f)), ghost, GHOST_W)
    }

    // ring centred (50, 47): enter from the bottom, run the circulation direction, leave at θ
    private fun roundabout(theta: Float, lht: Boolean, exit: Int): GlyphShape {
        val cx = 50f; val cy = 47f; val r = 19f; val w = 12f
        val th = Math.toRadians(theta.toDouble())
        val ux = sin(th).toFloat(); val uy = (-cos(th)).toFloat()
        val sweep = if (lht) theta + 180f else theta - 180f
        val pex = cx + ux * r; val pey = cy + uy * r
        val ex = pex + ux * 8f; val ey = pey + uy * 8f
        val s = Path().apply {
            moveTo(cx, 95f); lineTo(cx, cy + r)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 90f, sweep)
            lineTo(ex, ey)
        }
        val lit = union(solid(s, w), head(ex, ey, ux, uy, 14f, 2f, 15f))
        val ring = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        return GlyphShape(lit, ring, 6f, if (exit > 0) exit.toString() else null, cx, cy)
    }

    private fun ring(cx: Float, cy: Float, r: Float, hole: Float) = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        op(Path().apply { addCircle(cx, cy, hole, Path.Direction.CW) }, Path.Op.DIFFERENCE)
    }

    private fun arrive(): GlyphShape {
        val stem = Path().apply { moveTo(50f, 95f); lineTo(50f, 48f) }
        return GlyphShape(union(solid(stem), ring(50f, 33f, 17f, 7f)), null, 0f, null, 0f, 0f)
    }

    private fun arriveSide(sg: Float): GlyphShape {
        val ax = 50f - sg * 14f
        val dot = ring(50f + sg * 22f, 44f, 13f, 5f)
        return centred(union(straight(ax), dot), null, 0f)
    }

    private fun depart(): GlyphShape {
        val stem = Path().apply { moveTo(50f, 76f); lineTo(50f, 28f) }
        val lit = union(union(solid(stem, W, false), head(50f, 28f, 0f, -1f)), ring(50f, 84f, 11f, 4.5f))
        return GlyphShape(lit, null, 0f, null, 0f, 0f)
    }

    private fun ferry(): GlyphShape {
        val hull = Path().apply {
            moveTo(13f, 57f); lineTo(87f, 57f); lineTo(78f, 72f)
            quadTo(76.5f, 75f, 73f, 75f); lineTo(27f, 75f)
            quadTo(23.5f, 75f, 22f, 72f); close()
        }
        val cabin = Path().apply { addRoundRect(RectF(29f, 41f, 67f, 59f), 3f, 3f, Path.Direction.CW) }
        val funnel = Path().apply { addRoundRect(RectF(50f, 29f, 60f, 43f), 2f, 2f, Path.Direction.CW) }
        val windows = Path().apply {
            addRect(34f, 45.5f, 40.5f, 51.5f, Path.Direction.CW)
            addRect(44.75f, 45.5f, 51.25f, 51.5f, Path.Direction.CW)
            addRect(55.5f, 45.5f, 62f, 51.5f, Path.Direction.CW)
        }
        val wave = Path().apply {
            moveTo(12f, 87f)
            quadTo(19.6f, 81f, 27.2f, 87f); quadTo(34.8f, 93f, 42.4f, 87f); quadTo(50f, 81f, 57.6f, 87f)
            quadTo(65.2f, 93f, 72.8f, 87f); quadTo(80.4f, 81f, 88f, 87f)
        }
        val lit = union(union(union(hull, cabin), funnel), solid(wave, 6f))
        lit.op(windows, Path.Op.DIFFERENCE)
        return GlyphShape(lit, null, 0f, null, 0f, 0f)
    }

    // shifts the drawing so lit ∪ ghost sits centred across the box
    private fun centred(lit: Path, ghost: Path?, ghostWidth: Float): GlyphShape {
        val b = RectF(); lit.computeBounds(b, true)
        if (ghost != null) {
            val g = RectF(); ghost.computeBounds(g, true)
            g.inset(-ghostWidth / 2f, -ghostWidth / 2f)
            b.union(g)
        }
        val m = Matrix().apply { setTranslate(50f - b.centerX(), 0f) }
        lit.transform(m)
        ghost?.transform(m)
        return GlyphShape(lit, ghost, ghostWidth, null, 0f, 0f)
    }

    private const val GHOST_W = 13f
}
