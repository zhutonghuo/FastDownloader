package com.jisudownloader.net;

import android.content.Context;

import com.jisudownloader.model.Task;
import com.jisudownloader.util.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpEngine {

    public interface Progress {
        void onSpeed(double bytesPerSec);
        void onProgress(long downloaded, long total);
        void onThreads(int activeThreads);
    }

    private static final int BUF = 32768;

    private final Task task;
    private final Progress cb;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    private long total = -1;
    private int pieceSize;
    private boolean[] bitmap;
    private File part;
    private File meta;
    private volatile long doneBytes;
    private volatile long lastTickTime;
    private volatile long lastTickBytes;
    private volatile double speed;
    private volatile String error = "";

    public HttpEngine(Context ctx, Task task, Progress cb) {
        this.task = task;
        this.cb = cb;
    }

    public String error() {
        return error;
    }

    public void pause() {
        paused.set(true);
        for (Thread t : workers) t.interrupt();
    }

    public void abort() {
        aborted.set(true);
        pause();
    }

    private void checkStop() throws InterruptedException {
        if (aborted.get()) throw new InterruptedException("abort");
        if (paused.get()) throw new InterruptedException("pause");
    }

    public void run() throws Exception {
        Probe p = probe(task.source);
        if (!p.ok) {
            error = p.error;
            throw new IOException(error);
        }
        total = p.total;
        task.total = total;
        task.etag = p.etag;
        task.lastModified = p.lastModified;

        File dir = new File(task.dirPath);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录: " + task.dirPath);
        part = task.partFile();
        meta = task.stateFile();

        boolean resumable = p.acceptRanges && total > 0;
        if (resumable) {
            if (!loadState()) {
                pieceSize = choosePieceSize(total);
                bitmap = new boolean[pieceCount()];
                doneBytes = 0;
            }
            if (doneBytes >= total) {
                finish();
                return;
            }
            prealloc(total);
            parallel(p.etag, p.lastModified);
        } else {
            single();
        }

        checkStop();
        finish();
    }

    private int choosePieceSize(long total) {
        int threads = Math.max(1, task.threads);
        long target = Math.max(1, total / threads);
        long[] cands = {1L << 19, 1L << 20, 1L << 21, 1L << 22, 1L << 23};
        for (long c : cands) {
            if (c >= target) return (int) c;
        }
        return 1 << 23;
    }

    private int pieceCount() {
        return (int) ((total + pieceSize - 1) / pieceSize);
    }

    private boolean loadState() {
        if (!part.exists() || !meta.exists()) return false;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
            long st = -1;
            int sp = -1;
            for (String x : s.split(";")) {
                String[] p = x.split("=", 2);
                if (p.length != 2) continue;
                if (p[0].equals("total")) st = Long.parseLong(p[1]);
                else if (p[0].equals("ps")) sp = Integer.parseInt(p[1]);
                else if (p[0].equals("etag")) task.etag = p[1];
                else if (p[0].equals("lm")) task.lastModified = Long.parseLong(p[1]);
            }
            if (st <= 0 || sp <= 0 || st != total) return false;
            if (part.length() < total) return false;
            total = st;
            pieceSize = sp;
            bitmap = new boolean[pieceCount()];
            byte[] arr = java.nio.file.Files.readAllBytes(bitmapFile().toPath());
            long acc = 0;
            for (int i = 0; i < bitmap.length; i++) {
                int by = i / 8;
                if (by < arr.length && (arr[by] & (0x80 >> (i % 8))) != 0) {
                    bitmap[i] = true;
                    long start = (long) i * pieceSize;
                    acc += Math.min(total, start + pieceSize) - start;
                }
            }
            doneBytes = acc;
            return acc > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private File bitmapFile() {
        return new File(task.dirPath, task.fileName + ".jisubm");
    }

    private void prealloc(long size) {
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            if (raf.length() != size) raf.setLength(size);
        } catch (IOException ignored) {
        }
        saveState();
    }

    private void saveState() {
        try {
            byte[] arr = new byte[(bitmap.length + 7) / 8];
            for (int i = 0; i < bitmap.length; i++) {
                if (bitmap[i]) arr[i / 8] |= (0x80 >> (i % 8));
            }
            StringBuilder sb = new StringBuilder();
            sb.append("total=").append(total).append(";");
            sb.append("ps=").append(pieceSize).append(";");
            sb.append("etag=").append(task.etag == null ? "" : task.etag).append(";");
            sb.append("lm=").append(task.lastModified).append(";");
            java.nio.file.Files.write(meta.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.write(bitmapFile().toPath(), arr);
        } catch (Exception ignored) {
        }
    }

    private void parallel(String etag, long lm) throws Exception {
        List<Integer> queue = new ArrayList<>();
        for (int i = 0; i < bitmap.length; i++) {
            if (!bitmap[i]) queue.add(i);
        }
        if (queue.isEmpty()) return;
        Collections.shuffle(queue);

        final AtomicInteger cursor = new AtomicInteger(0);
        int n = Math.max(1, Math.min(Math.max(1, task.threads), queue.size()));
        lastTickTime = System.currentTimeMillis();
        lastTickBytes = doneBytes;
        if (cb != null) cb.onThreads(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread th = new Thread(() -> {
                try {
                    while (true) {
                        int p = cursor.getAndIncrement();
                        if (p >= queue.size()) break;
                        checkStop();
                        int piece = queue.get(p);
                        downloadPiece(piece, etag, lm);
                        synchronized (writeLock) {
                            bitmap[piece] = true;
                            saveState();
                        }
                    }
                } catch (InterruptedException e) {
                } catch (Throwable e) {
                    if (aborted.get() || paused.get()) return;
                    synchronized (HttpEngine.class) {
                        if (error.isEmpty()) error = msg(e);
                    }
                }
            }, "jisu-http-" + idx);
            th.setDaemon(true);
            workers.add(th);
            th.start();
        }
        for (Thread t : workers) t.join();

        if (!aborted.get() && !paused.get() && !error.isEmpty()) throw new IOException(error);
    }

    private void downloadPiece(int piece, String etag, long lm) throws Exception {
        long start = (long) piece * pieceSize;
        long end = Math.min(total, start + pieceSize) - 1;
        int maxRetry = Math.max(1, Prefs.retry());
        long w = start;
        IOException last = null;
        for (int attempt = 0; attempt < maxRetry; attempt++) {
            checkStop();
            HttpURLConnection c = null;
            RandomAccessFile raf = null;
            long wrote = 0;
            try {
                c = open(w, end, etag, lm);
                int code = c.getResponseCode();
                if (code == 416) { w = end + 1; break; }
                if (code != 206 && code != 200) throw new IOException("HTTP " + code);
                boolean fullFile = (code == 200);
                try (InputStream in = c.getInputStream()) {
                    raf = new RandomAccessFile(part, "rw");
                    byte[] buf = new byte[BUF];
                    long pos = w;
                    if (fullFile) {
                        long skip = w;
                        while (skip > 0) {
                            long s = in.skip(skip);
                            if (s > 0) { skip -= s; continue; }
                            if (in.read() < 0) throw new IOException("分片不完整");
                            skip--;
                        }
                        pos = w;
                    }
                    while (pos <= end) {
                        checkStop();
                        int want = (int) Math.min(BUF, end - pos + 1);
                        int r = in.read(buf, 0, want);
                        if (r <= 0) break;
                        synchronized (writeLock) {
                            raf.seek(pos);
                            raf.write(buf, 0, r);
                        }
                        pos += r;
                        wrote += r;
                        w = pos;
                        doneBytes += r;
                        tick();
                    }
                    if (pos > end) { w = end + 1; break; }
                    last = new IOException("分片不完整");
                }
            } catch (IOException e) {
                last = e;
            } finally {
                if (raf != null) try { raf.close(); } catch (IOException ignored) { }
                if (c != null) c.disconnect();
            }
            doneBytes -= wrote;
            Thread.sleep(300L * (attempt + 1));
        }
        if (w > end) return;
        throw last == null ? new IOException("分片失败") : last;
    }

    private void single() throws Exception {
        HttpURLConnection c = null;
        try {
            c = open(-1, -1, null, 0);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long len = c.getContentLengthLong();
            total = len > 0 ? len : 0;
            task.total = total;
            lastTickTime = System.currentTimeMillis();
            lastTickBytes = 0;
            doneBytes = 0;
            byte[] buf = new byte[BUF];
            long w = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(part, false)) {
                while (true) {
                    checkStop();
                    int r = in.read(buf);
                    if (r <= 0) break;
                    fo.write(buf, 0, r);
                    w += r;
                    doneBytes = w;
                    tick();
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long d = now - lastTickTime;
        if (d >= 450) {
            double inst = (doneBytes - lastTickBytes) / (d / 1000.0);
            speed = speed * 0.4 + inst * 0.6;
            lastTickTime = now;
            lastTickBytes = doneBytes;
            if (cb != null) {
                cb.onSpeed(speed);
                cb.onProgress(doneBytes, total);
            }
        }
    }

    private HttpURLConnection open(long start, long end, String etag, long lm) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(task.source).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Prefs.userAgent());
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Connection", "keep-alive");
        if (task.headers != null) {
            for (java.util.Map.Entry<String, String> e : task.headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (start >= 0) {
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-Range", "\"" + etag + "\"");
            else if (lm > 0) c.setRequestProperty("If-Range", httpDate(lm));
        }
        return c;
    }

    public static String msg(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t.getMessage() != null && !t.getMessage().isEmpty()) return t.getMessage();
            t = t.getCause();
        }
        return e.getClass().getSimpleName();
    }

    private static final String[] HTTP_DATE_FMT = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
    };

    static long parseHttpDate(String s) throws Exception {
        for (String f : HTTP_DATE_FMT) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(f, java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            try {
                return fmt.parse(s).getTime();
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("bad date");
    }

    static String httpDate(long millis) {
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return fmt.format(new java.util.Date(millis));
    }

    static final class Probe {
        boolean ok;
        String error = "";
        long total = -1;
        boolean acceptRanges;
        String etag = "";
        long lastModified;
    }

    private Probe probe(String url) {
        Probe p = new Probe();
        HttpURLConnection c = null;
        try {
            c = open(0, 0, null, 0);
            int code = c.getResponseCode();
            String cr = c.getHeaderField("Content-Range");
            String ar = c.getHeaderField("Accept-Ranges");
            String et = c.getHeaderField("ETag");
            if (et != null) p.etag = et.replace("W/", "").replace("\"", "");
            String lm = c.getHeaderField("Last-Modified");
            if (lm != null) {
                try {
                    p.lastModified = parseHttpDate(lm);
                } catch (Exception ignored) {
                }
            }
            if (cr != null && cr.contains("/")) {
                p.total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                p.acceptRanges = true;
            } else if (code == 200) {
                long len = c.getContentLengthLong();
                p.total = len > 0 ? len : -1;
                p.acceptRanges = ar != null && ar.toLowerCase().contains("bytes");
            } else if (code == 206) {
                p.acceptRanges = true;
            } else {
                p.error = "HTTP " + code;
                return p;
            }
            p.ok = true;
        } catch (Exception e) {
            p.error = msg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return p;
    }

    private void finish() throws IOException {
        File out = task.finalFile();
        if (out.exists()) out.delete();
        if (part.exists()) {
            if (!part.renameTo(out)) copyRename(part, out);
        }
        File st = task.stateFile();
        if (st.exists()) st.delete();
        File bm = bitmapFile();
        if (bm.exists()) bm.delete();
        task.downloaded = total > 0 ? total : task.downloaded;
    }

    private void copyRename(File a, File b) throws IOException {
        try (InputStream in = new FileInputStream(a); FileOutputStream fo = new FileOutputStream(b)) {
            byte[] buf = new byte[BUF];
            int r;
            while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
        }
        a.delete();
    }
}