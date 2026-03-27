package com.writer.ui.writing

import com.writer.model.DiagramArea
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.model.StrokeType
import com.writer.view.HandwritingCanvasView

/**
 * Generates programmatic demo strokes for the tutorial.
 * All coordinates are scaled relative to LINE_SPACING for device independence.
 */
object TutorialDemoContent {

    /**
     * Generate "Welcome to InkUp" using the Hershey cursive font.
     * Scales to fill approximately one line height.
     *
     * @param font pre-loaded HersheyFont instance
     */
    fun generateWelcomeText(
        font: HersheyFont,
        startX: Float,
        startY: Float,
        lineSpacing: Float
    ): List<InkStroke> {
        // Hershey font coordinates range roughly -12 to +12 vertically.
        // Scale so the text height fills ~80% of one line spacing.
        val scale = lineSpacing * 0.8f / 24f
        return font.textToStrokes(
            text = "Welcome to InkUp",
            startX = startX,
            startY = startY + lineSpacing * 0.4f,  // center vertically in line
            scale = scale,
            jitter = scale * 0.3f  // subtle natural variation
        )
    }

    /**
     * Generate "Hello" using the Hershey cursive font for the erase demo.
     */
    fun generateHelloText(
        font: HersheyFont,
        startX: Float,
        startY: Float,
        lineSpacing: Float
    ): List<InkStroke> {
        val scale = lineSpacing * 0.8f / 24f
        return font.textToStrokes(
            text = "Hello",
            startX = startX,
            startY = startY + lineSpacing * 0.4f,
            scale = scale,
            jitter = scale * 0.3f
        )
    }

    /**
     * Generate a rectangle outline stroke.
     */
    fun generateRectangle(
        cx: Float, cy: Float,
        width: Float, height: Float
    ): InkStroke {
        val halfW = width / 2f
        val halfH = height / 2f
        val corners = listOf(
            cx - halfW to cy - halfH,
            cx + halfW to cy - halfH,
            cx + halfW to cy + halfH,
            cx - halfW to cy + halfH,
            cx - halfW to cy - halfH  // close
        )
        // Interpolate between corners for smoothness
        val pts = mutableListOf<StrokePoint>()
        var t = 0L
        for (i in 0 until corners.size - 1) {
            val (ax, ay) = corners[i]
            val (bx, by) = corners[i + 1]
            for (j in 0..4) {
                val frac = j / 4f
                pts.add(StrokePoint(
                    ax + (bx - ax) * frac,
                    ay + (by - ay) * frac,
                    0.5f, t
                ))
                t += 20L
            }
        }
        return InkStroke(points = pts, isGeometric = true, strokeType = StrokeType.RECTANGLE)
    }


    /**
     * Generate a small rectangle for the erase step.
     */
    fun generateSmallRect(cx: Float, cy: Float, lineSpacing: Float): InkStroke {
        return generateRectangle(cx, cy, lineSpacing * 1.2f, lineSpacing * 0.8f)
    }

    /**
     * Generate a horizontal strikethrough line with slight wobble.
     */
    fun generateStrikethrough(startX: Float, endX: Float, y: Float): InkStroke {
        val pts = (0..15).map { i ->
            val t = i / 15f
            StrokePoint(
                x = startX + (endX - startX) * t,
                y = y + (if (i % 2 == 0) 1.5f else -1.5f),
                pressure = 0.5f,
                timestamp = i * 15L
            )
        }
        return InkStroke(points = pts)
    }

    /**
     * Generate an arrow/connector stroke from (x1,y1) to (x2,y2).
     */
    fun generateArrow(x1: Float, y1: Float, x2: Float, y2: Float): InkStroke {
        // Just a straight line — CanvasTheme.drawStroke renders the arrowhead
        // automatically for ARROW_HEAD stroke type.
        val pts = listOf(
            StrokePoint(x1, y1, 0.5f, 0L),
            StrokePoint(x2, y2, 0.5f, 100L)
        )
        return InkStroke(points = pts, isGeometric = true, strokeType = StrokeType.ARROW_HEAD)
    }

    /**
     * Generate an ellipse stroke at the given center.
     */
    fun generateEllipse(cx: Float, cy: Float, rx: Float, ry: Float): InkStroke {
        val pts = mutableListOf<StrokePoint>()
        val steps = 24
        for (i in 0..steps) {
            val angle = 2.0 * kotlin.math.PI * i / steps
            pts.add(StrokePoint(
                cx + rx * kotlin.math.cos(angle).toFloat(),
                cy + ry * kotlin.math.sin(angle).toFloat(),
                0.5f,
                i.toLong()
            ))
        }
        return InkStroke(points = pts, isGeometric = true, strokeType = StrokeType.ELLIPSE)
    }

    /**
     * Generate demo cue strokes for the peek tutorial step.
     * Places short annotations at lines 0 and 1 so cue dots appear on the indicator strip.
     */
    fun generateCueStrokes(
        font: HersheyFont,
        canvasWidth: Float,
        lineSpacing: Float,
        topMargin: Float
    ): List<InkStroke> {
        val scale = lineSpacing * 0.8f / 24f
        val jitter = scale * 0.25f
        val margin = canvasWidth * 0.05f
        val strokes = mutableListOf<InkStroke>()
        strokes.addAll(font.textToStrokes("Key topic", margin, topMargin + lineSpacing * 0.4f, scale, jitter))
        strokes.addAll(font.textToStrokes("Why?", margin, topMargin + lineSpacing * 1.4f, scale, jitter))
        return strokes
    }

    /**
     * Data class for the showcase document: strokes + diagram area.
     */
    data class ShowcaseDocument(
        val strokes: List<InkStroke>,
        val diagramArea: DiagramArea
    )

    /**
     * Generate a complete showcase document for the README screenshot.
     * Includes text lines, a diagram area with shapes and arrows.
     *
     * @param font Hershey font for text generation
     * @param canvasWidth canvas width in pixels
     * @param lineSpacing line spacing in pixels
     * @param topMargin top margin in pixels
     */
    fun generateShowcaseDocument(
        font: HersheyFont,
        canvasWidth: Float,
        lineSpacing: Float,
        topMargin: Float
    ): ShowcaseDocument {
        val strokes = mutableListOf<InkStroke>()
        val scale = lineSpacing * 0.8f / 24f
        val jitter = scale * 0.25f
        val margin = canvasWidth * 0.05f

        // Line 0: "Meeting Notes" (heading)
        strokes.addAll(font.textToStrokes("Meeting Notes", margin, topMargin + lineSpacing * 0.4f, scale, jitter))
        // Underline for heading
        strokes.add(InkStroke(points = listOf(
            StrokePoint(margin, topMargin + lineSpacing * 0.85f, 0.5f, 0L),
            StrokePoint(margin + canvasWidth * 0.55f, topMargin + lineSpacing * 0.85f, 0.5f, 100L)
        )))

        // Line 1: "- Review project goals"
        strokes.addAll(font.textToStrokes("- Review goals", margin, topMargin + lineSpacing * 1.4f, scale, jitter))

        // Line 2: "- Launch timeline"
        strokes.addAll(font.textToStrokes("- Launch timeline", margin, topMargin + lineSpacing * 2.4f, scale, jitter))

        // Lines 3-7: Diagram area — flowchart with 3 boxes + arrows
        val diagY = topMargin + lineSpacing * 3.5f
        val boxW = canvasWidth * 0.22f
        val boxH = lineSpacing * 0.9f

        // Box 1: "Plan" (left)
        val b1x = canvasWidth * 0.18f
        val b1y = diagY + lineSpacing * 0.5f
        strokes.add(generateRectangle(b1x, b1y, boxW, boxH))
        strokes.addAll(font.textToStrokes("Plan", b1x - boxW * 0.3f, b1y - boxH * 0.1f, scale * 0.8f, jitter * 0.5f))

        // Box 2: "Build" (center)
        val b2x = canvasWidth * 0.5f
        val b2y = diagY + lineSpacing * 0.5f
        strokes.add(generateRectangle(b2x, b2y, boxW, boxH))
        strokes.addAll(font.textToStrokes("Build", b2x - boxW * 0.35f, b2y - boxH * 0.1f, scale * 0.8f, jitter * 0.5f))

        // Box 3: "Ship" (right)
        val b3x = canvasWidth * 0.82f
        val b3y = diagY + lineSpacing * 0.5f
        strokes.add(generateRectangle(b3x, b3y, boxW, boxH))
        strokes.addAll(font.textToStrokes("Ship", b3x - boxW * 0.3f, b3y - boxH * 0.1f, scale * 0.8f, jitter * 0.5f))

        // Arrow: Plan → Build
        strokes.add(generateArrow(b1x + boxW / 2f, b1y, b2x - boxW / 2f, b2y))

        // Arrow: Build → Ship
        strokes.add(generateArrow(b2x + boxW / 2f, b2y, b3x - boxW / 2f, b3y))

        // Ellipse below: "Launch!"
        val ey = diagY + lineSpacing * 2.2f
        strokes.add(generateEllipse(canvasWidth * 0.5f, ey, boxW * 0.6f, boxH * 0.5f))
        strokes.addAll(font.textToStrokes("Launch!", canvasWidth * 0.38f, ey - boxH * 0.15f, scale * 0.7f, jitter * 0.5f))

        // Arrow: Ship → Launch (downward)
        strokes.add(generateArrow(b3x, b3y + boxH / 2f, canvasWidth * 0.58f, ey - boxH * 0.4f))

        // Line 8: "Next steps"
        strokes.addAll(font.textToStrokes("Next steps", margin, topMargin + lineSpacing * 7.4f, scale, jitter))

        // Diagram area covering lines 3-7 (the flowchart region)
        val diagramArea = DiagramArea(startLineIndex = 3, heightInLines = 4)

        return ShowcaseDocument(strokes, diagramArea)
    }

    /**
     * Generate a realistic scratch-out: tight back-and-forth horizontal scribble
     * concentrated over the target, like a human rapidly scribbling to erase.
     */
    fun generateScratchOut(cx: Float, cy: Float, width: Float, height: Float): InkStroke {
        val halfW = width / 2f
        val pts = mutableListOf<StrokePoint>()
        var t = 0L
        val random = java.util.Random(cx.toLong() + cy.toLong())

        // 6 rapid back-and-forth sweeps, each with slight vertical drift
        val sweeps = 6
        for (sweep in 0 until sweeps) {
            val goingRight = sweep % 2 == 0
            val yBase = cy + (sweep - sweeps / 2f + 0.5f) * (height * 0.15f)
            // ~8 points per sweep with random variation
            val numPoints = 8 + random.nextInt(4)
            for (i in 0..numPoints) {
                val frac = i.toFloat() / numPoints
                val xFrac = if (goingRight) frac else 1f - frac
                // Add horizontal jitter so strokes aren't perfectly uniform
                val xJitter = (random.nextFloat() - 0.5f) * width * 0.06f
                // Slight vertical wobble
                val yJitter = (random.nextFloat() - 0.5f) * height * 0.15f
                pts.add(StrokePoint(
                    x = cx - halfW + width * xFrac + xJitter,
                    y = yBase + yJitter,
                    pressure = 0.5f,
                    timestamp = t
                ))
                t += 8L
            }
        }
        return InkStroke(points = pts)
    }
}
