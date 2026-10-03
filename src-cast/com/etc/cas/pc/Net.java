package com.etc.cas.pc;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class Net {

    public static class Device {
        public String name, type, ip, location, udn, controlUrl, volumeControlUrl, key;
        public int port;
        public boolean etcas;
        public String baseUrl() { return "http://" + ip + ":" + port; }
        public String info() { return type + " · " + ip; }
        @Override public String toString() { return name; }
    }

    public interface Listener { void onDevices(List<Device> devices, boolean searching); }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:Basic:1",
            "upnp:rootdevice", "ssdp:all"};
    private static final int[] PROBE_PORTS = {9170, 80, 8080, 8060, 9000, 49152, 5000, 1900};
    private static final String[] PROBE_PATHS = {"/rootDesc.xml", "/description.xml", "/dd.xml"};

    private static final java.awt.event.ActionListener noop = e -> {};

    public static String localIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                boolean pref = name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth") || name.startsWith("en");
                for (Enumeration<InetAddress> ea = ni.getInetAddresses(); ea.hasMoreElements(); ) {
                    InetAddress ia = ea.nextElement();
                    if (ia instanceof Inet4Address && !ia.isLinkLocalAddress()) {
                        byte[] b = ia.getAddress();
                        int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                        if (pref && isPrivate(b)) return ia.getHostAddress();
                        if (ip != 0) return ia.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static boolean isPrivate(byte[] b) {
        int a = b[0] & 0xFF;
        return a == 10 || a == 172 || a == 192 || a == 100;
    }

    public static String subnetPrefix() {
        String ip = localIp();
        if (ip == null || ip.equals("127.0.0.1")) return null;
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last + 1) : null;
    }

    public static void discover(Listener listener) {
        Thread t = new Thread(() -> {
            final List<Device> result = Collections.synchronizedList(new ArrayList<>());
            final Set<String> seen = new LinkedHashSet<>();
            MulticastSocket socket = null;
            long end = System.currentTimeMillis() + 10000;
            long lastSend = 0;
            String prefix = subnetPrefix();
            ExecutorService probePool = prefix == null ? null : Executors.newFixedThreadPool(32, r -> {
                Thread th = new Thread(r); th.setDaemon(true); return th;
            });
            if (probePool != null) {
                for (int i = 1; i <= 254; i++) {
                    final String ip = prefix + i;
                    probePool.submit(() -> {
                        Device d = null;
                        for (int port : PROBE_PORTS) {
                            if (d != null) break;
                            if (!tcp(ip, port, 180)) continue;
                            for (String path : PROBE_PATHS) {
                                d = parse("http://" + ip + ":" + port + path);
                                if (d != null && d.controlUrl != null) break;
                            }
                        }
                        if (d != null && d.controlUrl != null && seen.add(key(d))) {
                            result.add(d);
                            listener.onDevices(snapshot(result), true);
                        }
                    });
                }
                probePool.shutdown();
            }
            try {
                socket = new MulticastSocket(null);
                socket.setReuseAddress(true);
                try { socket.bind(new InetSocketAddress(SSDP_PORT)); } catch (Exception e) { socket.bind(new InetSocketAddress(0)); }
                socket.setSoTimeout(1000);
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                NetworkInterface nif = multicastInterface();
                try {
                    if (nif != null) socket.joinGroup(new InetSocketAddress(group, SSDP_PORT), nif);
                    else socket.joinGroup(group);
                } catch (Exception ignored) {}
                listener.onDevices(snapshot(result), true);
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() - lastSend > 1000) {
                        for (String st : SEARCH_TARGETS) {
                            String msg = "M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT
                                    + "\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: " + st
                                    + "\r\nUSER-AGENT: ETCASCast/1.4\r\n\r\n";
                            try {
                                socket.send(new DatagramPacket(msg.getBytes(StandardCharsets.UTF_8), msg.length(), group, SSDP_PORT));
                            } catch (Exception ignored) {}
                        }
                        lastSend = System.currentTimeMillis();
                    }
                    try {
                        byte[] buf = new byte[8192];
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        socket.receive(p);
                        String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        String loc = extractLocation(text);
                        if (loc != null && seen.add(loc)) {
                            Device d = parse(loc);
                            if (d != null && (d.controlUrl != null || true) && seen.add(key(d))) {
                                result.add(d);
                                listener.onDevices(snapshot(result), true);
                            }
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (Exception ignored) {} finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
            try {
                if (probePool != null) probePool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            listener.onDevices(snapshot(result), false);
        }, "etcas-pc-discover");
        t.setDaemon(true);
        t.start();
    }

    public static void probeIp(String ip, Listener listener) {
        Thread t = new Thread(() -> {
            List<Device> r = new ArrayList<>();
            listener.onDevices(r, true);
            Device d = probeOne(ip.trim());
            if (d != null) r.add(d);
            listener.onDevices(r, false);
        }, "etcas-pc-probe");
        t.setDaemon(true);
        t.start();
    }

    private static Device probeOne(String clean) {
        if (clean.startsWith("http://") || clean.startsWith("https://")) return parse(clean);
        int colon = clean.indexOf(':');
        final String host = colon > 0 ? clean.substring(0, colon) : clean;
        AtomicReference<Device> found = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(16, r -> { Thread th = new Thread(r); th.setDaemon(true); return th; });
        int[] ports = {9170, 80, 8080, 8060, 9000, 49152, 49153, 49154, 5000, 1900, 36666, 52235};
        String[] paths = {"/rootDesc.xml", "/dd.xml", "/description.xml", "/upnp/description.xml",
                "/DeviceDescription.xml", "/devicedesc.xml", "/dmr.xml", "/xml/device_description.xml"};
        List<Future<?>> fs = new ArrayList<>();
        for (int port : ports) for (String path : paths) {
            fs.add(pool.submit(() -> {
                if (found.get() != null) return;
                Device d = parse("http://" + host + ":" + port + path);
                if (d != null) found.compareAndSet(null, d);
            }));
        }
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
        for (Future<?> f : fs) f.cancel(true);
        return found.get();
    }

    private static NetworkInterface multicastInterface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                String n = ni.getName().toLowerCase();
                if (n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("eth") || n.startsWith("en")) return ni;
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast()) return ni;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean tcp(String ip, int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeout);
            return true;
        } catch (Exception e) { return false; }
    }

    private static String extractLocation(String text) {
        for (String line : text.split("\r?\n")) {
            String l = line.toLowerCase();
            int idx = l.indexOf("location:");
            if (idx >= 0) {
                String url = line.substring(idx + 9).trim();
                if (url.startsWith("http")) return url;
            }
        }
        return null;
    }

    private static String key(Device d) {
        if (d.udn != null && !d.udn.isEmpty()) return d.udn;
        if (d.controlUrl != null) return d.controlUrl;
        return d.location != null ? d.location : (d.ip + ":" + d.port);
    }

    private static List<Device> snapshot(List<Device> src) {
        synchronized (src) { return new ArrayList<>(src); }
    }

    public static Device parse(String location) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(location);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "ETCASCast/1.4");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            Device d = new Device();
            d.location = location;
            String model = "";
            String deviceType = "";
            String lastServiceType = null;
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                f.setNamespaceAware(true);
                var doc = f.newDocumentBuilder().parse(in);
                var nodes = doc.getElementsByTagName("*");
                for (int i = 0; i < nodes.getLength(); i++) {
                    var el = nodes.item(i);
                    String tag = el.getLocalName() != null ? el.getLocalName() : el.getNodeName();
                    String val = el.getTextContent() == null ? "" : el.getTextContent().trim();
                    if ("friendlyName".equals(tag) && d.name == null) d.name = val;
                    else if ("deviceType".equals(tag) && deviceType.isEmpty()) deviceType = val;
                    else if ("modelName".equals(tag) && model.isEmpty()) model = val;
                    else if ("UDN".equals(tag) && d.udn == null) d.udn = val;
                    else if ("serviceType".equals(tag)) lastServiceType = val;
                    else if ("controlURL".equals(tag)) {
                        String ctrl = resolve(location, val);
                        if (lastServiceType != null && lastServiceType.contains("AVTransport") && d.controlUrl == null) d.controlUrl = ctrl;
                        else if (lastServiceType != null && lastServiceType.contains("RenderingControl") && d.volumeControlUrl == null) d.volumeControlUrl = ctrl;
                    } else if ("key".equals(tag) && (d.key == null || d.key.isEmpty()) && val.length() <= 8) d.key = val;
                }
            } catch (Exception ex) { return null; }
            URL u = new URL(location);
            d.ip = u.getHost();
            d.port = u.getPort() > 0 ? u.getPort() : 80;
            if (d.name == null || d.name.isEmpty()) d.name = d.ip;
            d.type = classify(model, d.name);
            d.etcas = d.type.contains("ETCAS") || (d.udn != null && d.udn.toLowerCase().contains("etcas"))
                    || model.toLowerCase().contains("etcas");
            if (d.controlUrl == null) return null;
            return d;
        } catch (Exception e) { return null; } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String resolve(String base, String url) {
        try { return new URL(new URL(base), url).toString(); } catch (Exception e) { return url; }
    }

    private static String classify(String model, String name) {
        String t = (model + " " + name).toLowerCase();
        if (t.contains("etcas")) return "ETCAS投屏客户端";
        if (t.contains("bilibili") || t.contains("哔哩") || t.contains("小电视") || t.contains("云视听")) return "哔哩哔哩 · 云视听小电视";
        if (t.contains("youku") || t.contains("酷喵") || t.contains("cibn") || t.contains("优酷")) return "酷喵 · 优酷 TV";
        if (t.contains("mango") || t.contains("芒果") || t.contains("mgtv")) return "芒果 TV 设备";
        if (t.contains("iqiyi") || t.contains("奇异果") || t.contains("爱奇艺")) return "银河奇异果设备";
        if (t.contains("极光") || t.contains("newtv") || t.contains("未来电视")) return "云视听极光设备";
        if (t.contains("lebo") || t.contains("乐播")) return "乐播投屏设备";
        if (t.contains("hisense") || t.contains("海信")) return "海信智能电视";
        if (t.contains("tcl") || t.contains("雷鸟")) return "TCL 智能电视";
        if (t.contains("sony") || t.contains("索尼")) return "索尼智能电视";
        if (t.contains("samsung") || t.contains("三星")) return "三星智能电视";
        if (t.contains("lg") || t.contains("乐金")) return "LG 智能电视";
        if (t.contains("philips") || t.contains("飞利浦")) return "飞利浦智能电视";
        if (t.contains("huawei") || t.contains("华为")) return "华为智慧屏";
        if (t.contains("xiaomi") || t.contains("小米") || t.contains("redmi") || t.contains("mibox")) return "小米智能电视";
        if (t.contains("letv") || t.contains("乐视")) return "乐视超级电视";
        if (t.contains("skyworth") || t.contains("创维")) return "创维智能电视";
        if (t.contains("changhong") || t.contains("长虹")) return "长虹智能电视";
        if (t.contains("haier") || t.contains("海尔")) return "海尔智能电视";
        if (t.contains("konka") || t.contains("康佳")) return "康佳智能电视";
        if (t.contains("chromecast") || t.contains("google tv")) return "Chromecast / Google TV";
        if (t.contains("roku")) return "Roku 设备";
        return "DLNA 投屏设备";
    }

    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String REND = "urn:schemas-upnp-org:service:RenderingControl:1";

    public static boolean cast(Device d, String uri, String meta) {
        if (d == null) return false;
        soap(d.controlUrl, AVT, "Stop", "<InstanceID>0</InstanceID>");
        if (!setUri(d, uri, meta)) return false;
        try { Thread.sleep(150); } catch (Exception ignored) {}
        return play(d);
    }

    public static boolean setUri(Device d, String uri, String meta) {
        String safeUri = uri == null ? "" : uri.replace("&", "&amp;");
        String safeMeta = meta == null ? "" : meta.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return soap(d.controlUrl, AVT, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>" + safeUri + "</CurrentURI><CurrentURIMetaData>" + safeMeta + "</CurrentURIMetaData>");
    }

    public static boolean play(Device d) {
        return soap(d.controlUrl, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static boolean stop(Device d) {
        return soap(d.controlUrl, AVT, "Stop", "<InstanceID>0</InstanceID>");
    }

    public static boolean setSpeed(Device d, float speed) {
        float rate = Math.max(0.25f, Math.min(2.0f, speed));
        if (d.etcas) return etcasPost(d, "/etcas/speed", "rate=" + URLEncoder.encode(String.valueOf(rate), StandardCharsets.UTF_8));
        return soap(d.controlUrl, AVT, "SetPlaySpeed", "<InstanceID>0</InstanceID><Speed>" + rate + "</Speed>");
    }

    public static boolean setQuality(Device d, int quality) {
        if (d == null || !d.etcas) return false;
        String q;
        switch (quality) {
            case 1: q = "hd"; break;
            case 2: q = "sd"; break;
            case 3: q = "smooth"; break;
            default: q = "auto";
        }
        return etcasPost(d, "/etcas/quality", "quality=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
    }

    private static boolean etcasPost(Device d, String path, String body) {
        String host = "http://" + d.ip + ":" + (d.port > 0 ? d.port : 9170);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(host + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            return conn.getResponseCode() == 200;
        } catch (Exception e) { return false; } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean pair(Device d, String key) {
        String host = "http://" + d.ip + ":" + (d.port > 0 ? d.port : 9170);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(host + "/etcas/pair");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "key=" + URLEncoder.encode(key == null ? "" : key, StandardCharsets.UTF_8);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            String resp = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
            return conn.getResponseCode() == 200 && resp.contains("\"ok\":true");
        } catch (Exception e) { return false; } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean soap(String controlUrl, String service, String action, String args) {
        if (controlUrl == null || controlUrl.isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">"
                    + args + "</u:" + action + "></s:Body></s:Envelope>";
            URL url = new URL(controlUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            conn.setRequestProperty("Connection", "close");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                String resp = new String(readAll(in), StandardCharsets.UTF_8);
                if (code == 200) {
                    if (resp.isEmpty()) return true;
                    String low = resp.toLowerCase();
                    if (low.contains("errorcode") || (low.contains("fault") && !low.contains(":response"))) return false;
                    return true;
                }
                return false;
            }
            return code == 200;
        } catch (Exception e) { return false; } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String httpGet(String urlStr, String referer) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            return new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
