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
    public interface Callback {
        void onResult(String tag, String apkUrl);
        void onError(String msg);
    }

    public static String currentVer(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.17";
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
