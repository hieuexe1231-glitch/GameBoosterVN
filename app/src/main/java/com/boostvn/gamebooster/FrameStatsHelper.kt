package com.boostvn.gamebooster

/**
 * Đọc thống kê khung hình ở mức shell khi Shizuku có quyền.
 * Cache dài — dumpsys gfxinfo nặng, gọi dày dễ gây khựng.
 */
object FrameStatsHelper {
    data class Result(val totalFrames: Long, val jankyFrames: Long, val jankPercent: Float?)

    @Volatile private var lastAt = 0L
    @Volatile private var lastPkg: String? = null
    @Volatile private var lastResult: Result? = null
    private const val MIN_INTERVAL_MS = 50_000L

    fun sample(packageName: String): Result? {
        if (!ShizukuHelper.hasPermission()) return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (packageName == lastPkg && lastResult != null && now - lastAt < MIN_INTERVAL_MS) {
            return lastResult
        }
        val output = ShizukuHelper.runShellCommandWithOutput("dumpsys gfxinfo $packageName") ?: return lastResult
        val total = Regex("""Total frames rendered:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return lastResult
        val jankMatch = Regex(
            """Janky frames:\s*(\d+)(?:\s*\(([-+]?\d+(?:\.\d+)?)%\))?""",
            RegexOption.IGNORE_CASE
        ).find(output)
        val janky = jankMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val pct = jankMatch?.groupValues?.getOrNull(2)?.toFloatOrNull()
            ?: if (total > 0) janky * 100f / total else null
        val r = Result(total, janky, pct)
        lastAt = now
        lastPkg = packageName
        lastResult = r
        return r
    }
}
