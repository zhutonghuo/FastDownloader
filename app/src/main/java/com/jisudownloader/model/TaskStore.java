package com.jisudownloader.model;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TaskStore {

    public interface Listener {
        void onChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Object LOCK = new Object();
    private static final ArrayList<Task> tasks = new ArrayList<>();
    private static File storeFile;

    public static void init(Context c) {
        storeFile = new File(c.getFilesDir(), "tasks.json");
        synchronized (LOCK) {
            tasks.clear();
            try {
                if (storeFile.exists()) {
                    String txt = new String(java.nio.file.Files.readAllBytes(storeFile.toPath()), "UTF-8");
                    JSONArray arr = new JSONArray(txt);
                    for (int i = 0; i < arr.length(); i++) {
                        Task t = Task.fromJson(arr.getJSONObject(i));
                        if (t.status == Task.RUNNING || t.status == Task.CONNECTING || t.status == Task.QUEUED) {
                            t.status = Task.PAUSED;
                        }
                        tasks.add(t);
                    }
                }
            } catch (Exception e) {
                tasks.clear();
            }
        }
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static void notifyChanged() {
        for (Listener l : listeners) {
            l.onChanged();
        }
    }

    public static List<Task> all() {
        synchronized (LOCK) {
            return new ArrayList<>(tasks);
        }
    }

    public static Task byId(String id) {
        synchronized (LOCK) {
            for (Task t : tasks) {
                if (t.id.equals(id)) return t;
            }
        }
        return null;
    }

    public static void add(Task t) {
        synchronized (LOCK) {
            tasks.add(0, t);
        }
        save();
    }

    public static void remove(String id) {
        synchronized (LOCK) {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id.equals(id)) {
                    tasks.remove(i);
                    break;
                }
            }
        }
        save();
    }

    public static void clearFinished() {
        synchronized (LOCK) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i).status == Task.DONE) tasks.remove(i);
            }
        }
        save();
    }

    public static int countRunning() {
        int n = 0;
        for (Task t : all()) {
            if (t.status == Task.RUNNING || t.status == Task.CONNECTING) n++;
        }
        return n;
    }

    public static void save() {
        if (storeFile == null) return;
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray();
                for (Task t : tasks) {
                    arr.put(t.toJson());
                }
                File tmp = new File(storeFile.getParentFile(), "tasks.json.tmp");
                java.nio.file.Files.write(tmp.toPath(), arr.toString().getBytes("UTF-8"));
                if (storeFile.exists()) storeFile.delete();
                tmp.renameTo(storeFile);
            } catch (Exception ignored) {
            }
        }
    }
}