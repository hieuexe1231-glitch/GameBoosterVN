# GameBoosterVN RealEngine v1.8 — Tối ưu thật cho máy yếu, ưu tiên hành động hơn đo đạc

Mục tiêu: tối ưu mọi game và ưu tiên độ ổn định khi chơi lâu trên Android tầm thấp/trung,
**đặc biệt lệch hẳn về hướng máy cấu hình thấp** thay vì làm giống nhau cho mọi máy.

## Máy yếu được xử lý KHÁC máy thường (không chỉ đo nhiều hơn)
Dựa trên `ActivityManager.isLowRamDevice()` (API chính thức Android):
- Dọn RAM trước trận mạnh tay hơn hẳn (tối đa 5 app thay vì 2) - RAM là đòn bẩy #1 của
  máy yếu, vì hệ thống dễ tự kill tiến trình game khi thiếu RAM giữa trận.
- Cảnh báo áp lực RAM sớm hơn (88% thay vì 93%) để có thời gian dọn trước khi hệ thống
  tự ý can thiệp.
- Bỏ hẳn việc đọc frame-jank (dumpsys gfxinfo) trong trận - bản thân việc đo cũng tốn
  CPU/IO, máy yếu cần dồn ngân sách đó cho hành động thật.
- Polling đo CPU/RAM/nhiệt thưa hơn (tối thiểu 20s thay vì 12s).
- Ngưỡng an toàn nhiệt hạ thấp hơn (40°C thay vì 42°C) vì tản nhiệt vật lý thường kém hơn.

## Hành động tối ưu thật mới (không cần Shizuku/root)
- Tắt đồng bộ tài khoản nền (`ContentResolver.setMasterSyncAutomatically`) suốt phiên
  chơi, tự bật lại khi thoát game - giảm CPU/mạng ngầm định kỳ, quyền thường không cần
  người dùng xác nhận.

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

## AI học trên máy (v1.9 — offline, miễn phí, riêng tư)
Đây là học thống kê nhẹ (EMA - trung bình trượt có trọng số qua nhiều phiên chơi), **không
phải mạng nơ-ron hay AI nặng** - nói đúng bản chất để không cường điệu. Toàn bộ lưu bằng
SharedPreferences ngay trên máy, không có bước nào gửi dữ liệu ra ngoài.

Sau mỗi phiên chơi (≥ 90 giây), app ghi lại: RAM% ngay trước lần đầu bị nghẽn hiệu năng,
số app đã dọn trước trận có đủ hay không, và game này có lặp lại nghẽn nhiệt hay không.
Từ phiên thứ 3 trở đi cho cùng 1 game, các ngưỡng học được sẽ thay cho số cố định:
- Ngưỡng RAM báo động (thay cho 88%/93% cố định).
- Số app nên dọn trước trận (thay cho 2-5 theo cấu hình máy chung).
- Phản ứng giảm sáng hạ nhiệt sớm hơn nếu game này từng lặp lại nóng máy nhiều lần.

Mọi giá trị học được đều bị kẹp trong khoảng an toàn đã kiểm chứng (RAM 70-95%, dọn 2-6
app) - "học" không bao giờ đẩy app ra khỏi vùng hành vi an toàn.

## v1.12 — Feedback Loop, nhiệt độ chính thức, bảo vệ IME/Accessibility
Đối chiếu với bộ tiêu chí "AI Game Engine" (Detect/Diagnose/Decide/Optimize/Verify):
- **Feedback Loop + tự rollback thật**: sau khi bật Fixed Performance Mode, AI hẹn giờ
  kiểm tra lại sau ~75 giây; nếu lúc đó máy đang nóng/khẩn cấp, tự tắt lại ngay trong
  phiên (không cần người dùng phát hiện lag rồi tắt tay).
- **Tín hiệu nhiệt chính thức**: dùng `PowerManager.getCurrentThermalStatus()` (API 29+)
  - do chính hãng máy cấu hình theo tản nhiệt thật - thay vì chỉ đoán một ngưỡng độ C.
- **Phát hiện xu hướng nhiệt tăng dần** (không chỉ chờ vượt ngưỡng tuyệt đối).
- **Mức khẩn cấp**: bỏ qua độ trễ chờ mẫu khi tín hiệu quá rõ ràng (SEVERE/CRITICAL chính
  thức, hoặc giật khung hình >=25%).
- **Bảo vệ IME/Accessibility đang bật** khi dọn app nền - đọc thẳng từ Settings hệ thống.

**Thành thật về giới hạn:** không thể đo GPU usage % (không có API công khai trên Android
không-root) và không tính chính xác "1% Low FPS" theo phân vị khung hình (cần timestamp
từng khung hình mà chỉ instrumentation bên trong chính game mới lấy được) - app dùng tỷ lệ
khung hình giật (`dumpsys gfxinfo`) làm chỉ số tương đương, phục vụ mục đích tương tự.

## v1.24 — Xác nhận đã dùng hết ADPF cho tool bên ngoài + thêm kiểm tra bảo trì hàng ngày
**Xác nhận từ trang chính thức developer.android.com/games/optimize/adpf:** ADPF (bộ
framework tối ưu game chính thức của Google) chỉ có đúng 4 trụ cột - Thermal API, Game
Mode/State API, Fixed Performance Mode, Power Efficiency Mode. App đã dùng 3/4 cái; cái còn
lại (Power Efficiency Mode, thuộc Performance Hint API) **chỉ gọi được từ BÊN TRONG chính
tiến trình của game** - không có cách nào cho 1 app bên ngoài như GameBoosterVN gọi hộ, kể
cả có Shizuku. Nghĩa là app đã khai thác hết phần ADPF mà 1 tool ngoài có thể chạm tới -
không phải thiếu sót, mà là giới hạn cứng của nền tảng.

**Học thêm từ CacheVoid** (module tự dọn cache theo lịch hàng ngày, tìm thấy ở v1.23) và áp
dụng bản AN TOÀN HƠN: thêm `DailyMaintenanceWorker` (WorkManager - thư viện chính thức
Jetpack, không cần Shizuku để lên lịch) kiểm tra bộ nhớ máy mỗi ngày. Khác CacheVoid ở chỗ
job này **không tự ý dọn/dừng gì cả** - chỉ hiện thông báo nếu bộ nhớ sắp đầy, giữ đúng
nguyên tắc "người dùng luôn chủ động" đã có xuyên suốt app từ đầu.

## v1.23 — Nghiên cứu app/module tối ưu máy yếu tốt nhất trên GitHub
Đã khảo sát các Magisk module phổ biến (Low-end_Magisk_Performance_Optimizer, SpeedCool,
Gaming-X...) - toàn bộ đều CẦN ROOT, ngoài phạm vi app này. Đáng chú ý nhất là **FrameX**
(github.com/MaheshSharan/FrameX-Android) - dự án mã nguồn mở đang phát triển tích cực (140
sao), không root, dùng Shizuku, làm rất giống hướng đi của app này. Đối chiếu học được:

**Đã áp dụng ngay - lỗi thật, sửa được:** kỹ thuật "Crash-Safe Snapshot" của FrameX - chụp
lại cài đặt GỐC trước khi đổi, khôi phục đúng giá trị đó thay vì đoán mặc định. Phát hiện
đúng app này mắc lỗi y hệt: tắt hiệu ứng chuyển cảnh hệ thống (ghi cứng "0"), lúc khôi phục
lại ghi cứng "1" (mặc định Android) - nếu người dùng từng tự chỉnh tốc độ hiệu ứng riêng
(vd 0.5x cho mượt tay hơn), app sẽ âm thầm reset về 1.0x sau mỗi trận mà không ai yêu cầu.
Đã sửa: đọc giá trị gốc bằng `settings get` trước khi đổi, lưu lại, khôi phục đúng giá trị
đó ("1" chỉ dùng khi không đọc được gì).

**Tìm thấy nhưng CHỦ ĐỘNG KHÔNG áp dụng - thành thật lý do:**
- *`dumpsys SurfaceFlinger --timestats`* (FrameX dùng để đo FPS chính xác hơn, ở tầng
  compositor hệ thống thay vì renderer riêng từng hãng máy) - về lý thuyết đáng tin hơn
  `gfxinfo` hiện tại, NHƯNG định dạng output phức tạp và có thể đổi khác giữa các bản
  Android mà không có máy thật để kiểm chứng chính xác. Viết bộ phân tích cú pháp mà không
  chắc đúng format sẽ nguy hiểm hơn cả việc không có tính năng đó - số liệu sai âm thầm đi
  vào quyết định của AI. Để dành cho lần cập nhật có điều kiện kiểm thử trên máy thật.
- *Vivo OriginOS hardware engine hook* - chỉ hoạt động trên đúng dòng máy Vivo/OriginOS,
  không phải kỹ thuật phổ quát, ngoài phạm vi app này.
- *"ART RAM heap compaction"* (khả năng dùng lệnh nội bộ `am compact`) - chưa đủ tự tin về
  cú pháp chính xác và mức độ an toàn khi gọi giữa trận để đưa vào lúc này.

## v1.22 — Vì sao KHÔNG "mạnh tay" lúc giao tranh đông người, và cách đúng để mượt lúc đó
**Quan điểm kỹ thuật rõ ràng:** can thiệp mạnh tay ĐÚNG LÚC giao tranh là hướng SAI - lúc
đó CPU/GPU đã bận nhất, bất kỳ lệnh nào chen vào (dù chỉ vài trăm ms) đều tranh tài nguyên
với chính khung hình đang cần render, làm giật THÊM chứ không giúp gì. Đây là lý do app luôn
"im lặng tuyệt đối" khi phát hiện combat/nghẽn - nguyên tắc này KHÔNG đổi.

**Cách đúng:** chuẩn bị sẵn TỪ TRƯỚC combat, không phải can thiệp TRONG combat. Fixed
Performance Mode (đã có từ trước) giải quyết đúng vấn đề "giật khi giao tranh đột ngột nổ
ra" bằng cách giữ xung nhịp CPU/GPU ổn định sẵn từ đầu trận - máy không phải mất thời gian
"tăng tốc từ mức thấp lên cao" khi combat bất ngờ ập đến (khoảng trễ tăng tốc này chính là
nguyên nhân giật khung hình đột ngột trên máy không tối ưu).

**Cải thiện thêm (an toàn, không đổi nguyên tắc):** dọn dẹp định kỳ lúc "yên tĩnh" giờ tự
kiểm tra CPU bận thật (đo tức thời 150ms, không dùng xung nhịp vì Fixed Performance Mode cố
tình giữ xung nhịp cao ổn định suốt trận nên dễ đo sai) ngay trước khi thực thi - nếu CPU đã
tăng vọt (rất có thể giao tranh vừa bắt đầu ngay sau lần đo trước), huỷ ngay, đợi đợt yên
tĩnh kế tiếp. Giảm tối đa rủi ro trùng đúng lúc combat mới bắt đầu.

**Điều app KHÔNG thể làm (thành thật):** giảm chất lượng đồ hoạ/hiệu ứng CỦA CHÍNH GAME lúc
giao tranh (ví dụ giảm hiệu ứng kỹ năng khi đông người) - việc này nằm trong chính engine
render của game, ngoài tầm với của công cụ hệ thống không root/không hook vào tiến trình
game. Nếu game có tuỳ chọn đồ hoạ riêng (thường có mức "mượt"/"cân bằng"/"đẹp"), chỉnh trực
tiếp trong game vẫn là cách hiệu quả nhất cho đúng lúc giao tranh đông người.

## v1.21 — Sửa lỗi kiến trúc "trận 1 bình thường, trận 2 lag hơn rất nhiều"
**Nguyên nhân (khác hẳn mọi lỗi học AI đã sửa ở trên - đây là lỗi kiến trúc):** khi chơi
xong 1 trận và vào trận tiếp theo (game vẫn mở, chỉ đổi ván), `HudOverlayService` KHÔNG hề
bị huỷ và tạo lại - nó là đúng 1 tiến trình chạy liên tục xuyên suốt vì vẫn đang ở trong
game (không trigger auto-stop). Hậu quả: các cờ "chỉ chạy 1 lần cho cả phiên" (hẹn giờ kiểm
tra Feedback Loop, đã-verify-hay-chưa...) vẫn giữ nguyên từ trận 1 sang trận 2 - Feedback
Loop đã "dùng hết" ở trận 1, trận 2 dù có vấn đề nhiệt thật (nhiệt tích luỹ từ trận 1 dồn
sang) cũng KHÔNG còn ai kiểm tra/rollback nữa. Đồng thời dữ liệu học bị gộp nhầm của nhiều
trận thành 1 "phiên" duy nhất, sai lệch hoàn toàn.

**Đã sửa:** `MainActivity` gửi kèm tín hiệu `new_session=true` mỗi lần người dùng bấm "Bắt
đầu chơi" - dùng đúng thời điểm THẬT SỰ người dùng bắt đầu 1 trận mới (thay vì đoán qua
trạng thái nội bộ). `HudOverlayService` nhận tín hiệu này sẽ: chốt dữ liệu học của trận vừa
xong (không để mất), khôi phục độ sáng nếu đang giảm dở, rồi reset sạch toàn bộ cờ trạng
thái để trận mới có đầy đủ Feedback Loop/rollback riêng - không bị "dùng ké" từ trận trước.

## v1.20 — 2 nguyên nhân "tích luỹ theo ngày" khác, bổ sung thêm cho v1.19
**#1 - Sạc pin trong lúc chơi làm AI học sai:** sạc pin tự nhiên làm máy nóng hơn hẳn -
không liên quan gì tới game hay tối ưu. Trước đây tín hiệu nóng lúc đang sạc vẫn được tính
vào việc học "game này hay nóng máy" - vài ngày có sạc-vừa-chơi cộng dồn lại sẽ khiến AI
dần né tối ưu (Fixed Performance Mode) cho game đó một cách oan uổng, dù phần lớn ngày
thường (không sạc) không hề nóng. Đã sửa: kiểm tra `BatteryManager.isCharging` mỗi lần ghi
nhận tín hiệu nóng - nếu đang sạc, VẪN rollback bình thường (an toàn thiết bị lúc đó quan
trọng hơn bất kể lý do), nhưng KHÔNG tính vào dữ liệu học cho các trận sau.

**#2 - Bộ nhớ máy đầy dần theo ngày làm chậm cả hệ thống:** đây là hiện tượng thật của
Android/Linux (không phải do app) - khi dung lượng trống xuống quá thấp, tốc độ đọc/ghi
file TOÀN MÁY chậm hẳn đi, không riêng lúc chơi game. App đã có "Dọn cache sâu" (dừng toàn
bộ app nền + trim-caches) nhưng trước đây chỉ chạy khi tự bấm. Đã thêm: chủ động kiểm tra
dung lượng trống mỗi lần mở app, nhắc dùng "Dọn cache" nếu dưới 10% - tối đa 1 lần/ngày để
không làm phiền.

## v1.19 — Cảnh báo khi tối ưu bị TẮT ÂM THẦM (nguyên nhân phổ biến nhất cho "lúc đầu mượt,
## về sau lag lại")
**Nguyên nhân thực tế** (khác hẳn các lỗi AI học sai đã sửa ở trên): với app dùng Shizuku,
lý do phổ biến NHẤT khiến hiệu năng "tụt dần theo thời gian" không phải do logic tối ưu tệ
đi, mà do 2 điều kiện tiên quyết bị thu hồi âm thầm mà app không hề báo:
1. **Shizuku tự ngắt sau khi khởi động lại máy** - Shizuku (qua Gỡ lỗi không dây) không tự
   khởi động lại sau khi tắt/mở máy, người dùng dễ quên mở lại. Từ lúc đó MỌI tối ưu nâng
   cao (Fixed Performance Mode, Game Mode, dọn app nền, Standby Bucket...) âm thầm ngừng
   chạy, app chỉ còn tối ưu Android cơ bản (yếu hơn nhiều) - nhưng KHÔNG có gì báo cho người
   dùng biết, nên cảm giác giống như "app dở đi theo thời gian".
2. **Quyền "Bỏ qua tối ưu hoá pin" bị hãng máy tự thu hồi** - các ROM Trung Quốc (MIUI,
   ColorOS, FuntouchOS) nổi tiếng tự tắt lại quyền này sau vài ngày theo đánh giá riêng của
   hãng, kể cả khi người dùng đã cấp thủ công trước đó.

**Đã thêm (`HealthCheckHelper.kt`):** ghi nhớ "đã từng hoạt động tốt" cho cả 2 điều kiện
trên; mỗi lần mở app và mỗi lần chuẩn bị vào game, so sánh với trạng thái hiện tại - nếu
phát hiện đã TỪNG tốt mà giờ không còn, hiện ngay dialog cảnh báo rõ ràng kèm nút bấm mở
thẳng Shizuku / cài đặt pin để sửa - thay vì để người dùng tự đoán vì sao "lại lag như cũ".

## v1.18 — Sửa "mượt lúc đầu, lag về sau" TRONG CÙNG 1 TRẬN (cả FF lẫn LQ)
**Nguyên nhân:** dù dọn app nền kỹ tới đâu trước trận, Android có thể tự khởi động lại
nhiều app nền GIỮA TRẬN (báo thức hệ thống, JobScheduler, thông báo đẩy...). App tuyệt đối
không đụng gì tới tiến trình khác trong lúc đang chơi (đúng nguyên tắc an toàn từ đầu), nên
hiệu quả dọn dẹp trước trận phai dần khi trận kéo dài 15-30 phút - đúng hiện tượng người
dùng mô tả, xảy ra với MỌI game dài chứ không riêng FF hay LQ.

**Đã sửa bằng App Standby Bucket** - cơ chế CHÍNH THỨC của Android (từ Android 9) để hạn
chế tần suất báo thức/job của app ít ưu tiên, nhẹ tay hơn hẳn force-stop nhưng có tác dụng
kéo dài cả ngày chứ không chỉ tức thời:
1. Sau khi dọn app trước trận, đặt luôn Standby Bucket "restricted" cho các app đó.
2. **Mới, quan trọng hơn:** trong lúc đang chơi, ở những lúc máy đang "yên tĩnh" (không
   nghẽn gì), tự quét và hạn chế Standby Bucket cho bất kỳ app nào MỚI XUẤT HIỆN kể từ lúc
   vào game - bắt đúng nhóm app tự khởi động lại giữa trận. Việc này AN TOÀN để làm giữa
   trận vì KHÔNG force-stop bất kỳ tiến trình đang chạy nào, chỉ giảm tần suất chạy nền
   TƯƠNG LAI của nó - không vi phạm nguyên tắc "không đụng tiến trình khác khi đang chơi".
3. Tự động trở lại bình thường ngay khi người dùng mở lại app đó - không cần bước khôi phục
   thủ công, không có rủi ro "quên bật lại".

## v1.17 — Sửa lỗi bỏ sót thủ phạm CPU thật (nguyên nhân "chơi FF vẫn lag")

**Lỗi tìm ra:** danh sách app "cần dọn trước trận" trước đây chỉ lấy từ lịch sử app người
dùng **vừa mở lên gần đây** (Usage Stats foreground). Free Fire là game nghẽn CPU (theo số
liệu CPU bottleneck 72%, GPU chỉ 18% - khác hẳn game nặng GPU như Liên Quân), mà nguyên
nhân nghẽn CPU điển hình là các **dịch vụ chạy nền sẵn** (SDK quảng cáo, đồng bộ, giữ kết
nối...) - loại này thường KHÔNG NẰM trong lịch sử "vừa mở gần đây" vì người dùng có thể
chưa từng mở chúng lên màn hình. App cũ hoàn toàn bỏ sót nhóm thủ phạm này.

**Đã sửa:** thêm `getActuallyRunningPackages()` - dùng quyền shell của Shizuku chạy lệnh
`ps -A` để lấy đúng danh sách tiến trình **THẬT SỰ đang chạy** ngay lúc đó (Java API
`getRunningAppProcesses()` bị Android chặn không cho xem tiến trình app khác từ Android
5.1 trở đi, đây là lý do phải dùng Shizuku). Danh sách này được ưu tiên trước danh sách
"vừa mở gần đây" khi chọn app để dọn trước trận và khi dọn sâu cache. Đồng thời sửa luôn 1
lỗi phụ: cả 2 hàm trước đó lỡ bắt buộc phải có quyền Usage Access mới chạy, dù phần dùng
Shizuku hoàn toàn không cần quyền đó - khiến máy có Shizuku nhưng chưa cấp Usage Access bị
bỏ lỡ oan uổng phần dọn qua Shizuku.

## v1.16 — Sửa lỗi AI tự học sai khiến game nặng GPU (như Liên Quân) bị thiếu tối ưu
**Lỗi tìm ra:** thermal_rate (dùng để quyết định né Fixed Performance Mode) được học từ
mức "MODERATE" - mức ẤM BÌNH THƯỜNG của bất kỳ game nặng GPU nào, không phải dấu hiệu tweak
có hại. Hậu quả: các game như Liên Quân chỉ cần ấm lên bình thường sau ~75s là bị:
1. Tự rollback Fixed Performance Mode ngay trong phiên (dù không phải do tweak gây ra).
2. Học sai thành "game này hay nóng máy" → các trận SAU né hẳn Fixed Performance Mode và
   Game Mode PERFORMANCE ngay từ đầu → tự làm giảm chính lợi ích tối ưu qua thời gian.

**Đã sửa:** tách 2 việc dùng chung nhầm 1 ngưỡng trước đây - phản ứng giảm sáng màn hình
(ít rủi ro) vẫn nhạy như cũ, còn tín hiệu HỌC + ROLLBACK giờ đòi bằng chứng mạnh hơn hẳn:
thermal status phải đạt SEVERE (hệ thống THẬT SỰ đang giảm hiệu năng vì nhiệt), không phải
chỉ MODERATE. Có thêm bước di trú tự động: xoá riêng dữ liệu thermal_rate đã học sai từ bản
cũ (giữ nguyên toàn bộ dữ liệu RAM/số app dọn, không liên quan tới lỗi này) một lần duy nhất
khi cập nhật lên bản này - không phải đợi hàng chục phiên để tự "quên" dần.


Tiếp tục đối chiếu roadmap "AI Game Engine v2":
- **Booster Overhead Monitor** (đánh dấu "đặc biệt còn thiếu" - ưu tiên cao nhất): tự đo
  CPU%/RAM của CHÍNH booster qua `/proc/self/stat` + `ActivityManager.getProcessMemoryInfo`
  - không cần quyền gì thêm. Nếu chính booster chiếm CPU >=3%, tự kéo dài chu kỳ đo thêm
  10s để giảm bớt overhead - hiển thị công khai trong HUD (`Booster x.x%/yy MB`).
- **I/O wait signal**: tận dụng lại field có sẵn trong `/proc/stat` (không đọc thêm file)
  để phát hiện Bottleneck.IO_STORAGE - máy đang chờ đọc lưu trữ (loading asset/shader),
  không phải CPU/GPU nghẽn thật, AI sẽ không nhầm sang xử lý RAM/nhiệt.
- **Dự đoán nhiệt độ**: ngoại suy tuyến tính đơn giản (tốc độ °C/phút hiện tại chiếu tới
  ngưỡng 45°C) - gọi đúng tên là phép toán ngoại suy, không gọi "AI dự đoán" để không
  cường điệu.

**Cân nhắc và quyết định KHÔNG làm (thành thật lý do):**
- *Frame Stability Index từ `dumpsys gfxinfo framestats`*: định dạng cột dữ liệu thô đổi
  khác nhau giữa các đời Android/hãng máy, dễ parse sai mà không biết - rủi ro cao hơn giá
  trị mang lại lúc này. jank% hiện tại vẫn là chỉ số ổn định hơn dù thô hơn.
- *Confidence Engine (% xác suất bottleneck)*: bộ phân loại hiện tại là rule-based (so
  ngưỡng), không phải mô hình xác suất thật - hiển thị số % "độ tin cậy" sẽ là bịa số liệu.
  Hysteresis (chờ đủ mẫu) + ngưỡng khẩn cấp đã phục vụ đúng mục đích "không tối ưu khi
  chưa chắc chắn" mà không cần con số giả.
- *A/B Testing giữa các profile*: muốn công bằng cần chủ động THỬ cả 2 phương án trong
  cùng điều kiện - nghĩa là có phiên bị cố tình chơi ở cấu hình kém hơn để so sánh, đánh
  đổi trải nghiệm thật của người dùng cho việc thử nghiệm. Không làm.
- *Game Phase Detection (lobby/loading/combat...)*: cần đọc nội dung màn hình (OCR/chụp
  màn hình) - vượt phạm vi và có rủi ro riêng tư. Cơ chế hysteresis "im lặng khi đang
  nghẽn" hiện tại đã đạt hiệu quả tương tự mà không cần biết chính xác đang ở giai đoạn nào.

```bash
gradle :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`
