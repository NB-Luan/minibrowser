import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Công cụ gửi thủ công các request HTTP GET / HEAD / POST ... và xem status, header, body. */
final class HttpToolDialog extends JDialog {

    private static final int MAX_SHOW = 2 * 1024 * 1024;

    private final JComboBox<String> method =
            new JComboBox<>(new String[]{"GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"});
    private final JTextField url = new JTextField();
    private final JTextArea reqHeaders = mono("Content-Type: application/x-www-form-urlencoded");
    private final JTextArea reqBody = mono("");
    private final JTextArea respHeaders = mono("");
    private final JTextArea respBody = mono("");
    private final JLabel status = new JLabel(" Sẵn sàng.");
    private final JButton send = new JButton("Gửi");

    HttpToolDialog(Component parent, String initialUrl) {
        super(SwingUtilities.getWindowAncestor(parent), "Công cụ HTTP (GET / HEAD / POST)");
        url.setText(initialUrl == null ? "" : initialUrl);
        respHeaders.setEditable(false);
        respBody.setEditable(false);
        respBody.setLineWrap(true);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(method, BorderLayout.WEST);
        top.add(url, BorderLayout.CENTER);
        top.add(send, BorderLayout.EAST);

        JTabbedPane req = new JTabbedPane();
        req.addTab("Header gửi đi", new JScrollPane(reqHeaders));
        req.addTab("Body (POST)", new JScrollPane(reqBody));

        JTabbedPane resp = new JTabbedPane();
        resp.addTab("Header nhận về", new JScrollPane(respHeaders));
        resp.addTab("Body nhận về", new JScrollPane(respBody));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, req, resp);
        split.setResizeWeight(0.35);

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        send.addActionListener(e -> doSend());
        url.addActionListener(e -> doSend());
        getRootPane().setDefaultButton(send);

        setPreferredSize(new Dimension(820, 620));
        pack();
        setLocationRelativeTo(parent);
    }

    private static JTextArea mono(String text) {
        JTextArea a = new JTextArea(text);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        return a;
    }

    private void doSend() {
        String m = (String) method.getSelectedItem();
        String u = url.getText().trim();
        if (u.isEmpty()) return;
        if (!u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) u = "http://" + u;
        final String target = u;
        final String headersText = reqHeaders.getText();
        final String bodyText = reqBody.getText();

        send.setEnabled(false);
        status.setText(" Đang gửi " + m + " " + target + " ...");
        respHeaders.setText("");
        respBody.setText("");

        Thread t = new Thread(() -> {
            try {
                HttpService.Result r = HttpService.request(m, target, headersText, bodyText);
                StringBuilder h = new StringBuilder();
                h.append(r.version).append(' ').append(r.status).append('\n');
                for (Map.Entry<String, List<String>> e : r.headers.entrySet()) {
                    for (String v : e.getValue()) h.append(e.getKey()).append(": ").append(v).append('\n');
                }
                String ct = r.header("Content-Type");
                String body;
                if (r.body.length == 0) {
                    body = m.equals("HEAD") ? "(HEAD không có body)" : "(body rỗng)";
                } else if (ct != null && HttpService.kindOfContentType(ct) != HttpService.Kind.PAGE) {
                    body = "(Dữ liệu nhị phân " + ct + ", " + DownloadManager.size(r.body.length)
                            + " - dùng trình duyệt để mở/tải về)";
                } else {
                    int n = Math.min(r.body.length, MAX_SHOW);
                    body = new String(r.body, 0, n, StandardCharsets.UTF_8);
                    if (n < r.body.length) body += "\n... (đã cắt bớt)";
                }
                final String shownBody = body;
                String line = " " + m + " " + r.finalUrl + "  →  " + r.status + "  ("
                        + r.millis + " ms, " + DownloadManager.size(r.body.length) + ")";
                SwingUtilities.invokeLater(() -> {
                    respHeaders.setText(h.toString());
                    respHeaders.setCaretPosition(0);
                    respBody.setText(shownBody);
                    respBody.setCaretPosition(0);
                    status.setText(line);
                    send.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    status.setText(" Lỗi: " + ex);
                    send.setEnabled(true);
                });
            }
        }, "http-tool");
        t.setDaemon(true);
        t.start();
    }
}
