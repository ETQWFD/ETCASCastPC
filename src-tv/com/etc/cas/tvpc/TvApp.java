package com.etc.cas.tvpc;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Random;

public class TvApp {

    private static final Color ACCENT = new Color(0x2E86DE);
    private static final Color ACCENT_DEEP = new Color(0x1B5FA8);
    private static final Color BG = new Color(0xF6F7FB);
    private static final Color INK = new Color(0x1A1A2E);
    private static final Color SUB = new Color(0x6E6E85);

    private JFrame frame;
    private JLabel statusLabel;
    private UpnpServer server;
    private Receiver receiver;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new TvApp().open());
    }

    private void open() {
        String key = String.format("%06d", new Random().nextInt(1000000));
        receiver = new Receiver();
        server = new UpnpServer(key);
        server.listener = new UpnpServer.CastListener() {
            @Override public void onCast(String uri, String title, int kind) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("正在接收投屏：" + (title.isEmpty() ? "内容" : title));
                });
                receiver.handleCast(uri, title, kind);
            }
            @Override public void onState(boolean playing) {
                if (!playing) receiver.onState(false);
            }
            @Override public void onSpeed(float rate) {
                VideoPlayer.setRate(rate);
            }
        };
        server.start(9170);

        frame = new JFrame("ETCAS 投屏 · 电脑接收端");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(640, 460);
        frame.setMinimumSize(new Dimension(560, 420));
        frame.setLocationRelativeTo(null);
        frame.setContentPane(build(key));
        frame.setVisible(true);
    }

    private JPanel build(String key) {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(0, 0, 0, 0));

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(ACCENT_DEEP);
        head.setPreferredSize(new Dimension(0, 62));
        JLabel title = new JLabel("ETCAS 投屏 · 电脑接收端");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 19));
        title.setForeground(Color.WHITE);
        title.setBorder(new EmptyBorder(0, 22, 0, 0));
        head.add(title, BorderLayout.WEST);
        statusLabel = new JLabel("等待投屏…");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusLabel.setForeground(new Color(0xDCE8F7));
        statusLabel.setBorder(new EmptyBorder(0, 0, 0, 22));
        head.add(statusLabel, BorderLayout.EAST);
        root.add(head, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setBackground(BG);
        body.setBorder(new EmptyBorder(20, 26, 20, 26));

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBackground(Color.WHITE);
        info.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE6E6F0), 1),
                new EmptyBorder(20, 20, 20, 20)));
        info.add(big("本机 IP"));
        info.add(value(Net.localIp()));
        info.add(box(10));
        info.add(big("端口"));
        info.add(value("9170"));
        info.add(box(10));
        info.add(big("6 位配对码"));
        JLabel keyLab = new JLabel(key);
        keyLab.setFont(new Font("Consolas", Font.BOLD, 34));
        keyLab.setForeground(ACCENT);
        keyLab.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(keyLab);
        info.add(box(8));
        JLabel tip = new JLabel("手机 / 电脑投屏端搜索到本客户端后，输入上方配对码即可连接；用扫码投屏则无需输码，扫码自动配对。");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        tip.setForeground(SUB);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(tip);
        body.add(info, BorderLayout.CENTER);

        JPanel qr = new JPanel(new BorderLayout());
        qr.setBackground(Color.WHITE);
        qr.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE6E6F0), 1),
                new EmptyBorder(14, 14, 14, 14)));
        String qrText = "etcas://cast?ip=" + Net.localIp() + "&port=9170&k=" + key;
        ImageIcon icon = new ImageIcon(QrGen.make(qrText, 240));
        JLabel qrLab = new JLabel(icon, SwingConstants.CENTER);
        qr.add(qrLab, BorderLayout.CENTER);
        JLabel qrTip = new JLabel("扫码投屏专用二维码", SwingConstants.CENTER);
        qrTip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        qrTip.setForeground(SUB);
        qrTip.setBorder(new EmptyBorder(8, 0, 0, 0));
        qr.add(qrTip, BorderLayout.SOUTH);
        body.add(qr, BorderLayout.EAST);

        root.add(body, BorderLayout.CENTER);

        JPanel foot = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        foot.setBackground(BG);
        foot.setBorder(new EmptyBorder(0, 0, 16, 0));
        JButton show = new JButton("打开接收窗口");
        show.setBackground(ACCENT);
        show.setForeground(Color.WHITE);
        show.setOpaque(true);
        show.setBorderPainted(false);
        show.setFocusPainted(false);
        show.setPreferredSize(new Dimension(140, 38));
        show.addActionListener(e -> receiver.show());
        foot.add(show);
        JButton refresh = new JButton("更换配对码");
        refresh.setBackground(Color.WHITE);
        refresh.setForeground(ACCENT);
        refresh.setOpaque(true);
        refresh.setBorder(BorderFactory.createLineBorder(new Color(0xC9D9EE)));
        refresh.setFocusPainted(false);
        refresh.setPreferredSize(new Dimension(120, 38));
        refresh.addActionListener(e -> {
            String k2 = String.format("%06d", new Random().nextInt(1000000));
            keyLab.setText(k2);
        });
        foot.add(refresh);
        JLabel copy = new JLabel("开发者 ETC 协会 · 翻译者 POAI · MIT License");
        copy.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        copy.setForeground(SUB);
        foot.add(copy);
        root.add(foot, BorderLayout.SOUTH);
        return root;
    }

    private static JLabel big(String t) {
        JLabel l = new JLabel(t);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        l.setForeground(SUB);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel value(String t) {
        JLabel l = new JLabel(t);
        l.setFont(new Font("Consolas", Font.BOLD, 24));
        l.setForeground(INK);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static Component box(int h) {
        return Box.createVerticalStrut(h);
    }
}
