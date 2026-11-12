import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.function.Consumer;

/** Lịch sử duyệt web: lưu ra ~/.minibrowser/history.tsv, có cửa sổ tìm kiếm / xoá. */
final class HistoryManager {

    record Entry(long time, String title, String url) { }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<Entry> entries = new ArrayList<>(); // cũ -> mới, chỉ truy cập trên EDT
    private final Path file;
    private JDialog dialog;
    private JTable table;
    private JTextField search;
    private List<Entry> view = new ArrayList<>();

    private final AbstractTableModel model = new AbstractTableModel() {
        private final String[] cols = {"Thời gian", "Tiêu đề", "Địa chỉ"};

        @Override public int getRowCount() { return view.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Entry e = view.get(r);
            switch (c) {
                case 0: return FMT.format(Instant.ofEpochMilli(e.time()));
                case 1: return e.title();
                default: return e.url();
            }
        }
    };

    HistoryManager() {
        file = Path.of(System.getProperty("user.home"), ".minibrowser", "history.tsv");
        load();
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 3);
                if (p.length == 3) {
                    try {
                        entries.add(new Entry(Long.parseLong(p[0]), p[1], p[2]));
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (IOException ignored) { }
    }

    /** Ghi nhận một lần truy cập (gọi trên EDT). */
    void add(String title, String url) {
        if (url == null || url.isBlank() || url.startsWith("about:")) return;
        if (!entries.isEmpty() && entries.get(entries.size() - 1).url().equals(url)) return;
        String t = (title == null || title.isBlank()) ? url : title.replaceAll("[\\t\\r\\n]+", " ");
        Entry e = new Entry(System.currentTimeMillis(), t, url);
        entries.add(e);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.time() + "\t" + e.title() + "\t" + e.url() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
        if (dialog != null && dialog.isVisible()) applyFilter();
    }

    private void saveAll() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (Entry e : entries) lines.add(e.time() + "\t" + e.title() + "\t" + e.url());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        view = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) { // mới nhất lên đầu
            Entry e = entries.get(i);
            if (q.isEmpty() || e.title().toLowerCase(Locale.ROOT).contains(q)
                    || e.url().toLowerCase(Locale.ROOT).contains(q)) {
                view.add(e);
            }
        }
        model.fireTableDataChanged();
    }

    void showDialog(Component parent, Consumer<String> openUrl) {
        if (dialog == null) {
            dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Lịch sử duyệt web");
            table = new JTable(model);
            table.setRowHeight(22);
            table.getColumnModel().getColumn(0).setPreferredWidth(150);
            table.getColumnModel().getColumn(1).setPreferredWidth(300);
            table.getColumnModel().getColumn(2).setPreferredWidth(400);
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    int r = table.getSelectedRow();
                    if (e.getClickCount() == 2 && r >= 0) openUrl.accept(view.get(r).url());
                }
            });

            search = new JTextField();
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
                @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
                @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
            });
            JPanel north = new JPanel(new BorderLayout(6, 0));
            north.add(new JLabel(" Tìm kiếm: "), BorderLayout.WEST);
            north.add(search, BorderLayout.CENTER);

            JButton btnOpen = new JButton("Mở");
            btnOpen.addActionListener(e -> {
                int r = table.getSelectedRow();
                if (r >= 0) openUrl.accept(view.get(r).url());
            });
            JButton btnDel = new JButton("Xoá mục đã chọn");
            btnDel.addActionListener(e -> {
                int[] rows = table.getSelectedRows();
                if (rows.length == 0) return;
                List<Entry> del = new ArrayList<>();
                for (int r : rows) del.add(view.get(r));
                entries.removeAll(del);
                saveAll();
                applyFilter();
            });
            JButton btnClear = new JButton("Xoá tất cả");
            btnClear.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(dialog, "Xoá toàn bộ lịch sử duyệt web?",
                        "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    entries.clear();
                    saveAll();
                    applyFilter();
                }
            });
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.add(btnOpen);
            south.add(btnDel);
            south.add(btnClear);

            dialog.add(north, BorderLayout.NORTH);
            dialog.add(new JScrollPane(table), BorderLayout.CENTER);
            dialog.add(south, BorderLayout.SOUTH);
            dialog.setPreferredSize(new Dimension(900, 480));
            dialog.pack();
            dialog.setLocationRelativeTo(parent);
        }
        applyFilter();
        dialog.setVisible(true);
        dialog.toFront();
    }
}
