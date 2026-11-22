package com.jisudownloader.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    private static final String NAME = "jisu_prefs";
    private static SharedPreferences sp;

    public static final String K_THREADS = "threads";
    public static final String K_THEME = "theme";
    public static final String K_PATH = "download_path";
    public static final String K_PATH_URI = "download_path_tree";
    public static final String K_AUTO_START = "auto_start";
    public static final String K_NOTIFY = "notify";
    public static final String K_USER_AGENT = "user_agent";
    public static final String K_MAX_TASKS = "max_tasks";
    public static final String K_RETRY = "retry";

    public static final int DEF_THREADS = 16;
    public static final int MAX_THREADS = 32;
    public static final int MIN_THREADS = 1;

    public static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences s() {
        if (sp == null) throw new IllegalStateException("Prefs not init");
        return sp;
    }

    public static int threads() {
        return clampThreads(s().getInt(K_THREADS, DEF_THREADS));
    }

    public static void setThreads(int v) {
        s().edit().putInt(K_THREADS, clampThreads(v)).apply();
    }

    public static int clampThreads(int v) {
        return Math.max(MIN_THREADS, Math.min(MAX_THREADS, v));
    }

    public static String theme() {
        return s().getString(K_THEME, "blue");
    }

    public static void setTheme(String v) {
        s().edit().putString(K_THEME, v).apply();
    }

    public static String userAgent() {
        return s().getString(K_USER_AGENT, "Mozilla/5.0 (Linux; Android 13) JisuDownloader/1.0");
    }

    public static void setUserAgent(String v) {
        s().edit().putString(K_USER_AGENT, v).apply();
    }

    public static int retry() {
        return clampRetry(s().getInt(K_RETRY, 3));
    }

    public static void setRetry(int v) {
        s().edit().putInt(K_RETRY, clampRetry(v)).apply();
    }

    private static int clampRetry(int v) {
        return Math.max(1, Math.min(10, v));
    }

    public static boolean notifyEnabled() {
        return s().getBoolean(K_NOTIFY, true);
    }

    public static void setNotify(boolean v) {
        s().edit().putBoolean(K_NOTIFY, v).apply();
    }

    public static int maxTasks() {
        return s().getInt(K_MAX_TASKS, 3);
    }

    public static void setMaxTasks(int v) {
        s().edit().putInt(K_MAX_TASKS, Math.max(1, Math.min(8, v))).apply();
    }

    public static boolean autoStart() {
        return s().getBoolean(K_AUTO_START, true);
    }

    public static void setAutoStart(boolean v) {
        s().edit().putBoolean(K_AUTO_START, v).apply();
    }

    public static String pathTree() {
        return s().getString(K_PATH_URI, "");
    }

    public static void setPathTree(String v) {
        s().edit().putString(K_PATH_URI, v == null ? "" : v).apply();
    }

    public static String path() {
        String v = s().getString(K_PATH, "");
        return v == null ? "" : v;
    }

    public static void setPath(String v) {
        s().edit().putString(K_PATH, v == null ? "" : v).apply();
    }
}