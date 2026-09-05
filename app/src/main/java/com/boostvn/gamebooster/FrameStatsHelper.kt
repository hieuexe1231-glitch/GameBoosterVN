package com.boostvn.gamebooster

/**
 * Đọc thống kê khung hình ở mức shell khi Shizuku có quyền.
 * Chỉ chạy thưa (mặc định 60s) vì dumpsys gfxinfo không phù hợp để gọi liên tục.
 */
object FrameStatsHelper {
    data class Result(val totalFrames: Long, val jankyFrames: Long, val jankPercent: Float?)

    fun sample(packageName: String): Result? {
        if (!ShizukuHelper.hasPermission()) return null
        val output = ShizukuHelper.runShellCommandWithOutput("dumpsys gfxinfo $packageName") ?: return null
        val total = Regex("Total frames rendered:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        val jankMatch = Regex("Janky frames:\\s*(\\d+)(?:\\s*\\(([-+]?\\d+(?:\\.\\d+)?)%\\))?", RegexOption.IGNORE_CASE)
            .find(output)
        val janky = jankMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val pct = jankMatch?.groupValues?.getOrNull(2)?.toFloatOrNull()
            ?: if (total > 0) janky * 100f / total else null
        return Result(total, janky, pct)
    }
}
