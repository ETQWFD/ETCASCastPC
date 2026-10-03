package com.etc.cas.pc;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AiChat {

    private static final String ENDPOINT = "https://api.hcnsec.cn/v1/chat/completions";
    private static final String MODEL = "DeepSeek-V4-Flash";
    private static final String KEY = "sk-iulr7ePG32AVIKBvXFs6m5Vgik2osFzluDMShJwGubyJCxnt";
    private static final String SYS = "你是 ETCAS 投屏官方 AI 客服助手。产品背景：ETCAS 投屏（手机端 com.etc.cas / 电视端 com.etc.cas.tv / 电脑端），官网 https://etc.os.kg ，开源仓库：手机 https://github.com/ETQWFD/ETCASCast 、电视 https://github.com/ETQWFD/ETCASCastTV 、电脑 https://github.com/ETQWFD/ETCASCastPC ，开发者 ETC 协会，翻译者 POAI，MIT 开源。功能：本地文件/图片投屏（含 m4s）、网站链接投屏（哔哩哔哩等解析直链）、屏幕同步镜像/电脑直播、扫码投屏、手动添加设备、DLNA 设备搜索（云视听小电视/酷喵/芒果等）、画质倍速切换、检查更新。最低 Android 7，兼容 64/32/x86；电视端支持 U 盘安装；电脑端支持 Windows 10/11 x64。投屏走局域网直连不经公网服务器。常见问题：搜不到设备→确认同一网络且接收端已开启，可扫码或手动添加 IP；自家接收端扫码免输码自动配对，手动连接需输 6 位配对码；投屏黑屏→确认格式支持，网页无直链先在投屏端观看。请用简体中文简洁友好回答，不要编造不存在的功能或版本。";

    public static void attach(JFrame frame) {
        JButton fab = new JButton("AI");
        fab.setToolTipText("AI 客服");
        fab.setFont(new Font("Dialog", Font.BOLD, 15));
        fab.setForeground(Color.WHITE);
        fab.setBackground(new Color(0x2E86DE));
        fab.setFocusPainted(false);
        fab.setBorderPainted(false);
        fab.setOpaque(true);
        fab.setPreferredSize(new Dimension(52, 52));
        fab.setBounds(0, 0, 52, 52);
        frame.getLayeredPane().add(fab, Integer.valueOf(300));

        JDialog chat = new JDialog(frame, "ETCAS 投屏 · AI 客服", false);
        chat.setSize(360, 460);
        chat.setLocationRelativeTo(frame);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);

        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(new Color(0x2E86DE));
        head.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JLabel title = new JLabel("ETCAS 投屏 · AI 客服");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Dialog", Font.BOLD, 14));
        head.add(title, BorderLayout.WEST);
        root.add(head, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(new Color(0xF6F7FB));
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        JTextArea input = new JTextArea(2, 20);
        input.setFont(new Font("Dialog", Font.PLAIN, 13));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        JButton send = new JButton("发送");
        send.setBackground(new Color(0x2E86DE));
        send.setForeground(Color.WHITE);
        send.setFocusPainted(false);
        send.setOpaque(true);
        send.setBorderPainted(false);
        send.setPreferredSize(new Dimension(64, 34));
        JPanel foot = new JPanel(new BorderLayout(6, 0));
        foot.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        foot.setBackground(Color.WHITE);
        foot.add(input, BorderLayout.CENTER);
        foot.add(send, BorderLayout.EAST);
        root.add(foot, BorderLayout.SOUTH);

        chat.setContentPane(root);

        final boolean[] busy = {false};
        ActionListener ask = e -> {
            String t = input.getText().trim();
            if (t.isEmpty() || busy[0]) return;
            input.setText("");
            addMsg(body, t, true);
            busy[0] = true;
            send.setEnabled(false);
            addTip(body, "思考中…");
            new Thread(() -> {
                final String reply = callApi(t);
                SwingUtilities.invokeLater(() -> {
                    busy[0] = false;
                    send.setEnabled(true);
                    removeTips(body);
                    addMsg(body, reply == null ? "抱歉，服务暂时繁忙，请稍后重试。" : reply, false);
                });
            }, "etcas-pc-ai").start();
        };
        send.addActionListener(ask);
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        input.getActionMap().put("send", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { ask.actionPerformed(e); }
        });

        chat.setVisible(false);
        fab.addActionListener(e -> {
            if (chat.isVisible()) chat.setVisible(false);
            else {
                chat.setLocationRelativeTo(frame);
                chat.setVisible(true);
            }
        });
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentMoved(java.awt.event.ComponentEvent e) {
                chat.setLocationRelativeTo(frame);
            }
        });
    }

    private static void addMsg(JPanel body, String text, boolean me) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font("Dialog", Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        wrap.setOpaque(false);
        if (me) {
            area.setBackground(new Color(0x2E86DE));
            area.setForeground(Color.WHITE);
            wrap.setBorder(BorderFactory.createEmptyBorder(4, 30, 4, 6));
        } else {
            area.setBackground(Color.WHITE);
            area.setForeground(new Color(0x222222));
            wrap.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 30));
        }
        area.setOpaque(true);
        wrap.add(area, BorderLayout.CENTER);
        body.add(wrap);
        SwingUtilities.invokeLater(() -> {
            JScrollPane sp = (JScrollPane) body.getParent();
            sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
        });
    }

    private static void addTip(JPanel body, String text) {
        JLabel tip = new JLabel(text);
        tip.setFont(new Font("Dialog", Font.PLAIN, 12));
        tip.setForeground(new Color(0x888888));
        tip.setAlignmentX(Component.CENTER_ALIGNMENT);
        tip.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        body.add(tip);
        body.revalidate();
        body.repaint();
    }

    private static void removeTips(JPanel body) {
        for (java.awt.Component c : body.getComponents()) {
            if (c instanceof JLabel) body.remove(c);
        }
        body.revalidate();
        body.repaint();
    }

    private static String callApi(String userText) {
        try {
            String body = "{\"model\":\"" + MODEL + "\",\"temperature\":0.7,\"max_tokens\":800,"
                    + "\"messages\":[{\"role\":\"system\",\"content\":"
                    + json(SYS) + "},{\"role\":\"user\",\"content\":" + json(userText) + "}]}";
            HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + KEY);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            conn.disconnect();
            if (code != 200) return null;
            String resp = bos.toString("UTF-8");
            int ci = resp.indexOf("\"content\":");
            if (ci < 0) return null;
            String content = resp.substring(ci + 10);
            int end = content.indexOf("\",");
            if (end < 0) end = content.length() - 1;
            content = content.substring(0, end);
            content = content.replace("\\n", "\n").replace("\\\"", "\"");
            return content.trim().isEmpty() ? null : content;
        } catch (Exception e) {
            return null;
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
