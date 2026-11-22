package com.jisudownloader.net;

import android.content.Context;

import com.jisudownloader.model.Magnet;
import com.jisudownloader.model.Task;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TorrentEngine {

    public interface Progress {
        void onSpeed(double bytesPerSec);
        void onProgress(long downloaded, long total);
        void onThreads(int activeThreads);
        void onStage(String stage);
    }

    private static final int BLOCK = 32768;
    private static final long META_TIMEOUT = 90000;

    private final Task task;
    private final Progress cb;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final List<PeerConn> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, Boolean> have = new ConcurrentHashMap<>();

    private volatile long total;
    private volatile long doneBytes;
    private volatile long lastTickTime;
    private volatile long lastTickBytes;
    private volatile double speed;
    private volatile String error = "";
    private File out;

    public TorrentEngine(Context ctx, Task task, Progress cb) {
        this.task = task;
        this.cb = cb;
    }

    public String error() {
        return error;
    }

    public void pause() {
        paused.set(true);
        for (PeerConn p : open) {
            try {
                p.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void abort() {
        aborted.set(true);
        pause();
    }

    private void checkStop() throws InterruptedException {
        if (aborted.get() || paused.get()) throw new InterruptedException("stop");
    }

    public void run() throws Exception {
        byte[] ih;
        String hex = Magnet.infoHashHex(task.source);
        byte[] b = Magnet.infoHashBytes(hex);
        if (b == null || b.length != 20) {
            error = "无法解析磁力哈希";
            throw new IOException(error);
        }
        ih = b;

        Map<String, Object> info = meta(ih);
        long pieceLen = asLong(info.get("piece length"));
        byte[] pieces = (byte[]) info.get("pieces");
        if (pieceLen <= 0 || pieces == null || pieces.length % 20 != 0) {
            error = "种子元数据损坏";
            throw new IOException(error);
        }
        int pieceCount = pieces.length / 20;
        total = asLong(info.get("length"));
        if (total <= 0) total = sumFiles(info);
        task.total = total;
        task.pieceCount = pieceCount;
        task.pieceLength = (int) pieceLen;

        String name = str(info.get("name"));
        if (name == null || name.isEmpty()) name = Magnet.displayName(task.source);
        task.displayName = name;
        task.fileName = com.jisudownloader.util.Fmt.safeName(name);
        buildTargets(info, name);

        File dir = new File(task.dirPath);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录");
        out = task.finalFile();

        long resume = loadResume();
        doneBytes = resume;

        List<PeerDiscovery.Peer> peers = PeerDiscovery.discover(ih, trackers(task.source), Math.max(20, task.threads * 2), 25000);
        if (peers.isEmpty()) {
            error = "未找到可用节点，请检查网络或稍后重试";
            throw new IOException(error);
        }

        int n = Math.max(1, Math.min(task.threads, 24));
        if (cb != null) cb.onThreads(n);

        AtomicInteger cursor = new AtomicInteger(0);
        List<Thread> ts = new ArrayList<>();
        lastTickTime = System.currentTimeMillis();
        lastTickBytes = doneBytes;

        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread th = new Thread(() -> {
                PeerConn pc = null;
                try {
                    while (!aborted.get() && !paused.get()) {
                        int p = cursor.getAndIncrement();
                        if (p >= pieceCount) break;
                        if (havePiece(p, resume)) continue;
                        checkStop();
                        if (pc == null) {
                            pc = connect(peers, cursor);
                            if (pc == null) {
                                Thread.sleep(1500);
                                cursor.decrementAndGet();
                                continue;
                            }
                            pc.readBitfield();
                        }
                        int len = (int) Math.min(pieceLen, total - (long) p * pieceLen);
                        pc.requestPiece(p, len);
                        byte[] data = pc.readFullPiece(len, p);
                        if (!verify(data, pieces, p)) {
                            pc.close();
                            pc = null;
                            cursor.decrementAndGet();
                            continue;
                        }
                        write(p, data, pieceLen);
                        synchronized (TorrentEngine.class) {
                            doneBytes += len;
                        }
                        tick();
                    }
                } catch (InterruptedException e) {
                } catch (Throwable e) {
                    if (!aborted.get() && !paused.get()) {
                        synchronized (TorrentEngine.class) {
                            if (error.isEmpty()) error = HttpEngine.msg(e);
                        }
                    }
                } finally {
                    active.decrementAndGet();
                    if (pc != null) pc.close();
                }
            }, "jisu-bt-" + idx);
            th.setDaemon(true);
            ts.add(th);
            th.start();
        }
        for (Thread t : ts) t.join();

        if (!aborted.get() && !paused.get() && !error.isEmpty()) throw new IOException(error);
        checkStop();
        if (doneBytes < total) throw new IOException("下载不完整(" + doneBytes + "/" + total + ")");
        finish();
    }

    private PeerConn connect(List<PeerDiscovery.Peer> peers, AtomicInteger cursor) {
        List<PeerDiscovery.Peer> shuffled = new ArrayList<>(peers);
        Collections.shuffle(shuffled);
        for (PeerDiscovery.Peer p : shuffled) {
            if (aborted.get() || paused.get()) return null;
            try {
                PeerConn c = new PeerConn(p, infoHash, PeerDiscovery.peerId(), 12000);
                open.add(c);
                active.incrementAndGet();
                return c;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private byte[] infoHash;

    private boolean havePiece(int index, long resume) {
        if (have.containsKey(index)) return true;
        return false;
    }

    private Map<String, Object> meta(byte[] ih) throws Exception {
        infoHash = ih;
        long deadline = System.currentTimeMillis() + META_TIMEOUT;
        List<String> trs = trackers(task.source);
        while (System.currentTimeMillis() < deadline) {
            checkStop();
            List<PeerDiscovery.Peer> peers = PeerDiscovery.discover(ih, trs, 40, 12000);
            if (cb != null) cb.onStage("正在获取种子信息… 已发现 " + peers.size() + " 个节点");
            for (PeerDiscovery.Peer p : peers) {
                if (aborted.get() || paused.get()) break;
                PeerConn c = null;
                try {
                    c = new PeerConn(p, ih, PeerDiscovery.peerId(), 12000);
                    c.sendExtendedHandshake(6881);
                    c.readExtendedHandshake();
                    c.requestMetadata();
                    byte[] raw = c.readMetadataBlock();
                    Object o = Bencode.parse(raw);
                    if (o instanceof Map) {
                        Object infoVal = ((Map<?, ?>) o).get("info");
                        if (infoVal instanceof Map) {
                            c.close();
                            if (cb != null) cb.onStage("种子信息已获取");
                            return (Map<String, Object>) infoVal;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
            }
            Thread.sleep(2000);
        }
        error = "获取种子信息超时（未找到可用的元数据提供者）";
        throw new IOException(error);
    }

    private List<String> trackers(String magnet) {
        List<String> list = new ArrayList<>();
        for (String v : allParams(magnet, "tr")) {
            if (!v.isEmpty() && !list.contains(v)) list.add(v);
        }
        String[] defs = {
                "udp://tracker.opentrackr.org:1337/announce",
                "udp://open.demonii.com:1337/announce",
                "udp://tracker.openbittorrent.com:6969/announce",
                "udp://exodus.desync.com:6969/announce",
                "udp://tracker.torrent.eu.org:451/announce",
                "http://tracker.opentrackr.org:1337/announce"
        };
        for (String d : defs) if (!list.contains(d)) list.add(d);
        return list;
    }

    private static List<String> allParams(String m, String key) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        String pat = key + "=";
        int from = 0;
        while (true) {
            int i = m.indexOf(pat, from);
            if (i < 0) break;
            int j = m.indexOf('&', i);
            String v = j < 0 ? m.substring(i + pat.length()) : m.substring(i + pat.length(), j);
            try {
                out.add(java.net.URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) {
                out.add(v);
            }
            from = i + 1;
            if (out.size() > 30) break;
        }
        return out;
    }

    private long loadResume() {
        File f = new File(task.dirPath, task.fileName + ".jtorrent");
        try {
            if (!f.exists()) return 0;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            int idx = s.indexOf('|');
            String head = idx < 0 ? s : s.substring(0, idx);
            String body = idx < 0 ? "" : s.substring(idx + 1);
            String[] hs = head.split(",");
            long acc = 0;
            int pieceLen = task.pieceLength > 0 ? task.pieceLength : Integer.parseInt(hs[0]);
            for (String x : hs) {
                if ("1".equals(x)) acc += pieceLen;
            }
            return acc;
        } catch (Exception e) {
            return 0;
        }
    }

    private void write(int index, byte[] data, long pieceLen) {
        try {
            long pieceStart = (long) index * pieceLen;
            int pos = 0;
            int remaining = data.length;
            long cur = pieceStart;

            if (targets == null) {
                if (!out.exists()) {
                    try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                        raf.setLength(total);
                    }
                }
                try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                    raf.seek(pieceStart);
                    raf.write(data);
                }
            } else {
                for (Target tg : targets) {
                    if (remaining <= 0) break;
                    long tEnd = tg.start + tg.length;
                    if (cur >= tEnd) continue;
                    int inThis = (int) Math.min(remaining, tEnd - cur);
                    if (!tg.file.exists()) tg.file.getParentFile().mkdirs();
                    try (RandomAccessFile raf = new RandomAccessFile(tg.file, "rw")) {
                        raf.seek(cur - tg.start);
                        raf.write(data, pos, inThis);
                    }
                    pos += inThis;
                    remaining -= inThis;
                    cur += inThis;
                }
            }
            have.put(index, Boolean.TRUE);
            saveResume(pieceLen);
        } catch (IOException ignored) {
        }
    }

    private static final class Target {
        final File file;
        final long start;
        final long length;

        Target(File file, long start, long length) {
            this.file = file;
            this.start = start;
            this.length = length;
        }
    }

    private List<Target> targets;

    private void buildTargets(Map<String, Object> info, String rootName) {
        targets = null;
        Object fs = info.get("files");
        if (!(fs instanceof List)) return;
        File root = new File(task.dirPath, com.jisudownloader.util.Fmt.safeName(rootName));
        List<Target> list = new ArrayList<>();
        long acc = 0;
        for (Object x : (List<?>) fs) {
            if (!(x instanceof Map)) continue;
            Map<?, ?> fm = (Map<?, ?>) x;
            long len = asLong(fm.get("length"));
            StringBuilder rel = new StringBuilder();
            Object pathList = fm.get("path");
            if (pathList instanceof List) {
                for (Object seg : (List<?>) pathList) {
                    if (rel.length() > 0) rel.append('/');
                    rel.append(str(seg));
                }
            }
            String name = rel.length() == 0 ? ("file_" + list.size()) : rel.toString();
            name = com.jisudownloader.util.Fmt.safeName(name);
            list.add(new Target(new File(root, name), acc, len));
            acc += len;
        }
        targets = list;
    }

    private void saveResume(long pieceLen) {
        try {
            File f = new File(task.dirPath, task.fileName + ".jtorrent");
            StringBuilder sb = new StringBuilder();
            sb.append(pieceLen).append(",");
            for (int i = 0; i < task.pieceCount; i++) {
                if (i > 0) sb.append(',');
                sb.append(have.containsKey(i) ? '1' : '0');
            }
            sb.append('|');
            StringBuilder bits = new StringBuilder();
            for (int i = 0; i < task.pieceCount; i += 8) {
                int v = 0;
                for (int k = 0; k < 8; k++) {
                    if (i + k < task.pieceCount && have.containsKey(i + k)) v |= 0x80 >> k;
                }
                bits.append(v);
            }
            java.nio.file.Files.write(f.toPath(), (sb.toString() + bits.toString()).getBytes("UTF-8"));
        } catch (Exception ignored) {
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

    private boolean verify(byte[] data, byte[] pieces, int index) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(data);
            for (int i = 0; i < 20; i++) {
                if (h[i] != pieces[index * 20 + i]) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private long sumFiles(Map<String, Object> info) {
        Object f = info.get("files");
        if (!(f instanceof List)) return 0;
        long acc = 0;
        for (Object x : (List<?>) f) {
            if (x instanceof Map) acc += asLong(((Map<?, ?>) x).get("length"));
        }
        return acc;
    }

    private void finish() {
        try {
            File f = new File(task.dirPath, task.fileName + ".jtorrent");
            if (f.exists()) f.delete();
        } catch (Exception ignored) {
        }
        task.downloaded = total;
    }

    private static long asLong(Object o) {
        return o instanceof Long ? (Long) o : 0;
    }

    private static String str(Object o) {
        return o instanceof byte[] ? new String((byte[]) o, java.nio.charset.StandardCharsets.UTF_8) : (o == null ? null : o.toString());
    }
}