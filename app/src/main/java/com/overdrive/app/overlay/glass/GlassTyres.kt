package com.overdrive.app.overlay.glass

import android.graphics.Path
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tyre warning bands, units, and the sag/taut puck each wheel of the power widget is drawn as. */
object GlassTyres {
    const val PULSE_MS = 1200.0

    enum class Kind { OK, LOW, HIGH, MISSING }

    /** [rank]: 0 in band, 1 no reading, 2 out of band, 3 critical. [b] shapes the puck: + sags (low), − goes taut (high). */
    class Info(val rank: Int, val kind: Kind, val crit: Boolean, val b: Float, val dev: Float)

    private val OK = Info(0, Kind.OK, false, 0f, 0f)
    private val MISSING = Info(1, Kind.MISSING, false, 0f, 0f)

    /**
     * [state] is core's verdict for the corner ("ok", "low", "high", "critical"): its own axle's limits,
     * the critical limit and the car's TPMS flags, which can call a tyre low inside the band. Without it
     * the band [lo]..[hi] (bar) decides; critical then sits 0.39 bar below and 0.3 bar above it.
     */
    fun classify(p: Float?, lo: Float, hi: Float, state: String? = null): Info {
        val reading = p != null && !p.isNaN()
        val dev = if (!reading) 0f else if (p!! < lo) lo - p else if (p > hi) p - hi else 0f
        when (state) {
            "critical" -> return Info(3, Kind.LOW, true, 0.92f, dev + 1f)
            "low" -> return Info(2, Kind.LOW, false, if (reading && p!! < lo) 0.45f + 0.3f * ((lo - p) / 0.39f).coerceIn(0f, 1f) else 0.5f, dev)
            "high" -> return Info(2, Kind.HIGH, false, -0.18f, dev)
            "ok" -> return if (reading) OK else MISSING
        }
        if (p == null || p.isNaN()) return MISSING
        val critLo = lo - 0.39f
        val critHi = hi + 0.3f
        return when {
            p < critLo -> Info(3, Kind.LOW, true, 0.85f + 0.15f * ((critLo - p) / 0.6f).coerceIn(0f, 1f), lo - p)
            p < lo -> Info(2, Kind.LOW, false, 0.45f + 0.3f * ((lo - p) / (lo - critLo)).coerceIn(0f, 1f), lo - p)
            p > critHi -> Info(3, Kind.HIGH, true, -0.3f, p - hi)
            p > hi -> Info(2, Kind.HIGH, false, -0.18f, p - hi)
            else -> OK
        }
    }

    /** psi and bar to one decimal, kPa whole (the TPMS reports whole kPa); psi converts like the web UI so both agree. */
    fun number(bar: Float, units: String): String {
        val kpa = (bar * 100f).roundToInt()
        return when (units) {
            "psi" -> String.format(Locale.US, "%.1f", kpa * KPA_TO_PSI)
            "kpa" -> kpa.toString()
            else -> String.format(Locale.US, "%.1f", kpa / 100.0)
        }
    }

    private const val KPA_TO_PSI = 0.1450377

    fun unit(units: String) = when (units) { "psi" -> "psi"; "kpa" -> "kPa"; else -> "bar" }

    /** Puck geometry for a wheel [w] wide and [l] long whose ground edge sits at [yb]. */
    class Geom {
        var cx = 0f; var yb = 0f; var yt = 0f; var len = 0f
        var ht = 0f; var hb = 0f; var bulge = 0f; var low = 0f; var high = 0f

        fun set(cx: Float, yb: Float, w: Float, l: Float, b: Float): Geom {
            low = max(0f, b); high = max(0f, -b)
            len = l * (1f - 0.14f * low + 0.05f * high)
            ht = (w / 2f) * (1f - 0.05f * low - 0.03f * high)
            hb = (w / 2f) * (1f + 0.3f * low - 0.16f * high)
            bulge = (w / 2f) * 0.2f * low
            this.cx = cx; this.yb = yb; yt = yb - len
            return this
        }
    }

    /** Low pressure sags and splays at the ground like the dashboard warning icon; high goes taut and round. */
    fun path(out: Path, g: Geom, dy: Float = 0f): Path {
        val cx = g.cx; val ht = g.ht; val hb = g.hb; val bulge = g.bulge
        val yb = g.yb + dy; val yt = yb - g.len
        val rt = min(ht * (0.82f + 0.18f * g.high), g.len * 0.42f)
        val rb = min(hb * (0.82f - 0.55f * g.low + 0.18f * g.high), g.len * 0.42f)
        val k = 0.5523f
        val mt = yt + rt; val mb = yb - rb; val span = mb - mt
        out.rewind()
        out.moveTo(cx, yt)
        out.lineTo(cx + ht - rt, yt)
        out.cubicTo(cx + ht - rt + k * rt, yt, cx + ht, mt - k * rt, cx + ht, mt)
        out.cubicTo(cx + ht, mt + span * 0.45f, cx + hb + bulge, mb - span * 0.22f, cx + hb, mb)
        out.cubicTo(cx + hb, mb + k * rb, cx + hb - rb + k * rb, yb, cx + hb - rb, yb)
        out.lineTo(cx - hb + rb, yb)
        out.cubicTo(cx - hb + rb - k * rb, yb, cx - hb, mb + k * rb, cx - hb, mb)
        out.cubicTo(cx - hb - bulge, mb - span * 0.22f, cx - ht, mt + span * 0.45f, cx - ht, mt)
        out.cubicTo(cx - ht, mt - k * rt, cx - ht + rt - k * rt, yt, cx - ht + rt, yt)
        out.close()
        return out
    }
}
