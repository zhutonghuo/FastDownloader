package com.jisudownloader.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class Task {

    public static final int QUEUED = 0;
    public static final int RUNNING = 1;
    public static final int PAUSED = 2;
    public static final int DONE = 3;
    public static final int ERROR = 4;
    public static final int CONNECTING = 5;

    public String id;
    public String source;
    public boolean magnet;
    public String displayName;
    public String dirPath;
    public String targetTreeUri = "";
    public String fileName;
    public long total = -1;
    public long downloaded;
    public int status = QUEUED;
    public int threads;
    public String error = "";
    public long createdAt;
    public String etag = "";
    public java.util.Map<String, String> headers;
    public long lastModified = 0;
    public int pieceCount;
    public int pieceLength;

    public Task() {
    }

    public static Task fromSource(String src, int threads) {
        Task t = new Task();
        t.source = src == null ? "" : src.trim();
        t.magnet = Magnet.isMagnet(t.source);
        t.threads = threads;
        t.createdAt = System.currentTimeMillis();
        t.id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        if (t.magnet) {
            t.displayName = Magnet.displayName(t.source);
        } else {
            t.fileName = FmtNames.fromUrl(t.source);
            t.displayName = t.fileName;
        }
        return t;
    }

    public double speed;

    public boolean isActive() {
        return status == RUNNING || status == CONNECTING || status == QUEUED;
    }

    public boolean isFinished() {
        return status == DONE;
    }

    public String stateText() {
        switch (status) {
            case QUEUED:
                return "排队中";
            case RUNNING:
                return "下载中";
            case CONNECTING:
                return "解析中";
            case PAUSED:
                return "已暂停";
            case DONE:
                return "已完成";
            default:
                return "失败";
        }
    }

    public long remain() {
        if (total <= 0) return 0;
        return Math.max(0, total - downloaded);
    }

    public float progress() {
        if (total <= 0) return status == DONE ? 1f : 0f;
        float p = (float) ((double) downloaded / total);
        if (p > 1f) p = 1f;
        return p;
    }

    public File partFile() {
        return new File(dirPath, fileName + ".jisu");
    }

    public File finalFile() {
        return new File(dirPath, fileName);
    }

    public File stateFile() {
        return new File(dirPath, fileName + ".jisustate");
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("source", source);
        o.put("magnet", magnet);
        o.put("displayName", displayName);
        o.put("dirPath", dirPath);
        o.put("targetTreeUri", targetTreeUri == null ? "" : targetTreeUri);
        o.put("fileName", fileName);
        o.put("total", total);
        o.put("downloaded", downloaded);
        o.put("status", status);
        o.put("threads", threads);
        o.put("error", error);
        o.put("createdAt", createdAt);
        o.put("etag", etag);
        o.put("lastModified", lastModified);
        o.put("pieceCount", pieceCount);
        o.put("pieceLength", pieceLength);
        return o;
    }

    public static Task fromJson(JSONObject o) {
        Task t = new Task();
        t.id = o.optString("id");
        t.source = o.optString("source");
        t.magnet = o.optBoolean("magnet");
        t.displayName = o.optString("displayName");
        t.dirPath = o.optString("dirPath");
        t.targetTreeUri = o.optString("targetTreeUri", "");
        t.fileName = o.optString("fileName");
        t.total = o.optLong("total", -1);
        t.downloaded = o.optLong("downloaded");
        t.status = o.optInt("status", QUEUED);
        t.threads = o.optInt("threads", 16);
        t.error = o.optString("error", "");
        t.createdAt = o.optLong("createdAt");
        t.etag = o.optString("etag", "");
        t.lastModified = o.optLong("lastModified");
        t.pieceCount = o.optInt("pieceCount");
        t.pieceLength = o.optInt("pieceLength");
        return t;
    }

    static final class FmtNames {
        static String fromUrl(String url) {
            String s = url;
            int q = s.indexOf('?');
            if (q > 0) s = s.substring(0, q);
            int h = s.indexOf('#');
            if (h > 0) s = s.substring(0, h);
            int slash = s.lastIndexOf('/');
            String name = slash >= 0 ? s.substring(slash + 1) : s;
            if (name.isEmpty()) name = "index.html";
            try {
                name = java.net.URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 32 || c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"'
                        || c == '<' || c == '>' || c == '|') {
                    sb.append('_');
                } else {
                    sb.append(c);
                }
            }
            String out = sb.toString().trim();
            if (out.length() > 100) out = out.substring(0, 100);
            if (out.isEmpty()) out = "download";
            return out;
        }
    }
}