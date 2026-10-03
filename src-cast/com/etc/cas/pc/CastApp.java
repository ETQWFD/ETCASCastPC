package com.etc.cas.pc;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

public class CastApp {

    private static final Color ACCENT = new Color(0x2E86DE);
    private static final Color ACCENT_DEEP = new Color(0x1B5FA8);
    private static final Color BG = new Color(0xF6F7FB);
    private static final Color CARD = Color.WHITE;
    private static final Color INK = new Color(0x1A1A2E);
    private static final Color SUB = new Color(0x6E6E85);

    private JFrame frame;
    private JLabel statusDev, statusTitle;
    private JComboBox<String> speedBox, qualityBox;
    private JButton btnCast, btnManual;
    private Net.Device currentDev;
    private String currentKind = ""; // file / image / mirror / link
    private volatile boolean busy;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new CastApp().open());
    }

    private void open() {
        frame = new JFrame("ETCAS 投屏 · 电脑版");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(760, 560);
        frame.setMinimumSize(new Dimension(680, 500));
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(BG);
        frame.add(header(), BorderLayout.NORTH);
        frame.add(center(), BorderLayout.CENTER);
        frame.add(footer(), BorderLayout.SOUTH);
        FileServer.start(8388);
        AiChat.attach(frame);
        frame.setVisible(true);
        timerUpdateStatus();
    }

    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setPreferredSize(new Dimension(0, 64));
        p.setBackground(ACCENT_DEEP);
        JLabel title = new JLabel("ETCAS 投屏 · 电脑版");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        title.setBorder(new EmptyBorder(0, 22, 0, 0));
        p.add(title, BorderLayout.WEST);
        JLabel ip = new JLabel("本机 " + Net.localIp() + " : 8388");
        ip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        ip.setForeground(new Color(0xDCE8F7));
        ip.setBorder(new EmptyBorder(0, 0, 0, 22));
        p.add(ip, BorderLayout.EAST);
        return p;
    }

    private JPanel center() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(18, 22, 6, 22));

        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 0));
        cards.setOpaque(false);
        cards.add(modeCard("本地文件投屏", "视频 / 全部格式", "选择电脑中的文件（含 m4s），投到电视、电脑客户端或手机客户端", () -> pickFile(false)));
        cards.add(modeCard("图片投屏", "高清无损显示", "选择图片直接投屏，支持 JPG / PNG / BMP / GIF 等", () -> pickFile(true)));
        cards.add(modeCard("屏幕直播", "电脑一举一动", "实时镜像电脑屏幕到电视，可切画质与倍速", this::startLive));
        cards.add(modeCard("链接投屏", "哔哩哔哩等", "输入视频链接自动解析直链，极速加载不卡顿", this::startLink));
        wrap.add(cards, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(10, 0, 0, 0));

        JPanel status = new JPanel(new GridLayout(2, 1, 0, 4));
        status.setBackground(CARD);
        status.setBorder(new EmptyBorder(12, 16, 12, 16));
        statusDev = new JLabel("未连接设备");
        statusDev.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        statusDev.setForeground(INK);
        statusTitle = new JLabel("点击上方卡片开始投屏");
        statusTitle.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusTitle.setForeground(SUB);
        status.add(statusDev);
        status.add(statusTitle);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        controls.setBackground(CARD);
        controls.add(new JLabel("倍速"));
        speedBox = new JComboBox<>(new String[]{"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"});
        speedBox.setSelectedItem("1.0x");
        speedBox.addActionListener(e -> {
            if (currentDev == null || busy) return;
            float rate = Float.parseFloat(((String) speedBox.getSelectedItem()).replace("x", ""));
            new Thread(() -> Net.setSpeed(currentDev, rate), "etcas-pc-speed").start();
        });
        controls.add(speedBox);
        controls.add(new JLabel("画质"));
        qualityBox = new JComboBox<>(new String[]{"高清", "标清", "流畅"});
        qualityBox.addActionListener(e -> {
            if (currentDev == null || busy) return;
            int q = qualityBox.getSelectedIndex() + 1;
            new Thread(() -> Net.setQuality(currentDev, q), "etcas-pc-quality").start();
        });
        controls.add(qualityBox);
        JButton stop = new JButton("停止投屏");
        stop.setBackground(new Color(0xEE4D4D));
        stop.setForeground(Color.WHITE);
        stop.setFocusPainted(false);
        stop.setOpaque(true);
        stop.setBorderPainted(false);
        stop.setPreferredSize(new Dimension(96, 32));
        stop.addActionListener(e -> new Thread(() -> {
            if (currentDev != null) Net.stop(currentDev);
            SwingUtilities.invokeLater(() -> statusTitle.setText("已停止投屏"));
        }, "etcas-pc-stop").start());
        controls.add(stop);

        bottom.add(status, BorderLayout.CENTER);
        bottom.add(controls, BorderLayout.SOUTH);
        wrap.add(bottom, BorderLayout.SOUTH);
        return wrap;
    }

    private JPanel footer() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 14));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(0, 0, 16, 0));
        btnCast = new JButton("开始投屏");
        btnCast.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        btnCast.setPreferredSize(new Dimension(200, 46));
        btnCast.setBackground(ACCENT);
        btnCast.setForeground(Color.WHITE);
        btnCast.setFocusPainted(false);
        btnCast.setOpaque(true);
        btnCast.setBorderPainted(false);
        btnCast.addActionListener(e -> searchAndCast(null));
        p.add(btnCast);
        btnManual = new JButton("手动添加设备");
        btnManual.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        btnManual.setPreferredSize(new Dimension(140, 40));
        btnManual.setBackground(CARD);
        btnManual.setForeground(ACCENT);
        btnManual.setFocusPainted(false);
        btnManual.setOpaque(true);
        btnManual.setBorder(BorderFactory.createLineBorder(new Color(0xC9D9EE)));
        btnManual.addActionListener(e -> manualAdd());
        p.add(btnManual);
        JLabel copy = new JLabel("开发者 ETC 协会 · 翻译者 POAI · MIT License");
        copy.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        copy.setForeground(SUB);
        p.add(copy);
        return p;
    }

    private JPanel modeCard(String title, String sub, String desc, Runnable action) {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBackground(CARD);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE6E6F0), 1),
                new EmptyBorder(22, 18, 18, 18)));
        JLabel t = new JLabel(title);
        t.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        t.setForeground(INK);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel s = new JLabel(sub);
        s.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        s.setForeground(ACCENT);
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        s.setBorder(new EmptyBorder(6, 0, 10, 0));
        JLabel d = new JLabel("<html><body style='width:170px;font-size:12px;color:#6E6E85'>" + desc + "</body></html>");
        d.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.add(t);
        c.add(s);
        c.add(d);
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { action.run(); }
            @Override public void mouseEntered(MouseEvent e) { c.setBackground(new Color(0xF0F6FF)); }
            @Override public void mouseExited(MouseEvent e) { c.setBackground(CARD); }
        });
        return c;
    }

    private void pickFile(boolean imageOnly) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(imageOnly ? "选择图片" : "选择文件（支持全部格式）");
        if (imageOnly) fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String n = f.getName().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                        || n.endsWith(".bmp") || n.endsWith(".gif") || n.endsWith(".webp");
            }
            @Override public String getDescription() { return "图片文件"; }
        });
        int r = fc.showOpenDialog(frame);
        if (r != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (f == null || !f.exists()) return;
        FileServer.currentFile = f;
        FileServer.currentTitle = f.getName();
        FileServer.currentMime = imageOnly ? imageMime(f) : mimeOf(f);
        currentKind = imageOnly ? "image" : "file";
        String url = "http://" + Net.localIp() + ":8388/file?n=" + java.net.URLEncoder.encode(f.getName(), java.nio.charset.StandardCharsets.UTF_8);
        if (imageOnly) url += "&img=1";
        searchAndCast(url);
    }

    private void startLive() {
        if (!ScreenLive.running.get()) {
            ScreenLive.start();
        }
        currentKind = "mirror";
        String frameUrl = "http://" + Net.localIp() + ":8388/frame";
        searchAndCast(frameUrl);
    }

    private void startLink() {
        String url = JOptionPane.showInputDialog(frame, "输入视频链接（支持哔哩哔哩等）", "链接投屏",
                JOptionPane.PLAIN_MESSAGE);
        if (url == null || url.trim().isEmpty()) return;
        new Thread(() -> {
            String direct = Bili.resolve(url.trim());
            SwingUtilities.invokeLater(() -> {
                currentKind = "link";
                FileServer.currentTitle = url.trim();
                searchAndCast(direct);
            });
        }, "etcas-pc-link").start();
    }

    private void manualAdd() {
        String ip = JOptionPane.showInputDialog(frame, "输入设备 IP（如 192.168.1.100）", "手动添加设备",
                JOptionPane.PLAIN_MESSAGE);
        if (ip == null || ip.trim().isEmpty()) return;
        Net.probeIp(ip, (devices, searching) -> {
            if (!searching && devices != null && !devices.isEmpty()) {
                Net.Device d = devices.get(0);
                SwingUtilities.invokeLater(() -> pickDevice(d, null));
            } else if (!searching) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame, "未发现该地址的设备，请确认 IP 与端口（默认 9170）"));
            }
        });
    }

    private void searchAndCast(String contentUrl) {
        SearchDialog dlg = new SearchDialog(frame, url -> {
            if (currentKind.equals("link") && FileServer.currentTitle != null
                    && !FileServer.currentTitle.contains("bilibili.com")) {
                Net.Device dev = currentDev;
                currentDev = dev;
                doCast(dev, url, null, false);
                return;
            }
            pickDevice(url == null ? null : currentDev, url);
        });
        dlg.setVisible(true);
    }

    private void pickDevice(Net.Device dev, String url) {
        if (dev == null) {
            SearchDialog dlg = new SearchDialog(frame, u -> {
                if (u == null) return;
                doCast(null, u, null, true);
            });
            dlg.setVisible(true);
            return;
        }
        doCast(dev, url, null, true);
    }

    private void doCast(Net.Device dev, String contentUrl, String meta, boolean fromSearch) {
        busy = true;
        btnCast.setEnabled(false);
        new Thread(() -> {
            try {
                if (dev == null) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "尚未选择设备"));
                    return;
                }
                if (dev.etcas && fromSearch) {
                    String key = JOptionPane.showInputDialog(frame,
                            "已选择自家接收端：" + dev.name + "\n请输入接收端显示的 6 位配对码",
                            "配对", JOptionPane.PLAIN_MESSAGE);
                    if (key == null) return;
                    boolean ok = Net.pair(dev, key.trim());
                    if (!ok) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "配对码不正确"));
                        return;
                    }
                }
                String uri;
                String m = null;
                if (currentKind.equals("mirror")) {
                    String frameUrl = "http://" + Net.localIp() + ":8388/frame";
                    if (dev.etcas) {
                        uri = "etcas://mirror?u=" + java.net.URLEncoder.encode(frameUrl, java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        uri = "http://" + Net.localIp() + ":8388/mirrorpage";
                    }
                } else if (currentKind.equals("link")) {
                    String direct = contentUrl;
                    if (direct != null && (direct.contains("bilibili.com") || direct.startsWith("http"))) {
                        uri = "http://" + Net.localIp() + ":8388/proxy?u="
                                + java.net.URLEncoder.encode(direct, java.nio.charset.StandardCharsets.UTF_8)
                                + "&ref=" + java.net.URLEncoder.encode("https://www.bilibili.com", java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        uri = direct == null ? "" : direct;
                    }
                } else if (currentKind.equals("image")) {
                    uri = contentUrl != null ? contentUrl
                            : "http://" + Net.localIp() + ":8388/file?n=" + java.net.URLEncoder.encode(FileServer.currentTitle, java.nio.charset.StandardCharsets.UTF_8) + "&img=1";
                    m = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + FileServer.currentTitle + "</dc:title><upnp:class>object.item.imageItem</upnp:class></item></DIDL-Lite>";
                } else {
                    uri = contentUrl != null ? contentUrl
                            : "http://" + Net.localIp() + ":8388/file?n=" + java.net.URLEncoder.encode(FileServer.currentTitle, java.nio.charset.StandardCharsets.UTF_8);
                    m = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + FileServer.currentTitle + "</dc:title><upnp:class>object.item.videoItem</upnp:class></item></DIDL-Lite>";
                }
                final Net.Device devF = dev;
                final String uriF = uri;
                final String metaF = m;
                final boolean ok = Net.cast(devF, uriF, metaF);
                SwingUtilities.invokeLater(() -> {
                    currentDev = devF;
                    statusDev.setText(devF.name + " · " + devF.info());
                    statusTitle.setText(ok ? "投屏中：" + (currentKind.equals("mirror") ? "屏幕直播" : FileServer.currentTitle)
                            : "投屏指令已发送，若电视无反应请尝试手动添加设备");
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    btnCast.setEnabled(true);
                });
            }
        }, "etcas-pc-cast").start();
    }

    private void timerUpdateStatus() {
        new Timer(2000, e -> {
            if (ScreenLive.running.get() && currentDev != null && currentKind.equals("mirror")) {
                statusTitle.setText("屏幕直播中 · " + FileServer.mirrorFrame.length + " 字节/帧");
            }
        }).start();
    }

    private static String mimeOf(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov") || n.endsWith(".mkv")
                || n.endsWith(".avi") || n.endsWith(".wmv") || n.endsWith(".flv") || n.endsWith(".ts")
                || n.endsWith(".webm") || n.endsWith(".m4s") || n.endsWith(".3gp")) return "video/mp4";
        if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aac")
                || n.endsWith(".ogg") || n.endsWith(".m4a")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private static String imageMime(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private class SearchDialog extends JDialog {
        private final DefaultListModel<Net.Device> model = new DefaultListModel<>();
        private final JList<Net.Device> list = new JList<>(model);
        private final java.util.function.Consumer<String> callback;
        private boolean finished;

        SearchDialog(JFrame owner, java.util.function.Consumer<String> cb) {
            super(owner, "搜索投屏设备", true);
            this.callback = cb;
            setSize(430, 420);
            setLocationRelativeTo(owner);
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(BG);
            root.setBorder(new EmptyBorder(14, 14, 14, 14));

            JLabel tip = new JLabel("正在搜索局域网内的电视 / 盒子 / 电脑客户端…（约 10 秒）");
            tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            tip.setForeground(SUB);
            root.add(tip, BorderLayout.NORTH);

            list.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int idx,
                        boolean sel, boolean foc) {
                    JLabel lab = (JLabel) super.getListCellRendererComponent(l, v, idx, sel, foc);
                    Net.Device d = (Net.Device) v;
                    lab.setText("<html><b>" + d.name + "</b><br><font size='-1' color='#6E6E85'>" + d.info() + "</font></html>");
                    lab.setBorder(new EmptyBorder(8, 8, 8, 8));
                    return lab;
                }
            });
            JScrollPane sp = new JScrollPane(list);
            sp.setBorder(BorderFactory.createLineBorder(new Color(0xE6E6F0)));
            root.add(sp, BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
            btns.setOpaque(false);
            JButton manual = new JButton("手动添加");
            manual.setBackground(CARD);
            manual.setForeground(ACCENT);
            manual.setOpaque(true);
            manual.setBorder(BorderFactory.createLineBorder(new Color(0xC9D9EE)));
            manual.addActionListener(e -> {
                String ip = JOptionPane.showInputDialog(this, "输入设备 IP", "手动添加", JOptionPane.PLAIN_MESSAGE);
                if (ip != null && !ip.trim().isEmpty()) {
                    Net.probeIp(ip.trim(), (devices, searching) -> {
                        if (!searching && devices != null && !devices.isEmpty()) {
                            SwingUtilities.invokeLater(() -> choose(devices.get(0)));
                        } else if (!searching) {
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "未发现该地址的设备"));
                        }
                    });
                }
            });
            btns.add(manual);
            JButton cancel = new JButton("取消");
            cancel.addActionListener(e -> { finished = true; callback.accept(null); dispose(); });
            btns.add(cancel);
            root.add(btns, BorderLayout.SOUTH);
            setContentPane(root);

            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        Net.Device d = list.getSelectedValue();
                        if (d != null) choose(d);
                    }
                }
            });
            list.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) return;
                Net.Device d = list.getSelectedValue();
                if (d != null) choose(d);
            });

            Net.discover((devices, searching) -> SwingUtilities.invokeLater(() -> {
                if (finished) return;
                if (searching) {
                    model.clear();
                    for (Net.Device d : devices) model.addElement(d);
                } else {
                    model.clear();
                    for (Net.Device d : devices) model.addElement(d);
                    finished = true;
                    if (devices.isEmpty()) {
                        tip.setText("未搜索到设备，请确认接收端已开启且在同一网络，或点击手动添加");
                    }
                }
            }));
        }

        private void choose(Net.Device d) {
            finished = true;
            dispose();
            callback.accept("__select__");
            SwingUtilities.invokeLater(() -> doCast(d, null, null, true));
        }
    }
}
