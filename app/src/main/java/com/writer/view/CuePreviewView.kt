package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.writer.model.InkStroke
import com.writer.model.minX
import com.writer.model.minY
import com.writer.model.maxX
import com.writer.model.maxY

/**
 * Renders a set of cue strokes scaled to fit a fixed-width preview panel.
 * Used in the cue peek popup (long-press on indicator strip dot).
 */
class CuePreviewView(context: Context) : View(context) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = CanvasTheme.DEFAULT_STROKE_WIDTH
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEEEE.toInt() // light gray — distinct from white canvas on e-ink
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt() // darker than ruled lines for consistent visibility
        style = Paint.Style.STROKE
        strokeWidth = ScreenMetrics.dp(2f)
    }

    private val cornerRadius = ScreenMetrics.dp(8f)

    private val path = Path()
    private var strokes: List<InkStroke> = emptyList()
    private var strokeMinX = 0f
    private var strokeMinY = 0f
    private var strokeMaxX = 0f
    private var strokeMaxY = 0f
    private var scale = 1f

    fun setStrokes(strokes: List<InkStroke>, previewWidth: Int) {
        this.strokes = strokes
        if (strokes.isEmpty()) return

        // Compute bounding box of all strokes
        strokeMinX = strokes.minOf { it.minX }
        strokeMinY = strokes.minOf { it.minY }
        strokeMaxX = strokes.maxOf { it.maxX }
        strokeMaxY = strokes.maxOf { it.maxY }

        val contentWidth = strokeMaxX - strokeMinX
        val contentHeight = strokeMaxY - strokeMinY
        if (contentWidth <= 0 || contentHeight <= 0) return

        // Padding
        val pad = ScreenMetrics.dp(8f)
        val availableWidth = previewWidth - 2 * pad

        scale = (availableWidth / contentWidth).coerceAtMost(1f)
        val scaledHeight = contentHeight * scale + 2 * pad

        // Set view dimensions
        minimumWidth = previewWidth
        minimumHeight = scaledHeight.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Light gray background
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(rect, bgPaint)

        // Solid left and right borders
        canvas.drawLine(0f, 0f, 0f, height.toFloat(), borderPaint)
        canvas.drawLine(width.toFloat(), 0f, width.toFloat(), height.toFloat(), borderPaint)

        // Wavy top and bottom borders — suggests content continues beyond
        drawWavyLine(canvas, 0f, 0f, width.toFloat(), true, borderPaint)
        drawWavyLine(canvas, 0f, height.toFloat(), width.toFloat(), false, borderPaint)

        if (strokes.isEmpty()) return

        val pad = ScreenMetrics.dp(8f)

        canvas.save()
        canvas.translate(pad, pad)
        canvas.scale(scale, scale)
        canvas.translate(-strokeMinX, -strokeMinY)

        // Scale stroke width inversely so strokes don't become too thick/thin
        strokePaint.strokeWidth = CanvasTheme.DEFAULT_STROKE_WIDTH / scale

        for (stroke in strokes) {
            CanvasTheme.drawStroke(canvas, stroke, path, strokePaint)
        }

        canvas.restore()
    }

    /** Draw a horizontal wavy line from (startX, y) to (endX, y).
     *  @param top if true, waves go downward; if false, waves go upward. */
    private fun drawWavyLine(canvas: Canvas, startX: Float, y: Float, endX: Float, top: Boolean, paint: Paint) {
        val waveLength = ScreenMetrics.dp(24f)
        val amplitude = ScreenMetrics.dp(6f)
        val dir = if (top) 1f else -1f

        val wavePath = Path()
        wavePath.moveTo(startX, y)
        var x = startX
        while (x < endX) {
            val nextX = (x + waveLength).coerceAtMost(endX)
            val midX = (x + nextX) / 2f
            wavePath.quadTo(midX, y + amplitude * dir, nextX, y)
            x = nextX
        }
        canvas.drawPath(wavePath, paint)
    }
}
