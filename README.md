# MiniBrowser - Trình duyệt Web Mini (Java)

Ứng dụng trình duyệt web đa tab mini được phát triển bằng **Java Swing** và **JavaFX WebView / WebEngine** (WebKit core).

---

## 🌟 Tính năng chính

- 📑 **Duyệt web đa tab (Tabbed Browsing):** Mở nhiều tab cùng lúc, đóng tab bằng nút `✕` hoặc click chuột giữa (Middle Mouse Click) lên tab.
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
- 📊 **Thanh tiến trình (Progress Bar):** Hiển thị trực quan tốc độ và trạng thái tải trang web.
- 🪟 **Xử lý Popup & Link ngoài:** Tự động bắt sự kiện `target="_blank"` hoặc JavaScript `window.open` để mở trong tab mới thay vì bị chặn.
- ⌨️ **Phím tắt hỗ trợ:**
  - `Ctrl + T`: Mở tab mới
  - `Ctrl + W`: Đóng tab hiện tại
  - `Ctrl + L`: Nhảy nhanh con trỏ vào thanh địa chỉ
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
│           └── MiniBrowser.java   # Toàn bộ mã nguồn giao diện Swing & bộ xử lý JavaFX WebView
├── pom.xml                        # Cấu hình phụ thuộc Maven (JavaFX Web, Controls, Swing)
├── .gitignore                     # Cấu hình bỏ qua thư mục build target và cấu hình IDE
└── README.md                      # Tài liệu hướng dẫn sử dụng và giới thiệu dự án
```
