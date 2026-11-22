package com.jisudownloader.model;

import org.json.JSONObject;

public class HistoryRecord {

    public String id;
    public String source;
    public String fileName;
    public long size;
    public long durationMs;
    public double avgSpeed;
    public long finishedAt;
    public String path;

    public HistoryRecord() {
    }

    public static HistoryRecord fromTask(Task t, long durationMs, double avgSpeed) {
        HistoryRecord r = new HistoryRecord();
        r.id = t.id;
        r.source = t.source;
        r.fileName = (t.fileName != null && !t.fileName.isEmpty()) ? t.fileName : t.displayName;
        r.size = t.total > 0 ? t.total : t.downloaded;
        r.durationMs = durationMs;
        r.avgSpeed = avgSpeed;
        r.finishedAt = System.currentTimeMillis();
        r.path = t.finalFile().getAbsolutePath();
        return r;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("source", source);
        o.put("fileName", fileName);
        o.put("size", size);
        o.put("durationMs", durationMs);
        o.put("avgSpeed", avgSpeed);
        o.put("finishedAt", finishedAt);
        o.put("path", path);
        return o;
    }

    public static HistoryRecord fromJson(JSONObject o) {
        HistoryRecord r = new HistoryRecord();
        r.id = o.optString("id");
        r.source = o.optString("source");
        r.fileName = o.optString("fileName");
        r.size = o.optLong("size");
        r.durationMs = o.optLong("durationMs");
        r.avgSpeed = o.optDouble("avgSpeed");
        r.finishedAt = o.optLong("finishedAt");
        r.path = o.optString("path");
        return r;
    }
}
