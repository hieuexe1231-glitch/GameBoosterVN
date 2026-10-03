package com.boostvn.gamebooster

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Đồng hồ đo hình vòng cung - phong cách neon cyan/cam theo yêu cầu (giống ảnh dashboard
 * tham khảo): vòng cung phát sáng (glow) đổi màu theo mức độ, số liệu lớn ở giữa, nhãn nhỏ
 * bên dưới. THẬT SỰ CHUYỂN ĐỘNG: mỗi lần setValue() được gọi với số liệu mới, vòng cung tự
 * chạy mượt từ giá trị cũ sang giá trị mới bằng ValueAnimator (không nhảy khựng như trước) -
 * đúng yêu cầu "phải chuyển động được". Tự vẽ bằng Canvas, không cần thư viện ngoài.
 */
class GaugeRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var displayedProgress: Float = 0f // 0..1 - giá trị ĐANG VẼ (chạy mượt tới targetProgress)
    private var targetProgress: Float = 0f // 0..1 - giá trị THẬT mới nhất
    private var valueText: String = "--"
    private var labelText: String = ""
    private var animator: ValueAnimator? = null

    // Nền vòng cung: cyan mờ - đúng tông màu chủ đạo của giao diện tham khảo (thay cho
    // trắng mờ trung tính trước đây).
    private val bgArcPaint = Paint().apply {
        color = Color.parseColor("#3300D9FF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val fgArcPaint = Paint().apply {
        color = Color.parseColor("#00D9FF")
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
        val clamped = percent.coerceIn(0f, 100f) / 100f
        valueText = displayText
        labelText = label
        // Màu theo mức độ: cyan (bình thường) -> cam (cảnh báo) -> đỏ (nguy hiểm) - vẫn giữ
        // ý nghĩa cảnh báo thật (không chỉ đổi màu cho đẹp), nhưng tông chủ đạo chuyển sang
        // cyan để khớp giao diện tham khảo thay vì xanh lá trước đây.
        val targetColor = when {
            percent >= 80 -> Color.parseColor("#FF2D2D")
            percent >= 50 -> Color.parseColor("#FF6B00")
            else -> Color.parseColor("#00D9FF")
        }
        fgArcPaint.color = targetColor

        // Chuyển động THẬT từ giá trị đang hiển thị sang giá trị mới - 600ms, giảm tốc dần
        // (DecelerateInterpolator) giống hiệu ứng đồng hồ vật lý đang "chốt" về đúng mức.
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedProgress, clamped).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        targetProgress = clamped
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val strokeWidth = w * 0.09f
        bgArcPaint.strokeWidth = strokeWidth
        fgArcPaint.strokeWidth = strokeWidth
        // Hiệu ứng phát sáng neon quanh vòng cung - đúng phong cách giao diện tham khảo.
        fgArcPaint.setShadowLayer(strokeWidth * 0.8f, 0f, 0f, fgArcPaint.color)
        setLayerType(LAYER_TYPE_SOFTWARE, fgArcPaint) // bắt buộc để shadowLayer hiển thị đúng

        val padding = strokeWidth
        arcRect.set(padding, padding, w - padding, h - padding)

        val startAngle = 135f
        val sweepFull = 270f

        canvas.drawArc(arcRect, startAngle, sweepFull, false, bgArcPaint)
        canvas.drawArc(arcRect, startAngle, sweepFull * displayedProgress, false, fgArcPaint)

        valuePaint.textSize = w * 0.20f
        canvas.drawText(valueText, w / 2f, h / 2f + valuePaint.textSize * 0.1f, valuePaint)

        labelPaint.textSize = w * 0.11f
        canvas.drawText(labelText, w / 2f, h / 2f + valuePaint.textSize * 0.55f + labelPaint.textSize, labelPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel() // tránh rò rỉ animator khi view bị huỷ (đổi tab, đóng màn hình...)
    }
}
