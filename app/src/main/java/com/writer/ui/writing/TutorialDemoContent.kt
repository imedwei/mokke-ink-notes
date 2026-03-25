package com.writer.ui.writing

import com.writer.model.InkStroke
import com.writer.model.StrokePoint
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
        return InkStroke(points = pts)
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
