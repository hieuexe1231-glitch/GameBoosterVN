package com.boostvn.gamebooster

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.boostvn.gamebooster.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.math.roundToInt

/**
 * GHI CHÚ QUAN TRỌNG:
 * - Android hiện đại tự quản lý RAM rất tốt, ứng dụng KHÔNG có quyền "tăng RAM vật lý"
 *   hay "làm mát CPU vật lý". Mọi nút trong app làm việc THẬT trong giới hạn hệ điều
 *   hành cho phép.
 * - App hoạt động đầy đủ trên MỌI máy (không cần Shizuku): tối ưu bất kỳ game nào đã
 *   cài, đo nhiệt độ pin thật, chế độ Tự động thích ứng theo RAM/nhiệt độ.
 * - Nếu có Shizuku (người dùng tự cài & bật), app nâng cấp thêm: force-stop app khác
 *   thật sự, đọc nhiệt độ CPU chính xác hơn, bật/tắt Wifi-Data trực tiếp, và tự động
 *   dọn dẹp định kỳ trong lúc chơi lâu để máy ổn định hơn.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private val FREE_FIRE_PACKAGE = "com.dts.freefireth"
    private val LIEN_QUAN_PACKAGE = "com.garena.game.kgvn"

    private val REQUEST_CODE_STORAGE = 1001
    private val REQUEST_CODE_NOTIF = 1002

    // Ghi nhớ game đang được tối ưu/theo dõi gần nhất - dùng để HUD (kể cả chế độ ẩn)
    // biết cần bảo vệ app nào khi người dùng bấm "Bật HUD nổi" thủ công sau đó.
    private var activeGamePackage: String? = null


    enum class PerfMode(val label: String) {
        TIET_KIEM("Tiết kiệm"), CAN_BANG("Cân bằng"), CUC_MANH("Cực mạnh"), TU_DONG("Tự động")
    }
    private var currentPerfMode = PerfMode.TU_DONG

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuHelper.REQUEST_CODE) {
            runOnUiThread {
                updateShizukuButtonLabel()
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Toast.makeText(
                    this,
                    if (granted) "Đã cấp quyền Shizuku ⚡" else "Bạn đã từ chối quyền Shizuku",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Giảm tần suất lấy mẫu cho biểu đồ RAM ở màn hình chính (2s -> 6s): đây chỉ là hiển
    // thị tham khảo trước khi vào game, không phải hành động tối ưu, nên không cần lấy
    // mẫu dày - đỡ tốn CPU/pin một cách không cần thiết, đúng hướng "ưu tiên tối ưu thật,
    // giảm bớt đo đạc".
    private val chartSamplerRunnable = object : Runnable {
        override fun run() {
            sampleRamForChart()
            mainHandler.postDelayed(this, 6000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            try {
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Throwable) { }

            refreshSystemInfo()
            setupClickListenersSafely()
            setPerfMode(PerfMode.TU_DONG)
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khởi động: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Throwable) { }
    }

    private fun setupClickListenersSafely() {
        binding.btnClearCache.setOnClickListener { clearOwnCache(showToast = true) }
        binding.btnKillBackground.setOnClickListener { killBackgroundApps(excludePackage = null, showUi = true) }
        binding.btnScanJunk.setOnClickListener { scanAndCleanJunk() }
        binding.btnGrantPermissions.setOnClickListener { requestNeededPermissions() }
        binding.btnPerfTips.setOnClickListener { openPerfTips() }
        binding.btnWifiPanel.setOnClickListener { openQuickInternetPanel() }
        binding.btnShizuku.setOnClickListener { onShizukuButtonClick() }
        binding.btnGameOther.setOnClickListener { showPickAnyGameDialog() }
        binding.btnToggleWifi.setOnClickListener { toggleWifiViaShizuku() }
        binding.btnToggleData.setOnClickListener { toggleDataViaShizuku() }

        binding.btnGameFreeFire.setOnClickListener { startGameMode(FREE_FIRE_PACKAGE, "Free Fire") }
        binding.btnGameLienQuan.setOnClickListener { startGameMode(LIEN_QUAN_PACKAGE, "Liên Quân") }

        binding.btnModeSave.setOnClickListener { setPerfMode(PerfMode.TIET_KIEM) }
        binding.btnModeBalance.setOnClickListener { setPerfMode(PerfMode.CAN_BANG) }
        binding.btnModeExtreme.setOnClickListener { setPerfMode(PerfMode.CUC_MANH) }
        binding.btnModeAuto.setOnClickListener { setPerfMode(PerfMode.TU_DONG) }

        binding.btnHud.setOnClickListener { toggleHud() }
        binding.btnDnd.setOnClickListener { toggleDnd() }
        binding.btnPing.setOnClickListener { measureAndShowPing() }
    }

    /** SỬA THÊM (tích luỹ theo NGÀY): bộ nhớ trong đầy dần lên mỗi ngày (ảnh, cache, file
     * tải về...) là hiện tượng THẬT của Android/Linux - khi dung lượng trống xuống quá
     * thấp, tốc độ đọc/ghi file TOÀN MÁY chậm hẳn đi (không riêng gì lúc chơi game), góp
     * phần vào đúng kiểu "mấy ngày đầu mượt, sau đó lag dần" mà không liên quan gì tới AI
     * hay cấu hình tối ưu. App đã có "Dọn cache sâu" nhưng trước đây chỉ chạy khi tự bấm -
     * giờ chủ động nhắc khi phát hiện bộ nhớ trống xuống dưới 10%, tối đa 1 lần/ngày để
     * không làm phiền. */
    private fun checkLowStorageAndSuggest() {
        try {
            val prefs = getSharedPreferences("booster_health_state", MODE_PRIVATE)
            val lastShown = prefs.getLong("last_low_storage_nudge", 0L)
            if (System.currentTimeMillis() - lastShown < 24 * 60 * 60 * 1000L) return

            val stat = android.os.StatFs(filesDir.path)
            val totalBytes = stat.totalBytes
            if (totalBytes <= 0) return
            val freePercent = (stat.availableBytes.toDouble() / totalBytes * 100.0)
            if (freePercent >= 10.0) return

            prefs.edit().putLong("last_low_storage_nudge", System.currentTimeMillis()).apply()
            Toast.makeText(
                this,
                "Bộ nhớ máy chỉ còn ${"%.0f".format(freePercent)}% trống - dễ làm máy chậm dần. Thử \"Dọn cache\" để giải phóng thêm.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemInfo()
        mainHandler.post(chartSamplerRunnable)
        updateDndButtonLabel()
        updateHudButtonLabel()
        updateShizukuButtonLabel()
        checkHealthAndWarnIfRegressed()
        checkLowStorageAndSuggest()
    }

    /** "Lúc đầu mượt, về sau lag lại như cũ" - xem HealthCheckHelper. Kiểm tra mỗi lần mở
     * app: nếu Shizuku hoặc quyền bỏ qua tối ưu hoá pin TỪNG hoạt động tốt mà giờ không còn,
     * báo NGAY bằng dialog rõ ràng (không chỉ dòng log dễ bị bỏ qua) kèm nút bấm sửa trực
     * tiếp - để người dùng biết chính xác lý do, không tưởng nhầm là "app/AI dở đi". */
    private fun checkHealthAndWarnIfRegressed() {
        val status = HealthCheckHelper.check(this)
        if (!status.regressedShizuku && !status.regressedBattery) return

        val message = buildString {
            if (status.regressedShizuku) {
                append("• Shizuku đã mất kết nối/mất quyền - TOÀN BỘ tối ưu nâng cao ")
                append("(Fixed Performance Mode, dọn app nền, hạn chế chạy nền...) đang KHÔNG hoạt động.\n")
                append("  Cách sửa: mở lại app Shizuku (thường tự tắt sau khi khởi động lại máy).\n\n")
            }
            if (status.regressedBattery) {
                append("• Quyền \"Bỏ qua tối ưu hoá pin\" đã bị thu hồi (thường do trình quản lý pin ")
                append("riêng của hãng máy tự tắt sau vài ngày) - app có thể bị hệ thống giới hạn chạy nền.\n")
                append("  Cách sửa: cấp lại quyền trong phần cài đặt pin.\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("⚠️ Phát hiện tối ưu bị tắt âm thầm")
            .setMessage(message.trim())
            .setPositiveButton("Mở Shizuku") { _, _ ->
                try {
                    val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (i != null) startActivity(i)
                    else Toast.makeText(this, "Chưa cài Shizuku", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) { }
            }
            .setNeutralButton("Cài đặt pin") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) { }
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(chartSamplerRunnable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_CODE_STORAGE -> {
                Toast.makeText(
                    this,
                    if (granted) "Đã có quyền, bấm 'Quét & xóa file rác' lại để quét"
                    else "Cần cấp quyền bộ nhớ mới quét được file rác",
                    Toast.LENGTH_SHORT
                ).show()
            }
            REQUEST_CODE_NOTIF -> {
                if (granted) toggleHud()
            }
        }
    }

    // ---------- CHỌN GAME BẤT KỲ ĐỂ TỐI ƯU (không chỉ Free Fire / Liên Quân) ----------

    private fun getInstalledLaunchableApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try { pm.queryIntentActivities(intent, 0) } catch (e: Exception) { emptyList() }

        return resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun showPickAnyGameDialog() {
        val apps = getInstalledLaunchableApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy ứng dụng nào trên máy", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { it.second }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn app / game để tối ưu")
            .setItems(labels) { _, index ->
                val (pkg, label) = apps[index]
                startGameMode(pkg, label)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- SHIZUKU ----------

    private fun updateShizukuButtonLabel() {
        val granted = try { ShizukuHelper.hasPermission() } catch (e: Throwable) { false }
        binding.btnShizuku.text = if (granted)
            "⚡ Shizuku: ĐÃ KÍCH HOẠT (sức mạnh tối đa)"
        else
            "⚡ Cấp quyền Shizuku (mở khoá thêm sức mạnh)"
        binding.btnShizuku.background = ContextCompat.getDrawable(
            this, if (granted) R.drawable.btn_shizuku_granted else R.drawable.btn_shizuku
        )
    }

    private fun onShizukuButtonClick() {
        val running = try { ShizukuHelper.isShizukuAppRunning() } catch (e: Throwable) { false }
        if (!running) {
            Toast.makeText(
                this,
                "Chưa thấy Shizuku hoạt động. Cài app Shizuku (CH Play), bật 'Gỡ lỗi không dây' trong Tuỳ chọn nhà phát triển, rồi mở Shizuku bấm Start.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api"))
                )
            } catch (e: Exception) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))
                    )
                } catch (ignored: Exception) { }
            }
            return
        }

        if (ShizukuHelper.hasPermission()) {
            Toast.makeText(this, "Shizuku đã bật sẵn cho app này rồi", Toast.LENGTH_SHORT).show()
            return
        }

        ShizukuHelper.requestPermission()
    }

    private fun toggleWifiViaShizuku() {
        if (!ShizukuHelper.hasPermission()) {
            Toast.makeText(this, "Cần bật Shizuku trước để dùng chức năng này", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            // Không có API công khai để biết chắc Wifi đang bật hay tắt, nên bấm 1 lần sẽ
            // thử TẮT trước; nếu máy báo đã tắt sẵn thì lệnh BẬT sẽ có tác dụng ở lần bấm kế.
            val ok = ShizukuHelper.runShellCommand("svc wifi disable") || ShizukuHelper.runShellCommand("svc wifi enable")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                log(if (ok) "Đã chuyển trạng thái Wifi qua Shizuku" else "Lệnh Wifi thất bại trên máy này")
                Toast.makeText(this, if (ok) "Đã bật/tắt Wifi" else "Không thực hiện được trên máy này", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleDataViaShizuku() {
        if (!ShizukuHelper.hasPermission()) {
            Toast.makeText(this, "Cần bật Shizuku trước để dùng chức năng này", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val ok = ShizukuHelper.runShellCommand("svc data disable") || ShizukuHelper.runShellCommand("svc data enable")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                log(if (ok) "Đã chuyển trạng thái Dữ liệu di động qua Shizuku" else "Lệnh dữ liệu di động thất bại trên máy này")
                Toast.makeText(this, if (ok) "Đã bật/tắt Dữ liệu di động" else "Không thực hiện được trên máy này", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---------- THÔNG TIN HỆ THỐNG ----------

    private fun refreshSystemInfo() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalGb = memInfo.totalMem / 1_073_741_824.0
        val availGb = memInfo.availMem / 1_073_741_824.0
        val usedPercent = ((1 - memInfo.availMem.toDouble() / memInfo.totalMem) * 100).roundToInt()
        binding.tvRamInfo.text = "RAM: ${"%.1f".format(availGb)} GB trống / ${"%.1f".format(totalGb)} GB (đang dùng $usedPercent%)"

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.tvBatteryInfo.text = if (batteryPct in 0..100) "Pin: $batteryPct%" else "Pin: không xác định"

        val stat = StatFs(Environment.getDataDirectory().path)
        val availStorage = stat.availableBytes / 1_073_741_824.0
        val totalStorage = stat.totalBytes / 1_073_741_824.0
        binding.tvStorageInfo.text = "Bộ nhớ: ${"%.1f".format(availStorage)} GB trống / ${"%.1f".format(totalStorage)} GB"

        // Đọc nhiệt độ CPU qua Shizuku chạy lệnh shell -> có thể mất chút thời gian,
        // đưa ra luồng nền để không làm giật giao diện chính (đặc biệt là khi bấm đi
        // bấm lại các nút liên tục).
        val batteryTempFast = TemperatureHelper.getBatteryTemperatureC(this)
        binding.tvTempInfo.text = if (batteryTempFast != null)
            "Nhiệt độ pin: ${"%.1f".format(batteryTempFast)}°C" else "Nhiệt độ: không đọc được"
        checkOverheat(batteryTempFast)

        if (ShizukuHelper.hasPermission()) {
            Thread {
                val cpuTemp = TemperatureHelper.getCpuTemperatureC()
                if (cpuTemp != null) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        binding.tvTempInfo.text = "Nhiệt độ CPU: ${"%.1f".format(cpuTemp)}°C (qua Shizuku)"
                        checkOverheat(cpuTemp)
                    }
                }
            }.start()
        }
    }

    private var lastOverheatWarnTime = 0L
    private fun checkOverheat(tempC: Float?) {
        if (tempC == null) return
        val now = System.currentTimeMillis()
        if (tempC >= 44f && now - lastOverheatWarnTime > 60_000) {
            lastOverheatWarnTime = now
            log("⚠️ Máy đang khá nóng (${"%.1f".format(tempC)}°C) - cân nhắc nghỉ vài phút")
            Toast.makeText(this, "Máy đang nóng (${"%.1f".format(tempC)}°C), nên nghỉ vài phút", Toast.LENGTH_LONG).show()
        }
    }

    private fun sampleRamForChart() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val usedPercent = ((1 - memInfo.availMem.toDouble() / memInfo.totalMem) * 100).toFloat()
            binding.chartRam.addValue(usedPercent)
        } catch (e: Exception) { }
    }

    // ---------- CHẾ ĐỘ HIỆU NĂNG ----------

    private fun setPerfMode(mode: PerfMode) {
        currentPerfMode = mode
        binding.btnModeSave.background = ContextCompat.getDrawable(
            this, if (mode == PerfMode.TIET_KIEM) R.drawable.btn_mode_selected else R.drawable.btn_mode_unselected
        )
        binding.btnModeBalance.background = ContextCompat.getDrawable(
            this, if (mode == PerfMode.CAN_BANG) R.drawable.btn_mode_selected else R.drawable.btn_mode_unselected
        )
        binding.btnModeExtreme.background = ContextCompat.getDrawable(
            this, if (mode == PerfMode.CUC_MANH) R.drawable.btn_mode_selected else R.drawable.btn_mode_unselected
        )
        binding.btnModeAuto.background = ContextCompat.getDrawable(
            this, if (mode == PerfMode.TU_DONG) R.drawable.btn_mode_selected else R.drawable.btn_mode_unselected
        )
        log("Chế độ hiệu năng: ${mode.label}")
        Toast.makeText(this, "Đã chọn chế độ: ${mode.label}", Toast.LENGTH_SHORT).show()
    }

    /** Chế độ Tự động: tự đánh giá RAM/nhiệt độ hiện tại để chọn mức hành xử phù hợp.
     * SỬA LỖI v1.8: trước đây dùng ngưỡng RAM 90% cố định cho mọi máy, không khớp với
     * ngưỡng cảnh báo RAM 88% đã đặt riêng cho máy yếu trong AdaptiveGameEngine - máy
     * yếu có thể bị "Tự động" chọn nhầm CỰC MẠNH trong khi RAM đã ở mức cảnh báo. Giờ
     * dùng cùng ngưỡng nhất quán qua DeviceProfileHelper. */
    private fun resolveEffectiveMode(): PerfMode {
        if (currentPerfMode != PerfMode.TU_DONG) return currentPerfMode

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramUsedPercent = (1 - memInfo.availMem.toDouble() / memInfo.totalMem) * 100
        val temp = TemperatureHelper.getCpuTemperatureC() ?: TemperatureHelper.getBatteryTemperatureC(this) ?: 0f
        val ramThreshold = if (DeviceProfileHelper.isLowRamDevice(this)) 85 else 90

        return when {
            temp >= DeviceProfileHelper.thermalSafeThresholdC(this) -> PerfMode.CAN_BANG
            ramUsedPercent >= ramThreshold -> PerfMode.CAN_BANG
            else -> PerfMode.CUC_MANH
        }
    }


    // ---------- XOÁ CACHE ----------

    /** SỬA THEO YÊU CẦU: trước đây chỉ xoá cache của chính app X-Force Booster (an toàn
     * nhưng không đúng kỳ vọng "dọn cache" của người dùng - cache game/app khác mới là thứ
     * chiếm dung lượng đáng kể). Giờ dùng `pm trim-caches` qua Shizuku (lệnh CHÍNH THỨC
     * Android, đúng lệnh mà Cài đặt > Bộ nhớ > Giải phóng dung lượng dùng nội bộ) để dọn
     * cache của TẤT CẢ app, cộng thêm dọn cache riêng của booster (không cần quyền, luôn
     * chạy được dù chưa cấp Shizuku). Đây là hành động người dùng TỰ BẤM, không phải tối ưu
     * tự động giữa trận, nên không áp dụng nguyên tắc "không trim cache trong lúc chơi".
     *
     * SỬA TIẾP: theo tài liệu AOSP, `pm trim-caches` chỉ dọn được cache của app KHÔNG đang
     * chạy - app còn sống trong RAM bị bỏ qua dù số truyền vào cực lớn. Đây là lý do trước
     * đây cảm giác "không giải phóng hết". Giờ dừng hẳn các app nền hợp lệ trước (vẫn tôn
     * trọng đầy đủ danh sách bảo vệ hệ thống/IME/Accessibility/launcher/foreground), rồi
     * mới trim-caches 2 lần (gọi lặp lại giúp dọn triệt để hơn theo đúng cách hệ thống xử
     * lý yêu cầu này dần dần, không phải 1 lần là xong ngay).
     */
    private fun clearOwnCache(showToast: Boolean) {
        Thread {
            var freed = 0L
            cacheDir?.let { freed += deleteRecursivelyAndCount(it) }
            externalCacheDir?.let { freed += deleteRecursivelyAndCount(it) }
            val freedFinal = freed

            var stoppedApps = 0
            var systemWideOk = false
            if (ShizukuHelper.hasPermission()) {
                stoppedApps = BackgroundAppKiller.stopAllBackgroundApps(this, packageName)
                systemWideOk = ShizukuHelper.runShellCommand("pm trim-caches 999999999999")
                ShizukuHelper.runShellCommand("pm trim-caches 999999999999") // gọi lần 2 để dọn triệt để hơn
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val freedMb = freedFinal / 1_048_576.0
                if (showToast) {
                    val msg = when {
                        systemWideOk -> "Đã dừng $stoppedApps app nền và dọn cache của mọi app trên máy"
                        ShizukuHelper.hasPermission() -> "Đã dọn cache của X-Force Booster (lệnh dọn toàn máy thất bại)"
                        else -> "Đã dọn cache của X-Force Booster - bật Shizuku để dọn được cache của mọi app"
                    }
                    log("Dọn cache: ${"%.2f".format(freedMb)} MB (booster) ${if (systemWideOk) "+ dừng $stoppedApps app nền + toàn hệ thống" else ""}")
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                refreshSystemInfo()
            }
        }.start()
    }

    private fun deleteRecursivelyAndCount(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) deleteRecursivelyAndCount(file) else file.length().also { file.delete() }
        }
        return size
    }

    private fun openAppInfoScreen(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) { }
    }

    // ---------- TẮT APP CHẠY NỀN ----------

    private fun killBackgroundApps(excludePackage: String?, showUi: Boolean): Int {
        val target = excludePackage ?: BackgroundAppKiller.getLikelyForegroundPackage(this)
        val count = BackgroundAppKiller.cleanBeforeGame(
            this,
            gamePackage = target ?: "",
            ownPackage = packageName,
            maxApps = 2
        )
        log("Đã dọn chọn lọc $count app nền; không đụng game/launcher/hệ thống")
        if (showUi) Toast.makeText(this, "Đã dọn chọn lọc $count app nền", Toast.LENGTH_SHORT).show()
        refreshSystemInfo()
        return count
    }

    // ---------- QUÉT & XOÁ FILE RÁC ----------

    private val junkExtensions = listOf(".log", ".tmp", ".bak", ".old", ".crdownload", ".dmp")

    private fun hasLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanAndCleanJunk() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Cần cấp quyền truy cập bộ nhớ trước", Toast.LENGTH_LONG).show()
                requestAllFilesAccess()
                return
            }
        } else if (!hasLegacyStoragePermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE
            )
            return
        }

        Toast.makeText(this, "Đang quét file rác, vui lòng đợi...", Toast.LENGTH_SHORT).show()
        log("Bắt đầu quét file rác...")

        Thread {
            val root = Environment.getExternalStorageDirectory()
            val result = scanJunkRecursive(root, depthLimit = 6)

            var totalFreed = 0L
            var totalFiles = 0
            result.forEach { file ->
                totalFreed += file.length()
                if (file.delete()) totalFiles++
            }

            val freedMb = totalFreed / 1_048_576.0
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                log("Đã xoá $totalFiles file rác, giải phóng ${"%.2f".format(freedMb)} MB")
                Toast.makeText(this, "Đã dọn $totalFiles file rác (${"%.1f".format(freedMb)} MB)", Toast.LENGTH_SHORT).show()
                refreshSystemInfo()
            }
        }.start()
    }

    private fun scanJunkRecursive(dir: File, depthLimit: Int): List<File> {
        if (depthLimit <= 0 || !dir.isDirectory) return emptyList()
        val found = mutableListOf<File>()
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                found += scanJunkRecursive(f, depthLimit - 1)
            } else if (junkExtensions.any { f.name.endsWith(it, ignoreCase = true) }) {
                found += f
            }
        }
        return found
    }

    // ---------- QUYỀN ----------

    private fun requestNeededPermissions() {
        // Học từ việc khảo sát 1 app cùng lĩnh vực: dùng Shizuku (nếu đã có quyền) để TỰ
        // CẤP Usage Access qua lệnh `appops`, thay vì luôn bắt người dùng tự vào Cài đặt
        // tìm và bật tay - đây là bước dễ gây bỏ cuộc/cấu hình thiếu nhất trong cả quá
        // trình setup. Chỉ mở màn Cài đặt thủ công khi chưa có Shizuku hoặc tự cấp thất bại.
        val selfGranted = if (ShizukuHelper.hasPermission()) {
            ShizukuHelper.runShellCommand("appops set $packageName GET_USAGE_STATS allow")
        } else false

        if (selfGranted) {
            Toast.makeText(this, "Đã tự cấp quyền 'Truy cập dữ liệu sử dụng' qua Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) { }
            Toast.makeText(this, "Bật quyền 'Truy cập dữ liệu sử dụng' cho X-Force Booster", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess()
        } else if (!hasLegacyStoragePermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE
            )
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    // ---------- HUD NỔI ----------

    private fun isHudServiceActuallyRunning(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(Integer.MAX_VALUE).any { it.service.className == HudOverlayService::class.java.name }
        } catch (e: Exception) {
            false
        }
    }

    private fun updateHudButtonLabel() {
        binding.btnHud.text = if (isHudServiceActuallyRunning())
            "🎮 Tắt HUD nổi (đang bật)" else "🎮 Bật HUD nổi khi chơi game"
    }

    private fun toggleHud() {
        if (isHudServiceActuallyRunning()) {
            stopService(Intent(this, HudOverlayService::class.java))
            updateHudButtonLabel()
            log("Đã tắt HUD nổi")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần cấp quyền 'Hiển thị trên ứng dụng khác' trước", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) { }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIF)
            return
        }

        val intent = Intent(this, HudOverlayService::class.java)
        intent.putExtra("game_package", activeGamePackage)
        intent.putExtra("show_overlay", true)
        ContextCompat.startForegroundService(this, intent)
        updateHudButtonLabel()
        log("Đã bật HUD nổi - mở game để xem RAM/Pin/Ping đè trên màn hình")
        Toast.makeText(this, "Đã bật HUD nổi, mở game để xem", Toast.LENGTH_SHORT).show()
    }

    // ---------- KHÔNG LÀM PHIỀN ----------

    private fun updateDndButtonLabel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isOn = nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        binding.btnDnd.text = if (isOn) "🔕 Không làm phiền: ĐANG BẬT" else "🔕 Không làm phiền: đang tắt"
    }

    private fun toggleDnd() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Cần cấp quyền 'Không làm phiền' trước", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } catch (e: Exception) { }
            return
        }
        val turningOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
        nm.setInterruptionFilter(
            if (turningOn) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        updateDndButtonLabel()
        log(if (turningOn) "Đã bật Không làm phiền" else "Đã tắt Không làm phiền")
    }

    // ---------- ĐO PING MẠNG ----------

    private fun measureAndShowPing() {
        Toast.makeText(this, "Đang đo ping...", Toast.LENGTH_SHORT).show()
        binding.tvPingResult.text = "Đang đo..."
        Thread {
            val ping = NetworkPingHelper.measurePingMs()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val text = if (ping >= 0) "Ping mạng hiện tại: ${ping} ms" else "Không đo được ping (kiểm tra kết nối mạng)"
                binding.tvPingResult.text = text
                log(text)
            }
        }.start()
    }

    // ---------- MẸO TĂNG HIỆU NĂNG THẬT ----------

    private fun openPerfTips() {
        Toast.makeText(
            this,
            "Mẹo thật: vào Tuỳ chọn nhà phát triển > tắt/giảm 'Tỷ lệ hiệu ứng cửa sổ, chuyển cảnh' xuống 0.5x hoặc Tắt",
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                Toast.makeText(
                    this,
                    "Vào 'Thông tin phần mềm' > bấm 7 lần vào 'Số hiệu bản dựng' để mở khoá Tuỳ chọn nhà phát triển",
                    Toast.LENGTH_LONG
                ).show()
            } catch (ignored: Exception) { }
        }
    }

    // ---------- BẢNG WIFI / DỮ LIỆU DI ĐỘNG ----------

    private fun openQuickInternetPanel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                return
            } catch (e: Exception) { }
        }
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) { }
    }

    // ---------- CHẾ ĐỘ CHƠI GAME ----------

    private fun startGameMode(gamePackage: String, gameLabel: String) {
        activeGamePackage = gamePackage
        // Kiểm tra ngay lúc quan trọng nhất - trước khi vào game - để nếu tối ưu bị tắt
        // âm thầm, người dùng biết ngay tại sao thay vì chơi xong mới thắc mắc sao vẫn lag.
        checkHealthAndWarnIfRegressed()
        showOptimizingUi(true)
        val effectiveMode = resolveEffectiveMode()
        val special = if (gamePackage == LIEN_QUAN_PACKAGE) " + Liên Quân Performance" else ""
        log("Chuẩn bị $gameLabel: profile ${effectiveMode.label} + Adaptive Engine$special")

        setOptimizingStatus("Bước 1/3: Đang áp dụng tối ưu an toàn...")
        Thread {
            val engine = GameOptimizationEngine.prepare(this, gamePackage, effectiveMode)
            // Máy yếu (RAM thấp): dọn mạnh tay hơn hẳn trước trận vì đây là đòn bẩy #1
            // cho máy yếu (xem DeviceProfileHelper). Máy thường vẫn giữ nguyên mức thận
            // trọng cũ (tối đa 2 app) để không đụng chạm quá tay tới app khác.
            // AI học trên máy: trộn mượt giữa số mặc định này với kinh nghiệm đã học cho
            // đúng game+máy này (xem LearningProfileHelper) - độ tin cậy tăng dần tự nhiên
            // theo số phiên đã chơi, không cần chờ "đủ N phiên" cứng nhắc.
            val defaultCleanup = DeviceProfileHelper.recommendedCleanupCount(this)
            val maxCleanup = LearningProfileHelper.getLearnedCleanupCount(this, gamePackage, defaultCleanup)
            val cleaned = BackgroundAppKiller.cleanBeforeGame(this, gamePackage, packageName, maxApps = maxCleanup)

            // Combat Stability thật: bật Không làm phiền để thông báo không bật lên giữa
            // lúc giao tranh (hệ thống phải vẽ đè thông báo lên màn hình game, gây giật
            // hình đúng lúc quan trọng nhất). CHỈ bật nếu đã có sẵn quyền - không ép xin
            // quyền giữa luồng tối ưu, tránh làm gián đoạn trải nghiệm mở game.
            val dndEnabled = try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    true
                } else false
            } catch (e: Exception) { false }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setOptimizingStatus("Bước 2/3: ${engine.profile} · ${engine.actions.size} mục · dọn $cleaned app nền")
                engine.actions.forEach { log("✓ $it") }
                if (cleaned > 0) log("✓ Đã dừng $cleaned app người dùng gần đây")
                if (dndEnabled) log("✓ Đã bật Không làm phiền - ổn định giao tranh, không bị thông báo cắt ngang")

                // Bảo vệ liên tục suốt phiên chơi (giảm nhiệt chủ động, dọn cache lúc
                // yên tĩnh) - chạy Ở CHẾ ĐỘ ẨN, không hiện widget, không cần quyền "Hiển
                // thị đè lên ứng dụng khác". Widget hiển thị (nếu người dùng có bật riêng)
                // vẫn hoạt động song song bình thường, không xung đột.
                try {
                    val hudIntent = Intent(this, HudOverlayService::class.java)
                    hudIntent.putExtra("game_package", gamePackage)
                    hudIntent.putExtra("show_overlay", false)
                    hudIntent.putExtra("cleanup_count", cleaned) // để AI học ghi đúng số app đã thực sự dọn
                    hudIntent.putExtra("fixed_perf_enabled", engine.fixedPerfEnabled) // để AI tự kiểm tra lại sau ~75s (Feedback Loop)
                    // SỬA LỖI: mỗi lần người dùng bấm "Bắt đầu chơi" đều coi là 1 TRẬN MỚI,
                    // dù HUD Service có thể vẫn đang chạy liên tục từ trận trước (do vẫn ở
                    // trong game, chưa bị coi là "đã thoát"). Không có tín hiệu này, các cờ
                    // "chỉ chạy 1 lần cho cả phiên" của trận trước sẽ bị dùng ké sang trận
                    // mới - xem ghi chú đầy đủ ở HudOverlayService.onStartCommand().
                    hudIntent.putExtra("new_session", true)
                    ContextCompat.startForegroundService(this, hudIntent)
                    log("✓ Đã bật bảo vệ nền (giảm nhiệt tự động, dọn cache lúc yên tĩnh)")
                } catch (e: Exception) { }

                // Không xin Battery Optimization cho game: quyền này không phải cơ chế
                // tăng FPS đáng tin cậy và thường gây vòng lặp/hộp thoại trên OEM.
                setOptimizingStatus("Bước 3/3: Kiểm tra game và mở...")
                mainHandler.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    showOptimizingUi(false)
                    launchGame(gamePackage, gameLabel)
                }, 250)
            }
        }.start()
    }

    private fun launchGame(gamePackage: String, gameLabel: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(gamePackage)
        if (launchIntent != null) {
            log("Đã tối ưu xong, đang mở $gameLabel")
            startActivity(launchIntent)
        } else {
            log("Chưa tìm thấy $gameLabel trên máy")
            Toast.makeText(this, "Chưa cài đặt $gameLabel trên máy này", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOptimizingUi(show: Boolean) {
        binding.cardOptimizing.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setOptimizingStatus(text: String) {
        binding.tvOptimizingStatus.text = text
    }


    // ---------- NHẬT KÝ ----------

    private fun log(message: String) {
        val current = binding.tvLog.text
        binding.tvLog.text = "• $message\n$current"
    }
}
