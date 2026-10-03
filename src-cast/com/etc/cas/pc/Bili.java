package com.etc.cas.pc;

public class Bili {

    public static String resolve(String url) {
        try {
            if (!url.contains("bilibili.com")) return url;
            String bv = null;
            int i = url.indexOf("/video/");
            if (i > 0) {
                int j = i + 7;
                int k = j;
                while (k < url.length() && (Character.isLetterOrDigit(url.charAt(k)) || url.charAt(k) == '_')) k++;
                bv = url.substring(j, k);
            }
            if (bv == null || bv.isEmpty()) return url;
            String view = Net.httpGet("https://api.bilibili.com/x/web-interface/view?bvid=" + bv, "https://www.bilibili.com");
            if (view == null) return url;
            int ci = view.indexOf("\"cid\":");
            if (ci < 0) return url;
            String cid = view.substring(ci + 6);
            int ce = 0;
            while (ce < cid.length() && Character.isDigit(cid.charAt(ce))) ce++;
            cid = cid.substring(0, ce);
            if (cid.isEmpty()) return url;
            String play = Net.httpGet("https://api.bilibili.com/x/player/playurl?bvid=" + bv + "&cid=" + cid
                    + "&qn=127&fnval=16&platform=pc&high_quality=1", "https://www.bilibili.com");
            if (play == null) return url;
            int qi = play.indexOf("\"url\":\"");
            if (qi < 0) {
                qi = play.indexOf("\"baseUrl\":\"");
                if (qi < 0) return url;
                qi += 11;
            } else {
                qi += 7;
            }
            int qe = qi;
            while (qe < play.length() && play.charAt(qe) != '"') qe++;
            String direct = play.substring(qi, qe);
            direct = direct.replace("\\u0026", "&").replace("\\/", "/").replace("\\\\", "\\");
            return direct.isEmpty() ? url : direct;
        } catch (Exception e) {
            return url;
        }
    }
}
