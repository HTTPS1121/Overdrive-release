package com.overdrive.app.safekeep.dest.s3;

import com.overdrive.app.safekeep.util.StreamDigests;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for S3-compatible endpoints (header auth). */
public final class SigV4 {

    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private SigV4() {}

    /**
     * @param headers lower-case header names to sign (host is added); values are trimmed
     * @return headers to set on the request, including Authorization
     */
    public static Map<String, String> sign(String method, String host, String canonicalUri,
                                           String canonicalQuery, Map<String, String> headers,
                                           String payloadHash, String accessKey, String secretKey,
                                           String region, long epochMs) {
        SimpleDateFormat full = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        full.setTimeZone(TimeZone.getTimeZone("UTC"));
        String amzDate = full.format(new Date(epochMs));
        String day = amzDate.substring(0, 8);

        TreeMap<String, String> signed = new TreeMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                signed.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().trim());
            }
        }
        signed.put("host", host);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);

        StringBuilder canonHeaders = new StringBuilder();
        StringBuilder signedNames = new StringBuilder();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            canonHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (signedNames.length() > 0) signedNames.append(';');
            signedNames.append(e.getKey());
        }
        String canonicalRequest = method + "\n" + canonicalUri + "\n"
                + (canonicalQuery == null ? "" : canonicalQuery) + "\n"
                + canonHeaders + "\n" + signedNames + "\n" + payloadHash;

        String scope = day + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = StreamDigests.hex(hmac(kSigning, stringToSign));

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            if (!e.getKey().equals("host")) out.put(e.getKey(), e.getValue());
        }
        out.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedNames + ", Signature=" + signature);
        return out;
    }

    /** Query-string presigned GET (≤ 7 days), signed for the host header only. */
    public static String presignGet(String scheme, String hostHeader, String canonicalUri, String accessKey,
                                    String secretKey, String region, long epochMs, int expiresSec) {
        SimpleDateFormat full = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        full.setTimeZone(TimeZone.getTimeZone("UTC"));
        String amzDate = full.format(new Date(epochMs));
        String day = amzDate.substring(0, 8);
        String scope = day + "/" + region + "/s3/aws4_request";
        Map<String, String> q = new LinkedHashMap<>();
        q.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        q.put("X-Amz-Credential", accessKey + "/" + scope);
        q.put("X-Amz-Date", amzDate);
        q.put("X-Amz-Expires", Integer.toString(Math.max(1, Math.min(604800, expiresSec))));
        q.put("X-Amz-SignedHeaders", "host");
        String query = canonicalQuery(q);
        String canonicalRequest = "GET\n" + canonicalUri + "\n" + query + "\nhost:" + hostHeader + "\n\nhost\n"
                + UNSIGNED_PAYLOAD;
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day), region), "s3"),
                "aws4_request");
        return scheme + "://" + hostHeader + canonicalUri + "?" + query + "&X-Amz-Signature="
                + StreamDigests.hex(hmac(k, stringToSign));
    }

    /** RFC 3986 encoding with AWS's unreserved set; '/' kept unless {@code encodeSlash}. */
    public static String uriEncode(String s, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /** Sorted, encoded query string; a null value renders as "key=". */
    public static String canonicalQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            sorted.put(uriEncode(e.getKey(), true),
                    e.getValue() == null ? "" : uriEncode(e.getValue(), true));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static String sha256Hex(byte[] data) {
        try {
            return StreamDigests.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
