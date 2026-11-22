package com.jisudownloader.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import com.jisudownloader.model.Task;
import com.jisudownloader.model.TaskStore;
import com.jisudownloader.model.HistoryRecord;
import com.jisudownloader.model.HistoryStore;
import com.jisudownloader.net.HttpEngine;
import com.jisudownloader.net.TorrentEngine;
import com.jisudownloader.ui.MainActivity;
import com.jisudownloader.util.Fmt;
import com.jisudownloader.util.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.webkit.MimeTypeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadService extends Service {

    public static final String CH_ID = "jisu_dl";
    private static final int NOTIF_ID = 1001;

    private static final Map<String, Object> engines = new HashMap<>();
    private static final AtomicBoolean pumping = new AtomicBoolean(false);
    private static ExecutorService pool;
    private static volatile DownloadService inst;
    private Runnable ticker;

    public static void pump(Context c) {
        if (pumping.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    pumpLoop(c.getApplicationContext());
                } finally {
                    pumping.set(false);
                }
            }, "jisu-pump").start();
        }
    }

    private static void pumpLoop(Context ctx) {
        List<Task> all = TaskStore.all();
        List<String> ids = new ArrayList<>();
        for (Task t : all) {
            if (t.isActive()) {
                synchronized (engines) {
                    if (!engines.containsKey(t.id)) ids.add(t.id);
                }
            }
        }
        int max = Prefs.maxTasks();
        for (String id : ids) {
            if (TaskStore.countRunning() >= max) break;
            startTask(ctx, TaskStore.byId(id));
        }
    }

    public static void start(Context ctx, Task t) {
        pump(ctx);
    }

    private static void startTask(Context ctx, Task t) {
        if (t == null) return;
        synchronized (engines) {
            if (engines.containsKey(t.id)) return;
        }
        if (pool == null || pool.isShutdown()) pool = Executors.newCachedThreadPool();
        Task task = t;
        engines.put(task.id, Boolean.TRUE);
        if (!Prefs.autoStart()) {
            task.status = Task.PAUSED;
            synchronized (engines) {
                engines.remove(task.id);
            }
            TaskStore.save();
            TaskStore.notifyChanged();
            return;
        }
        pool.execute(() -> run(ctx, task));
    }

    private static void run(Context ctx, Task task) {
        task.status = Task.CONNECTING;
        task.error = "";
        TaskStore.save();
        TaskStore.notifyChanged();

        final long[] lastSave = {0};
        final Object saveLock = new Object();

        HttpEngine.Progress hcb = new HttpEngine.Progress() {
            @Override
            public void onSpeed(double bps) {
                task.speed = bps;
            }

            @Override
            public void onProgress(long downloaded, long total) {
                task.downloaded = downloaded;
                if (task.total != total && total > 0) task.total = total;
            }

            @Override
            public void onThreads(int n) {
                task.threads = n;
            }
        };

        TorrentEngine.Progress tcb = new TorrentEngine.Progress() {
            @Override
            public void onSpeed(double bps) {
                task.speed = bps;
            }

            @Override
            public void onProgress(long downloaded, long total) {
                task.downloaded = downloaded;
                if (task.total != total && total > 0) task.total = total;
            }

            @Override
            public void onThreads(int n) {
                task.threads = n;
            }

            @Override
            public void onStage(String stage) {
                task.error = "";
            }
        };

        boolean ok = false;
        long startedAt = System.currentTimeMillis();
        try {
            task.status = Task.RUNNING;
            TaskStore.save();
            TaskStore.notifyChanged();
            if (task.magnet) {
                TorrentEngine e = new TorrentEngine(ctx, task, tcb);
                synchronized (engines) {
                    engines.put(task.id, e);
                }
                e.run();
                ok = true;
            } else {
                HttpEngine e = new HttpEngine(ctx, task, hcb);
                synchronized (engines) {
                    engines.put(task.id, e);
                }
                e.run();
                ok = true;
            }
            task.status = Task.DONE;
            task.error = "";
            if (ok && task.targetTreeUri != null && !task.targetTreeUri.isEmpty()) {
                exportToTree(ctx, task);
            }
            if (ok) {
                long dur = System.currentTimeMillis() - startedAt;
                if (dur < 1) dur = 1;
                double av = task.total > 0 ? task.total / (dur / 1000.0) : 0;
                HistoryStore.add(HistoryRecord.fromTask(task, dur, av));
            }
        } catch (InterruptedException ie) {
            task.status = Task.PAUSED;
            task.error = "";
        } catch (Throwable e) {
            task.status = Task.ERROR;
            task.error = HttpEngine.msg(e);
        } finally {
            synchronized (engines) {
                engines.remove(task.id);
            }
            task.speed = 0;
            TaskStore.save();
            TaskStore.notifyChanged();
            pump(ctx);
        }
    }

    // 把缓存工作目录里的内容拷贝到用户用 SAF 选择的文件夹，然后清理缓存
    private static void exportToTree(Context ctx, Task task) {
        try {
            Uri tree = Uri.parse(task.targetTreeUri);
            DocumentFile dest = DocumentFile.fromTreeUri(ctx, tree);
            if (dest == null || !dest.canWrite()) return;
            File src = new File(task.dirPath);
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) copyToTree(ctx, f, dest);
            }
            deleteRecursively(src);
        } catch (Exception ignored) {
            // 拷贝失败不影响“已完成”状态，文件仍留在应用缓存中
        }
    }

    private static void copyToTree(Context ctx, File src, DocumentFile destDir) throws IOException {
        if (src.isDirectory()) {
            DocumentFile sub = destDir.findFile(src.getName());
            if (sub == null || !sub.isDirectory()) {
                sub = destDir.createDirectory(src.getName());
            }
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) copyToTree(ctx, k, sub);
            }
        } else {
            String name = src.getName();
            DocumentFile exist = destDir.findFile(name);
            if (exist != null && exist.exists()) exist.delete();
            DocumentFile out = destDir.createFile(guessMime(name), name);
            try (InputStream in = new FileInputStream(src);
                 OutputStream os = ctx.getContentResolver().openOutputStream(out.getUri())) {
                if (os == null) throw new IOException("无法写入目标文件夹");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
    }

    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }

    public static void pauseTask(String id) {
        synchronized (engines) {
            Object o = engines.get(id);
            if (o instanceof HttpEngine) {
                ((HttpEngine) o).pause();
            } else if (o instanceof TorrentEngine) {
                ((TorrentEngine) o).pause();
            }
        }
        Task t = TaskStore.byId(id);
        if (t != null && (t.status == Task.RUNNING || t.status == Task.CONNECTING || t.status == Task.QUEUED)) {
            t.status = Task.PAUSED;
            t.speed = 0;
            TaskStore.save();
            TaskStore.notifyChanged();
        }
    }

    public static void resumeTask(Context ctx, String id) {
        Task t = TaskStore.byId(id);
        if (t == null) return;
        t.status = Task.QUEUED;
        t.error = "";
        t.threads = Prefs.threads();
        TaskStore.save();
        TaskStore.notifyChanged();
        pump(ctx);
    }

    public static void deleteTask(Context ctx, String id) {
        synchronized (engines) {
            Object o = engines.get(id);
            if (o instanceof HttpEngine) {
                ((HttpEngine) o).abort();
            } else if (o instanceof TorrentEngine) {
                ((TorrentEngine) o).abort();
            }
            engines.remove(id);
        }
        Task t = TaskStore.byId(id);
        if (t != null) {
            java.io.File f = t.partFile();
            if (f.exists()) f.delete();
            java.io.File s = t.stateFile();
            if (s.exists()) s.delete();
            java.io.File b = new java.io.File(t.dirPath, t.fileName + ".jisubm");
            if (b.exists()) b.delete();
            java.io.File j = new java.io.File(t.dirPath, t.fileName + ".jtorrent");
            if (j.exists()) j.delete();
        }
        TaskStore.remove(id);
        TaskStore.notifyChanged();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotif(null));
        ticker = new Runnable() {
            @Override
            public void run() {
                if (inst == null) return;
                Task active = null;
                for (Task t : TaskStore.all()) {
                    if (t.status == Task.RUNNING || t.status == Task.CONNECTING) {
                        active = t;
                        break;
                    }
                }
                Notification n = buildNotif(active);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, n);
                TaskStore.notifyChanged();
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(this, 700);
            }
        };
        new android.os.Handler(android.os.Looper.getMainLooper()).post(ticker);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "下载任务", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif(Task t) {
        String title = "极速下载器";
        String text = "空闲中";
        int progress = 0;
        if (t != null) {
            title = t.displayName;
            progress = (int) (t.progress() * 100);
            text = t.status == Task.CONNECTING ? "解析中…"
                    : Fmt.percent(t.downloaded, t.total) + " · " + Fmt.speed(t.speed)
                    + " · 剩余 " + Fmt.eta(t.remain(), t.speed);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(t != null)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (t != null && t.total > 0) b.setProgress(100, progress, false);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (a.equals("pause") && intent.getStringExtra("id") != null) {
                pauseTask(intent.getStringExtra("id"));
            } else if (a.equals("resume") && intent.getStringExtra("id") != null) {
                resumeTask(this, intent.getStringExtra("id"));
            } else if (a.equals("delete") && intent.getStringExtra("id") != null) {
                deleteTask(this, intent.getStringExtra("id"));
            } else {
                pump(this);
            }
        } else {
            pump(this);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}