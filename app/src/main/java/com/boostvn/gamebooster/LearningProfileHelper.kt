package com.boostvn.gamebooster

import android.content.Context
import android.content.SharedPreferences

/**
 * "AI học trên máy" v4.2 — Càng chơi càng mượt (học late-lag, grace, skip Fixed Perf).
 * THẬT THÀ: học thống kê nhẹ (EMA + Bayesian shrinkage), không phải mạng nơ-ron.
 *
 * NÂNG CẤP v2.1:
 * - Thêm học tỉ lệ FRAME bottleneck (lag frame-time) để nhận diện game/máy dễ giật.
 * - SessionResult mở rộng: hadFrameBottleneck + avgJankPercent (nếu có).
 * - getLearnedLagSensitivity(): trả về mức độ nhạy cần thiết (0.0–1.0) để Adaptive
 *   Engine / HudOverlayService phản ứng sớm hơn với game dễ lag.
 * - Giữ nguyên toàn bộ cơ chế kẹp một chiều (chỉ cẩn thận hơn, không bao giờ lơ là hơn).
 * - Schema vẫn 2, dữ liệu cũ tương thích (field mới tự khởi tạo khi cần).
 */
object LearningProfileHelper {
    private const val PREFS_NAME = "booster_learning_profile"
    private const val SCHEMA_VERSION = 2
    private const val SCHEMA_VERSION_KEY = "schema_version"
    private const val ALPHA = 0.42f // v4.5: học nhanh hơn — 2–3 trận đã thấy khác
    private const val ALPHA_THERMAL_DOWN = 0.12f // "quên" độ nóng chậm hơn hẳn "nhớ" (0.3) -
                                                   // game từng làm nóng máy vẫn bị coi là
                                                   // rủi ro thêm nhiều phiên mát mẻ sau đó,
                                                   // tránh việc AI vội tin "đã hết nóng" rồi
                                                   // bật lại Fixed Performance Mode quá sớm.
    private const val MIN_SESSION_MS_TO_LEARN = 45_000L // v4.5: trận ≥45s đã học
    private const val SHRINKAGE_K = 2.0f // v4.5: tin sớm hơn (~2 trận)
    private const val DEVICE_KEY = "__device__" // khoá gộp kinh nghiệm chung của máy

    data class SessionResult(
        val durationMs: Long,
        val cleanupCountUsed: Int,
        val hadEarlyMemoryBottleneck: Boolean, // nghẽn RAM thật xảy ra trong vài phút đầu
        val ramPercentAtFirstBottleneck: Int?, // RAM% ngay trước lần nghẽn RAM đầu tiên (chỉ loại MEMORY)
        val hadThermalBottleneck: Boolean,
        // v2.1 — lag sensitivity
        val hadFrameBottleneck: Boolean = false, // có phát hiện FRAME-TIME XẤU trong phiên
        val avgJankPercent: Float? = null, // trung bình jank% (nếu đo được)
        // v4.2 — lag về sau trận (mượt đầu → lag sau)
        val hadLateLag: Boolean = false, // FRAME/THERMAL sau phút thứ 8
        val recoverStuckTriggered: Boolean = false, // AI đã phải phục hồi lag kẹt
        val strategyUsed: Int = 0 // 0..3 chiến lược phiên này
    )

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
            val newEma = if (sessionCount <= 1) ramAtIssue.toFloat()
                else oldEma * (1 - ALPHA) + ramAtIssue * ALPHA
            editor.putFloat(key(scope, "ram_threshold_ema"), newEma)
        }

        val oldCleanup = p.getFloat(key(scope, "cleanup_count_ema"), result.cleanupCountUsed.toFloat())
        val cleanupSample = if (result.hadEarlyMemoryBottleneck) {
            result.cleanupCountUsed + 1f
        } else {
            result.cleanupCountUsed - 0.3f // giảm chậm hơn tăng - thận trọng hơn
        }
        editor.putFloat(key(scope, "cleanup_count_ema"), oldCleanup * (1 - ALPHA) + cleanupSample * ALPHA)

        val oldThermalRate = p.getFloat(key(scope, "thermal_rate_ema"), if (result.hadThermalBottleneck) 1f else 0f)
        val thermalSample = if (result.hadThermalBottleneck) 1f else 0f
        // Bất đối xứng có chủ đích: "nhớ" nhanh (ALPHA=0.3) khi phát hiện nóng, nhưng
        // "quên" chậm (ALPHA_THERMAL_DOWN=0.12) khi mát trở lại - xem SỬA LỖI QUAN TRỌNG
        // ở đầu file, tránh vòng lặp tự nới lỏng an toàn quá nhanh.
        val thermalAlpha = if (thermalSample > oldThermalRate) ALPHA else ALPHA_THERMAL_DOWN
        val newThermalRate = if (sessionCount <= 1) thermalSample
            else oldThermalRate * (1 - thermalAlpha) + thermalSample * thermalAlpha
        editor.putFloat(key(scope, "thermal_rate_ema"), newThermalRate)

        // v2.1: học tỉ lệ FRAME bottleneck + avg jank để nhận diện game dễ lag
        val oldFrameRate = p.getFloat(key(scope, "frame_rate_ema"), if (result.hadFrameBottleneck) 1f else 0f)
        val frameSample = if (result.hadFrameBottleneck) 1f else 0f
        val frameAlpha = if (frameSample > oldFrameRate) ALPHA else ALPHA_THERMAL_DOWN // nhớ lag nhanh, quên chậm
        val newFrameRate = if (sessionCount <= 1) frameSample
            else oldFrameRate * (1 - frameAlpha) + frameSample * frameAlpha
        editor.putFloat(key(scope, "frame_rate_ema"), newFrameRate)

        result.avgJankPercent?.let { jank ->
            val oldJank = p.getFloat(key(scope, "avg_jank_ema"), jank)
            val newJank = if (sessionCount <= 1) jank else oldJank * (1 - ALPHA) + jank * ALPHA
            editor.putFloat(key(scope, "avg_jank_ema"), newJank)
        }

        // v4.2: học "lag về sau" — nhớ nhanh, quên chậm (tránh càng chơi càng nóng/lag)
        val lateSample = if (result.hadLateLag || result.recoverStuckTriggered) 1f else 0f
        val oldLate = p.getFloat(key(scope, "late_lag_rate_ema"), lateSample)
        val lateAlpha = if (lateSample > oldLate) ALPHA else ALPHA_THERMAL_DOWN
        val newLate = if (sessionCount <= 1) lateSample
            else oldLate * (1 - lateAlpha) + lateSample * lateAlpha
        editor.putFloat(key(scope, "late_lag_rate_ema"), newLate)

        // Phiên mượt (không frame lag, không nhiệt, không late lag) → tăng điểm ổn định
        val smoothSample = if (!result.hadFrameBottleneck && !result.hadThermalBottleneck &&
            !result.hadLateLag && !result.recoverStuckTriggered
        ) 1f else 0f
        val oldSmooth = p.getFloat(key(scope, "smooth_rate_ema"), smoothSample)
        val smoothAlpha = if (smoothSample > oldSmooth) ALPHA else ALPHA_THERMAL_DOWN
        val newSmooth = if (sessionCount <= 1) smoothSample
            else oldSmooth * (1 - smoothAlpha) + smoothSample * smoothAlpha
        editor.putFloat(key(scope, "smooth_rate_ema"), newSmooth)

        // v4.5: điểm chiến lược (0..1) — mượt cao, lag thấp; luôn cập nhật, không dừng học
        val strat = result.strategyUsed.coerceIn(0, 3)
        val outcome = when {
            result.recoverStuckTriggered || result.hadLateLag -> 0.05f
            result.hadThermalBottleneck && result.hadFrameBottleneck -> 0.15f
            result.hadThermalBottleneck || result.hadFrameBottleneck -> 0.4f
            else -> 0.95f // phiên mượt
        }
        val sk = key(scope, "strat_${strat}_score")
        val oldScore = p.getFloat(sk, 0.5f)
        val nStrat = p.getInt(key(scope, "strat_${strat}_n"), 0) + 1
        // Học mãi: alpha giảm dần nhưng sàn 0.12 — trận thứ 50 vẫn còn chỉnh
        val a = maxOf(0.12f, ALPHA / (1f + nStrat * 0.08f))
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

            return blended >= 0.4f
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * v2.1 — Độ nhạy lag đã học (0.0 = bình thường, 1.0 = rất dễ lag).
     * Dùng để AdaptiveEngine / HudOverlayService phản ứng sớm hơn với game có tiền sử
     * frame-time xấu. Trộn 3 tầng giống các hàm học khác.
     */
    fun getLearnedLagSensitivity(context: Context, gamePackage: String): Float {
        return try {
            val p = prefs(context)
            val deviceSessions = p.getInt(key(DEVICE_KEY, "sessions"), 0)
            val deviceWeight = trustWeight(deviceSessions)
            val deviceRate = p.getFloat(key(DEVICE_KEY, "frame_rate_ema"), 0f)
            val level2 = deviceRate * deviceWeight

            val gameSessions = p.getInt(key(gamePackage, "sessions"), 0)
            val gameWeight = trustWeight(gameSessions)
            val gameRate = p.getFloat(key(gamePackage, "frame_rate_ema"), level2)
            val blended = level2 * (1 - gameWeight) + gameRate * gameWeight

            // Kết hợp thêm avg jank nếu có
            val avgJank = p.getFloat(key(gamePackage, "avg_jank_ema"), -1f)
            val jankBoost = if (avgJank > 0f) (avgJank / 25f).coerceIn(0f, 0.4f) else 0f
            (blended + jankBoost).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            0f
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


    /** Gợi ý ngắn cho UI: AI đã học bao nhiêu + hướng tối ưu trận tới. */
    fun getLearningHint(context: Context, gamePackage: String): String {
        return try {
            val n = getSessionCount(context, gamePackage)
            if (n <= 0) return "AI chưa có dữ liệu game này — chơi ≥1 trận để học"
            val skipFp = getLearnedSkipFixedPerf(context, gamePackage)
            val thermal = getLearnedThermalRisk(context, gamePackage)
            val sens = getLearnedLagSensitivity(context, gamePackage)
            val parts = mutableListOf("AI đã học $n trận")
            if (skipFp) parts += "tránh Fixed Perf (hay lag về sau)"
            if (thermal) parts += "chống nóng sớm"
            if (sens >= 0.35f) parts += "nhạy frame cao"
            if (parts.size == 1) parts += "ổn định dần"
            parts.joinToString(" · ")
        } catch (_: Throwable) {
            "AI đang học"
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
     * Chọn chiến lược cho trận tới.
     * - Nếu gần đây vẫn lag → khám phá chiến lược khác (epsilon)
     * - Nếu đã mượt → khai thác chiến lược điểm cao nhất
     * Học không bao giờ "đóng băng": luôn cập nhật điểm sau mỗi trận.
     */
    fun pickStrategy(context: Context, gamePackage: String): Int {
        return try {
            val p = prefs(context)
            migrateIfNeeded(p)
            val sessions = p.getInt(key(gamePackage, "sessions"), 0)
            val late = p.getFloat(key(gamePackage, "late_lag_rate_ema"), 0f)
            val smooth = p.getFloat(key(gamePackage, "smooth_rate_ema"), 0.5f)
            val last = p.getInt(key(gamePackage, "last_strategy"), Strategy.BALANCED)

            // Điểm 4 chiến lược (game → fallback device)
            fun score(id: Int): Float {
                val g = p.getFloat(key(gamePackage, "strat_${id}_score"), -1f)
                if (g >= 0f) return g
                return p.getFloat(key(DEVICE_KEY, "strat_${id}_score"), 0.5f)
            }

            // Vẫn lag nhiều → buộc thử hướng khác so với lần trước
            val stillLaggy = late >= 0.28f || smooth < 0.45f
            if (stillLaggy && sessions >= 1) {
                // Chọn chiến lược điểm cao nhất KHÁC last; nếu hòa → xoay vòng
                val order = listOf(Strategy.COOL_FIRST, Strategy.CLEAN_HEAVY, Strategy.LIGHT_AI, Strategy.BALANCED)
                    .filter { it != last }
                val best = order.maxByOrNull { score(it) } ?: Strategy.COOL_FIRST
                return best
            }

            // Đã khá mượt: dùng chiến lược điểm cao nhất, thỉnh thoảng (12%) thử cái khác để không kẹt cục bộ
            val all = listOf(Strategy.BALANCED, Strategy.COOL_FIRST, Strategy.CLEAN_HEAVY, Strategy.LIGHT_AI)
            val best = all.maxByOrNull { score(it) } ?: Strategy.BALANCED
            if (sessions >= 4 && smooth >= 0.7f && (sessions % 8 == 0)) {
                // Khám phá nhẹ 1 trận / 8 trận mượt
                return all.filter { it != best }.maxByOrNull { score(it) } ?: best
            }
            best
        } catch (_: Throwable) {
            Strategy.BALANCED
        }
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
