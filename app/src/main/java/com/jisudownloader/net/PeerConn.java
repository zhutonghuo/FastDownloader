package com.jisudownloader.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PeerConn implements AutoCloseable {

    public static final int MAX_BLOCK = 32768;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private final byte[] infoHash;
    private final byte[] peerId;
    private boolean closed;

    public byte[] remotePeerId;
    public Set<Integer> have = new HashSet<>();
    public int downloadedFrom;

    public PeerConn(PeerDiscovery.Peer p, byte[] ih, byte[] pid, int timeoutMs) throws IOException {
        peerId = pid;
        infoHash = ih;
        sock = new Socket();
        sock.setTcpNoDelay(true);
        sock.connect(new InetSocketAddress(p.ip, p.port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());
        handshake();
    }

    private void handshake() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(19);
        bo.write("BitTorrent protocol".getBytes("ASCII"));
        bo.write(new byte[8]);
        bo.write(infoHash);
        bo.write(peerId);
        write(0, bo.toByteArray());
        byte[] resp = read();
        if (resp.length < 68 || resp[0] != 19) throw new IOException("bad handshake");
        remotePeerId = Arrays.copyOfRange(resp, 48, 68);
        payload();
    }

    public void sendExtendedHandshake(int port) throws IOException {
        Map<String, Object> m = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("ut_metadata", 1L);
        inner.put("ut_pex", 2L);
        m.put("m", inner);
        m.put("v", "JisuDownloader 1.0");
        m.put("p", (long) port);
        m.put("reqq", 250L);
        Map<String, Object> root = new HashMap<>();
        root.put("m", m);
        root.put("v", 2L);
        byte[] benc = Bencode.encode(root);
        write(20, benc);
    }

    public void readExtendedHandshake() throws IOException {
        for (int i = 0; i < 12; i++) {
            byte[] p = payload();
            if (p.length > 2 && p[0] == 20) {
                Object o = Bencode.parse(p);
                if (o instanceof Map) return;
            }
        }
    }

    public void requestMetadata() throws IOException {
        byte[] m = Bencode.encode(new byte[]{0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
        write(20, m);
    }

    public byte[] readMetadataBlock() throws IOException {
        for (int i = 0; i < 24; i++) {
            byte[] p = payload();
            if (p.length < 2 || p[0] != 20) continue;
            byte[] benc = Arrays.copyOfRange(p, 2, p.length);
            Object o;
            try {
                o = Bencode.parse(benc);
            } catch (Exception e) {
                continue;
            }
            if (!(o instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) o;
            Object ty = map.get("msg_type");
            Object pi = map.get("piece");
            if (ty instanceof Long && (Long) ty == 1L && pi instanceof Long) {
                Object data = map.get("data");
                if (data instanceof byte[]) return (byte[]) data;
            }
        }
        throw new IOException("未收到种子元数据");
    }

    public void readBitfield() throws IOException {
        for (int i = 0; i < 12; i++) {
            byte[] p = payload();
            if (p.length < 1) continue;
            int id = p[0];
            if (id == 5) {
                int bits = (p.length - 1) * 8;
                for (int b = 0; b < bits; b++) {
                    if ((p[1 + b / 8] & (0x80 >> (b % 8))) != 0) have.add(b);
                }
                return;
            }
            if (id == 1) {
                have.clear();
                for (int b = 0; b < p.length * 8; b++) have.add(b);
                return;
            }
        }
    }

    public void requestPiece(int index, int length) throws IOException {
        byte[] p = new byte[13];
        p[0] = 6;
        int off = 1;
        p[off++] = (byte) (index >>> 24);
        p[off++] = (byte) (index >>> 16);
        p[off++] = (byte) (index >>> 8);
        p[off++] = (byte) index;
        p[off++] = (byte) (length >>> 24);
        p[off++] = (byte) (length >>> 16);
        p[off++] = (byte) (length >>> 8);
        p[off++] = (byte) length;
        p[off] = 0;
        write(0, p);
    }

    public byte[] readBlock() throws IOException {
        for (int i = 0; i < 96; i++) {
            byte[] p = payload();
            if (p.length >= 9 && p[0] == 9) return p;
            if (p.length > 0 && p[0] == 6) {
                requestPiece(readInt(p, 1), readInt(p, 5));
            }
        }
        throw new IOException("等待数据块超时");
    }

    public byte[] readFullPiece(int expectedLen, int expectedIndex) throws IOException {
        byte[] out = new byte[expectedLen];
        int filled = 0;
        while (filled < expectedLen) {
            byte[] b = readBlock();
            int begin = readInt(b, 1);
            int pieceIdx = readInt(b, 5);
            byte[] data = Arrays.copyOfRange(b, 9, b.length);
            if (pieceIdx != expectedIndex) continue;
            if (begin < 0 || begin + data.length > expectedLen) continue;
            System.arraycopy(data, 0, out, begin, data.length);
            filled += data.length;
            downloadedFrom += data.length;
        }
        return out;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private void write(int id, byte[] payload) throws IOException {
        if (closed) throw new IOException("closed");
        int len = payload.length;
        out.writeByte(len >>> 16);
        out.writeByte(len >>> 8);
        out.writeByte(len);
        out.writeByte(id);
        out.write(payload);
        out.flush();
    }

    private byte[] read() throws IOException {
        int h = in.read();
        if (h < 0) throw new IOException("连接已断开");
        h = h << 8;
        int h2 = in.read();
        if (h2 < 0) throw new IOException("连接已断开");
        h |= h2 << 8;
        h = h << 8;
        h2 = in.read();
        if (h2 < 0) throw new IOException("连接已断开");
        h |= h2;
        in.read();
        byte[] data = new byte[h];
        in.readFully(data);
        return data;
    }

    private byte[] payload() throws IOException {
        int h = in.read();
        if (h < 0) throw new IOException("连接已断开");
        h = h << 8;
        int h2 = in.read();
        if (h2 < 0) throw new IOException("连接已断开");
        h |= h2 << 8;
        h = h << 8;
        h2 = in.read();
        if (h2 < 0) throw new IOException("连接已断开");
        h |= h2;
        int id = in.read();
        if (id < 0) throw new IOException("连接已断开");
        byte[] data = new byte[h];
        in.readFully(data);
        return data;
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        }
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
    }
}