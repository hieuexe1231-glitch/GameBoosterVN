package com.boostvn.gamebooster

/**
 * Bật/tắt WiFi và Dữ liệu di động nhanh qua Shizuku (lệnh `svc` chuẩn ADB, không cần root).
 *
 * VÌ SAO CẦN: khi đang chơi mà WiFi yếu/chập chờn, chuyển nhanh sang 4G/5G (hoặc ngược lại)
 * mà không phải thoát game ra Cài đặt hệ thống - đúng tinh thần "hành động thật, không chỉ
 * đo đạc" của app (xem README).
 *
 * THẬT THÀ VỀ GIỚI HẠN: lệnh `svc wifi`/`svc data` là lệnh ADB chuẩn, hoạt động trên hầu hết
 * máy Android không root có Shizuku đang chạy và đã cấp quyền. Một số ROM (đặc biệt máy
 * khoá SELinux chặt) có thể chặn lệnh này dù đã cấp quyền Shizuku - mọi hàm dưới đây trả về
 * null một cách an toàn khi không chắc chắn, KHÔNG báo giả là đã đổi thành công.
 */
object NetworkToggleHelper {

    /** Đọc trạng thái WiFi hiện tại. Trả về null nếu không đọc được (chưa có Shizuku...). */
    fun isWifiEnabled(): Boolean? {
        val out = ShizukuHelper.runShellCommandWithOutput("settings get global wifi_on") ?: return null
        return out.trim() == "1"
    }

    /** Đọc trạng thái Dữ liệu di động hiện tại. Trả về null nếu không đọc được. */
    fun isMobileDataEnabled(): Boolean? {
        val out = ShizukuHelper.runShellCommandWithOutput("settings get global mobile_data") ?: return null
        return out.trim() == "1"
    }

    /**
     * Đảo trạng thái WiFi (đang bật -> tắt, đang tắt -> bật). Đọc trạng thái GỐC trước khi
     * đổi (đúng nguyên tắc "đọc giá trị gốc trước khi đổi" app đã áp dụng từ v1.23 khi sửa
     * lỗi hiệu ứng chuyển cảnh), không đoán mò trạng thái ban đầu là gì.
     *
     * @return trạng thái MỚI sau khi đổi thành công, hoặc null nếu thất bại (không đụng gì
     *         tới máy - không có tác dụng phụ khi trả về null).
     */
    fun toggleWifi(): Boolean? {
        val current = isWifiEnabled() ?: return null
        val target = !current
        val cmd = if (target) "svc wifi enable" else "svc wifi disable"
        val ok = ShizukuHelper.runShellCommand(cmd)
        return if (ok) target else null
    }

    /** Giống toggleWifi() nhưng cho Dữ liệu di động. */
    fun toggleMobileData(): Boolean? {
        val current = isMobileDataEnabled() ?: return null
        val target = !current
        val cmd = if (target) "svc data enable" else "svc data disable"
        val ok = ShizukuHelper.runShellCommand(cmd)
        return if (ok) target else null
    }
}
