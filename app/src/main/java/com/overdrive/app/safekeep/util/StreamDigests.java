package com.overdrive.app.safekeep.util;

import java.security.MessageDigest;

/**
 * Whole-object digests fed as bytes go on the wire. Checkpoints clone state before a
 * chunk so a failed or partially-accepted chunk can be rolled back without re-reading.
 */
public final class StreamDigests {

    private static final int DROPBOX_BLOCK = 4 * 1024 * 1024;

    private MessageDigest md5;
    private MessageDigest sha256;
    private MessageDigest dbxOverall;
    private MessageDigest dbxBlock;
    private int dbxBlockLen;
    private long fed;

    public StreamDigests(boolean wantMd5, boolean wantSha256, boolean wantDropbox) {
        try {
            if (wantMd5) md5 = MessageDigest.getInstance("MD5");
            if (wantSha256) sha256 = MessageDigest.getInstance("SHA-256");
            if (wantDropbox) {
                dbxOverall = MessageDigest.getInstance("SHA-256");
                dbxBlock = MessageDigest.getInstance("SHA-256");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public long fedBytes() {
        return fed;
    }

    public void update(byte[] b, int off, int len) {
        if (len <= 0) return;
        if (md5 != null) md5.update(b, off, len);
        if (sha256 != null) sha256.update(b, off, len);
        if (dbxOverall != null) {
            int p = off, remaining = len;
            while (remaining > 0) {
                int take = Math.min(remaining, DROPBOX_BLOCK - dbxBlockLen);
                dbxBlock.update(b, p, take);
                dbxBlockLen += take;
                p += take;
                remaining -= take;
                if (dbxBlockLen == DROPBOX_BLOCK) {
                    dbxOverall.update(dbxBlock.digest());
                    dbxBlockLen = 0;
                }
            }
        }
        fed += len;
    }

    public static final class Checkpoint {
        final MessageDigest md5, sha256, dbxOverall, dbxBlock;
        final int dbxBlockLen;
        final long fed;

        Checkpoint(MessageDigest md5, MessageDigest sha256, MessageDigest dbxOverall,
                   MessageDigest dbxBlock, int dbxBlockLen, long fed) {
            this.md5 = md5;
            this.sha256 = sha256;
            this.dbxOverall = dbxOverall;
            this.dbxBlock = dbxBlock;
            this.dbxBlockLen = dbxBlockLen;
            this.fed = fed;
        }
    }

    /** Null when the platform digest can't be cloned; callers then re-feed from the source. */
    public Checkpoint checkpoint() {
        try {
            return new Checkpoint(copy(md5), copy(sha256), copy(dbxOverall), copy(dbxBlock),
                    dbxBlockLen, fed);
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    public void restore(Checkpoint c) throws CloneNotSupportedException {
        md5 = copy(c.md5);
        sha256 = copy(c.sha256);
        dbxOverall = copy(c.dbxOverall);
        dbxBlock = copy(c.dbxBlock);
        dbxBlockLen = c.dbxBlockLen;
        fed = c.fed;
    }

    public void reset() {
        if (md5 != null) md5.reset();
        if (sha256 != null) sha256.reset();
        if (dbxOverall != null) {
            dbxOverall.reset();
            dbxBlock.reset();
        }
        dbxBlockLen = 0;
        fed = 0;
    }

    public String md5Hex() {
        return md5 == null ? null : hexOfCopy(md5);
    }

    public String sha256Hex() {
        return sha256 == null ? null : hexOfCopy(sha256);
    }

    public String dropboxHex() {
        if (dbxOverall == null) return null;
        try {
            MessageDigest overall = (MessageDigest) dbxOverall.clone();
            if (dbxBlockLen > 0) overall.update(((MessageDigest) dbxBlock.clone()).digest());
            return hex(overall.digest());
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    private static String hexOfCopy(MessageDigest d) {
        try {
            return hex(((MessageDigest) d.clone()).digest());
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    private static MessageDigest copy(MessageDigest d) throws CloneNotSupportedException {
        return d == null ? null : (MessageDigest) d.clone();
    }

    public static String hex(byte[] b) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = hexChars[(b[i] >> 4) & 0xf];
            out[i * 2 + 1] = hexChars[b[i] & 0xf];
        }
        return new String(out);
    }
}
