package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context

/**
 * Hồ sơ máy THẬT - dùng ActivityManager.isLowRamDevice() (API chính thức Android,
 * do nhà sản xuất/ROM khai báo dựa trên RAM tổng + ngưỡng cấu hình, KHÔNG phải mình tự
 * đoán qua "RAM < X GB" vì mỗi ROM/hãng có ngưỡng "máy yếu" khác nhau).
 *
 * Mục tiêu dùng hồ sơ này: máy yếu cần CHIẾN LƯỢC KHÁC, không phải "giống máy mạnh nhưng
 * đo nhiều hơn":
 * 1) Dọn RAM mạnh tay hơn trước khi vào game (đây là đòn bẩy #1 cho máy yếu - máy yếu
 *    thường chỉ có 3-4GB RAM, hệ thống dễ phải kill lại tiến trình game giữa trận nếu
 *    thiếu RAM).
 * 2) Đo đạc (CPU/RAM/nhiệt/frame-jank) THƯA hơn và NHẸ hơn - bản thân việc đo cũng tốn
 *    CPU, mà máy yếu vốn đã ít CPU dư để "cho" ứng dụng nền dùng. Ưu tiên hành động tối
 *    ưu thật hơn là theo dõi số liệu chi tiết.
 * 3) Thận trọng hơn với Fixed Performance Mode: chế độ này khoá xung ở mức ổn định,
 *    tốt cho frame-time nhưng cũng có thể giữ nhiệt cao lâu hơn - máy yếu thường có tản
 *    nhiệt kém hơn máy cao cấp nên hạ ngưỡng an toàn nhiệt xuống thấp hơn.
 */
object DeviceProfileHelper {

    fun isLowRamDevice(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice
    } catch (_: Throwable) { false }

    /** Số app nền tối đa nên dừng trước khi vào game. Máy yếu cần giải phóng nhiều RAM
     * hơn vì tổng RAM sẵn có đã thấp, ngược lại máy mạnh không cần dọn quá tay vì dễ gây
     * mất trạng thái các app khác một cách không cần thiết. */
    fun recommendedCleanupCount(context: Context): Int = if (isLowRamDevice(context)) 5 else 2

    /** Ngưỡng nhiệt (độ C) để coi là "nên ưu tiên an toàn/tản nhiệt hơn là hiệu năng khoá
     * xung". Máy yếu hạ thấp hơn vì tản nhiệt vật lý thường kém hơn. */
    fun thermalSafeThresholdC(context: Context): Float = if (isLowRamDevice(context)) 40f else 42f

    /** Khoảng polling tối thiểu (ms) khi đo thích ứng trong trận. Máy yếu cần polling
     * thưa hơn để việc "đo" không tranh CPU với game. */
    fun minPollingIntervalMs(context: Context): Long = if (isLowRamDevice(context)) 20_000L else 12_000L

    /** Máy yếu: bỏ qua việc đọc frame-jank (dumpsys gfxinfo) - thao tác này tự nó tốn
     * CPU/IO đáng kể hơn tương đối so với sức máy yếu, mà lợi ích chỉ là hiển thị số liệu
     * chứ không phải hành động tối ưu. Ưu tiên dồn "ngân sách CPU" của booster cho hành
     * động thật (dọn RAM, giảm nền) thay vì đo chi tiết. */
    fun shouldSkipFrameJankSampling(context: Context): Boolean = isLowRamDevice(context)
}
