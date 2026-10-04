package com.etc.cas.tvpc;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;

public class Receiver {

    private JFrame win;
    private JLabel imgLabel;
    private volatile boolean mirroring;
    private Thread mirrorThread;

    public void show() {
        if (win != null && win.isVisible()) return;
        win = new JFrame("ETCAS 投屏接收");
        win.setSize(960, 540);
        win.setLocationRelativeTo(null);
        win.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        win.setBackground(Color.BLACK);
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.BLACK);
        imgLabel = new JLabel("投屏加载中…  ETC协会制作，让我们致敬开发者！", SwingConstants.CENTER);
        imgLabel.setForeground(Color.WHITE);
        imgLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 22));
        p.add(imgLabel, BorderLayout.CENTER);
        win.setContentPane(p);
        win.setVisible(true);
    }

    public void handleCast(String uri, String title, int kind) {
        show();
        if (kind == UpnpServer.KIND_IMAGE) {
            showImage(uri, title);
        } else if (uri != null && uri.startsWith("etcas://mirror")) {
            showMirror(uri);
        } else if (uri != null && uri.startsWith("http")) {
            playMedia(uri, title);
        } else {
            setText(title == null || title.isEmpty() ? "正在投屏…" : title);
        }
    }

    public void onState(boolean playing) {
        if (!playing) setText("投屏已停止");
    }

    private void setText(String t) {
        SwingUtilities.invokeLater(() -> {
            if (imgLabel != null) {
                imgLabel.setIcon(null);
                imgLabel.setText(t);
            }
        });
    }

    private void showImage(String url, String title) {
        setText("加载中…");
        new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(new URL(url));
                SwingUtilities.invokeLater(() -> {
                    if (imgLabel != null) {
                        imgLabel.setText("");
                        imgLabel.setIcon(scale(img));
                    }
                });
            } catch (Exception e) {
                setText("图片加载失败：" + url);
            }
        }, "etcas-tvpc-img").start();
    }

    private ImageIcon scale(BufferedImage img) {
        Dimension d = imgLabel.getSize();
        int w = Math.max(200, d.width), h = Math.max(120, d.height);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        double s = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        int iw = (int) (img.getWidth() * s), ih = (int) (img.getHeight() * s);
        g.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
        g.dispose();
        return new ImageIcon(out);
    }

    private void showMirror(String uri) {
        String u = "";
        try {
            int q = uri.indexOf('?');
            if (q > 0) {
                for (String kv : uri.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && "u".equals(kv.substring(0, eq))) u = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {}
        final String frameUrl = u.isEmpty() ? "http://" + Net.localIp() + ":8388/frame" : u;
        mirroring = true;
        setText("屏幕直播连接中…");
        if (mirrorThread != null) mirrorThread.interrupt();
        mirrorThread = new Thread(() -> {
            long last = 0;
            while (mirroring) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(frameUrl).openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        in.close();
                        byte[] data = bos.toByteArray();
                        if (data.length > 100) {
                            long now = System.currentTimeMillis();
                            if (now - last > 120) {
                                last = now;
                                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                                if (img != null) {
                                    SwingUtilities.invokeLater(() -> {
                                        if (imgLabel != null) {
                                            imgLabel.setText("");
                                            imgLabel.setIcon(scale(img));
                                        }
                                    });
                                }
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) {}
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }, "etcas-tvpc-mirror");
        mirrorThread.setDaemon(true);
        mirrorThread.start();
    }

    public void playMedia(String url, String title) {
        setText("视频加载中…");
        VideoPlayer.play(url, title);
    }

    public void stopMirror() {
        mirroring = false;
        if (mirrorThread != null) {
            mirrorThread.interrupt();
            mirrorThread = null;
        }
    }
}
