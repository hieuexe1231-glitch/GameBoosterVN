package com.boostvn.gamebooster

import android.content.Context
import android.content.SharedPreferences

/**
 * "AI học trên máy" v6 Raphael — Phán đoán siêu nhanh, case tối ưu, càng chơi càng mượt.
 * Cảm hứng: phân tích đa tín hiệu tức thì → chọn phương án tối ưu (không phải LLM cloud).
 * Cơ chế: EMA thích ứng + Bayesian shrinkage + prior theo máy/OEM + điểm chiến lược.
 *
 * v6 Raphael:
 * - Trận 1 học gần như tức thì (alpha cao); tin sau ~1 trận.
 * - Prior OEM/tier khi game mới (Samsung GOS, Xiaomi, máy yếu…).
 * - getOptimalCase(): 1 lời phán tối ưu (chiến lược + lý do) cho trận tới.
 * - Theo dõi smooth_delta: thấy rõ "mượt hơn trận trước".
 */
object LearningProfileHelper {
    private const val PREFS_NAME = "booster_learning_profile"
    private const val SCHEMA_VERSION = 2
    private const val SCHEMA_VERSION_KEY = "schema_version"
    private const val ALPHA = 0.38f
    private const val ALPHA_FAST = 0.62f
    /** Trận đầu tiên — hấp thụ gần như toàn bộ tín hiệu */
    private const val ALPHA_INSTANT = 0.78f
    private const val ALPHA_THERMAL_DOWN = 0.09f
    private const val MIN_SESSION_MS_TO_LEARN = 35_000L
    private const val SHRINKAGE_K = 1.2f
    private const val DEVICE_KEY = "__device__"

    data class SessionResult(
        val durationMs: Long,
        val cleanupCountUsed: Int,
        val hadEarlyMemoryBottleneck: Boolean,
        val ramPercentAtFirstBottleneck: Int?,
        val hadThermalBottleneck: Boolean,
        val hadFrameBottleneck: Boolean = false,
        val avgJankPercent: Float? = null,
        val hadLateLag: Boolean = false,
        val recoverStuckTriggered: Boolean = false,
        val strategyUsed: Int = 0,
        /** v5: nghẽn IO/lưu trữ trong phiên */
        val hadIoBottleneck: Boolean = false
    )

    /** Alpha Raphael: trận 1 gần instant, 2–3 nhanh, sau ổn định — quên nóng rất chậm. */
    private fun adaptiveAlpha(sessionCount: Int, upward: Boolean): Float {
        if (!upward) return ALPHA_THERMAL_DOWN
        return when {
            sessionCount <= 1 -> ALPHA_INSTANT
            sessionCount <= 3 -> ALPHA_FAST
            sessionCount <= 6 -> 0.48f
            else -> ALPHA
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Chạy MỘT LẦN khi phát hiện dữ liệu học cũ (schema < hiện tại) - xoá riêng phần dữ
     * liệu bị ảnh hưởng bởi định nghĩa sai đã sửa, giữ nguyên phần còn lại (RAM/số app dọn
     * không liên quan tới lỗi thermal_rate). Xem mục 6 trong ghi chú đầu file. */
    private fun migrateIfNeeded(p: SharedPreferences) {
        val current = p.getInt(SCHEMA_VERSION_KEY, 1)
        if (current >= SCHEMA_VERSION) return
        try {
            val editor = p.edit()
            p.all.keys.filter { it.endsWith("__thermal_rate_ema") }.forEach { editor.remove(it) }
            editor.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            editor.apply()
        } catch (_: Throwable) { }
    }

    private fun key(scope: String, field: String) = "${scope}__$field"

    /** weight tăng dần theo số phiên, không có bước nhảy cứng - 0 phiên = 0% tin, càng
     * nhiều phiên càng tiệm cận 100% nhưng không bao giờ chạm hẳn 100%. */
    private fun trustWeight(sessions: Int): Float = sessions / (sessions + SHRINKAGE_K)

    fun recordSessionEnd(context: Context, gamePackage: String, result: SessionResult) {
        if (result.durationMs < MIN_SESSION_MS_TO_LEARN) return // dữ liệu quá ngắn, bỏ qua
        try {
            val p = prefs(context)
            migrateIfNeeded(p)
            val editor = p.edit()

            // Cập nhật cho CẢ HAI: đúng game này, VÀ bộ gộp chung của máy (dùng làm nền
            // tảng khi gặp game mới chưa từng chơi).
            updateScope(p, editor, gamePackage, result)
            updateScope(p, editor, DEVICE_KEY, result)

            editor.apply()
        } catch (t: Throwable) {
            // học thất bại không được làm crash app
        }
    }

    private fun updateScope(
        p: SharedPreferences,
        editor: SharedPreferences.Editor,
        scope: String,
        result: SessionResult
    ) {
        val sessionCount = p.getInt(key(scope, "sessions"), 0) + 1
        editor.putInt(key(scope, "sessions"), sessionCount)

        val ramAtIssue = result.ramPercentAtFirstBottleneck
        if (ramAtIssue != null) {
            val oldEma = p.getFloat(key(scope, "ram_threshold_ema"), ramAtIssue.toFloat())
            val a = adaptiveAlpha(sessionCount, upward = true)
            val newEma = if (sessionCount <= 1) ramAtIssue.toFloat()
                else oldEma * (1 - a) + ramAtIssue * a
            editor.putFloat(key(scope, "ram_threshold_ema"), newEma)
        }

        val oldCleanup = p.getFloat(key(scope, "cleanup_count_ema"), result.cleanupCountUsed.toFloat())
        val cleanupSample = if (result.hadEarlyMemoryBottleneck) {
            result.cleanupCountUsed + 1.2f // v5: tăng mạnh hơn khi RAM nghẽn sớm
        } else {
            result.cleanupCountUsed - 0.2f // giảm rất chậm — ưu tiên ổn định
        }
        val aCleanup = adaptiveAlpha(sessionCount, upward = result.hadEarlyMemoryBottleneck)
        editor.putFloat(
            key(scope, "cleanup_count_ema"),
            if (sessionCount <= 1) cleanupSample
            else oldCleanup * (1 - aCleanup) + cleanupSample * aCleanup
        )

        val oldThermalRate = p.getFloat(key(scope, "thermal_rate_ema"), if (result.hadThermalBottleneck) 1f else 0f)
        val thermalSample = if (result.hadThermalBottleneck) 1f else 0f
        val thermalAlpha = adaptiveAlpha(sessionCount, upward = thermalSample > oldThermalRate)
        val newThermalRate = if (sessionCount <= 1) thermalSample
            else oldThermalRate * (1 - thermalAlpha) + thermalSample * thermalAlpha
        editor.putFloat(key(scope, "thermal_rate_ema"), newThermalRate)

        val oldFrameRate = p.getFloat(key(scope, "frame_rate_ema"), if (result.hadFrameBottleneck) 1f else 0f)
        val frameSample = if (result.hadFrameBottleneck) 1f else 0f
        val frameAlpha = adaptiveAlpha(sessionCount, upward = frameSample > oldFrameRate)
        val newFrameRate = if (sessionCount <= 1) frameSample
            else oldFrameRate * (1 - frameAlpha) + frameSample * frameAlpha
        editor.putFloat(key(scope, "frame_rate_ema"), newFrameRate)

        result.avgJankPercent?.let { jank ->
            val oldJank = p.getFloat(key(scope, "avg_jank_ema"), jank)
            val a = adaptiveAlpha(sessionCount, upward = true)
            val newJank = if (sessionCount <= 1) jank else oldJank * (1 - a) + jank * a
            editor.putFloat(key(scope, "avg_jank_ema"), newJank)
        }

        // v5: học IO bottleneck (chờ lưu trữ)
        val ioSample = if (result.hadIoBottleneck) 1f else 0f
        val oldIo = p.getFloat(key(scope, "io_rate_ema"), ioSample)
        val ioAlpha = adaptiveAlpha(sessionCount, upward = ioSample > oldIo)
        val newIo = if (sessionCount <= 1) ioSample
            else oldIo * (1 - ioAlpha) + ioSample * ioAlpha
        editor.putFloat(key(scope, "io_rate_ema"), newIo)

        // Học độ dài phiên (phút) — trận dài + nóng → ưu tiên mát
        val durMin = (result.durationMs / 60_000f).coerceIn(0.5f, 120f)
        val oldDur = p.getFloat(key(scope, "duration_min_ema"), durMin)
        val aDur = adaptiveAlpha(sessionCount, upward = true)
        val newDur = if (sessionCount <= 1) durMin else oldDur * (1 - aDur) + durMin * aDur
        editor.putFloat(key(scope, "duration_min_ema"), newDur)

        val lateSample = if (result.hadLateLag || result.recoverStuckTriggered) 1f else 0f
        val oldLate = p.getFloat(key(scope, "late_lag_rate_ema"), lateSample)
        val lateAlpha = adaptiveAlpha(sessionCount, upward = lateSample > oldLate)
        val newLate = if (sessionCount <= 1) lateSample
            else oldLate * (1 - lateAlpha) + lateSample * lateAlpha
        editor.putFloat(key(scope, "late_lag_rate_ema"), newLate)

        // Phiên mượt = không frame/thermal/late/IO/recover
        val smoothSample = if (!result.hadFrameBottleneck && !result.hadThermalBottleneck &&
            !result.hadLateLag && !result.recoverStuckTriggered && !result.hadIoBottleneck
        ) 1f else 0f
        val oldSmooth = p.getFloat(key(scope, "smooth_rate_ema"), smoothSample)
        val smoothAlpha = adaptiveAlpha(sessionCount, upward = smoothSample > oldSmooth)
        val newSmooth = if (sessionCount <= 1) smoothSample
            else oldSmooth * (1 - smoothAlpha) + smoothSample * smoothAlpha
        editor.putFloat(key(scope, "smooth_rate_ema"), newSmooth)
        // Raphael: delta mượt so với EMA trước — UI/hint "mượt hơn trận trước"
        editor.putFloat(key(scope, "last_smooth_delta"), newSmooth - oldSmooth)
        editor.putFloat(key(scope, "last_session_smooth"), smoothSample)

        // Điểm chiến lược — phạt nặng late lag / stuck; thưởng phiên mượt
        val strat = result.strategyUsed.coerceIn(0, 3)
        val outcome = when {
            result.recoverStuckTriggered && result.hadLateLag -> 0.02f
            result.recoverStuckTriggered || result.hadLateLag -> 0.08f
            result.hadThermalBottleneck && result.hadFrameBottleneck -> 0.12f
            result.hadThermalBottleneck && result.hadIoBottleneck -> 0.18f
            result.hadThermalBottleneck || result.hadFrameBottleneck -> 0.35f
            result.hadIoBottleneck -> 0.55f
            else -> 0.96f
        }
        val sk = key(scope, "strat_${strat}_score")
        val oldScore = p.getFloat(sk, 0.5f)
        val nStrat = p.getInt(key(scope, "strat_${strat}_n"), 0) + 1
        val a = maxOf(0.14f, ALPHA_FAST / (1f + nStrat * 0.07f))
        val newScore = if (nStrat <= 1) outcome else oldScore * (1 - a) + outcome * a
        editor.putFloat(sk, newScore)
        editor.putInt(key(scope, "strat_${strat}_n"), nStrat)
        editor.putInt(key(scope, "last_strategy"), strat)
    }

    /** Ngưỡng RAM% báo động - trộn mượt 3 tầng theo độ tin cậy: mặc định tĩnh (fallback)
     * -> kinh nghiệm chung của máy -> kinh nghiệm riêng của đúng game này. Luôn trả về một
     * giá trị dùng được ngay. */
    fun getLearnedMemoryThreshold(context: Context, gamePackage: String, fallback: Int): Int {
        try {
            val p = prefs(context)
            val deviceSessions = p.getInt(key(DEVICE_KEY, "sessions"), 0)
            val deviceWeight = trustWeight(deviceSessions)
            val deviceEma = p.getFloat(key(DEVICE_KEY, "ram_threshold_ema"), fallback.toFloat())
            val level2 = fallback * (1 - deviceWeight) + deviceEma * deviceWeight

            val gameSessions = p.getInt(key(gamePackage, "sessions"), 0)
            val gameWeight = trustWeight(gameSessions)
            val gameEma = p.getFloat(key(gamePackage, "ram_threshold_ema"), level2)
            val blended = level2 * (1 - gameWeight) + gameEma * gameWeight

            // Trừ biên an toàn 4% để cảnh báo sớm hơn mốc đã từng gây sự cố.
            // SỬA LỖI QUAN TRỌNG: kẹp MỘT CHIỀU với coerceAtMost(fallback) - ngưỡng học
            // được CHỈ được phép thấp hơn (báo động sớm hơn, cẩn thận hơn) mặc định tĩnh,
            // KHÔNG BAO GIỜ được phép cao hơn (báo động trễ hơn). Đây chính là chỗ sửa
            // vòng lặp "mượt vài ngày đầu rồi lag trở lại" - trước đây ngưỡng có thể tự
            // trôi lên cao hơn mặc định sau nhiều phiên yên ổn, làm app phản ứng ngày càng
            // trễ dần theo thời gian.
            return (blended - 4f).toInt().coerceAtMost(fallback).coerceIn(70, 95)
        } catch (t: Throwable) {
            return fallback
        }
    }

    /** Số app nên dọn trước trận - cùng cách trộn 3 tầng như trên. */
    fun getLearnedCleanupCount(context: Context, gamePackage: String, fallback: Int): Int {
        try {
            val p = prefs(context)
            val deviceSessions = p.getInt(key(DEVICE_KEY, "sessions"), 0)
            val deviceWeight = trustWeight(deviceSessions)
            val deviceEma = p.getFloat(key(DEVICE_KEY, "cleanup_count_ema"), fallback.toFloat())
            val level2 = fallback * (1 - deviceWeight) + deviceEma * deviceWeight

            val gameSessions = p.getInt(key(gamePackage, "sessions"), 0)
            val gameWeight = trustWeight(gameSessions)
            val gameEma = p.getFloat(key(gamePackage, "cleanup_count_ema"), level2)
            val blended = level2 * (1 - gameWeight) + gameEma * gameWeight

            // SỬA LỖI QUAN TRỌNG: kẹp MỘT CHIỀU với coerceAtLeast(fallback) - số app học
            // được CHỈ được phép NHIỀU hơn (dọn kỹ hơn) mặc định tĩnh, KHÔNG BAO GIỜ được
            // phép ÍT hơn. Trước đây mỗi phiên không nghẽn sẽ tự giảm dần số này xuống tới
            // sát mức tối thiểu 2, xoá mất chính lý do khiến phiên đó không nghẽn (dọn đủ
            // nhiều) - đây là nguyên nhân chính gây "mượt vài ngày đầu rồi lag trở lại".
            return Math.round(blended).coerceAtLeast(fallback).coerceIn(2, 6)
        } catch (t: Throwable) {
            return fallback
        }
    }

    /** Game này có khả năng "nóng máy" hay không - trộn tỉ lệ nghẽn nhiệt riêng của game
     * với tỉ lệ chung của máy, theo cùng công thức tin cậy tăng dần. true nếu tỉ lệ trộn
     * >= 40%. Dùng để phản ứng SỚM HƠN (giảm sáng màn hình chủ động) cho game/máy có tiền
     * sử dễ nóng. */
    fun getLearnedThermalRisk(context: Context, gamePackage: String): Boolean {
        try {
            val p = prefs(context)
            migrateIfNeeded(p)
            val deviceSessions = p.getInt(key(DEVICE_KEY, "sessions"), 0)
            val deviceWeight = trustWeight(deviceSessions)
            val deviceRate = p.getFloat(key(DEVICE_KEY, "thermal_rate_ema"), 0f)
            val level2 = deviceRate * deviceWeight // mặc định tĩnh coi như 0% nóng

            val gameSessions = p.getInt(key(gamePackage, "sessions"), 0)
            val gameWeight = trustWeight(gameSessions)
            val gameRate = p.getFloat(key(gamePackage, "thermal_rate_ema"), level2)
            val blended = level2 * (1 - gameWeight) + gameRate * gameWeight

            // v5: ngưỡng 0.35 — nhận diện game hay nóng sớm hơn (1–2 trận có tín hiệu)
            return blended >= 0.35f
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * v5 — Độ nhạy lag đã học (0.0 = bình thường, 1.0 = rất dễ lag).

     * Kết hợp đa tín hiệu: frame lag + late lag + thermal + IO + avg jank.
     * Phản ứng sớm hơn với game có tiền sử không ổn định.
     */
    fun getLearnedLagSensitivity(context: Context, gamePackage: String): Float {
        return try {
            val p = prefs(context)
            val deviceSessions = p.getInt(key(DEVICE_KEY, "sessions"), 0)
            val gameSessions = p.getInt(key(gamePackage, "sessions"), 0)
            val dw = trustWeight(deviceSessions)
            val gw = trustWeight(gameSessions)

            fun blend(deviceKey: String, gameKey: String): Float {
                val d = p.getFloat(key(DEVICE_KEY, deviceKey), 0f) * dw
                val g = p.getFloat(key(gamePackage, gameKey), d)
                return d * (1 - gw) + g * gw
            }

            val frame = blend("frame_rate_ema", "frame_rate_ema")
            val late = blend("late_lag_rate_ema", "late_lag_rate_ema")
            val thermal = blend("thermal_rate_ema", "thermal_rate_ema")
            val io = blend("io_rate_ema", "io_rate_ema")

            val avgJank = p.getFloat(key(gamePackage, "avg_jank_ema"), -1f)
            val jankBoost = if (avgJank > 0f) (avgJank / 22f).coerceIn(0f, 0.35f) else 0f

            // Trọng số: frame & late quan trọng nhất cho "cảm giác lag"
            val combined = (
                frame * 0.35f +
                late * 0.30f +
                thermal * 0.18f +
                io * 0.10f +
                jankBoost * 0.25f
            ).coerceIn(0f, 1f)
            combined
        } catch (_: Throwable) {
            0f
        }
    }

    /**
     * v5 — Điểm ổn định đã học (0..1). Cao = nhiều phiên mượt, ít late-lag.
     * Dùng để AdaptiveEngine nới interval / bớt can thiệp khi máy đã ổn.
     */
    fun getLearnedStabilityScore(context: Context, gamePackage: String): Float {
        return try {
            val p = prefs(context)
            val sessions = p.getInt(key(gamePackage, "sessions"), 0)
            if (sessions <= 0) {
                val ds = p.getInt(key(DEVICE_KEY, "sessions"), 0)
                if (ds <= 0) return 0.5f
                return p.getFloat(key(DEVICE_KEY, "smooth_rate_ema"), 0.5f)
            }
            val smooth = p.getFloat(key(gamePackage, "smooth_rate_ema"), 0.5f)
            val late = p.getFloat(key(gamePackage, "late_lag_rate_ema"), 0f)
            val thermal = p.getFloat(key(gamePackage, "thermal_rate_ema"), 0f)
            (smooth * 0.55f + (1f - late) * 0.30f + (1f - thermal) * 0.15f).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            0.5f
        }
    }

    /** Cho phép người dùng xoá dữ liệu học của 1 game (ví dụ sau khi đổi ROM/cài lại máy).
     * Không xoá bộ gộp chung của máy - đó là kinh nghiệm về chính cái máy, không gắn với
     * riêng game này. */
    fun resetGame(context: Context, gamePackage: String) {
        try {
            prefs(context).edit()
                .remove(key(gamePackage, "sessions"))
                .remove(key(gamePackage, "ram_threshold_ema"))
                .remove(key(gamePackage, "cleanup_count_ema"))
                .remove(key(gamePackage, "thermal_rate_ema"))
                .remove(key(gamePackage, "frame_rate_ema"))
                .remove(key(gamePackage, "avg_jank_ema"))
                .apply()
        } catch (t: Throwable) {
        }
    }


    /**
     * v4.2: Game/máy hay "mượt đầu – lag sau" (late lag / recover stuck / nóng).
     * → BỎ Fixed Perf deferred (Fixed Perf dễ tích nhiệt → lag về sau).
     */
    fun getLearnedSkipFixedPerf(context: Context, gamePackage: String): Boolean {
        return try {
            val p = prefs(context)
            val gameSessions = p.getInt(key(gamePackage, "sessions"), 0)
            val deviceSessions = p.getInt(key(DEVICE_KEY, "sessions"), 0)
            if (gameSessions + deviceSessions < 2) return false
            val gameLate = p.getFloat(key(gamePackage, "late_lag_rate_ema"), 0f)
            val deviceLate = p.getFloat(key(DEVICE_KEY, "late_lag_rate_ema"), 0f)
            val gw = trustWeight(gameSessions)
            val late = deviceLate * (1 - gw) + gameLate * gw
            val thermal = getLearnedThermalRisk(context, gamePackage)
            late >= 0.35f || (thermal && late >= 0.22f)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * v4.2: Grace đầu trận (ms). Game hay giật lúc load → grace dài hơn;
     * game ổn định nhiều phiên → hơi ngắn hơn (vẫn >= 45s).
     */
    fun getLearnedGraceMs(context: Context, gamePackage: String, fallbackMs: Long): Long {
        return try {
            val p = prefs(context)
            val sessions = p.getInt(key(gamePackage, "sessions"), 0)
            if (sessions < 2) return fallbackMs
            val frameRate = p.getFloat(key(gamePackage, "frame_rate_ema"), 0f)
            val smoothRate = p.getFloat(key(gamePackage, "smooth_rate_ema"), 0.5f)
            val lateRate = p.getFloat(key(gamePackage, "late_lag_rate_ema"), 0f)
            var ms = fallbackMs.toFloat()
            // Hay frame lag → cho load lâu hơn trước khi AI can thiệp
            if (frameRate >= 0.4f) ms += 8_000f
            // Hay lag về sau → grace dài + tránh Fixed Perf sớm
            if (lateRate >= 0.3f) ms += 10_000f
            // Nhiều phiên mượt → có thể rút nhẹ (không dưới 45s)
            if (smoothRate >= 0.65f && lateRate < 0.2f) ms -= 5_000f
            ms.toLong().coerceIn(45_000L, 75_000L)
        } catch (_: Throwable) {
            fallbackMs
        }
    }


    /** Gợi ý Raphael: lời phán case tối ưu + xu hướng mượt. */
    fun getLearningHint(context: Context, gamePackage: String): String {
        return try {
            val c = getOptimalCase(context, gamePackage)
            if (c.sessionsLearned <= 0) {
                return "Raphael: ${c.strategyLabel} — ${c.reason}"
            }
            buildString {
                append("Raphael · ${c.sessionsLearned} trận · ${c.strategyLabel}")
                append(" · ${c.smoothnessTrend}")
                if (c.lagSensitivity >= 0.35f) append(" · nhạy lag")
                if (c.stabilityScore >= 0.7f) append(" · ổn định cao")
                append(" · ${c.reason}")
            }
        } catch (_: Throwable) {
            "Raphael đang học"
        }
    }

    /** Số phiên đã học cho game (hiển thị / debug). */
    fun getSessionCount(context: Context, gamePackage: String): Int {
        return try {
            prefs(context).getInt(key(gamePackage, "sessions"), 0)
        } catch (_: Throwable) {
            0
        }
    }


    /** 4 chiến lược no-root: thử & học chiến lược nào mượt nhất cho từng game. */
    object Strategy {
        const val BALANCED = 0      // mặc định
        const val COOL_FIRST = 1    // tránh Fixed Perf, grace dài, dim sớm (chống lag về sau)
        const val CLEAN_HEAVY = 2   // dọn nền nhiều hơn trước trận
        const val LIGHT_AI = 3      // AI ít shell hơn, skip FP (máy yếu / dễ giật vì can thiệp)
        fun label(id: Int): String = when (id) {
            COOL_FIRST -> "Ưu tiên mát"
            CLEAN_HEAVY -> "Dọn nền mạnh"
            LIGHT_AI -> "AI nhẹ"
            else -> "Cân bằng"
        }
    }

    /**
     * Prior OEM/tier khi chưa có dữ liệu game — tương thích rộng từ trận 0.
     * Samsung: hay GOS/nóng → COOL_FIRST. Máy yếu/low-RAM → CLEAN_HEAVY hoặc LIGHT_AI.
     */
    private fun oemTierPriorStrategy(context: Context): Int {
        return try {
            val mfr = android.os.Build.MANUFACTURER.lowercase()
            val tier = DeviceProfileHelper.detectTier(context)
            val lowRam = DeviceProfileHelper.isLowRamDevice(context)
            when {
                lowRam || tier == DeviceProfileHelper.Tier.WEAK -> Strategy.LIGHT_AI
                mfr.contains("samsung") -> Strategy.COOL_FIRST
                mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") ->
                    if (tier == DeviceProfileHelper.Tier.MID) Strategy.COOL_FIRST else Strategy.BALANCED
                mfr.contains("vivo") || mfr.contains("oppo") || mfr.contains("realme") -> Strategy.CLEAN_HEAVY
                else -> Strategy.BALANCED
            }
        } catch (_: Throwable) {
            Strategy.BALANCED
        }
    }

    /**
     * v6 Raphael — Chọn chiến lược tối ưu cho trận tới.
     * 0 trận: prior OEM/tier. 1–2 trận: học nhanh. Sau đó: điểm + prior + chống kẹt.
     */
    fun pickStrategy(context: Context, gamePackage: String): Int {
        return try {
            val p = prefs(context)
            migrateIfNeeded(p)
            val sessions = p.getInt(key(gamePackage, "sessions"), 0)
            val late = p.getFloat(key(gamePackage, "late_lag_rate_ema"), 0f)
            val smooth = p.getFloat(key(gamePackage, "smooth_rate_ema"), 0.5f)
            val thermal = p.getFloat(key(gamePackage, "thermal_rate_ema"), 0f)
            val frame = p.getFloat(key(gamePackage, "frame_rate_ema"), 0f)
            val io = p.getFloat(key(gamePackage, "io_rate_ema"), 0f)
            val last = p.getInt(key(gamePackage, "last_strategy"), Strategy.BALANCED)
            val deviceLate = p.getFloat(key(DEVICE_KEY, "late_lag_rate_ema"), 0f)
            val deviceThermal = p.getFloat(key(DEVICE_KEY, "thermal_rate_ema"), 0f)

            fun score(id: Int): Float {
                val g = p.getFloat(key(gamePackage, "strat_${id}_score"), -1f)
                if (g >= 0f) return g
                return p.getFloat(key(DEVICE_KEY, "strat_${id}_score"), 0.5f)
            }

            fun priorBoost(id: Int): Float {
                var b = 0f
                when (id) {
                    Strategy.COOL_FIRST -> {
                        if (thermal >= 0.30f || late >= 0.22f || deviceThermal >= 0.35f) b += 0.20f
                        if (thermal >= 0.45f || deviceLate >= 0.30f) b += 0.12f
                    }
                    Strategy.CLEAN_HEAVY -> {
                        val ramEma = p.getFloat(key(gamePackage, "ram_threshold_ema"), 90f)
                        if (ramEma <= 84f) b += 0.16f
                        if (io >= 0.25f) b += 0.10f
                    }
                    Strategy.LIGHT_AI -> {
                        if (frame >= 0.35f && thermal < 0.35f) b += 0.14f
                        if (smooth < 0.45f && late < 0.28f) b += 0.10f
                        if (DeviceProfileHelper.detectTier(context) == DeviceProfileHelper.Tier.WEAK) b += 0.12f
                    }
                    Strategy.BALANCED -> {
                        if (smooth >= 0.62f && late < 0.22f) b += 0.12f
                    }
                }
                return b
            }

            fun total(id: Int) = score(id) + priorBoost(id)

            // Trận 0: prior máy — phán ngay không cần chơi trước
            if (sessions <= 0) return oemTierPriorStrategy(context)

            // Trận 1: trộn prior máy + tín hiệu vừa học
            if (sessions == 1) {
                return when {
                    thermal >= 0.35f || late >= 0.25f || deviceThermal >= 0.4f -> Strategy.COOL_FIRST
                    p.getFloat(key(gamePackage, "ram_threshold_ema"), 90f) <= 82f -> Strategy.CLEAN_HEAVY
                    frame >= 0.40f -> Strategy.LIGHT_AI
                    else -> oemTierPriorStrategy(context)
                }
            }

            val stillLaggy = late >= 0.18f || smooth < 0.50f || thermal >= 0.40f
            if (stillLaggy) {
                val order = listOf(
                    Strategy.COOL_FIRST, Strategy.CLEAN_HEAVY, Strategy.LIGHT_AI, Strategy.BALANCED
                ).filter { it != last }
                return order.maxByOrNull { total(it) } ?: Strategy.COOL_FIRST
            }

            val all = listOf(Strategy.BALANCED, Strategy.COOL_FIRST, Strategy.CLEAN_HEAVY, Strategy.LIGHT_AI)
            val best = all.maxByOrNull { total(it) } ?: Strategy.BALANCED
            if (sessions >= 3 && smooth >= 0.70f && (sessions % 6 == 0)) {
                return all.filter { it != best }.maxByOrNull { total(it) } ?: best
            }
            best
        } catch (_: Throwable) {
            Strategy.BALANCED
        }
    }

    /**
     * Raphael: một lời phán tối ưu cho trận tới — chiến lược + lý do + xu hướng mượt.
     */
    data class OptimalCase(
        val strategyId: Int,
        val strategyLabel: String,
        val reason: String,
        val smoothnessTrend: String,
        val sessionsLearned: Int,
        val lagSensitivity: Float,
        val stabilityScore: Float
    )

    fun getOptimalCase(context: Context, gamePackage: String): OptimalCase {
        val strat = pickStrategy(context, gamePackage)
        val n = getSessionCount(context, gamePackage)
        val sens = getLearnedLagSensitivity(context, gamePackage)
        val stab = getLearnedStabilityScore(context, gamePackage)
        val delta = try {
            prefs(context).getFloat(key(gamePackage, "last_smooth_delta"), 0f)
        } catch (_: Throwable) { 0f }

        val reason = try {
            val p = prefs(context)
            val late = p.getFloat(key(gamePackage, "late_lag_rate_ema"), 0f)
            val thermal = p.getFloat(key(gamePackage, "thermal_rate_ema"), 0f)
            val frame = p.getFloat(key(gamePackage, "frame_rate_ema"), 0f)
            when (strat) {
                Strategy.COOL_FIRST -> when {
                    thermal >= 0.35f -> "Máy/game dễ nóng — ưu tiên mát, bỏ Fixed Perf sớm"
                    late >= 0.25f -> "Hay mượt đầu–lag sau — kéo grace, tránh tích nhiệt"
                    else -> "Profile nhiệt/late-lag → chiến lược mát an toàn nhất"
                }
                Strategy.CLEAN_HEAVY -> "RAM/IO từng nghẽn — dọn nền mạnh trước trận"
                Strategy.LIGHT_AI -> when {
                    frame >= 0.35f -> "Frame từng giật — AI can thiệp tối thiểu trong trận"
                    else -> "Máy nhạy can thiệp — giữ booster siêu nhẹ"
                }
                else -> if (stab >= 0.65f) "Đã ổn định — giữ cân bằng, không đụng thừa"
                else "Chưa đủ dữ liệu xấu — cân bằng là case tối ưu"
            }
        } catch (_: Throwable) {
            "Case mặc định an toàn"
        }

        val trend = when {
            n <= 0 -> "Chưa học — trận này bắt đầu thu thập"
            delta >= 0.08f -> "↑ Mượt hơn trận trước (thấy rõ)"
            delta >= 0.02f -> "↑ Đang mượt dần"
            delta <= -0.08f -> "↓ Vừa kém hơn — đã chỉnh chiến lược"
            else -> "→ Giữ mức ổn định"
        }

        return OptimalCase(
            strategyId = strat,
            strategyLabel = Strategy.label(strat),
            reason = reason,
            smoothnessTrend = trend,
            sessionsLearned = n,
            lagSensitivity = sens,
            stabilityScore = stab
        )
    }

    fun strategyCleanupBonus(strategy: Int): Int = when (strategy) {
        Strategy.CLEAN_HEAVY -> 2
        Strategy.COOL_FIRST -> 1
        else -> 0
    }

    fun strategyForcesSkipFixedPerf(strategy: Int): Boolean =
        strategy == Strategy.COOL_FIRST || strategy == Strategy.LIGHT_AI

    fun strategyGraceBonusMs(strategy: Int): Long = when (strategy) {
        Strategy.COOL_FIRST -> 12_000L
        Strategy.LIGHT_AI -> 6_000L
        else -> 0L
    }

}
