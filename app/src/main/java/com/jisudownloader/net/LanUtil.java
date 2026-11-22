package com.jisudownloader.net;

import android.webkit.MimeTypeMap;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Random;

public final class LanUtil {

    private static final Random RND = new Random();

    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public static int pickPort() {
        for (int i = 0; i < 120; i++) {
            int p = 20000 + RND.nextInt(40000);
            ServerSocket s = null;
            try {
                s = new ServerSocket(p);
                return p;
            } catch (Exception ignored) {
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    public static String randomCode(int len, int port) {
        String c = "";
        for (int guard = 0; guard < 300; guard++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append(RND.nextInt(10));
            c = sb.toString();
            if (c.charAt(0) == '0') continue;
            if (c.equals(String.valueOf(port))) continue;
            boolean same = true;
            for (int i = 1; i < c.length(); i++) {
                if (c.charAt(i) != c.charAt(0)) { same = false; break; }
            }
            if (same) continue;
            return c;
        }
        return c;
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    public static String dec(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    public static String mime(String name) {
        int d = name.lastIndexOf('.');
        if (d > 0) {
            String m = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(d + 1).toLowerCase(Locale.US));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    public static String mediaClass(String name) {
        int d = name == null ? -1 : name.lastIndexOf('.');
        if (d < 0) return "other";
        String e = name.substring(d + 1).toLowerCase(Locale.US);
        switch (e) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "webp":
            case "bmp":
            case "heic":
            case "heif":
            case "wbmp":
                return "image";
            case "mp4":
            case "m4v":
            case "mov":
            case "avi":
            case "mkv":
            case "webm":
            case "3gp":
            case "3gpp":
            case "flv":
            case "wmv":
                return "video";
            case "mp3":
            case "wav":
            case "ogg":
            case "oga":
            case "m4a":
            case "aac":
            case "flac":
            case "wma":
            case "amr":
            case "opus":
                return "audio";
            default:
                return "other";
        }
    }

    public static boolean isPreviewable(String name) {
        return !"other".equals(mediaClass(name));
    }
}
