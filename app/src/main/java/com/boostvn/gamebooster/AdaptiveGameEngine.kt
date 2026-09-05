package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock

/**
 * Adaptive Performance Engine v1.4.
 * Mục tiêu: giữ frame-time ổn định và làm overhead của booster thấp.
 * Không ép xung, không ghi governor, không trim cache, không kill app trong trận.
 */
class AdaptiveGameEngine(private val context: Context) {
    enum class Bottleneck(val label: String) {
        NONE("ỔN ĐỊNH"), CPU("CPU NGHẼN"), THERMAL("NHIỆT CAO"), MEMORY("RAM ÁP LỰC"),
        FRAME("FRAME-TIME XẤU"), NETWORK("MẠNG"), UNKNOWN("ĐANG ĐO")
    }

    data class Snapshot(
        val cpuLoadPercent: Int,
        val ramUsedPercent: Int,
        val temperatureC: Float?,
        val cpuFreqPercent: Int,
        val frameJankPercent: Float?,
        val bottleneck: Bottleneck,
        val suggestedIntervalMs: Long
    )

    private var lastCpuTotal = -1L
    private var lastCpuIdle = -1L
    private var lastFrameAt = 0L
    private var lastFrameTotal = -1L
    private var lastFrameJank = -1L
    private var lastFrameJankPct: Float? = null
    private var lastTempAt = 0L
    private var cachedTemp: Float? = null
    private var sampleCount = 0
    private var lastBottleneck = Bottleneck.UNKNOWN
    private var stableSamples = 0

    fun sample(gamePackage: String?): Snapshot {
        val cpu = readCpuLoad()
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
        val freq = CpuFrequencyHelper.getFrequency()
        val freqPct = if (freq != null && freq.maxGhz > 0f) {
            (freq.currentGhz / freq.maxGhz * 100f).toInt().coerceIn(0, 100)
        } else 0

        var frameJank: Float? = lastFrameJankPct
        // dumpsys gfxinfo chỉ 1 lần/phút, tránh tạo overhead đáng kể trong trận.
        if (gamePackage != null && (now - lastFrameAt >= 60_000L || lastFrameAt == 0L)) {
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
        val rawBottleneck = when {
            temp != null && temp >= 45f -> Bottleneck.THERMAL
            ram >= 93 -> Bottleneck.MEMORY
            frameJankFinal != null && frameJankFinal >= 10f && cpu >= 82 -> Bottleneck.FRAME
            cpu >= 90 && freqPct >= 75 -> Bottleneck.CPU
            frameJankFinal != null && frameJankFinal >= 12f -> Bottleneck.FRAME
            else -> Bottleneck.NONE
        }

        // Hysteresis (độ trễ chống dao động): chỉ đổi trạng thái sau 2 mẫu liên tiếp.
        if (rawBottleneck == lastBottleneck) stableSamples++ else {
            lastBottleneck = rawBottleneck
            stableSamples = 1
        }
        val bottleneck = if (stableSamples >= 2) rawBottleneck else Bottleneck.UNKNOWN

        // Khi ổn định lâu, polling thưa hơn để booster gần như không tranh CPU với game.
        val interval = when (bottleneck) {
            Bottleneck.THERMAL -> 20_000L
            Bottleneck.FRAME, Bottleneck.CPU -> 15_000L
            Bottleneck.MEMORY -> 18_000L
            Bottleneck.UNKNOWN -> 12_000L
            else -> 15_000L
        }

        return Snapshot(cpu, ram, temp, freqPct, frameJank, bottleneck, interval)
    }

    private fun readCpuLoad(): Int {
        return try {
            val line = java.io.File("/proc/stat").useLines { it.firstOrNull { l -> l.startsWith("cpu ") } } ?: return 0
            val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return 0
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.sum()
            val pt = lastCpuTotal
            val pi = lastCpuIdle
            lastCpuTotal = total
            lastCpuIdle = idle
            if (pt < 0 || total <= pt) return 0
            val td = total - pt
            val id = idle - pi
            if (td <= 0) return 0
            ((1.0 - id.toDouble() / td.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } catch (_: Throwable) { 0 }
    }
}
