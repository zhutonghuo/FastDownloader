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

    // ===== 调度参数（与电脑端一致：全程保持高速）=====
    private static final long PIECE_ALIGN = 128L * 1024;   // piece 粒度对齐
    private static final long MIN_PIECE = 128L * 1024;     // 最小 piece
    private static final long MAX_PIECE = 8L * 1024 * 1024;// 最大 piece
    private static final int PIECE_TARGETS = 48;           // 目标 piece 数 ≈ 线程数 × 此值
    private static final int BLOCKS_PER_THREAD = 4;        // block 数 ≈ 线程数 × 此值
    private static final long MIN_BLOCK_BYTES = 256L * 1024;   // 段大小下限（太小反而低效）
    private static final double STEAL_RATIO = 0.35;        // 速率低于全场此比例即被回收
    private static final long STEAL_COOLDOWN_MS = 400;     // 回收冷却
    private static final long RATE_WINDOW_MS = 2000;       // 速率统计窗口

    private final Task task;
    private final Progress cb;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    private long total = -1;
    private int pieceSize;
    private boolean[] bitmap;
    /** 已完成的字节区间（精确断点）—— 字节级调度下 piece 位图不够精确 */
    private final java.util.List<long[]> doneRanges = new ArrayList<>();
    private File part;
    private File meta;
    private volatile long doneBytes;
    // ---- 调度状态 ----
    private final java.util.ArrayList<long[]> pool = new java.util.ArrayList<>(); // 未领取的段 [start,end)
    private final java.util.Map<Integer, Long> doneByConn = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, long[]> rateState = new java.util.concurrent.ConcurrentHashMap<>(); // {窗口起点ms, 窗口起始字节}
    private final java.util.Map<Integer, Double> rateNow = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Random rng = new java.util.Random();
    private final Object poolLock = new Object();
    private final java.util.Map<Integer, long[]> segs = new java.util.concurrent.ConcurrentHashMap<>(); // {start, cur, limit}
    private volatile double aggSpeed = 0;
    private volatile boolean stealEnabled = true;
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

    /**
     * piece 粒度：目标 piece 数 ≈ 线程数 × 48，且 piece 数 ≥ 线程数。
     * piece 越细，尾段越能被所有连接平分 → 进度快满时速度不掉。
     */
    private int choosePieceSize(long total) {
        int threads = Math.max(1, task.threads);
        long target = total / (threads * (long) PIECE_TARGETS);
        if (target <= 0) target = MIN_PIECE;
        long ps = ((target + PIECE_ALIGN - 1) / PIECE_ALIGN) * PIECE_ALIGN;
        if (ps < MIN_PIECE) ps = MIN_PIECE;
        if (ps > MAX_PIECE) ps = MAX_PIECE;
        if (total > 0) {
            long need = (total + threads - 1) / threads;   // ceil，保证每线程至少一片
            if (ps > need) ps = Math.max(1, need);
        }
        return (int) ps;
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
            String ranges = "";
            for (String x : s.split(";")) {
                String[] p = x.split("=", 2);
                if (p.length != 2) continue;
                if (p[0].equals("total")) st = Long.parseLong(p[1]);
                else if (p[0].equals("ps")) sp = Integer.parseInt(p[1]);
                else if (p[0].equals("etag")) task.etag = p[1];
                else if (p[0].equals("lm")) task.lastModified = Long.parseLong(p[1]);
                else if (p[0].equals("ranges")) ranges = p[1];
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
            // 读回精确区间（优先于 piece 位图）
            if (!ranges.isEmpty()) {
                doneRanges.clear();
                for (String seg : ranges.split(",")) {
                    String[] ab = seg.split("-", 2);
                    if (ab.length == 2) {
                        try {
                            doneRanges.add(new long[]{Long.parseLong(ab[0]), Long.parseLong(ab[1])});
                        } catch (NumberFormatException ignored) { }
                    }
                }
                java.util.Collections.sort(doneRanges, (a, b) -> Long.compare(a[0], b[0]));
                java.util.List<long[]> merged = new ArrayList<>();
                for (long[] r : doneRanges) {
                    if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                        if (r[1] > merged.get(merged.size() - 1)[1]) merged.get(merged.size() - 1)[1] = r[1];
                    } else {
                        merged.add(new long[]{r[0], r[1]});
                    }
                }
                doneRanges.clear();
                doneRanges.addAll(merged);
                long acc2 = 0;
                for (long[] r : merged) acc2 += r[1] - r[0];
                doneBytes = acc2;
                return acc2 > 0;
            }
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
            // 精确断点：已完成的字节区间（r=a-b,逗号分隔）
            sb.append("v=3;");
            StringBuilder rg = new StringBuilder();
            for (long[] r : completedRanges()) {
                if (rg.length() > 0) rg.append(',');
                rg.append(r[0]).append('-').append(r[1]);
            }
            sb.append("ranges=").append(rg).append(";");
            java.nio.file.Files.write(meta.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.write(bitmapFile().toPath(), arr);
        } catch (Exception ignored) {
        }
    }

    /**
     * 多线程下载（共享 block 池 + Work Stealing）。
     *
     * 传统做法：文件被均分成 N 段，每线程死守一段 → 快的下完就退出，
     * 最后只剩 1 条连接在跑，速度掉到 1/N（"进度快满时速度像被砍"）。
     *
     * 这里：所有线程从同一个 block 池随机领取；调度器持续监测速率，
     * 慢连接手里未开始的尾部会被回收进池子交给空闲连接 → 尾段也保持满速。
     */
    private void parallel(String etag, long lm) throws Exception {
        buildPool();
        if (pool.isEmpty()) return;

        int n = Math.max(1, Math.min(Math.max(1, task.threads), pool.size()));
        lastTickTime = System.currentTimeMillis();
        lastTickBytes = doneBytes;
        if (cb != null) cb.onThreads(n);

        // 调度器线程：负责 Work Stealing
        Thread sched = new Thread(() -> {
            while (!aborted.get() && !paused.get() && stealEnabled) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                try { refreshRates(); } catch (Throwable ignored) { }
                try { stealWork(); } catch (Throwable ignored) { }
            }
        }, "jisu-sched");
        sched.setDaemon(true);
        sched.start();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread th = new Thread(() -> {
                try {
                    while (true) {
                        long[] blk = claimBlock(idx);
                        if (blk == null) {
                            // 池里可能还有活（自己被降权了）→ 稍等重试
                            synchronized (poolLock) {
                                if (pool.isEmpty()) break;
                            }
                            try { Thread.sleep(50); } catch (InterruptedException ie) { return; }
                            continue;
                        }
                        checkStop();
                        long[] seg = new long[]{blk[0], blk[0], blk[1]};  // start, cur, limit
                        segs.put(idx, seg);
                        try {
                            downloadBlock(idx, seg, etag, lm);
                        } finally {
                            segs.remove(idx);
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
        stealEnabled = false;

        if (!aborted.get() && !paused.get() && !error.isEmpty()) throw new IOException(error);
    }

    /**
     * 字节级区间池：每个段 = [start, end)。
     * 尾段可以把剩余字节精确切分给所有连接 → 真正做到"全程满速"。
     */
    private void buildPool() {
        /** 构建字节级区间池：跳过已完成的区间，其余按段切分（随机顺序）。 */
        long[][] done = completedRanges();
        List<long[]> gaps = new ArrayList<>();
        long pos = 0;
        for (long[] r : done) {
            if (r[0] > pos) gaps.add(new long[]{pos, r[0]});
            if (r[1] > pos) pos = r[1];
        }
        if (pos < total) gaps.add(new long[]{pos, total});

        List<long[]> blocks = new ArrayList<>();
        int threads = Math.max(1, task.threads);
        for (long[] g : gaps) {
            long span = g[1] - g[0];
            int nblocks = Math.max(1, threads * BLOCKS_PER_THREAD);
            long per = span / nblocks;
            if (per < MIN_BLOCK_BYTES) per = MIN_BLOCK_BYTES;
            long p = g[0];
            while (p < g[1]) {
                long e = Math.min(g[1], p + per);
                blocks.add(new long[]{p, e});
                p = e;
            }
        }
        Collections.shuffle(blocks);
        synchronized (poolLock) {
            pool.clear();
            pool.addAll(blocks);
        }
    }

    /** 从池中领取一段字节区间；慢连接在冷却期内领不到活（把机会让给快连接） */
    private long[] claimBlock(int idx) {
        synchronized (poolLock) {
            if (pool.isEmpty()) return null;
            if (idx >= 0) {
                Long until = slowUntil.get(idx);
                if (until != null && System.currentTimeMillis() < until && pool.size() > 1) {
                    return null;      // 慢连接暂时歇着，让快连接先干
                }
            }
            if (pool.size() == 1) return pool.remove(0);
            return pool.remove(rng.nextInt(pool.size()));
        }
    }

    /** 把未完成的字节区间放回池子 */
    private void returnBlock(long start, long end) {
        if (end <= start) return;
        synchronized (poolLock) {
            pool.add(new long[]{start, end});
        }
    }

    /**
     * Work Stealing：明显偏慢的连接，它手里**尚未开始**的尾部被回收进池子，
     * 由空闲连接接手 → 慢连接不再拖住全局，尾段也能保持高速。
     */
    private void stealWork() {
        if (segs.isEmpty()) return;
        double agg = aggSpeed;
        if (agg <= 0) return;
        int idle = Math.max(0, Math.max(1, task.threads) - segs.size());
        if (idle <= 0) return;
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<Integer, long[]> e : segs.entrySet()) {
            int idx = e.getKey();
            long[] seg = e.getValue();
            double mine = rateNow.getOrDefault(idx, 0.0);
            if (mine <= 0 || mine >= agg * STEAL_RATIO) continue;
            Long last = lastSteal.get(idx);
            if (last != null && now - last < STEAL_COOLDOWN_MS) continue;
            long keep = seg[1];
            if (keep >= seg[2]) continue;
            lastSteal.put(idx, now);
            returnBlock(keep, seg[2]);
            seg[2] = keep;
            // 慢连接进入冷却：暂不让它领新活，避免它又抢回慢活
            slowUntil.put(idx, now + 1500);
        }
    }

    private final java.util.Map<Integer, Long> lastSteal = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, Long> slowUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** 本连接最近一个统计窗口的速率（B/s） */
    private double connRate(int idx) {
        return rateNow.getOrDefault(idx, 0.0);
    }

    /** 每 2 秒统计一次各连接的速率（累计字节差 / 时间差） */
    private void refreshRates() {
        long now = System.currentTimeMillis();
        double sum = 0;
        for (java.util.Map.Entry<Integer, Long> e : doneByConn.entrySet()) {
            int idx = e.getKey();
            long[] st = rateState.get(idx);
            if (st == null) {
                rateState.put(idx, new long[]{now, 0L});
                continue;
            }
            long t0 = st[0];
            if (now - t0 < RATE_WINDOW_MS) continue;
            long last = st[1];
            double rate = (e.getValue() - last) / ((double) (now - t0) / 1000.0);
            rateNow.put(idx, rate);
            sum += rate;
            rateState.put(idx, new long[]{now, e.getValue()});
        }
        aggSpeed = sum;
    }

    /**
     * 下载一段字节区间 seg = {start, cur, limit}。
     * 调度器回收尾部时会把 limit 调小 → 本连接下到 limit 即收工，余下交还池子。
     */
    private void downloadBlock(int idx, long[] seg, String etag, long lm) throws Exception {
        long start = seg[0];
        long limit = seg[2];
        long end = limit - 1;              // 闭区间
        long w = start;
        int maxRetry = Math.max(1, Prefs.retry());
        IOException last = null;
        for (int attempt = 0; attempt < maxRetry; attempt++) {
            checkStop();
            HttpURLConnection c = null;
            RandomAccessFile raf = null;
            long wrote = 0;
            boolean stolen = false;
            long pos = start;
            try {
                c = open(w, end, etag, lm);
                int code = c.getResponseCode();
                if (code == 416) { w = end + 1; break; }
                if (code != 206 && code != 200) throw new IOException("HTTP " + code);
                boolean fullFile = (code == 200);
                if (fullFile && (start != 0 || (end - start + 1) < total)) {
                    throw new IOException("server ignored Range");
                }
                try (InputStream in = c.getInputStream()) {
                    raf = new RandomAccessFile(part, "rw");
                    byte[] buf = new byte[BUF];
                    pos = w;
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
                    while (pos < limit) {
                        checkStop();
                        int want = (int) Math.min(BUF, limit - pos);
                        int r = in.read(buf, 0, want);
                        if (r <= 0) break;
                        synchronized (writeLock) {
                            raf.seek(pos);
                            raf.write(buf, 0, r);
                        }
                        pos += r;
                        wrote += r;
                        w = pos;
                        seg[1] = pos;
                        doneBytes += r;
                        doneByConn.merge(idx, (long) r, Long::sum);
                        tick();
                        // 该段尾部已被调度器回收 → 提前收工，余下交给别人
                        if (seg[2] <= pos) { stolen = true; break; }
                    }
                    if (pos >= limit) { w = limit; break; }
                    last = new IOException("分片不完整");
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                throw e;
            } finally {
                if (raf != null) try { raf.close(); } catch (IOException ignored) { }
                if (c != null) c.disconnect();
            }
            markRangeDone(start, pos);
            saveState();
            if (stolen) {
                // 尾部已被回收并交给别人 → 不再重复放回
                return;
            }
            if (w >= limit) break;
            // 只扣掉本轮写下但未记账为完成区间的那部分字节，避免进度与断点不一致
            long recorded = 0;
            for (long[] r : completedRanges()) {
                if (r[0] >= start && r[1] <= w) recorded += r[1] - r[0];
            }
            long partial = wrote - recorded;
            if (partial > 0) doneBytes -= partial;
            if (attempt + 1 < maxRetry) {
                try { Thread.sleep(300L * (attempt + 1)); } catch (InterruptedException ie) { throw ie; }
            }
        }
        // 未完成的字节放回池子
        if (seg[2] > seg[1]) {
            returnBlock(seg[1], seg[2]);
        }
        if (w < seg[2] && last != null && !aborted.get() && !paused.get()) {
            throw last;
        }
    }

    /** 记录一段已完成的字节区间（精确断点，end 为开区间） */
    private void markRangeDone(long start, long end) {
        if (end <= start) return;
        synchronized (writeLock) {
            doneRanges.add(new long[]{start, end});
            java.util.Collections.sort(doneRanges, (a, b) -> Long.compare(a[0], b[0]));
            java.util.List<long[]> merged = new ArrayList<>();
            for (long[] r : doneRanges) {
                if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                    if (r[1] > merged.get(merged.size() - 1)[1]) {
                        merged.get(merged.size() - 1)[1] = r[1];
                    }
                } else {
                    merged.add(new long[]{r[0], r[1]});
                }
            }
            doneRanges.clear();
            doneRanges.addAll(merged);
            // 同步 piece 位图（整片完成的才置位）
            for (long[] r : merged) {
                int k0 = (int) (r[0] / pieceSize);
                int k1 = (int) ((r[1] - 1) / pieceSize);
                for (int k = k0; k <= k1 && k < bitmap.length; k++) {
                    if (!bitmap[k] && (long) (k + 1) * pieceSize <= r[1]) bitmap[k] = true;
                }
            }
        }
    }

    /** 取得已完成的字节区间（用于构建区间池） */
    private long[][] completedRanges() {
        synchronized (writeLock) {
            long[][] out = new long[doneRanges.size()][];
            for (int i = 0; i < doneRanges.size(); i++) {
                out[i] = doneRanges.get(i);
            }
            return out;
        }
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