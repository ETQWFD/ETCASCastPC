package com.etc.cas.tvpc;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class UpnpServer {

    public interface CastListener {
        void onCast(String uri, String title, int kind);
        void onState(boolean playing);
        void onSpeed(float rate);
    }

    public static final int KIND_VIDEO = 0;
    public static final int KIND_IMAGE = 1;
    public static final int KIND_AUDIO = 2;

    public final String key;
    public final String name = "ETCAS投屏客户端";
    private final String udn = "uuid:etcas-pc-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean paired;
    private volatile boolean playing;
    private volatile float rate = 1.0f;
    private volatile String currentUri = "";
    private volatile String currentTitle = "";
    private volatile int currentKind = KIND_VIDEO;
    public CastListener listener;

    public UpnpServer(String key) {
        this.key = key;
    }

    public void start(int port) {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> serve(s), "etcas-tvpc-conn").start();
                }
            } catch (Exception ignored) {}
        }, "etcas-tvpc-server").start();
        startSsdp(port);
    }

    private void startSsdp(int port) {
        new Thread(() -> {
            try {
                MulticastSocket ms = new MulticastSocket(null);
                ms.setReuseAddress(true);
                try { ms.bind(new InetSocketAddress(1900)); } catch (Exception e) { ms.bind(new InetSocketAddress(0)); }
                InetAddress group = InetAddress.getByName("239.255.255.250");
                NetworkInterface nif = multicastInterface();
                try {
                    if (nif != null) ms.joinGroup(new InetSocketAddress(group, 1900), nif);
                    else ms.joinGroup(group);
                } catch (Exception ignored) {}
                byte[] buf = new byte[8192];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { ms.receive(p); } catch (Exception e) { break; }
                    String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!text.contains("M-SEARCH")) continue;
                    String st = "ssdp:all";
                    for (String line : text.split("\r?\n")) {
                        String l = line.toLowerCase();
                        if (l.startsWith("st:")) st = line.substring(3).trim();
                    }
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "CACHE-CONTROL: max-age=1800\r\n"
                            + "EXT:\r\n"
                            + "LOCATION: http://" + Net.localIp() + ":" + port + "/rootDesc.xml\r\n"
                            + "SERVER: ETCAS/1.0 UPnP/1.0 ETCASCastTV/1.1\r\n"
                            + "ST: " + st + "\r\n"
                            + "USN: " + udn + "::" + st + "\r\n\r\n";
                    try {
                        byte[] data = resp.getBytes(StandardCharsets.UTF_8);
                        ms.send(new DatagramPacket(data, data.length,
                                new InetSocketAddress(p.getAddress(), p.getPort())));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, "etcas-tvpc-ssdp").start();
    }

    private static NetworkInterface multicastInterface() {
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                String n = ni.getName().toLowerCase();
                if (n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("eth") || n.startsWith("en")) return ni;
            }
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast()) return ni;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int state = 0;
            int n;
            byte[] buf = new byte[4096];
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                String head = bos.toString(StandardCharsets.ISO_8859_1);
                int sep = head.indexOf("\r\n\r\n");
                if (sep > 0) {
                    int cl = contentLength(head);
                    if (bos.size() - sep - 4 >= cl) {
                        state = 1;
                        break;
                    }
                }
                if (bos.size() > 64 * 1024) break;
            }
            if (state == 0) return;
            String raw = bos.toString(StandardCharsets.ISO_8859_1);
            int sep = raw.indexOf("\r\n\r\n");
            String head = raw.substring(0, sep);
            String body = raw.substring(sep + 4);
            String[] lines = head.split("\r\n");
            String reqLine = lines[0];
            String method = reqLine.split(" ")[0];
            String path = reqLine.split(" ")[1];
            int q = path.indexOf('?');
            String pathOnly = q > 0 ? path.substring(0, q) : path;
            String query = q > 0 ? path.substring(q + 1) : "";
            String soapAction = "";
            for (String line : lines) {
                String l = line.toLowerCase();
                if (l.startsWith("soapaction:")) soapAction = line.substring(11).trim();
            }
            OutputStream out = s.getOutputStream();
            if (pathOnly.equals("/rootDesc.xml")) {
                rootDesc(out);
            } else if (pathOnly.equals("/etcas/info")) {
                info(out);
            } else if (pathOnly.equals("/etcas/pair") && "POST".equalsIgnoreCase(method)) {
                handlePair(out, body);
            } else if (pathOnly.equals("/etcas/speed")) {
                handleSpeed(out, body, query);
            } else if (pathOnly.equals("/etcas/quality")) {
                handleQuality(out, body);
            } else if (pathOnly.startsWith("/ctl")) {
                handleSoap(out, soapAction, body);
            } else {
                out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            String l = line.toLowerCase();
            if (l.startsWith("content-length:")) {
                try { return Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private void rootDesc(OutputStream out) throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:etcas=\"urn:etcas:device\">\n"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>\n"
                + "<device>\n"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\n"
                + "<friendlyName>" + name + "</friendlyName>\n"
                + "<manufacturer>ETC</manufacturer>\n"
                + "<modelName>ETCAS PC Client</modelName>\n"
                + "<UDN>" + udn + "</UDN>\n"
                + "<iconList><icon><mimetype>image/png</mimetype><width>64</width><height>64</height><depth>24</depth><url>/icon.png</url></icon></iconList>\n"
                + "<serviceList>\n"
                + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/ctl</controlURL>"
                + "<eventSubURL>/evt</eventSubURL><SCPDURL>/scpd.xml</SCPDURL></service>\n"
                + "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><controlURL>/ctl</controlURL>"
                + "<eventSubURL>/evt</eventSubURL><SCPDURL>/scpd.xml</SCPDURL></service>\n"
                + "</serviceList>\n"
                + "<etcas:key>" + key + "</etcas:key>\n"
                + "</device>\n</root>\n";
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void info(OutputStream out) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"model\":\"ETCAS PC Client\",\"key\":\"" + key + "\",\"pair\":true}";
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void handlePair(OutputStream out, String body) throws Exception {
        String submitted = "";
        if (body != null) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "key".equals(pair.substring(0, eq))) {
                    submitted = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        }
        submitted = submitted.trim().toUpperCase();
        boolean ok = key.equalsIgnoreCase(submitted);
        if (ok) paired = true;
        byte[] data = ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8);
        String status = ok ? "200 OK" : "403 Forbidden";
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void handleSpeed(OutputStream out, String body, String query) throws Exception {
        float r = 1.0f;
        String raw = null;
        if (body != null && !body.isEmpty()) {
            for (String kv : body.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "rate".equals(kv.substring(0, eq))) raw = kv.substring(eq + 1);
            }
        }
        if (raw == null && query.contains("rate=")) {
            int idx = query.indexOf("rate=");
            raw = query.substring(idx + 5);
            int amp = raw.indexOf('&');
            if (amp >= 0) raw = raw.substring(0, amp);
        }
        if (raw != null) {
            try { r = Float.parseFloat(URLDecoder.decode(raw, "UTF-8")); } catch (Exception ignored) {}
        }
        rate = Math.max(0.25f, Math.min(2.0f, r));
        if (listener != null) listener.onSpeed(rate);
        byte[] data = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void handleQuality(OutputStream out, String body) throws Exception {
        byte[] data = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void handleSoap(OutputStream out, String soapAction, String body) throws Exception {
        String action = soapAction.contains("#") ? soapAction.substring(soapAction.indexOf('#') + 1) : "";
        if (action.endsWith("\"")) action = action.substring(0, action.length() - 1);
        switch (action) {
            case "SetAVTransportURI": {
                String uri = tag(body, "CurrentURI");
                String title = tagDcTitle(body);
                String meta = tag(body, "CurrentURIMetaData");
                int kind = KIND_VIDEO;
                if (meta != null) {
                    if (meta.contains("imageItem")) kind = KIND_IMAGE;
                    else if (meta.contains("audioItem")) kind = KIND_AUDIO;
                }
                if (kind == KIND_VIDEO && uri != null && (uri.contains("img=1") || isImageUri(uri))) {
                    kind = KIND_IMAGE;
                }
                currentUri = uri == null ? "" : uri;
                currentTitle = title == null ? "" : title;
                currentKind = kind;
                if (listener != null) listener.onCast(currentUri, currentTitle, kind);
                soapResponse(out, "SetAVTransportURI");
                return;
            }
            case "Play":
                playing = true;
                if (listener != null) listener.onState(true);
                soapResponse(out, "Play");
                return;
            case "Pause":
                playing = false;
                if (listener != null) listener.onState(false);
                soapResponse(out, "Pause");
                return;
            case "Stop":
                playing = false;
                if (listener != null) listener.onState(false);
                soapResponse(out, "Stop");
                return;
            case "SetPlaySpeed": {
                String sp = tag(body, "Speed");
                if (sp != null && !sp.isEmpty()) {
                    try { rate = Float.parseFloat(sp); } catch (Exception ignored) {}
                    if (listener != null) listener.onSpeed(rate);
                }
                soapResponse(out, "SetPlaySpeed");
                return;
            }
            case "GetTransportInfo":
                transportInfo(out);
                return;
            case "GetPositionInfo":
                positionInfo(out);
                return;
            default:
                soapResponse(out, action.isEmpty() ? "Response" : action);
        }
    }

    private static boolean isImageUri(String uri) {
        String u = uri.toLowerCase();
        return u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png")
                || u.endsWith(".bmp") || u.endsWith(".gif") || u.endsWith(".webp");
    }

    private static String tag(String body, String name) {
        int i = body.indexOf("<" + name + ">");
        if (i < 0) i = body.indexOf("<" + name + " xmlns");
        if (i < 0) return null;
        int s = body.indexOf('>', i) + 1;
        int e = body.indexOf("</" + name + ">", s);
        if (e < 0) return null;
        String v = body.substring(s, e);
        try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) {}
        return v.trim();
    }

    private static String tagDcTitle(String body) {
        for (String name : new String[]{"dc:title", "title"}) {
            int i = body.indexOf("<" + name + ">");
            if (i < 0) i = body.indexOf("<" + name + " ");
            if (i < 0) continue;
            int s = body.indexOf('>', i) + 1;
            int e = body.indexOf("</" + name + ">", s);
            if (e < 0) continue;
            return body.substring(s, e).trim();
        }
        return null;
    }

    private void soapResponse(OutputStream out, String action) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + "Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:"
                + action + "Response></s:Body></s:Envelope>";
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void transportInfo(OutputStream out) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<CurrentTransportState>" + (playing ? "PLAYING" : "STOPPED") + "</CurrentTransportState>"
                + "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
                + "</u:GetTransportInfoResponse></s:Body></s:Envelope>";
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }

    private void positionInfo(OutputStream out) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:GetPositionInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<TrackDuration>0</TrackDuration><RelTime>0</RelTime><AbsTime>0</AbsTime>"
                + "</u:GetPositionInfoResponse></s:Body></s:Envelope>";
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(data);
    }
}
