package com.boostvn.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Nhận diện máy mạnh / trung bình / yếu từ tiêu chí thật (RAM, low-ram flag, CPU, model).
 * Đưa ra cấu hình BOOST khuyến nghị — user xác nhận trước khi apply.
 */
object DeviceProfileHelper {

    enum class Tier(val label: String, val emoji: String) {
        WEAK("Máy yếu", "🪫"),
        MID("Máy trung bình", "📱"),
        STRONG("Máy mạnh", "⚡")
    }

    data class BoostRecommendation(
        val tier: Tier,
        val totalRamGb: Double,
        val cpuCores: Int,
        val maxCpuGhz: Float?,
        val downscale: String,          // "off" | "0.55" | "0.65" | "0.75" | "0.85"
        val perfMode: MainActivity.PerfMode,
        val useFixedPerf: Boolean,
        val cleanupApps: Int,
        val reasonLines: List<String>
    ) {
        fun summaryText(): String = buildString {
            append("${tier.emoji} ${tier.label}")
            append(" · RAM ~%.1f GB · %d nhân".format(totalRamGb, cpuCores))
            maxCpuGhz?.let { append(" · CPU ~%.1f GHz".format(it)) }
            append("\n\n")
            append("Cấu hình đề xuất khi BOOST:\n")
            append("• Downscale: ${downscaleLabel()}\n")
            append("• Chế độ: ${perfMode.label}\n")
            append("• Fixed Performance: ${if (useFixedPerf) "Có (sau khi load)" else "Không (tránh nóng → lag sau)"}\n")
            append("• Dọn app nền trước trận: tối đa $cleanupApps app\n")
            if (reasonLines.isNotEmpty()) {
                append("\nLý do:\n")
                reasonLines.forEach { append("• $it\n") }
            }
            append("\nBạn có muốn áp dụng cấu hình này không?")
        }

        private fun downscaleLabel(): String = when (downscale) {
            "off" -> "Tắt (độ phân giải gốc)"
            "0.55" -> "0.55 — FPS ưu tiên tối đa"
            "0.65" -> "0.65 — máy yếu (khuyên dùng)"
            "0.75" -> "0.75 — cân bằng"
            "0.85" -> "0.85 — giảm nhẹ"
            else -> downscale
        }
    }

    fun isVivoY21(): Boolean = Build.MANUFACTURER.equals("vivo", true) &&
        Build.MODEL.equals("V2111", true)

    fun isLowRamDevice(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice
    } catch (_: Throwable) { false }

    fun totalRamGb(context: Context): Double = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024.0 * 1024.0 * 1024.0)
    } catch (_: Throwable) { 0.0 }

    fun cpuCoreCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Đọc max freq CPU (kHz) từ sysfs — không cần root trên nhiều máy. */
    fun maxCpuGhz(): Float? {
        return try {
            val base = File("/sys/devices/system/cpu")
            if (!base.isDirectory) return null
            var maxKhz = 0L
            base.listFiles()?.forEach { cpu ->
                if (!cpu.name.startsWith("cpu") || !cpu.name.drop(3).all { it.isDigit() }) return@forEach
                val f = File(cpu, "cpufreq/cpuinfo_max_freq")
                if (f.canRead()) {
                    val v = f.readText().trim().toLongOrNull() ?: return@forEach
                    if (v > maxKhz) maxKhz = v
                }
            }
            if (maxKhz > 0) maxKhz / 1_000_000f else null
        } catch (_: Throwable) {
            null
        }
    }

    fun detectTier(context: Context): Tier {
        val ram = totalRamGb(context)
        val lowRam = isLowRamDevice(context)
        val cores = cpuCoreCount()
        val ghz = maxCpuGhz()
        val y21 = isVivoY21()

        // Điểm thô: càng cao càng mạnh
        var score = 0
        when {
            ram >= 8.0 -> score += 3
            ram >= 6.0 -> score += 2
            ram >= 4.0 -> score += 1
            else -> score += 0
        }
        if (lowRam) score -= 2
        when {
            cores >= 8 -> score += 2
            cores >= 6 -> score += 1
        }
        when {
            ghz != null && ghz >= 2.6f -> score += 2
            ghz != null && ghz >= 2.2f -> score += 1
            ghz != null && ghz < 2.0f -> score -= 1
        }
        if (y21) score -= 2 // Helio P35 / 4GB class

        return when {
            score <= 1 -> Tier.WEAK
            score <= 4 -> Tier.MID
            else -> Tier.STRONG
        }
    }

    fun recommendBoost(context: Context, gamePackage: String? = null): BoostRecommendation {
        val tier = detectTier(context)
        val ram = totalRamGb(context)
        val cores = cpuCoreCount()
        val ghz = maxCpuGhz()
        val reasons = mutableListOf<String>()

        if (isLowRamDevice(context)) reasons += "Android đánh dấu máy low-RAM"
        if (isVivoY21()) reasons += "Nhận diện Vivo Y21 (chip entry, ưu tiên sustained)"
        if (ram > 0) reasons += "RAM tổng ~%.1f GB".format(ram)
        reasons += "Số nhân CPU: $cores"
        ghz?.let { reasons += "Xung tối đa ~%.2f GHz".format(it) }

        // Game nặng (FF/LQ) trên máy yếu → downscale mạnh hơn một nấc
        val heavyGame = gamePackage == "com.dts.freefireth" ||
            gamePackage == "com.garena.game.kgvn"

        return when (tier) {
            Tier.WEAK -> BoostRecommendation(
                tier = tier,
                totalRamGb = ram,
                cpuCores = cores,
                maxCpuGhz = ghz,
                downscale = "0.55", // v4.0 máy yếu: luôn ưu tiên FPS
                perfMode = MainActivity.PerfMode.CAN_BANG,
                useFixedPerf = false,
                cleanupApps = 4, // v4.0: dọn mạnh hơn trước trận trên máy yếu
                reasonLines = reasons + listOf(
                    "Máy yếu: downscale mạnh + dọn nền nhiều hơn",
                    "Tắt Fixed Perf (tránh nóng → throttle giữa trận)",
                    if (heavyGame) "Game nặng (FF/LQ): downscale 0.55" else "Downscale 0.55 mặc định"
                )
            )
            Tier.MID -> BoostRecommendation(
                tier = tier,
                totalRamGb = ram,
                cpuCores = cores,
                maxCpuGhz = ghz,
                downscale = if (heavyGame) "0.65" else "0.75",
                perfMode = MainActivity.PerfMode.TU_DONG,
                useFixedPerf = false,
                cleanupApps = 2,
                reasonLines = reasons + listOf(
                    "Cân bằng hình ảnh và FPS",
                    "Tự động chọn chế độ theo nhiệt/RAM lúc BOOST"
                )
            )
            Tier.STRONG -> BoostRecommendation(
                tier = tier,
                totalRamGb = ram,
                cpuCores = cores,
                maxCpuGhz = ghz,
                downscale = if (heavyGame) "0.85" else "off",
                perfMode = MainActivity.PerfMode.TU_DONG,
                useFixedPerf = false, // vẫn mặc định tắt; user chọn Cực mạnh nếu muốn
                cleanupApps = 1,
                reasonLines = reasons + listOf(
                    "Máy đủ mạnh: giữ đồ họa cao hơn",
                    "Có thể chọn Cực mạnh thủ công nếu máy mát"
                )
            )
        }
    }

    fun recommendedCleanupCount(context: Context): Int =
        recommendBoost(context).cleanupApps

    fun thermalSafeThresholdC(context: Context): Float = when (detectTier(context)) {
        Tier.WEAK -> 40f
        Tier.MID -> 41.5f
        Tier.STRONG -> 42.5f
    }

    fun minPollingIntervalMs(context: Context): Long = when (detectTier(context)) {
        Tier.WEAK -> 25_000L
        Tier.MID -> 22_000L
        Tier.STRONG -> 20_000L
    }

    fun shouldSkipFrameJankSampling(context: Context): Boolean =
        detectTier(context) == Tier.WEAK // máy yếu: bỏ dumpsys gfxinfo (gây hitch)
}
