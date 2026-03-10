package com.writer.ui.writing

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import com.writer.model.DiagramArea
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
    val canvasContentHeight: Float = 0f,
    val diagramAreas: List<DiagramArea> = emptyList(),
    val diagramDisplays: List<WritingCoordinator.DiagramDisplay> = emptyList()
)

object TutorialContent {

    private val LINE_SPACING = HandwritingCanvasView.LINE_SPACING
    private val TOP_MARGIN = HandwritingCanvasView.TOP_MARGIN
    private val GUTTER_WIDTH get() = HandwritingCanvasView.GUTTER_WIDTH

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

        // Lines 0-4: text content, Lines 5-7: diagram area
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

        // --- Lines 3-4: Fox text with strikethrough on "lazy" ---
        strokes.addAll(textToStrokes("The quick brown fox", 60f, baseline(3), 64f))
        strokes.addAll(textToStrokes("jumps over the lazy dog", 60f, baseline(4), 64f))

        // Scroll offset: start at the top so heading is visible
        val scrollOffset = 0f

        // Strikethrough on "lazy" in line 4
        val strikeY = baseline(4) - 22f
        annotations.add(makeLine(395f, strikeY, 500f, strikeY, red, 5f))
        textAnnotations.add(
            TextAnnotation("Strike through to delete words", 700f, strikeY + 10f, red, 32f)
        )

        // --- Gutter scroll hint (top line, matching resize arrow style) ---
        val scrollHintY = lineTop(0) + LINE_SPACING * 0.4f
        val scrollHintRight = writingWidth - 20f
        val scrollHintLeft = writingWidth - 370f
        annotations.add(makeLine(scrollHintLeft, scrollHintY, scrollHintRight, scrollHintY, blue, 4f))
        annotations.add(makeLine(scrollHintRight - 20f, scrollHintY - 12f, scrollHintRight, scrollHintY, blue, 4f))
        annotations.add(makeLine(scrollHintRight - 20f, scrollHintY + 12f, scrollHintRight, scrollHintY, blue, 4f))
        textAnnotations.add(
            TextAnnotation("Drag this gutter to scroll", scrollHintLeft.toFloat() - 10f, scrollHintY - 21f, blue, 34f)
        )

        // --- Insert/delete line demo (right of eggs/bread area, +100px right) ---
        val vertDownX = 600f
        val vertDownStart = lineTop(1) + LINE_SPACING / 2f
        val vertDownEnd = vertDownStart + LINE_SPACING * 1.5f - 10f
        annotations.add(makeLine(vertDownX, vertDownStart, vertDownX, vertDownEnd, green, 5f))
        annotations.add(makeLine(vertDownX - 12f, vertDownEnd - 20f, vertDownX, vertDownEnd, green, 4f))
        annotations.add(makeLine(vertDownX + 12f, vertDownEnd - 20f, vertDownX, vertDownEnd, green, 4f))
        textAnnotations.add(
            TextAnnotation("Drag ↓ to insert below", vertDownX + 30f, (vertDownStart + vertDownEnd) / 2f + 8f, green, 32f)
        )

        val vertUpX = vertDownX - 35f
        val vertUpMid = baseline(3) - 10f
        val vertUpEnd = vertUpMid - LINE_SPACING * 1.5f
        annotations.add(makeLine(vertUpX, vertUpMid, vertUpX, vertUpEnd, green, 5f))
        annotations.add(makeLine(vertUpX - 12f, vertUpEnd + 20f, vertUpX, vertUpEnd, green, 4f))
        annotations.add(makeLine(vertUpX + 12f, vertUpEnd + 20f, vertUpX, vertUpEnd, green, 4f))
        textAnnotations.add(
            TextAnnotation("Drag ↑ to delete above", vertUpX + 30f, (vertUpMid + vertUpEnd) / 2f + 8f + LINE_SPACING / 2f, green, 32f)
        )

        // --- Lines 6-8: Diagram area with smiley face ---
        val diagramArea = DiagramArea(startLineIndex = 6, heightInLines = 3)

        val smileyCx = writingWidth / 2f
        val smileyCy = lineTop(6) + 1.5f * LINE_SPACING
        val smileyR = LINE_SPACING * 1.1f

        // Track stroke count before smiley so we can extract them for text view
        val preSmileyStrokeCount = strokes.size

        // Face circle
        val facePoints = (0..40).map { i ->
            val angle = 2.0 * Math.PI * i / 40
            StrokePoint(
                smileyCx + smileyR * Math.cos(angle).toFloat(),
                smileyCy + smileyR * Math.sin(angle).toFloat(),
                0.5f, 0L
            )
        }
        strokes.add(InkStroke(points = facePoints, strokeWidth = 3f))

        // Eyes
        val eyeR = LINE_SPACING * 0.12f
        val eyeOffsetX = smileyR * 0.35f
        val eyeOffsetY = smileyR * 0.25f
        for (side in listOf(-1f, 1f)) {
            val eyePoints = (0..20).map { i ->
                val angle = 2.0 * Math.PI * i / 20
                StrokePoint(
                    smileyCx + side * eyeOffsetX + eyeR * Math.cos(angle).toFloat(),
                    smileyCy - eyeOffsetY + eyeR * Math.sin(angle).toFloat(),
                    0.5f, 0L
                )
            }
            strokes.add(InkStroke(points = eyePoints, strokeWidth = 2f))
        }

        // Mouth (smile arc — bottom of circle)
        val mouthCy = smileyCy + smileyR * 0.1f
        val mouthR = smileyR * 0.5f
        val mouthPoints = (0..20).map { i ->
            val angle = Math.toRadians(20.0 + 140.0 * i / 20)
            StrokePoint(
                smileyCx + mouthR * Math.cos(angle).toFloat(),
                mouthCy + mouthR * Math.sin(angle).toFloat(),
                0.5f, 0L
            )
        }
        strokes.add(InkStroke(points = mouthPoints, strokeWidth = 2f))

        val smileyStrokes = strokes.subList(preSmileyStrokeCount, strokes.size).toList()

        // --- Scribble-then-down annotation (starts above diagram, arrow goes into it) ---
        val scribbleX = 140f
        val scribbleWidth = 80f
        val scribbleBaseY = lineTop(5) + LINE_SPACING * 0.3f
        val zigZagPoints = mutableListOf<StrokePoint>()
        val zigSegments = 6
        for (i in 0..zigSegments) {
            val t = i.toFloat() / zigSegments
            val px = if (i == zigSegments) scribbleX + scribbleWidth / 2f
                     else scribbleX + if (i % 2 == 0) 0f else scribbleWidth
            val py = scribbleBaseY + t * LINE_SPACING * 0.8f
            zigZagPoints.add(StrokePoint(px, py, 0.5f, 0L))
        }
        annotations.add(AnnotationStroke(zigZagPoints, green, 4f))

        val scribbleArrowX = scribbleX + scribbleWidth / 2f
        val scribbleArrowStartY = scribbleBaseY + LINE_SPACING * 0.8f - 1f
        val scribbleArrowEndY = scribbleArrowStartY + LINE_SPACING * 0.8f
        annotations.add(makeLine(scribbleArrowX, scribbleArrowStartY, scribbleArrowX, scribbleArrowEndY, green, 5f))
        annotations.add(makeLine(scribbleArrowX - 12f, scribbleArrowEndY - 20f, scribbleArrowX, scribbleArrowEndY, green, 4f))
        annotations.add(makeLine(scribbleArrowX + 12f, scribbleArrowEndY - 20f, scribbleArrowX, scribbleArrowEndY, green, 4f))

        textAnnotations.add(TextAnnotation(
            "Scribble then down",
            scribbleX, scribbleArrowEndY + 30f, green, 32f
        ))
        textAnnotations.add(TextAnnotation(
            "inserts a drawing area",
            scribbleX, scribbleArrowEndY + 66f, green, 32f
        ))

        // --- Undo/redo gesture demo: right-then-up arrow (shifted up 2 lines) ---
        val undoMidY = baseline(4) - 22f + LINE_SPACING - LINE_SPACING * 0.25f
        val undoRightLen = 2f * LINE_SPACING
        val undoUpLen = 2f * LINE_SPACING
        val undoStartX = writingWidth - 140f - undoRightLen
        val undoCornerX = undoStartX + undoRightLen
        val undoEndY = undoMidY - undoUpLen
        // Horizontal segment (right)
        annotations.add(makeLine(undoStartX, undoMidY, undoCornerX, undoMidY, blue, 5f))
        // Vertical segment (up = undo)
        annotations.add(makeLine(undoCornerX, undoMidY, undoCornerX, undoEndY, blue, 5f))
        // Arrowhead at end of vertical segment
        annotations.add(makeLine(undoCornerX - 12f, undoEndY + 20f, undoCornerX, undoEndY, blue, 4f))
        annotations.add(makeLine(undoCornerX + 12f, undoEndY + 20f, undoCornerX, undoEndY, blue, 4f))
        textAnnotations.add(
            TextAnnotation("Right/left then up/down for undo/redo", (undoStartX + undoCornerX) / 2f - 30f, undoMidY + 41f, blue, 32f, centered = true)
        )

        // --- Auto-scroll hint below diagram area ---
        textAnnotations.add(
            TextAnnotation(
                "Writing will auto-scroll up as you reach the bottom",
                writingWidth / 2f, lineTop(10) + LINE_SPACING * 0.8f, blue, 34f,
                centered = true
            )
        )

        // Canvas content extends below diagram area + auto-scroll hint
        val canvasContentHeight = lineTop(12) + 20f - scrollOffset

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
                WritingCoordinator.TextSegment("jumps over the dog", dimmed = false, lineIndex = 4)
            )
        )

        // Diagram display for the text view (smiley rendered inline)
        val diagramDisplay = WritingCoordinator.DiagramDisplay(
            startLineIndex = 6,
            strokes = smileyStrokes,
            canvasWidth = writingWidth.toFloat(),
            heightPx = 3f * LINE_SPACING,
            offsetY = lineTop(6)
        )

        return TutorialData(
            strokes = strokes,
            annotations = annotations,
            textAnnotations = textAnnotations,
            scrollOffsetY = scrollOffset,
            textParagraphs = textParagraphs,
            canvasContentHeight = canvasContentHeight,
            diagramAreas = listOf(diagramArea),
            diagramDisplays = listOf(diagramDisplay)
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
