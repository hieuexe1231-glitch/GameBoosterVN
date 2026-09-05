# GameBoosterVN RealEngine v1.6 — Universal Sustained Performance

Mục tiêu: tối ưu mọi game và ưu tiên độ ổn định khi chơi lâu trên Android tầm thấp/trung.

## Nguyên tắc
- Không root, không ép xung CPU/GPU.
- Không đổi governor.
- Không giảm độ phân giải/đồ họa game.
- Không trim cache hệ thống.
- Không xóa cache game.
- Không force-stop app định kỳ trong trận.
- Không compile ART mỗi lần mở game.
- Không can thiệp thermal policy.
- Adaptive monitoring có hysteresis và polling thưa để giảm overhead.

## Universal Game Profile
- Android Game Mode Performance nếu ROM hỗ trợ.
- Doze whitelist cho game.
- Đánh dấu game active.
- Dọn tối đa 2 app người dùng trước khi mở game, không dọn trong trận.
- Tự nhận diện Liên Quân, Free Fire và game khác bằng package.

## Long-session stability
- Khi nhiệt pin cao, profile chuyển sang THERMAL-SAFE thay vì cố ép hiệu năng.
- CPU/RAM/nhiệt/frame-jank được lấy thưa.
- Khi trạng thái ổn định, giảm polling hơn nữa.
- Frame-jank chỉ đọc gfxinfo khoảng 60 giây/lần.

## Build
```bash
gradle :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`
