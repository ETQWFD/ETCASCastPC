package com.etc.cas.pc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class FileServer {

    public static volatile File currentFile;
    public static volatile String currentMime = "application/octet-stream";
    public static volatile String currentTitle = "";
    public static volatile byte[] mirrorFrame = null;
    public static volatile long mirrorSeq = 0;
    public static volatile boolean running = true;

    private static final int BUF = 256 * 1024;

    public static void start(int port) {
        try {
            HttpServer srv = HttpServer.create(new java.net.InetSocketAddress(port), 0);
            srv.createContext("/", FileServer::handle);
            srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r); t.setDaemon(true); return t;
            }));
            srv.start();
        } catch (Exception e) {
            System.err.println("fileserver fail: " + e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String range = ex.getRequestHeaders().getFirst("Range");
        OutputStream out = ex.getResponseBody();
        try {
            if (path.startsWith("/frame")) {
                serveFrame(ex);
            } else if (path.startsWith("/mirrorpage")) {
                serveMirrorPage(ex);
            } else if (path.startsWith("/mirror")) {
                serveMirror(ex);
            } else if (path.startsWith("/file")) {
                serveFile(ex, range);
            } else if (path.startsWith("/proxy")) {
                serveProxy(ex, query, range);
            } else {
                ex.sendResponseHeaders(404, -1);
            }
        } catch (Exception ignored) {
        } finally {
            try { ex.close(); } catch (Exception ignored) {}
        }
    }

    private static void serveFrame(HttpExchange ex) throws IOException {
        byte[] frame = mirrorFrame;
        if (frame == null || frame.length == 0) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, frame.length);
        ex.getResponseBody().write(frame);
    }

    private static void serveMirrorPage(HttpExchange ex) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>ETCAS Mirror</title></head>"
                + "<body style=\"margin:0;background:#000\">"
                + "<img src=\"/mirror\" style=\"width:100vw;height:100vh;object-fit:contain\">"
                + "</body></html>";
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private static void serveMirror(HttpExchange ex) throws IOException {
        if (mirrorFrame == null) {
            ex.sendResponseHeaders(503, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=etcasframe");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        long lastSeq = mirrorSeq;
        int idle = 0;
        while (running) {
            long seq = mirrorSeq;
            byte[] frame = mirrorFrame;
            if (seq == lastSeq || frame == null || frame.length == 0) {
                if (++idle > 3000) break;
                try { Thread.sleep(12); } catch (InterruptedException e) { break; }
                continue;
            }
            idle = 0;
            lastSeq = seq;
            StringBuilder h = new StringBuilder();
            h.append("--etcasframe\r\nContent-Type: image/jpeg\r\nContent-Length: ").append(frame.length).append("\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            out.write(frame);
            out.write("\r\n".getBytes());
            out.flush();
        }
    }

    private static void serveFile(HttpExchange ex, String rangeHeader) throws IOException {
        File f = currentFile;
        if (f == null || !f.exists()) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        long total = f.length();
        String mime = currentMime != null ? currentMime : "application/octet-stream";
        long start = 0;
        long end = total > 0 ? total - 1 : Long.MAX_VALUE;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String r = rangeHeader.substring(6).replace(" ", "");
            int dash = r.indexOf('-');
            try {
                long rs = Long.parseLong(r.substring(0, dash));
                start = rs;
                if (dash + 1 < r.length()) end = Long.parseLong(r.substring(dash + 1));
                partial = true;
            } catch (Exception ignored) {}
        }
        if (total > 0 && end > total - 1) end = total - 1;
        if (start < 0) start = 0;
        if (partial && total > 0 && start >= total) {
            ex.sendResponseHeaders(416, -1);
            return;
        }
        long len = end - start + 1;
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (currentTitle != null && !currentTitle.isEmpty()) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(currentTitle, StandardCharsets.UTF_8));
        }
        if (partial) {
            ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + total);
            ex.sendResponseHeaders(206, len);
        } else {
            ex.sendResponseHeaders(200, total >= 0 ? total : 0);
        }
        OutputStream out = ex.getResponseBody();
        try (InputStream in = new FileInputStream(f)) {
            long skip = start;
            byte[] buf = new byte[BUF];
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) { if (in.read() == -1) break; n = 1; }
                skip -= n;
            }
            long left = len;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(BUF, left))) > 0) {
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    private static void serveProxy(HttpExchange ex, String query, String rangeHeader) throws IOException {
        String target = null;
        String ref = null;
        try {
            if (query != null) {
                for (String kv : query.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        String k = kv.substring(0, eq);
                        String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                        if ("u".equals(k)) target = v;
                        else if ("ref".equals(k)) ref = v;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (target == null || target.isEmpty()) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        HttpURLConnection conn = null;
        try {
            URL u = new URL(target);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            if (ref != null && !ref.isEmpty()) conn.setRequestProperty("Referer", ref);
            if (rangeHeader != null) conn.setRequestProperty("Range", rangeHeader);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                ex.sendResponseHeaders(code, -1);
                return;
            }
            String ct = conn.getContentType();
            long len = conn.getContentLengthLong();
            if (ct != null) ex.getResponseHeaders().set("Content-Type", ct);
            if (len >= 0) ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.sendResponseHeaders(code, len >= 0 ? len : 0);
            OutputStream out = ex.getResponseBody();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Exception e) {
            ex.sendResponseHeaders(502, -1);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
