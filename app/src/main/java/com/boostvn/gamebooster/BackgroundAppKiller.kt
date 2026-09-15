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

    /** "AI phải quản lý app nền thông minh" - không được đụng vào những tiến trình mà kill
     * nhầm sẽ gây crash/reload hiển nhiên cho người dùng: bàn phím (IME) đang được cấu
     * hình làm mặc định, và bất kỳ dịch vụ hỗ trợ (Accessibility) nào người dùng đang bật.
     * Đọc thẳng từ Settings hệ thống - không đoán theo tên gói. */
    private fun getSystemCriticalPackages(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        try {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')?.let { if (it.isNotBlank()) result += it }
        } catch (_: Throwable) { }
        try {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.split(':')?.forEach { entry ->
                entry.substringBefore('/').takeIf { it.isNotBlank() }?.let { result += it }
            }
        } catch (_: Throwable) { }
        return result
    }

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

    /** Lấy danh sách package THẬT SỰ ĐANG CHẠY ngay lúc này qua Shizuku (lệnh `ps` chuẩn
     * Linux - quyền shell luôn xem được toàn bộ tiến trình hệ thống, khác hẳn API Java
     * ActivityManager.getRunningAppProcesses() bị Android giới hạn chỉ thấy tiến trình CỦA
     * CHÍNH MÌNH từ Android 5.1 trở đi).
     *
     * ĐÂY LÀ ĐIỂM MẤU CHỐT CHO GAME NGHẼN CPU (như Free Fire - theo đúng số liệu CPU
     * bottleneck 72% GPU chỉ 18% người dùng cung cấp, khác hẳn game nặng GPU như Liên
     * Quân): loại nghẽn CPU này thường đến từ CÁC DỊCH VỤ NỀN ĐANG CHẠY SẴN (SDK quảng cáo,
     * đồng bộ, giữ kết nối liên tục...) chứ KHÔNG PHẢI app người dùng vừa mở gần đây -
     * getRecentPackages() (dựa trên lịch sử FOREGROUND) hoàn toàn bỏ sót nhóm này vì chúng
     * có thể chưa từng được người dùng mở lên màn hình bao giờ. */
    private fun getActuallyRunningPackages(context: Context): Set<String> {
        if (!ShizukuHelper.hasPermission()) return emptySet()
        val raw = ShizukuHelper.runShellCommandWithOutput("ps -A -o NAME") ?: return emptySet()
        val installed = try {
            context.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }
        return raw.lines().mapNotNull { line ->
            // Tiến trình con của 1 app có thể có dạng "com.example.app:service" - lấy phần
            // trước dấu ':' để khớp đúng tên package.
            line.trim().substringBefore(':').takeIf { it in installed }
        }.toSet()
    }

    fun getLikelyForegroundPackage(context: Context): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - 15_000, end).orEmpty()
            .maxByOrNull { it.lastTimeUsed }?.packageName
    } catch (_: Throwable) { null }

    /**
     * Dọn "sâu" cho hành động người dùng TỰ BẤM "Dọn cache" (khác cleanBeforeGame chỉ dọn
     * tối đa vài app trước trận). LÝ DO CẦN HÀM RIÊNG: theo tài liệu AOSP, `pm trim-caches`
     * chỉ dọn được cache của app KHÔNG đang chạy - app còn sống trong RAM bị bỏ qua dù gọi
     * với số cực lớn. Đây là lý do "giải phóng cache" trước đây có cảm giác không dọn hết:
     * rất nhiều app vẫn đang chạy nền nên cache của chúng chưa từng được đụng tới. Hàm này
     * dừng TẤT CẢ app nền hợp lệ (không giới hạn số lượng như cleanBeforeGame) để chúng đủ
     * điều kiện được trim-caches dọn sau đó - vẫn tôn trọng đầy đủ danh sách bảo vệ (hệ
     * thống, launcher, IME, Accessibility, app đang ở foreground).
     */
    fun stopAllBackgroundApps(context: Context, ownPackage: String): Int {
        if (!ShizukuHelper.hasPermission()) return 0
        val pm = context.packageManager
        val launcherPackages = try {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }
        val systemCritical = getSystemCriticalPackages(context)
        val foregroundNow = getLikelyForegroundPackage(context)

        // Cửa sổ 24 giờ (thay vì 2 phút của cleanBeforeGame) - hành động "dọn sâu" này chủ
        // ý bao quát rộng hơn, vì mục tiêu là giải phóng thật nhiều cache, không phải chỉ
        // né 1-2 app có thể ảnh hưởng ngay trước khi vào game. Gộp thêm app ĐANG THẬT SỰ
        // CHẠY (không chỉ app vừa mở gần đây) - xem ghi chú getActuallyRunningPackages.
        val merged = (getActuallyRunningPackages(context).toList() + getRecentPackages(context, 24 * 60)).distinct()
        val candidates = merged
            .asSequence()
            .filter { it != ownPackage && it != foregroundNow }
            .filterNot { pkg -> protectedPrefixes.any { pkg.startsWith(it) } }
            .filterNot { it in launcherPackages }
            .filterNot { it in systemCritical }
            .mapNotNull { pkg -> runCatching { pkg to pm.getApplicationInfo(pkg, 0) }.getOrNull() }
            .filter { (_, info) ->
                (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    (info.flags and ApplicationInfo.FLAG_STOPPED) == 0
            }
            .toList()

        var count = 0
        for ((pkg, _) in candidates) {
            if (ShizukuHelper.runShellCommand("am force-stop --user 0 $pkg")) count++
        }
        return count
    }

    /**
     * CHỈ hạn chế Standby Bucket, KHÔNG force-stop - an toàn để gọi GIỮA TRẬN (khác hẳn
     * cleanBeforeGame chỉ nên gọi trước trận) vì không đụng tới bất kỳ tiến trình đang chạy
     * nào, chỉ giảm tần suất báo thức/job TƯƠNG LAI của nó. Dùng để bắt các app mới xuất
     * hiện/tự khởi động lại GIỮA TRẬN (do báo thức hệ thống, thông báo đẩy, JobScheduler...)
     * mà cleanBeforeGame trước trận không thể biết trước - đây chính là nguyên nhân "mượt
     * lúc đầu, lag về sau" TRONG CÙNG 1 TRẬN dài.
     */
    fun restrictNewlyRunningApps(context: Context, gamePackage: String, ownPackage: String): Int {
        if (!ShizukuHelper.hasPermission()) return 0
        val pm = context.packageManager
        val launcherPackages = try {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }
        val systemCritical = getSystemCriticalPackages(context)

        val candidates = getActuallyRunningPackages(context)
            .asSequence()
            .filter { it != ownPackage && it != gamePackage }
            .filterNot { pkg -> protectedPrefixes.any { pkg.startsWith(it) } }
            .filterNot { it in launcherPackages }
            .filterNot { it in systemCritical }
            .toList()

        var count = 0
        for (pkg in candidates) {
            if (ShizukuHelper.runShellCommand("am set-standby-bucket $pkg restricted")) count++
        }
        return count
    }

    /**
     * Chỉ nên gọi một lần ngay trước khi mở game. Không gọi trong HUD định kỳ.
     */
    fun cleanBeforeGame(context: Context, gamePackage: String, ownPackage: String, maxApps: Int = 3): Int {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val launcherPackages = try {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }
        val systemCritical = getSystemCriticalPackages(context)

        // Ưu tiên app THẬT SỰ ĐANG CHẠY (thủ phạm CPU thật, xem ghi chú getActuallyRunningPackages)
        // trước, rồi mới bù thêm bằng app vừa mở gần đây nếu còn chỗ trống (maxApps).
        // Trộn 2 nguồn, giữ thứ tự ưu tiên, loại trùng lặp.
        val runningNow = getActuallyRunningPackages(context)
        val recentlyUsed = getRecentPackages(context, 2)
        val merged = (runningNow.toList() + recentlyUsed).distinct()

        val candidates = merged
            .asSequence()
            .filter { it != ownPackage && it != gamePackage }
            .filterNot { pkg -> protectedPrefixes.any { pkg.startsWith(it) } }
            .filterNot { it in launcherPackages }
            .filterNot { it in systemCritical }
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
                // SỬA MỚI (nguyên nhân "mượt lúc đầu, lag về sau" TRONG CÙNG 1 TRẬN): dừng
                // app xong không có nghĩa nó im mãi - Android có thể tự khởi động lại app
                // giữa trận vì báo thức hệ thống, JobScheduler, hoặc thông báo đẩy, mà app
                // này TUYỆT ĐỐI không đụng gì tới tiến trình khác trong lúc đang chơi (đúng
                // nguyên tắc an toàn), nên hiệu quả dọn dẹp phai dần khi trận kéo dài.
                // App Standby Bucket là cơ chế CHÍNH THỨC của Android (từ Android 9) để hạn
                // chế tần suất chạy nền của app - đặt "restricted" khiến hệ thống giãn cách
                // báo thức/job của app đó ra rất nhiều trong cả ngày, không chỉ tức thời như
                // force-stop. Tự động về lại bình thường ngay khi người dùng mở lại app đó
                // - không cần bước khôi phục thủ công, không có rủi ro "quên bật lại".
                ShizukuHelper.runShellCommand("am set-standby-bucket $pkg restricted")
            } else {
                runCatching { am.killBackgroundProcesses(pkg); count++ }
            }
        }
        return count
    }

    @Deprecated("Không dùng trong lúc chơi game; dùng cleanBeforeGame() trước khi mở game")
    fun killRecentBackgroundApps(context: Context, excludePackage: String?, minutes: Long, ownPackage: String): Int = 0
}
