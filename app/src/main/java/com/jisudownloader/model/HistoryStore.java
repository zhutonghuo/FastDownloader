package com.jisudownloader.model;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HistoryStore {

    public interface Listener {
        void onChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Object LOCK = new Object();
    private static final ArrayList<HistoryRecord> recs = new ArrayList<>();
    private static File storeFile;

    public static void init(Context c) {
        storeFile = new File(c.getFilesDir(), "history.json");
        synchronized (LOCK) {
            recs.clear();
            try {
                if (storeFile.exists()) {
                    String txt = new String(java.nio.file.Files.readAllBytes(storeFile.toPath()), "UTF-8");
                    JSONArray arr = new JSONArray(txt);
                    for (int i = 0; i < arr.length(); i++) {
                        recs.add(HistoryRecord.fromJson(arr.getJSONObject(i)));
                    }
                }
            } catch (Exception e) {
                recs.clear();
            }
        }
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged() {
        for (Listener l : listeners) l.onChanged();
    }

    public static List<HistoryRecord> all() {
        synchronized (LOCK) {
            return new ArrayList<>(recs);
        }
    }

    public static int count() {
        synchronized (LOCK) {
            return recs.size();
        }
    }

    public static void add(HistoryRecord r) {
        if (r == null) return;
        synchronized (LOCK) {
            for (int i = recs.size() - 1; i >= 0; i--) {
                if (recs.get(i).source != null && recs.get(i).source.equals(r.source)) {
                    recs.remove(i);
                    break;
                }
            }
            recs.add(0, r);
            while (recs.size() > 500) recs.remove(recs.size() - 1);
        }
        save();
        notifyChanged();
    }

    public static void update(HistoryRecord r) {
        synchronized (LOCK) {
            for (int i = 0; i < recs.size(); i++) {
                if (recs.get(i).id.equals(r.id)) {
                    recs.set(i, r);
                    break;
                }
            }
        }
        save();
        notifyChanged();
    }

    public static void remove(String id) {
        synchronized (LOCK) {
            for (int i = recs.size() - 1; i >= 0; i--) {
                if (recs.get(i).id.equals(id)) {
                    recs.remove(i);
                    break;
                }
            }
        }
        save();
        notifyChanged();
    }

    public static void removeMany(List<String> ids) {
        synchronized (LOCK) {
            for (int i = recs.size() - 1; i >= 0; i--) {
                if (ids.contains(recs.get(i).id)) recs.remove(i);
            }
        }
        save();
        notifyChanged();
    }

    public static void clear() {
        synchronized (LOCK) {
            recs.clear();
        }
        save();
        notifyChanged();
    }

    public static void save() {
        if (storeFile == null) return;
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray();
                for (HistoryRecord r : recs) arr.put(r.toJson());
                File tmp = new File(storeFile.getParentFile(), "history.json.tmp");
                java.nio.file.Files.write(tmp.toPath(), arr.toString().getBytes("UTF-8"));
                if (storeFile.exists()) storeFile.delete();
                tmp.renameTo(storeFile);
            } catch (Exception ignored) {
            }
        }
    }
}
