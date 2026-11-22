package com.jisudownloader.net;

import android.content.Context;

import com.jisudownloader.util.Fmt;
import com.jisudownloader.util.QrUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LanHub {

    public static final class RoomFile {
        public final String id;
        public final String name;
        public final String fromId;
        public final String fromName;
        public final long size;
        public final long time;
        public final File file;

        RoomFile(String id, String name, String fromId, String fromName, long size, long time, File file) {
            this.id = id;
            this.name = name;
            this.fromId = fromId;
            this.fromName = fromName;
            this.size = size;
            this.time = time;
            this.file = file;
        }
    }

    public static final class ChatMsg {
        public final String id;
        public final String kind;
        public final String fromId;
        public final String fromName;
        public final String text;
        public final String fileId;
        public final String fileName;
        public final long fileSize;
        public final long time;

        ChatMsg(String id, String kind, String fromId, String fromName, String text,
                String fileId, String fileName, long fileSize, long time) {
            this.id = id;
            this.kind = kind;
            this.fromId = fromId;
            this.fromName = fromName;
            this.text = text;
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.time = time;
        }
    }

    private final Context ctx;
    private final String code;
    private final int port;
    private final File dir;
    private final String selfId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final List<RoomFile> files = new CopyOnWriteArrayList<>();
    private final List<ChatMsg> msgs = new CopyOnWriteArrayList<>();
    private final Map<String, Long> peers = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;
    private volatile byte[] page;
    private volatile String lastError = "";

    public LanHub(Context c, int codeLen) throws IOException {
        ctx = c.getApplicationContext();
        int p = LanUtil.pickPort();
        if (p == 0) throw new IOException("没有可用端口");
        port = p;
        code = LanUtil.randomCode(codeLen, port);
        dir = new File(ctx.getFilesDir(), "lanroom");
        if (dir.exists()) deleteRecursively(dir);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建房间目录");
    }

    public String code() {
        return code;
    }

    public int port() {
        return port;
    }

    public String ip() {
        return LanUtil.localIpv4();
    }

    public String url() {
        return "http://" + ip() + ":" + port;
    }

    public String selfId() {
        return selfId;
    }

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return lastError;
    }

    public List<RoomFile> snapshot() {
        return new ArrayList<>(files);
    }

    public List<ChatMsg> chat() {
        return new ArrayList<>(msgs);
    }

    public JSONArray chatJson() {
        JSONArray a = new JSONArray();
        for (ChatMsg m : msgs) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", m.id).put("kind", m.kind).put("fromId", m.fromId)
                        .put("fromName", m.fromName).put("text", m.text)
                        .put("fileId", m.fileId).put("fileName", m.fileName)
                        .put("fileSize", m.fileSize).put("time", m.time);
            } catch (Exception ignored) {
            }
            a.put(o);
        }
        return a;
    }

    public List<String> peerNames() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = peers.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > 90000) it.remove();
        }
        List<String> names = new ArrayList<>(peers.keySet());
        Collections.sort(names);
        return names;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        page = readPage();
        running = true;
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (running) lastError = String.valueOf(e.getMessage());
                    break;
                }
            }
        }, "lan-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public RoomFile addLocal(InputStream src, String name, String fromId, String fromName) throws IOException {
        RoomFile f = store(name, fromId, fromName, src);
        files.add(f);
        addFileMsg(f);
        return f;
    }

    public ChatMsg sendText(String fromId, String fromName, String text) {
        if (text == null) text = "";
        if (text.length() > 4000) text = text.substring(0, 4000);
        ChatMsg m = new ChatMsg(rid(), "text", fromId, fromName, text, null, null, 0,
                System.currentTimeMillis());
        msgs.add(m);
        return m;
    }

    public boolean recall(String me, String msgId) {
        ChatMsg target = null;
        for (ChatMsg m : msgs) {
            if (m.id.equals(msgId)) {
                target = m;
                break;
            }
        }
        if (target == null) return false;
        if (!target.fromId.equals(me)) return false;
        msgs.remove(target);
        if ("file".equals(target.kind) && target.fileId != null) remove(target.fileId);
        return true;
    }

    public void remove(String id) {
        for (RoomFile f : files) {
            if (f.id.equals(id)) {
                files.remove(f);
                if (f.file != null) f.file.delete();
                break;
            }
        }
        for (ChatMsg m : msgs) {
            if ("file".equals(m.kind) && id.equals(m.fileId)) {
                msgs.remove(m);
                break;
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
        for (RoomFile f : files) {
            if (f.file != null) f.file.delete();
        }
        files.clear();
        msgs.clear();
        peers.clear();
        deleteRecursively(dir);
    }

    private RoomFile store(String name, String fromId, String fromName, InputStream src) throws IOException {
        String safe = Fmt.safeName(name);
        String id = rid();
        File dest = new File(dir, id + "_" + safe);
        long size = 0;
        try (OutputStream os = new FileOutputStream(dest); InputStream is = src) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                size += n;
            }
        }
        return new RoomFile(id, safe, fromId, fromName, size, System.currentTimeMillis(), dest);
    }

    private void addFileMsg(RoomFile f) {
        msgs.add(new ChatMsg(rid(), "file", f.fromId, f.fromName, "", f.id, f.name, f.size, f.time));
    }

    private static String rid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private byte[] readPage() {
        try (InputStream in = ctx.getAssets().open("lan.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } catch (Exception e) {
            return "<h1>页面加载失败</h1>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream raw = new BufferedInputStream(s.getInputStream(), 65536);
            String line = readLine(raw);
            if (line == null) return;
            String[] head = line.split(" ");
            if (head.length < 2) return;
            String method = head[0].toUpperCase(Locale.US);
            String rawPath = head[1];
            Map<String, String> hs = new HashMap<>();
            String h;
            while ((h = readLine(raw)) != null && !h.isEmpty()) {
                int k = h.indexOf(':');
                if (k > 0) {
                    hs.put(h.substring(0, k).trim().toLowerCase(Locale.US), h.substring(k + 1).trim());
                }
            }
            URI u = new URI(rawPath);
            String path = u.getPath();
            if (path == null) path = "/";
            Map<String, String> q = parseQuery(u.getRawQuery());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);

            if ("OPTIONS".equals(method)) {
                send(out, 204, "text/plain; charset=UTF-8", new byte[0], null);
                out.flush();
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] pg = page == null ? readPage() : page;
                send(out, 200, "text/html; charset=UTF-8", pg, null);
                out.flush();
                return;
            }
            if ("/api/ping".equals(path)) {
                json(out, 200, new JSONObject().put("ok", true).put("name", "极速下载器").put("port", port));
                out.flush();
                return;
            }
            String given = q.get("code");
            if (!code.equals(given)) {
                json(out, 403, new JSONObject().put("ok", false).put("msg", "口令错误"));
                out.flush();
                return;
            }
            String who = q.get("name");
            if (who != null && !who.trim().isEmpty()) peers.put(who.trim(), System.currentTimeMillis());

            if ("/api/join".equals(path)) {
                json(out, 200, new JSONObject().put("ok", true).put("msg", "已加入房间")
                        .put("ip", ip()).put("port", port).put("code", code));
            } else if ("/api/list".equals(path)) {
                JSONArray fa = new JSONArray();
                for (RoomFile f : files) {
                    JSONObject o = new JSONObject();
                    o.put("id", f.id).put("name", f.name).put("size", f.size)
                            .put("time", f.time).put("from", f.fromName).put("fromId", f.fromId);
                    fa.put(o);
                }
                JSONArray pa = new JSONArray();
                for (String n : peerNames()) pa.put(n);
                json(out, 200, new JSONObject().put("ok", true).put("files", fa)
                        .put("peers", pa).put("code", code).put("port", port));
            } else if ("/api/chat".equals(path)) {
                if ("POST".equals(method)) {
                    Map<String, String> f = readForm(raw, hs);
                    String me = f.get("me");
                    if (me == null || me.isEmpty()) me = f.get("from");
                    String name = f.get("name");
                    if (name == null || name.isEmpty()) name = "匿名";
                    String text = f.get("text");
                    if (text == null || text.trim().isEmpty()) {
                        json(out, 400, new JSONObject().put("ok", false).put("msg", "内容为空"));
                    } else {
                        sendText(me, name, text);
                        json(out, 200, new JSONObject().put("ok", true));
                    }
                } else {
                    json(out, 200, new JSONObject().put("ok", true).put("msgs", chatJson()));
                }
            } else if ("/api/upload".equals(path)) {
                doUpload(raw, hs, q, out);
            } else if ("/api/download".equals(path)) {
                doDownload(q, out);
            } else if ("/api/qr".equals(path)) {
                byte[] img = QrUtil.png(url() + "/?code=" + code, 480);
                send(out, 200, "image/png", img, "Content-Disposition: inline; filename=\"lan-room.png\"\r\n");
            } else if ("/api/recall".equals(path)) {
                Map<String, String> f = readForm(raw, hs);
                String me = f.get("me");
                String id = f.get("id");
                boolean ok = recall(me, id);
                json(out, ok ? 200 : 400, new JSONObject().put("ok", ok)
                        .put("msg", ok ? "已撤回" : "只能撤回自己发送的内容"));
            } else {
                json(out, 404, new JSONObject().put("ok", false).put("msg", "不存在"));
            }
            out.flush();
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void doUpload(InputStream raw, Map<String, String> hs, Map<String, String> q,
                          OutputStream out) throws Exception {
        String name = Fmt.safeName(LanUtil.dec(q.get("filename")));
        if ("download".equals(name)) name = "file_" + System.currentTimeMillis();
        String me = q.get("me");
        if (me == null || me.isEmpty()) me = q.get("from");
        if (me == null || me.isEmpty()) me = "anon";
        String disp = q.get("name");
        if (disp == null || disp.isEmpty()) disp = "匿名";
        InputStream body = bodyStream(raw, hs);
        RoomFile f = store(name, me, disp, body);
        if (f.size == 0) {
            f.file.delete();
            json(out, 400, new JSONObject().put("ok", false).put("msg", "空文件"));
            return;
        }
        files.add(f);
        addFileMsg(f);
        json(out, 200, new JSONObject().put("ok", true).put("id", f.id).put("name", name));
    }

    private void doDownload(Map<String, String> q, OutputStream out) throws Exception {
        String id = q.get("id");
        RoomFile f = null;
        for (RoomFile x : files) {
            if (x.id.equals(id)) {
                f = x;
                break;
            }
        }
        if (f == null || !f.file.exists()) {
            json(out, 404, new JSONObject().put("ok", false).put("msg", "文件不存在"));
            return;
        }
        boolean inline = "1".equals(q.get("inline"));
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(LanUtil.mime(f.name)).append("\r\n");
        h.append("Content-Length: ").append(f.file.length()).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Content-Disposition: ")
                .append(inline ? "inline" : "attachment")
                .append("; filename*=UTF-8''")
                .append(LanUtil.enc(f.name)).append("\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        try (FileInputStream fis = new FileInputStream(f.file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private Map<String, String> readForm(InputStream raw, Map<String, String> hs) throws IOException {
        InputStream body = bodyStream(raw, hs);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = body.read(buf)) > 0) b.write(buf, 0, n);
        String s = new String(b.toByteArray(), "UTF-8");
        Map<String, String> m = new HashMap<>();
        for (String p : s.split("&")) {
            int k = p.indexOf('=');
            if (k < 0) continue;
            m.put(LanUtil.dec(p.substring(0, k)), LanUtil.dec(p.substring(k + 1)));
        }
        return m;
    }

    private static InputStream bodyStream(InputStream in, Map<String, String> hs) throws IOException {
        String te = hs.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) return new Chunked(in);
        long len = 0;
        String cl = hs.get("content-length");
        if (cl != null) {
            try {
                len = Long.parseLong(cl.trim());
            } catch (Exception ignored) {
            }
        }
        return new Limited(in, len);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extra)
            throws IOException {
        String reason;
        if (code == 200) reason = "OK";
        else if (code == 204) reason = "No Content";
        else if (code == 400) reason = "Bad Request";
        else if (code == 403) reason = "Forbidden";
        else if (code == 404) reason = "Not Found";
        else reason = "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: *\r\n");
        if (extra != null) h.append(extra);
        h.append("\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        if (body.length > 0) out.write(body);
    }

    private static void json(OutputStream out, int code, JSONObject o) throws IOException {
        send(out, code, "application/json; charset=UTF-8", o.toString().getBytes("UTF-8"), null);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String p : raw.split("&")) {
            int k = p.indexOf('=');
            if (k < 0) m.put(LanUtil.dec(p), "");
            else m.put(LanUtil.dec(p.substring(0, k)), LanUtil.dec(p.substring(k + 1)));
        }
        return m;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "US-ASCII");
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursively(c);
        }
        f.delete();
    }

    private static final class Limited extends InputStream {
        private final InputStream src;
        private long left;

        Limited(InputStream s, long n) {
            src = s;
            left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int r = src.read();
            if (r >= 0) left--;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = src.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    private static final class Chunked extends InputStream {
        private final InputStream src;
        private long left = 0;
        private boolean eof = false;

        Chunked(InputStream s) {
            src = s;
        }

        private void prepare() throws IOException {
            while (left == 0 && !eof) {
                String l = readAsciiLine(src);
                if (l == null) {
                    eof = true;
                    return;
                }
                int semi = l.indexOf(';');
                String hex = (semi >= 0 ? l.substring(0, semi) : l).trim();
                long n;
                try {
                    n = Long.parseLong(hex, 16);
                } catch (Exception e) {
                    eof = true;
                    return;
                }
                if (n == 0) {
                    while (true) {
                        String t = readAsciiLine(src);
                        if (t == null || t.isEmpty()) break;
                    }
                    eof = true;
                    return;
                }
                left = n;
            }
        }

        @Override
        public int read() throws IOException {
            prepare();
            if (eof) return -1;
            int r = src.read();
            if (r < 0) {
                eof = true;
                return -1;
            }
            left--;
            if (left == 0) readAsciiLine(src);
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            prepare();
            if (eof) return -1;
            int r = src.read(b, off, (int) Math.min(len, left));
            if (r < 0) {
                eof = true;
                return -1;
            }
            left -= r;
            if (left == 0) readAsciiLine(src);
            return r;
        }
    }
}
