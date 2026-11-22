package com.jisudownloader.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Bencode {

    private byte[] buf;
    private int pos;

    private Bencode(byte[] b) {
        this.buf = b;
    }

    public static Object parse(byte[] data) {
        Bencode p = new Bencode(data);
        try {
            return p.value();
        } catch (Exception e) {
            throw new RuntimeException("bencode parse error", e);
        }
    }

    private Object value() {
        if (pos >= buf.length) throw new IllegalStateException("eof");
        char c = (char) (buf[pos] & 0xFF);
        if (c == 'i') return number();
        if (c == 'l') return list();
        if (c == 'd') return dict();
        if (c >= '0' && c <= '9') return bytes();
        throw new IllegalStateException("bad token " + c + " @" + pos);
    }

    private Object number() {
        int e = indexOf('e', pos);
        String s = new String(buf, pos + 1, e - pos - 1, StandardCharsets.US_ASCII);
        pos = e + 1;
        return Long.parseLong(s);
    }

    private byte[] bytes() {
        int colon = indexOf(':', pos);
        int len = Integer.parseInt(new String(buf, pos, colon - pos, StandardCharsets.US_ASCII));
        int start = colon + 1;
        pos = start + len;
        byte[] out = new byte[len];
        System.arraycopy(buf, start, out, 0, len);
        return out;
    }

    private List<Object> list() {
        pos++;
        List<Object> out = new ArrayList<>();
        while (pos < buf.length && buf[pos] != 'e') out.add(value());
        pos++;
        return out;
    }

    private Map<String, Object> dict() {
        pos++;
        Map<String, Object> out = new LinkedHashMap<>();
        while (pos < buf.length && buf[pos] != 'e') {
            byte[] k = bytes();
            out.put(new String(k, StandardCharsets.UTF_8), value());
        }
        pos++;
        return out;
    }

    private int indexOf(char ch, int from) {
        for (int i = from; i < buf.length; i++) {
            if (buf[i] == (byte) ch) return i;
        }
        throw new IllegalStateException("missing " + ch);
    }

    public static byte[] encode(Object o) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        write(o, bo);
        return bo.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, ByteArrayOutputStream bo) {
        try {
            if (o instanceof Long) {
                bo.write('i');
                bo.write(String.valueOf(o).getBytes(StandardCharsets.US_ASCII));
                bo.write('e');
            } else if (o instanceof byte[]) {
                byte[] b = (byte[]) o;
                bo.write(String.valueOf(b.length).getBytes(StandardCharsets.US_ASCII));
                bo.write(':');
                bo.write(b);
            } else if (o instanceof String) {
                byte[] b = ((String) o).getBytes(StandardCharsets.UTF_8);
                bo.write(String.valueOf(b.length).getBytes(StandardCharsets.US_ASCII));
                bo.write(':');
                bo.write(b);
            } else if (o instanceof List) {
                bo.write('l');
                for (Object x : (List<Object>) o) write(x, bo);
                bo.write('e');
            } else if (o instanceof Map) {
                bo.write('d');
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    write(e.getKey(), bo);
                    write(e.getValue(), bo);
                }
                bo.write('e');
            } else if (o instanceof Integer) {
                write((long) (Integer) o, bo);
            } else {
                throw new IllegalArgumentException("unsupported " + o.getClass());
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}