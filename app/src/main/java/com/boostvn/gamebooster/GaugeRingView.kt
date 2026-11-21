package com.boostvn.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Đồng hồ đo vòng cung — phiên bản tối ưu hiệu năng + chống crash.
 *
 * - Không ValueAnimator, không software layer, không shadow.
 * - setValue / onDraw bọc try-catch — lỗi vẽ không làm sập app.
 * - Chỉ invalidate khi view còn gắn cửa sổ.
 */
class GaugeRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress: Float = 0f // 0..1
    private var valueText: String = "--"
    private var labelText: String = ""

    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300D9FF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D9FF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B00")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val arcRect = RectF()

    fun setValue(percent: Float, displayText: String, label: String) {
        try {
            val safePercent = percent.let { if (it.isNaN() || it.isInfinite()) 0f else it }
            val clamped = safePercent.coerceIn(0f, 100f) / 100f
            if (kotlin.math.abs(clamped - progress) < 0.005f &&
                displayText == valueText && label == labelText
            ) return

            progress = clamped
            valueText = displayText
            labelText = label
            fgArcPaint.color = when {
                safePercent >= 80f -> Color.parseColor("#FF2D2D")
                safePercent >= 50f -> Color.parseColor("#FF6B00")
                else -> Color.parseColor("#00D9FF")
            }
            if (isAttachedToWindow) invalidate()
        } catch (_: Throwable) {
            // Không để lỗi gauge làm sập MainActivity / HUD
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val strokeWidth = (w * 0.09f).coerceAtLeast(1f)
            bgArcPaint.strokeWidth = strokeWidth
            fgArcPaint.strokeWidth = strokeWidth

            val padding = strokeWidth
            arcRect.set(padding, padding, w - padding, h - padding)

            val startAngle = 135f
            val sweepFull = 270f

            canvas.drawArc(arcRect, startAngle, sweepFull, false, bgArcPaint)
            if (progress > 0f) {
                canvas.drawArc(arcRect, startAngle, sweepFull * progress, false, fgArcPaint)
            }

            valuePaint.textSize = (w * 0.20f).coerceAtLeast(8f)
            canvas.drawText(valueText, w / 2f, h / 2f + valuePaint.textSize * 0.1f, valuePaint)

            labelPaint.textSize = (w * 0.11f).coerceAtLeast(6f)
            canvas.drawText(
                labelText,
                w / 2f,
                h / 2f + valuePaint.textSize * 0.55f + labelPaint.textSize,
                labelPaint
            )
        } catch (_: Throwable) {
            // Nuốt lỗi vẽ
        }
    }
}
