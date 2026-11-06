package com.boostvn.gamebooster

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Kiểm tra hàng ngày (module CacheVoid - tự dọn cache lúc nửa đêm - gợi ý cách làm này,
 * nhưng ở đây làm AN TOÀN HƠN): job này CHỈ KIỂM TRA bộ nhớ máy, KHÔNG tự ý dừng app hay
 * xoá gì cả - đúng nguyên tắc "người dùng luôn chủ động, không có gì xảy ra sau lưng" đã
 * giữ xuyên suốt app này. Nếu phát hiện bộ nhớ sắp đầy, chỉ hiện thông báo - bạn tự quyết
 * định có mở app để dọn hay không.
 */
class DailyMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            val prefs = applicationContext.getSharedPreferences("booster_health_state", Context.MODE_PRIVATE)
            val lastShown = prefs.getLong("last_low_storage_nudge", 0L)
            if (System.currentTimeMillis() - lastShown < 24 * 60 * 60 * 1000L) return Result.success()

            val stat = StatFs(applicationContext.filesDir.path)
            val totalBytes = stat.totalBytes
            if (totalBytes <= 0) return Result.success()
            val freePercent = stat.availableBytes.toDouble() / totalBytes * 100.0
            if (freePercent >= 10.0) return Result.success()

            prefs.edit().putLong("last_low_storage_nudge", System.currentTimeMillis()).apply()
            postNotification(freePercent)
        } catch (_: Throwable) {
            // Job nền thất bại không được crash app - bỏ qua, thử lại vào lần chạy sau.
        }
        return Result.success()
    }

    private fun postNotification(freePercent: Double) {
        val context = applicationContext
        val channelId = "daily_maintenance_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Nhắc bảo trì máy", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Bộ nhớ máy sắp đầy")
            .setContentText("Chỉ còn ${"%.0f".format(freePercent)}% trống - mở app để dọn cache")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try { nm.notify(9911, notification) } catch (_: Throwable) { }
    }

    companion object {
        private const val WORK_NAME = "daily_storage_check"

        /** Gọi 1 lần lúc app khởi động (BoosterApp.onCreate) - WorkManager tự lo việc lên
         * lịch lặp lại, sống sót qua khởi động lại máy, và tự chọn thời điểm hệ thống rảnh
         * để chạy (không cố định "đúng nửa đêm" như CacheVoid vì WorkManager không đảm bảo
         * giờ chính xác cho periodic work - đây là đánh đổi hợp lý để tiết kiệm pin, không
         * đánh thức máy chỉ để chạy đúng giờ). */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
                val request = PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (_: Throwable) { }
        }
    }
}
