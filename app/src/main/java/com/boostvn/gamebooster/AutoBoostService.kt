package com.boostvn.gamebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * v4.0 Auto-Boost: theo dõi app foreground (UsageStats).
 * Khi user mở game đã biết (từng BOOST / FF / LQ) → grace → prepare profile → HUD.
 * Thoát game → restore. Không apply app thường.
 */
class AutoBoostService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var stop = false

    private var activeGame: String? = null
    private var candidateGame: String? = null
    private var candidateSince = 0L
    private var missingSamples = 0
    private var candidateHits = 0
    @Volatile private var applying = false
    private var lastApplyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Đang chờ mở game…"))
        thread = HandlerThread("auto-boost").also { it.start() }
        handler = Handler(thread!!.looper)
        stop = false
        handler?.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop = true
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        // Nếu đang boost thì restore
        if (activeGame != null) {
            Thread { GameOptimizationEngine.forceFullRestore(applicationContext) }.start()
            activeGame = null
        }
        super.onDestroy()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (stop) return
            try {
                tick()
            } catch (_: Throwable) { }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    private fun tick() {
        if (!AutoBoostPrefs.isEnabled(this)) {
            updateNotif("Auto-Boost đang tắt")
            return
        }
        if (applying) return
        val fg = BackgroundAppKiller.getLikelyForegroundPackage(this) ?: return
        if (fg == packageName) return

        val known = GameProfileStore.isKnownGame(this, fg)
        val now = SystemClock.elapsedRealtime()

        if (known) {
            missingSamples = 0
            if (activeGame == fg) {
                updateNotif("Đang bảo vệ: ${shortLabel(fg)}")
                return
            }
            // Cần thấy cùng game liên tiếp + đủ grace sau load (tránh apply sớm / nhầm)
            if (candidateGame != fg) {
                candidateGame = fg
                candidateSince = now
                candidateHits = 1
                updateNotif("Phát hiện ${shortLabel(fg)} — chờ ổn định…")
                return
            }
            candidateHits++
            if (candidateHits < MIN_HITS_BEFORE_APPLY) {
                updateNotif("Phát hiện ${shortLabel(fg)} — xác nhận…")
                return
            }
            if (now - candidateSince < GRACE_MS) {
                val left = ((GRACE_MS - (now - candidateSince)) / 1000L).coerceAtLeast(0)
                updateNotif("Phát hiện ${shortLabel(fg)} — chờ load ~${left}s")
                return
            }
            // Tránh apply liên tục
            if (now - lastApplyAt < MIN_REAPPLY_MS && activeGame == null) {
                // cho phép apply lần đầu sau cooldown ngắn nếu vừa restore
            }
            applyBoost(fg)
        } else {
            if (activeGame != null) {
                // Chỉ đếm khi fg rõ ràng khác game (launcher/app khác)
                missingSamples++
                if (missingSamples >= MISSING_BEFORE_RESTORE) {
                    restoreActive()
                }
            } else {
                candidateGame = null
                candidateHits = 0
                updateNotif("Đang chờ mở game…")
            }
        }
    }

    private fun applyBoost(gamePackage: String) {
        if (applying) return
        applying = true
        try {
            // Restore game cũ nếu có
            if (activeGame != null && activeGame != gamePackage) {
                try { GameOptimizationEngine.forceFullRestore(this) } catch (_: Throwable) { }
            }

            val profile = GameProfileStore.load(this, gamePackage)
            val rec = DeviceProfileHelper.recommendBoost(this, gamePackage)
            // Ưu tiên ổn định: mode từ profile, không thì Cân bằng / Tự động theo máy (không ép Cực mạnh)
            val mode = try {
                profile?.modeName?.let { MainActivity.PerfMode.valueOf(it) }
            } catch (_: Throwable) { null } ?: when (rec.perfMode) {
                MainActivity.PerfMode.CUC_MANH -> MainActivity.PerfMode.CAN_BANG
                else -> rec.perfMode
            }

            // Downscale: chỉ mức USER đã chọn
            if (profile != null) {
                GameOptimizationEngine.setDownscaleFactor(this, profile.downscale)
            }

            val result = GameOptimizationEngine.prepare(this, gamePackage, mode)
            // Auto: dọn nhẹ hơn BOOST tay — giảm I/O giật lúc vừa vào game
            val cleanup = DeviceProfileHelper.recommendedCleanupCount(this).coerceAtMost(2)
            BackgroundAppKiller.cleanBeforeGame(this, gamePackage, packageName, maxApps = cleanup)

            try {
                val hud = Intent(this, HudOverlayService::class.java).apply {
                    putExtra("game_package", gamePackage)
                    putExtra("show_overlay", false)
                    putExtra("cleanup_count", cleanup)
                    putExtra("fixed_perf_enabled", result.fixedPerfEnabled)
                    putExtra("defer_refresh_pin", true)
                    putExtra("new_session", true)
                }
                ContextCompat.startForegroundService(this, hud)
            } catch (_: Throwable) { }

            activeGame = gamePackage
            candidateGame = null
            candidateHits = 0
            lastApplyAt = SystemClock.elapsedRealtime()
            updateNotif("Đã tối ưu (ổn định): ${shortLabel(gamePackage)}")

            if (AutoBoostPrefs.notifyOnApply(this)) {
                postApplyToastNotif(shortLabel(gamePackage), result.actions.size)
            }
        } finally {
            applying = false
        }
    }

    private fun restoreActive() {
        val pkg = activeGame
        activeGame = null
        candidateGame = null
        missingSamples = 0
        Thread {
            try { GameOptimizationEngine.forceFullRestore(applicationContext) } catch (_: Throwable) { }
            // Dừng HUD nếu đang chạy
            try { stopService(Intent(applicationContext, HudOverlayService::class.java)) } catch (_: Throwable) { }
        }.start()
        updateNotif("Đã thoát game — khôi phục hệ thống")
        pkg?.let { /* keep for log */ }
    }

    private fun shortLabel(pkg: String): String = when (pkg) {
        "com.dts.freefireth", "com.dts.freefiremax" -> "Free Fire"
        "com.garena.game.kgvn" -> "Liên Quân"
        else -> pkg.substringAfterLast('.').take(18)
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Auto-Boost")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) { }
    }

    private fun postApplyToastNotif(game: String, actions: Int) {
        try {
            ensureChannel()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Đã Auto-Boost: $game")
                .setContentText("$actions mục tối ưu · profile máy + game")
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_APPLY_ID, n)
        } catch (_: Throwable) { }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto-Boost", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "auto_boost_channel"
        private const val NOTIF_ID = 9920
        private const val NOTIF_APPLY_ID = 9921
        private const val POLL_MS = 3000L
        private const val GRACE_MS = 16_000L // chờ load asset lâu hơn → ít giật đầu trận
        private const val MISSING_BEFORE_RESTORE = 5 // thoát game chắc hơn trước khi restore
        private const val MIN_HITS_BEFORE_APPLY = 2
        private const val MIN_REAPPLY_MS = 20_000L
        const val ACTION_STOP = "com.boostvn.gamebooster.STOP_AUTO_BOOST"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AutoBoostService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AutoBoostService::class.java).setAction(ACTION_STOP))
            ctx.stopService(Intent(ctx, AutoBoostService::class.java))
        }
    }
}
