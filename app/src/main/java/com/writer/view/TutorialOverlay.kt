package com.writer.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.writer.ui.writing.TutorialStep

/**
 * Full-screen transparent overlay for the guided tutorial.
 *
 * Renders a semi-transparent dimmed background with a sharp-edged cutout
 * revealing the active zone. Shows a tooltip card and skip button.
 * Consumes all touch events outside the cutout.
 *
 * Designed for e-ink: no animations, no gradients, flat high-contrast rendering.
 */
class TutorialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Current tutorial step to display. Null = overlay hidden. */
    var currentStep: TutorialStep? = null
        set(value) {
            field = value
            visibility = if (value != null) VISIBLE else GONE
            invalidate()
        }

    /** Current step index (0-based) for the step indicator. */
    var stepIndex: Int = 0

    /** Total number of steps for the step indicator. */
    var totalSteps: Int = 0

    /** Called when the user taps "Skip". */
    var onSkip: (() -> Unit)? = null

    // Dim overlay paint
    private val dimPaint = Paint().apply {
        color = Color.argb(153, 0, 0, 0)  // 60% black
        style = Paint.Style.FILL
    }

    // Cutout eraser paint (clears the dim overlay in the cutout region)
    private val cutoutPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // Tooltip card
    private val cardPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val tooltipTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.dp(20f)
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Step indicator
    private val indicatorPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = ScreenMetrics.dp(12f)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // Skip button
    private val skipPaint = Paint().apply {
        color = Color.WHITE
        textSize = ScreenMetrics.dp(14f)
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }
    private val skipRect = Rect()

    override fun onDraw(canvas: Canvas) {
        val step = currentStep ?: return

        // Use an offscreen layer so PorterDuff.CLEAR works correctly
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // Draw dimmed background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Cut out the active zone
        step.cutoutRect?.let { rect ->
            canvas.drawRect(rect, cutoutPaint)
        }

        canvas.restoreToCount(saveCount)

        // Draw cutout border (thin white line around the revealed area)
        step.cutoutRect?.let { rect ->
            cardBorderPaint.color = Color.WHITE
            canvas.drawRect(RectF(rect), cardBorderPaint)
            cardBorderPaint.color = Color.BLACK
        }

        // Draw tooltip card
        val tooltipText = step.tooltipText
        val textWidth = tooltipTextPaint.measureText(tooltipText)
        val cardPadH = ScreenMetrics.dp(16f)
        val cardPadV = ScreenMetrics.dp(12f)
        val cardWidth = textWidth + 2 * cardPadH
        val cardHeight = tooltipTextPaint.textSize + 2 * cardPadV

        val cutout = step.cutoutRect ?: Rect(0, 0, width, height)
        val cardX: Float
        val cardY: Float

        when (step.tooltipPosition) {
            TutorialStep.TooltipPosition.ABOVE -> {
                cardX = (cutout.centerX() - cardWidth / 2f).coerceIn(0f, (width - cardWidth).coerceAtLeast(0f))
                cardY = cutout.top - cardHeight - ScreenMetrics.dp(12f)
            }
            TutorialStep.TooltipPosition.BELOW -> {
                cardX = (cutout.centerX() - cardWidth / 2f).coerceIn(0f, (width - cardWidth).coerceAtLeast(0f))
                cardY = cutout.bottom + ScreenMetrics.dp(12f)
            }
            TutorialStep.TooltipPosition.CENTER -> {
                // Position in the lower third of the cutout — above the writing area
                // but not dead center, leaving space for the user to write/draw above
                cardX = (cutout.centerX() - cardWidth / 2f).coerceIn(0f, (width - cardWidth).coerceAtLeast(0f))
                cardY = cutout.top + cutout.height() * 0.7f
            }
        }

        // Card background + border
        val cardRect = RectF(cardX, cardY, cardX + cardWidth, cardY + cardHeight)
        canvas.drawRoundRect(cardRect, ScreenMetrics.dp(4f), ScreenMetrics.dp(4f), cardPaint)
        canvas.drawRoundRect(cardRect, ScreenMetrics.dp(4f), ScreenMetrics.dp(4f), cardBorderPaint)

        // Card text
        val textX = cardX + cardPadH
        val textY = cardY + cardPadV + tooltipTextPaint.textSize * 0.85f
        canvas.drawText(tooltipText, textX, textY, tooltipTextPaint)

        // Step indicator (bottom center)
        val indicatorText = "${stepIndex + 1} of $totalSteps"
        canvas.drawText(indicatorText, width / 2f, height - ScreenMetrics.dp(16f), indicatorPaint)

        // Skip button — Material outlined button style, centered below the tooltip card
        val skipText = "Skip tutorial"
        val skipBtnPadH = ScreenMetrics.dp(24f)
        val skipBtnPadV = ScreenMetrics.dp(10f)
        val skipTextWidth = skipPaint.measureText(skipText)
        skipPaint.textAlign = Paint.Align.CENTER
        skipPaint.textSize = ScreenMetrics.dp(16f)
        val skipBtnWidth = skipTextWidth + 2 * skipBtnPadH
        val skipBtnHeight = skipPaint.textSize + 2 * skipBtnPadV
        val skipBtnX = cardX + (cardWidth - skipBtnWidth) / 2f
        val skipBtnY = cardY + cardHeight + ScreenMetrics.dp(12f)
        val skipBtnRect = RectF(skipBtnX, skipBtnY, skipBtnX + skipBtnWidth, skipBtnY + skipBtnHeight)

        // Button background (white) + border (black, 2dp, rounded corners per Material)
        val btnCorner = ScreenMetrics.dp(20f)  // Material full-rounded for small buttons
        canvas.drawRoundRect(skipBtnRect, btnCorner, btnCorner, cardPaint)
        cardBorderPaint.strokeWidth = ScreenMetrics.dp(1.5f)
        canvas.drawRoundRect(skipBtnRect, btnCorner, btnCorner, cardBorderPaint)
        cardBorderPaint.strokeWidth = 3f  // restore

        // Button text centered
        val skipTextX = skipBtnX + skipBtnWidth / 2f
        val skipTextY = skipBtnY + skipBtnPadV + skipPaint.textSize * 0.8f
        skipPaint.color = Color.BLACK
        canvas.drawText(skipText, skipTextX, skipTextY, skipPaint)
        skipPaint.color = Color.WHITE  // restore

        // Track button bounds for tap detection
        skipRect.set(
            skipBtnX.toInt(),
            skipBtnY.toInt(),
            (skipBtnX + skipBtnWidth).toInt(),
            (skipBtnY + skipBtnHeight).toInt()
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val step = currentStep ?: return false

        // Skip button takes priority over cutout pass-through
        if (skipRect.contains(event.x.toInt(), event.y.toInt())) {
            if (event.action == MotionEvent.ACTION_UP) {
                onSkip?.invoke()
            }
            return true  // consume all events on the skip button
        }

        // If touch is inside the cutout, let it pass through to views below
        val cutout = step.cutoutRect
        if (cutout != null && cutout.contains(event.x.toInt(), event.y.toInt())) {
            return false  // pass through
        }

        // Outside cutout — consume the event (block input)
        return true
    }
}
