package com.overdrive.app.overlay.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint

/**
 * The shared type: a 15 sp label, a 38 sp key value (unit at half size and half opacity, tabular
 * digits) or a 28 sp word, and a 15 sp status line, stacked and vertically centred like the mock.
 * Strings are set on data change; drawing never formats or allocates.
 */
class GlassText(context: Context, private val theme: () -> GlassTheme) {
    val label = GlassFonts.paint(context, 500, 15f, 0)
    val num = GlassFonts.paint(context, 600, 38f, 0)
    val unit = GlassFonts.paint(context, 500, 19f, 0)
    val word = GlassFonts.paint(context, 600, 28f, 0)
    val status = GlassFonts.paint(context, 600, 15f, 0)

    var labelText = ""
    /** Value parts: number, unit, number, unit… (units may be empty). */
    val parts = arrayOfNulls<String>(4)
    var isWord = false
    var wordText = ""
    var statusText = ""
    var statusColor = 0
    /** Optional plain lead-in before the status, in the secondary colour: "38 min · Efficient". */
    var statusLead = ""
    /** Optional glyph before the status text, drawn in design units about 11×15. */
    var statusIcon: android.graphics.Path? = null
    var valueColor = 0

    fun setValue(n1: String, u1: String, n2: String? = null, u2: String? = null) {
        isWord = false
        parts[0] = n1; parts[1] = u1; parts[2] = n2; parts[3] = u2
    }

    fun setWord(text: String, color: Int = 0) {
        isWord = true
        wordText = text
        valueColor = color
    }

    /** Draws the column with its left edge at [x], centred in [top]..[bottom]. */
    fun draw(c: Canvas, x: Float, top: Float, bottom: Float) {
        val t = theme()
        label.color = t.text2
        num.color = t.text
        unit.color = GlassColor.alpha(t.text, 0.5f)
        word.color = if (valueColor != 0) valueColor else t.text
        status.color = if (statusColor != 0) statusColor else t.text2
        val valueH = if (isWord) 28f else 38f
        val block = LABEL_H + GAP + valueH + GAP + STATUS_H
        var y = top + (bottom - top - block) / 2f
        c.drawText(labelText, x, baseline(y, LABEL_H, label), label)
        y += LABEL_H + GAP
        if (isWord) {
            c.drawText(wordText, x, baseline(y, valueH, word), word)
        } else {
            val by = baseline(y, valueH, num)
            var cx = x
            var i = 0
            while (i < parts.size) {
                val n = parts[i] ?: break
                if (i > 0) cx += SPACE
                c.drawText(n, cx, by, num)
                cx += num.measureText(n)
                val u = parts[i + 1]
                if (!u.isNullOrEmpty()) {
                    cx += UNIT_GAP
                    c.drawText(u, cx, by, unit)
                    cx += unit.measureText(u)
                }
                i += 2
            }
        }
        y += valueH + GAP
        if (statusText.isNotEmpty() || statusLead.isNotEmpty()) {
            var sx = x
            statusIcon?.let { icon ->
                c.save(); c.translate(x, y + 1.5f)
                val ic = status.style; status.style = Paint.Style.FILL
                c.drawPath(icon, status)
                status.style = ic
                c.restore()
                sx += 17f
            }
            val by = baseline(y, STATUS_H, status)
            if (statusLead.isNotEmpty()) {
                val own = status.color
                status.color = t.text2
                c.drawText(statusLead, sx, by, status)
                sx += status.measureText(statusLead)
                if (statusText.isNotEmpty()) { c.drawText(SEP, sx, by, status); sx += status.measureText(SEP) }
                status.color = own
            }
            if (statusText.isNotEmpty()) c.drawText(statusText, sx, by, status)
        }
    }

    private val fm = Paint.FontMetrics()
    private fun baseline(top: Float, lineH: Float, p: Paint): Float {
        p.getFontMetrics(fm)
        return top + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    companion object {
        const val LABEL_H = 17.25f
        const val STATUS_H = 18f
        const val GAP = 9f
        const val SPACE = 10.6f
        const val UNIT_GAP = 3f
        private const val SEP = " · "
    }
}
