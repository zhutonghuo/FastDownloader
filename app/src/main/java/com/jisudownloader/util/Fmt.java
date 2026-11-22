package com.jisudownloader.util;

import java.util.Locale;

public final class Fmt {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    public static String size(double bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return String.format(Locale.US, "%.0f B", bytes);
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < UNITS.length - 1) {
            v /= 1024;
            i++;
        }
        if (v >= 100) return String.format(Locale.US, "%.0f %s", v, UNITS[i]);
        if (v >= 10) return String.format(Locale.US, "%.1f %s", v, UNITS[i]);
        return String.format(Locale.US, "%.2f %s", v, UNITS[i]);
    }

    public static String speed(double bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        return size(bytesPerSec) + "/s";
    }

    public static String eta(long remainBytes, double bytesPerSec) {
        if (remainBytes <= 0) return "0秒";
        if (bytesPerSec <= 1) return "估算中";
        long sec = (long) (remainBytes / bytesPerSec);
        if (sec > 86400 * 30) return "很久";
        if (sec >= 3600) {
            long h = sec / 3600;
            long m = (sec % 3600) / 60;
            return h + "小时" + (m > 0 ? m + "分" : "");
        }
        if (sec >= 60) {
            long m = sec / 60;
            long s = sec % 60;
            return m + "分" + (s > 0 ? s + "秒" : "");
        }
        return sec + "秒";
    }

    public static String percent(long done, long total) {
        if (total <= 0) return "0.00%";
        double p = done * 100.0 / total;
        return String.format(Locale.US, "%.2f%%", p);
    }

    public static String time(long millis) {
        if (millis <= 0) return "";
        long s = millis / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    public static String duration(long ms) {
        if (ms <= 0) return "0秒";
        long sec = ms / 1000;
        if (sec < 60) return sec + "秒";
        long m = sec / 60;
        long s = sec % 60;
        if (m < 60) return m + "分" + (s > 0 ? s + "秒" : "");
        long h = m / 60;
        long mm = m % 60;
        return h + "时" + (mm > 0 ? mm + "分" : "");
    }

    public static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "download";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (s.length() > 120) s = s.substring(0, 120);
        if (s.startsWith(".")) s = "_" + s.substring(1);
        return s;
    }
}