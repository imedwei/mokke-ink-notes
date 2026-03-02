package com.writer.ui.writing

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.view.HandwritingCanvasView

data class AnnotationStroke(
    val points: List<StrokePoint>,
    val color: Int,
    val strokeWidth: Float = 4f
)

data class TextAnnotation(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val size: Float = 34f,
    val centered: Boolean = false
)

data class TutorialData(
    val strokes: List<InkStroke>,
    val annotations: List<AnnotationStroke>,
    val textAnnotations: List<TextAnnotation>,
    val scrollOffsetY: Float,
    val textParagraphs: List<List<WritingCoordinator.TextSegment>>,
    val canvasContentHeight: Float = 0f
)

object TutorialContent {

    private val LINE_SPACING = HandwritingCanvasView.LINE_SPACING
    private val TOP_MARGIN = HandwritingCanvasView.TOP_MARGIN
    private val GUTTER_WIDTH = HandwritingCanvasView.GUTTER_WIDTH

    private val textPaint = Paint().apply {
        typeface = Typeface.create("cursive", Typeface.NORMAL)
        isAntiAlias = true
    }

    fun generate(canvasWidth: Int, canvasHeight: Int): TutorialData {
        val writingWidth = canvasWidth - GUTTER_WIDTH

        val strokes = mutableListOf<InkStroke>()
        val annotations = mutableListOf<AnnotationStroke>()
        val textAnnotations = mutableListOf<TextAnnotation>()

        val red = Color.rgb(200, 50, 50)
        val blue = Color.rgb(50, 50, 200)
        val green = Color.rgb(40, 150, 40)

        fun lineTop(idx: Int): Float = TOP_MARGIN + idx * LINE_SPACING
        fun baseline(idx: Int): Float = TOP_MARGIN + (idx + 1) * LINE_SPACING - 20f

        // All lines (0-9) are visible on the canvas starting from the top.
        // Lines 0-4 also appear rendered in the text view above.

        // --- Line 0: Heading "Shopping List" with underline ---
        strokes.addAll(textToStrokes("Shopping List", 60f, baseline(0), 64f))
        val underlineY = baseline(0) + 15f
        strokes.add(InkStroke(
            points = listOf(
                StrokePoint(55f, underlineY, 0.5f, 0L),
                StrokePoint(520f, underlineY, 0.5f, 0L)
            ),
            strokeWidth = 3f
        ))

        // --- Lines 1-2: List items with dash markers ---
        val listItems = listOf("Eggs" to 1, "Bread" to 2)
        for ((text, lineIdx) in listItems) {
            val dashY = baseline(lineIdx) - 20f
            strokes.add(InkStroke(
                points = listOf(
                    StrokePoint(60f, dashY, 0.5f, 0L),
                    StrokePoint(110f, dashY, 0.5f, 0L)
                ),
                strokeWidth = 2f
            ))
            strokes.addAll(textToStrokes(text, 140f, baseline(lineIdx), 64f))
        }

        // --- Lines 3-4: Fox text (multi-line concatenation demo) ---
        strokes.addAll(textToStrokes("The quick brown fox", 60f, baseline(3), 64f))
        strokes.addAll(textToStrokes("jumps over the lazy dog", 60f, baseline(4), 64f))

        // Scroll offset: start at the top so heading is visible
        val scrollOffset = 0f

        // --- Line 5: Strikethrough demo + scroll annotation ---
        strokes.addAll(textToStrokes("Hello beautiful world", 60f, baseline(5), 64f))

        val strikeY = baseline(5) - 22f
        annotations.add(makeLine(180f, strikeY, 400f, strikeY, red, 5f))
        textAnnotations.add(
            TextAnnotation("Strike through to delete words", 560f, strikeY + 10f, red, 32f)
        )

        // Blue arrow pointing to gutter (on first visible line)
        val arrowY = lineTop(2) + 40f
        annotations.addAll(
            makeArrow(writingWidth - 300f, arrowY, writingWidth - 30f, arrowY, blue)
        )
        textAnnotations.add(
            TextAnnotation("Drag in gutter to scroll", writingWidth - 680f, arrowY + 10f, blue, 34f)
        )

        // --- Line 6: Delete line demo (X gesture) ---
        strokes.addAll(textToStrokes("Once upon a time", 60f, baseline(6), 64f))

        val xCenterX = 580f
        val xCenterY = baseline(6) - 22f
        val xSize = 28f
        annotations.add(makeLine(xCenterX + xSize, xCenterY - xSize, xCenterX - xSize, xCenterY + xSize, red, 5f))
        annotations.add(makeLine(xCenterX - xSize, xCenterY + xSize, xCenterX - xSize, xCenterY - xSize, red, 5f))
        annotations.add(makeLine(xCenterX - xSize, xCenterY - xSize, xCenterX + xSize, xCenterY + xSize, red, 5f))
        textAnnotations.add(
            TextAnnotation("Draw X in one stroke to delete line", xCenterX + xSize + 20f, xCenterY + 10f, red, 32f)
        )

        // --- Lines 7-9: Insert line demo ---
        strokes.addAll(textToStrokes("Line above", 60f, baseline(7), 64f))
        strokes.addAll(textToStrokes("Line below", 60f, baseline(9), 64f))

        // Downward vertical line (draw ↓ to insert below)
        val vertDownX = 450f
        val vertDownStart = lineTop(7) + LINE_SPACING / 2f
        val vertDownEnd = vertDownStart + LINE_SPACING * 1.5f - 10f
        annotations.add(makeLine(vertDownX, vertDownStart, vertDownX, vertDownEnd, green, 5f))
        annotations.add(makeLine(vertDownX - 12f, vertDownEnd - 20f, vertDownX, vertDownEnd, green, 4f))
        annotations.add(makeLine(vertDownX + 12f, vertDownEnd - 20f, vertDownX, vertDownEnd, green, 4f))
        textAnnotations.add(
            TextAnnotation("Draw ↓ to insert below", vertDownX + 30f, (vertDownStart + vertDownEnd) / 2f + 8f, green, 32f)
        )

        // Upward vertical line (draw ↑ to insert above)
        val vertUpX = 850f
        val vertUpMid = baseline(9) - 10f
        val vertUpEnd = vertUpMid - LINE_SPACING * 1.5f
        annotations.add(makeLine(vertUpX, vertUpMid, vertUpX, vertUpEnd, green, 5f))
        annotations.add(makeLine(vertUpX - 12f, vertUpEnd + 20f, vertUpX, vertUpEnd, green, 4f))
        annotations.add(makeLine(vertUpX + 12f, vertUpEnd + 20f, vertUpX, vertUpEnd, green, 4f))
        textAnnotations.add(
            TextAnnotation("Draw ↑ to insert above", vertUpX + 30f, (vertUpMid + vertUpEnd) / 2f + 8f, green, 32f)
        )

        // --- Auto-scroll hint between delete and insert demos ---
        textAnnotations.add(
            TextAnnotation(
                "Writing will auto-scroll up as you reach the bottom",
                writingWidth / 2f, lineTop(7) - LINE_SPACING * 0.35f + 5f * LINE_SPACING, blue, 34f,
                centered = true
            )
        )

        // Canvas content extends to bottom of line 9
        val canvasContentHeight = lineTop(9) + LINE_SPACING + 20f - scrollOffset

        // --- Text paragraphs for the text view ---
        val textParagraphs = listOf(
            listOf(
                WritingCoordinator.TextSegment("Shopping List", dimmed = false, lineIndex = 0, heading = true)
            ),
            listOf(
                WritingCoordinator.TextSegment("Eggs", dimmed = false, lineIndex = 1, listItem = true)
            ),
            listOf(
                WritingCoordinator.TextSegment("Bread", dimmed = false, lineIndex = 2, listItem = true)
            ),
            listOf(
                WritingCoordinator.TextSegment("The quick brown fox", dimmed = false, lineIndex = 3),
                WritingCoordinator.TextSegment("jumps over the lazy dog", dimmed = false, lineIndex = 4)
            ),
            listOf(
                WritingCoordinator.TextSegment("Hello world", dimmed = false, lineIndex = 5)
            ),
            listOf(
                WritingCoordinator.TextSegment("Line above", dimmed = false, lineIndex = 7),
                WritingCoordinator.TextSegment("Line below", dimmed = false, lineIndex = 9)
            )
        )

        return TutorialData(
            strokes = strokes,
            annotations = annotations,
            textAnnotations = textAnnotations,
            scrollOffsetY = scrollOffset,
            textParagraphs = textParagraphs,
            canvasContentHeight = canvasContentHeight
        )
    }

    private fun textToStrokes(text: String, x: Float, y: Float, textSize: Float): List<InkStroke> {
        val paint = Paint(textPaint)
        paint.textSize = textSize

        val path = Path()
        paint.getTextPath(text, 0, text.length, x, y, path)

        val strokes = mutableListOf<InkStroke>()
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)

        do {
            val length = measure.length
            if (length < 2f) continue

            val points = mutableListOf<StrokePoint>()
            val step = 2f
            var dist = 0f
            while (dist <= length) {
                measure.getPosTan(dist, pos, null)
                points.add(StrokePoint(pos[0], pos[1], 0.5f, 0L))
                dist += step
            }
            measure.getPosTan(length, pos, null)
            points.add(StrokePoint(pos[0], pos[1], 0.5f, 0L))

            if (points.size >= 2) {
                strokes.add(InkStroke(points = points, strokeWidth = 2f))
            }
        } while (measure.nextContour())

        return strokes
    }

    private fun makeArrow(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        color: Int
    ): List<AnnotationStroke> {
        val shaft = makeLine(fromX, fromY, toX, toY, color, 4f)

        val arrowSize = 18f
        val dx = toX - fromX
        val dy = toY - fromY
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val ux = dx / len
        val uy = dy / len

        val head1 = makeLine(
            toX, toY,
            toX - arrowSize * ux + arrowSize * 0.5f * uy,
            toY - arrowSize * uy - arrowSize * 0.5f * ux,
            color, 4f
        )
        val head2 = makeLine(
            toX, toY,
            toX - arrowSize * ux - arrowSize * 0.5f * uy,
            toY - arrowSize * uy + arrowSize * 0.5f * ux,
            color, 4f
        )

        return listOf(shaft, head1, head2)
    }

    private fun makeLine(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        color: Int, width: Float
    ): AnnotationStroke {
        return AnnotationStroke(
            points = listOf(
                StrokePoint(x1, y1, 0.5f, 0L),
                StrokePoint(x2, y2, 0.5f, 0L)
            ),
            color = color,
            strokeWidth = width
        )
    }
}
