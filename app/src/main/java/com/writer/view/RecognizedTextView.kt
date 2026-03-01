package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * Displays recognized text as flowing word-wrapped paragraphs.
 * Text is bottom-aligned to match the feel of writing scrolling up into text.
 */
class RecognizedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 80f
        isAntiAlias = false // e-ink
    }

    private var paragraphs: List<String> = emptyList()
    private var staticLayouts: List<StaticLayout> = emptyList()
    private var totalTextHeight = 0

    private val horizontalPadding = 40f
    private val paragraphSpacing = 24f
    private val bottomPadding = 10f

    fun setParagraphs(texts: List<String>, lineIndices: List<List<Int>> = emptyList()) {
        paragraphs = texts
        rebuildLayouts()
        invalidate()
    }

    private fun rebuildLayouts() {
        val availableWidth = (width - 2 * horizontalPadding).toInt()
        if (availableWidth <= 0) return

        var height = 0f
        staticLayouts = paragraphs.map { text ->
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8f, 1f)
                .build()
            height += layout.height + paragraphSpacing
            layout
        }
        totalTextHeight = height.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) rebuildLayouts()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (staticLayouts.isEmpty()) return

        // Bottom-align: start drawing from (height - totalTextHeight)
        val startY = (height - totalTextHeight - bottomPadding).coerceAtLeast(0f)

        canvas.save()
        canvas.translate(horizontalPadding, startY)

        for (layout in staticLayouts) {
            layout.draw(canvas)
            canvas.translate(0f, layout.height + paragraphSpacing)
        }

        canvas.restore()
    }
}
