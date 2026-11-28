# TubiTV CloudStream 4.5.2 Plugin (com.lagradost)

## 1. Tính năng chính
- **ClipboardLogger**: Tự động copy toàn bộ log (kiểm tra geo-block, số lượng server, độ phân giải HLS, kết quả OpenSubtitles/SubDL) vào Clipboard Android.
- **Nhiều nguồn server & chất lượng video**:
  - Đọc toàn bộ `video_resources` (`hlsv3`, `hlsv6`, `mp4`) + WebView fallback.
  - Tự động phân tích master `.m3u8` (`#EXT-X-STREAM-INF`) để tách riêng các mức `2160p`, `1080p`, `720p`, `480p`, `360p`.
  - Lọc bỏ an toàn các luồng có Widevine/PlayReady DRM (không phát được trên CloudStream).
- **Tự động nạp 02 phụ đề Việt + 01 phụ đề Anh**:
  - Tự động map tiêu đề Tubi sang TMDB/IMDb ID.
  - Gọi song song **OpenSubtitles** (REST + V3) và **SubDL** (giải nén `.zip` tức thì qua loopback proxy nội bộ).
  - Chọn lọc đúng **2 phụ đề Tiếng Việt** + **1 phụ đề Tiếng Anh** rõ ràng.

## 2. Cách xử lý khi TubiTV chặn IP (Geo-Block HTTP 403)
TubiTV chặn IP tại tầng CDN biên (chỉ cho phép US, CA, UK, AU, MX). Bạn có 2 cách hợp lệ để truy cập:
1. **Cách ổn định nhất (Khuyên dùng)**: Bật VPN sang **Mỹ (US)** hoặc **Canada** trên Android (VD: ProtonVPN, Mullvad, WireGuard, hoặc 1.1.1.1 WARP+ khu vực US). Lưu ý: nhiều IP datacenter miễn phí cũng bị Tubi chặn, nên chọn server VPN ít người dùng hoặc residential.
2. **Dùng Proxy / Relay riêng trong Cài đặt Plugin (biểu tượng bánh răng ⚙️)**:
   - Nhập **HTTP/SOCKS5 Proxy** (`host:port`, VD `127.0.0.1:1080`) nếu bạn chạy tunnel SSH/Shadowsocks/V2Ray qua VPS đặt tại Mỹ.
   - Hoặc nhập **Relay URL** đặt trên VPS Mỹ của riêng bạn.
