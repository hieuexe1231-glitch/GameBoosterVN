package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Adaptive Performance Engine v6 Raphael — phán đoán siêu nhanh, case tối ưu, chống lag ngược.
 * Đa tín hiệu (CPU/RAM/nhiệt/jank/IO) → 1 action; combat = STAY_SILENT tuyệt đối.
 * Học từ LearningProfileHelper (Raphael) để ngưỡng và chiến lược càng chơi càng khớp máy.
 */
class AdaptiveGameEngine(private val context: Context) {
    private val isLowRam = DeviceProfileHelper.isLowRamDevice(context)
    private val minInterval = DeviceProfileHelper.minPollingIntervalMs(context)
    private val skipFrameJank = DeviceProfileHelper.shouldSkipFrameJankSampling(context)

    enum class Bottleneck(val label: String) {
        NONE("ỔN ĐỊNH"), CPU("CPU NGHẼN"), THERMAL("NHIỆT CAO"), MEMORY("RAM ÁP LỰC"),
        FRAME("FRAME-TIME XẤU"), IO_STORAGE("CHỜ LƯU TRỮ"), NETWORK("MẠNG"), UNKNOWN("ĐANG ĐO")
    }

    /** Hành động AI khuyến nghị — HUD chỉ làm đúng 1 việc, có cooldown riêng. */
    enum class Action(val label: String) {
        NONE("Không làm gì"),
        STAY_SILENT("Im lặng (đang combat/GPU)"),
        DIM_SCREEN("Giảm sáng chống nóng"),
        DISABLE_FIXED_PERF("Tắt Fixed Perf (nóng)"),
        RESTRICT_BG("Hạn chế app nền (RAM)"),
        REENABLE_FIXED_PERF("Bật lại Fixed Perf (đã mát)"),
        /** Lag kéo dài nhiều mẫu liên tiếp — can thiệp nhẹ 1 lần để kéo về ổn định */
        RECOVER_STUCK_LAG("Phục hồi lag kéo dài")
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
        val minutesUntilThermalRisk: Float?,
        val recommendedAction: Action = Action.NONE,
        val confidence: Float = 0.5f // 0..1 — độ tin nhận diện
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
    /** Số mẫu liên tiếp FRAME/CPU/IO — lag kẹt không tự về */
    private var consecutiveLagStreak = 0
    private var lastOverheadAt = 0L
    private var cachedOverhead = BoosterOverheadMonitor.Overhead(0f, 0)
    // Khi vừa phát hiện FRAME hoặc không ổn định → đo jank thường xuyên hơn
    private var preferFasterFrameSample = false


    /** HUD gọi sau khi đã RECOVER_STUCK — streak giảm để không spam, vẫn học. */
    fun notifyRecoverApplied() {
        consecutiveLagStreak = maxOf(0, consecutiveLagStreak - 2)
        risingJankStreak = 0
        preferFasterFrameSample = false
    }

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
        // v4.7: gfxinfo tối thiểu 90s; bỏ hẳn nếu CPU đang cao (chính dumpsys gây khựng)
        val frameInterval = 90_000L
        val cpuBusy = cpu >= 75
        if (!skipFrameJank && !cpuBusy && gamePackage != null &&
            (now - lastFrameAt >= frameInterval || lastFrameAt == 0L)
        ) {
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
            risingJankStreak = if (prevJ != null && fj - prevJ >= 1.8f) risingJankStreak + 1 else 0
            lastJankValue = fj
        }

        sampleCount++
        val frameJankFinal = frameJank

        val defaultThreshold = if (isLowRam) 86 else 91 // v2.0: hơi thấp hơn để nhạy RAM hơn
        val memoryThreshold = if (gamePackage != null) {
            LearningProfileHelper.getLearnedMemoryThreshold(context, gamePackage, defaultThreshold)
        } else defaultThreshold

        // --- AI v5: đa tín hiệu + học theo game + ưu tiên ổn định ---
        val lagSens = if (gamePackage != null)
            LearningProfileHelper.getLearnedLagSensitivity(context, gamePackage) else 0.2f
        val thermalRiskGame = gamePackage != null &&
            LearningProfileHelper.getLearnedThermalRisk(context, gamePackage)
        val stabilityScore = if (gamePackage != null)
            LearningProfileHelper.getLearnedStabilityScore(context, gamePackage) else 0.5f

        // CombatGuard: nhận combat sớm hơn → STAY_SILENT tuyệt đối (chống lag ngược do shell)
        val gpuPressure = (cpu >= 72 && freqPct >= 65) ||
            (frameJankFinal != null && frameJankFinal >= 7f && cpu >= 65) ||
            (cpu >= 80) // CPU rất cao = combat / load nặng dù freq đọc lệch

        // Nhiệt: game hay nóng / lagSens cao → cửa nhiệt thấp hơn (phản ứng sớm)
        val thermalTempGate = when {
            thermalRiskGame && lagSens >= 0.45f -> 42.8f
            thermalRiskGame -> 43.2f
            lagSens >= 0.5f -> 43.8f
            else -> 44.5f
        }
        val thermalHit = (temp != null && temp >= thermalTempGate) ||
            (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) ||
            risingTempStreak >= 2

        val memoryHit = ram >= memoryThreshold
        // IO: nhạy hơn nếu đã học máy hay chờ lưu trữ
        val ioGate = if (lagSens >= 0.4f) 15 else 18
        val ioHit = iowait >= ioGate && cpu < 75
        val cpuHit = cpu >= 88 && freqPct >= 72
        val frameGate = when {
            lagSens >= 0.55f -> 7f
            lagSens >= 0.35f -> 9f
            else -> 11f
        }
        val frameHit = (frameJankFinal != null && frameJankFinal >= frameGate) ||
            (risingJankStreak >= 2 && frameJankFinal != null && frameJankFinal >= 6f)

        // Ưu tiên: THERMAL > MEMORY > IO > FRAME/GPU > CPU > NONE
        val rawBottleneck = when {
            thermalHit -> Bottleneck.THERMAL
            memoryHit -> Bottleneck.MEMORY
            ioHit -> Bottleneck.IO_STORAGE
            gpuPressure || frameHit -> Bottleneck.FRAME
            cpuHit -> Bottleneck.CPU
            else -> Bottleneck.NONE
        }

        // Emergency: chỉ tín hiệu hệ thống nặng — tránh false positive → lag ngược
        val isEmergency = (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) ||
            (temp != null && temp >= 47.5f) ||
            (cpu >= 97 && freqPct >= 90 && thermalHit)

        // Hysteresis thích ứng: game hay lag → xác nhận nhanh hơn; máy ổn → chậm hơn (ít dao động)
        val needStable = when {
            isEmergency -> 1
            lagSens >= 0.5f && (rawBottleneck == Bottleneck.FRAME || rawBottleneck == Bottleneck.THERMAL) -> 1
            stabilityScore >= 0.7f && rawBottleneck == Bottleneck.NONE -> 2
            else -> 2
        }
        if (rawBottleneck == lastBottleneck) stableSamples++ else {
            lastBottleneck = rawBottleneck
            stableSamples = 1
        }
        val bottleneck = if (stableSamples >= needStable) rawBottleneck else Bottleneck.UNKNOWN

        preferFasterFrameSample = false // không đo gfxinfo dày

        val confidence = when {
            isEmergency -> 0.95f
            bottleneck == Bottleneck.THERMAL && thermalStatus != null -> 0.9f
            bottleneck == Bottleneck.UNKNOWN -> 0.28f
            stableSamples >= 3 -> 0.88f
            stableSamples >= 2 -> 0.72f
            lagSens >= 0.45f && stableSamples >= 1 -> 0.65f
            else -> 0.5f
        }

        val isLagType = bottleneck == Bottleneck.FRAME || bottleneck == Bottleneck.CPU ||
            bottleneck == Bottleneck.IO_STORAGE
        if (isLagType) consecutiveLagStreak++ else if (bottleneck == Bottleneck.NONE) {
            consecutiveLagStreak = maxOf(0, consecutiveLagStreak - 1)
        }
        val prevJForSpike = lastJankValue
        val suddenJankSpike = frameJankFinal != null && (
            frameJankFinal >= 15f ||
            (prevJForSpike != null && frameJankFinal - prevJForSpike >= 5.5f) ||
            (risingJankStreak >= 1 && frameJankFinal >= 11f)
        )
        val risingHard = risingJankStreak >= 2 && frameJankFinal != null && frameJankFinal >= 9f
        val stuckNeed = when {
            suddenJankSpike -> 1
            lagSens >= 0.50f || risingHard -> 2
            lagSens >= 0.30f -> 2
            else -> 3
        }
        val stuckLag = isLagType && (consecutiveLagStreak >= stuckNeed || suddenJankSpike)

        // Quyết định v6 LagMonster:
        // 1) Nhiệt emergency → tắt Fixed Perf
        // 2) Combat / GPU / CPU cao → IM LẶNG (không bao giờ shell — chống lag ngược)
        // 3) Stuck lag chỉ khi ĐÃ hết combat (CPU thấp) + confidence cao
        // 4) RAM chỉ khi yên và RAM rất cao
        val inCombat = gpuPressure || cpu >= 78 ||
            (frameJankFinal != null && frameJankFinal >= 10f && cpu >= 60)
        val recoverSafe = stuckLag && !inCombat && cpu < 60 && confidence >= 0.7f &&
            (bottleneck == Bottleneck.FRAME || bottleneck == Bottleneck.IO_STORAGE)

        val recommendedAction = when {
            bottleneck == Bottleneck.THERMAL && isEmergency -> Action.DISABLE_FIXED_PERF
            bottleneck == Bottleneck.THERMAL && !inCombat -> Action.DIM_SCREEN
            inCombat -> Action.STAY_SILENT
            recoverSafe -> Action.RECOVER_STUCK_LAG
            isLagType || cpu >= 70 -> Action.STAY_SILENT
            bottleneck == Bottleneck.MEMORY && ram >= (if (stabilityScore >= 0.65f) 94 else 92) &&
                cpu < 55 -> Action.RESTRICT_BG
            bottleneck == Bottleneck.NONE -> Action.NONE
            else -> Action.NONE
        }

        // Interval: giật → mau hơn; máy đã học là ổn định → thưa hơn (tiết kiệm overhead)
        val overheadPenaltyMs = if (overhead.cpuPercent >= 2.5f) 12_000L else 0L
        val stabilityBonusMs = if (stabilityScore >= 0.7f && bottleneck == Bottleneck.NONE) 8_000L else 0L
        val interval = (when {
            suddenJankSpike || stuckLag -> 18_000L
            isEmergency -> 22_000L
            bottleneck == Bottleneck.THERMAL -> 26_000L
            bottleneck == Bottleneck.MEMORY -> 32_000L
            isLagType -> 30_000L
            bottleneck == Bottleneck.UNKNOWN -> 28_000L
            else -> 34_000L
        } + overheadPenaltyMs + stabilityBonusMs).coerceAtLeast(minInterval)

        return Snapshot(
            cpu, ram, temp, freqPct, frameJank, bottleneck, interval, thermalStatus, isEmergency,
            iowait, overhead.cpuPercent, overhead.ramKb, minutesUntilThermalRisk,
            recommendedAction, confidence
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
