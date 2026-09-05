package com.boostvn.gamebooster

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * HUD nhẹ: 1 worker tuần tự duy nhất, polling thưa để overhead <=2-3%.
 *
 * NÂNG CẤP THEO HƯỚNG "TỐI ƯU HIỆU NĂNG THẬT" (không chỉ đo, mà HÀNH ĐỘNG):
 * - AdaptiveGameEngine đã đo tốt (CPU/RAM/Nhiệt/Frame-jank + hysteresis chống dao động),
 *   nhưng bản trước CHỈ hiện chữ, không làm gì cả. Giờ kết quả đo được dùng để:
 *   1) Ổn định giao tranh: TUYỆT ĐỐI không dọn dẹp gì khi đang CPU/THERMAL/FRAME nghẽn
 *      (dấu hiệu đang combat nặng) - đây là lúc app cần "im lặng" nhất để không tranh
 *      tài nguyên với game.
 *   2) Chơi lâu dài: chỉ dọn RAM (nếu MEMORY nghẽn) hoặc dọn nhẹ định kỳ vào đúng lúc máy
 *      đang ở trạng thái ỔN ĐỊNH (NONE) trong ít nhất vài mẫu liên tiếp - tức đang ở
 *      "khoảng lặng" giữa các pha giao tranh, không phải dọn mù theo giờ cố định.
 *   3) Nút "dọn app nền" và "Không làm phiền" trong HUD giờ THỰC SỰ hoạt động (trước đây
 *      rỗng, bấm không có tác dụng gì).
 */
class HudOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayShown = false
    private val handler = Handler(Looper.getMainLooper())
    private var currentGamePackage: String? = null
    private var adaptiveEngine: AdaptiveGameEngine? = null
    private var intervalMs = 12_000L
    private var worker: Thread? = null
    private var stopWorker = false

    // Đếm số mẫu liên tiếp ở trạng thái ỔN ĐỊNH - chỉ dọn dẹp khi đủ "yên tĩnh" thật sự,
    // tránh dọn ngay khi vừa hết 1 pha combat (có thể combat tiếp ngay sau đó).
    private var stableCalmSamples = 0
    private var lastMaintenanceAt = 0L
    private val MIN_CALM_SAMPLES_BEFORE_CLEAN = 3
    private val MIN_MAINTENANCE_GAP_MS = 4 * 60 * 1000L

    // Tự động dừng khi phát hiện người dùng không còn chơi game này nữa - tránh thông
    // báo "Đang bảo vệ nền" hiện mãi không tắt dù đã thoát game từ lâu.
    private var missingGameSamples = 0
    private val MISSING_SAMPLES_BEFORE_AUTOSTOP = 3

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * ĐÃ SỬA: onCreate() trước đây LUÔN gọi addOverlayView() ngay lập tức, dù người dùng
     * không hề bật HUD nổi - nghĩa là mọi hành động bảo vệ (DND, giảm nhiệt, dọn cache
     * lúc yên tĩnh) CHỈ hoạt động khi bấm nút "Bật HUD nổi" thủ công. Giờ tách riêng:
     * Service này luôn có thể chạy Ở CHẾ ĐỘ ẨN (không hiện widget, không cần quyền "Hiển
     * thị đè lên ứng dụng khác") để việc "Tối ưu & Mở game" cũng có đầy đủ bảo vệ liên tục
     * mà không bắt buộc phải bật HUD nổi.
     */
    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannelIfNeeded()
            startForeground(NOTIF_ID, buildNotification())
            startWorker()
        } catch (_: Throwable) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentGamePackage = intent?.getStringExtra("game_package") ?: currentGamePackage
        if (adaptiveEngine == null) adaptiveEngine = AdaptiveGameEngine(this)

        val wantOverlay = intent?.getBooleanExtra("show_overlay", false) ?: false
        if (wantOverlay && !overlayShown) {
            try {
                addOverlayView()
                overlayShown = true
            } catch (_: Throwable) { /* thiếu quyền overlay - vẫn chạy tiếp ở chế độ ẩn */ }
        }
        // Cập nhật lại nội dung thông báo cho đúng chế độ vừa xác định (lúc onCreate()
        // chạy thì chưa biết Intent muốn ẩn hay hiện widget).
        try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Throwable) { }
        return START_NOT_STICKY
    }

    private fun startWorker() {
        stopWorker = false
        worker = Thread {
            while (!stopWorker) {
                val game = currentGamePackage
                if (game != null) {
                    // Kiểm tra xem người dùng có còn thực sự chơi game này không - nếu
                    // không, đây là lúc coi như phiên chơi đã kết thúc, tự tắt bảo vệ nền
                    // và thông báo mà không cần bấm tay.
                    val foregroundNow = BackgroundAppKiller.getLikelyForegroundPackage(this)
                    if (foregroundNow != null && foregroundNow != game && foregroundNow != packageName) {
                        missingGameSamples++
                        if (missingGameSamples >= MISSING_SAMPLES_BEFORE_AUTOSTOP) {
                            stopSelf()
                            break
                        }
                    } else {
                        missingGameSamples = 0
                    }

                    val snapshot = runCatching { adaptiveEngine?.sample(game) }.getOrNull()
                    snapshot?.let { s ->
                        intervalMs = s.suggestedIntervalMs
                        handler.post {
                            overlayView?.findViewById<TextView>(R.id.tvHudExtra)?.text =
                                "${s.bottleneck.label} · CPU ${s.cpuLoadPercent}% · RAM ${s.ramUsedPercent}% · " +
                                "${s.temperatureC?.let { "%.0f°C".format(it) } ?: "--°C"}" +
                                (s.frameJankPercent?.let { " · Jank %.1f%%".format(it) } ?: "")
                        }
                        reactToBottleneck(s, game)
                    }
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    // Theo dõi độ sáng gốc để khôi phục đúng sau khi đã tự giảm để hạ nhiệt.
    private var dimmedForThermal = false
    private var originalBrightness: Int? = null
    private var consecutiveThermalSamples = 0
    private val THERMAL_SAMPLES_BEFORE_DIM = 2

    /**
     * Đây là chỗ biến số liệu đo được thành HÀNH ĐỘNG THẬT - phần trước đây hoàn toàn
     * thiếu. Nguyên tắc: ĐANG NGHẼN (combat) -> không làm gì cả, im lặng tuyệt đối.
     * ĐANG ỔN ĐỊNH ĐỦ LÂU -> mới dọn nhẹ RAM để chuẩn bị cho pha giao tranh kế tiếp.
     */
    private fun reactToBottleneck(s: AdaptiveGameEngine.Snapshot, gamePackage: String) {
        when (s.bottleneck) {
            AdaptiveGameEngine.Bottleneck.THERMAL -> {
                // Đang combat/nghẽn nặng - KHÔNG dọn dẹp gì để không tranh tài nguyên.
                stableCalmSamples = 0
                // Nhưng nhiệt độ là ngoại lệ: máy quá nóng kéo dài mới thực sự làm giảm
                // hiệu năng lâu dài (throttling), nên vẫn xử lý riêng - giảm độ sáng màn
                // hình (nguồn tỏa nhiệt lớn nhất) thay vì đụng vào CPU/tiến trình game.
                consecutiveThermalSamples++
                if (consecutiveThermalSamples >= THERMAL_SAMPLES_BEFORE_DIM && !dimmedForThermal) {
                    dimScreenForCooling()
                    // An toàn kép: tắt luôn Fixed Performance Mode (nếu đang bật) vì chế
                    // độ này ưu tiên khoá xung ổn định, có thể góp phần giữ nhiệt cao hơn
                    // mức cần thiết khi máy đã thực sự quá nóng - lúc này ưu tiên an toàn
                    // hơn là giữ frame-time ổn định.
                    Thread { GameOptimizationEngine.disableFixedPerformanceOnly() }.start()
                }
            }
            AdaptiveGameEngine.Bottleneck.CPU,
            AdaptiveGameEngine.Bottleneck.FRAME -> {
                // Đang combat/nghẽn nặng - KHÔNG làm gì cả để không tranh tài nguyên.
                stableCalmSamples = 0
                consecutiveThermalSamples = 0
                if (dimmedForThermal) restoreScreenBrightness()
            }
            AdaptiveGameEngine.Bottleneck.MEMORY -> {
                // RAM đang áp lực thật - đây là lúc CẦN dọn ngay, không đợi "yên tĩnh"
                // nữa vì nếu để lâu Android có thể tự ý kill tiến trình game.
                stableCalmSamples = 0
                consecutiveThermalSamples = 0
                if (dimmedForThermal) restoreScreenBrightness()
                doLightCleanup(gamePackage, reason = "RAM đang áp lực")
            }
            AdaptiveGameEngine.Bottleneck.NONE -> {
                stableCalmSamples++
                consecutiveThermalSamples = 0
                if (dimmedForThermal) restoreScreenBrightness()
                val now = SystemClock.elapsedRealtime()
                val calmEnough = stableCalmSamples >= MIN_CALM_SAMPLES_BEFORE_CLEAN
                val enoughGapSinceLast = now - lastMaintenanceAt >= MIN_MAINTENANCE_GAP_MS
                if (calmEnough && enoughGapSinceLast) {
                    lastMaintenanceAt = now
                    doLightCleanup(gamePackage, reason = "bảo trì định kỳ lúc máy đang ổn định")
                }
            }
            else -> { /* UNKNOWN - chưa đủ mẫu để kết luận, chưa hành động */ }
        }
    }

    /**
     * Giảm nhiệt chủ động THẬT: màn hình là nguồn tỏa nhiệt lớn nhất trên điện thoại khi
     * chơi game (độ sáng cao + xử lý đồ hoạ liên tục). Giảm nhẹ ~25% độ sáng khi máy quá
     * nóng kéo dài giúp giảm 1 phần nhiệt tổng, kéo dài thời gian trước khi CPU/GPU tự
     * giảm xung (thermal throttling) - đây là cách THẬT giúp "chơi được lâu hơn ở hiệu
     * năng cao", không phải mẹo giả. Cần Shizuku vì chỉnh độ sáng hệ thống của app khác
     * cần quyền đặc biệt (WRITE_SETTINGS) mà lệnh shell bỏ qua được.
     */
    private fun dimScreenForCooling() {
        if (!ShizukuHelper.hasPermission()) return
        Thread {
            val current = ShizukuHelper.runShellCommandWithOutput("settings get system screen_brightness")
                ?.trim()?.toIntOrNull() ?: return@Thread
            if (originalBrightness == null) originalBrightness = current
            val target = (current * 0.75f).toInt().coerceIn(20, 255)
            if (ShizukuHelper.runShellCommand("settings put system screen_brightness $target")) {
                dimmedForThermal = true
            }
        }.start()
    }

    private fun restoreScreenBrightness() {
        val original = originalBrightness ?: return
        if (!dimmedForThermal) return
        Thread {
            ShizukuHelper.runShellCommand("settings put system screen_brightness $original")
            dimmedForThermal = false
            originalBrightness = null
        }.start()
    }

    /**
     * Dọn TỰ ĐỘNG khi máy đang yên tĩnh - CHỈ dọn cache hệ thống (an toàn, không đụng
     * tiến trình app khác), tôn trọng đúng nguyên tắc của cleanBeforeGame()/
     * killRecentBackgroundApps() trong BackgroundAppKiller: KHÔNG force-stop app khác
     * trong lúc đang chơi, dù đang "yên tĩnh" hay không - vì việc đó vẫn tốn CPU thật
     * (spawn tiến trình shell) và có rủi ro tái diễn giật đúng lúc combat bất ngờ quay lại.
     */
    private fun doLightCleanup(gamePackage: String, reason: String) {
        if (!ShizukuHelper.hasPermission()) return
        Thread {
            ShizukuHelper.runShellCommand("pm trim-caches 999999999999")
        }.start()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("hud_overlay_channel", "HUD khi chơi game", NotificationManager.IMPORTANCE_MIN))
        }
    }

    private fun buildNotification(): Notification {
        val text = if (overlayShown) "HUD nổi đang hiện trên màn hình" else "Đang bảo vệ nền: DND, giảm nhiệt, dọn cache tự động"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "hud_overlay_channel").setContentTitle("X-Force Booster Adaptive").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).build()
        else @Suppress("DEPRECATION") Notification.Builder(this).setContentTitle("X-Force Booster Adaptive").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    private fun addOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_hud, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START; params.x = 20; params.y = 200
        overlayView?.findViewById<View>(R.id.btnCloseHud)?.setOnClickListener { stopSelf() }

        // ĐÃ SỬA: nút này trước đây rỗng, bấm không làm gì. Đây là hành động NGƯỜI DÙNG
        // TỰ BẤM (có chủ đích, không phải tự động định kỳ) nên dùng đúng cleanBeforeGame()
        // thật - vẫn giới hạn tối đa 2 app để an toàn, tự loại trừ game đang chơi.
        overlayView?.findViewById<View>(R.id.btnHudKillBg)?.setOnClickListener {
            currentGamePackage?.let { pkg ->
                Thread {
                    BackgroundAppKiller.cleanBeforeGame(this, pkg, packageName, maxApps = 2)
                }.start()
            }
        }

        // ĐÃ SỬA: bật/tắt Không làm phiền thật (trước đây rỗng).
        overlayView?.findViewById<View>(R.id.btnHudDnd)?.setOnClickListener {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    val turningOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
                    nm.setInterruptionFilter(if (turningOn) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } catch (_: Exception) { }
        }

        overlayView?.findViewById<View>(R.id.btnHudRefreshPing)?.setOnClickListener { }
        overlayView?.findViewById<View>(R.id.btnCollapseHud)?.setOnClickListener {
            val c = overlayView?.findViewById<View>(R.id.hudFullContent)
            c?.visibility = if (c?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        stopWorker = true
        worker?.interrupt()
        handler.removeCallbacksAndMessages(null)
        overlayView?.let { runCatching { windowManager.removeView(it) } }

        // Đóng HUD = coi như kết thúc phiên chơi. Khôi phục mọi thay đổi tạm thời để
        // không "kẹt" lại sau khi đã chơi xong (độ sáng bị giảm, thông báo bị chặn,
        // Fixed Performance Mode vẫn bật ngốn pin cả ngày, animation hệ thống tắt
        // vĩnh viễn làm máy có cảm giác cứng/giật khi dùng bình thường).
        if (dimmedForThermal) restoreScreenBrightness()
        Thread { GameOptimizationEngine.restore() }.start()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
            ) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) { }

        super.onDestroy()
    }

    companion object { private const val NOTIF_ID = 5001 }
}
