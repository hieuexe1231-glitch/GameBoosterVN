package com.boostvn.gamebooster

import android.content.Context
import android.content.SharedPreferences

/**
 * "AI học trên máy" - THẬT THÀ VỀ BẢN CHẤT: đây là học thống kê nhẹ (EMA + Bayesian
 * shrinkage - hai kỹ thuật thống kê cổ điển, có công thức rõ ràng, không phải mạng nơ-ron
 * hay mô hình "hộp đen"). Không cường điệu để đúng tinh thần cả app: không bịa, không mẹo giả.
 *
 * VÌ SAO CẦN: các ngưỡng cố định (RAM 88%/93%, dọn 2-5 app...) là suy đoán CHUNG cho mọi
 * game/máy. Thực tế mỗi game ăn RAM khác nhau, mỗi máy có baseline RAM nền khác nhau. Học
 * riêng theo cặp (gamePackage, máy) cho ra ngưỡng ĐÚNG VỚI THỰC TẾ SỬ DỤNG thay vì đoán chung.
 *
 * CÁCH HOẠT ĐỘNG (v2 - sửa lỗi build "return trong expression body" + cải tiến cách học):
 * 1) Mỗi phiên chơi, HudOverlayService ghi lại: RAM% ngay trước lần nghẽn RAM THẬT SỰ đầu
 *    tiên (chỉ tính bottleneck loại MEMORY, KHÔNG tính nhiệt/CPU/frame - tránh trộn tín
 *    hiệu không liên quan), và liệu lần dọn app trước trận có đủ hay không.
 * 2) Cuối phiên, recordSessionEnd() cập nhật EMA cho gamePackage đó VÀ đồng thời cập nhật
 *    một bộ số liệu "chung của máy" (gộp mọi game đã chơi) - kinh nghiệm từ các game đã
 *    chơi trước là điểm khởi đầu tốt hơn hằng số mặc định chung chung, ngay cả khi game
 *    hiện tại hoàn toàn mới chưa từng chơi.
 * 3) Khi đọc giá trị đã học, KHÔNG dùng ngưỡng cứng "đủ N phiên mới tin" (bước nhảy đột
 *    ngột dễ bị 1 phiên may/rủi làm lệch). Thay vào đó dùng trọng số tin cậy TĂNG DẦN theo
 *    số phiên (Bayesian shrinkage: weight = sessions / (sessions + K)), trộn mượt giữa 3
 *    tầng theo độ tin cậy tăng dần: mặc định tĩnh -> kinh nghiệm chung của máy -> kinh
 *    nghiệm riêng của đúng game này.
 * 4) Mọi giá trị học được đều bị kẹp (coerceIn) trong khoảng an toàn đã kiểm chứng - "học"
 *    không bao giờ được đẩy app ra khỏi vùng hành vi an toàn.
 * 5) SỬA LỖI QUAN TRỌNG (do người dùng phát hiện: "lúc đầu mượt, vài ngày sau lag trở
 *    lại"): bản trước có một vòng lặp tự phá hoại - khi AI dọn RAM đủ tốt, sẽ không còn
 *    thấy nghẽn nữa, dẫn tới học lầm là "không cần dọn nhiều vậy" rồi tự HẠ số app cần dọn
 *    xuống mỗi phiên yên ổn; và ngưỡng RAM báo động cũng tự TRÔI LÊN cao hơn theo thời gian
 *    (vì hiếm khi nghẽn RAM ở mức thấp, chỉ còn ghi nhận được những lần nghẽn ở mức cao dần
 *    do đã được dọn tốt). Cả 2 xu hướng này đều làm app "tự nới lỏng an toàn" sau vài ngày
 *    hoạt động tốt - cho tới khi bảo vệ đã bị nới lỏng đủ nhiều thì lag quay lại, đúng như
 *    người dùng mô tả. FIX: giá trị học được giờ bị kẹp MỘT CHIỀU so với mặc định tĩnh -
 *    ngưỡng RAM CHỈ được phép thấp hơn (thận trọng hơn) mặc định, KHÔNG BAO GIỜ cao hơn; số
 *    app cần dọn CHỈ được phép nhiều hơn (thận trọng hơn) mặc định, KHÔNG BAO GIỜ ít hơn.
 *    Nói cách khác: học chỉ có thể làm app CẨN THẬN HƠN theo thời gian, không bao giờ làm
 *    app lơ là hơn mức đã biết là an toàn - "AI" sẽ ngày càng mượt hơn chứ không tự nới
 *    lỏng rồi lag trở lại. Ngoài ra thermal_rate giờ "quên" chậm hơn "nhớ" (xem
 *    ALPHA_THERMAL_DOWN) - game từng làm nóng máy sẽ vẫn được coi là rủi ro thêm một thời
 *    gian, không bị quên ngay chỉ sau vài phiên mát mẻ.
 * 6) SỬA LỖI QUAN TRỌNG THỨ 2 (nguyên nhân "chơi LQ vẫn lag" dù đã tối ưu nhiều bước):
 *    trước đây thermal_rate được học từ Bottleneck.THERMAL - kích hoạt ở mức MODERATE, tức
 *    mức ẤM BÌNH THƯỜNG của bất kỳ game nặng GPU nào (như LQ, đặc biệt lúc giao tranh).
 *    Hậu quả: chỉ cần ấm lên bình thường sau ~75 giây (không phải do Fixed Performance Mode
 *    gây ra) đã bị học nhầm thành "game này hay nóng máy", khiến các trận SAU ĐÓ AI né hẳn
 *    Fixed Performance Mode cho game đó NGAY TỪ ĐẦU - tự làm giảm chính lợi ích mà nó nên
 *    mang lại. Giờ chỉ học "nóng máy" khi thermal status đạt SEVERE trở lên (hệ thống THẬT
 *    SỰ đang giảm hiệu năng vì nhiệt). Vì dữ liệu học kiểu cũ đã bị lệch theo định nghĩa
 *    sai, có thêm bước "di trú": tự xoá riêng phần thermal_rate đã học (giữ nguyên RAM/số
 *    app dọn, không liên quan tới lỗi này) một lần duy nhất khi app cập nhật lên bản này.
 *
 * Lưu bằng SharedPreferences - không cần mạng, không rời khỏi máy, không có quyền riêng tư
 * nào bị đụng tới (chỉ là số liệu hiệu năng ẩn danh của chính máy đó).
 */
object LearningProfileHelper {
    private const val PREFS_NAME = "booster_learning_profile"
    private const val SCHEMA_VERSION = 2
    private const val SCHEMA_VERSION_KEY = "schema_version"
    private const val ALPHA = 0.3f // trọng số cho mẫu mới trong EMA - đủ nhạy để bắt kịp
                                     // thay đổi thực tế (cập nhật app, đổi ROM...) nhưng
                                     // không quá nhạy để 1 phiên bất thường làm lệch hẳn.
    private const val ALPHA_THERMAL_DOWN = 0.12f // "quên" độ nóng chậm hơn hẳn "nhớ" (0.3) -
                                                   // game từng làm nóng máy vẫn bị coi là
                                                   // rủi ro thêm nhiều phiên mát mẻ sau đó,
                                                   // tránh việc AI vội tin "đã hết nóng" rồi
                                                   // bật lại Fixed Performance Mode quá sớm.
    private const val MIN_SESSION_MS_TO_LEARN = 90_000L // phiên quá ngắn (mở rồi tắt ngay)
                                                          // không phải dữ liệu chơi thật.
    private const val SHRINKAGE_K = 3f // hằng số "K" trong weight = n/(n+K): sau đúng K
                                        // phiên, kinh nghiệm riêng của game được tin 50%;
                                        // càng nhiều phiên hơn càng được tin gần 100%.
    private const val DEVICE_KEY = "__device__" // khoá gộp kinh nghiệm chung của máy

    data class SessionResult(
        val durationMs: Long,
        val cleanupCountUsed: Int,
        val hadEarlyMemoryBottleneck: Boolean, // nghẽn RAM thật xảy ra trong vài phút đầu
        val ramPercentAtFirstBottleneck: Int?, // RAM% ngay trước lần nghẽn RAM đầu tiên (chỉ loại MEMORY)
        val hadThermalBottleneck: Boolean
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
                .apply()
        } catch (t: Throwable) {
        }
    }
}
