package com.jisudownloader.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 文件类型判定：用于决定能否内置预览、是否可安装。 */
public final class MediaUtil {

    private static final Set<String> IMAGE = set(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico");
    private static final Set<String> VIDEO = set(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts", "rmvb", "rm");
    private static final Set<String> AUDIO = set(
            "mp3", "wav", "aac", "ogg", "m4a", "flac", "amr", "opus", "wma", "aiff");
    private static final Set<String> TEXT = set(
            "txt", "md", "markdown", "json", "xml", "log", "html", "htm", "csv", "tsv",
            "ini", "conf", "cfg", "yaml", "yml", "properties", "srt", "js", "css",
            "java", "py", "c", "cpp", "h", "hpp", "go", "rs", "sh", "bat", "sql", "rtf");

    private static Set<String> set(String... a) {
        return new HashSet<>(Arrays.asList(a));
    }

    private MediaUtil() {
    }

    public static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** 返回 "image" / "video" / "audio" / "text"，不支持返回 null。 */
    public static String classify(String name) {
        String e = ext(name);
        if (e.isEmpty()) return null;
        if (IMAGE.contains(e)) return "image";
        if (VIDEO.contains(e)) return "video";
        if (AUDIO.contains(e)) return "audio";
        if (TEXT.contains(e)) return "text";
        return null;
    }

    public static boolean isApk(String name) {
        return "apk".equals(ext(name));
    }
}
