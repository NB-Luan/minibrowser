import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
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
 *
 * Web client dùng giao thức HTTP:
 *  - Duyệt trang web (GET) bằng WebView; tự nhận diện dịch vụ khác của server:
 *      .mp3 / .mp4  -> mở trình phát media ngay trong tab,
 *      .pdf         -> tải về rồi mở bằng trình xem PDF của hệ thống,
 *      tệp khác     -> tải xuống.
 *  - Công cụ HTTP (Ctrl+K): gửi thủ công GET / HEAD / POST và xem status + header + body.
 *  - Lịch sử duyệt web (Ctrl+H), Trình quản lý tải xuống (Ctrl+J).
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
    private final JButton btnHistory = new JButton("Lịch sử");
    private final JButton btnDownloads = new JButton("Tải xuống");
    private final JButton btnHttp = new JButton("HTTP");
    private final HistoryManager history = new HistoryManager();
    private final DownloadManager downloads = new DownloadManager();
    private final JProgressBar progress = new JProgressBar(0, 100);

    public MiniBrowser() {
        super("Mini Browser");
        // Không dùng EXIT_ON_CLOSE: phải tắt WebView/JavaFX trước rồi mới thoát JVM,
        // nếu không timer của WebKit chạy khi Prism đã bị huỷ -> NullPointerException.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { shutdown(); }
        });
        setSize(1200, 800);
        setLocationRelativeTo(null);

        buildToolbar();
        buildTabs();
        bindShortcuts();

        addTab(HOME);
    }

    // ---------------------------------------------------------------- UI

    private void buildToolbar() {
        for (JButton b : new JButton[]{btnBack, btnForward, btnReload, btnHome, btnNewTab,
                btnHistory, btnDownloads, btnHttp}) {
            b.setFocusable(false);
            b.setMargin(new Insets(4, 10, 4, 10));
        }
        btnBack.setToolTipText("Quay lại (Alt+←)");
        btnForward.setToolTipText("Tiến tới (Alt+→)");
        btnReload.setToolTipText("Tải lại (F5)");
        btnHome.setToolTipText("Trang chủ");
        btnNewTab.setToolTipText("Tab mới (Ctrl+T)");
        btnHistory.setToolTipText("Lịch sử duyệt web (Ctrl+H)");
        btnDownloads.setToolTipText("Tải xuống (Ctrl+J)");
        btnHttp.setToolTipText("Công cụ HTTP: GET / HEAD / POST (Ctrl+K)");

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
        btnHistory.addActionListener(e -> showHistory());
        btnDownloads.addActionListener(e -> downloads.showDialog(this));
        btnHttp.addActionListener(e -> showHttpTool());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        left.add(btnBack);
        left.add(btnForward);
        left.add(btnReload);
        left.add(btnHome);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        right.add(btnNewTab);
        right.add(btnHistory);
        right.add(btnDownloads);
        right.add(btnHttp);

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
            Component sel = tabs.getSelectedComponent();
            if (sel instanceof BrowserTab) {
                BrowserTab t = (BrowserTab) sel;
                address.setText(t.url);
                setTitle(t.title + " - Mini Browser");
                refreshNavButtons(t);
                progress.setVisible(false);
            } else if (sel instanceof MediaTab) {
                MediaTab m = (MediaTab) sel;
                address.setText(m.url);
                setTitle(m.title + " - Mini Browser");
                btnBack.setEnabled(false);
                btnForward.setEnabled(false);
                progress.setVisible(false);
            }
        });
        // Chuột giữa trên tab = đóng tab
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    int i = tabs.indexAtLocation(e.getX(), e.getY());
                    if (i >= 0) closeTab(tabs.getComponentAt(i));
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
            Component c = tabs.getSelectedComponent();
            if (c != null) closeTab(c);
        });
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_H, ctrl), "history", this::showHistory);
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_J, ctrl), "downloads", () -> downloads.showDialog(this));
        bind(im, am, KeyStroke.getKeyStroke(KeyEvent.VK_K, ctrl), "httpTool", this::showHttpTool);
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
        attachTab(tab, tab.header, true);
        return tab;
    }

    /** Thêm tab đã tạo sẵn (dùng cho popup / target=_blank / media). */
    private void attachTab(Component tab, JComponent header, boolean select) {
        tabs.addTab(null, tab);
        int idx = tabs.indexOfComponent(tab);
        tabs.setTabComponentAt(idx, header);
        if (select) {
            tabs.setSelectedIndex(idx);
            address.requestFocusInWindow();
        }
    }

    void closeTab(Component tab) {
        if (tab instanceof BrowserTab) ((BrowserTab) tab).dispose();
        else if (tab instanceof MediaTab) ((MediaTab) tab).dispose();
        tabs.remove(tab);
        if (tabs.getTabCount() == 0) {
            shutdown();
        }
    }

    private boolean exiting;

    /** Tắt trình duyệt đúng thứ tự: dừng WebEngine -> thoát JavaFX -> thoát JVM. */
    private void shutdown() {
        if (exiting) return;
        exiting = true;
        setVisible(false);
        for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
            Component c = tabs.getComponentAt(i);
            if (c instanceof BrowserTab) ((BrowserTab) c).dispose();
            else if (c instanceof MediaTab) ((MediaTab) c).dispose();
        }
        // Các lệnh dispose đã xếp hàng trên FX thread; Platform.exit() xếp sau chúng.
        Platform.runLater(() -> {
            FX_EXITING = true;
            Platform.exit();
        });
        // Dự phòng: nếu JVM chưa tự thoát thì ép thoát sau một chút.
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "exit-fallback");
        t.setDaemon(true);
        t.start();
        dispose();
    }

    /** Đặt true khi JavaFX đang tắt, để bỏ qua lỗi vặt của WebKit lúc đó. */
    private static volatile boolean FX_EXITING;
    private static boolean fxHandlerInstalled;

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
        BrowserTab t = current();
        if (t == null) {
            // đang ở tab media -> mở trang trong tab mới
            if (tabs.getSelectedComponent() != null) addTab(url);
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            t.load(url);
            return;
        }
        HttpService.Kind byExt = HttpService.kindOfUrl(url);
        if (byExt != HttpService.Kind.PAGE) {
            handleResource(url, byExt);
            return;
        }
        // Gửi HEAD để biết server trả về trang web hay tệp (pdf / mp3 / mp4 ...).
        Thread th = new Thread(() -> {
            HttpService.Kind k = HttpService.detect(url);
            SwingUtilities.invokeLater(() -> {
                if (k == HttpService.Kind.PAGE) t.load(url);
                else handleResource(url, k);
            });
        }, "detect-kind");
        th.setDaemon(true);
        th.start();
    }

    /** Xử lý URL không phải trang HTML: phát media, mở PDF hoặc tải xuống. */
    void handleResource(String url, HttpService.Kind kind) {
        switch (kind) {
            case AUDIO:
            case VIDEO:
                MediaTab m = new MediaTab(this, url, kind == HttpService.Kind.VIDEO);
                attachTab(m, m.header, true);
                break;
            case PDF:
                downloads.start(url, true); // tải xong tự mở bằng trình xem PDF
                downloads.showDialog(this);
                break;
            default:
                downloads.start(url, false);
                downloads.showDialog(this);
                break;
        }
    }

    private void showHistory() {
        history.showDialog(this, this::navigate);
    }

    private void showHttpTool() {
        BrowserTab t = current();
        String u = t != null && t.url != null && t.url.startsWith("http") ? t.url : "https://example.com";
        new HttpToolDialog(this, u).setVisible(true);
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
        SwingUtilities.invokeLater(() -> attachTab(fresh, fresh.header, true));
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
            if (!fxHandlerInstalled) {
                fxHandlerInstalled = true;
                Thread.currentThread().setUncaughtExceptionHandler((th, ex) -> {
                    // Lỗi NPE của WebKit/Prism lúc đang tắt là vô hại -> bỏ qua.
                    if (FX_EXITING && ex instanceof NullPointerException) return;
                    ex.printStackTrace();
                });
            }
            WebView view = new WebView();
            engine = view.getEngine();
            engine.setJavaScriptEnabled(true);
            fxPanel.setScene(new Scene(view));

            engine.locationProperty().addListener((o, a, b) -> {
                // Link tới .pdf / .mp3 / .mp4 / tệp khác: huỷ tải trong WebView, xử lý riêng.
                if (b != null && (b.startsWith("http://") || b.startsWith("https://"))) {
                    HttpService.Kind k = HttpService.kindOfUrl(b);
                    if (k != HttpService.Kind.PAGE) {
                        engine.getLoadWorker().cancel();
                        SwingUtilities.invokeLater(() -> {
                            owner.handleResource(b, k);
                            if (url.isEmpty()) owner.closeTab(this); // tab popup trống
                        });
                        return;
                    }
                }
                SwingUtilities.invokeLater(() -> owner.onLocation(this, b));
            });
            engine.titleProperty().addListener((o, a, b) ->
                    SwingUtilities.invokeLater(() -> owner.onTitle(this, b)));
            engine.getLoadWorker().progressProperty().addListener((o, a, b) -> {
                boolean running = engine.getLoadWorker().isRunning();
                SwingUtilities.invokeLater(() -> owner.onProgress(this, b.doubleValue(), running));
            });
            engine.getLoadWorker().stateProperty().addListener((o, a, s) -> {
                boolean running = s == Worker.State.RUNNING || s == Worker.State.SCHEDULED;
                SwingUtilities.invokeLater(() -> owner.onProgress(this, running ? 0.1 : 1, running));
                if (s == Worker.State.SUCCEEDED) {
                    String loc = engine.getLocation();
                    String ti = engine.getTitle();
                    SwingUtilities.invokeLater(() -> owner.history.add(ti, loc));
                }
                if (s == Worker.State.FAILED) {
                    String loc = engine.getLocation();
                    SwingUtilities.invokeLater(() -> owner.onTitle(this, "Không tải được trang"));
                    // Có thể là tệp không có đuôi (vd /download?id=1): hỏi server rồi tải/phát.
                    if (loc != null && (loc.startsWith("http://") || loc.startsWith("https://"))) {
                        Thread th = new Thread(() -> {
                            HttpService.Kind k = HttpService.detect(loc);
                            if (k != HttpService.Kind.PAGE) {
                                SwingUtilities.invokeLater(() -> owner.handleResource(loc, k));
                            }
                        }, "detect-failed");
                        th.setDaemon(true);
                        th.start();
                    }
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
                if (engine != null) {
                    engine.load(null);
                    engine.setCreatePopupHandler(null);
                }
                fxPanel.setScene(null);
            });
        }
    }

    // ------------------------------------------------------- Media tab

    /** Tab phát .mp3 / .mp4 trực tiếp từ URL (stream qua HTTP) bằng JavaFX MediaPlayer. */
    static class MediaTab extends JPanel {
        final String url;
        String title;
        final JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final MiniBrowser owner;
        private final JFXPanel fxPanel = new JFXPanel();
        private final JButton btnPlay = new JButton("Tạm dừng");
        private final JSlider pos = new JSlider(0, 1000, 0);
        private final JSlider vol = new JSlider(0, 100, 80);
        private final JLabel time = new JLabel("00:00 / 00:00");
        private final JLabel status = new JLabel(" Đang tải media...");
        private MediaPlayer player;
        private volatile double totalSec;
        private boolean updating;

        MediaTab(MiniBrowser owner, String url, boolean video) {
            super(new BorderLayout());
            this.owner = owner;
            this.url = url;
            this.title = HttpService.fileName(url, null, null);

            JLabel titleLabel = new JLabel((video ? "▶ " : "♪ ") + title);
            titleLabel.setPreferredSize(new Dimension(150, 18));
            JButton close = new JButton("✕");
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.setFocusable(false);
            close.setMargin(new Insets(0, 2, 0, 2));
            close.addActionListener(e -> owner.closeTab(this));
            header.setOpaque(false);
            header.add(titleLabel);
            header.add(close);
            titleLabel.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) owner.tabs.setSelectedComponent(MediaTab.this);
                    else if (SwingUtilities.isMiddleMouseButton(e)) owner.closeTab(MediaTab.this);
                }
            });

            btnPlay.setFocusable(false);
            btnPlay.addActionListener(e -> Platform.runLater(() -> {
                if (player == null) return;
                if (player.getStatus() == MediaPlayer.Status.PLAYING) player.pause();
                else player.play();
            }));
            JButton btnDownload = new JButton("Tải về");
            btnDownload.setFocusable(false);
            btnDownload.addActionListener(e -> {
                owner.downloads.start(url, false);
                owner.downloads.showDialog(owner);
            });
            pos.addChangeListener(e -> {
                if (updating || pos.getValueIsAdjusting()) return;
                double target = totalSec * pos.getValue() / 1000.0;
                Platform.runLater(() -> {
                    if (player != null) player.seek(javafx.util.Duration.seconds(target));
                });
            });
            vol.addChangeListener(e -> {
                double v = vol.getValue() / 100.0;
                Platform.runLater(() -> {
                    if (player != null) player.setVolume(v);
                });
            });

            JPanel controls = new JPanel(new BorderLayout(6, 0));
            JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            l.add(btnPlay);
            JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
            r.add(time);
            r.add(new JLabel("Âm lượng"));
            r.add(vol);
            r.add(btnDownload);
            controls.add(l, BorderLayout.WEST);
            controls.add(pos, BorderLayout.CENTER);
            controls.add(r, BorderLayout.EAST);

            JPanel south = new JPanel(new BorderLayout());
            south.add(controls, BorderLayout.CENTER);
            south.add(status, BorderLayout.SOUTH);
            add(fxPanel, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);

            Platform.runLater(() -> initFx(video));
        }

        private void initFx(boolean video) {
            try {
                Media media = new Media(url);
                player = new MediaPlayer(media);
                StackPane root = new StackPane();
                root.setStyle("-fx-background-color: black;");
                if (video) {
                    MediaView mv = new MediaView(player);
                    mv.setPreserveRatio(true);
                    mv.fitWidthProperty().bind(root.widthProperty());
                    mv.fitHeightProperty().bind(root.heightProperty());
                    root.getChildren().add(mv);
                } else {
                    javafx.scene.control.Label lb = new javafx.scene.control.Label("♪  " + title);
                    lb.setStyle("-fx-text-fill: white; -fx-font-size: 24px;");
                    root.getChildren().add(lb);
                }
                fxPanel.setScene(new Scene(root));

                player.setVolume(vol.getValue() / 100.0);
                player.setOnReady(() -> {
                    totalSec = player.getTotalDuration().toSeconds();
                    SwingUtilities.invokeLater(() -> status.setText(" Đang phát: " + url));
                });
                player.statusProperty().addListener((o, a, s) -> SwingUtilities.invokeLater(() ->
                        btnPlay.setText(s == MediaPlayer.Status.PLAYING ? "Tạm dừng" : "Phát")));
                player.currentTimeProperty().addListener((o, a, t) -> {
                    double cur = t.toSeconds();
                    double tot = totalSec;
                    SwingUtilities.invokeLater(() -> {
                        if (!pos.getValueIsAdjusting() && tot > 0) {
                            updating = true;
                            pos.setValue((int) (cur / tot * 1000));
                            updating = false;
                        }
                        time.setText(fmt(cur) + " / " + fmt(tot));
                    });
                });
                player.setOnEndOfMedia(() -> {
                    player.seek(javafx.util.Duration.ZERO);
                    player.pause();
                });
                Runnable onErr = () -> {
                    String msg = player != null && player.getError() != null
                            ? player.getError().getMessage() : "không rõ";
                    SwingUtilities.invokeLater(() -> status.setText(
                            " Không phát được (" + msg + "). Bấm \"Tải về\" để lưu tệp."));
                };
                player.setOnError(onErr);
                media.setOnError(onErr);
                player.setAutoPlay(true);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> status.setText(" Lỗi: " + ex.getMessage()));
            }
        }

        private static String fmt(double sec) {
            int s = (int) Math.max(0, sec);
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        void dispose() {
            Platform.runLater(() -> {
                if (player != null) {
                    player.stop();
                    player.dispose();
                    player = null;
                }
                fxPanel.setScene(null);
            });
        }
    }

    // -------------------------------------------------------------- main

    public static void main(String[] args) {
        // Loader HTTP/2 của JavaFX 21 báo "Unknown encoding type 'none'" -> dùng loader cũ.
        System.setProperty("com.sun.webkit.useHTTP2Loader", "false");
        Platform.setImplicitExit(false);
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            new MiniBrowser().setVisible(true);
        });
    }
}
