package com.boostvn.gamebooster

import android.content.Context
import android.content.ContentResolver
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
 *
 * NÂNG CẤP v1.8 - ƯU TIÊN "TỐI ƯU THẬT CHO MÁY YẾU", GIẢM BỚT HƯỚNG ĐO ĐẠC:
 * 3) Tắt đồng bộ tài khoản nền (ContentResolver.setMasterSyncAutomatically) - API
 *    CHÍNH THỨC, không cần root/Shizuku, quyền WRITE_SYNC_SETTINGS là quyền thường
 *    (tự cấp khi cài app, không cần người dùng bấm đồng ý). Đồng bộ tài khoản (danh bạ,
 *    email, ảnh...) là nguồn đánh thức CPU/mạng ngầm định kỳ suốt phiên chơi - trên máy
 *    yếu (ít lõi/lõi yếu) từng lần đánh thức này chiếm tỷ trọng CPU cao hơn hẳn so với
 *    máy mạnh, ảnh hưởng trực tiếp tới độ ổn định frame-time khi chơi lâu.
 * 4) Dùng DeviceProfileHelper (ActivityManager.isLowRamDevice() - API chính thức) để
 *    máy yếu được: dọn RAM mạnh tay hơn trước trận, ngưỡng an toàn nhiệt thấp hơn (tản
 *    nhiệt vật lý kém hơn), và đo đạc trong trận thưa/nhẹ hơn - dồn "ngân sách CPU" của
 *    booster cho hành động thật thay vì cho việc đo.
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
        // AI học trên máy: game này lịch sử có hay làm máy nóng không (dựa trên thermal_rate
        // đã học qua nhiều phiên trước - xem LearningProfileHelper). Nếu có, coi như "đang
        // nóng" ngay từ đầu trận dù nhiệt độ lúc này đang bình thường - né trước thay vì đợi
        // nóng thật rồi mới tắt Fixed Performance Mode giữa chừng (lúc đó nhiệt đã lỡ tích).
        val isThermalRiskyGame = LearningProfileHelper.getLearnedThermalRisk(context, gamePackage)
        val skipFixedPerf = isThermalSafe || isThermalRiskyGame
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

        return Result(actions, verifiedCount > 0, profile, fixedPerfEnabled)
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
}
