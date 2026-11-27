package com.boostvn.gamebooster

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.util.Log

class BoosterApp : Application() {
    companion object {
        @Volatile
        lateinit var instance: BoosterApp
            private set
        val appContext: android.content.Context
            get() = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            try {
                getSharedPreferences("booster_crash_log", MODE_PRIVATE).edit()
                    .putString("last_error", stackTrace.take(4000))
                    .putLong("last_error_at", System.currentTimeMillis())
                    .putBoolean("last_error_fatal", thread === Looper.getMainLooper().thread)
                    .apply()
            } catch (_: Throwable) { }

            // Lỗi ở luồng NỀN (đo nhiệt, dọn nền, lệnh Shizuku...) chỉ làm chết đúng luồng đó - không
            // được giết cả app giữa trận. Trước đây mọi lỗi đều kill process nên app/HUD bị tắt đột ngột.
            if (thread !== Looper.getMainLooper().thread) {
                Log.e("BoosterApp", "Lỗi ở luồng nền '${thread.name}' (đã bỏ qua, app vẫn chạy)", throwable)
            } else {
                try {
                    val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                        putExtra("crash_info", stackTrace)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(1)
                } catch (e: Exception) {
                    defaultHandler?.uncaughtException(thread, throwable)
                }
            }
        }

        // Lấy cảm hứng từ module CacheVoid (tự kiểm tra theo lịch hàng ngày) nhưng làm an
        // toàn hơn - job chỉ kiểm tra bộ nhớ và báo, không tự ý dọn gì (xem DailyMaintenanceWorker).
        DailyMaintenanceWorker.schedule(applicationContext)
    }
}
