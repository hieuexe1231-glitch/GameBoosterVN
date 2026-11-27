package com.boostvn.gamebooster

import android.os.Build

/**
 * Bổ sung cho GameOptimizationEngine ở những máy CHƯA có Android Game Mode API.
 *
 * - Android 12+ (API 31+): GameOptimizationEngine đã tự áp Game Mode (battery/standard/performance)
 *   có xác minh đọc-lại và khôi phục đúng mode GỐC của người dùng khi thoát game -> file này
 *   không can thiệp để tránh ghi đè.
 * - Android 8-11: không có GameManager, nên đưa game vào standby bucket "active" để hệ thống
 *   ít bóp tài nguyên nền của game hơn (lệnh `am set-standby-bucket`, cần Android 9+; máy cũ
 *   hơn lệnh sẽ thất bại và hàm trả về false một cách an toàn).
 *
 * ĐÃ BỎ có chủ đích: `cmd thermalservice override-status 0`. Lệnh đó chỉ "nói dối" ứng dụng rằng
 * máy không nóng, KHÔNG gỡ được giới hạn nhiệt phần cứng -> game tưởng máy mát nên không tự
 * giảm tải, máy nóng hơn mà vẫn bị throttle. Không an toàn cho máy nên không đưa vào app.
 */
object AndroidGameModeHelper {

    private val SAFE_PACKAGE = Regex("^[A-Za-z0-9._]+$")

    fun applyLegacyBoost(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        // Tên package được ghép vào lệnh shell -> chỉ chấp nhận ký tự hợp lệ.
        if (!SAFE_PACKAGE.matches(packageName)) return false
        if (!ShizukuHelper.hasPermission()) return false
        return ShizukuHelper.runShellCommand("am set-standby-bucket $packageName active")
    }
}
