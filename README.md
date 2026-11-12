# MiniBrowser - Trình duyệt Web Mini (Java)

Ứng dụng trình duyệt web đa tab mini được phát triển bằng **Java Swing** và **JavaFX WebView / WebEngine** (WebKit core), tích hợp công cụ kiểm tra HTTP và quản lý tải xuống.

---

## 🌟 Tính năng chính

- 📑 **Duyệt web đa tab (Tabbed Browsing):** Mở nhiều tab cùng lúc, đóng tab bằng nút `✕` hoặc click chuột giữa (Middle Mouse Click) lên tab. Hỗ trợ cả Tab duyệt Web tiêu chuẩn và Tab xem Media (Video, Audio, PDF, Tệp).
- 🔗 **Thanh địa chỉ thông minh (Smart Address Bar):**
  - Tự động chuẩn hóa địa chỉ URL (`http://`, `https://`, `about:`, `file:`).
  - Tự động chuyển cụm từ tìm kiếm thành truy vấn Google Search khi không phải định dạng URL / Hostname.
  - Tự động bôi đen toàn bộ địa chỉ khi focus để thuận tiện gõ URL mới.
- 🧭 **Bộ nút điều hướng chuẩn:**
  - `◀` Quay lại (Back - `Alt + ←`)
  - `▶` Tiến tới (Forward - `Alt + →`)
  - `⟳` Tải lại (Reload - `F5`)
  - `⌂` Về trang chủ (Home - Google)
  - `+` Mở tab mới (`Ctrl + T`)
- 📥 **Quản lý tải xuống (Download Manager):**
  - Tải file qua HTTP GET đa luồng ngầm bằng `java.net.http.HttpClient`.
  - Cửa sổ theo dõi tiến trình tải xuống chi tiết (tên file, dung lượng, % tiến độ, trạng thái).
  - Tự động mở file sau khi tải xong nếu được tuỳ chọn.
- 📜 **Lịch sử duyệt web (History Manager):**
  - Lưu vết lịch sử duyệt web tự động vào thư mục cấu hình cá nhân (`~/.minibrowser/history.tsv`).
  - Cửa sổ tìm kiếm, lọc nhanh theo tiêu đề hoặc URL, xoá lịch sử duyệt web.
- 🛠 **Công cụ kiểm tra HTTP (HTTP Client Tool):**
  - Hỗ trợ gửi các phương thức HTTP: `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS`.
  - Cấu hình request header và request body dễ dàng.
  - Xem chi tiết response status, response headers và response body (tương tự Postman mini).
- 🎬 **Hỗ trợ Media & File Viewer:**
  - Tự động nhận diện định dạng nội dung qua `Content-Type` hoặc phần mở rộng tệp.
  - Nhúng trình phát HTML5 Video / Audio trực tiếp trong tab duyệt web.
- 📊 **Thanh tiến trình (Progress Bar):** Hiển thị trực quan tốc độ và trạng thái tải trang web.
- 🪟 **Xử lý Popup & Link ngoài:** Tự động bắt sự kiện `target="_blank"` hoặc JavaScript `window.open` để mở trong tab mới thay vì bị chặn.
- ⌨️ **Phím tắt hỗ trợ:**
  - `Ctrl + T`: Mở tab mới
  - `Ctrl + W`: Đóng tab hiện tại
  - `Ctrl + L`: Nhảy nhanh con trỏ vào thanh địa chỉ
  - `Ctrl + H`: Mở lịch sử duyệt web
  - `Ctrl + J`: Mở quản lý tải xuống
  - `Ctrl + K`: Mở công cụ kiểm tra HTTP
  - `F5`: Tải lại trang web
  - `Alt + ←` / `Alt + →`: Quay lại / Tiến tới trang trước - sau

---

## 🛠 Yêu cầu hệ thống & Công nghệ

- **Java JDK:** 17 trở lên
- **JavaFX:** 21.0.2 (`javafx-controls`, `javafx-web`, `javafx-swing`)
- **Giao diện:** Java Swing kết hợp `JFXPanel`
- **Công cụ build:** Apache Maven

---

## 🚀 Hướng dẫn chạy ứng dụng

### 1. Chạy trực tiếp từ IDE (IntelliJ IDEA / VS Code / Antigravity IDE)
- Mở thư mục dự án `minibrowser` trong IDE.
- Mở file `src/main/java/MiniBrowser.java`.
- Bấm nút **Run** (chạy hàm `main`).

### 2. Chạy bằng Maven qua dòng lệnh
```bash
mvn clean compile javafx:run
```
hoặc đóng gói ứng dụng:
```bash
mvn clean package
```

---

## 📁 Cấu trúc thư mục

```
minibrowser/
├── src/
│   └── main/
│       └── java/
│           ├── MiniBrowser.java       # Giao diện chính, điều hướng tab, menu toolbar
│           ├── DownloadManager.java   # Quản lý tải xuống ngầm và hiển thị tiến độ
│           ├── HistoryManager.java    # Quản lý, lưu trữ và tìm kiếm lịch sử duyệt web
│           ├── HttpService.java       # HTTP Client gửi GET/POST/HEAD/..., phân loại MIME
│           └── HttpToolDialog.java    # Hộp thoại kiểm tra & gửi request HTTP thủ công
├── pom.xml                            # Cấu hình phụ thuộc Maven (JavaFX Web, Controls, Swing)
├── .gitignore                         # Cấu hình bỏ qua thư mục build target và cấu hình IDE
└── README.md                          # Tài liệu hướng dẫn sử dụng và giới thiệu dự án
```
