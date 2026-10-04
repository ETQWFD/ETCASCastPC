package test;

import com.etc.cas.pc.FileServer;
import com.etc.cas.pc.Net;
import com.etc.cas.tvpc.UpnpServer;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class PcProtoTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // ===== 1. Mock DLNA 设备（返回 rootDesc + SOAP 200）=====
        final AtomicReference<String> receivedUri = new AtomicReference<>();
        final AtomicReference<String> receivedMeta = new AtomicReference<>();
        ServerSocket mock = new ServerSocket(9171);
        new Thread(() -> {
            try {
                while (true) {
                    Socket s = mock.accept();
                    new Thread(() -> {
                        try {
                            InputStream in = s.getInputStream();
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                bos.write(buf, 0, n);
                                String head = bos.toString(StandardCharsets.ISO_8859_1);
                                if (head.contains("\r\n\r\n")) break;
                            }
                            String req = bos.toString(StandardCharsets.ISO_8859_1);
                            String line0 = req.split("\r\n")[0];
                            String path = line0.split(" ")[1];
                            OutputStream out = s.getOutputStream();
                            if (path.contains("rootDesc")) {
                                String xml = "<?xml version=\"1.0\"?><root><device>"
                                        + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                                        + "<friendlyName>Mock TV</friendlyName><modelName>MockDLNA</modelName>"
                                        + "<UDN>uuid:mocktv-001</UDN>"
                                        + "<serviceList>"
                                        + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                                        + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
                                        + "<controlURL>/ctl</controlURL></service>"
                                        + "</serviceList></device></root>";
                                byte[] d = xml.getBytes(StandardCharsets.UTF_8);
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: " + d.length
                                        + "\r\nConnection: close\r\n\r\n").getBytes());
                                out.write(d);
                            } else if (path.contains("/ctl")) {
                                String body = req.substring(req.indexOf("\r\n\r\n") + 4);
                                if (body.contains("SetAVTransportURI")) {
                                    int u = body.indexOf("<CurrentURI>");
                                    int u2 = body.indexOf("</CurrentURI>", u);
                                    if (u >= 0 && u2 >= 0) {
                                        receivedUri.set(body.substring(u + 12, u2));
                                        receivedMeta.set(body.contains("CurrentURIMetaData") ? "has-meta" : "no-meta");
                                    }
                                }
                                String xml = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                        + "<s:Body><u:Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/></s:Body></s:Envelope>";
                                byte[] d = xml.getBytes(StandardCharsets.UTF_8);
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: " + d.length
                                        + "\r\nConnection: close\r\n\r\n").getBytes());
                                out.write(d);
                            } else {
                                out.write("HTTP/1.1 404\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                            }
                            out.flush();
                            s.close();
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (Exception ignored) {}
        }).start();

        // ===== 2. 投屏端解析 Mock 设备 =====
        Net.Device d = Net.parse("http://127.0.0.1:9171/rootDesc.xml");
        check("解析设备", d != null && "Mock TV".equals(d.name) && d.controlUrl != null && "DLNA 投屏设备".equals(d.type));
        check("解析设备 ip", d != null && "127.0.0.1".equals(d.ip));

        // ===== 3. 投屏端 cast（SOAP 转义）=====
        boolean castOk = d != null && Net.cast(d, "http://127.0.0.1:8388/file?n=a&b.mp4&img=1", "<DIDL-Lite><item><dc:title>a&b</dc:title></item></DIDL-Lite>");
        check("cast 返回成功", castOk);
        check("SOAP URI 正确转义", receivedUri.get() != null && receivedUri.get().contains("&amp;") && receivedUri.get().contains("img=1"));
        check("SOAP meta 传入", "has-meta".equals(receivedMeta.get()));

        // ===== 4. 自家客户端 UpnpServer（配对 / SOAP / kind 判定）=====
        final AtomicReference<String> castUri = new AtomicReference<>();
        final AtomicReference<Integer> castKind = new AtomicReference<>(-1);
        UpnpServer tv = new UpnpServer("123456");
        tv.listener = new UpnpServer.CastListener() {
            @Override public void onCast(String uri, String title, int kind) {
                castUri.set(uri);
                castKind.set(kind);
            }
            @Override public void onState(boolean playing) {}
            @Override public void onSpeed(float rate) {}
        };
        tv.start(9170);
        Thread.sleep(800);

        String rootDesc = httpGet("http://127.0.0.1:9170/rootDesc.xml");
        check("rootDesc 含 ETCAS 标识", rootDesc != null && rootDesc.contains("ETCAS投屏客户端") && rootDesc.contains("<etcas:key>123456</etcas:key>"));
        check("rootDesc 含控制端点", rootDesc != null && rootDesc.contains("/ctl"));

        String pairOk = httpPost("http://127.0.0.1:9170/etcas/pair", "key=123456");
        check("配对成功", pairOk != null && pairOk.contains("\"ok\":true"));
        String pairBad = httpPost("http://127.0.0.1:9170/etcas/pair", "key=000000");
        check("错误配对拒绝", pairBad != null && pairBad.contains("\"ok\":false"));

        String speed = httpPost("http://127.0.0.1:9170/etcas/speed", "rate=2.0");
        check("倍速通道", speed != null && speed.contains("\"ok\":true"));

        String soap = soapCall("http://127.0.0.1:9170/ctl", "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>http://1.2.3.4/file.mp4?img=1</CurrentURI>"
                        + "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>");
        check("SOAP SetAVTransportURI 200", soap != null && soap.contains("200"));
        check("kind 判定 img=1 → 图片", castKind.get() != null && castKind.get() == UpnpServer.KIND_IMAGE);

        // ===== 5. 文件服务器（Range）=====
        FileServer.currentFile = new File("/tmp/ptest.bin");
        try (FileOutputStream fo = new FileOutputStream("/tmp/ptest.bin")) {
            byte[] data = new byte[1024 * 1024];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
            fo.write(data);
        }
        FileServer.currentMime = "video/mp4";
        FileServer.currentTitle = "test.mp4";
        FileServer.start(8388);
        Thread.sleep(500);
        String range = curlRange("http://127.0.0.1:8388/file", "bytes=100-199");
        check("文件服务器 206 Range", range != null && range.contains("206") && range.contains("bytes 100-199/1048576") && range.contains("len=100"));
        System.out.println("  file-range: " + range);

        // ===== 6. 图片 meta 判定（imageItem）=====
        castKind.set(-1);
        String soapImg = soapCall("http://127.0.0.1:9170/ctl", "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>http://1.2.3.4/pic.jpg</CurrentURI>"
                        + "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>");
        check("SOAP 图片 200", soapImg != null && soapImg.contains("200"));
        check("kind 判定 imageItem → 图片", castKind.get() != null && castKind.get() == UpnpServer.KIND_IMAGE);

        System.out.println("====== 结果: " + (failures == 0 ? "全部 PASS" : failures + " 项失败") + " ======");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (!ok) failures++;
    }

    static String httpGet(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            int code = c.getResponseCode();
            byte[] d = c.getInputStream().readAllBytes();
            return code + "|" + new String(d, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    static String httpPost(String url, String body) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] d = in == null ? new byte[0] : in.readAllBytes();
            return code + "|" + new String(d, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    static String soapCall(String url, String action, String args) {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + args + "</u:" + action + "></s:Body></s:Envelope>";
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"");
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            byte[] d = c.getInputStream().readAllBytes();
            return code + "|" + new String(d, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    static String curlRange(String url, String range) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            String cr = c.getHeaderField("Content-Range");
            byte[] d = c.getInputStream().readAllBytes();
            return code + "|" + (cr == null ? "" : cr) + "|len=" + d.length + "|first=" + (d.length > 0 ? (d[0] & 0xFF) : -1);
        } catch (Exception e) { return null; }
    }
}
