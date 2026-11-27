package com.boostvn.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Tiện ích trong game: Khóa độ sáng tự động và Tâm ngắm ảo (Crosshair).
 *
 * Chặn thông báo (Không làm phiền) KHÔNG nằm ở đây: GameOptimizationEngine + HudOverlayService đã
 * quản lý DND có lưu/khôi phục trạng thái gốc, làm thêm ở đây sẽ giẫm chân nhau.
 */
class InGameUtilityController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var crosshairView: CrosshairView? = null

    val isCrosshairVisible: Boolean get() = crosshairView != null

    val isBrightnessLocked: Boolean
        get() = SessionSnapshotStore.get(context, KEY_BRIGHTNESS_MODE)?.toIntOrNull() != null

    /**
     * Khóa độ sáng tự động (tránh màn hình tự tối khi tay che cảm biến ánh sáng).
     * Cần quyền WRITE_SETTINGS do người dùng tự cấp. Trả về false nếu chưa có quyền / lỗi.
     */
    fun toggleBrightnessLock(lock: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return try {
            if (lock) {
                // Chỉ lưu chế độ GỐC một lần - bấm khóa lần 2 không được ghi đè bằng MANUAL.
                if (!isBrightnessLocked) {
                    val current = Settings.System.getInt(
                        context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                    SessionSnapshotStore.save(context, KEY_BRIGHTNESS_MODE, current.toString())
                }
                Settings.System.putInt(
                    context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                true
            } else {
                restoreBrightnessIfLocked(context)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Bật/Tắt tâm ngắm ảo ở giữa màn hình cho game FPS. Cần quyền "Hiển thị trên ứng dụng khác".
     * Trả về false nếu chưa có quyền hoặc hệ thống từ chối (không crash).
     */
    fun toggleCrosshair(show: Boolean, color: Int = Color.GREEN, sizeDp: Int = 28): Boolean {
        if (!show) {
            removeCrosshair()
            return true
        }
        if (crosshairView != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        return try {
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).toInt().coerceAtLeast(1)
            val view = CrosshairView(context, color, density)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                sizePx, sizePx, layoutFlag,
                // NOT_TOUCHABLE: chạm xuyên qua tâm ngắm vào game bình thường.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            windowManager.addView(view, params)
            crosshairView = view
            true
        } catch (e: Throwable) {
            crosshairView = null
            false
        }
    }

    private fun removeCrosshair() {
        crosshairView?.let { runCatching { windowManager.removeView(it) } }
        crosshairView = null
    }

    /** Dọn toàn bộ khi tắt HUD / kết thúc phiên. Chỉ khôi phục những gì CHÍNH app này đã đổi. */
    fun releaseAll() {
        removeCrosshair()
        restoreBrightnessIfLocked(context)
    }

    private class CrosshairView(
        context: Context,
        crosshairColor: Int,
        private val density: Float
    ) : View(context) {

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = crosshairColor
            strokeWidth = 1.5f * density
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val gap = 3f * density
            val len = minOf(width, height) / 2f - linePaint.strokeWidth

            // 4 gạch chữ thập có khoảng hở ở tâm + chấm đỏ nhỏ ở giữa
            canvas.drawLine(cx - len, cy, cx - gap, cy, linePaint)
            canvas.drawLine(cx + gap, cy, cx + len, cy, linePaint)
            canvas.drawLine(cx, cy - len, cx, cy - gap, linePaint)
            canvas.drawLine(cx, cy + gap, cx, cy + len, linePaint)
            canvas.drawCircle(cx, cy, 1.2f * density, dotPaint)
        }
    }

    companion object {
        private const val KEY_BRIGHTNESS_MODE = "brightness_mode_original"

        /**
         * Khôi phục chế độ độ sáng gốc NẾU app đã khóa nó (có bản lưu). Nếu chưa từng khóa thì
         * không làm gì - tránh ép máy về "tự động" khi người dùng đang để thủ công.
         * Được gọi cả từ GameOptimizationEngine.restore() nên cũng chạy khi phục hồi phiên bị ngắt.
         */
        fun restoreBrightnessIfLocked(context: Context): Boolean {
            val saved = SessionSnapshotStore.get(context, KEY_BRIGHTNESS_MODE)?.toIntOrNull()
                ?: return true
            if (!Settings.System.canWrite(context)) return false
            return try {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, saved)
                SessionSnapshotStore.save(context, KEY_BRIGHTNESS_MODE, "")
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
