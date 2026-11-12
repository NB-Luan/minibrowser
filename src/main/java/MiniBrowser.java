import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Trình duyệt web mini bằng Java Swing + JavaFX WebView.
 * Tính năng: nhiều tab, back/forward/reload/home, thanh địa chỉ thông minh
 * (gõ từ khoá sẽ tìm Google), thanh tiến trình, phím tắt Ctrl+T / Ctrl+W / Ctrl+L / F5.
 */
public class MiniBrowser extends JFrame {

    private static final String HOME = "https://www.google.com";
    private static final String SEARCH = "https://www.google.com/search?q=";

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField address = new JTextField();
    private final JButton btnBack = new JButton("◀");
    private final JButton btnForward = new JButton("▶");
    private final JButton btnReload = new JButton("⟳");
    private final JButton btnHome = new JButton("⌂");
    private final JButton btnNewTab = new JButton("+");
    private final JProgressBar progress = new JProgressBar(0, 100);

    public MiniBrowser() {
        super("Mini Browser");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        buildToolbar();
        buildTabs();
        bindShortcuts();

        addTab(HOME);
    }

    // ---------------------------------------------------------------- UI

    private void buildToolbar() {
        for (JButton b : new JButton[]{btnBack, btnForward, btnReload, btnHome, btnNewTab}) {
            b.setFocusable(false);
            b.setMargin(new Insets(4, 10, 4, 10));
        }
        btnBack.setToolTipText("Quay lại (Alt+←)");
        btnForward.setToolTipText("Tiến tới (Alt+→)");
        btnReload.setToolTipText("Tải lại (F5)");
        btnHome.setToolTipText("Trang chủ");
        btnNewTab.setToolTipText("Tab mới (Ctrl+T)");

        address.setFont(address.getFont().deriveFont(14f));
        address.setMargin(new Insets(4, 8, 4, 8));
        address.addActionListener(e -> navigate(address.getText()));
        address.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                SwingUtilities.invokeLater(address::selectAll);
            }
        });

        btnBack.addActionListener(e -> withCurrent(t -> t.run(en -> {
            if (en.getHistory().getCurrentIndex() > 0) en.getHistory().go(-1);
        })));
        btnForward.addActionListener(e -> withCurrent(t -> t.run(en -> {
            if (en.getHistory().getCurrentIndex() < en.getHistory().getEntries().size() - 1)
                en.getHistory().go(1);
        })));
        btnReload.addActionListener(e -> withCurrent(t -> t.run(WebEngine::reload)));
        btnHome.addActionListener(e -> navigate(HOME));
        btnNewTab.addActionListener(e -> addTab(HOME));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        left.add(btnBack);
        left.add(btnForward);
        left.add(btnReload);
        left.add(btnHome);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        right.add(btnNewTab);

        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.add(left, BorderLayout.WEST);
        bar.add(address, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        progress.setPreferredSize(new Dimension(10, 3));
        progress.setBorderPainted(false);
        progress.setVisible(false);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.CENTER);
        north.add(progress, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
    }

    private void buildTabs() {
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(e -> {
            BrowserTab t = current();
            if (t != null) {
                address.setText(t.url);
                setTitle(t.title + " - Mini Browser");
                refreshNavButtons(t);
                progress.setVisible(false);
            }
        });
        // Chuột giữa trên tab = đóng tab
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    int i = tabs.indexAtLocation(e.getX(), e.getY());
                    if (i >= 0) closeTab((BrowserTab) tabs.getComponentAt(i));
                }
            }
        });
        add(tabs, BorderLayout.CENTER);
    }

    private void bindShortcuts() {
        JRootPane root = getRootPane();
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_T, ctrl), "newTab", () -> addTab(HOME));
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_W, ctrl), "closeTab", () -> {
            BrowserTab t = current();
            if (t != null) closeTab(t);
        });
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_L, ctrl), "focusAddr", () -> {
            address.requestFocusInWindow();
            address.selectAll();
        });
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "reload", btnReload::doClick);
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK), "back", btnBack::doClick);
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK), "fwd", btnForward::doClick);
    }

    private static void bind(InputMap im, ActionMap am, KeyStroke ks, String name, Runnable r) {
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    // -------------------------------------------------------------- Tabs

    private BrowserTab addTab(String url) {
        BrowserTab tab = new BrowserTab(this, url);
        attachTab(tab, true);
        return tab;
    }

    /** Thêm tab đã tạo sẵn (dùng cho popup / target=_blank). */
    private void attachTab(BrowserTab tab, boolean select) {
        tabs.addTab(null, tab);
        int idx = tabs.indexOfComponent(tab);
        tabs.setTabComponentAt(idx, tab.header);
        if (select) {
            tabs.setSelectedIndex(idx);
            address.requestFocusInWindow();
        }
    }

    void closeTab(BrowserTab tab) {
        tab.dispose();
        tabs.remove(tab);
        if (tabs.getTabCount() == 0) {
            System.exit(0);
        }
    }

    private BrowserTab current() {
        Component c = tabs.getSelectedComponent();
        return c instanceof BrowserTab ? (BrowserTab) c : null;
    }

    private void withCurrent(Consumer<BrowserTab> c) {
        BrowserTab t = current();
        if (t != null) c.accept(t);
    }

    private void navigate(String input) {
        String url = normalize(input);
        withCurrent(t -> t.load(url));
    }

    static String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return HOME;
        if (s.startsWith("about:") || s.startsWith("file:")) return s;
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return s;
        boolean looksLikeHost = !s.contains(" ")
                && (s.contains(".") || s.startsWith("localhost"));
        if (looksLikeHost) return "https://" + s;
        return SEARCH + URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // Callbacks từ BrowserTab (luôn chạy trên EDT)

    void onLocation(BrowserTab t, String url) {
        t.url = url;
        if (t == current() && !address.hasFocus()) address.setText(url);
    }

    void onTitle(BrowserTab t, String title) {
        t.setTitleText(title);
        if (t == current()) setTitle(t.title + " - Mini Browser");
    }

    void onProgress(BrowserTab t, double p, boolean running) {
        if (t != current()) return;
        progress.setVisible(running);
        progress.setValue((int) (p * 100));
    }

    void refreshNavButtons(BrowserTab t) {
        if (t != current()) return;
        btnBack.setEnabled(t.canBack);
        btnForward.setEnabled(t.canForward);
    }

    BrowserTab openPopup(BrowserTab opener, BrowserTab fresh) {
        SwingUtilities.invokeLater(() -> attachTab(fresh, true));
        return fresh;
    }

    // -------------------------------------------------------------- Tab

    static class BrowserTab extends JPanel {
        private final MiniBrowser owner;
        private final JFXPanel fxPanel = new JFXPanel();
        private final JLabel titleLabel = new JLabel("Tab mới");
        final JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private WebEngine engine;
        String url = "";
        String title = "Tab mới";
        boolean canBack, canForward;

        BrowserTab(MiniBrowser owner, String initialUrl) {
            super(new BorderLayout());
            this.owner = owner;
            add(fxPanel, BorderLayout.CENTER);
            buildHeader();
            if (Platform.isFxApplicationThread()) initFx(initialUrl);
            else Platform.runLater(() -> initFx(initialUrl));
        }

        private void buildHeader() {
            header.setOpaque(false);
            titleLabel.setPreferredSize(new Dimension(150, 18));
            JButton close = new JButton("✕");
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.setFocusable(false);
            close.setMargin(new Insets(0, 2, 0, 2));
            close.addActionListener(e -> owner.closeTab(this));
            header.add(titleLabel);
            header.add(close);
            // Click vào header chọn tab
            titleLabel.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        owner.tabs.setSelectedComponent(BrowserTab.this);
                    } else if (SwingUtilities.isMiddleMouseButton(e)) {
                        owner.closeTab(BrowserTab.this);
                    }
                }
            });
        }

        /** Chạy trên JavaFX thread. */
        private void initFx(String initialUrl) {
            WebView view = new WebView();
            engine = view.getEngine();
            engine.setJavaScriptEnabled(true);
            fxPanel.setScene(new Scene(view));

            engine.locationProperty().addListener((o, a, b) ->
                    SwingUtilities.invokeLater(() -> owner.onLocation(this, b)));
            engine.titleProperty().addListener((o, a, b) ->
                    SwingUtilities.invokeLater(() -> owner.onTitle(this, b)));
            engine.getLoadWorker().progressProperty().addListener((o, a, b) -> {
                boolean running = engine.getLoadWorker().isRunning();
                SwingUtilities.invokeLater(() -> owner.onProgress(this, b.doubleValue(), running));
            });
            engine.getLoadWorker().stateProperty().addListener((o, a, s) -> {
                boolean running = s == Worker.State.RUNNING || s == Worker.State.SCHEDULED;
                SwingUtilities.invokeLater(() -> owner.onProgress(this, running ? 0.1 : 1, running));
                if (s == Worker.State.FAILED) {
                    SwingUtilities.invokeLater(() -> owner.onTitle(this, "Không tải được trang"));
                }
            });
            engine.getHistory().currentIndexProperty().addListener((o, a, b) -> updateNav());
            engine.getHistory().getEntries().addListener(
                    (javafx.collections.ListChangeListener<javafx.scene.web.WebHistory.Entry>) c -> updateNav());

            // target=_blank / window.open -> mở tab mới
            engine.setCreatePopupHandler(cfg -> {
                BrowserTab fresh = new BrowserTab(owner, null); // đang ở FX thread nên engine tạo ngay
                owner.openPopup(this, fresh);
                return fresh.engine;
            });

            if (initialUrl != null) engine.load(initialUrl);
        }

        private void updateNav() {
            int idx = engine.getHistory().getCurrentIndex();
            int size = engine.getHistory().getEntries().size();
            canBack = idx > 0;
            canForward = idx < size - 1;
            SwingUtilities.invokeLater(() -> owner.refreshNavButtons(this));
        }

        void load(String u) { run(e -> e.load(u)); }

        void run(Consumer<WebEngine> action) {
            Platform.runLater(() -> {
                if (engine != null) action.accept(engine);
            });
        }

        void setTitleText(String t) {
            title = (t == null || t.isBlank()) ? "Tab mới" : t;
            titleLabel.setText(title);
            titleLabel.setToolTipText(title);
        }

        void dispose() {
            Platform.runLater(() -> {
                if (engine != null) engine.load(null);
            });
        }
    }

    // -------------------------------------------------------------- main

    public static void main(String[] args) {
        Platform.setImplicitExit(false);
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            new MiniBrowser().setVisible(true);
        });
    }
}
