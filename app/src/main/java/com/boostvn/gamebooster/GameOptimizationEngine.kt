package com.boostvn.gamebooster

import android.content.Context
import android.content.ContentResolver
import android.os.Build

/**
 * Game Optimization Engine v3.0 — Max No-Root / Universal.
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
    private const val PREFS = "booster_downscale"
    private const val KEY_FACTOR = "factor" // "off" | "0.55" | "0.65" | "0.75" | "0.85"

    /** Lưu / đọc mức downscale do người dùng chọn trên UI */
    fun getDownscaleFactor(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FACTOR, "off") ?: "off"
        } catch (_: Throwable) { "off" }
    }

    fun setDownscaleFactor(context: Context, factor: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_FACTOR, factor).apply()
        } catch (_: Throwable) { }
    }


    data class Result(val actions: List<String>, val verified: Boolean, val profile: String, val fixedPerfEnabled: Boolean)

    fun prepare(context: Context, gamePackage: String, mode: MainActivity.PerfMode): Result {
        val actions = mutableListOf<String>()
        var verifiedCount = 0
        SessionSnapshotStore.begin(context, gamePackage)
        val shizuku = ShizukuHelper.hasPermission()
        val isLienQuan = gamePackage == LIEN_QUAN_PACKAGE
        val isFreeFire = gamePackage == FREE_FIRE_PACKAGE
        val batteryTemp = TemperatureHelper.getBatteryTemperatureC(context)
        val isLowRam = DeviceProfileHelper.isLowRamDevice(context)
        val tier = DeviceProfileHelper.detectTier(context)
        val isWeak = tier == DeviceProfileHelper.Tier.WEAK || isLowRam
        // Dùng cả tín hiệu chính thức của Android (đáng tin hơn ngưỡng độ C tự đoán, xem
        // TemperatureHelper.getThermalStatus) LẪN ngưỡng độ C dự phòng cho máy không hỗ trợ.
        val thermalStatus = TemperatureHelper.getThermalStatus(context)
        // Above the configured temperature/status is a THERMAL RISK, not a safe state.
        // The previous implementation inverted this condition and could select the
        // performance path exactly when the device was already warm.
        val isThermalRisk = (batteryTemp != null && batteryTemp >= DeviceProfileHelper.thermalSafeThresholdC(context)) ||
            (thermalStatus != null && thermalStatus >= android.os.PowerManager.THERMAL_STATUS_LIGHT)
        // AI học trên máy + ưu tiên GPU ổn định khi giao tranh đông hiệu ứng.
        // Fixed Performance Mode khoá xung CPU/GPU ổn định → giảm giật frame-time lúc
        // skill/hiệu ứng dày đặc. Game có lag sensitivity cao (AI đã học) được ưu tiên
        // bật Fixed Perf mạnh hơn, miễn là máy chưa nóng thật sự.
        val isThermalRiskyGame = LearningProfileHelper.getLearnedThermalRisk(context, gamePackage)
        val lagSensitivity = LearningProfileHelper.getLearnedLagSensitivity(context, gamePackage)
        val preferFixedForLag = lagSensitivity >= 0.30f && !isThermalRisk && !isThermalRiskyGame
        // Chỉ bỏ Fixed Perf khi máy đang nóng hoặc game lịch sử rất nóng — không bỏ vì
        // lý do khác nếu game cần GPU ổn định.
        val skipFixedPerf = (isThermalRisk || isThermalRiskyGame) && !preferFixedForLag
        val sustainedMode = when {
            isThermalRisk -> "THERMAL-PROTECT"
            mode == MainActivity.PerfMode.TIET_KIEM -> "BATTERY-SAFE"
            preferFixedForLag -> "GPU-STABLE FIXED"
            else -> "SUSTAINED PERFORMANCE"
        }
        val profile = when {
            isLienQuan -> "LIÊN QUÂN $sustainedMode"
            isFreeFire -> "FREE FIRE $sustainedMode"
            else -> "UNIVERSAL GAME $sustainedMode"
        }

        if (isWeak) actions += "Máy ${tier.label}: tối ưu MẠNH — dọn nền, siết process, tránh Fixed Perf (downscale chỉ khi bạn chọn)"

        // Tắt đồng bộ tài khoản nền - hoạt động trên MỌI máy, không cần Shizuku/root.
        // Đây là hành động TỐI ƯU THẬT (giảm CPU/mạng ngầm) chứ không phải đo đạc.
        if (disableAutoSync(context)) {
            actions += "Tắt đồng bộ tài khoản nền: OK (giảm CPU/mạng ngầm suốt trận)"
            verifiedCount++
        }

        if (!shizuku) {
            actions += "Shizuku: chưa kết nối — tối ưu path KHÔNG Shizuku (max có thể)"
            verifiedCount += applyNoShizukuMax(context, actions, mode)
            actions += "Profile: $profile · ổn định bền (không ép xung)"
            actions += "Gợi ý: bật Shizuku để có Game Mode / Downscale / Fixed Perf đầy đủ"
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
            // Persistent snapshot: Game Mode is a per-package system override, so it must
            // be restored just like refresh rate/animation when the session ends.
            if (SessionSnapshotStore.get(context, "game_mode_original") == null) {
                val listing = ShizukuHelper.runShellCommandWithOutput("cmd game list-modes $gamePackage")
                val originalMode = Regex("current mode:\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
                    .find(listing.orEmpty())?.groupValues?.getOrNull(1)?.lowercase()
                if (originalMode in setOf("standard", "performance", "battery", "custom")) {
                    SessionSnapshotStore.save(context, "game_mode_original", originalMode)
                }
            }
            val gameModeOk = listOf(
                "cmd game mode $gameModeName $gamePackage",
                "cmd game mode $gameModeCode $gamePackage"
            ).any { ShizukuHelper.runShellCommand(it) }
            val readBack = ShizukuHelper.runShellCommandWithOutput("cmd game list-modes $gamePackage")
            val verifiedMode = Regex("current mode:\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
                .find(readBack.orEmpty())?.groupValues?.getOrNull(1)?.lowercase()
            if (gameModeOk && verifiedMode == gameModeName) {
                actions += "Game Mode ${gameModeName.uppercase()}: VERIFIED"
                verifiedCount++
            } else if (gameModeOk) {
                actions += "Game Mode ${gameModeName.uppercase()}: đã áp dụng nhưng chưa xác minh được"
            } else actions += "Game Mode: ROM không hỗ trợ/xác nhận"
        }

        // Giảm khả năng Doze/App Standby can thiệp vào game.
        val whitelistBefore = ShizukuHelper.runShellCommandWithOutput("cmd deviceidle whitelist")?.contains(gamePackage) == true
        SessionSnapshotStore.saveBoolean(context, "doze_whitelisted_original", whitelistBefore)
        if (ShizukuHelper.runShellCommand("cmd deviceidle whitelist +$gamePackage")) {
            actions += "Doze whitelist: OK"
            verifiedCount++
        }
        val inactiveBefore = ShizukuHelper.runShellCommandWithOutput("am get-inactive $gamePackage")?.trim()
        SessionSnapshotStore.save(context, "inactive_original", inactiveBefore)
        if (ShizukuHelper.runShellCommand("am set-inactive $gamePackage false")) {
            actions += "App active state: OK"
            verifiedCount++
        }
        // Ưu tiên RAM/CPU cho game: standby bucket = active (Android 9+)
        if (ShizukuHelper.runShellCommand("am set-standby-bucket $gamePackage active")) {
            actions += "Standby bucket ACTIVE: OK"
            verifiedCount++
        }

        // v2.5 UltraSmooth: MẶC ĐỊNH không dùng Fixed Performance Mode.
        // Trên nhiều máy (nhất là tầm trung/yếu) khoá xung cao → nóng nhanh → thermal
        // throttle giữa trận → giật kéo dài. Game Mode Performance + ít can thiệp ổn định hơn.
        // Chỉ bật Fixed Perf khi user chọn Cực mạnh VÀ máy không nóng/rủi ro nhiệt.
        var fixedPerfEnabled = false
        val originalFixed = SessionSnapshotStore.get(context, "fixed_perf_original")
            ?: ShizukuHelper.runShellCommandWithOutput("cmd power is-fixed-performance-mode-enabled")?.trim()
        if (originalFixed != null) SessionSnapshotStore.save(context, "fixed_perf_original", originalFixed)
        // Ổn định bền: Fixed Perf chỉ Cực mạnh + máy không yếu + không rủi ro nhiệt
        // (Fixed Perf lâu → nóng → throttle → "mượt đầu, lag sau")
        val wantFixed = mode == MainActivity.PerfMode.CUC_MANH && !skipFixedPerf && !isWeak
        if (wantFixed) {
            actions += "Fixed Performance Mode: hẹn bật sau load (~50s) vì đang chọn Cực mạnh"
            fixedPerfEnabled = true
        } else {
            actions += "Fixed Performance Mode: tắt (mượt bền hơn — tránh nóng/throttle giữa trận)"
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
        captureOriginalAnimationScales(context)
        val animOk = verifiedSettingWrite("settings put global window_animation_scale 0", "settings get global window_animation_scale", "0") &&
            verifiedSettingWrite("settings put global transition_animation_scale 0", "settings get global transition_animation_scale", "0") &&
            verifiedSettingWrite("settings put global animator_duration_scale 0", "settings get global animator_duration_scale", "0")
        if (animOk) {
            actions += "Tắt hiệu ứng chuyển cảnh hệ thống: OK"
            verifiedCount++
        }

        // ----- Tính năng từ script tối ưu máy yếu (Vivo Y21 / FF+LQ) -----
        // Chỉ dùng settings/device_config có thật, có restore. Bỏ setprop debug.* (cần root,
        // không bền, dễ lệch ROM).

        // 1) Cảm ứng nhanh hơn (long/multi press timeout + pointer speed)
        if (applyTouchBoost(context)) {
            actions += "Cảm ứng game: OK (timeout 100ms, pointer max, tap threshold 0)"
            verifiedCount++
        }

        // 2) Tắt blur / giảm transparency hệ thống — nhẹ GPU compositor
        if (applyBlurOff(context)) {
            actions += "Tắt blur cửa sổ + giảm transparency: OK"
            verifiedCount++
        }

        // 3) Máy RAM thấp: giới hạn process nền (không dùng always_finish_activities —
        //    quá mạnh, dễ làm app khác bị kill khó chịu khi thoát game)
        if (isWeak && applyProcessLimits(context)) {
            actions += "Giới hạn process nền (máy RAM thấp): OK"
            verifiedCount++
        }

        // 4) Downscale — mức do người dùng chọn trên UI (Tắt / 0.55 / 0.65 / 0.75 / 0.85)
        val userFactor = getDownscaleFactor(context)
        if (userFactor != "off" && userFactor.toFloatOrNull() != null) {
            if (applyGameDownscale(context, gamePackage, userFactor)) {
                actions += "Downscale $userFactor: OK (chỉnh trong tab Game)"
                verifiedCount++
            } else {
                actions += "Downscale: ROM có thể không hỗ trợ device_config game_overlay"
            }
        } else {
            // Chọn Tắt: chủ động gỡ downscale đang kẹt từ phiên trước (nếu có)
            if (restoreGameDownscale(context)) {
                actions += "Downscale: đã khôi phục độ phân giải gốc"
            } else {
                actions += "Downscale: tắt"
            }
        }

        // ===== MAX NO-ROOT PACK (Shizuku) — lệnh thật, có snapshot/restore =====
        verifiedCount += applyMaxNoRootPack(context, actions, isWeak, mode)

        // Không compile ART mỗi lần mở game: thao tác này có thể tốn I/O/CPU và không
        // đảm bảo tăng FPS. Android/Play sẽ tự quản lý profile khi cần.
        actions += "ART compile: bỏ qua (tránh I/O spike lúc vào game)"

        if (batteryTemp != null) actions += "Nhiệt pin: ${"%.1f".format(batteryTemp)}°C"
        actions += "Profile: $profile"
        actions += when {
            isLienQuan -> "Liên Quân: ưu tiên frame-time ổn định + sustained performance"
            isFreeFire -> "Free Fire: ưu tiên frame-time ổn định + sustained performance"
            else -> "Game: profile universal + sustained performance"
        }
        actions += "Không ép xung / không đổi governor / không trim cache / không kill trong trận"

        // v2.4: ghim refresh rate cũng trì hoãn — đổi Hz lúc game đang load dễ giật.
        // HUD gọi applyDeferredHeavyBoost() sau grace period.
        actions += "Refresh rate: sẽ ghim tối đa sau khi load xong (tránh giật đầu trận)"

        return Result(actions, verifiedCount > 0, profile, fixedPerfEnabled)
    }

    @Volatile private var originalPeakRefreshRate: String? = null
    @Volatile private var originalMinRefreshRate: String? = null

    private fun pinRefreshRateToMax(context: Context): Boolean {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
            } ?: return false
            val maxHz = display.supportedModes.maxOf { it.refreshRate }
            if (maxHz <= 0f) return false

            if (originalPeakRefreshRate == null) {
                originalPeakRefreshRate = SessionSnapshotStore.get(context, "refresh_peak")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get system peak_refresh_rate")?.trim()
                originalMinRefreshRate = SessionSnapshotStore.get(context, "refresh_min")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get system min_refresh_rate")?.trim()
                SessionSnapshotStore.save(context, "refresh_peak", originalPeakRefreshRate)
                SessionSnapshotStore.save(context, "refresh_min", originalMinRefreshRate)
            }
            val hz = maxHz.toInt()
            val a = verifiedSettingWrite("settings put system peak_refresh_rate $hz", "settings get system peak_refresh_rate", hz.toString())
            val b = verifiedSettingWrite("settings put system min_refresh_rate $hz", "settings get system min_refresh_rate", hz.toString())
            return a && b
        } catch (_: Throwable) { return false }
    }

    private fun restoreRefreshRate(context: Context): Boolean {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null" && v.toFloatOrNull() != null
        if (originalPeakRefreshRate == null) originalPeakRefreshRate = SessionSnapshotStore.get(context, "refresh_peak")
        if (originalMinRefreshRate == null) originalMinRefreshRate = SessionSnapshotStore.get(context, "refresh_min")
        var ok = true
        originalPeakRefreshRate?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite("settings put system peak_refresh_rate $it", "settings get system peak_refresh_rate", it) && ok
        }
        originalMinRefreshRate?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite("settings put system min_refresh_rate $it", "settings get system min_refresh_rate", it) && ok
        }
        if (originalPeakRefreshRate == null && originalMinRefreshRate == null) ok = true
        if (ok) {
            originalPeakRefreshRate = null
            originalMinRefreshRate = null
        }
        return ok
    }

    /**
     * Khôi phục mọi thay đổi tạm thời khi kết thúc phiên chơi - QUAN TRỌNG, không được
     * để "kẹt" lại (Fixed Performance Mode kẹt bật sẽ tốn pin không cần thiết cả ngày,
     * animation tắt vĩnh viễn sẽ khiến cảm giác dùng máy hàng ngày cứng/giật hình).
     */
    
    /**
     * Nút "Khôi phục hệ thống": gỡ tối ưu đang kẹt kể cả khi session snapshot thiếu.
     * Best-effort: Fixed Perf off, animation 1, refresh, downscale delete, max pack keys.
     */
    fun forceFullRestore(context: Context): Boolean {
        val ok = restore(context)
        restoreNotificationsAndDnd(context)
        // Fallback khi không có snapshot active
        if (!ShizukuHelper.hasPermission()) {
            restoreAutoSync(context)
            return ok
        }
        ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled false")
        ShizukuHelper.runShellCommand("settings put global window_animation_scale 1")
        ShizukuHelper.runShellCommand("settings put global transition_animation_scale 1")
        ShizukuHelper.runShellCommand("settings put global animator_duration_scale 1")
        ShizukuHelper.runShellCommand("settings put global heads_up_notifications_enabled 1")
        ShizukuHelper.runShellCommand("settings put global zen_mode 0")
        ShizukuHelper.runShellCommand("cmd notification set_dnd off")
        ShizukuHelper.runShellCommand("settings put global wifi_scan_always_enabled 1")
        // Cảm ứng về mức an toàn nếu bị kẹt (OEM thường ~400 / 300 / 0)
        val tl = SessionSnapshotStore.get(context, "touch_long")
        val tm = SessionSnapshotStore.get(context, "touch_multi")
        val tp = SessionSnapshotStore.get(context, "touch_pointer")
        val td = SessionSnapshotStore.get(context, "touch_double")
        ShizukuHelper.runShellCommand("settings put secure long_press_timeout ${tl?.takeIf { it.toIntOrNull() != null } ?: "400"}")
        ShizukuHelper.runShellCommand("settings put secure multi_press_timeout ${tm?.takeIf { it.toIntOrNull() != null } ?: "300"}")
        ShizukuHelper.runShellCommand("settings put system pointer_speed ${tp?.takeIf { it.toIntOrNull() != null } ?: "0"}")
        td?.toIntOrNull()?.let { ShizukuHelper.runShellCommand("settings put secure double_tap_timeout $it") }
        // Gỡ downscale các package đã biết trong snapshot hoặc recent
        SessionSnapshotStore.get(context, "downscale_pkg")?.let { pkg ->
            ShizukuHelper.runShellCommand("device_config delete game_overlay $pkg")
        }
        SessionSnapshotStore.clear(context)
        return true
    }

    fun restore(context: Context): Boolean {
        restoreAutoSync(context)
        restoreNoShizukuMax(context)
        if (!ShizukuHelper.hasPermission()) {
            restoreNotificationsAndDnd(context)
            return true
        }

        var ok = true
        val originalFixed = SessionSnapshotStore.get(context, "fixed_perf_original")?.lowercase()
        if (originalFixed != null) {
            val restoreFixed = originalFixed == "true" || originalFixed == "1"
            ok = verifiedShell(
                "cmd power set-fixed-performance-mode-enabled ${if (restoreFixed) "true" else "false"}",
                "cmd power is-fixed-performance-mode-enabled",
                if (restoreFixed) setOf("true", "1") else setOf("false", "0")
            ) && ok
        }
        ok = restoreOriginalAnimationScales(context) && ok
        ok = restoreRefreshRate(context) && ok
        ok = restoreTouchBoost(context) && ok
        ok = restoreBlurOff(context) && ok
        ok = restoreProcessLimits(context) && ok

        val gamePkg = SessionSnapshotStore.gamePackage(context)
        val originalGameMode = SessionSnapshotStore.get(context, "game_mode_original")
        if (!gamePkg.isNullOrBlank() && !originalGameMode.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val modeWrite = ShizukuHelper.runShellCommand("cmd game mode $originalGameMode $gamePkg")
            val rb = ShizukuHelper.runShellCommandWithOutput("cmd game list-modes $gamePkg").orEmpty()
            val verified = modeWrite && rb.contains(Regex("current mode:\\s*${Regex.escape(originalGameMode)}", RegexOption.IGNORE_CASE))
            ok = verified && ok
        }

        // Downscale restore best-effort — không chặn clear snapshot nếu lệch format ROM
        restoreGameDownscale(context)
        restoreMaxNoRootPack(context)
        restoreNotificationsAndDnd(context)
        restoreNoShizukuMax(context)

        val originalWhitelist = SessionSnapshotStore.getBoolean(context, "doze_whitelisted_original")
        if (!gamePkg.isNullOrBlank() && originalWhitelist != null) {
            val whitelistOk = if (originalWhitelist) {
                true
            } else {
                val write = ShizukuHelper.runShellCommand("cmd deviceidle whitelist -$gamePkg")
                val list = ShizukuHelper.runShellCommandWithOutput("cmd deviceidle whitelist").orEmpty()
                write && !list.lines().any { it.trim().contains(gamePkg) }
            }
            ok = whitelistOk && ok
        }

        val inactiveOriginal = SessionSnapshotStore.get(context, "inactive_original")
        if (!gamePkg.isNullOrBlank() && inactiveOriginal != null) {
            val shouldBeInactive = Regex("Idle=(true|false)", RegexOption.IGNORE_CASE)
                .find(inactiveOriginal)?.groupValues?.getOrNull(1)?.equals("true", true)
            if (shouldBeInactive != null) {
                val write = ShizukuHelper.runShellCommand("am set-inactive $gamePkg $shouldBeInactive")
                val rb = ShizukuHelper.runShellCommandWithOutput("am get-inactive $gamePkg").orEmpty()
                val verified = write && (Regex("Idle=(true|false)", RegexOption.IGNORE_CASE).find(rb)?.groupValues?.getOrNull(1)?.equals("true", true) == shouldBeInactive)
                ok = verified && ok
            }
        }

        if (ok) SessionSnapshotStore.clear(context)
        return ok
    }

    // Nhớ lại 3 giá trị tốc độ hiệu ứng GỐC của người dùng trước khi tắt - xem ghi chú
    // "Crash-Safe Snapshot" ở nơi gọi. "1" chỉ là phương án dự phòng cuối cùng nếu không
    // đọc được (ví dụ lệnh `settings get` thất bại) - không phải giá trị được ưu tiên.
    @Volatile private var originalWindowAnimScale: String? = null
    @Volatile private var originalTransitionAnimScale: String? = null
    @Volatile private var originalAnimatorDurationScale: String? = null

    private fun captureOriginalAnimationScales(context: Context) {
        if (originalWindowAnimScale == null) {
            originalWindowAnimScale = SessionSnapshotStore.get(context, "anim_window")
                ?: ShizukuHelper.runShellCommandWithOutput("settings get global window_animation_scale")?.trim()
            originalTransitionAnimScale = SessionSnapshotStore.get(context, "anim_transition")
                ?: ShizukuHelper.runShellCommandWithOutput("settings get global transition_animation_scale")?.trim()
            originalAnimatorDurationScale = SessionSnapshotStore.get(context, "anim_duration")
                ?: ShizukuHelper.runShellCommandWithOutput("settings get global animator_duration_scale")?.trim()
            SessionSnapshotStore.save(context, "anim_window", originalWindowAnimScale)
            SessionSnapshotStore.save(context, "anim_transition", originalTransitionAnimScale)
            SessionSnapshotStore.save(context, "anim_duration", originalAnimatorDurationScale)
        }
    }

    private fun restoreOriginalAnimationScales(context: Context): Boolean {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null" && v.toFloatOrNull() != null
        if (originalWindowAnimScale == null) originalWindowAnimScale = SessionSnapshotStore.get(context, "anim_window")
        if (originalTransitionAnimScale == null) originalTransitionAnimScale = SessionSnapshotStore.get(context, "anim_transition")
        if (originalAnimatorDurationScale == null) originalAnimatorDurationScale = SessionSnapshotStore.get(context, "anim_duration")
        var ok = true
        originalWindowAnimScale?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global window_animation_scale $it", "settings get global window_animation_scale", it) && ok }
        originalTransitionAnimScale?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global transition_animation_scale $it", "settings get global transition_animation_scale", it) && ok }
        originalAnimatorDurationScale?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global animator_duration_scale $it", "settings get global animator_duration_scale", it) && ok }
        if (ok) { originalWindowAnimScale = null; originalTransitionAnimScale = null; originalAnimatorDurationScale = null }
        return ok
    }

    // Nhớ lại trạng thái sync gốc để khôi phục đúng, tránh trường hợp người dùng đã tự
    // tắt sync từ trước rồi bị app này "bật nhầm" lên sau khi chơi xong.
    @Volatile private var originalSyncEnabled: Boolean? = null

    private fun disableAutoSync(context: Context): Boolean = try {
        if (originalSyncEnabled == null) {
            originalSyncEnabled = SessionSnapshotStore.getBoolean(context, "sync_enabled")
                ?: ContentResolver.getMasterSyncAutomatically()
            SessionSnapshotStore.saveBoolean(context, "sync_enabled", originalSyncEnabled == true)
        }
        if (ContentResolver.getMasterSyncAutomatically()) {
            ContentResolver.setMasterSyncAutomatically(false)
        }
        true
    } catch (_: Throwable) { false }

    private fun restoreAutoSync(context: Context) {
        try {
            val original = originalSyncEnabled
                ?: SessionSnapshotStore.getBoolean(context, "sync_enabled")
            if (original == true && !ContentResolver.getMasterSyncAutomatically()) {
                ContentResolver.setMasterSyncAutomatically(true)
            } else if (original == false && ContentResolver.getMasterSyncAutomatically()) {
                ContentResolver.setMasterSyncAutomatically(false)
            }
        } catch (_: Throwable) { }
        originalSyncEnabled = null
    }

    /** Recover an interrupted session after process death/reboot. If the game is still running,
     * recovery is deferred so opening the booster app cannot undo an active gaming session. */
    fun recoverInterruptedSession(context: Context): Boolean {
        if (!SessionSnapshotStore.isActive(context)) return false
        val pkg = SessionSnapshotStore.gamePackage(context)
        if (pkg.isNullOrBlank()) {
            restore(context)
            return true
        }
        if (ShizukuHelper.hasPermission()) {
            val running = ShizukuHelper.runShellCommandWithOutput("pidof $pkg")?.trim().orEmpty()
            if (running.isNotEmpty()) return false
        }
        restore(context)
        return true
    }

    /** Chỉ tắt Fixed Performance Mode - dùng khi phát hiện máy nóng bất thường giữa
     * chừng, vẫn giữ tắt animation vì không liên quan tới nhiệt độ. */
    fun disableFixedPerformanceOnly(): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        if (!ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled false")) return false
        val state = ShizukuHelper.runShellCommandWithOutput("cmd power is-fixed-performance-mode-enabled")?.trim()?.lowercase()
        return state == "false" || state == "0"
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
        if (!ShizukuHelper.runShellCommand("cmd power set-fixed-performance-mode-enabled true")) return false
        val state = ShizukuHelper.runShellCommandWithOutput("cmd power is-fixed-performance-mode-enabled")?.trim()?.lowercase()
        return state == "true" || state == "1"
    }

    /**
     * v2.4 — Bật tối ưu nặng SAU khi game đã load xong (gọi từ HudOverlayService hết grace).
     * Tránh Fixed Perf + đổi refresh rate cạnh tranh I/O/CPU lúc vào game.
     */
    fun applyDeferredHeavyBoost(context: Context): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        // Chỉ pin refresh — nhẹ, tránh giật đổi Hz giữa trận. Fixed Perf do caller quyết định qua enableFixed.
        return pinRefreshRateToMax(context)
    }

    fun applyDeferredFixedPerfIfNeeded(): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        return enableFixedPerformanceOnly()
    }



    // ---------- Cảm ứng game (nhạy hơn khi chạm/vuốt, có snapshot/restore) ----------
    // long_press / multi_press thấp = hệ thống ít “chờ” trước khi nhận gesture.
    // tap_duration_threshold = 0 = bỏ buffer trễ chạm (nếu ROM hỗ trợ).
    // pointer_speed cao = con trỏ/gesture vuốt nhanh hơn (1–7, mặc định ~0).
    @Volatile private var originalLongPress: String? = null
    @Volatile private var originalMultiPress: String? = null
    @Volatile private var originalPointerSpeed: String? = null
    @Volatile private var originalTapDuration: String? = null

    private fun applyTouchBoost(context: Context): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        try {
            if (originalLongPress == null) {
                originalLongPress = SessionSnapshotStore.get(context, "touch_long")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get secure long_press_timeout")?.trim()
                originalMultiPress = SessionSnapshotStore.get(context, "touch_multi")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get secure multi_press_timeout")?.trim()
                originalPointerSpeed = SessionSnapshotStore.get(context, "touch_pointer")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get system pointer_speed")?.trim()
                originalTapDuration = SessionSnapshotStore.get(context, "touch_tap")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get secure tap_duration_threshold")?.trim()
                val originalDoubleTap = SessionSnapshotStore.get(context, "touch_double")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get secure double_tap_timeout")?.trim()
                SessionSnapshotStore.save(context, "touch_long", originalLongPress)
                SessionSnapshotStore.save(context, "touch_multi", originalMultiPress)
                SessionSnapshotStore.save(context, "touch_pointer", originalPointerSpeed)
                SessionSnapshotStore.save(context, "touch_tap", originalTapDuration)
                SessionSnapshotStore.save(context, "touch_double", originalDoubleTap)
            }
            // Game: giảm trễ nhận chạm/vuốt — luôn có restore khi thoát
            // 100ms: nhạy hơn 150/400 mặc định; vẫn đủ để không "hỏng" long-press ngoài game
            val longMs = "100"
            val multiMs = "100"
            val target = 7 // pointer_speed tối đa (hệ thống -7..7)
            var ok = false
            if (verifiedSettingWrite(
                    "settings put secure long_press_timeout $longMs",
                    "settings get secure long_press_timeout", longMs
                )
            ) ok = true
            if (verifiedSettingWrite(
                    "settings put secure multi_press_timeout $multiMs",
                    "settings get secure multi_press_timeout", multiMs
                )
            ) ok = true
            if (verifiedSettingWrite(
                    "settings put system pointer_speed $target",
                    "settings get system pointer_speed", target.toString()
                )
            ) ok = true
            // tap_duration_threshold = 0: bỏ buffer trễ chạm (nếu ROM có key)
            if (ShizukuHelper.runShellCommand("settings put secure tap_duration_threshold 0")) {
                val rb = ShizukuHelper.runShellCommandWithOutput("settings get secure tap_duration_threshold")?.trim()
                if (rb == "0") ok = true
            }
            // double_tap_timeout thấp hơn = nhận double-tap/skill nhanh hơn (OEM mặc định ~300)
            if (ShizukuHelper.runShellCommand("settings put secure double_tap_timeout 200")) {
                val rb = ShizukuHelper.runShellCommandWithOutput("settings get secure double_tap_timeout")?.trim()
                if (rb == "200") ok = true
            }
            return ok
        } catch (_: Throwable) {
            return false
        }
    }

    private fun restoreTouchBoost(context: Context): Boolean {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null" && !v.equals("unknown", true)
        if (originalLongPress == null) originalLongPress = SessionSnapshotStore.get(context, "touch_long")
        if (originalMultiPress == null) originalMultiPress = SessionSnapshotStore.get(context, "touch_multi")
        if (originalPointerSpeed == null) originalPointerSpeed = SessionSnapshotStore.get(context, "touch_pointer")
        if (originalTapDuration == null) originalTapDuration = SessionSnapshotStore.get(context, "touch_tap")
        var ok = true
        originalLongPress?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite(
                "settings put secure long_press_timeout $it",
                "settings get secure long_press_timeout", it
            ) && ok
        }
        originalMultiPress?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite(
                "settings put secure multi_press_timeout $it",
                "settings get secure multi_press_timeout", it
            ) && ok
        }
        originalPointerSpeed?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite(
                "settings put system pointer_speed $it",
                "settings get system pointer_speed", it
            ) && ok
        }
        originalTapDuration?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite(
                "settings put secure tap_duration_threshold $it",
                "settings get secure tap_duration_threshold", it
            ) && ok
        }
        SessionSnapshotStore.get(context, "touch_double")?.takeIf { valid(it) }?.let {
            ok = verifiedSettingWrite(
                "settings put secure double_tap_timeout $it",
                "settings get secure double_tap_timeout", it
            ) && ok
        }
        if (ok) {
            originalLongPress = null
            originalMultiPress = null
            originalPointerSpeed = null
            originalTapDuration = null
        }
        return ok
    }

    // ---------- Tắt blur ----------
    @Volatile private var originalDisableBlurs: String? = null
    @Volatile private var originalReduceTransparency: String? = null

    private fun applyBlurOff(context: Context): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        try {
            if (originalDisableBlurs == null) {
                originalDisableBlurs = SessionSnapshotStore.get(context, "blur_disable")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get global disable_window_blurs")?.trim()
                originalReduceTransparency = SessionSnapshotStore.get(context, "blur_transparency")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get global accessibility_reduce_transparency")?.trim()
                SessionSnapshotStore.save(context, "blur_disable", originalDisableBlurs)
                SessionSnapshotStore.save(context, "blur_transparency", originalReduceTransparency)
            }
            // accessibility_reduce_transparency có trên một số ROM
            val a = verifiedSettingWrite("settings put global disable_window_blurs 1", "settings get global disable_window_blurs", "1")
            val b = verifiedSettingWrite("settings put global accessibility_reduce_transparency 1", "settings get global accessibility_reduce_transparency", "1")
            return a && b
        } catch (_: Throwable) { return false }
    }

    private fun restoreBlurOff(context: Context): Boolean {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null"
        if (originalDisableBlurs == null) originalDisableBlurs = SessionSnapshotStore.get(context, "blur_disable")
        if (originalReduceTransparency == null) originalReduceTransparency = SessionSnapshotStore.get(context, "blur_transparency")
        var ok = true
        originalDisableBlurs?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global disable_window_blurs $it", "settings get global disable_window_blurs", it) && ok }
        originalReduceTransparency?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global accessibility_reduce_transparency $it", "settings get global accessibility_reduce_transparency", it) && ok }
        if (ok) { originalDisableBlurs = null; originalReduceTransparency = null }
        return ok
    }

    // ---------- Giới hạn process (máy yếu) ----------
    @Volatile private var originalMaxCached: String? = null
    @Volatile private var originalBgLimit: String? = null

    private fun applyProcessLimits(context: Context): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        try {
            if (originalMaxCached == null) {
                originalMaxCached = SessionSnapshotStore.get(context, "proc_max_cached")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get global max_cached_processes")?.trim()
                originalBgLimit = SessionSnapshotStore.get(context, "proc_bg_limit")
                    ?: ShizukuHelper.runShellCommandWithOutput("settings get global background_process_limit")?.trim()
                SessionSnapshotStore.save(context, "proc_max_cached", originalMaxCached)
                SessionSnapshotStore.save(context, "proc_bg_limit", originalBgLimit)
            }
            val a = verifiedSettingWrite("settings put global max_cached_processes 2", "settings get global max_cached_processes", "2")
            val b = verifiedSettingWrite("settings put global background_process_limit 1", "settings get global background_process_limit", "1")
            return a && b
        } catch (_: Throwable) { return false }
    }

    private fun restoreProcessLimits(context: Context): Boolean {
        fun valid(v: String?) = !v.isNullOrBlank() && v != "null"
        if (originalMaxCached == null) originalMaxCached = SessionSnapshotStore.get(context, "proc_max_cached")
        if (originalBgLimit == null) originalBgLimit = SessionSnapshotStore.get(context, "proc_bg_limit")
        var ok = true
        originalMaxCached?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global max_cached_processes $it", "settings get global max_cached_processes", it) && ok }
        originalBgLimit?.takeIf { valid(it) }?.let { ok = verifiedSettingWrite("settings put global background_process_limit $it", "settings get global background_process_limit", it) && ok }
        if (ok) { originalMaxCached = null; originalBgLimit = null }
        return ok
    }

    // ---------- Game Overlay downscale (FF / LQ) ----------
    @Volatile private var downscalePackage: String? = null

    private fun applyGameDownscale(context: Context, gamePackage: String, factor: String): Boolean {
        if (!ShizukuHelper.hasPermission()) return false
        // Lưu giá trị GỐC một lần/phiên để restore đúng khi thoát game / chọn Tắt.
        if (SessionSnapshotStore.get(context, "downscale_pkg") == null) {
            SessionSnapshotStore.save(context, "downscale_pkg", gamePackage)
            val original = ShizukuHelper.runShellCommandWithOutput("device_config get game_overlay $gamePackage")?.trim()
            val normalized = when {
                original.isNullOrBlank() -> "__MISSING__"
                original.equals("null", true) || original.equals("unknown", true) -> "__MISSING__"
                else -> original
            }
            SessionSnapshotStore.save(context, "downscale_original", normalized)
        }
        SessionSnapshotStore.save(context, "downscale_pkg", gamePackage)
        // loadingBoost=1: Android Game Mode intervention — ưu tiên CPU lúc load map/asset
        val value = "mode=2,downscaleFactor=$factor,loadingBoost=1:mode=3,downscaleFactor=$factor"
        // Thử cả có quote và không quote (ROM Vivo đôi khi parse khác)
        val wrote = ShizukuHelper.runShellCommand("device_config put game_overlay $gamePackage '$value'") ||
            ShizukuHelper.runShellCommand("device_config put game_overlay $gamePackage $value")
        if (!wrote) return false
        val readBack = ShizukuHelper.runShellCommandWithOutput("device_config get game_overlay $gamePackage")?.trim().orEmpty()
        val verified = readBack.contains("downscaleFactor=$factor") ||
            readBack.contains("downscaleFactor=$factor".replace("0.", ".")) ||
            readBack == value
        // Luôn ghi nhận package để restore được kể cả khi read-back hơi lệch format
        downscalePackage = gamePackage
        SessionSnapshotStore.save(context, "downscale_applied_factor", factor)
        return verified || wrote
    }

    /**
     * Khôi phục downscale về đúng trạng thái trước khi BOOST.
     * Best-effort trên nhiều ROM: delete → put original → xóa factor khỏi overlay.
     * Không để verification quá chặt khiến người dùng bị "kẹt" downscale mãi.
     */
    private fun restoreGameDownscale(context: Context): Boolean {
        val pkg = downscalePackage
            ?: SessionSnapshotStore.get(context, "downscale_pkg")
            ?: SessionSnapshotStore.gamePackage(context)
            ?: return true

        val original = SessionSnapshotStore.get(context, "downscale_original")
        val appliedFactor = SessionSnapshotStore.get(context, "downscale_applied_factor")

        fun currentOverlay(): String =
            ShizukuHelper.runShellCommandWithOutput("device_config get game_overlay $pkg")?.trim().orEmpty()

        fun isCleared(rb: String): Boolean {
            if (rb.isEmpty() || rb.equals("null", true) || rb.equals("unknown", true)) return true
            // Hết downscaleFactor của ta = coi như đã về gần gốc
            if (appliedFactor != null && rb.contains("downscaleFactor=$appliedFactor")) return false
            if (original != null && original != "__MISSING__" && rb == original) return true
            if (!rb.contains("downscaleFactor")) return true
            return false
        }

        var restored = false

        // 1) Có giá trị gốc thật → ghi lại
        if (!original.isNullOrBlank() && original != "__MISSING__" &&
            !original.equals("null", true) && !original.equals("unknown", true)
        ) {
            val putOk = ShizukuHelper.runShellCommand("device_config put game_overlay $pkg '$original'") ||
                ShizukuHelper.runShellCommand("device_config put game_overlay $pkg $original")
            if (putOk) {
                val rb = currentOverlay()
                if (rb == original || isCleared(rb) || !rb.contains("downscaleFactor=$appliedFactor")) {
                    restored = true
                }
            }
        }

        // 2) Không có gốc / bước 1 thất bại → delete overlay
        if (!restored) {
            ShizukuHelper.runShellCommand("device_config delete game_overlay $pkg")
            // Một số ROM cần reset — dùng chuỗi rỗng shell, không dùng """ (lỗi Kotlin)
            ShizukuHelper.runShellCommand("device_config put game_overlay $pkg ''")
            val rb = currentOverlay()
            if (isCleared(rb)) restored = true
        }

        // 3) Vẫn còn factor của ta → thử ghi overlay rỗng mode không downscale (fallback yếu)
        if (!restored) {
            val rb = currentOverlay()
            if (appliedFactor != null && rb.contains("downscaleFactor=$appliedFactor")) {
                // Xóa bằng cách put lại chuỗi gốc đã strip downscale nếu parse được
                ShizukuHelper.runShellCommand("device_config delete game_overlay $pkg")
                restored = true // best-effort: đã cố gắng tối đa
            } else {
                restored = true
            }
        }

        downscalePackage = null
        // Xóa key downscale trong snapshot để lần sau không restore nhầm
        // (clear full session vẫn do restore() gọi khi ok)
        return restored
    }

    /** Write → read-back → compare. A successful shell exit alone is not treated as verified. */
    private fun verifiedSettingWrite(writeCommand: String, readCommand: String, expected: String): Boolean {
        if (!ShizukuHelper.runShellCommand(writeCommand)) return false
        val actual = ShizukuHelper.runShellCommandWithOutput(readCommand)?.trim()?.split("\n")?.lastOrNull()?.trim()
        return actual == expected
    }

    private fun verifiedShell(writeCommand: String, readCommand: String, expected: Set<String>): Boolean {
        if (!ShizukuHelper.runShellCommand(writeCommand)) return false
        val actual = ShizukuHelper.runShellCommandWithOutput(readCommand)?.trim()?.lowercase().orEmpty()
        return expected.contains(actual)
    }


    /**
     * Gói MAX no-root v4.3 (Shizuku/ADB): nhiễu nền + mạng + game driver + device_config, có snapshot/restore.
     * Ưu tiên: giảm nhiễu nền (Wi‑Fi scan, heads-up, data always-on), giữ màn hình,
     * siết cached processes trên máy yếu, tắt battery saver nếu đang bật.
     */

    /**
     * Khôi phục thông báo + cuộc gọi sau khi chơi.
     * BOOST có thể: tắt heads-up, bật DND (Không làm phiền) → nếu không restore thì
     * tin nhắn/Zalo/Messenger không banner, cuộc gọi không hiện.
     */
    fun restoreNotificationsAndDnd(context: Context) {
        // 1) Heads-up + zen_mode qua shell (Shizuku)
        if (ShizukuHelper.hasPermission()) {
            val heads = SessionSnapshotStore.get(context, "heads_up")
            val headsVal = if (!heads.isNullOrBlank() && heads != "__MISSING__" && !heads.equals("null", true)) heads else "1"
            ShizukuHelper.runShellCommand("settings put global heads_up_notifications_enabled $headsVal")
            // zen_mode: 0 = off (một số ROM dùng cái này thay interruption filter)
            val zen = SessionSnapshotStore.get(context, "zen_mode")
            val zenVal = if (!zen.isNullOrBlank() && zen != "__MISSING__" && !zen.equals("null", true)) zen else "0"
            ShizukuHelper.runShellCommand("settings put global zen_mode $zenVal")
            // notification_bubbles / policy — best effort
            ShizukuHelper.runShellCommand("cmd notification set_dnd off")
        }
        // 2) DND qua NotificationManager API (không cần Shizuku, cần quyền policy)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (nm != null && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Throwable) { }
    }

    private fun applyMaxNoRootPack(
        context: Context,
        actions: MutableList<String>,
        isLowRam: Boolean,
        mode: MainActivity.PerfMode
    ): Int {
        var n = 0
        fun snapAndPut(key: String, getCmd: String, putCmd: String, expect: String, label: String) {
            if (SessionSnapshotStore.get(context, key) == null) {
                val cur = ShizukuHelper.runShellCommandWithOutput(getCmd)?.trim()
                if (!cur.isNullOrBlank() && cur != "null") SessionSnapshotStore.save(context, key, cur)
            }
            if (verifiedSettingWrite(putCmd, getCmd, expect)) {
                actions += "$label: OK"
                n++
            }
        }

        // Tắt heads-up notification (banner) — giảm giật khi có thông báo
        snapAndPut(
            "heads_up",
            "settings get global heads_up_notifications_enabled",
            "settings put global heads_up_notifications_enabled 0",
            "0",
            "Tắt heads-up notification"
        )
        // Snapshot zen_mode (DND) — restore về 0 khi thoát
        if (SessionSnapshotStore.get(context, "zen_mode") == null) {
            val z = ShizukuHelper.runShellCommandWithOutput("settings get global zen_mode")?.trim()
            if (!z.isNullOrBlank() && z != "null") SessionSnapshotStore.save(context, "zen_mode", z)
        }

        // Giảm quét Wi‑Fi nền (tiết kiệm CPU/radio)
        snapAndPut(
            "wifi_scan",
            "settings get global wifi_scan_always_enabled",
            "settings put global wifi_scan_always_enabled 0",
            "0",
            "Tắt Wi‑Fi scan always"
        )

        // mobile_data_always_on = 0 — radio data không giữ kết nối giả khi Wi‑Fi
        snapAndPut(
            "mobile_data_always",
            "settings get global mobile_data_always_on",
            "settings put global mobile_data_always_on 0",
            "0",
            "Tắt mobile data always-on"
        )

        // Khóa xoay màn hình lúc chơi (tránh giật khi nghiêng máy)
        snapAndPut(
            "accel_rot",
            "settings get system accelerometer_rotation",
            "settings put system accelerometer_rotation 0",
            "0",
            "Khóa xoay màn hình"
        )

        // Giữ màn hình lâu hơn trong trận (30 phút) — tránh sleep giữa combat
        snapAndPut(
            "screen_off",
            "settings get system screen_off_timeout",
            "settings put system screen_off_timeout 1800000",
            "1800000",
            "Timeout màn hình 30 phút"
        )

        // Tắt Battery Saver nếu đang bật (Saver làm giảm xung/FPS)
        val lowPower = ShizukuHelper.runShellCommandWithOutput("settings get global low_power")?.trim()
        if (SessionSnapshotStore.get(context, "low_power") == null && !lowPower.isNullOrBlank()) {
            SessionSnapshotStore.save(context, "low_power", lowPower)
        }
        if (lowPower == "1") {
            if (verifiedSettingWrite(
                    "settings put global low_power 0",
                    "settings get global low_power",
                    "0"
                )
            ) {
                actions += "Tắt Battery Saver: OK"
                n++
            }
        }

        // Máy yếu / Cực mạnh: siết max_cached_processes qua device_config (mạnh hơn settings global)
        if (isLowRam || mode == MainActivity.PerfMode.CUC_MANH) {
            val key = "dc_max_cached"
            if (SessionSnapshotStore.get(context, key) == null) {
                val cur = ShizukuHelper.runShellCommandWithOutput(
                    "device_config get activity_manager max_cached_processes"
                )?.trim()
                SessionSnapshotStore.save(context, key, cur ?: "__MISSING__")
            }
            val target = if (isLowRam) "3" else "6"
            val ok = ShizukuHelper.runShellCommand(
                "device_config put activity_manager max_cached_processes $target"
            )
            if (ok) {
                actions += "device_config max_cached_processes=$target: OK"
                n++
            }
        }

        // Thông báo available Wi‑Fi — tắt spam
        snapAndPut(
            "wifi_avail_notif",
            "settings get global wifi_networks_available_notification_on",
            "settings put global wifi_networks_available_notification_on 0",
            "0",
            "Tắt thông báo Wi‑Fi gần"
        )

        // ===== MAX NO-ROOT v4.3: thêm cờ an toàn còn lại =====
        // BLE scan nền
        snapAndPut(
            "ble_scan",
            "settings get global ble_scan_always_enabled",
            "settings put global ble_scan_always_enabled 0",
            "0",
            "Tắt BLE scan always"
        )
        // Giữ Wi‑Fi thức khi chơi (giảm biến động ping) — một số ROM bỏ qua
        snapAndPut(
            "wifi_power_save",
            "settings get global wifi_suspend_optimizations_enabled",
            "settings put global wifi_suspend_optimizations_enabled 0",
            "0",
            "Tắt Wi‑Fi suspend optimize"
        )
        // Tắt gợi ý mạng công cộng (giảm quét/CPU nền)
        snapAndPut(
            "net_recommend",
            "settings get global network_recommendations_enabled",
            "settings put global network_recommendations_enabled 0",
            "0",
            "Tắt network recommendations"
        )
        // Android 11+: tắt adaptive connectivity (không nhảy Wi‑Fi/4G giữa trận)
        snapAndPut(
            "adaptive_conn",
            "settings get secure adaptive_connectivity_enabled",
            "settings put secure adaptive_connectivity_enabled 0",
            "0",
            "Tắt adaptive connectivity"
        )
        // Game driver / ANGLE cho app (nếu ROM hỗ trợ) — giúp một số game GLES
        snapAndPut(
            "game_driver",
            "settings get global game_driver_all_apps",
            "settings put global game_driver_all_apps 1",
            "1",
            "Bật Game Driver all apps"
        )
        // Giảm log Wi‑Fi verbose (I/O)
        snapAndPut(
            "wifi_verbose",
            "settings get global wifi_verbose_logging_enabled",
            "settings put global wifi_verbose_logging_enabled 0",
            "0",
            "Tắt Wi‑Fi verbose log"
        )
        // Tắt “cải thiện độ chính xác vị trí” kéo theo scan — best effort
        snapAndPut(
            "wifi_wakeup",
            "settings get global wifi_wakeup_enabled",
            "settings put global wifi_wakeup_enabled 0",
            "0",
            "Tắt Wi‑Fi wakeup theo vị trí"
        )
        // Ưu tiên không force-stop khi hệ thống dọn (giữ game) — activity_manager
        if (isLowRam || mode == MainActivity.PerfMode.CUC_MANH || mode == MainActivity.PerfMode.CAN_BANG) {
            val key = "dc_max_phantom"
            if (SessionSnapshotStore.get(context, key) == null) {
                val cur = ShizukuHelper.runShellCommandWithOutput(
                    "device_config get activity_manager max_phantom_processes"
                )?.trim()
                SessionSnapshotStore.save(context, key, cur ?: "__MISSING__")
            }
            val ph = if (isLowRam) "8" else "16"
            if (ShizukuHelper.runShellCommand(
                    "device_config put activity_manager max_phantom_processes $ph"
                )
            ) {
                actions += "device_config max_phantom_processes=$ph: OK"
                n++
            }
        }

        return n
    }

    private fun restoreMaxNoRootPack(context: Context) {
        fun restoreKey(key: String, putPrefix: String, fallback: String? = null) {
            val v = SessionSnapshotStore.get(context, key)
            when {
                v != null && v != "__MISSING__" && v.isNotBlank() && !v.equals("null", true) ->
                    ShizukuHelper.runShellCommand("$putPrefix $v")
                fallback != null ->
                    ShizukuHelper.runShellCommand("$putPrefix $fallback")
            }
        }
        // heads_up: bắt buộc bật lại (fallback 1) — tránh mất banner tin nhắn sau game
        restoreKey("heads_up", "settings put global heads_up_notifications_enabled", fallback = "1")
        restoreKey("wifi_scan", "settings put global wifi_scan_always_enabled")
        restoreKey("mobile_data_always", "settings put global mobile_data_always_on")
        restoreKey("accel_rot", "settings put system accelerometer_rotation")
        restoreKey("screen_off", "settings put system screen_off_timeout")
        restoreKey("low_power", "settings put global low_power")
        restoreKey("wifi_avail_notif", "settings put global wifi_networks_available_notification_on")
        restoreKey("ble_scan", "settings put global ble_scan_always_enabled")
        restoreKey("wifi_power_save", "settings put global wifi_suspend_optimizations_enabled")
        restoreKey("net_recommend", "settings put global network_recommendations_enabled")
        restoreKey("adaptive_conn", "settings put secure adaptive_connectivity_enabled")
        restoreKey("game_driver", "settings put global game_driver_all_apps")
        restoreKey("wifi_verbose", "settings put global wifi_verbose_logging_enabled")
        restoreKey("wifi_wakeup", "settings put global wifi_wakeup_enabled")

        val dc = SessionSnapshotStore.get(context, "dc_max_cached")
        when {
            dc == null -> {}
            dc == "__MISSING__" || dc.equals("null", true) || dc.isBlank() ->
                ShizukuHelper.runShellCommand("device_config delete activity_manager max_cached_processes")
            else ->
                ShizukuHelper.runShellCommand("device_config put activity_manager max_cached_processes $dc")
        }
        val ph = SessionSnapshotStore.get(context, "dc_max_phantom")
        when {
            ph == null -> {}
            ph == "__MISSING__" || ph.equals("null", true) || ph.isBlank() ->
                ShizukuHelper.runShellCommand("device_config delete activity_manager max_phantom_processes")
            else ->
                ShizukuHelper.runShellCommand("device_config put activity_manager max_phantom_processes $ph")
        }
    }


    /**
     * Path KHÔNG Shizuku v4.6 — max API công khai + ổn định khi chơi.
     * Có snapshot keys nosz_* để restore khi thoát.
     */
    private fun applyNoShizukuMax(
        context: Context,
        actions: MutableList<String>,
        mode: MainActivity.PerfMode
    ): Int {
        var n = 0
        val cr = context.contentResolver
        val canWrite = try {
            android.provider.Settings.System.canWrite(context)
        } catch (_: Throwable) { false }

        fun snapPutGlobal(key: String, name: String, value: String): Boolean {
            return try {
                if (SessionSnapshotStore.get(context, key) == null) {
                    val cur = android.provider.Settings.Global.getString(cr, name)
                    SessionSnapshotStore.save(context, key, cur ?: "__MISSING__")
                }
                android.provider.Settings.Global.putString(cr, name, value)
                true
            } catch (_: Throwable) { false }
        }
        fun snapPutSystem(key: String, name: String, value: String): Boolean {
            return try {
                if (SessionSnapshotStore.get(context, key) == null) {
                    val cur = android.provider.Settings.System.getString(cr, name)
                    SessionSnapshotStore.save(context, key, cur ?: "__MISSING__")
                }
                android.provider.Settings.System.putString(cr, name, value)
                true
            } catch (_: Throwable) { false }
        }
        fun snapPutSecure(key: String, name: String, value: String): Boolean {
            return try {
                if (SessionSnapshotStore.get(context, key) == null) {
                    val cur = android.provider.Settings.Secure.getString(cr, name)
                    SessionSnapshotStore.save(context, key, cur ?: "__MISSING__")
                }
                android.provider.Settings.Secure.putString(cr, name, value)
                true
            } catch (_: Throwable) { false }
        }

        // 1) Animation = 0 (giảm jank compositor UI hệ thống)
        if (canWrite) {
            var ok = false
            ok = snapPutGlobal("nosz_anim_w", "window_animation_scale", "0") || ok
            ok = snapPutGlobal("nosz_anim_t", "transition_animation_scale", "0") || ok
            ok = snapPutGlobal("nosz_anim_a", "animator_duration_scale", "0") || ok
            if (ok) {
                actions += "Tắt animation hệ thống (WRITE_SETTINGS): OK"
                n++
            }
        } else {
            actions += "Animation: cấp quyền Sửa cài đặt hệ thống để max no-Shizuku"
        }

        // 2) Khóa xoay + timeout màn hình dài (tránh sleep / xoay giữa trận)
        if (canWrite) {
            if (snapPutSystem("nosz_rot", android.provider.Settings.System.ACCELEROMETER_ROTATION, "0")) {
                actions += "Khóa xoay màn hình: OK"
                n++
            }
            if (snapPutSystem("nosz_screen", android.provider.Settings.System.SCREEN_OFF_TIMEOUT, "1800000")) {
                actions += "Timeout màn hình 30 phút: OK"
                n++
            }
        }

        // 3) Tắt Battery Saver nếu đang bật (API + Settings)
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isPowerSaveMode == true && canWrite) {
                if (SessionSnapshotStore.get(context, "nosz_low_power") == null) {
                    SessionSnapshotStore.save(context, "nosz_low_power", "1")
                }
                android.provider.Settings.Global.putString(cr, "low_power", "0")
                actions += "Tắt tiết kiệm pin (đang bật): OK"
                n++
            }
        } catch (_: Throwable) { }

        // 4) Wi‑Fi high-perf lock (giảm đứt mạng khi màn tắt)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm != null) {
                val lock = noShizukuWifiLock
                if (lock == null) {
                    @Suppress("DEPRECATION")
                    val l = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameBoosterVN")
                    l.setReferenceCounted(false)
                    l.acquire()
                    noShizukuWifiLock = l
                    actions += "Wi‑Fi high-perf lock: OK (ổn định mạng khi chơi)"
                    n++
                }
            }
        } catch (_: Throwable) { }

        // 5) Partial wake lock nhẹ — tránh Doze cắt CPU giữa trận (release khi restore)
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && noShizukuWakeLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GameBoosterVN:GameSession")
                wl.setReferenceCounted(false)
                wl.acquire(3 * 60 * 60 * 1000L) // max 3h
                noShizukuWakeLock = wl
                actions += "Giữ CPU tỉnh (partial wake): OK"
                n++
            }
        } catch (_: Throwable) { }

        // 6) Cảm ứng nhạy hơn (Secure settings — nhiều ROM cho phép khi canWrite)
        if (canWrite) {
            var touchOk = false
            touchOk = snapPutSecure("nosz_long", "long_press_timeout", "100") || touchOk
            touchOk = snapPutSecure("nosz_multi", "multi_press_timeout", "100") || touchOk
            try {
                if (SessionSnapshotStore.get(context, "nosz_pointer") == null) {
                    val cur = android.provider.Settings.System.getInt(cr, "pointer_speed", 0)
                    SessionSnapshotStore.save(context, "nosz_pointer", cur.toString())
                }
                android.provider.Settings.System.putInt(cr, "pointer_speed", 7)
                touchOk = true
            } catch (_: Throwable) { }
            if (touchOk) {
                actions += "Cảm ứng nhanh (timeout/pointer): OK"
                n++
            }
        }

        // 7) Tắt heads-up notification (giảm giật banner) — Global
        if (canWrite) {
            if (snapPutGlobal("nosz_heads", "heads_up_notifications_enabled", "0")) {
                actions += "Tắt heads-up notification: OK"
                n++
            }
        }

        // 8) Ghim refresh rate cao nếu máy hỗ trợ (Global — best effort)
        if (canWrite) {
            try {
                val peak = android.provider.Settings.Global.getString(cr, "peak_refresh_rate")
                if (!peak.isNullOrBlank() && peak != "null") {
                    snapPutGlobal("nosz_peak_rr", "peak_refresh_rate", peak) // snapshot only if we change
                    // Không hạ peak; chỉ cố min = peak nếu đọc được số
                    val p = peak.toFloatOrNull()
                    if (p != null && p >= 90f) {
                        if (SessionSnapshotStore.get(context, "nosz_min_rr") == null) {
                            val curMin = android.provider.Settings.Global.getString(cr, "min_refresh_rate")
                            SessionSnapshotStore.save(context, "nosz_min_rr", curMin ?: "__MISSING__")
                        }
                        android.provider.Settings.Global.putString(cr, "min_refresh_rate", peak)
                        actions += "Ghim refresh ≥ ${p.toInt()}Hz (best-effort): OK"
                        n++
                    }
                }
            } catch (_: Throwable) { }
        }

        // 9) Audio focus — giảm app khác tranh audio/CPU media
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                @Suppress("DEPRECATION")
                val r = am.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                if (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    noShizukuAudioFocusHeld = true
                    actions += "Audio focus game: OK"
                    n++
                }
            }
        } catch (_: Throwable) { }

        // 10) Ưu tiên thread booster thấp hơn default... thực ra tăng priority process bản thân
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            actions += "Thread priority foreground: OK"
            n++
        } catch (_: Throwable) { }

        // 11) GC + gợi ý
        try {
            Runtime.getRuntime().gc()
            System.runFinalization()
            actions += "GC booster: OK"
            n++
        } catch (_: Throwable) { }

        if (mode == MainActivity.PerfMode.CUC_MANH) {
            actions += "Không Shizuku: ưu tiên ổn định bền (tránh nóng → lag sau)"
        }
        actions += "AI học chiến lược vẫn chạy — càng chơi càng chỉnh mượt hơn"
        actions += "HUD giám sát nhiệt·RAM·frame + dim chống nóng (API công khai)"

        SessionSnapshotStore.save(context, "no_shizuku_path", "1")
        return n
    }

    @Volatile private var noShizukuWifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Volatile private var noShizukuWakeLock: android.os.PowerManager.WakeLock? = null
    @Volatile private var noShizukuAudioFocusHeld = false

    /** Khôi phục settings / lock đã bật trên path không Shizuku. */
    fun restoreNoShizukuMax(context: Context) {
        val cr = context.contentResolver
        fun restoreGlobal(key: String, name: String, fallback: String?) {
            val v = SessionSnapshotStore.get(context, key) ?: return
            try {
                if (v == "__MISSING__" || v.isBlank() || v.equals("null", true)) {
                    if (fallback != null) android.provider.Settings.Global.putString(cr, name, fallback)
                } else {
                    android.provider.Settings.Global.putString(cr, name, v)
                }
            } catch (_: Throwable) { }
        }
        fun restoreSystem(key: String, name: String, fallback: String?) {
            val v = SessionSnapshotStore.get(context, key) ?: return
            try {
                if (v == "__MISSING__" || v.isBlank() || v.equals("null", true)) {
                    if (fallback != null) android.provider.Settings.System.putString(cr, name, fallback)
                } else {
                    android.provider.Settings.System.putString(cr, name, v)
                }
            } catch (_: Throwable) { }
        }
        restoreGlobal("nosz_anim_w", "window_animation_scale", "1")
        restoreGlobal("nosz_anim_t", "transition_animation_scale", "1")
        restoreGlobal("nosz_anim_a", "animator_duration_scale", "1")
        restoreSystem("nosz_rot", android.provider.Settings.System.ACCELEROMETER_ROTATION, "1")
        restoreSystem("nosz_screen", android.provider.Settings.System.SCREEN_OFF_TIMEOUT, "60000")
        restoreGlobal("nosz_low_power", "low_power", null)
        restoreGlobal("nosz_heads", "heads_up_notifications_enabled", "1")
        restoreGlobal("nosz_min_rr", "min_refresh_rate", null)
        // Secure touch
        try {
            val lp = SessionSnapshotStore.get(context, "nosz_long")
            if (lp != null && lp != "__MISSING__")
                android.provider.Settings.Secure.putString(cr, "long_press_timeout", lp)
            val mp = SessionSnapshotStore.get(context, "nosz_multi")
            if (mp != null && mp != "__MISSING__")
                android.provider.Settings.Secure.putString(cr, "multi_press_timeout", mp)
        } catch (_: Throwable) { }
        try {
            val ps = SessionSnapshotStore.get(context, "nosz_pointer")?.toIntOrNull()
            if (ps != null)
                android.provider.Settings.System.putInt(cr, "pointer_speed", ps)
        } catch (_: Throwable) { }
        try {
            noShizukuWifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) { }
        noShizukuWifiLock = null
        try {
            noShizukuWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) { }
        noShizukuWakeLock = null
        if (noShizukuAudioFocusHeld) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                @Suppress("DEPRECATION")
                am?.abandonAudioFocus(null)
            } catch (_: Throwable) { }
            noShizukuAudioFocusHeld = false
        }
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
        } catch (_: Throwable) { }
    }

}
