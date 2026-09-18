package com.boostvn.gamebooster

import android.content.Context
import android.os.PowerManager

/**
 * "Lúc đầu mượt, về sau lag lại như cũ" - nguyên nhân THỰC TẾ phổ biến nhất với các app
 * dùng Shizuku KHÔNG PHẢI do logic tối ưu tệ đi, mà do 2 điều kiện tiên quyết bị THU HỒI
 * ÂM THẦM sau một thời gian, mà app không hề báo cho người dùng biết:
 *
 * 1) Shizuku mất quyền/mất kết nối - Shizuku (qua Gỡ lỗi không dây) KHÔNG tự khởi động lại
 *    sau khi máy khởi động lại/tắt nguồn, trừ khi người dùng tự mở lại app Shizuku. Nhiều
 *    người dùng khởi động lại máy (cập nhật ROM, sạc pin xong khởi động lại...) rồi quên
 *    mở lại Shizuku - từ lúc đó, MỌI tối ưu qua Shizuku (Fixed Performance Mode, Game Mode,
 *    dọn app nền, Standby Bucket...) âm thầm KHÔNG chạy nữa, app chỉ còn tối ưu Android cơ
 *    bản (yếu hơn nhiều).
 * 2) Quyền "Bỏ qua tối ưu hoá pin" bị hãng máy tự thu hồi - các ROM Trung Quốc (MIUI,
 *    ColorOS, FuntouchOS...) có trình quản lý pin RIÊNG của hãng, nổi tiếng tự động thu hồi
 *    lại các quyền autostart/bỏ qua tối ưu pin sau vài ngày "không dùng nhiều" theo đánh giá
 *    riêng của hãng - kể cả khi người dùng đã cấp quyền thủ công trước đó.
 *
 * App không thể ngăn 2 điều này xảy ra (đây là hành vi của Shizuku/hãng máy, ngoài tầm kiểm
 * soát), nhưng CÓ THỂ và NÊN phát hiện rồi báo ngay cho người dùng biết CHÍNH XÁC lý do,
 * thay vì để người dùng tưởng nhầm "AI dở đi" hay "app dở đi theo thời gian".
 */
object HealthCheckHelper {
    private const val PREFS = "booster_health_state"
    private const val KEY_SHIZUKU_EVER_OK = "was_shizuku_ok"
    private const val KEY_BATTERY_EVER_OK = "was_battery_ok"

    data class HealthStatus(
        val shizukuPermission: Boolean,
        val batteryExempt: Boolean,
        val regressedShizuku: Boolean, // TỪNG hoạt động tốt, giờ KHÔNG còn - đây là tín hiệu đáng báo
        val regressedBattery: Boolean
    )

    fun check(context: Context): HealthStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shizukuPerm = try { ShizukuHelper.hasPermission() } catch (_: Throwable) { false }
        val batteryExempt = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (_: Throwable) { false }

        val wasShizukuOk = prefs.getBoolean(KEY_SHIZUKU_EVER_OK, false)
        val wasBatteryOk = prefs.getBoolean(KEY_BATTERY_EVER_OK, false)

        val regressedShizuku = wasShizukuOk && !shizukuPerm
        val regressedBattery = wasBatteryOk && !batteryExempt

        // Chỉ NÂNG "đã từng ổn" lên true, không hạ xuống false ở đây - việc hạ xuống chỉ
        // nên xảy ra khi người dùng đã được cảnh báo (tự nhiên: lần kiểm tra sau khi đã sửa
        // sẽ thấy shizukuPerm=true trở lại, regressedShizuku tự về false, không cần bước
        // "reset" thủ công nào).
        val editor = prefs.edit()
        if (shizukuPerm) editor.putBoolean(KEY_SHIZUKU_EVER_OK, true)
        if (batteryExempt) editor.putBoolean(KEY_BATTERY_EVER_OK, true)
        editor.apply()

        return HealthStatus(shizukuPerm, batteryExempt, regressedShizuku, regressedBattery)
    }
}
