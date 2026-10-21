package com.boostvn.gamebooster

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Cầu nối tới Shizuku - cho phép chạy lệnh ở mức "shell" (như ADB) mà không cần root.
 * Người dùng phải tự cài app Shizuku (CH Play) và bật qua "Gỡ lỗi không dây" trong
 * Tuỳ chọn nhà phát triển trước, app này chỉ xin quyền SỬ DỤNG Shizuku sau khi nó đã chạy.
 *
 * QUAN TRỌNG - THẬT THÀ VỀ GIỚI HẠN:
 * Ngay cả có Shizuku, quyền "shell" KHÔNG phải root hoàn toàn. Một số máy (do hãng khoá
 * SELinux chặt) vẫn có thể chặn 1 số lệnh. Các lệnh dưới đây (force-stop, svc wifi/data,
 * pm trim-caches) là lệnh ADB chuẩn, hoạt động trên phần lớn máy Android không root.
 */
object ShizukuHelper {

    const val REQUEST_CODE = 9001

    fun isShizukuAppRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        if (!isShizukuAppRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!isShizukuAppRunning()) return
            if (hasPermission()) return
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) { }
    }

    /**
     * Chạy 1 lệnh shell (mức ADB) qua Shizuku. Trả về true nếu chạy xong không lỗi.
     *
     * GHI CHÚ KỸ THUẬT: hàm Shizuku.newProcess() bị thư viện đánh dấu "private" (ẩn) từ
     * một số phiên bản gần đây, không gọi trực tiếp được nữa. Dùng reflection (Java) để
     * gọi vòng qua giới hạn này - cách làm phổ biến trong cộng đồng app dùng Shizuku.
     * Nếu về sau hàm bị đổi tên hoàn toàn, hàm này sẽ trả về false một cách an toàn
     * (không làm crash app) thay vì báo giả là đã chạy thành công.
     */
    fun runShellCommand(command: String): Boolean {
        if (!hasPermission()) return false
        return try {
            val process = newProcessReflective(command) ?: return false
            val waitForMethod = process.javaClass.getMethod("waitFor")
            waitForMethod.invoke(process)
            val exit = runCatching { (process.javaClass.getMethod("exitValue").invoke(process) as Number).toInt() }.getOrDefault(1)
            exit == 0
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Giống runShellCommand nhưng đọc luôn nội dung lệnh trả về (dùng để đọc file, ví dụ
     * nhiệt độ CPU trong /sys/class/thermal/). Trả về null nếu thất bại.
     */
    fun runShellCommandWithOutput(command: String): String? {
        if (!hasPermission()) return null
        return try {
            val process = newProcessReflective(command) ?: return null

            val getInputStream = process.javaClass.getMethod("getInputStream")
            val inputStream = getInputStream.invoke(process) as? java.io.InputStream ?: return null
            val output = inputStream.bufferedReader().readText().trim()

            val waitForMethod = process.javaClass.getMethod("waitFor")
            waitForMethod.invoke(process)

            output
        } catch (e: Throwable) {
            null
        }
    }

    private fun newProcessReflective(command: String): Any? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null)
        } catch (e: Throwable) {
            null
        }
    }
}
