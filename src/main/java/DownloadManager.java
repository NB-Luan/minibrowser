import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Quản lý tải xuống: tải bằng HTTP GET ở luồng nền, có cửa sổ danh sách + tiến trình. */
final class DownloadManager {

    static final class Item {
        final String url;
        final boolean openWhenDone;
        volatile Path file;
        volatile long received;
        volatile long total = -1;
        volatile String state = "Đang kết nối...";

        Item(String url, boolean openWhenDone) {
            this.url = url;
            this.openWhenDone = openWhenDone;
        }

        String displayName() {
            return file != null ? file.getFileName().toString() : url;
        }
    }

    private final List<Item> items = new ArrayList<>(); // chỉ truy cập trên EDT
    private final Path dir;
    private JDialog dialog;
    private JTable table;

    private final AbstractTableModel model = new AbstractTableModel() {
        private final String[] cols = {"Tên tệp", "Kích thước", "Tiến trình", "Trạng thái"};

        @Override public int getRowCount() { return items.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Item it = items.get(r);
            switch (c) {
                case 0: return it.displayName();
                case 1: return it.total > 0
                        ? size(it.received) + " / " + size(it.total) : size(it.received);
                case 2: return it;
                default: return it.state;
            }
        }
    };

    DownloadManager() {
        Path home = Path.of(System.getProperty("user.home"));
        Path d = home.resolve("Downloads");
        dir = Files.isDirectory(d) ? d : home;
    }

    Path directory() { return dir; }

    /** Bắt đầu tải một URL (gọi trên EDT). */
    Item start(String url, boolean openWhenDone) {
        Item it = new Item(url, openWhenDone);
        items.add(it);
        model.fireTableRowsInserted(items.size() - 1, items.size() - 1);
        Thread t = new Thread(() -> run(it), "download");
        t.setDaemon(true);
        t.start();
        return it;
    }

    private void run(Item it) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(it.url))
                    .header("User-Agent", HttpService.UA).GET().build();
            HttpResponse<InputStream> resp =
                    HttpService.CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                resp.body().close();
                throw new IOException("HTTP " + resp.statusCode());
            }
            it.total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
            String name = HttpService.fileName(resp.uri().toString(),
                    resp.headers().firstValue("Content-Disposition").orElse(null),
                    resp.headers().firstValue("Content-Type").orElse(null));
            Path target = uniquePath(name);
            it.file = target;
            it.state = "Đang tải";
            refresh();

            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                long last = 0;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    it.received += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 200) {
                        last = now;
                        refresh();
                    }
                }
            }
            it.state = "Hoàn tất";
            refresh();
            if (it.openWhenDone) open(target);
        } catch (Exception e) {
            it.state = "Lỗi: " + e.getMessage();
            refresh();
        }
    }

    private Path uniquePath(String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return p;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            p = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
    }

    private void refresh() {
        SwingUtilities.invokeLater(() -> {
            if (!items.isEmpty()) model.fireTableRowsUpdated(0, items.size() - 1);
        });
    }

    // ------------------------------------------------------------- dialog

    void showDialog(Component parent) {
        if (dialog == null) {
            dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Tải xuống");
            table = new JTable(model);
            table.setRowHeight(24);
            table.getColumnModel().getColumn(0).setPreferredWidth(280);
            table.getColumnModel().getColumn(1).setPreferredWidth(140);
            table.getColumnModel().getColumn(2).setPreferredWidth(160);
            table.getColumnModel().getColumn(3).setPreferredWidth(160);
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setStringPainted(true);
            table.getColumnModel().getColumn(2).setCellRenderer((TableCellRenderer) (t, v, sel, foc, r, c) -> {
                Item it = (Item) v;
                if (it.total > 0) {
                    bar.setIndeterminate(false);
                    bar.setValue((int) (it.received * 100 / it.total));
                } else {
                    bar.setIndeterminate(it.state.equals("Đang tải") || it.state.startsWith("Đang kết nối"));
                    bar.setValue(it.state.equals("Hoàn tất") ? 100 : 0);
                }
                return bar;
            });
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) openSelected();
                }
            });

            JButton btnOpen = new JButton("Mở tệp");
            btnOpen.addActionListener(e -> openSelected());
            JButton btnFolder = new JButton("Mở thư mục");
            btnFolder.addActionListener(e -> open(dir));
            JButton btnClear = new JButton("Xoá danh sách");
            btnClear.addActionListener(e -> {
                items.removeIf(i -> !i.state.equals("Đang tải") && !i.state.startsWith("Đang kết nối"));
                model.fireTableDataChanged();
            });
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.add(btnOpen);
            south.add(btnFolder);
            south.add(btnClear);

            dialog.add(new JScrollPane(table), BorderLayout.CENTER);
            dialog.add(south, BorderLayout.SOUTH);
            dialog.setPreferredSize(new Dimension(780, 320));
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    private void openSelected() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        Item it = items.get(r);
        if (it.file != null && it.state.equals("Hoàn tất")) open(it.file);
    }

    private static void open(Path p) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(new File(p.toString()));
            } else {
                JOptionPane.showMessageDialog(null, "Tệp nằm ở: " + p);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Không mở được: " + p + "\n" + e.getMessage());
        }
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format("%.1f KB", v);
        v /= 1024;
        if (v < 1024) return String.format("%.1f MB", v);
        return String.format("%.2f GB", v / 1024);
    }
}
