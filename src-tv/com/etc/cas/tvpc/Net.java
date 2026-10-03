package com.etc.cas.tvpc;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class Net {

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
                        int a = b[0] & 0xFF;
                        boolean priv = a == 10 || a == 172 || a == 192 || a == 100;
                        if (pref && priv) return ia.getHostAddress();
                        if (priv) return ia.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
