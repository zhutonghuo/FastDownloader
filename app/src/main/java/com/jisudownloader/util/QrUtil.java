package com.jisudownloader.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public final class QrUtil {

    public static byte[] png(String content, int px) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints);
        int w = m.getWidth();
        int h = m.getHeight();
        byte[] raw = new byte[(w * 3 + 1) * h];
        int p = 0;
        for (int y = 0; y < h; y++) {
            raw[p++] = 0;
            for (int x = 0; x < w; x++) {
                byte v = (byte) (m.get(x, y) ? 0x00 : 0xFF);
                raw[p++] = v;
                raw[p++] = v;
                raw[p++] = v;
            }
        }
        Deflater def = new Deflater(9);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(buf);
            z.write(buf, 0, n);
        }
        def.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, w);
        writeInt(ihdr, h);
        ihdr.write(new byte[]{8, 2, 0, 0, 0});
        out.write(chunk("IHDR", ihdr.toByteArray()));
        out.write(chunk("IDAT", z.toByteArray()));
        out.write(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = type.getBytes("US-ASCII");
        writeInt(b, data.length);
        b.write(t);
        b.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeInt(b, (int) crc.getValue());
        return b.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream b, int v) {
        b.write((v >>> 24) & 0xFF);
        b.write((v >>> 16) & 0xFF);
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }
}
