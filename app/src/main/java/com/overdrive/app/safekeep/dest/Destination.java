package com.overdrive.app.safekeep.dest;

import org.json.JSONObject;

import java.util.List;

/**
 * Protocol driver. The core owns naming, encryption, rules, retention, budgets and the
 * chunk loop; a driver only moves bytes and keeps its resume state in {@link UploadJob#state}.
 */
public interface Destination {

    final class Caps {
        /** Whole-object verification the driver reports in {@link RemoteRef}: "md5", "dropbox" or "size". */
        public final String verify;
        public final boolean resumable;
        public final boolean canDelete;
        public final boolean canShare;
        public final boolean canList;

        public Caps(String verify, boolean resumable, boolean canDelete, boolean canShare, boolean canList) {
            this.verify = verify;
            this.resumable = resumable;
            this.canDelete = canDelete;
            this.canShare = canShare;
            this.canList = canList;
        }
    }

    final class RemoteRef {
        public final String ref;
        public final long size;
        public final String md5;
        public final String contentHash;
        public final String webUrl;

        public RemoteRef(String ref, long size, String md5, String contentHash, String webUrl) {
            this.ref = ref;
            this.size = size;
            this.md5 = md5;
            this.contentHash = contentHash;
            this.webUrl = webUrl;
        }
    }

    final class RemoteEntry {
        public final String name;
        public final String ref;
        public final long size;
        public final boolean folder;

        public RemoteEntry(String name, String ref, long size, boolean folder) {
            this.name = name;
            this.ref = ref;
            this.size = size;
            this.folder = folder;
        }
    }

    final class Quota {
        public final long used;
        public final long total;

        public Quota(long used, long total) {
            this.used = used;
            this.total = total;
        }
    }

    Caps caps();

    /** Creates or restores the remote session; must {@link UploadJob#saveState()} before any byte. */
    void begin(UploadJob job) throws DestError;

    /** Server-confirmed offset (0 for a fresh session). */
    long confirmed(UploadJob job) throws DestError;

    /** Chunk size for the next put, aligned to the protocol (S3 keeps one part size per upload). */
    int chunkSize(UploadJob job, long offset, int suggested);

    /** Uploads [offset, offset+len) and returns the new confirmed offset. */
    long put(UploadJob job, long offset, int len) throws DestError;

    RemoteRef finish(UploadJob job) throws DestError;

    /** Best-effort cleanup of an abandoned session (never throws). */
    void abandon(UploadJob job);

    /** Called once per transfer attempt, success or not: drop per-transfer connections. */
    default void release(UploadJob job) {
    }

    void delete(String remoteRef) throws DestError;

    /** Real round trip; returns a small JSON report (latency, account, notes). */
    JSONObject test() throws DestError;

    /** Null when the provider can't report it for this scope. */
    Quota quota() throws DestError;

    /** Null when unsupported. {@code anyone} creates a public link; otherwise an owner-only URL. */
    String shareLink(String remoteRef, boolean anyone) throws DestError;

    /** Restore listing of a folder path relative to the destination root. */
    List<RemoteEntry> list(String dirPath) throws DestError;

    byte[] download(String remoteRef, long maxBytes) throws DestError;
}
