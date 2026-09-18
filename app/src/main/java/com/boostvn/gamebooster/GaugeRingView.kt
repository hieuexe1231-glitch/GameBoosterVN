package com.boostvn.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Đồng hồ đo hình vòng cung - lấy cảm hứng từ HUD Game Space của các máy gaming (RedMagic,
 * ROG Phone...): 1 vòng cung màu đỏ/cam thể hiện mức độ (0-100%), số liệu lớn ở giữa,
 * nhãn nhỏ bên dưới. Tự vẽ bằng Canvas, không cần thư viện ngoài.
 */
class GaugeRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress: Float = 0f // 0..1
    private var valueText: String = "--"
    private var labelText: String = ""

    private val bgArcPaint = Paint().apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val fgArcPaint = Paint().apply {
        color = Color.parseColor("#FF2D2D")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val valuePaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#FF6B00")
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val arcRect = RectF()

    fun setValue(percent: Float, displayText: String, label: String) {
        progress = percent.coerceIn(0f, 100f) / 100f
        valueText = displayText
        labelText = label
        fgArcPaint.color = when {
            percent >= 80 -> Color.parseColor("#FF2D2D")
            percent >= 50 -> Color.parseColor("#FF6B00")
            else -> Color.parseColor("#3DDC97")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val strokeWidth = w * 0.09f
        bgArcPaint.strokeWidth = strokeWidth
        fgArcPaint.strokeWidth = strokeWidth

        val padding = strokeWidth
        arcRect.set(padding, padding, w - padding, h - padding)

        val startAngle = 135f
        val sweepFull = 270f

        canvas.drawArc(arcRect, startAngle, sweepFull, false, bgArcPaint)
        canvas.drawArc(arcRect, startAngle, sweepFull * progress, false, fgArcPaint)

        valuePaint.textSize = w * 0.20f
        canvas.drawText(valueText, w / 2f, h / 2f + valuePaint.textSize * 0.1f, valuePaint)

        labelPaint.textSize = w * 0.11f
        canvas.drawText(labelText, w / 2f, h / 2f + valuePaint.textSize * 0.55f + labelPaint.textSize, labelPaint)
    }
}
