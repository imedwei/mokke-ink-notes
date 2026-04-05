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
 * Bounding box and scale result for preview layout.
 */
data class PreviewMetrics(
    val minX: Float, val minY: Float,
    val maxX: Float, val maxY: Float,
    val scale: Float, val previewHeight: Int
) {
    val contentWidth get() = maxX - minX
    val contentHeight get() = maxY - minY
}

/**
 * Computes bounding box and scale factor for stroke + text block preview.
 * Extracted from [CuePreviewView] for testability (no View dependency).
 */
object PreviewMetricsCalculator {
    /**
     * Compute the bounding box of strokes + text blocks and the scale factor
     * needed to fit into [previewWidth].
     *
     * @param measureMaxLineWidth returns the widest wrapped line width for a
     *        text block's text given a wrap width. Defaults to StaticLayout measurement.
     */
    fun compute(
        strokes: List<InkStroke>,
        textBlocks: List<TextBlock>,
        previewWidth: Int,
        canvasWidth: Float,
        lineSpacing: Float,
        topMargin: Float,
        textBodySize: Float,
        padPx: Float,
        measureMaxLineWidth: (text: String, wrapWidth: Int) -> Float = { text, wrapWidth ->
            val paint = android.text.TextPaint().apply { textSize = textBodySize }
            val layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, paint, wrapWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .build()
            (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        }
    ): PreviewMetrics? {
        val hasStrokes = strokes.isNotEmpty()
        val hasTextBlocks = textBlocks.isNotEmpty()
        if (!hasStrokes && !hasTextBlocks) return null

        var minX = if (hasStrokes) strokes.minOf { it.minX } else Float.MAX_VALUE
        var minY = if (hasStrokes) strokes.minOf { it.minY } else Float.MAX_VALUE
        var maxX = if (hasStrokes) strokes.maxOf { it.maxX } else 0f
        var maxY = if (hasStrokes) strokes.maxOf { it.maxY } else 0f

        val textLeftMargin = lineSpacing * 0.3f
        val wrapWidth = if (canvasWidth > 0)
            (canvasWidth - 2 * textLeftMargin).toInt().coerceAtLeast(1) else 9999
        for (tb in textBlocks) {
            if (tb.text.isBlank()) continue
            val tbTop = topMargin + tb.startLineIndex * lineSpacing
            val tbBottom = topMargin + (tb.endLineIndex + 1) * lineSpacing
            minX = minOf(minX, textLeftMargin)
            minY = minOf(minY, tbTop)
            maxY = maxOf(maxY, tbBottom)
            val widestLine = measureMaxLineWidth(tb.text, wrapWidth)
            maxX = maxOf(maxX, textLeftMargin + widestLine)
        }

        val contentWidth = maxX - minX
        val contentHeight = maxY - minY
        if (contentWidth <= 0 || contentHeight <= 0) return null

        val availableWidth = previewWidth - 2 * padPx
        val scale = (availableWidth / contentWidth).coerceAtMost(1f)
        val previewHeight = (contentHeight * scale + 2 * padPx).toInt()

        return PreviewMetrics(minX, minY, maxX, maxY, scale, previewHeight)
    }
}

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

        val pad = ScreenMetrics.dp(8f)
        val metrics = PreviewMetricsCalculator.compute(
            strokes, textBlocks, previewWidth, canvasWidth,
            HandwritingCanvasView.LINE_SPACING, HandwritingCanvasView.TOP_MARGIN,
            ScreenMetrics.textBody, pad
        ) ?: return

        strokeMinX = metrics.minX
        strokeMinY = metrics.minY
        strokeMaxX = metrics.maxX
        strokeMaxY = metrics.maxY
        scale = metrics.scale

        minimumWidth = previewWidth
        minimumHeight = metrics.previewHeight
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
