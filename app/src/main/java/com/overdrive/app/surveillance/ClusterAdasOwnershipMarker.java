package com.overdrive.app.surveillance;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Own overlay recovery record; independent of the projection admission markers. */
public final class ClusterAdasOwnershipMarker {
    private static final String HEADER = "overdrive-cluster-overlay-v1";
    private final File file;

    public static final class Record {
        public final String bootId;
        public final boolean pending;
        Record(String bootId, boolean pending) {
            this.bootId = bootId;
            this.pending = pending;
        }
    }

    public ClusterAdasOwnershipMarker(File file) { this.file = file; }

    public ClusterAdasLifecycle.Ownership ownership(String bootId) {
        if (!validBootId(bootId)) return ClusterAdasLifecycle.Ownership.UNCERTAIN;
        Record r = read();
        if (r == null) return file.exists() ? ClusterAdasLifecycle.Ownership.UNCERTAIN
                : ClusterAdasLifecycle.Ownership.NONE;
        if (r.pending) {
            // Killing the sender does not recall a Binder transaction. A new
            // daemon in the same OS boot must not race a possibly late hide.
            if (r.bootId.equals(bootId) || !write(bootId, false)) {
                return ClusterAdasLifecycle.Ownership.UNCERTAIN;
            }
        }
        return ClusterAdasLifecycle.Ownership.OWNED;
    }

    public boolean acquire(String bootId) {
        if (!validBootId(bootId)) return false;
        if (file.exists()) {
            Record r = read();
            if (r == null || r.pending) return false;
        }
        return write(bootId, false);
    }

    /** Write ahead of every mutable operation, including each bounded frame batch. */
    public boolean begin(String bootId) {
        Record r = read();
        return validBootId(bootId) && r != null && !r.pending && write(bootId, true);
    }

    public boolean complete(String bootId) {
        Record r = read();
        return r != null && r.pending && r.bootId.equals(bootId) && write(bootId, false);
    }

    /** Only confirmed restoration may call this, and never with an in-flight command. */
    public boolean clear() {
        try {
            if (!file.exists()) return true;
            Record r = read();
            if (r == null || r.pending) return false;
            Files.deleteIfExists(file.toPath());
            return !file.exists();
        } catch (Throwable failure) {
            return false;
        }
    }

    public Record read() {
        try {
            if (!file.isFile() || file.length() <= 0 || file.length() > 256L) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] fields = text.split("\\n", -1);
            if (fields.length != 4 || !HEADER.equals(fields[0]) || !validBootId(fields[1])
                    || !("pending".equals(fields[2]) || "owned".equals(fields[2]))
                    || !fields[3].isEmpty()) return null;
            return new Record(fields[1], "pending".equals(fields[2]));
        } catch (Throwable failure) {
            return null;
        }
    }

    private boolean write(String bootId, boolean pending) {
        if (!validBootId(bootId)) return false;
        File tmp = null;
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            tmp = File.createTempFile(file.getName() + ".", ".tmp", parent);
            byte[] data = (HEADER + "\n" + bootId + "\n"
                    + (pending ? "pending" : "owned") + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException noAtomicMove) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Record back = read();
            return back != null && back.bootId.equals(bootId) && back.pending == pending;
        } catch (Throwable failure) {
            return false;
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp.toPath()); } catch (Throwable ignored) { }
            }
        }
    }

    private static boolean validBootId(String bootId) {
        return bootId != null && bootId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
