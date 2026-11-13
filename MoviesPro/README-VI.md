# MoviesPro (CloudStream)

## Cài vào project
1. Copy thư mục `MoviesPro` vào root repo `noname2-main`.
2. Thêm `include(":MoviesPro")` vào `settings.gradle.kts`.
3. Build: `gradlew.bat :MoviesPro:make` (hoặc task build plugin của repo).

## Chẩn đoán
Logger `MoviesProClipboardLogger` tích lũy log trong phiên load/search/loadLinks; mỗi giai đoạn copy toàn bộ buffer vào clipboard. Mở Logcat tag `MoviesProDiag` nếu clipboard không được cập nhật. Nội dung có giới hạn 55 KB và che các query token/key/signature phổ biến.

## Tình trạng
Bản này là provider khởi đầu có parser HTML tổng quát (WordPress-like) và dò các iframe/track/link được công khai trên trang. Website moviespro.top được đọc thành công ở trang chủ; tuy nhiên cấu trúc trang detail/player và extractor cụ thể chưa được xác minh vì đường Watch/detail bị timeout trong lần kiểm tra. Vì vậy đây **chưa phải bản xác nhận phát được**. Không tự động vượt CAPTCHA, DRM, paywall hay access control. Nếu link video nằm trong JS/API hoặc player cần tương tác, log sẽ ghi ứng viên nhưng cần bổ sung extractor theo dữ liệu được phép truy cập.
