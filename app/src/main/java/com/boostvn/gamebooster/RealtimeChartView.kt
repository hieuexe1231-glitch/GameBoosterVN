package com.boostvn.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Biểu đồ đường đơn giản, tự vẽ bằng Canvas (không cần thư viện ngoài).
 * Nhận vào 1 danh sách giá trị 0-100 (%) và vẽ thành đường biểu diễn theo thời gian.
 */
class RealtimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val values = mutableListOf<Float>()
    private val maxPoints = 30

    private val linePaint = Paint().apply {
        color = Color.parseColor("#3D5CFF")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#333D5CFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#2A2F55")
        strokeWidth = 2f
    }

    fun addValue(percent: Float) {
        values.add(percent.coerceIn(0f, 100f))
        if (values.size > maxPoints) values.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Vẽ lưới ngang (25%, 50%, 75%)
        for (i in 1..3) {
            val y = h * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (values.size < 2) return

        val stepX = w / (maxPoints - 1).toFloat()
        val path = android.graphics.Path()
        val fillPath = android.graphics.Path()

        val startX = w - (values.size - 1) * stepX
        values.forEachIndexed { index, v ->
            val x = startX + index * stepX
            val y = h - (v / 100f * h)
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(startX + (values.size - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
