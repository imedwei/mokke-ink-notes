package com.writer.ui.writing

import android.content.Context
import com.writer.model.InkStroke
import com.writer.model.StrokePoint

/**
 * Parses and renders Hershey single-stroke vector fonts (.jhf format).
 *
 * Each glyph is a series of polylines (pen-down segments separated by pen-up moves).
 * The output is single-stroke paths — ideal for simulating handwriting because the
 * data represents how a pen would actually draw each letter.
 *
 * Uses the `scripts.jhf` (cursive/script) font for handwriting-like appearance.
 */
class HersheyFont(rawLines: List<String>) {

    data class Glyph(
        val leftBound: Int,
        val rightBound: Int,
        val strokes: List<List<Pair<Float, Float>>>  // list of polylines
    )

    private val glyphs = mutableMapOf<Int, Glyph>()

    init {
        // Rejoin continuation lines (lines not starting with a digit at pos 0-4)
        val joined = mutableListOf<String>()
        for (line in rawLines) {
            if (line.length >= 5 && line.substring(0, 5).trim().toIntOrNull() != null) {
                joined.add(line)
            } else if (joined.isNotEmpty()) {
                joined[joined.lastIndex] = joined.last() + line
            }
        }

        for ((index, line) in joined.withIndex()) {
            if (line.length < 10) continue
            val leftBound = line[8].code - 'R'.code
            val rightBound = line[9].code - 'R'.code
            val data = line.substring(10)

            val strokes = mutableListOf<List<Pair<Float, Float>>>()
            var currentStroke = mutableListOf<Pair<Float, Float>>()

            var i = 0
            while (i < data.length - 1) {
                if (data[i] == ' ' && i + 1 < data.length && data[i + 1] == 'R') {
                    // Pen up
                    if (currentStroke.isNotEmpty()) {
                        strokes.add(currentStroke.toList())
                        currentStroke = mutableListOf()
                    }
                    i += 2
                } else {
                    val x = (data[i].code - 'R'.code).toFloat()
                    val y = (data[i + 1].code - 'R'.code).toFloat()
                    currentStroke.add(x to y)
                    i += 2
                }
            }
            if (currentStroke.isNotEmpty()) strokes.add(currentStroke)

            // Map: index 0 = ASCII 32 (space), index 1 = '!', etc.
            val charCode = 32 + index
            glyphs[charCode] = Glyph(leftBound, rightBound, strokes)
        }
    }

    /**
     * Convert text to a list of [InkStroke]s positioned at ([startX], [startY]).
     * Each polyline segment becomes a separate InkStroke.
     *
     * @param scale  multiplier applied to the raw font coordinates (~1 unit = 1px at scale 1)
     * @param jitter random position perturbation in pixels for a more natural look (0 = none)
     */
    fun textToStrokes(
        text: String,
        startX: Float,
        startY: Float,
        scale: Float,
        jitter: Float = 0f
    ): List<InkStroke> {
        val result = mutableListOf<InkStroke>()
        var cursorX = 0f
        val random = if (jitter > 0f) java.util.Random(text.hashCode().toLong()) else null

        for (char in text) {
            val glyph = glyphs[char.code] ?: continue
            for (stroke in glyph.strokes) {
                if (stroke.size < 2) continue
                val pts = stroke.mapIndexed { i, (x, y) ->
                    val jx = random?.let { (it.nextFloat() - 0.5f) * jitter * 2f } ?: 0f
                    val jy = random?.let { (it.nextFloat() - 0.5f) * jitter * 2f } ?: 0f
                    StrokePoint(
                        x = startX + (x - glyph.leftBound + cursorX) * scale + jx,
                        y = startY + y * scale + jy,
                        pressure = 0.5f,
                        timestamp = i.toLong()
                    )
                }
                result.add(InkStroke(points = pts))
            }
            cursorX += (glyph.rightBound - glyph.leftBound)
        }
        return result
    }

    companion object {
        /** Load the cursive Hershey font from assets. */
        fun loadScript(context: Context): HersheyFont {
            val lines = context.assets.open("scripts.jhf").bufferedReader().readLines()
            return HersheyFont(lines)
        }
    }
}
