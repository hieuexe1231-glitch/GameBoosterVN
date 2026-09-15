package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Adaptive Performance Engine v1.6.
 * Mục tiêu: giữ frame-time ổn định và làm overhead của booster thấp.
 * Không ép xung, không ghi governor, không trim cache, không kill app trong trận.
 *
 * v1.5: dùng DeviceProfileHelper để máy yếu (RAM thấp) đo THƯA và NHẸ hơn hẳn - đặc biệt
 * bỏ hẳn việc đọc frame-jank (dumpsys gfxinfo) vì bản thân thao tác đo này tốn CPU/IO
 * tương đối cao trên máy yếu, trong khi chỉ phục vụ hiển thị chứ không phải hành động
 * tối ưu. "Ngân sách CPU" ít ỏi của máy yếu nên dồn cho hành động thật (dọn RAM khi cần).
 *
 * v1.6 - 3 BỔ SUNG THẬT, ĐỀU KHÔNG CẦN QUYỀN/API MỚI (dùng lại dữ liệu đã đọc sẵn):
 * 1) Booster Overhead Monitor: tự đo CPU%/RAM của CHÍNH booster (BoosterOverheadMonitor,
 *    đọc /proc/self/stat - không cần Shizuku). Nếu booster tự chiếm nhiều tài nguyên, tự
 *    kéo dài chu kỳ đo để giảm chính overhead đó - "app tăng tốc không được tự gây lag".
 * 2) Tín hiệu I/O wait: tận dụng lại field thứ 5 của /proc/stat (vốn đã đọc để tính CPU
 *    load, không tốn thêm chi phí đọc file) để phát hiện máy đang nghẽn vì chờ lưu trữ
 *    (đọc asset/shader cache...) chứ không phải vì CPU/GPU thật sự bận.
 * 3) Dự đoán nhiệt độ bằng NGOẠI SUY TUYẾN TÍNH đơn giản (tốc độ tăng °C/phút hiện tại,
 *    chiếu tới ngưỡng nguy hiểm) - gọi đúng tên là ngoại suy toán học, KHÔNG gọi là "AI dự
 *    đoán" để tránh cường điệu; đây chỉ là phép chia đơn giản, không phải mô hình học máy.
 */
class AdaptiveGameEngine(private val context: Context) {
    private val isLowRam = DeviceProfileHelper.isLowRamDevice(context)
    private val minInterval = DeviceProfileHelper.minPollingIntervalMs(context)
    private val skipFrameJank = DeviceProfileHelper.shouldSkipFrameJankSampling(context)
    enum class Bottleneck(val label: String) {
        NONE("ỔN ĐỊNH"), CPU("CPU NGHẼN"), THERMAL("NHIỆT CAO"), MEMORY("RAM ÁP LỰC"),
        FRAME("FRAME-TIME XẤU"), IO_STORAGE("CHỜ LƯU TRỮ"), NETWORK("MẠNG"), UNKNOWN("ĐANG ĐO")
    }

    data class Snapshot(
        val cpuLoadPercent: Int,
        val ramUsedPercent: Int,
        val temperatureC: Float?,
        val cpuFreqPercent: Int,
        val frameJankPercent: Float?,
        val bottleneck: Bottleneck,
        val suggestedIntervalMs: Long,
        val thermalStatus: Int?, // PowerManager.THERMAL_STATUS_* chính thức, null nếu máy không hỗ trợ
        val isEmergency: Boolean, // vượt ngưỡng nghiêm trọng - HudOverlayService bỏ qua độ trễ chờ mẫu để phản ứng ngay
        val ioWaitPercent: Int, // % thời gian CPU rảnh vì đang chờ lưu trữ (I/O wait chuẩn Linux)
        val boosterCpuPercent: Float, // CPU% của CHÍNH booster - overhead thật, không đoán
        val boosterRamKb: Int, // RAM (PSS) của CHÍNH booster
        val minutesUntilThermalRisk: Float? // ngoại suy tuyến tính đơn giản, null nếu nhiệt không tăng hoặc chưa đủ dữ liệu
    )

    private var lastCpuTotal = -1L
    private var lastCpuIdle = -1L
    private var lastCpuIowait = -1L
    private var lastFrameAt = 0L
    private var lastFrameTotal = -1L
    private var lastFrameJank = -1L
    private var lastFrameJankPct: Float? = null
    private var lastTempAt = 0L
    private var cachedTemp: Float? = null
    private var sampleCount = 0
    private var lastBottleneck = Bottleneck.UNKNOWN
    private var stableSamples = 0
    // Phát hiện XU HƯỚNG nhiệt tăng dần (42→45→48→51°C) thay vì chỉ chờ vượt ngưỡng cố
    // định - đúng tinh thần "AI phải hiểu nhiệt độ": nhiệt tích tụ dần trong game nặng,
    // đợi tới ngưỡng cứng mới phản ứng là đã trễ.
    private var lastTempValue: Float? = null
    private var lastTempValueAt = 0L
    private var risingTempStreak = 0
    // v1.25: phát hiện throttling THẬT qua tỉ lệ xung CPU tụt sâu kéo dài - một số hãng
    // máy không báo đúng/đủ nhanh qua PowerManager.getCurrentThermalStatus() (tín hiệu
    // chính thức, dùng ở rawBottleneck bên dưới), nhưng tỉ lệ xung hiện tại/xung tối đa
    // (freqPct - đã đọc sẵn mỗi mẫu, không tốn thêm chi phí) là bằng chứng CỨNG không thể
    // giả: SoC tự hạ xung rất sâu trong khi CPU vẫn đang thực sự bận (không phải máy đang
    // rảnh) chỉ xảy ra khi throttling thật đang diễn ra.
    private var lowFreqStreak = 0
    // Overhead của chính booster - đo thưa (60s/lần, giống cách đo nhiệt) vì bản thân
    // ActivityManager.getProcessMemoryInfo() là 1 lệnh Binder, không nên gọi liên tục.
    private var lastOverheadAt = 0L
    private var cachedOverhead = BoosterOverheadMonitor.Overhead(0f, 0)

    fun sample(gamePackage: String?): Snapshot {
        val (cpu, iowait) = readCpuLoadAndIowait()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ram = if (mem.totalMem > 0) {
            ((1.0 - mem.availMem.toDouble() / mem.totalMem) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        val now = SystemClock.elapsedRealtime()
        if (now - lastTempAt >= 30_000L || lastTempAt == 0L) {
            cachedTemp = TemperatureHelper.getCpuTemperatureC()
                ?: TemperatureHelper.getBatteryTemperatureC(context)
            lastTempAt = now
        }
        val temp = cachedTemp
        // Xu hướng + dự đoán nhiệt: so với lần đo trước, nếu tăng >=1.5°C liên tục nhiều
        // lần -> cảnh báo SỚM dù chưa chạm ngưỡng tuyệt đối (42→45→48→51°C). Đồng thời
        // ngoại suy tuyến tính đơn giản: tốc độ tăng hiện tại (°C/phút) chiếu tới ngưỡng
        // 45°C còn bao nhiêu phút - CHỈ là phép chia, không phải mô hình dự đoán AI.
        var minutesUntilThermalRisk: Float? = null
        if (temp != null) {
            val prev = lastTempValue
            val prevAt = lastTempValueAt
            risingTempStreak = if (prev != null && temp - prev >= 1.5f) risingTempStreak + 1 else 0
            if (prev != null && prevAt > 0 && temp > prev && now > prevAt) {
                val minutesElapsed = (now - prevAt) / 60_000f
                val ratePerMinute = (temp - prev) / minutesElapsed.coerceAtLeast(0.05f)
                if (ratePerMinute > 0.1f && temp < 45f) {
                    minutesUntilThermalRisk = ((45f - temp) / ratePerMinute).coerceIn(0f, 999f)
                }
            }
            lastTempValue = temp
            lastTempValueAt = now
        }
        // Tín hiệu nhiệt CHÍNH THỨC của Android (PowerManager) - hệ thống tự đánh giá dựa
        // trên đặc tính tản nhiệt thật của từng máy, đáng tin hơn một ngưỡng độ C cố định.
        val thermalStatus = TemperatureHelper.getThermalStatus(context)
        val freq = CpuFrequencyHelper.getFrequency()
        val freqPct = if (freq != null && freq.maxGhz > 0f) {
            (freq.currentGhz / freq.maxGhz * 100f).toInt().coerceIn(0, 100)
        } else 0

        // Booster Overhead Monitor - đo thưa (60s/lần).
        if (now - lastOverheadAt >= 60_000L || lastOverheadAt == 0L) {
            cachedOverhead = BoosterOverheadMonitor.sample(context)
            lastOverheadAt = now
        }
        val overhead = cachedOverhead

        // Cập nhật streak throttle qua xung CPU - chỉ tính "nghi ngờ" khi CPU ĐANG THỰC SỰ
        // BẬN (>=40%, tức máy có việc để làm) mà xung lại tụt rất sâu (<40% xung tối đa) -
        // loại trừ trường hợp máy đang rảnh tự hạ xung để tiết kiệm pin (bình thường, không
        // phải throttling).
        lowFreqStreak = if (freqPct in 1..39 && cpu >= 40) lowFreqStreak + 1 else 0

        var frameJank: Float? = lastFrameJankPct
        // dumpsys gfxinfo chỉ 1 lần/phút, tránh tạo overhead đáng kể trong trận.
        // Máy yếu: bỏ hẳn bước đo này (xem ghi chú v1.5 ở đầu file).
        if (!skipFrameJank && gamePackage != null && (now - lastFrameAt >= 60_000L || lastFrameAt == 0L)) {
            FrameStatsHelper.sample(gamePackage)?.let { r ->
                if (lastFrameTotal >= 0 && r.totalFrames >= lastFrameTotal && r.jankyFrames >= lastFrameJank) {
                    val df = r.totalFrames - lastFrameTotal
                    val dj = r.jankyFrames - lastFrameJank
                    frameJank = if (df > 0) dj * 100f / df else r.jankPercent
                } else {
                    frameJank = r.jankPercent
                }
                lastFrameTotal = r.totalFrames
                lastFrameJank = r.jankyFrames
                lastFrameJankPct = frameJank
            }
            lastFrameAt = now
        }

        sampleCount++
        // Gán sang biến bất biến (val) trước khi so sánh - "frameJank" là var bị gán lại
        // bên trong lambda .let{} phía trên, Kotlin không tự ép kiểu an toàn được ở đây
        // (đây là lỗi biên dịch thật, không liên quan tới máy chạy app).
        val frameJankFinal = frameJank
        // Máy yếu: hạ ngưỡng RAM báo động xuống sớm hơn (88% thay vì 93%) - RAM tổng đã
        // ít, đợi tới 93% dễ để hệ thống tự kill tiến trình game trước khi kịp dọn.
        // AI học trên máy: trộn mượt giữa mặc định này với kinh nghiệm đã học (xem
        // LearningProfileHelper). Không còn chờ "đủ N phiên" cứng nhắc, độ tin cậy tăng
        // dần tự nhiên theo số phiên đã chơi game này trên đúng máy này.
        val defaultThreshold = if (isLowRam) 88 else 93
        val memoryThreshold = if (gamePackage != null) {
            LearningProfileHelper.getLearnedMemoryThreshold(context, gamePackage, defaultThreshold)
        } else defaultThreshold
        val rawBottleneck = when {
            // THERMAL xét cả 3 nguồn: ngưỡng độ C cố định (dự phòng), tín hiệu chính thức
            // Android (đáng tin hơn - theo đúng đặc tính máy), và xu hướng tăng dần.
            temp != null && temp >= 45f -> Bottleneck.THERMAL
            thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Bottleneck.THERMAL
            risingTempStreak >= 3 -> Bottleneck.THERMAL
            // v1.25: xung CPU tụt sâu kéo dài trong khi máy đang bận - bằng chứng cứng của
            // throttling thật, xử lý như THERMAL (đúng bản chất nguyên nhân) dù hãng máy
            // có báo thermalStatus hay không.
            lowFreqStreak >= 3 -> Bottleneck.THERMAL
            ram >= memoryThreshold -> Bottleneck.MEMORY
            // I/O wait cao (>=20%) trong khi CPU không thực sự bận (<70%) -> máy đang
            // chờ đọc/ghi lưu trữ (asset, shader cache...), không phải CPU/GPU nghẽn thật.
            // Kiểm tra sau MEMORY/THERMAL vì 2 nguyên nhân đó ưu tiên xử lý hơn.
            iowait >= 20 && cpu < 70 -> Bottleneck.IO_STORAGE
            frameJankFinal != null && frameJankFinal >= 10f && cpu >= 82 -> Bottleneck.FRAME
            cpu >= 90 && freqPct >= 75 -> Bottleneck.CPU
            frameJankFinal != null && frameJankFinal >= 12f -> Bottleneck.FRAME
            else -> Bottleneck.NONE
        }

        // Mức KHẨN CẤP: tín hiệu quá rõ ràng và nguy hiểm tới mức không cần chờ đủ 2 mẫu
        // (hysteresis) để xác nhận nữa - phản ứng ngay lập tức. Chỉ 2 trường hợp: Android
        // tự báo SEVERE/CRITICAL trở lên (hệ thống ĐÃ đang giảm hiệu năng thật), hoặc giật
        // khung hình quá nặng (>=25%, rõ ràng không phải nhiễu đo).
        val isEmergency = (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) ||
            (frameJankFinal != null && frameJankFinal >= 25f)

        // Hysteresis (độ trễ chống dao động): chỉ đổi trạng thái sau 2 mẫu liên tiếp -
        // NGOẠI TRỪ khi isEmergency=true thì phản ứng ngay, không chờ (xem ghi chú trên).
        if (rawBottleneck == lastBottleneck) stableSamples++ else {
            lastBottleneck = rawBottleneck
            stableSamples = 1
        }
        val bottleneck = if (isEmergency || stableSamples >= 2) rawBottleneck else Bottleneck.UNKNOWN

        // Khi ổn định lâu, polling thưa hơn để booster gần như không tranh CPU với game.
        // Máy yếu luôn có sàn (minInterval) cao hơn máy thường, kể cả lúc đang nghẽn.
        // BOOSTER OVERHEAD MONITOR: nếu CHÍNH booster đang chiếm CPU nhiều bất thường
        // (>=3%, tức đã gấp nhiều lần mức nền ~0.5-1% thường thấy), tự nới thêm chu kỳ đo
        // để giảm bớt chính overhead đó - "app tăng tốc không được tự gây lag".
        val overheadPenaltyMs = if (overhead.cpuPercent >= 3f) 10_000L else 0L
        val interval = (when (bottleneck) {
            Bottleneck.THERMAL -> 20_000L
            Bottleneck.FRAME, Bottleneck.CPU, Bottleneck.IO_STORAGE -> 15_000L
            Bottleneck.MEMORY -> 18_000L
            Bottleneck.UNKNOWN -> 12_000L
            else -> 15_000L
        } + overheadPenaltyMs).coerceAtLeast(minInterval)

        return Snapshot(
            cpu, ram, temp, freqPct, frameJank, bottleneck, interval, thermalStatus, isEmergency,
            iowait, overhead.cpuPercent, overhead.ramKb, minutesUntilThermalRisk
        )
    }

    /** Đọc /proc/stat MỘT LẦN, tính cả CPU load% (đã có từ trước) LẪN I/O wait% (mới) từ
     * cùng 1 lần đọc file - không tốn thêm chi phí I/O so với bản trước. */
    private fun readCpuLoadAndIowait(): Pair<Int, Int> {
        return try {
            val line = java.io.File("/proc/stat").useLines { it.firstOrNull { l -> l.startsWith("cpu ") } } ?: return 0 to 0
            val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 5) return 0 to 0
            // Thứ tự chuẩn /proc/stat: user nice system idle iowait irq softirq...
            val iowaitTicks = values[4]
            val idle = values[3] + iowaitTicks
            val total = values.sum()
            val pt = lastCpuTotal
            val pi = lastCpuIdle
            val piow = lastCpuIowait
            lastCpuTotal = total
            lastCpuIdle = idle
            lastCpuIowait = iowaitTicks
            if (pt < 0 || total <= pt) return 0 to 0
            val td = total - pt
            val id = idle - pi
            val iow = iowaitTicks - piow
            if (td <= 0) return 0 to 0
            val cpuPct = ((1.0 - id.toDouble() / td.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            val iowaitPct = (iow.toDouble() / td.toDouble() * 100.0).toInt().coerceIn(0, 100)
            cpuPct to iowaitPct
        } catch (_: Throwable) { 0 to 0 }
    }
}
