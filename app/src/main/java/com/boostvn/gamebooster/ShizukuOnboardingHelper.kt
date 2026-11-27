package com.boostvn.gamebooster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Nhận diện hãng điện thoại (OEM) để cung cấp hướng dẫn kích hoạt Shizuku chuẩn xác nhất.
 */
object ShizukuOnboardingHelper {

    data class OemGuide(
        val brandName: String,
        val steps: List<String>,
        val warningNote: String?
    )

    fun getDeviceSpecificGuide(): OemGuide {
        val manufacturer = Build.MANUFACTURER.lowercase()

        val base = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi") -> {
                OemGuide(
                    brandName = "Xiaomi / Redmi / POCO (HyperOS & MIUI)",
                    steps = listOf(
                        "1. Vào Cài đặt > Giới thiệu điện thoại > Chạm 7 lần vào 'Phiên bản OS/MIUI'.",
                        "2. Vào Cài đặt bổ sung > Tùy chọn nhà phát triển.",
                        "3. Bật 'Gỡ lỗi USB' và 'Gỡ lỗi không dây'.",
                        "4. BẮT BUỘC: Bật dòng 'Gỡ lỗi USB (Cài đặt bảo mật)' (Cần đăng nhập tài khoản Mi).",
                        "5. Mở app Shizuku và nhấn 'Khởi động' qua Gỡ lỗi không dây."
                    ),
                    warningNote = "Nếu thiếu bước 4, Xiaomi sẽ chặn mọi lệnh tối ưu của GameBoosterVN."
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                OemGuide(
                    brandName = "OPPO / Realme / OnePlus (ColorOS)",
                    steps = listOf(
                        "1. Vào Cài đặt > Giới thiệu thiết bị > Phiên bản > Chạm 7 lần vào 'Số bản dựng'.",
                        "2. Vào Cài đặt hệ thống > Tùy chọn nhà phát triển.",
                        "3. Bật 'Gỡ lỗi USB' và 'Gỡ lỗi không dây'.",
                        "4. Kéo xuống cuối cùng, bật 'Tắt giám sát quyền' (Disable permission monitoring).",
                        "5. Mở Shizuku và tiến hành ghép nối."
                    ),
                    warningNote = "Bắt buộc bật 'Tắt giám sát quyền' để giữ Shizuku không bị ngắt khi đang chơi game."
                )
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                OemGuide(
                    brandName = "Vivo / iQOO (FuntouchOS / OriginOS)",
                    steps = listOf(
                        "1. Vào Cài đặt > Giới thiệu về điện thoại > Thông tin phần mềm > Chạm 7 lần vào 'Số hiệu bản tạo'.",
                        "2. Vào Hệ thống > Tùy chọn nhà phát triển.",
                        "3. Bật 'Gỡ lỗi USB', 'Gỡ lỗi không dây' và 'Cho phép gỡ lỗi bảo mật'.",
                        "4. Mở Shizuku và khởi chạy."
                    ),
                    warningNote = null
                )
            }
            else -> {
                OemGuide(
                    brandName = "Samsung / Google / Android Tiêu chuẩn",
                    steps = listOf(
                        "1. Vào Cài đặt > Thông tin điện thoại > Thông tin phần mềm > Chạm 7 lần vào 'Số hiệu bản tạo'.",
                        "2. Quay lại Cài đặt > Tùy chọn nhà phát triển.",
                        "3. Bật 'Gỡ lỗi không dây' (Wireless Debugging).",
                        "4. Mở ứng dụng Shizuku > Ghép nối > Nhập mã ghép nối từ thông báo."
                    ),
                    warningNote = null
                )
            }
        }

        // app hỗ trợ từ Android 8 nhưng 'Gỡ lỗi không dây' chỉ có từ Android 11.
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val oldAndroid = "Máy đang chạy Android ${Build.VERSION.RELEASE} - chưa có 'Gỡ lỗi không dây' " +
                "(chỉ có từ Android 11). Cần cắm máy tính chạy ADB để khởi động Shizuku, xem hướng dẫn trong app Shizuku."
            base.copy(warningNote = listOfNotNull(base.warningNote, oldAndroid).joinToString("\n\n"))
        } else base
    }

    /**
     * Hiện hộp thoại hướng dẫn theo đúng hãng máy. Gọi khi Shizuku chưa chạy.
     * [onOpenShizukuOrStore] mở app Shizuku (nếu đã cài) hoặc trang tải về.
     */
    fun showGuideDialog(activity: Activity, shizukuInstalled: Boolean, onOpenShizukuOrStore: () -> Unit) {
        val guide = getDeviceSpecificGuide()
        val message = buildString {
            append(guide.steps.joinToString("\n"))
            guide.warningNote?.let { append("\n\n⚠ ").append(it) }
            if (!shizukuInstalled) append("\n\n(Bạn chưa cài app Shizuku - bấm 'Cài Shizuku'.)")
        }
        AlertDialog.Builder(activity)
            .setTitle("Bật Shizuku: ${guide.brandName}")
            .setMessage(message)
            .setPositiveButton("Mở Tùy chọn nhà phát triển") { _, _ -> openDeveloperSettings(activity) }
            .setNeutralButton(if (shizukuInstalled) "Mở Shizuku" else "Cài Shizuku") { _, _ -> onOpenShizukuOrStore() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    /**
     * Mở thẳng màn hình Tùy chọn nhà phát triển cho người dùng
     */
    fun openDeveloperSettings(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(flags))
        } catch (e: Exception) {
            // Fallback: chưa mở khoá Tùy chọn nhà phát triển -> mở trang Thông tin phần mềm; cuối cùng mới tới Cài đặt chung.
            try {
                context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(flags))
            } catch (e2: Exception) {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags)) }
            }
        }
    }
}
