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
    /** Game đang chơi + bản FF MAX — tuyệt đối không force-stop / restrict. */
    private val protectedGamePackages = setOf(
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.dts.freefirevn",
        "com.garena.game.freefirevn",
        "com.garena.freefirevn",
        "com.garena.game.ffvn",
        "com.garena.game.kgvn",
        "com.garena.game.kgid",
        "com.tencent.tmgp.sgame",
        "com.mobile.legends",
        "com.vng.mlbbvn"
    )

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

    // Mã sự kiện UsageEvents (giá trị giữ nguyên từ API 21; từ API 29 đổi tên thành
    // ACTIVITY_RESUMED / ACTIVITY_PAUSED nhưng số không đổi).
    private const val EVT_FOREGROUND = 1
    private const val EVT_BACKGROUND = 2

    /** Lớp giao diện hệ thống thoáng qua - không bao giờ được coi là "app đang dùng". */
    private val transientUiPackages = setOf(
        "com.android.systemui", "android",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller"
    )

    /**
     * App đang ở foreground, suy ra từ SỰ KIỆN Activity (resume/pause) thay vì lastTimeUsed.
     *
     * Cách cũ (lấy app có lastTimeUsed lớn nhất trong 15 giây) sai khi đang chơi game: lastTimeUsed của
     * game chỉ cập nhật lúc có sự kiện, nên chỉ cần 1 app khác thoáng qua (thanh thông báo, popup
     * quyền, cửa sổ nổi...) là bị nhận nhầm thành "đã thoát game" -> app tự khôi phục và TẮT toàn bộ
     * tối ưu giữa trận. Giờ chỉ tin sự kiện resume thật; lớp hệ thống thoáng qua bị bỏ qua.
     *
     * Trả về null = không xác định (không có sự kiện / thiếu quyền Usage Access / có activity khác
     * đang che tạm). Nơi gọi coi null là "không biết" và KHÔNG được suy ra là đã thoát game.
     */
    fun getLikelyForegroundPackage(context: Context): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 30 * 60_000L, end)
        val e = android.app.usage.UsageEvents.Event()
        var current: String? = null
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                EVT_FOREGROUND -> if (pkg !in transientUiPackages) current = pkg
                EVT_BACKGROUND -> if (pkg == current) current = null
            }
        }
        current
    } catch (_: Throwable) { null }

    data class HudCleanResult(
        val stoppedNames: List<String>,
        val freedMb: Long,
        /** true = không có nguồn để biết app nào đang chạy (chưa có Shizuku và chưa cấp Usage Access). */
        val needsAccess: Boolean,
        /** true = không xác định được game đang chơi nên không dám dọn (tránh dừng nhầm game). */
        val gameUnknown: Boolean
    )

    /**
     * Dọn nền do NGƯỜI DÙNG TỰ BẤM nút 🧹 trong HUD. Trước đây nút này gọi cleanBeforeGame() - hàm đó
     * chỉ xét app chạy nền (cần Shizuku) hoặc app mới dùng trong 2 phút, mà đang ở trong game thì
     * 2 phút gần nhất chỉ có game + chính app này (đều bị loại) nên nút không làm gì và không báo gì.
     * Hàm này: lấy app thật sự đang chạy (Shizuku) hoặc app dùng trong 6 giờ qua (Usage Access),
     * dừng tối đa [maxApps] app, đo RAM trống trước/sau và trả kết quả để HUD báo lại.
     * KHÔNG đổi Standby Bucket (ảnh hưởng kéo dài cả ngày, làm trễ thông báo của app) - chỉ dừng tạm.
     */
    fun cleanFromHud(context: Context, gamePackage: String?, ownPackage: String, maxApps: Int = 8): HudCleanResult {
        val foregroundNow = getLikelyForegroundPackage(context)
        val gameOrFg = gamePackage ?: foregroundNow
        if (gameOrFg == null) return HudCleanResult(emptyList(), 0L, needsAccess = false, gameUnknown = true)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun availBytes(): Long = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = availBytes()

        val pm = context.packageManager
        val useShizuku = ShizukuHelper.hasPermission()
        val sources = if (useShizuku) getActuallyRunningPackages(context).toList()
        else getRecentPackages(context, 6 * 60)
        if (sources.isEmpty() && !useShizuku) {
            return HudCleanResult(emptyList(), 0L, needsAccess = true, gameUnknown = false)
        }

        val launcherPackages = try {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }
        val systemCritical = getSystemCriticalPackages(context)

        val candidates = sources.asSequence()
            .filter { it != ownPackage && it != gameOrFg && it != foregroundNow }
            .filter { it != "moe.shizuku.privileged.api" } // dừng Shizuku sẽ làm mất toàn bộ quyền tối ưu
            .filterNot { it in protectedGamePackages }
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

        val names = mutableListOf<String>()
        for ((pkg, info) in candidates) {
            val ok = if (useShizuku) {
                ShizukuHelper.runShellCommand("am force-stop --user 0 $pkg")
            } else {
                runCatching { am.killBackgroundProcesses(pkg); true }.getOrDefault(false)
            }
            if (ok) names += runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
        }

        // Hệ thống cần một chút thời gian để thu hồi RAM sau khi dừng tiến trình.
        if (names.isNotEmpty()) try { Thread.sleep(700) } catch (_: InterruptedException) { }
        val freedMb = ((availBytes() - before).coerceAtLeast(0L)) / (1024L * 1024L)
        return HudCleanResult(names, freedMb, needsAccess = false, gameUnknown = false)
    }

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
            .filterNot { it in protectedGamePackages }
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
        if (!ShizukuHelper.hasPermission()) {
            // No-Shizuku: killBackgroundProcesses (nhẹ hơn force-stop, API công khai)
            return cleanBackgroundNoShizuku(context, gamePackage, ownPackage, maxApps = 6)
        }
        val pm = context.packageManager
        val launcherPackages = try {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) { emptySet() }
        val systemCritical = getSystemCriticalPackages(context)

        // Giới hạn số lượng xử lý mỗi lần gọi (tối đa 10 app) - nếu không giới hạn, máy có
        // nhiều tiến trình nền (thường gặp) sẽ khiến 1 lần gọi hàm này phải chạy hàng chục
        // lệnh shell liên tiếp, tự nó cũng tốn CPU đáng kể dù mỗi lệnh riêng lẻ rất nhẹ -
        // góp phần vào đúng kiểu giật lặp lại giữa trận đã tìm ra và sửa ở nơi gọi hàm này.
        val candidates = getActuallyRunningPackages(context)
            .asSequence()
            .filter { it != ownPackage && it != gamePackage }
            .filterNot { it in protectedGamePackages }
            .filterNot { pkg -> protectedPrefixes.any { pkg.startsWith(it) } }
            .filterNot { it in launcherPackages }
            .filterNot { it in systemCritical }
            .take(4)
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

    /** Dọn nền không Shizuku: ActivityManager.killBackgroundProcesses. */
    private fun cleanBackgroundNoShizuku(
        context: Context,
        gamePackage: String,
        ownPackage: String,
        maxApps: Int
    ): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val systemCritical = getSystemCriticalPackages(context)
        val recent = getRecentPackages(context, 15)
        var count = 0
        for (pkg in recent) {
            if (count >= maxApps) break
            if (pkg == ownPackage || pkg == gamePackage) continue
            if (protectedPrefixes.any { pkg.startsWith(it) }) continue
            if (pkg in systemCritical) continue
            try {
                am.killBackgroundProcesses(pkg)
                count++
            } catch (_: Throwable) { }
        }
        return count
    }

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
