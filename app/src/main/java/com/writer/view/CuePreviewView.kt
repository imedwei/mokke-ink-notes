package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import com.writer.model.InkStroke
import com.writer.model.TextBlock
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

    private val textBlockPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.textBody
        isAntiAlias = false
    }

    private val cornerRadius = ScreenMetrics.dp(8f)

    private val path = Path()
    private var strokes: List<InkStroke> = emptyList()
    private var textBlocks: List<TextBlock> = emptyList()
    private var strokeMinX = 0f
    private var strokeMinY = 0f
    private var strokeMaxX = 0f
    private var strokeMaxY = 0f
    private var scale = 1f
    private var originalCanvasWidth = 0f

    fun setStrokes(
        strokes: List<InkStroke>, previewWidth: Int,
        textBlocks: List<TextBlock> = emptyList(),
        canvasWidth: Float = 0f
    ) {
        this.strokes = strokes
        this.textBlocks = textBlocks
        this.originalCanvasWidth = canvasWidth

        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN

        // Compute bounding box of all strokes and text blocks
        val hasStrokes = strokes.isNotEmpty()
        val hasTextBlocks = textBlocks.isNotEmpty()
        if (!hasStrokes && !hasTextBlocks) return

        strokeMinX = if (hasStrokes) strokes.minOf { it.minX } else 0f
        strokeMinY = if (hasStrokes) strokes.minOf { it.minY } else Float.MAX_VALUE
        strokeMaxX = if (hasStrokes) strokes.maxOf { it.maxX } else 0f
        strokeMaxY = if (hasStrokes) strokes.maxOf { it.maxY } else 0f

        // Expand bounds to include text blocks (use full canvas width for X)
        for (tb in textBlocks) {
            val tbTop = tm + tb.startLineIndex * ls
            val tbBottom = tm + (tb.endLineIndex + 1) * ls
            val textLeftMargin = ls * 0.3f
            strokeMinX = minOf(strokeMinX, textLeftMargin)
            strokeMinY = minOf(strokeMinY, tbTop)
            strokeMaxY = maxOf(strokeMaxY, tbBottom)
            if (canvasWidth > 0) strokeMaxX = maxOf(strokeMaxX, canvasWidth)
        }

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

        if (strokes.isEmpty() && textBlocks.isEmpty()) return

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

        // Render text blocks using word-wrapped lines aligned to ruled lines
        val ls = HandwritingCanvasView.LINE_SPACING
        val tm = HandwritingCanvasView.TOP_MARGIN
        val textLeftMargin = ls * 0.3f
        // Use original canvas text size and width so text wraps at the same
        // points as on the canvas — the canvas scale transform shrinks it to fit.
        textBlockPaint.textSize = ScreenMetrics.textBody
        val textWidth = ((originalCanvasWidth - 2 * textLeftMargin).coerceAtLeast(1f)).toInt()
        for (block in textBlocks) {
            if (block.text.isBlank()) continue
            val layout = android.text.StaticLayout.Builder
                .obtain(block.text, 0, block.text.length, textBlockPaint, textWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .build()
            for (i in 0 until layout.lineCount) {
                val lineText = block.text.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
                val baselineY = tm + (block.startLineIndex + i + 1) * ls
                canvas.drawText(lineText, textLeftMargin, baselineY, textBlockPaint)
            }
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
