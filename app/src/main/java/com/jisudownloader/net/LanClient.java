package com.jisudownloader.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class LanClient {

    public interface Progress {
        void onProgress(long sent, long total);
    }

    public static final class Result {
        public boolean ok;
        public String msg = "";
        public JSONArray files = new JSONArray();
        public JSONArray peers = new JSONArray();
        public JSONArray msgs = new JSONArray();
        public String ip = "";
        public int port;
    }

    public static String normalize(String addr) {
        String a = addr == null ? "" : addr.trim();
        if (a.isEmpty()) return "";
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        while (a.endsWith("/") && a.length() > 8) a = a.substring(0, a.length() - 1);
        return a;
    }

    private static String q(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(LanUtil.enc(kv[i])).append('=').append(LanUtil.enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static HttpURLConnection open(String url, String method, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setUseCaches(false);
        return c;
    }

    private static String text(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    public static Result join(String base, String code, String name) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = open(base + "/api/join?" + q("code", code, "name", name), "GET", 10000);
            int rc = c.getResponseCode();
            JSONObject o = new JSONObject(text(c));
            r.ok = rc == 200 && o.optBoolean("ok");
            r.msg = o.optString("msg", r.ok ? "已加入" : "口令错误");
            r.ip = o.optString("ip", "");
            r.port = o.optInt("port", 0);
        } catch (Exception e) {
            r.ok = false;
            r.msg = "连接失败：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    public static Result list(String base, String code, String name) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = open(base + "/api/list?" + q("code", code, "name", name), "GET", 10000);
            int rc = c.getResponseCode();
            JSONObject o = new JSONObject(text(c));
            r.ok = rc == 200 && o.optBoolean("ok");
            if (r.ok) {
                r.files = o.optJSONArray("files") == null ? new JSONArray() : o.optJSONArray("files");
                r.peers = o.optJSONArray("peers") == null ? new JSONArray() : o.optJSONArray("peers");
            } else {
                r.msg = o.optString("msg", "口令失效");
            }
        } catch (Exception e) {
            r.ok = false;
            r.msg = "断开了：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    public static Result chat(String base, String code, String me, String name) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = open(base + "/api/chat?" + q("code", code, "name", name, "me", me), "GET", 10000);
            int rc = c.getResponseCode();
            JSONObject o = new JSONObject(text(c));
            r.ok = rc == 200 && o.optBoolean("ok");
            if (r.ok) {
                r.msgs = o.optJSONArray("msgs") == null ? new JSONArray() : o.optJSONArray("msgs");
            } else {
                r.msg = o.optString("msg", "口令失效");
            }
        } catch (Exception e) {
            r.ok = false;
            r.msg = "断开了：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    public static Result sendText(String base, String code, String me, String name, String text) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            String url = base + "/api/chat?" + q("code", code, "me", me, "name", name);
            c = open(url, "POST", 10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("text=" + LanUtil.enc(text)).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            JSONObject o = new JSONObject(text(c));
            r.ok = c.getResponseCode() == 200 && o.optBoolean("ok");
            r.msg = o.optString("msg", r.ok ? "已发送" : "发送失败");
        } catch (Exception e) {
            r.ok = false;
            r.msg = "发送失败：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    public static Result upload(String base, String code, String me, String name, String fileName,
                               InputStream src, long size, Progress p) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            String url = base + "/api/upload?" + q("code", code, "me", me, "name", name, "filename", fileName);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (size > 0) {
                c.setFixedLengthStreamingMode(size);
            } else {
                c.setChunkedStreamingMode(65536);
            }
            try (OutputStream os = c.getOutputStream(); InputStream is = src) {
                byte[] buf = new byte[65536];
                int n;
                long sent = 0;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    sent += n;
                    if (p != null) p.onProgress(sent, size);
                }
            }
            JSONObject o = new JSONObject(text(c));
            r.ok = c.getResponseCode() == 200 && o.optBoolean("ok");
            r.msg = o.optString("msg", r.ok ? "已上传" : "上传失败");
        } catch (Exception e) {
            r.ok = false;
            r.msg = "上传失败：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    public static Result recall(String base, String code, String me, String id) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            String url = base + "/api/recall?" + q("code", code);
            c = open(url, "POST", 10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("me=" + LanUtil.enc(me) + "&id=" + LanUtil.enc(id)).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            JSONObject o = new JSONObject(text(c));
            r.ok = c.getResponseCode() == 200 && o.optBoolean("ok");
            r.msg = o.optString("msg", r.ok ? "已撤回" : "撤回失败");
        } catch (Exception e) {
            r.ok = false;
            r.msg = "撤回失败：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    public static Result remove(String base, String code, String id) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            c = open(base + "/api/delete?" + q("code", code, "id", id), "POST", 10000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            JSONObject o = new JSONObject(text(c));
            r.ok = c.getResponseCode() == 200 && o.optBoolean("ok");
            r.msg = o.optString("msg", r.ok ? "已移除" : "移除失败");
        } catch (Exception e) {
            r.ok = false;
            r.msg = "移除失败：" + shortMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String shortMsg(Exception e) {
        String s = e.getMessage();
        if (s == null || s.isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
