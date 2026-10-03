package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Adaptive Performance Engine v2.0 — Extreme Smooth / AI Sensitive.
 * Mục tiêu: giữ frame-time ổn định, overhead booster thấp, nhận diện lag NHẠY BÉN hơn
 * để phản ứng sớm giữa trận (đặc biệt combat / phase chuyển đổi).
 *
 * NÂNG CẤP v2.0 (so với v1.6):
 * 1) FRAME detection nhạy hơn: ngưỡng jank hạ (8%/10% thay vì 10%/12%), thêm xu hướng
 *    jank tăng dần (risingJankStreak) để bắt lag "từ từ" trước khi nặng.
 * 2) Emergency lag sớm hơn: jank >= 18% (thay 25%) + CPU cao → phản ứng ngay, không chờ
 *    hysteresis 2 mẫu — fix cảm giác "lag giữa trận rồi mới xử lý".
 * 3) Nhiệt nhạy hơn: risingTempStreak >= 2 (thay 3) + ngoại suy vẫn giữ.
 * 4) Adaptive frame-jank sampling: khi đang không ổn định hoặc vừa phát hiện FRAME,
 *    đo gfxinfo thường xuyên hơn (30s) thay vì cố định 60s — bắt lag nhanh mà vẫn kiểm
 *    soát overhead.
 * 5) MEMORY/CPU ngưỡng hơi thận trọng hơn trên máy yếu + học từ LearningProfile.
 * 6) Giữ nguyên triết lý: không ép xung, không kill app trong combat, không trim cache
 *    nặng, ưu tiên hành động thật + giảm overhead chính booster.
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
        val thermalStatus: Int?,
        val isEmergency: Boolean,
        val ioWaitPercent: Int,
        val boosterCpuPercent: Float,
        val boosterRamKb: Int,
        val minutesUntilThermalRisk: Float?
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
    private var lastTempValue: Float? = null
    private var lastTempValueAt = 0L
    private var risingTempStreak = 0
    // v2.0: xu hướng jank tăng dần — bắt lag "từ từ" trước khi nặng
    private var lastJankValue: Float? = null
    private var risingJankStreak = 0
    private var lastOverheadAt = 0L
    private var cachedOverhead = BoosterOverheadMonitor.Overhead(0f, 0)
    // Khi vừa phát hiện FRAME hoặc không ổn định → đo jank thường xuyên hơn
    private var preferFasterFrameSample = false

    fun sample(gamePackage: String?): Snapshot {
        val (cpu, iowait) = readCpuLoadAndIowait()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ram = if (mem.totalMem > 0) {
            ((1.0 - mem.availMem.toDouble() / mem.totalMem) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        val now = SystemClock.elapsedRealtime()
        if (now - lastTempAt >= 25_000L || lastTempAt == 0L) { // hơi nhanh hơn 30s để nhạy nhiệt
            cachedTemp = TemperatureHelper.getCpuTemperatureC()
                ?: TemperatureHelper.getBatteryTemperatureC(context)
            lastTempAt = now
        }
        val temp = cachedTemp

        var minutesUntilThermalRisk: Float? = null
        if (temp != null) {
            val prev = lastTempValue
            val prevAt = lastTempValueAt
            // v2.0: nhạy hơn — tăng >= 1.2°C (thay 1.5) đã tính streak
            risingTempStreak = if (prev != null && temp - prev >= 1.2f) risingTempStreak + 1 else 0
            if (prev != null && prevAt > 0 && temp > prev && now > prevAt) {
                val minutesElapsed = (now - prevAt) / 60_000f
                val ratePerMinute = (temp - prev) / minutesElapsed.coerceAtLeast(0.05f)
                if (ratePerMinute > 0.08f && temp < 45f) {
                    minutesUntilThermalRisk = ((45f - temp) / ratePerMinute).coerceIn(0f, 999f)
                }
            }
            lastTempValue = temp
            lastTempValueAt = now
        }

        val thermalStatus = TemperatureHelper.getThermalStatus(context)
        val freq = CpuFrequencyHelper.getFrequency()
        val freqPct = if (freq != null && freq.maxGhz > 0f) {
            (freq.currentGhz / freq.maxGhz * 100f).toInt().coerceIn(0, 100)
        } else 0

        if (now - lastOverheadAt >= 60_000L || lastOverheadAt == 0L) {
            cachedOverhead = BoosterOverheadMonitor.sample(context)
            lastOverheadAt = now
        }
        val overhead = cachedOverhead

        var frameJank: Float? = lastFrameJankPct
        // v2.0 Adaptive frame sampling: 30s khi đang bất ổn / vừa FRAME, 60s khi ổn định
        val frameInterval = if (preferFasterFrameSample || lastBottleneck == Bottleneck.FRAME ||
            lastBottleneck == Bottleneck.UNKNOWN) 30_000L else 60_000L
        if (!skipFrameJank && gamePackage != null && (now - lastFrameAt >= frameInterval || lastFrameAt == 0L)) {
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

        // v2.0: theo dõi xu hướng jank tăng
        val fj = frameJank
        if (fj != null) {
            val prevJ = lastJankValue
            risingJankStreak = if (prevJ != null && fj - prevJ >= 2.5f) risingJankStreak + 1 else 0
            lastJankValue = fj
        }

        sampleCount++
        val frameJankFinal = frameJank

        val defaultThreshold = if (isLowRam) 86 else 91 // v2.0: hơi thấp hơn để nhạy RAM hơn
        val memoryThreshold = if (gamePackage != null) {
            LearningProfileHelper.getLearnedMemoryThreshold(context, gamePackage, defaultThreshold)
        } else defaultThreshold

        val rawBottleneck = when {
            // THERMAL — nhạy hơn
            temp != null && temp >= 44.5f -> Bottleneck.THERMAL
            thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Bottleneck.THERMAL
            risingTempStreak >= 2 -> Bottleneck.THERMAL // v2.0: 2 thay vì 3
            ram >= memoryThreshold -> Bottleneck.MEMORY
            iowait >= 18 && cpu < 72 -> Bottleneck.IO_STORAGE // hơi nhạy hơn
            // FRAME — nhạy bén hơn rất nhiều
            frameJankFinal != null && frameJankFinal >= 8f && cpu >= 78 -> Bottleneck.FRAME
            risingJankStreak >= 2 && frameJankFinal != null && frameJankFinal >= 6f -> Bottleneck.FRAME
            cpu >= 88 && freqPct >= 72 -> Bottleneck.CPU
            frameJankFinal != null && frameJankFinal >= 10f -> Bottleneck.FRAME
            else -> Bottleneck.NONE
        }

        // v2.0 Emergency sớm hơn để fix "lag giữa trận rồi mới xử lý"
        val isEmergency = (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) ||
            (frameJankFinal != null && frameJankFinal >= 18f) ||
            (frameJankFinal != null && frameJankFinal >= 14f && cpu >= 85) ||
            (risingJankStreak >= 3 && frameJankFinal != null && frameJankFinal >= 9f)

        if (rawBottleneck == lastBottleneck) stableSamples++ else {
            lastBottleneck = rawBottleneck
            stableSamples = 1
        }
        val bottleneck = if (isEmergency || stableSamples >= 2) rawBottleneck else Bottleneck.UNKNOWN

        // Cập nhật cờ đo jank nhanh hơn
        preferFasterFrameSample = bottleneck == Bottleneck.FRAME || bottleneck == Bottleneck.UNKNOWN ||
            isEmergency || risingJankStreak >= 1

        val overheadPenaltyMs = if (overhead.cpuPercent >= 2.5f) 8_000L else 0L // hơi sớm hơn
        val interval = (when (bottleneck) {
            Bottleneck.THERMAL -> 18_000L
            Bottleneck.FRAME, Bottleneck.CPU -> 12_000L // phản ứng nhanh hơn khi frame/CPU
            Bottleneck.IO_STORAGE -> 14_000L
            Bottleneck.MEMORY -> 16_000L
            Bottleneck.UNKNOWN -> 10_000L
            else -> 14_000L
        } + overheadPenaltyMs).coerceAtLeast(minInterval)

        return Snapshot(
            cpu, ram, temp, freqPct, frameJank, bottleneck, interval, thermalStatus, isEmergency,
            iowait, overhead.cpuPercent, overhead.ramKb, minutesUntilThermalRisk
        )
    }

    private fun readCpuLoadAndIowait(): Pair<Int, Int> {
        return try {
            val line = java.io.File("/proc/stat").useLines { it.firstOrNull { l -> l.startsWith("cpu ") } } ?: return 0 to 0
            val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 5) return 0 to 0
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
