package com.overdrive.app.overlay.glass

import android.graphics.Matrix
import android.graphics.Path

/**
 * Glyphs for automation shortcuts on a 32-unit grid centred on (16, 16), each composed into one
 * solid outline: solid parts and strokes are merged, cut-outs (a keyhole, a lens) are subtracted,
 * and inlays (a clapper, a lens) are added back inside them. [GlassGlyphs] renders the outline as a
 * glass object.
 */
object GlassIcons {
    private enum class Kind { SOLID, CUT, INLAY }

    private class Part(val path: Path, val kind: Kind, val stroke: Float = 0f)

    private fun solid(d: String) = Part(GlassPaths.svg(d), Kind.SOLID)
    private fun line(d: String, w: Float = 2.8f) = Part(GlassPaths.svg(d), Kind.SOLID, w)
    private fun cut(d: String, w: Float = 0f) = Part(GlassPaths.svg(d), Kind.CUT, w)
    private fun inlay(d: String, w: Float = 0f) = Part(GlassPaths.svg(d), Kind.INLAY, w)

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy A$r $r 0 1 0 ${cx + r} $cy A$r $r 0 1 0 ${cx - r} $cy Z"

    private val glyphs: Map<String, List<Part>> by lazy {
        mapOf(
            "lock" to listOf(
                line("M10.5 15 V11 A5.5 5.5 0 0 1 21.5 11 V15", 3.2f),
                solid("M10.5 14.5 H21.5 A3.5 3.5 0 0 1 25 18 V24.5 A3.5 3.5 0 0 1 21.5 28 H10.5 A3.5 3.5 0 0 1 7 24.5 V18 A3.5 3.5 0 0 1 10.5 14.5 Z"),
                cut(circle(16f, 20.2f, 2.2f)), cut("M15 20.4 H17 V24.6 H15 Z"),
            ),
            "fan" to fan(),
            "window" to listOf(
                solid("M5.5 24.5 L10.2 9.5 H24.8 A1.8 1.8 0 0 1 26.6 11.3 V24.5 Z"),
                cut("M7.6 17.6 H25.2 V19.6 H7.1 Z"),
            ),
            "boot" to listOf(
                solid("M3.5 24.5 V18.4 L8.4 11 H19.8 L25.8 17.4 H28.2 V24.5 Z"),
                line("M19.8 11 L26.8 5.2", 2.6f),
                cut(circle(9.4f, 24.6f, 3.6f)), cut(circle(22.4f, 24.6f, 3.6f)),
                inlay(circle(9.4f, 24.6f, 2.5f)), inlay(circle(22.4f, 24.6f, 2.5f)),
            ),
            "lamp" to listOf(
                solid("M4 9.4 C13.2 8.4 17.4 12.4 17.4 16 C17.4 19.6 13.2 23.6 4 22.6 Z"),
                line("M21 10.6 L27.6 8.6 M21.6 16 H28.6 M21 21.4 L27.6 23.4", 2.6f),
            ),
            "bell" to listOf(
                solid("M16 4.5 C11.3 4.5 9 8.4 9 12.6 V17.6 L6.6 21.2 C6.2 21.8 6.6 22.6 7.3 22.6 H24.7 C25.4 22.6 25.8 21.8 25.4 21.2 L23 17.6 V12.6 C23 8.4 20.7 4.5 16 4.5 Z"),
                solid(circle(16f, 25.4f, 2.5f)),
            ),
            "cloud" to listOf(solid("M10 24.5 H23.5 A5.5 5.5 0 0 0 23.6 13.5 A7.5 7.5 0 0 0 9.4 15 A4.8 4.8 0 0 0 10 24.5 Z")),
            "terminal" to listOf(
                solid("M7.5 6.5 H24.5 A3.5 3.5 0 0 1 28 10 V22 A3.5 3.5 0 0 1 24.5 25.5 H7.5 A3.5 3.5 0 0 1 4 22 V10 A3.5 3.5 0 0 1 7.5 6.5 Z"),
                cut("M8 9.6 H24 A1.6 1.6 0 0 1 25.6 11.2 V21 A1.6 1.6 0 0 1 24 22.6 H8 A1.6 1.6 0 0 1 6.4 21 V11.2 A1.6 1.6 0 0 1 8 9.6 Z"),
                inlay("M9.6 12.8 L13.2 16 L9.6 19.2 M15.2 19.4 H21.6", 2.4f),
            ),
            "music" to listOf(
                line("M13 22.4 V8.4 L25 5.8 V19.4", 2.6f),
                solid("M13.2 22.4 A3.6 3 0 1 1 6 22.4 A3.6 3 0 1 1 13.2 22.4 Z"),
                solid("M25.2 19.4 A3.6 3 0 1 1 18 19.4 A3.6 3 0 1 1 25.2 19.4 Z"),
            ),
            "shield" to listOf(
                solid("M16 3.8 L26.2 7.6 V15 C26.2 21.2 21.7 25.9 16 28.3 C10.3 25.9 5.8 21.2 5.8 15 V7.6 Z"),
                cut("M11.2 15.6 L14.6 19 L21 12.4", 2.8f),
            ),
            "sparkle" to listOf(
                solid("M14 3.5 C15.1 10.4 16.6 11.9 23.5 13 C16.6 14.1 15.1 15.6 14 22.5 C12.9 15.6 11.4 14.1 4.5 13 C11.4 11.9 12.9 10.4 14 3.5 Z"),
                solid("M23.5 18 C24 21.3 24.7 22 28 22.5 C24.7 23 24 23.7 23.5 27 C23 23.7 22.3 23 19 22.5 C22.3 22 23 21.3 23.5 18 Z"),
            ),
            "camera" to listOf(
                solid("M6.5 10 H10.8 L12.8 7 H19.2 L21.2 10 H25.5 A2.5 2.5 0 0 1 28 12.5 V23 A2.5 2.5 0 0 1 25.5 25.5 H6.5 A2.5 2.5 0 0 1 4 23 V12.5 A2.5 2.5 0 0 1 6.5 10 Z"),
                cut(circle(16f, 17.6f, 5.6f)),
                inlay(circle(16f, 17.6f, 3.7f)),
            ),
            "flow" to listOf(
                line("M9.5 9.5 C19 9.5 13 22.5 22.5 22.5", 2.6f),
                solid(circle(9.5f, 9.5f, 3.8f)), solid(circle(22.5f, 22.5f, 3.8f)),
            ),
            "power" to listOf(line("M10.2 9.6 A9.2 9.2 0 1 0 21.8 9.6", 3f), line("M16 4.6 V15.6", 3f)),
            "bolt" to listOf(solid("M18.5 3.5 L8 18 H15 L13 28.5 L24 13.5 H17 Z")),
        )
    }

    // four swept blades round a hub, like the cabin fan
    private fun fan(): List<Part> {
        val parts = ArrayList<Part>()
        val m = Matrix()
        for (i in 0 until 4) {
            val blade = Path(GlassPaths.svg("M16 16 C14.4 11.4 15.8 6.4 20.2 5.2 C22.6 8.8 20.8 13.6 16 16 Z"))
            m.setRotate(i * 90f, 16f, 16f)
            blade.transform(m)
            parts.add(Part(blade, Kind.SOLID))
        }
        parts.add(Part(GlassPaths.svg(circle(16f, 16f, 3.2f)), Kind.SOLID))
        parts.add(Part(GlassPaths.svg(circle(16f, 16f, 1.2f)), Kind.CUT))
        return parts
    }

    private val outlines = HashMap<String, Path>()

    /** The glyph as one solid outline (copied, never the cached instance). */
    fun outline(glyph: String): Path {
        val cached = outlines.getOrPut(if (glyphs.containsKey(glyph)) glyph else "bolt") {
            val parts = glyphs[glyph] ?: glyphs.getValue("bolt")
            val shape = Path()
            fun shapeOf(p: Part) = if (p.stroke > 0f) GlassGlyphs.outline(p.path, p.stroke) else Path(p.path)
            for (p in parts) if (p.kind == Kind.SOLID) shape.op(shapeOf(p), Path.Op.UNION)
            for (p in parts) if (p.kind == Kind.CUT) shape.op(shapeOf(p), Path.Op.DIFFERENCE)
            for (p in parts) if (p.kind == Kind.INLAY) shape.op(shapeOf(p), Path.Op.UNION)
            shape
        }
        return Path(cached)
    }

    /** Each kind of action's glass colour, so a key reads at a glance: climate ice, locks mint, alerts amber. */
    fun hue(glyph: String): Int = when (glyph) {
        "lock" -> 0xFF2FF0BD.toInt()
        "fan" -> 0xFF4CC9FF.toInt()
        "window" -> 0xFF6FB6FF.toInt()
        "boot" -> 0xFFFFA23A.toInt()
        "lamp" -> 0xFFFFD24A.toInt()
        "bell" -> 0xFFFFB547.toInt()
        "cloud" -> 0xFF58B9FF.toInt()
        "terminal" -> 0xFF3BE07A.toInt()
        "music" -> 0xFFFF6FAE.toInt()
        "shield" -> 0xFF2CD4C4.toInt()
        "sparkle" -> 0xFFB08CFF.toInt()
        "camera" -> 0xFF8C9CFF.toInt()
        "flow" -> 0xFF7E8BFF.toInt()
        "power" -> 0xFFFF6A5F.toInt()
        else -> 0xFFFFD33D.toInt()
    }

    /** The empty key's plus, drawn faint rather than as glass. */
    val plus: Path by lazy { GlassPaths.svg("M16 9 V23 M9 16 H23") }
}
