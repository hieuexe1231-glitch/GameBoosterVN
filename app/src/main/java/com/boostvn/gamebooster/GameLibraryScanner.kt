package com.boostvn.gamebooster

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quét tự động danh sách game trên thiết bị để hiển thị trong tab Game.
 *
 * Chạy trên luồng IO (không chặn giao diện). Nhận diện game theo 2 nguồn:
 *  1. Android tự khai báo CATEGORY_GAME.
 *  2. Tiền tố package của các hãng game phổ biến ở VN (phòng khi nhà phát hành quên gắn tag).
 *
 * Lưu ý quan trọng: KHÔNG dùng từ khoá lỏng kiểu "tencent" vì sẽ nhận nhầm WeChat/QQ... là game.
 * Bàn phím (IME) và app hệ thống luôn bị loại - trước đây có lỗi thật khi bàn phím bị lọt vào danh sách.
 */
object GameLibraryScanner {

    data class InstalledGame(
        val packageName: String,
        val appName: String,
        val icon: Drawable,
        val isAutoDetected: Boolean
    )

    private val knownGamePrefixes = listOf(
        "com.tencent.ig", "com.pubg", "com.proximabeta", "com.levelinfinite", "com.krafton",
        "com.miHoYo", "com.HoYoverse", "com.mojang", "com.supercell", "com.riotgames",
        "com.activision", "com.ea.gp", "com.vng.", "com.garena.", "com.dts.",
        "net.gamestar", "com.gameloft", "com.roblox", "com.mobile.legends", "com.mobilelegends"
    )

    suspend fun scanInstalledGames(
        context: Context,
        maxResults: Int = 20
    ): List<InstalledGame> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val ownPackage = context.packageName

            val imePackages = runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.inputMethodList.map { it.packageName }.toSet()
            }.getOrDefault(emptySet())

            val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            // minSdk = 26 nên ApplicationInfo.category luôn khả dụng.
            apps.asSequence()
                .filter { it.packageName != ownPackage }
                .filter { it.packageName !in imePackages }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .filter { app ->
                    app.category == ApplicationInfo.CATEGORY_GAME ||
                        knownGamePrefixes.any { app.packageName.startsWith(it, ignoreCase = true) }
                }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { app -> app to pm.getApplicationLabel(app).toString() }
                .sortedBy { it.second.lowercase() }
                .take(maxResults)
                .mapNotNull { (app, label) ->
                    val icon = runCatching { pm.getApplicationIcon(app) }.getOrNull()
                        ?: return@mapNotNull null
                    InstalledGame(
                        packageName = app.packageName,
                        appName = label,
                        icon = icon,
                        isAutoDetected = true
                    )
                }
                .toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
