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

    /** Called when the user taps "Skip" or "Finish". */
    var onSkip: (() -> Unit)? = null

    /** Called when the user taps "Next". */
    var onNext: (() -> Unit)? = null

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

    // Button text paint
    private val btnTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.dp(16f)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    // Filled button paint (for "Finish tutorial")
    private val filledBtnPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    // Anchor tooltip paint (smaller text for strip-anchored hints)
    private val anchorTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = ScreenMetrics.dp(14f)
        isAntiAlias = true
    }
    private val arrowPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val skipRect = Rect()
    private val nextRect = Rect()

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

        // Anchor tooltip (small label with arrow pointing at a strip/rail)
        val anchorText = step.anchorTooltipText
        val anchorRect = step.anchorTooltipRect
        if (anchorText != null && anchorRect != null) {
            // Convert screen coordinates to overlay-local coordinates
            val overlayLoc = IntArray(2)
            getLocationOnScreen(overlayLoc)
            val localRect = Rect(
                anchorRect.left - overlayLoc[0],
                anchorRect.top - overlayLoc[1],
                anchorRect.right - overlayLoc[0],
                anchorRect.bottom - overlayLoc[1]
            )

            val aPadH = ScreenMetrics.dp(12f)
            val aPadV = ScreenMetrics.dp(8f)
            val aTextWidth = anchorTextPaint.measureText(anchorText)
            val aCardWidth = aTextWidth + 2 * aPadH
            val aCardHeight = anchorTextPaint.textSize + 2 * aPadV
            val arrowSize = ScreenMetrics.dp(8f)

            // Position to the right of the anchor strip, vertically centered
            val aCardX = localRect.right + arrowSize
            val aCardY = localRect.centerY() - aCardHeight / 2f
            val aCardRect = RectF(aCardX, aCardY, aCardX + aCardWidth, aCardY + aCardHeight)

            // Card background + border
            val corner = ScreenMetrics.dp(4f)
            canvas.drawRoundRect(aCardRect, corner, corner, cardPaint)
            canvas.drawRoundRect(aCardRect, corner, corner, cardBorderPaint)

            // Arrow pointing left toward the strip
            val path = android.graphics.Path()
            val arrowTipX = localRect.right.toFloat()
            val arrowTipY = localRect.centerY().toFloat()
            path.moveTo(arrowTipX, arrowTipY)
            path.lineTo(aCardX, arrowTipY - arrowSize)
            path.lineTo(aCardX, arrowTipY + arrowSize)
            path.close()
            canvas.drawPath(path, cardPaint)
            canvas.drawPath(path, arrowPaint)
            // Redraw the card edge where the arrow meets it
            canvas.drawLine(aCardX, arrowTipY - arrowSize, aCardX, arrowTipY + arrowSize, cardPaint)

            // Text
            canvas.drawText(anchorText, aCardX + aPadH,
                aCardY + aPadV + anchorTextPaint.textSize * 0.85f, anchorTextPaint)
        }

        // Step indicator (bottom center)
        val indicatorText = "${stepIndex + 1} of $totalSteps"
        canvas.drawText(indicatorText, width / 2f, height - ScreenMetrics.dp(16f), indicatorPaint)

        // Buttons below the tooltip card
        val isLast = step.isLastStep
        val btnPadH = ScreenMetrics.dp(24f)
        val btnPadV = ScreenMetrics.dp(10f)
        val btnCorner = ScreenMetrics.dp(20f)
        val btnGap = ScreenMetrics.dp(12f)
        val btnTopY = cardY + cardHeight + ScreenMetrics.dp(12f)

        if (isLast) {
            // Single filled "Finish tutorial" button
            val finishText = "Finish tutorial"
            val finishTextWidth = btnTextPaint.measureText(finishText)
            val finishBtnWidth = finishTextWidth + 2 * btnPadH
            val finishBtnHeight = btnTextPaint.textSize + 2 * btnPadV
            val finishBtnX = cardX + (cardWidth - finishBtnWidth) / 2f
            val finishBtnRect = RectF(finishBtnX, btnTopY, finishBtnX + finishBtnWidth, btnTopY + finishBtnHeight)

            // Filled black background
            canvas.drawRoundRect(finishBtnRect, btnCorner, btnCorner, filledBtnPaint)

            // White text on black
            btnTextPaint.color = Color.WHITE
            canvas.drawText(finishText, finishBtnX + finishBtnWidth / 2f,
                btnTopY + btnPadV + btnTextPaint.textSize * 0.8f, btnTextPaint)
            btnTextPaint.color = Color.BLACK

            skipRect.set(finishBtnRect.left.toInt(), finishBtnRect.top.toInt(),
                finishBtnRect.right.toInt(), finishBtnRect.bottom.toInt())
            nextRect.set(0, 0, 0, 0)
        } else {
            // Two buttons side by side: "Skip tutorial" (outlined) + "Next" (outlined)
            val skipText = "Skip tutorial"
            val nextText = "Next"
            val skipTextWidth = btnTextPaint.measureText(skipText)
            val nextTextWidth = btnTextPaint.measureText(nextText)
            val skipBtnWidth = skipTextWidth + 2 * btnPadH
            val nextBtnWidth = nextTextWidth + 2 * btnPadH
            val btnHeight = btnTextPaint.textSize + 2 * btnPadV
            val totalWidth = skipBtnWidth + btnGap + nextBtnWidth
            val startX = cardX + (cardWidth - totalWidth) / 2f

            // Skip button (outlined)
            val skipBtnRect = RectF(startX, btnTopY, startX + skipBtnWidth, btnTopY + btnHeight)
            canvas.drawRoundRect(skipBtnRect, btnCorner, btnCorner, cardPaint)
            cardBorderPaint.strokeWidth = ScreenMetrics.dp(1.5f)
            canvas.drawRoundRect(skipBtnRect, btnCorner, btnCorner, cardBorderPaint)
            cardBorderPaint.strokeWidth = 3f
            btnTextPaint.color = Color.BLACK
            canvas.drawText(skipText, startX + skipBtnWidth / 2f,
                btnTopY + btnPadV + btnTextPaint.textSize * 0.8f, btnTextPaint)

            // Next button (outlined)
            val nextBtnX = startX + skipBtnWidth + btnGap
            val nextBtnRect = RectF(nextBtnX, btnTopY, nextBtnX + nextBtnWidth, btnTopY + btnHeight)
            canvas.drawRoundRect(nextBtnRect, btnCorner, btnCorner, cardPaint)
            cardBorderPaint.strokeWidth = ScreenMetrics.dp(1.5f)
            canvas.drawRoundRect(nextBtnRect, btnCorner, btnCorner, cardBorderPaint)
            cardBorderPaint.strokeWidth = 3f
            canvas.drawText(nextText, nextBtnX + nextBtnWidth / 2f,
                btnTopY + btnPadV + btnTextPaint.textSize * 0.8f, btnTextPaint)

            skipRect.set(skipBtnRect.left.toInt(), skipBtnRect.top.toInt(),
                skipBtnRect.right.toInt(), skipBtnRect.bottom.toInt())
            nextRect.set(nextBtnRect.left.toInt(), nextBtnRect.top.toInt(),
                nextBtnRect.right.toInt(), nextBtnRect.bottom.toInt())
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val step = currentStep ?: return false

        // Buttons take priority over cutout pass-through
        if (skipRect.contains(event.x.toInt(), event.y.toInt())) {
            if (event.action == MotionEvent.ACTION_UP) {
                onSkip?.invoke()
            }
            return true
        }
        if (nextRect.contains(event.x.toInt(), event.y.toInt())) {
            if (event.action == MotionEvent.ACTION_UP) {
                onNext?.invoke()
            }
            return true
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
