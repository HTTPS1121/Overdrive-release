package com.overdrive.app.safekeep.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** QR code → PNG bytes without android.graphics (pure Java, safe in the daemon). */
public final class QrPng {

    private QrPng() {}

    public static byte[] render(String text, int moduleScale) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        int scale = Math.max(2, Math.min(16, moduleScale));
        int w = m.getWidth() * scale, h = m.getHeight() * scale;
        int stride = w + 1;
        byte[] raw = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            raw[y * stride] = 0; // filter: none
            for (int x = 0; x < w; x++) {
                raw[y * stride + 1 + x] = (byte) (m.get(x / scale, y / scale) ? 0x00 : 0xff);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;  // bit depth
        ihdr[9] = 0;  // grayscale
        chunk(png, "IHDR", ihdr);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        chunk(png, "IDAT", z.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len, 0, 4);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c, 0, 4);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
