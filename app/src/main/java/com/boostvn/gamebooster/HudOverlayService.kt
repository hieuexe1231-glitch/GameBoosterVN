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

    // AI học trên máy (LearningProfileHelper): ghi lại diễn biến phiên chơi hiện tại để
    // cuối phiên tổng kết thành dữ liệu học riêng cho gamePackage này - không đo thêm gì
    // mới, chỉ tận dụng lại snapshot đã có sẵn mỗi lần lấy mẫu.
    private var sessionStartAt = 0L
    private var cleanupCountUsed = 2
    private var ramPercentAtFirstBottleneck: Int? = null
    private var hadEarlyMemoryBottleneck = false
    private var hadThermalBottleneck = false

    // FEEDBACK LOOP + ROLLBACK THẬT (tiêu chí quan trọng nhất theo yêu cầu người dùng):
    // sau khi bật Fixed Performance Mode lúc đầu trận, không "tin luôn là tốt" - hẹn giờ
    // kiểm tra lại sau ~75 giây (đủ 4-5 mẫu đo). Nếu lúc đó máy đang THERMAL hoặc ở mức
    // khẩn cấp -> coi như tweak này đang PHẢN TÁC DỤNG với đúng game+máy này, tự động
    // rollback (tắt Fixed Performance Mode) ngay trong phiên, không đợi người dùng báo.
    private var fixedPerfCheckAt = 0L
    private var fixedPerfVerified = false

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
        // SỬA LỖI KIẾN TRÚC QUAN TRỌNG (nguyên nhân "trận 1 bình thường, trận 2 lag hơn
        // rất nhiều"): trước đây khi người dùng chơi xong 1 trận rồi vào trận tiếp theo
        // (game vẫn mở, chỉ đổi ván), Service này KHÔNG hề bị huỷ và tạo lại - nó là đúng 1
        // tiến trình chạy liên tục xuyên suốt (vì "đang ở trong game" nên không trigger
        // auto-stop). Hậu quả: các cờ "chỉ chạy 1 lần cho cả phiên" (fixedPerfCheckAt,
        // fixedPerfVerified...) vẫn giữ nguyên giá trị CŨ từ trận 1 sang trận 2 - nghĩa là
        // cơ chế Feedback Loop/rollback (xem GameOptimizationEngine + phần dưới) đã "dùng
        // hết" ở trận 1, trận 2 dù có vấn đề nhiệt thật (nhiệt tích luỹ từ trận 1 dồn sang)
        // cũng KHÔNG còn ai kiểm tra/rollback nữa. Đồng thời dữ liệu học cũng bị gộp nhầm
        // của cả 2 trận thành 1 "phiên" duy nhất, sai lệch hoàn toàn.
        // FIX: MainActivity giờ gửi kèm "new_session"=true mỗi lần người dùng bấm bắt đầu
        // chơi - dùng tín hiệu THẬT này (thay vì đoán qua sessionStartAt) để biết chắc chắn
        // khi nào cần coi là phiên mới, chốt dữ liệu học của trận vừa xong rồi mới reset.
        if (intent?.getBooleanExtra("new_session", false) == true) {
            resetSessionState()
        }
        currentGamePackage = intent?.getStringExtra("game_package") ?: currentGamePackage
        if (adaptiveEngine == null) adaptiveEngine = AdaptiveGameEngine(this)
        // Phiên chơi mới (game_package lần đầu xuất hiện) -> khởi tạo lại dữ liệu học.
        if (sessionStartAt == 0L && currentGamePackage != null) {
            sessionStartAt = SystemClock.elapsedRealtime()
            thermalSamplesBeforeDim =
                if (LearningProfileHelper.getLearnedThermalRisk(this, currentGamePackage!!)) 1 else 2
        }
        intent?.getIntExtra("cleanup_count", -1)?.let { if (it >= 0) cleanupCountUsed = it }
        // Chỉ hẹn giờ kiểm tra MỘT LẦN mỗi phiên (giống sessionStartAt) - nếu Intent này
        // lặp lại (ví dụ HUD nổi được bật/tắt nhiều lần trong cùng phiên) không hẹn giờ lại.
        if (fixedPerfCheckAt == 0L && (intent?.getBooleanExtra("fixed_perf_enabled", false) == true)) {
            fixedPerfCheckAt = SystemClock.elapsedRealtime() + 75_000L
        }

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
                // SỬA LỖI ỔN ĐỊNH QUAN TRỌNG: toàn bộ khối xử lý mỗi lần đo (dưới đây) chạy
                // mỗi 12-20 giây suốt trận, hoàn toàn không có lớp bảo vệ nào ngoài đúng
                // bước đo số liệu (adaptiveEngine?.sample). Nếu bất kỳ dòng nào phía sau lỗi
                // bất ngờ (ví dụ thiết bị/ROM có hành vi lạ chưa từng gặp), lỗi sẽ giết chết
                // cả luồng tối ưu - và vì app có handler bắt lỗi toàn cục (BoosterApp), điều
                // đó có thể kill LUÔN CẢ APP giữa trận. Bọc try/catch tổng quanh cả khối: nếu
                // 1 lần đo lỗi, bỏ qua đúng lần đó và tiếp tục vòng lặp bình thường ở lần
                // sau - không để 1 lỗi hiếm gặp phá hỏng cả phần còn lại của trận.
                try {
                    runWorkerIteration()
                } catch (t: Throwable) {
                    // Nuốt lỗi có chủ đích - xem ghi chú trên. Không làm gì thêm (không ghi
                    // log ra file vì bản thân việc ghi log cũng có thể lỗi trên số ít ROM).
                }
                if (stopWorker) break // tránh chờ thừa 1 chu kỳ (tới 20s) trước khi thoát hẳn
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    private fun runWorkerIteration() {
        val game = currentGamePackage
        if (game != null) {
                    // Kiểm tra xem người dùng có còn thực sự chơi game này không - nếu
                    // không, đây là lúc coi như phiên chơi đã kết thúc, tự tắt bảo vệ nền
                    // và thông báo mà không cần bấm tay.
                    val foregroundNow = BackgroundAppKiller.getLikelyForegroundPackage(this)
                    if (foregroundNow != null && foregroundNow != game && foregroundNow != packageName) {
                        missingGameSamples++
                        if (missingGameSamples >= MISSING_SAMPLES_BEFORE_AUTOSTOP) {
                            // Đặt cờ dừng NGAY tại đây (không đợi onDestroy() - stopSelf()
                            // chỉ YÊU CẦU Android huỷ Service, chạy KHÔNG đồng bộ, nên vòng
                            // lặp có thể chạy thêm 1 lần trước khi Service thực sự bị huỷ
                            // nếu không tự đặt cờ ngay) - đảm bảo vòng while() dừng đúng lúc,
                            // không lãng phí 1 lần lặp + chờ thừa sau khi đã quyết định dừng.
                            stopWorker = true
                            stopSelf()
                            return
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
                                (s.frameJankPercent?.let { " · Jank %.1f%%".format(it) } ?: "") +
                                (s.thermalStatus?.takeIf { it > 0 }?.let { " · ${TemperatureHelper.thermalStatusLabel(it)}" } ?: "") +
                                (s.minutesUntilThermalRisk?.let { " · Nóng thêm sau ~%.0f phút".format(it) } ?: "") +
                                (if (s.ioWaitPercent >= 20) " · Đang chờ lưu trữ" else "") +
                                " · Booster ${"%.1f".format(s.boosterCpuPercent)}%/${s.boosterRamKb / 1024}MB"
                            // ĐÃ SỬA LỖI: 2 đồng hồ đo CPU/RAM trong overlay_hud.xml (gaugeCpu,
                            // gaugeRam) tồn tại sẵn trong layout nhưng CHƯA BAO GIỜ được gọi
                            // setValue() - trước đây luôn đứng yên ở trạng thái mặc định "--"
                            // dù đã có đủ số liệu tính sẵn mỗi lần lấy mẫu. Nối lại dữ liệu thật
                            // đã có, không thêm phép đo mới nào.
                            overlayView?.findViewById<GaugeRingView>(R.id.gaugeCpu)
                                ?.setValue(s.cpuLoadPercent.toFloat(), "${s.cpuLoadPercent}%", "CPU")
                            overlayView?.findViewById<GaugeRingView>(R.id.gaugeRam)
                                ?.setValue(s.ramUsedPercent.toFloat(), "${s.ramUsedPercent}%", "RAM")
                        }
                        // Ghi lại dữ liệu thô cho AI học trên máy - không tạo phép đo mới,
                        // chỉ đọc lại snapshot đã tính sẵn ở trên.
                        // SỬA LỖI: trước đây gán RAM% lúc bất kỳ loại nghẽn nào (nhiệt, CPU,
                        // frame...) làm "ngưỡng RAM nguy hiểm" - sai vì trộn tín hiệu không
                        // liên quan (máy nóng làm giật ở RAM 50% không có nghĩa 50% RAM là
                        // ngưỡng nguy hiểm). Giờ CHỈ lấy mẫu khi đúng là nghẽn RAM, và bỏ
                        // qua 20 giây đầu phiên (RAM lúc đó còn nhiễu bởi tiến trình game
                        // vừa khởi động, chưa phản ánh đúng mức RAM ổn định của game).
                        val elapsedForSample = if (sessionStartAt > 0) SystemClock.elapsedRealtime() - sessionStartAt else 0L
                        if (s.bottleneck == AdaptiveGameEngine.Bottleneck.MEMORY && elapsedForSample >= 20_000L) {
                            if (ramPercentAtFirstBottleneck == null) {
                                ramPercentAtFirstBottleneck = s.ramUsedPercent
                            }
                            if (elapsedForSample <= 5 * 60_000L) {
                                hadEarlyMemoryBottleneck = true
                            }
                        }
                        // SỬA LỖI QUAN TRỌNG (nguyên nhân có thể khiến "chơi LQ vẫn lag"):
                        // trước đây dùng chung Bottleneck.THERMAL (kích hoạt ở mức MODERATE
                        // - mức ấm BÌNH THƯỜNG của bất kỳ game nặng GPU nào như LQ) làm tín
                        // hiệu để HỌC "game này hay nóng máy" VÀ để ROLLBACK Fixed Performance
                        // Mode. Hậu quả: LQ chỉ cần ấm lên bình thường sau ~75s (không phải
                        // vì Fixed Performance Mode gây ra) đã bị hiểu nhầm là "tweak có hại"
                        // -> tự tắt ngay trong phiên, VÀ học sai thành "LQ hay nóng máy" ->
                        // các trận sau AI né hẳn Fixed Performance Mode cho LQ NGAY TỪ ĐẦU,
                        // dù chưa gì đã nóng thật. Giờ tách riêng: phản ứng giảm sáng màn
                        // hình (ít rủi ro, nên vẫn nhạy - dùng Bottleneck.THERMAL) KHÁC với
                        // tín hiệu học+rollback (cần bằng chứng mạnh hơn hẳn - SEVERE trở
                        // lên, tức hệ thống THẬT SỰ đang giảm hiệu năng vì nhiệt, không phải
                        // chỉ "ấm hơn bình thường").
                        val isSevereThermal = s.thermalStatus != null &&
                            s.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
                        // SỬA THÊM (tích luỹ theo NGÀY): sạc pin trong lúc chơi tự nhiên làm
                        // máy nóng hơn hẳn - KHÔNG liên quan gì tới game hay tối ưu. Nếu vẫn
                        // cho tín hiệu nóng lúc đang sạc vào học "game này hay nóng máy", vài
                        // ngày có sạc-vừa-chơi sẽ cộng dồn khiến AI dần né tối ưu cho game đó
                        // một cách oan uổng dù phần lớn các ngày (không sạc) không hề nóng.
                        val isCharging = try {
                            val bm = getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager
                            bm?.isCharging == true
                        } catch (_: Throwable) { false }
                        if (isSevereThermal && !isCharging) {
                            hadThermalBottleneck = true
                        }
                        // FEEDBACK LOOP: tới giờ hẹn kiểm tra lại Fixed Performance Mode
                        // chưa? Chỉ rollback khi có BẰNG CHỨNG MẠNH (SEVERE trở lên hoặc
                        // giật khung hình quá nặng) - không rollback chỉ vì máy ấm lên bình
                        // thường như mọi game nặng khác vẫn vậy.
                        if (fixedPerfCheckAt > 0 && !fixedPerfVerified &&
                            SystemClock.elapsedRealtime() >= fixedPerfCheckAt) {
                            fixedPerfVerified = true
                            if (isSevereThermal || s.isEmergency) {
                                // Rollback vẫn thực hiện dù đang sạc (an toàn thiết bị lúc
                                // này quan trọng hơn, bất kể lý do nóng là gì) - nhưng CHỈ
                                // ghi nhận vào việc học nếu không đang sạc, cùng lý do với
                                // hadThermalBottleneck ở trên.
                                Thread { GameOptimizationEngine.disableFixedPerformanceOnly() }.start()
                                if (!isCharging) hadThermalBottleneck = true
                            }
                        }
                        reactToBottleneck(s, game)
                    }
                }
    }

    // Theo dõi độ sáng gốc để khôi phục đúng sau khi đã tự giảm để hạ nhiệt.
    private var dimmedForThermal = false
    private var originalBrightness: Int? = null
    private var consecutiveThermalSamples = 0
    // AI học trên máy: game từng lặp lại nghẽn nhiệt nhiều phiên trước (getLearnedThermalRisk)
    // thì lần này phản ứng giảm sáng SỚM HƠN (1 mẫu thay vì 2) - không đợi đủ như game
    // thường vì đã biết trước game này "nóng tay" trên đúng máy này.
    private var thermalSamplesBeforeDim = 2

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
                // KHẨN CẤP (s.isEmergency, dựa trên tín hiệu SEVERE/CRITICAL chính thức của
                // Android hoặc giật khung hình quá nặng): phản ứng NGAY, không chờ đủ mẫu -
                // đây chính là lúc "hiện tại tăng hiệu năng tiếp sẽ phản tác dụng" mà tiêu
                // chí bạn gửi mô tả.
                if ((s.isEmergency || consecutiveThermalSamples >= thermalSamplesBeforeDim) && !dimmedForThermal) {
                    dimScreenForCooling()
                    // An toàn kép: tắt luôn Fixed Performance Mode (nếu đang bật) vì chế
                    // độ này ưu tiên khoá xung ổn định, có thể góp phần giữ nhiệt cao hơn
                    // mức cần thiết khi máy đã thực sự quá nóng - lúc này ưu tiên an toàn
                    // hơn là giữ frame-time ổn định.
                    Thread { GameOptimizationEngine.disableFixedPerformanceOnly() }.start()
                }
            }
            AdaptiveGameEngine.Bottleneck.CPU,
            AdaptiveGameEngine.Bottleneck.FRAME,
            AdaptiveGameEngine.Bottleneck.IO_STORAGE -> {
                // Đang combat/nghẽn nặng, hoặc đang chờ đọc lưu trữ (loading map/asset) -
                // KHÔNG làm gì cả để không tranh tài nguyên hoặc chen vào lúc I/O đang bận.
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
            // Tự kiểm tra ngay trước khi làm (đo tức thời trong 150ms, không cần đợi 2 mẫu
            // như AdaptiveGameEngine) - nếu CPU đang bận thật sự cao ngay lúc này, rất có
            // thể giao tranh vừa bắt đầu ngay sau lần đo "yên tĩnh" gần nhất (poll cách
            // nhau 15-20s, có độ trễ tự nhiên). Huỷ ngay để không chen lệnh vào đúng lúc
            // CPU/GPU đang cần dồn hết cho khung hình - đợi đợt "yên tĩnh" kế tiếp.
            // Dùng % CPU BẬN THẬT (idle/busy qua /proc/stat) thay vì xung nhịp, vì Fixed
            // Performance Mode cố tình giữ xung nhịp cao ổn định suốt trận - kiểm tra theo
            // xung nhịp sẽ luôn tưởng nhầm "đang bận" dù máy thực ra đang rảnh.
            if (quickCpuBusyPercent() >= 85) return@Thread

            ShizukuHelper.runShellCommand("pm trim-caches 999999999999")
            // An toàn để gọi giữa trận vì KHÔNG force-stop gì cả - chỉ hạn chế lịch chạy
            // nền TƯƠNG LAI của app mới xuất hiện/tự khởi động lại từ lúc vào game tới giờ
            // (xem ghi chú restrictNewlyRunningApps).
            BackgroundAppKiller.restrictNewlyRunningApps(this@HudOverlayService, gamePackage, packageName)
        }.start()
    }

    /** Đo % CPU bận THẬT (idle/busy chuẩn Linux, giống readCpuLoadAndIowait trong
     * AdaptiveGameEngine nhưng KHÔNG dùng chung state - hàm này chạy trên thread riêng của
     * doLightCleanup, dùng chung state sẽ làm sai lệch phép đo chính của AdaptiveGameEngine
     * đang chạy song song trên worker thread). Đo tức thời trong 150ms - đủ nhanh để không
     * làm chậm việc dọn dẹp, đủ để bắt được CPU đang bận hay rảnh ngay lúc gọi. */
    private fun quickCpuBusyPercent(): Int {
        fun readTotals(): Pair<Long, Long>? {
            val line = try {
                java.io.File("/proc/stat").useLines { it.firstOrNull { l -> l.startsWith("cpu ") } }
            } catch (_: Throwable) { null } ?: return null
            val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            return if (values.size < 5) null else values.sum() to (values[3] + values[4])
        }

        val before = readTotals() ?: return 0
        try { Thread.sleep(150) } catch (_: InterruptedException) { return 0 }
        val after = readTotals() ?: return 0
        val dTotal = after.first - before.first
        val dIdle = after.second - before.second
        return if (dTotal <= 0) 0 else ((1.0 - dIdle.toDouble() / dTotal) * 100.0).toInt().coerceIn(0, 100)
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

        // ĐÃ SỬA LỖI: nút này trước đây rỗng hoàn toàn, bấm không có tác dụng gì (khác với
        // btnHudKillBg/btnHudDnd đã được nối trước đó). Giờ đo ping thật (giống hệt cách
        // MainActivity.measureAndShowPing() làm) và hiện luôn kết quả vào tvHudExtra.
        overlayView?.findViewById<View>(R.id.btnHudRefreshPing)?.setOnClickListener {
            Thread {
                val ping = NetworkPingHelper.measurePingMs()
                handler.post {
                    val text = if (ping >= 0) "Ping: ${ping} ms" else "Ping: không đo được"
                    overlayView?.findViewById<TextView>(R.id.tvHudExtra)?.text = text
                }
            }.start()
        }
        overlayView?.findViewById<View>(R.id.btnCollapseHud)?.setOnClickListener {
            val c = overlayView?.findViewById<View>(R.id.hudFullContent)
            c?.visibility = if (c?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        windowManager.addView(overlayView, params)
    }

    /** Ghi nhận dữ liệu học của phiên (trận) hiện tại vào LearningProfileHelper, dùng
     * chung cho cả onDestroy() (đóng HUD hẳn) LẪN resetSessionState() (bắt đầu trận mới
     * trong khi Service vẫn đang chạy liên tục) - xem ghi chú ở onStartCommand(). Chạy
     * trên thread riêng vì SharedPreferences.apply() vẫn có thể chạm I/O nhẹ. */
    private fun flushSessionLearning() {
        currentGamePackage?.let { pkg ->
            val duration = if (sessionStartAt > 0) SystemClock.elapsedRealtime() - sessionStartAt else 0L
            val result = LearningProfileHelper.SessionResult(
                durationMs = duration,
                cleanupCountUsed = cleanupCountUsed,
                hadEarlyMemoryBottleneck = hadEarlyMemoryBottleneck,
                ramPercentAtFirstBottleneck = ramPercentAtFirstBottleneck,
                hadThermalBottleneck = hadThermalBottleneck
            )
            Thread { LearningProfileHelper.recordSessionEnd(applicationContext, pkg, result) }.start()
        }
    }

    /** Coi như bắt đầu MỘT TRẬN MỚI dù Service vẫn đang chạy liên tục (không bị huỷ) -
     * chốt dữ liệu học của trận vừa xong trước khi mất, rồi reset sạch mọi cờ "chỉ chạy 1
     * lần cho cả phiên" để trận mới có đầy đủ Feedback Loop/rollback riêng của nó, không bị
     * "dùng ké" trạng thái đã tiêu thụ hết từ trận trước. Xem ghi chú đầy đủ ở onStartCommand(). */
    private fun resetSessionState() {
        if (dimmedForThermal) restoreScreenBrightness()
        flushSessionLearning()
        sessionStartAt = 0L
        fixedPerfCheckAt = 0L
        fixedPerfVerified = false
        ramPercentAtFirstBottleneck = null
        hadEarlyMemoryBottleneck = false
        hadThermalBottleneck = false
        consecutiveThermalSamples = 0
        stableCalmSamples = 0
        missingGameSamples = 0
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
        Thread { GameOptimizationEngine.restore(applicationContext) }.start()

        // AI học trên máy: tổng kết phiên chơi vừa kết thúc thành dữ liệu học riêng cho
        // gamePackage này.
        flushSessionLearning()
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
