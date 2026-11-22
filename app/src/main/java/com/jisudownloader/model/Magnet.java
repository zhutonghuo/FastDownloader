package com.jisudownloader.model;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

public final class Magnet {

    public static boolean isMagnet(String s) {
        return s != null && s.toLowerCase().startsWith("magnet:");
    }

    public static boolean isTorrentUrl(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        return l.startsWith("magnet:") || l.endsWith(".torrent");
    }

    public static String displayName(String m) {
        if (m == null) return "磁力任务";
        String dn = param(m, "dn");
        if (dn != null && !dn.isEmpty()) return dn;
        String xt = param(m, "xt");
        if (xt != null && xt.length() > 20) return xt.substring(xt.length() - 12);
        return "磁力任务";
    }

    public static String infoHashHex(String m) {
        String xt = param(m, "xt");
        if (xt == null) return null;
        int idx = xt.toLowerCase().indexOf("urn:btih:");
        if (idx < 0) return null;
        String h = xt.substring(idx + 9).trim();
        return h.isEmpty() ? null : h;
    }

    public static String tracker(String m) {
        String tr = param(m, "tr");
        return (tr == null || tr.isEmpty()) ? null : tr;
    }

    public static String param(String m, String key) {
        if (m == null) return null;
        String pat = key + "=";
        int i = m.indexOf(pat);
        if (i < 0) return null;
        int j = m.indexOf('&', i);
        String v = j < 0 ? m.substring(i + pat.length()) : m.substring(i + pat.length(), j);
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return v;
        } catch (Exception e) {
            return v;
        }
    }

    public static byte[] infoHashBytes(String hex) {
        String h = hex.trim();
        if (h.length() == 40 && isHex(h)) {
            byte[] out = new byte[20];
            for (int i = 0; i < 20; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        if (h.length() == 32 && isBase32(h)) {
            return base32(h);
        }
        return null;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isBase32(String s) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if (A.indexOf(c) < 0) return false;
        }
        return true;
    }

    private static byte[] base32(String s) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int[] rev = new int[256];
        java.util.Arrays.fill(rev, -1);
        for (int i = 0; i < A.length(); i++) rev[A.charAt(i)] = i;
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = rev[Character.toUpperCase(c)];
            if (v < 0) continue;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bo.write((buf >> bits) & 0xFF);
            }
        }
        return bo.toByteArray();
    }
}