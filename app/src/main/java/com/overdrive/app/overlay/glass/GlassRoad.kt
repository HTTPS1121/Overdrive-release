package com.overdrive.app.overlay.glass

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Perspective road shared by the trip and RoadSense widgets. u = 0 at the horizon, 1 at the
 * widget's bottom edge. Builders write into caller-owned Paths so nothing allocates per frame.
 */
class GlassRoad(
    private val vpx: Float, private val yh: Float, private val yb: Float, private val half: Float,
    private val lean: Float = 0f, private val bend: Float = 0f, private val width: Float = 400f,
    private val mirror: Boolean = false, private val z0: Float = 4f,
) {
    fun fx(x: Float) = if (mirror) width - x else x
    fun yAt(u: Float) = yh + u * (yb - yh)
    private fun cRaw(u: Float) = vpx - lean * u + bend * sin(PI.toFloat() * u)
    fun hAt(u: Float) = half * u
    fun uOfZ(z: Float) = z0 / (z0 + max(0f, z))
    fun zOfU(u: Float) = z0 / max(1e-4f, u) - z0
    fun cAt(u: Float) = fx(cRaw(u))
    fun edgeX(u: Float, side: Float) = fx(cRaw(u) + side * hAt(u))

    private fun sample(u0: Float, u1: Float, i: Int, n: Int) = u0 + (u1 - u0) * (i.toFloat() / n).pow(1.7f)

    fun poly(out: Path, u0: Float, u1: Float, widen: Float = 1f): Path {
        out.rewind()
        for (i in 0..N) {
            val u = sample(u0, u1, i, N)
            val x = fx(cRaw(u) - hAt(u) * widen); val y = yAt(u)
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        for (i in N downTo 0) {
            val u = sample(u0, u1, i, N)
            out.lineTo(fx(cRaw(u) + hAt(u) * widen), yAt(u))
        }
        out.close()
        return out
    }

    /** Min and max x of [poly] over u0..u1 (the SVG bounding box of that lane). */
    fun xBounds(u0: Float, u1: Float, widen: Float, out: FloatArray) {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0..N) {
            val u = sample(u0, u1, i, N)
            val a = fx(cRaw(u) - hAt(u) * widen); val b = fx(cRaw(u) + hAt(u) * widen)
            lo = minOf(lo, a, b); hi = maxOf(hi, a, b)
        }
        out[0] = lo; out[1] = hi
    }

    fun edge(out: Path, u0: Float, u1: Float, side: Float, widen: Float = 1f): Path {
        out.rewind()
        for (i in 0..N) {
            val u = sample(u0, u1, i, N)
            val x = fx(cRaw(u) + side * hAt(u) * widen); val y = yAt(u)
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        return out
    }

    /** Centre-line dashes of constant world length; [offset] (m) scrolls them toward the viewer. */
    fun dashes(out: Path, u0: Float, u1: Float, offset: Float = 0f, period: Float = 3.2f, len: Float = 1.3f, rel: Float = 0.022f): Path {
        out.rewind()
        val zNear = zOfU(u1); val zFar = zOfU(max(u0, 0.012f))
        val ph = ((offset % period) + period) % period
        var z = -ph
        while (z < zFar) {
            val za = max(z, zNear); val zb = minOf(z + len, zFar)
            if (zb > za) {
                val ua = uOfZ(za); val ub = uOfZ(zb)
                val wa = max(0.3f, hAt(ua) * rel); val wb = max(0.3f, hAt(ub) * rel)
                out.moveTo(fx(cRaw(ua) - wa), yAt(ua))
                out.lineTo(fx(cRaw(ub) - wb), yAt(ub))
                out.lineTo(fx(cRaw(ub) + wb), yAt(ub))
                out.lineTo(fx(cRaw(ua) + wa), yAt(ua))
                out.close()
            }
            z += period
        }
        return out
    }

    companion object { private const val N = 36 }
}
