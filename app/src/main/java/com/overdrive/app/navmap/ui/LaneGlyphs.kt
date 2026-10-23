package com.overdrive.app.navmap.ui

import android.graphics.Matrix
import android.graphics.Path
import com.overdrive.app.overlay.glass.GlassPaths

/**
 * Lane arrows in a 40-unit box (SPEC §6 lane tray): every branch leaves one shared stem at x 20, stroked 5.2 with
 * round caps; heads are filled. Bits are Valhalla's lane directions; a reverse arrow curls toward the oncoming side.
 */
internal object LaneGlyphs {
    const val NONE = 1
    const val THROUGH = 2
    const val SHARP_LEFT = 4
    const val LEFT = 8
    const val SLIGHT_LEFT = 16
    const val SLIGHT_RIGHT = 32
    const val RIGHT = 64
    const val SHARP_RIGHT = 128
    const val REVERSE = 256
    const val MERGE_LEFT = 512
    const val MERGE_RIGHT = 1024
    const val ALL = 2047

    val BITS = intArrayOf(NONE, THROUGH, SHARP_LEFT, LEFT, SLIGHT_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, REVERSE, MERGE_LEFT, MERGE_RIGHT)

    class Arrow(val stem: Path, val head: Path?)

    private class Def(val stem: String, val head: String?)

    // right-hand definitions; left-hand mirrors are derived (only REVERSE differs by traffic side)
    private val defs: Map<Int, Def> = mapOf(
        NONE to Def("M20 35 V8", null),
        THROUGH to Def("M20 37 V12", "M20 2.5 L12.5 12.5 L27.5 12.5 Z"),
        LEFT to Def("M20 37 V27 Q20 17.5 10.5 17.5", "M1.5 17.5 L11 10 L11 25 Z"),
        SLIGHT_LEFT to Def("M20 37 V28 L12 17", "M6.5 9 L15.5 13.5 L8.5 19.5 Z"),
        SLIGHT_RIGHT to Def("M20 37 V28 L28 17", "M33.5 9 L24.5 13.5 L31.5 19.5 Z"),
        RIGHT to Def("M20 37 V27 Q20 17.5 29.5 17.5", "M38.5 17.5 L29 10 L29 25 Z"),
        SHARP_RIGHT to Def("M20 37 V14 Q20 -1.5 27 9 L31 15", "M36.7 22.6 L25 19.5 L37 10.5 Z"),
        SHARP_LEFT to Def("M20 37 V14 Q20 -1.5 13 9 L9 15", "M3.3 22.6 L15 19.5 L3 10.5 Z"),
        REVERSE to Def("M20 37 V17 A6 6 0 0 0 8 17 V23", "M8 32 L2 23.5 L14 23.5 Z"),
        MERGE_LEFT to Def("M20 37 V27 L12 19 V12", "M12 2.5 L4.5 12.5 L19.5 12.5 Z"),
        MERGE_RIGHT to Def("M20 37 V27 L28 19 V12", "M28 2.5 L20.5 12.5 L35.5 12.5 Z"),
    )

    private val rht = HashMap<Int, Arrow>()
    private val lht = HashMap<Int, Arrow>()

    fun arrow(bit: Int, leftHandTraffic: Boolean): Arrow {
        val cache = if (leftHandTraffic && bit == REVERSE) lht else rht
        return cache.getOrPut(bit) {
            val d = defs[bit] ?: defs.getValue(NONE)
            val stem = Path(GlassPaths.svg(d.stem))
            val head = d.head?.let { Path(GlassPaths.svg(it)) }
            if (leftHandTraffic && bit == REVERSE) {
                val m = Matrix().apply { setScale(-1f, 1f, 20f, 0f) }
                stem.transform(m); head?.transform(m)
            }
            Arrow(stem, head)
        }
    }

    /** A lone turn arrow shifts toward the lane's centre, so it reads centred in its cell. */
    fun nudge(dirs: Int, leftHandTraffic: Boolean): Float = when (dirs) {
        RIGHT -> -4.5f
        LEFT -> 4.5f
        SLIGHT_RIGHT -> -3f
        SLIGHT_LEFT -> 3f
        SHARP_RIGHT -> -5f
        SHARP_LEFT -> 5f
        MERGE_LEFT -> 4f
        MERGE_RIGHT -> -4f
        REVERSE -> if (leftHandTraffic) -5f else 5f
        else -> 0f
    }
}
