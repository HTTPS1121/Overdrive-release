package com.overdrive.app.safekeep.crypto;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** BIP-173 Bech32 (not bech32m), as used by age for recipients and identities. */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    private Bech32() {}

    public static String encode(String hrp, byte[] data) {
        byte[] five = convertBits(data, 8, 5, true);
        String h = hrp.toLowerCase(Locale.ROOT);
        byte[] checksum = checksum(h, five);
        StringBuilder sb = new StringBuilder(h.length() + 1 + five.length + 6);
        sb.append(h).append('1');
        for (byte b : five) sb.append(CHARSET.charAt(b));
        for (byte b : checksum) sb.append(CHARSET.charAt(b));
        return sb.toString();
    }

    /** Returns {hrp, data}. Mixed case is rejected, as the spec requires. */
    public static Object[] decode(String s) {
        if (s == null) throw new IllegalArgumentException("empty");
        String lower = s.toLowerCase(Locale.ROOT);
        String upper = s.toUpperCase(Locale.ROOT);
        if (!s.equals(lower) && !s.equals(upper)) throw new IllegalArgumentException("mixed case");
        int pos = lower.lastIndexOf('1');
        if (pos < 1 || pos + 7 > lower.length()) throw new IllegalArgumentException("bad separator");
        String hrp = lower.substring(0, pos);
        byte[] values = new byte[lower.length() - pos - 1];
        for (int i = 0; i < values.length; i++) {
            int v = CHARSET.indexOf(lower.charAt(pos + 1 + i));
            if (v < 0) throw new IllegalArgumentException("bad character");
            values[i] = (byte) v;
        }
        if (polymod(concat(hrpExpand(hrp), values)) != 1) {
            throw new IllegalArgumentException("bad checksum");
        }
        byte[] five = new byte[values.length - 6];
        System.arraycopy(values, 0, five, 0, five.length);
        return new Object[]{hrp, convertBits(five, 5, 8, false)};
    }

    private static byte[] checksum(String hrp, byte[] data) {
        byte[] values = concat(concat(hrpExpand(hrp), data), new byte[6]);
        int mod = polymod(values) ^ 1;
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte) ((mod >>> (5 * (5 - i))) & 31);
        return out;
    }

    private static int polymod(byte[] values) {
        int chk = 1;
        for (byte v : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (v & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) != 0) chk ^= GEN[i];
            }
        }
        return chk;
    }

    private static byte[] hrpExpand(String hrp) {
        byte[] out = new byte[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            out[i] = (byte) (hrp.charAt(i) >> 5);
            out[i + hrp.length() + 1] = (byte) (hrp.charAt(i) & 31);
        }
        return out;
    }

    private static byte[] convertBits(byte[] in, int from, int to, boolean pad) {
        int acc = 0, bits = 0, maxv = (1 << to) - 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : in) {
            int v = b & 0xff;
            if ((v >>> from) != 0) throw new IllegalArgumentException("bad value");
            acc = (acc << from) | v;
            bits += from;
            while (bits >= to) {
                bits -= to;
                out.write((acc >>> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc << (to - bits)) & maxv);
        } else if (bits >= from || ((acc << (to - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("bad padding");
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
