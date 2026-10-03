package com.boostvn.gamebooster

import android.content.Context
import android.content.ContentResolver
import android.os.Build

/**
 * Game Optimization Engine v2.0 — Extreme Smooth / Universal.
 * Ưu tiên hiệu năng bền vững + frame-time ổn định: không ép xung, không sửa governor,
 * không trim cache nặng, không can thiệp thermal policy nguy hiểm.
 *
 * NÂNG CẤP v2.0:
 * - Ưu tiên Fixed Performance Mode mạnh hơn khi AI học thấy game dễ lag (lag sensitivity
 *   cao) nhưng nhiệt vẫn an toàn → khoá xung ổn định để giảm frame-time dao động.
 * - Kết hợp LearningProfileHelper.getLearnedLagSensitivity để quyết định profile.
 * - Giữ toàn bộ tối ưu thật đã xác minh (Game Mode, animation scale 0, auto-sync off,
 *   refresh rate, standby bucket...).
 */
object GameOptimizationEngine {
    private const val LIEN_QUAN_PACKAGE = "com.garena.game.kgvn"
    private const val FREE_FIRE_PACKAGE = "com.dts.freefireth"

    data class Result(val actions: List<String>, val verified: Boolean, val profile: String, val fixedPerfEnabled: Boolean)

    fun prepare(context: Context, gamePackage: String, mode: MainActivity.PerfMode): Result {
        val actions = mutableListOf<String>()
        var verifiedCount = 0
        val shizuku = ShizukuHelper.hasPermission()
        val isLienQuan = gamePackage == LIEN_QUAN_PACKAGE
        val isFreeFire = gamePackage == FREE_FIRE_PACKAGE
        val batteryTemp = TemperatureHelper.getBatteryTemperatureC(context)
        val isLowRam = DeviceProfileHelper.isLowRamDevice(context)
        // Dùng cả tín hiệu chính thức của Android (đáng tin hơn ngưỡng độ C tự đoán, xem
        // TemperatureHelper.getThermalStatus) LẪN ngưỡng độ C dự phòng cho máy không hỗ trợ.
        val thermalStatus = TemperatureHelper.getThermalStatus(context)
        val isThermalSafe = (batteryTemp != null && batteryTemp >= DeviceProfileHelper.thermalSafeThresholdC(context)) ||
            (thermalStatus != null && thermalStatus >= android.os.PowerManager.THERMAL_STATUS_LIGHT)
        // AI học trên máy: game này lịch sử có hay làm máy nóng không.
        // v2.0: thêm lag sensitivity — nếu game dễ lag nhưng nhiệt an toàn thì ƯU TIÊN
        // Fixed Performance Mode hơn (khoá xung ổn định giúp frame-time mượt).
        val isThermalRiskyGame = LearningProfileHelper.getLearnedThermalRisk(context, gamePackage)
        val lagSensitivity = LearningProfileHelper.getLearnedLagSensitivity(context, gamePackage)
        val preferFixedForLag = lagSensitivity >= 0.35f && !isThermalSafe && !isThermalRiskyGame
        val skipFixedPerf = (isThermalSafe || isThermalRiskyGame) && !preferFixedForLag
        val sustainedMode = when {
            isThermalSafe -> "THERMAL-SAFE"
            mode == MainActivity.PerfMode.TIET_KIEM -> "BATTERY-SAFE"
            preferFixedForLag -> "LAG-SENSITIVE FIXED"
            else -> "SUSTAINED PERFORMANCE"
        }
        val profile = when {
            isLienQuan -> "LIÊN QUÂN $sustainedMode"
            isFreeFire -> "FREE FIRE $sustainedMode"
            else -> "UNIVERSAL GAME $sustainedMode"
        }

        if (isLowRam) actions += "Phát hiện máy cấu hình thấp: ưu tiên dọn RAM + giảm nền, hạn chế đo đạc"

        // Tắt đồng bộ tài khoản nền - hoạt động trên MỌI máy, không cần Shizuku/root.
        // Đây là hành động TỐI ƯU THẬT (giảm CPU/mạng ngầm) chứ không phải đo đạc.
        if (disableAutoSync()) {
            actions += "Tắt đồng bộ tài khoản nền: OK (giảm CPU/mạng ngầm suốt trận)"
            verifiedCount++
        }

        if (!shizuku) {
            actions += "Shizuku: chưa cấp quyền — dùng tối ưu Android an toàn"
            actions += "Profile: $profile"
            return Result(actions, verifiedCount > 0, profile, fixedPerfEnabled = false)
        }

        // Android Game Mode: dùng API shell chuẩn khi ROM hỗ trợ. Không ép xung.
        // SỬA LỖI v1.8: trước đây LUÔN ép "performance" bất kể người dùng chọn chế độ
        // gì trong UI (nút "Tiết kiệm" không có tác dụng thật) - giờ tôn trọng đúng lựa
        // chọn của người dùng bằng GameManager mode chính thức của Android
        // (developer.android.com/reference/android/app/GameManager):
        // STANDARD=1, PERFORMANCE=2, BATTERY=3. Máy đang nóng luôn ưu tiên STANDARD dù
        // người dùng chọn gì, vì lúc đó an toàn nhiệt quan trọng hơn.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val (gameModeName, gameModeCode) = when {
                skipFixedPerf -> "standard" to 1
                mode == MainActivity.PerfMode.TIET_KIEM -> "battery" to 3
                else -> "performance" to 2
            }
            val gameModeOk = listOf(
                "cmd game mode $gameModeName $gamePackage",
                "cmd game mode $gameModeCode $gamePackage"
            ).any { ShizukuHelper.runShellCommand(it) }
            if (gameModeOk) {
                actions += "Game Mode ${gameModeName.uppercase()}: OK"
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

        // Fixed Performance Mode - xem ghi chú đầu file. KHÔNG bật khi máy đang nóng, hoặc
        // khi AI đã học được rằng chính GAME này lịch sử hay làm máy nóng (né trước thay vì
        // đợi nóng thật) - AdaptiveGameEngine vẫn tự tắt lại nếu phát hiện nóng giữa chừng.
        var fixedPerfEnabled = false
        if (!skipFixedPerf && mode != MainActivity.PerfMode.TIET_KIEM) {
            if (ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled true")) {
                actions += "Fixed Performance Mode: OK (frame-time ổn định hơn) - AI sẽ tự kiểm tra lại sau ~75s, rollback nếu làm máy nóng hơn"
                verifiedCount++
                fixedPerfEnabled = true
            }
        } else if (mode == MainActivity.PerfMode.TIET_KIEM) {
            actions += "Fixed Performance Mode: bỏ qua vì đang ở chế độ Tiết kiệm"
        } else if (isThermalRiskyGame && !isThermalSafe) {
            actions += "Fixed Performance Mode: bỏ qua vì AI ghi nhận game này hay làm máy nóng"
        } else {
            actions += "Fixed Performance Mode: bỏ qua vì máy đang nóng (ưu tiên an toàn)"
        }

        // Tắt hiệu ứng chuyển cảnh hệ thống - không ảnh hưởng khung hình bên trong game,
        // chỉ giúp thao tác chuyển đổi màn hình/mở app mượt hơn ngay lập tức.
        // SỬA LỖI (tham khảo mẫu "Crash-Safe Snapshot" của dự án mã nguồn mở FrameX):
        // trước đây ghi cứng "0" rồi lúc restore() lại ghi cứng "1" - GIẢ ĐỊNH người dùng
        // chưa từng tự chỉnh 3 giá trị này. Nếu người dùng đã tự đặt tốc độ hiệu ứng riêng
        // (ví dụ 0.5x cho mượt tay hơn - khá phổ biến), app sẽ âm thầm reset về 1.0x sau
        // mỗi trận mà người dùng không hề yêu cầu. Giờ ĐỌC giá trị gốc trước, LƯU lại, rồi
        // restore() trả về ĐÚNG giá trị đã đọc được (chỉ dùng "1" làm phương án dự phòng
        // nếu không đọc được gì).
        captureOriginalAnimationScales()
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

        // Ghim tần số quét màn hình về ĐÚNG mức tối đa của chính máy đó (không khoá cứng
        // về 1 số cố định như 60Hz - sẽ giới hạn ngược máy có màn 90/120Hz). Tương ứng
        // đúng công tắc "Buộc tần số quét tối đa" trong Tuỳ chọn nhà phát triển Android -
        // API chính thức (settings system peak_refresh_rate/min_refresh_rate), chỉ đang
        // bật bằng lệnh thay vì bắt người dùng tự vào Cài đặt. Mục đích: tránh màn hình tự
        // chuyển đổi qua lại 60↔90↔120Hz giữa trận (chính bản thân việc chuyển đổi này có
        // thể gây giật khung hình thoáng qua), không phải để "tăng" hay "giảm" FPS.
        pinRefreshRateToMax(context)

        return Result(actions, verifiedCount > 0, profile, fixedPerfEnabled)
    }

    @Volatile private var originalPeakRefreshRate: String? = null
    @Volatile private var originalMinRefreshRate: String? = null

    private fun pinRefreshRateToMax(context: Context) {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
            } ?: return
            val maxHz = display.supportedModes.maxOf { it.refreshRate }
            if (maxHz <= 0f) return

            if (originalPeakRefreshRate == null) {
                originalPeakRefreshRate = ShizukuHelper.runShellCommandWithOutput("settings get system peak_refresh_rate")?.trim()
                originalMinRefreshRate = ShizukuHelper.runShellCommandWithOutput("settings get system min_refresh_rate")?.trim()
            }
            val hz = maxHz.toInt()
            ShizukuHelper.runShellCommand("settings put system peak_refresh_rate $hz")
            ShizukuHelper.runShellCommand("settings put system min_refresh_rate $hz")
        } catch (_: Throwable) { }
    }

    private fun restoreRefreshRate() {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null" && v.toFloatOrNull() != null
        if (originalPeakRefreshRate != null || originalMinRefreshRate != null) {
            originalPeakRefreshRate?.takeIf { valid(it) }?.let {
                ShizukuHelper.runShellCommand("settings put system peak_refresh_rate $it")
            }
            originalMinRefreshRate?.takeIf { valid(it) }?.let {
                ShizukuHelper.runShellCommand("settings put system min_refresh_rate $it")
            }
            originalPeakRefreshRate = null
            originalMinRefreshRate = null
        }
    }

    /**
     * Khôi phục mọi thay đổi tạm thời khi kết thúc phiên chơi - QUAN TRỌNG, không được
     * để "kẹt" lại (Fixed Performance Mode kẹt bật sẽ tốn pin không cần thiết cả ngày,
     * animation tắt vĩnh viễn sẽ khiến cảm giác dùng máy hàng ngày cứng/giật hình).
     */
    fun restore(context: Context) {
        // Bật lại đồng bộ tài khoản - không được để tắt vĩnh viễn sau khi chơi xong,
        // nếu không danh bạ/email/ảnh sẽ ngừng cập nhật cả ngày mà người dùng không biết.
        restoreAutoSync()
        if (!ShizukuHelper.hasPermission()) return
        ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled false")
        restoreOriginalAnimationScales()
        restoreRefreshRate()
    }

    // Nhớ lại 3 giá trị tốc độ hiệu ứng GỐC của người dùng trước khi tắt - xem ghi chú
    // "Crash-Safe Snapshot" ở nơi gọi. "1" chỉ là phương án dự phòng cuối cùng nếu không
    // đọc được (ví dụ lệnh `settings get` thất bại) - không phải giá trị được ưu tiên.
    @Volatile private var originalWindowAnimScale: String? = null
    @Volatile private var originalTransitionAnimScale: String? = null
    @Volatile private var originalAnimatorDurationScale: String? = null

    private fun captureOriginalAnimationScales() {
        if (originalWindowAnimScale != null) return // đã chụp rồi (ví dụ prepare() gọi lại
                                                       // trong cùng phiên) - không ghi đè để
                                                       // tránh vô tình "chụp" lại giá trị đã
                                                       // bị chính app này đổi thành 0.
        originalWindowAnimScale = ShizukuHelper.runShellCommandWithOutput("settings get global window_animation_scale")?.trim()
        originalTransitionAnimScale = ShizukuHelper.runShellCommandWithOutput("settings get global transition_animation_scale")?.trim()
        originalAnimatorDurationScale = ShizukuHelper.runShellCommandWithOutput("settings get global animator_duration_scale")?.trim()
    }

    private fun restoreOriginalAnimationScales() {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null" && v.toFloatOrNull() != null
        ShizukuHelper.runShellCommand("settings put global window_animation_scale ${originalWindowAnimScale.takeIf { valid(it) } ?: "1"}")
        ShizukuHelper.runShellCommand("settings put global transition_animation_scale ${originalTransitionAnimScale.takeIf { valid(it) } ?: "1"}")
        ShizukuHelper.runShellCommand("settings put global animator_duration_scale ${originalAnimatorDurationScale.takeIf { valid(it) } ?: "1"}")
        originalWindowAnimScale = null
        originalTransitionAnimScale = null
        originalAnimatorDurationScale = null
    }

    // Nhớ lại trạng thái sync gốc để khôi phục đúng, tránh trường hợp người dùng đã tự
    // tắt sync từ trước rồi bị app này "bật nhầm" lên sau khi chơi xong.
    @Volatile private var originalSyncEnabled: Boolean? = null

    private fun disableAutoSync(): Boolean = try {
        if (originalSyncEnabled == null) {
            originalSyncEnabled = ContentResolver.getMasterSyncAutomatically()
        }
        if (ContentResolver.getMasterSyncAutomatically()) {
            ContentResolver.setMasterSyncAutomatically(false)
        }
        true
    } catch (_: Throwable) { false }

    private fun restoreAutoSync() {
        try {
            val original = originalSyncEnabled
            if (original == true) {
                ContentResolver.setMasterSyncAutomatically(true)
            }
        } catch (_: Throwable) { }
        originalSyncEnabled = null
    }

    /** Chỉ tắt Fixed Performance Mode - dùng khi phát hiện máy nóng bất thường giữa
     * chừng, vẫn giữ tắt animation vì không liên quan tới nhiệt độ. */
    fun disableFixedPerformanceOnly() {
        if (!ShizukuHelper.hasPermission()) return
        ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled false")
    }

    /** SỬA LỖI QUAN TRỌNG (nguyên nhân "một khi giật thì mãi không hết" người dùng báo):
     * trước đây disableFixedPerformanceOnly() không có hàm bật lại tương ứng nào - máy chỉ
     * hơi nóng thoáng qua (MODERATE, chưa nguy hiểm) cũng đủ kích hoạt tắt, rồi tắt VĨNH
     * VIỄN cho tới hết trận dù máy đã hạ nhiệt từ lâu. Khi Fixed Performance Mode bị tắt,
     * CPU/GPU quay về chế độ tự điều chỉnh xung nhịp mặc định của máy - trên nhiều máy,
     * kiểu tự điều chỉnh này dao động lên xuống liên tục dưới tải không đều (kể cả lúc di
     * chuyển bình thường, không chỉ combat), gây đúng cảm giác giật kéo dài không dứt. Hàm
     * này bật lại khi máy ĐÃ THẬT SỰ hạ nhiệt trong 1 khoảng đủ dài (do nơi gọi tự kiểm tra
     * trước, xem HudOverlayService) - không tự ý gọi nếu người dùng chọn "Tiết kiệm" hoặc
     * game đã được học là hay nóng máy (nơi gọi tự loại trừ 2 trường hợp này). */
    fun enableFixedPerformanceOnly(): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        return ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled true")
    }
}
