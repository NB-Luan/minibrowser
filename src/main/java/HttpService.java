import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lớp HTTP client của trình duyệt: gửi GET / HEAD / POST (và PUT, DELETE, OPTIONS),
 * phân loại tài nguyên theo Content-Type / phần mở rộng (trang web, pdf, mp3, mp4, tệp khác).
 */
final class HttpService {

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/605.1.15 (KHTML, like Gecko) MiniBrowser/1.0";

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Loại dịch vụ/tài nguyên mà server trả về. */
    enum Kind { PAGE, PDF, AUDIO, VIDEO, FILE }

    static final class Result {
        int status;
        String version = "HTTP/1.1";
        String finalUrl;
        Map<String, List<String>> headers = Map.of();
        byte[] body = new byte[0];
        long millis;

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return String.join(", ", e.getValue());
            }
            return null;
        }
    }

    private HttpService() { }

    // ------------------------------------------------------------ request

    /**
     * Gửi một HTTP request.
     *
     * @param method     GET, HEAD, POST, PUT, DELETE, OPTIONS
     * @param headerText mỗi dòng một header dạng "Tên: giá trị" (có thể null)
     * @param body       nội dung gửi kèm (chỉ dùng cho POST/PUT/DELETE...)
     */
    static Result request(String method, String url, String headerText, String body)
            throws IOException, InterruptedException {
        method = method.toUpperCase(Locale.ROOT);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .setHeader("User-Agent", UA);
        if (headerText != null) {
            for (String line : headerText.split("\\R")) {
                int i = line.indexOf(':');
                if (i > 0) b.setHeader(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        }
        boolean hasBody = body != null && !body.isEmpty()
                && !method.equals("GET") && !method.equals("HEAD");
        if (method.equals("POST") && hasBody && !hasHeader(headerText, "Content-Type")) {
            b.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        }
        b.method(method, hasBody
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());

        long t0 = System.currentTimeMillis();
        HttpResponse<byte[]> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        Result r = new Result();
        r.millis = System.currentTimeMillis() - t0;
        r.status = resp.statusCode();
        r.version = resp.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
        r.finalUrl = resp.uri().toString();
        r.headers = resp.headers().map();
        r.body = resp.body() == null ? new byte[0] : resp.body();
        return r;
    }

    private static boolean hasHeader(String headerText, String name) {
        if (headerText == null) return false;
        for (String line : headerText.split("\\R")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------ classification

    private static final Set<String> PDF_EXT = Set.of("pdf");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "wav", "m4a", "aac", "aif", "aiff");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "m4v");
    private static final Set<String> FILE_EXT = Set.of(
            "zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "iso", "exe", "msi", "dmg", "apk",
            "jar", "deb", "rpm", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
            "avi", "mkv", "mov", "wmv", "flv", "webm", "ogg", "flac", "wma", "mpg", "mpeg", "3gp");

    /** Phân loại theo phần mở rộng của đường dẫn URL (bỏ query/fragment). */
    static Kind kindOfUrl(String url) {
        if (url == null) return Kind.PAGE;
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0) return Kind.PAGE;
        String ext = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (PDF_EXT.contains(ext)) return Kind.PDF;
        if (AUDIO_EXT.contains(ext)) return Kind.AUDIO;
        if (VIDEO_EXT.contains(ext)) return Kind.VIDEO;
        if (FILE_EXT.contains(ext)) return Kind.FILE;
        return Kind.PAGE;
    }

    /** Phân loại theo header Content-Type. */
    static Kind kindOfContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return Kind.PAGE;
        String ct = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (ct.equals("application/pdf")) return Kind.PDF;
        if (ct.equals("audio/mpeg") || ct.equals("audio/mp3") || ct.equals("audio/x-mpeg")
                || ct.equals("audio/wav") || ct.equals("audio/x-wav") || ct.equals("audio/wave")
                || ct.equals("audio/aac") || ct.equals("audio/x-m4a") || ct.equals("audio/mp4")) {
            return Kind.AUDIO;
        }
        if (ct.equals("video/mp4") || ct.equals("video/x-m4v")) return Kind.VIDEO;
        if (ct.startsWith("audio/") || ct.startsWith("video/")) return Kind.FILE;
        if (ct.startsWith("text/") || ct.startsWith("image/")) return Kind.PAGE;
        if (ct.equals("application/xhtml+xml") || ct.equals("application/json")
                || ct.equals("application/xml") || ct.equals("application/javascript")
                || ct.endsWith("+xml") || ct.endsWith("+json")) {
            return Kind.PAGE;
        }
        if (ct.startsWith("application/")) return Kind.FILE;
        return Kind.PAGE;
    }

    static Kind kindOfResult(Result r) {
        Kind k = kindOfContentType(r.header("Content-Type"));
        if (k == Kind.PAGE) {
            String cd = r.header("Content-Disposition");
            if (cd != null && cd.toLowerCase(Locale.ROOT).contains("attachment")) k = Kind.FILE;
            else if (r.finalUrl != null) k = kindOfUrl(r.finalUrl);
        }
        return k;
    }

    /**
     * Xác định URL là trang web hay tệp (pdf/mp3/mp4/...): xét phần mở rộng trước,
     * nếu không rõ thì gửi HEAD để đọc Content-Type. Lỗi mạng -> coi là PAGE.
     */
    static Kind detect(String url) {
        Kind k = kindOfUrl(url);
        if (k != Kind.PAGE) return k;
        try {
            Result r = request("HEAD", url, null, null);
            if (r.status >= 400) return Kind.PAGE;
            return kindOfResult(r);
        } catch (Exception e) {
            return Kind.PAGE;
        }
    }

    // ----------------------------------------------------------- file name

    private static final Pattern FILENAME_STAR =
            Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME =
            Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** Tên tệp lấy từ Content-Disposition, nếu không có thì từ đường dẫn URL. */
    static String fileName(String url, String contentDisposition, String contentType) {
        String name = null;
        if (contentDisposition != null) {
            Matcher m = FILENAME_STAR.matcher(contentDisposition);
            if (m.find()) {
                name = decode(m.group(1).trim());
            } else {
                m = FILENAME.matcher(contentDisposition);
                if (m.find()) name = m.group(1).trim();
            }
        }
        if (name == null || name.isBlank()) {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            name = decode(path.substring(path.lastIndexOf('/') + 1));
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "download";
        if (contentType != null && kindOfContentType(contentType) == Kind.PDF
                && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name += ".pdf";
        }
        return name;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
