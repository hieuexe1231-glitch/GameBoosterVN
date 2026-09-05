package com.boostvn.gamebooster

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Dọn nền có chọn lọc. Không chạy định kỳ khi đang chơi game.
 * Mặc định chỉ xử lý ứng dụng người dùng vừa dùng gần đây và không đụng game/launcher.
 */
object BackgroundAppKiller {
    private val protectedPrefixes = listOf("com.android", "com.google.android", "android")

    private fun hasUsageAccess(context: Context): Boolean = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000, now).orEmpty().isNotEmpty()
    } catch (_: Throwable) { false }

    private fun getRecentPackages(context: Context, minutes: Long): List<String> = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - minutes * 60_000
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end).orEmpty()
            .filter { it.lastTimeUsed >= begin && it.totalTimeInForeground > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .distinct()
    } catch (_: Throwable) { emptyList() }

    fun getLikelyForegroundPackage(context: Context): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - 15_000, end).orEmpty()
            .maxByOrNull { it.lastTimeUsed }?.packageName
    } catch (_: Throwable) { null }

    /**
     * Chỉ nên gọi một lần ngay trước khi mở game. Không gọi trong HUD định kỳ.
     */
    fun cleanBeforeGame(context: Context, gamePackage: String, ownPackage: String, maxApps: Int = 3): Int {
        if (!hasUsageAccess(context)) return 0
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val launcherPackages = try {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }

        val candidates = getRecentPackages(context, 2)
            .asSequence()
            .filter { it != ownPackage && it != gamePackage }
            .filterNot { pkg -> protectedPrefixes.any { pkg.startsWith(it) } }
            .filterNot { it in launcherPackages }
            .mapNotNull { pkg -> runCatching { pkg to pm.getApplicationInfo(pkg, 0) }.getOrNull() }
            .filter { (_, info) ->
                (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    (info.flags and ApplicationInfo.FLAG_STOPPED) == 0
            }
            .take(maxApps)
            .toList()

        var count = 0
        val useShizuku = ShizukuHelper.hasPermission()
        for ((pkg, _) in candidates) {
            if (useShizuku) {
                if (ShizukuHelper.runShellCommand("am force-stop --user 0 $pkg")) count++
            } else {
                runCatching { am.killBackgroundProcesses(pkg); count++ }
            }
        }
        return count
    }

    @Deprecated("Không dùng trong lúc chơi game; dùng cleanBeforeGame() trước khi mở game")
    fun killRecentBackgroundApps(context: Context, excludePackage: String?, minutes: Long, ownPackage: String): Int = 0
}
