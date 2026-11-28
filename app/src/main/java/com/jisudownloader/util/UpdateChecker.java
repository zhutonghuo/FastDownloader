package com.jisudownloader.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.jisudownloader.model.Task;
import com.jisudownloader.model.TaskStore;
import com.jisudownloader.service.DownloadService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class UpdateChecker {
    private static final String PREF = "jisu_update";
    private static final String KEY_TAG = "pending_tag";
    private static final String KEY_URL = "pending_url";

    public static void savePending(Activity a, String tag, String url) {
        android.content.SharedPreferences sp = a.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TAG, tag).putString(KEY_URL, url).apply();
    }

    public static String[] takePending(Activity a) {
        android.content.SharedPreferences sp = a.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
        String tag = sp.getString(KEY_TAG, "");
        String url = sp.getString(KEY_URL, "");
        if (tag == null || tag.isEmpty() || url == null || url.isEmpty()) return null;
        sp.edit().remove(KEY_TAG).remove(KEY_URL).apply();
        return new String[]{tag, url};
    }

    public interface Callback {
        void onResult(String tag, String apkUrl);
        void onError(String msg);
    }

    public static String currentVer(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.36";
        }
    }

    private static String safeInt(String s) {
        if (s == null) return "0";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) b.append(c);
            else break;
        }
        return b.length() == 0 ? "0" : b.toString();
    }

    public static int cmpVer(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? Integer.parseInt(safeInt(pa[i])) : 0;
            int y = i < pb.length ? Integer.parseInt(safeInt(pb[i])) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static String strip(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        return s.substring(i);
    }

    public static void check(Activity a, Callback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/zhutonghuo/FastDownloader/releases?per_page=100").openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "JisuDownloader-Update");
                if (c.getResponseCode() != 200) throw new Exception("服务器返回 " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                c.disconnect();
                JSONArray rels = new JSONArray(sb.toString());
                JSONObject best = null;
                String bestTag = "";
                for (int i = 0; i < rels.length(); i++) {
                    JSONObject r = rels.optJSONObject(i);
                    if (r == null || r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                    String t = r.optString("tag_name", "");
                    if (t.isEmpty()) continue;
                    if (best == null || cmpVer(strip(t), strip(bestTag)) > 0) {
                        best = r; bestTag = t;
                    }
                }
                if (best == null) throw new Exception("没有可用版本");
                String apkUrl = null;
                JSONArray assets = best.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject it = assets.optJSONObject(i);
                    if (it != null && it.optString("name", "").toLowerCase().endsWith(".apk")) {
                        apkUrl = it.optString("browser_download_url", "");
                        break;
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) throw new Exception("未找到安装包");
                if (cmpVer(strip(bestTag), currentVer(a)) <= 0) {
                    a.runOnUiThread(() -> cb.onResult(null, null));
                } else {
                    final String tag = bestTag, url = apkUrl;
                    a.runOnUiThread(() -> cb.onResult(tag, url));
                }
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                a.runOnUiThread(() -> cb.onError(msg));
            }
        }, "update-check").start();
    }

    public static void downloadAndInstall(Activity a, String url, TextView progressView) {
        if (progressView != null) progressView.setEnabled(false);
        new Thread(() -> {
            try {
                File dir = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
                File apk = new File(dir, "ZDown.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(120000);
                if (c.getResponseCode() != 200) throw new Exception("下载失败 " + c.getResponseCode());
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     FileOutputStream os = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                c.disconnect();
                a.runOnUiThread(() -> {
                    if (progressView != null) progressView.setEnabled(true);
                    install(a, apk);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                a.runOnUiThread(() -> {
                    if (progressView != null) progressView.setEnabled(true);
                    Toast.makeText(a, "下载失败：" + msg, Toast.LENGTH_SHORT).show();
                });
            }
        }, "update-dl").start();
    }

    /**
     * 以「普通下载任务」的方式开始下载新版本：
     * 任务会出现在主页下载列表里，和用户手动添加的链接一样显示进度、速度、大小、线程数等数值。
     * 下载完成后任务行会出现「安装」按钮，由用户点击安装。
     */
    public static boolean startUpdateDownload(Activity a, String tag, String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(a, "下载地址无效", Toast.LENGTH_SHORT).show();
            return false;
        }
        url = url.trim();
        // 同一个安装包不要重复入队
        for (Task old : TaskStore.all()) {
            if (old.source != null && old.source.equals(url) && old.status != Task.ERROR) {
                Toast.makeText(a, "该版本已在下载列表中", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        try {
            Task t = Task.fromSource(url, Prefs.threads());
            String ver = (tag == null ? "" : tag).replace("v", "").trim();
            t.fileName = "极速下载器_" + (ver.isEmpty() ? "new" : ver) + ".apk";
            t.displayName = "极速下载器 " + (tag == null ? "新版本" : tag) + " 安装包";
            t.dirPath = updateDir(a).getAbsolutePath();
            t.targetTreeUri = "";
            t.total = -1;
            t.downloaded = 0;
            t.status = Task.QUEUED;
            TaskStore.add(t);
            DownloadService.pump(a.getApplicationContext());
            Toast.makeText(a, "已加入下载列表：" + t.displayName, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(a, "无法开始下载：" + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** 安装包存放目录（应用专属外部目录，无需存储权限）。 */
    public static File updateDir(Activity a) {
        File base = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(base != null ? base : a.getFilesDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 找到已完成的 .apk 任务对应的安装包文件。 */
    public static File apkFileOf(Task t) {
        if (t == null || t.fileName == null) return null;
        File f = t.finalFile();
        if (f != null && f.exists()) return f;
        File alt = new File(updateDirFrom(t), t.fileName);
        if (alt.exists()) return alt;
        return null;
    }

    private static File updateDirFrom(Task t) {
        File d = new File(t.dirPath);
        return d;
    }

    public static void install(Activity a, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(a, "com.jisudownloader.fileprovider", apk);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.getPackageManager().canRequestPackageInstalls()) {
                Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
                a.startActivity(perm);
                Toast.makeText(a, "请允许安装未知应用后重试", Toast.LENGTH_LONG).show();
                return;
            }
            a.startActivity(it);
        } catch (Exception e) {
            Toast.makeText(a, "安装失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
