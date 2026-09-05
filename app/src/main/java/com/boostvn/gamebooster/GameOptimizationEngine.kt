package com.boostvn.gamebooster

import android.content.Context
import android.os.Build

/**
 * Game Optimization Engine v1.7 — Universal / Long Session.
 * Ưu tiên hiệu năng bền vững: không ép xung, không sửa governor, không trim cache,
 * không can thiệp thermal policy và không chạy tác vụ nặng trong trận.
 *
 * NÂNG CẤP v1.7 - CHỈ THÊM 2 THỨ ĐÃ XÁC MINH LÀ CÓ THẬT (không bịa "mẹo tăng tốc"):
 * 1) Fixed Performance Mode - API CHÍNH THỨC của Google (Android Game SDK / ADPF,
 *    xem developer.android.com/games/optimize/adpf/fixed-performance-mode), khoá
 *    xung nhịp CPU/GPU ở mức ỔN ĐỊNH thay vì để hệ thống tự dao động lên/xuống liên
 *    tục - đây chính là nguyên nhân gây frame-time không đều lúc combat.
 * 2) Tắt hiệu ứng chuyển cảnh hệ thống (window/transition/animator scale = 0) -
 *    tính năng CÓ THẬT của "Tuỳ chọn nhà phát triển" trong Android từ đời đầu.
 * Rất nhiều "mẹo tăng tốc" trôi nổi trên mạng (cpu_boost_enabled, sem_enhanced_*,
 * multicore_packet_scheduler...) là KHÔNG có thật hoặc chỉ dành riêng cho 1 hãng
 * (Samsung One UI) - không đưa vào để tránh lừa dối người dùng.
 */
object GameOptimizationEngine {
    private const val LIEN_QUAN_PACKAGE = "com.garena.game.kgvn"
    private const val FREE_FIRE_PACKAGE = "com.dts.freefireth"

    data class Result(val actions: List<String>, val verified: Boolean, val profile: String)

    fun prepare(context: Context, gamePackage: String, mode: MainActivity.PerfMode): Result {
        val actions = mutableListOf<String>()
        var verifiedCount = 0
        val shizuku = ShizukuHelper.hasPermission()
        val isLienQuan = gamePackage == LIEN_QUAN_PACKAGE
        val isFreeFire = gamePackage == FREE_FIRE_PACKAGE
        val batteryTemp = TemperatureHelper.getBatteryTemperatureC(context)
        val isThermalSafe = batteryTemp != null && batteryTemp >= 42f
        val sustainedMode = when {
            isThermalSafe -> "THERMAL-SAFE"
            mode == MainActivity.PerfMode.TIET_KIEM -> "BATTERY-SAFE"
            else -> "SUSTAINED PERFORMANCE"
        }
        val profile = when {
            isLienQuan -> "LIÊN QUÂN $sustainedMode"
            isFreeFire -> "FREE FIRE $sustainedMode"
            else -> "UNIVERSAL GAME $sustainedMode"
        }

        if (!shizuku) {
            actions += "Shizuku: chưa cấp quyền — dùng tối ưu Android an toàn"
            actions += "Profile: $profile"
            return Result(actions, false, profile)
        }

        // Android Game Mode: dùng API shell chuẩn khi ROM hỗ trợ. Không ép xung.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val gameModeOk = listOf(
                "cmd game mode performance $gamePackage",
                "cmd game mode 2 $gamePackage"
            ).any { ShizukuHelper.runShellCommand(it) }
            if (gameModeOk) {
                actions += "Game Mode Performance: OK"
                verifiedCount++
            } else actions += "Game Mode: ROM không hỗ trợ/xác nhận"
        }

        // Giảm khả năng Doze/App Standby can thiệp vào game.
        if (ShizukuHelper.runShellCommand("cmd deviceidle whitelist +$gamePackage")) {
            actions += "Doze whitelist: OK"
            verifiedCount++
        }
        if (ShizukuHelper.runShellCommand("cmd activity set-inactive $gamePackage false")) {
            actions += "App active state: OK"
            verifiedCount++
        }

        // Fixed Performance Mode - xem ghi chú đầu file. KHÔNG bật khi máy đang nóng vì
        // chế độ này có thể làm tăng nhiệt độ thêm nếu dùng lúc máy đã nóng sẵn -
        // AdaptiveGameEngine sẽ tự tắt lại chế độ này nếu phát hiện máy nóng giữa chừng.
        if (!isThermalSafe) {
            if (ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled true")) {
                actions += "Fixed Performance Mode: OK (frame-time ổn định hơn)"
                verifiedCount++
            }
        } else {
            actions += "Fixed Performance Mode: bỏ qua vì máy đang nóng (ưu tiên an toàn)"
        }

        // Tắt hiệu ứng chuyển cảnh hệ thống - không ảnh hưởng khung hình bên trong game,
        // chỉ giúp thao tác chuyển đổi màn hình/mở app mượt hơn ngay lập tức.
        val animOk = listOf(
            "settings put global window_animation_scale 0",
            "settings put global transition_animation_scale 0",
            "settings put global animator_duration_scale 0"
        ).all { ShizukuHelper.runShellCommand(it) }
        if (animOk) {
            actions += "Tắt hiệu ứng chuyển cảnh hệ thống: OK"
            verifiedCount++
        }

        // Không compile ART mỗi lần mở game: thao tác này có thể tốn I/O/CPU và không
        // đảm bảo tăng FPS. Android/Play sẽ tự quản lý profile khi cần.
        actions += "ART compile: bỏ qua để giảm I/O và giữ ổn định lâu dài"

        if (batteryTemp != null) actions += "Nhiệt pin: ${"%.1f".format(batteryTemp)}°C"
        actions += "Profile: $profile"
        actions += when {
            isLienQuan -> "Liên Quân: ưu tiên frame-time ổn định + sustained performance"
            isFreeFire -> "Free Fire: ưu tiên frame-time ổn định + sustained performance"
            else -> "Game: profile universal + sustained performance"
        }
        actions += "Không ép xung / không đổi governor / không trim cache / không kill trong trận"

        return Result(actions, verifiedCount > 0, profile)
    }

    /**
     * Khôi phục mọi thay đổi tạm thời khi kết thúc phiên chơi - QUAN TRỌNG, không được
     * để "kẹt" lại (Fixed Performance Mode kẹt bật sẽ tốn pin không cần thiết cả ngày,
     * animation tắt vĩnh viễn sẽ khiến cảm giác dùng máy hàng ngày cứng/giật hình).
     */
    fun restore() {
        if (!ShizukuHelper.hasPermission()) return
        ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled false")
        ShizukuHelper.runShellCommand("settings put global window_animation_scale 1")
        ShizukuHelper.runShellCommand("settings put global transition_animation_scale 1")
        ShizukuHelper.runShellCommand("settings put global animator_duration_scale 1")
    }

    /** Chỉ tắt Fixed Performance Mode - dùng khi phát hiện máy nóng bất thường giữa
     * chừng, vẫn giữ tắt animation vì không liên quan tới nhiệt độ. */
    fun disableFixedPerformanceOnly() {
        if (!ShizukuHelper.hasPermission()) return
        ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled false")
    }
}
