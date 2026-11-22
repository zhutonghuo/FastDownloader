package com.jisudownloader.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PeerDiscovery {

    public static final class Peer {
        public final String ip;
        public final int port;

        public Peer(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String id() {
            return ip + ":" + port;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Peer && ((Peer) o).id().equals(id());
        }

        @Override
        public int hashCode() {
            return id().hashCode();
        }
    }

    private static final String[] BOOTSTRAP = {
            "router.bittorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "router.utorrent.com:6881",
            "dht.libtorrent.org:25401",
            "router.bitcomet.com:6881"
    };

    private static byte[] pid;

    public static synchronized byte[] peerId() {
        if (pid == null) {
            pid = new byte[20];
            new SecureRandom().nextBytes(pid);
            byte[] tag = "-JI4100-".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(tag, 0, pid, 0, tag.length);
        }
        return pid;
    }

    public static List<Peer> discover(byte[] ih, List<String> trackers, int want, long timeoutMs) {
        List<Peer> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (trackers != null) {
            for (String tr : trackers) {
                try {
                    for (Peer p : announce(tr, ih, want)) {
                        if (seen.add(p.id())) out.add(p);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (out.size() < want) {
            try {
                for (Peer p : dht(ih, Math.max(want * 2, 20), timeoutMs)) {
                    if (seen.add(p.id())) out.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static List<Peer> announce(String tracker, byte[] ih, int limit) throws Exception {
        String t = tracker.trim();
        if (t.startsWith("udp://")) return udpAnnounce(t.substring(6), ih, limit);
        if (t.startsWith("http://") || t.startsWith("https://")) return httpAnnounce(t, ih, limit);
        return new ArrayList<>();
    }

    private static List<Peer> httpAnnounce(String tracker, byte[] ih, int limit) throws IOException {
        String sep = tracker.contains("?") ? "&" : "?";
        String url = tracker + sep
                + "info_hash=" + enc(ih)
                + "&peer_id=" + enc(peerId())
                + "&port=6881&uploaded=0&downloaded=0&left=0"
                + "&compact=1&event=started&numwant=" + Math.min(200, limit);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "JisuDownloader/1.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return parseCompact(bo.toByteArray());
        } finally {
            c.disconnect();
        }
    }

    private static List<Peer> parseCompact(byte[] data) {
        List<Peer> out = new ArrayList<>();
        Object o;
        try {
            o = Bencode.parse(data);
        } catch (Exception e) {
            return out;
        }
        if (!(o instanceof Map)) return out;
        Object peers = ((Map<?, ?>) o).get("peers");
        if (peers instanceof byte[]) {
            byte[] b = (byte[]) peers;
            for (int i = 0; i + 6 <= b.length; i += 6) {
                out.add(new Peer(ipv4(b, i), ((b[i + 4] & 0xFF) << 8) | (b[i + 5] & 0xFF)));
            }
        } else if (peers instanceof List) {
            for (Object x : (List<?>) peers) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Object ip = m.get("ip");
                    Object port = m.get("port");
                    if (ip instanceof byte[] && port instanceof Long) {
                        out.add(new Peer(new String((byte[]) ip, StandardCharsets.UTF_8), (int) (long) (Long) port));
                    }
                }
            }
        }
        return out;
    }

    private static List<Peer> udpAnnounce(String hostport, byte[] ih, int limit) throws IOException {
        String[] hp = hostport.split(":");
        String host = hp[0];
        int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 6881;
        List<Peer> res = new ArrayList<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(4000);
            InetAddress addr = InetAddress.getByName(host);

            Map<String, Object> connect = new LinkedHashMap<>();
            connect.put("connection_id", "qpx96q4ad8jk6gx".getBytes(StandardCharsets.US_ASCII));
            connect.put("transaction_id", tx());
            connect.put("action", 0L);
            byte[] cOut = Bencode.encode(connect);
            sock.send(new DatagramPacket(cOut, cOut.length, addr, port));

            byte[] cid = null;
            byte[] buf = new byte[4096];
            long end = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < end) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException te) {
                    break;
                }
                Map<?, ?> r = readMap(in);
                if (r == null) continue;
                long action = asLong(r.get("action"));
                if (action == 3) {
                    cid = (byte[]) r.get("connection_id");
                    break;
                }
                if (action == 0) break;
            }
            if (cid == null) return res;

            Map<String, Object> ann = new LinkedHashMap<>();
            ann.put("connection_id", cid);
            ann.put("transaction_id", tx());
            ann.put("action", 1L);
            ann.put("info_hash", ih);
            ann.put("peer_id", peerId());
            ann.put("numwant", (long) Math.min(200, limit));
            ann.put("port", 6881L);
            ann.put("key", tx());
            ann.put("event", "started");
            byte[] aOut = Bencode.encode(ann);
            sock.send(new DatagramPacket(aOut, aOut.length, addr, port));

            end = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < end) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException te) {
                    break;
                }
                Map<?, ?> r = readMap(in);
                if (r == null) continue;
                if (asLong(r.get("action")) == 1) {
                    res.addAll(parseCompact(Bencode.encode(r)));
                    break;
                }
            }

            Map<String, Object> stop = new LinkedHashMap<>(ann);
            stop.put("transaction_id", tx());
            stop.put("event", "stopped");
            byte[] sOut = Bencode.encode(stop);
            sock.send(new DatagramPacket(sOut, sOut.length, addr, port));
        } catch (Exception ignored) {
        }
        return res;
    }

    private static Map<?, ?> readMap(DatagramPacket in) {
        try {
            return (Map<?, ?>) Bencode.parse(Arrays.copyOf(in.getData(), in.getLength()));
        } catch (Exception e) {
            return null;
        }
    }

    private static long asLong(Object o) {
        return o instanceof Long ? (Long) o : -1;
    }

    private static byte[] tx() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static List<Peer> dht(byte[] ih, int want, long timeoutMs) {
        List<Peer> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(2000);
            byte[] id = new byte[20];
            new SecureRandom().nextBytes(id);
            byte[] token = new byte[8];
            new SecureRandom().nextBytes(token);
            byte[] target = ByteBufferUtil.toBytes(targetOf(ih));

            for (String bs : BOOTSTRAP) {
                String[] hp = bs.split(":");
                try {
                    byte[] nq = Bencode.encode(krpcQuery("find_node", id, token, target));
                    sock.send(new DatagramPacket(nq, nq.length, InetAddress.getByName(hp[0]), Integer.parseInt(hp[1])));
                } catch (Exception ignored) {
                }
            }

            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            int rounds = 0;
            while (System.currentTimeMillis() < end && res.size() < want && rounds < 400) {
                rounds++;
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(in);
                } catch (SocketTimeoutException te) {
                    continue;
                }
                Map<?, ?> msg = readMap(in);
                if (msg == null) continue;
                Object rr = msg.get("r");
                if (!(rr instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) rr;
                String ty = str(msg.get("y"));
                if ("1".equals(ty)) {
                    byte[] nodes = (byte[]) r.get("nodes");
                    if (nodes == null) continue;
                    for (int i = 0; i + 26 <= nodes.length && res.size() < want; i += 26) {
                        try {
                            String ip = ipv4(nodes, i + 20);
                            int p = ((nodes[i + 24] & 0xFF) << 8) | (nodes[i + 25] & 0xFF);
                            byte[] nq = Bencode.encode(krpcQuery("get_peers", id, token, target));
                            sock.send(new DatagramPacket(nq, nq.length, InetAddress.getByName(ip), p));
                        } catch (Exception ignored) {
                        }
                    }
                } else if ("3".equals(ty)) {
                    Object vals = r.get("values");
                    if (!(vals instanceof List)) continue;
                    for (Object v : (List<?>) vals) {
                        if (res.size() >= want) break;
                        if (v instanceof byte[] && ((byte[]) v).length == 6) {
                            byte[] b = (byte[]) v;
                            Peer pe = new Peer(ipv4(b, 0), ((b[4] & 0xFF) << 8) | (b[5] & 0xFF));
                            if (seen.add(pe.id())) res.add(pe);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return res;
    }

    private static long targetOf(byte[] ih) {
        byte[] b = Arrays.copyOf(ih, 20);
        Arrays.sort(b);
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (b[i] & 0xFF)) << (56 - i * 8);
        return v;
    }

    private static Map<String, Object> krpcQuery(String method, byte[] id, byte[] token, byte[] target) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", id);
        args.put("target", target);
        if ("get_peers".equals(method)) args.put("token", token);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("y", "q");
        q.put("t", "aa");
        q.put("q", method);
        q.put("a", args);
        return q;
    }

    static String ipv4(byte[] b, int off) {
        return (b[off] & 0xFF) + "." + (b[off + 1] & 0xFF) + "." + (b[off + 2] & 0xFF) + "." + (b[off + 3] & 0xFF);
    }

    static String str(Object o) {
        if (o instanceof byte[]) return new String((byte[]) o, StandardCharsets.UTF_8);
        return o == null ? null : o.toString();
    }

    static String enc(byte[] b) {
        try {
            return URLEncoder.encode(new String(b, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return "";
        }
    }

    static final class ByteBufferUtil {
        static byte[] toBytes(long v) {
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) b[i] = (byte) (v >> (56 - i * 8));
            return b;
        }
    }
}