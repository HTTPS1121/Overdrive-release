package com.overdrive.app.safekeep.dest;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.UserInfo;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpStatVFS;
import com.overdrive.app.mqtt.ProxyHelper;
import com.overdrive.app.safekeep.SafeKeepSecrets;
import com.overdrive.app.safekeep.net.HttpFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SFTP to a NAS or storage box. The host key is pinned (trust-on-first-use via Test);
 * uploads write a hidden {@code .odpart} file that resumes by its remote size, then rename.
 */
final class SftpDestination implements Destination {

    private final String destId;
    private final String host;
    private final int port;
    private final String root;
    private final String pin;
    /** The pinned key itself (base64), so JSch negotiates that key type first. */
    private final String pinBlob;
    private final HttpFactory.ProxyMode proxyMode;
    static final String HOST_KEY_CHANGED = "SFTP host key changed";
    private final ConcurrentHashMap<Long, Conn> conns = new ConcurrentHashMap<>();

    static {
        JSch.setConfig("FingerprintHash", "SHA256");
    }

    private static final class Conn {
        final Session session;
        final ChannelSftp sftp;

        Conn(Session session, ChannelSftp sftp) {
            this.session = session;
            this.sftp = sftp;
        }

        void close() {
            try {
                sftp.disconnect();
            } catch (Throwable ignored) {
            }
            try {
                session.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    SftpDestination(String destId, JSONObject params, HttpFactory.ProxyMode proxyMode) {
        this.destId = destId;
        this.host = params.optString("host", "").trim();
        if (host.isEmpty()) throw new IllegalArgumentException("SFTP host required");
        this.port = params.optInt("port", 22);
        String r = params.optString("rootPath", "").trim();
        this.root = r.replaceAll("/+$", "");
        this.pin = params.optString("hostKeySha256", "").trim();
        this.pinBlob = params.optString("hostKeyBlob", "").trim();
        this.proxyMode = proxyMode;
    }

    @Override
    public Caps caps() {
        return new Caps("size", true, true, false, true);
    }

    private String abs(String rel) {
        String p = rel.replaceAll("^/+", "");
        return root.isEmpty() ? p : root + "/" + p;
    }

    /**
     * Accepts only the pinned key, inside key exchange: a server with any other key is refused
     * before a password or key signature is ever sent.
     */
    private static final class PinnedHostKeys implements HostKeyRepository {
        private final String pin;
        private final String blob;
        volatile String seen;
        volatile byte[] seenKey;

        PinnedHostKeys(String pin, String blob) {
            this.pin = pin;
            this.blob = blob == null ? "" : blob;
        }

        @Override
        public int check(String host, byte[] key) {
            seen = fingerprint(key);
            seenKey = key;
            if (pin.isEmpty()) return NOT_INCLUDED;
            return sameFingerprint(seen, pin) ? OK : CHANGED;
        }

        /** The pinned key, so a server offering several key types is asked for this one. */
        private HostKey[] pinned(String host, String type) {
            if (blob.isEmpty()) return new HostKey[0];
            try {
                HostKey k = new HostKey(host == null ? "" : host, java.util.Base64.getDecoder().decode(blob));
                if (type == null || type.equals(k.getType())) return new HostKey[]{k};
            } catch (Exception ignored) {
            }
            return new HostKey[0];
        }

        @Override public void add(HostKey hostkey, UserInfo ui) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "safekeep"; }
        @Override public HostKey[] getHostKey() { return pinned(null, null); }
        @Override public HostKey[] getHostKey(String host, String type) { return pinned(host, type); }
    }

    /** OpenSSH-style SHA256 fingerprint of a raw host key blob. */
    static String fingerprint(byte[] key) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** Test before a key is trusted: key exchange only, no credentials; returns {fingerprint, key blob}. */
    private String[] probeHostKey() throws DestError {
        DestError last = null;
        for (Proxy proxy : proxies()) {
            PinnedHostKeys repo = new PinnedHostKeys("", pinBlob);
            Session session = null;
            try {
                JSch jsch = new JSch();
                jsch.setHostKeyRepository(repo);
                session = jsch.getSession("safekeep-probe", host, port);
                session.setConfig("StrictHostKeyChecking", "yes");
                session.setTimeout(30_000);
                if (proxy != null) session.setProxy(proxy);
                session.connect(15_000);
            } catch (Exception e) {
                if (repo.seen != null && !repo.seen.isEmpty() && repo.seenKey != null) {
                    return new String[]{repo.seen, java.util.Base64.getEncoder().encodeToString(repo.seenKey)};
                }
                last = new DestError(DestError.Kind.NETWORK, "SFTP: " + e.getMessage());
            } finally {
                if (session != null) session.disconnect();
            }
        }
        throw last != null ? last : new DestError(DestError.Kind.NETWORK, "SFTP: no route");
    }

    /** Opens a session; the pinned host key is enforced during key exchange, before login. */
    private Conn open() throws DestError {
        if (pin.isEmpty()) {
            throw new DestError(DestError.Kind.PERMANENT, "SFTP host key not confirmed yet — run Test");
        }
        JSONObject s = SafeKeepSecrets.get(destId);
        List<Proxy> routes = proxies();
        DestError last = null;
        for (Proxy proxy : routes) {
            Session session = null;
            PinnedHostKeys repo = new PinnedHostKeys(pin, pinBlob);
            try {
                JSch jsch = new JSch();
                jsch.setHostKeyRepository(repo);
                String key = s.optString("privateKey", "");
                if (!key.isEmpty()) {
                    String pass = s.optString("passphrase", "");
                    jsch.addIdentity("safekeep", key.getBytes(StandardCharsets.UTF_8), null,
                            pass.isEmpty() ? null : pass.getBytes(StandardCharsets.UTF_8));
                }
                session = jsch.getSession(s.optString("username", ""), host, port);
                String pwd = s.optString("password", "");
                if (!pwd.isEmpty()) session.setPassword(pwd);
                session.setConfig("StrictHostKeyChecking", "yes");
                session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
                session.setServerAliveInterval(15_000);
                session.setTimeout(30_000);
                if (proxy != null) session.setProxy(proxy);
                session.connect(15_000);
                ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
                ch.connect(15_000);
                Conn c = new Conn(session, ch);
                lastFingerprint = repo.seen;
                return c;
            } catch (Exception e) {
                if (session != null) session.disconnect();
                if (repo.seen != null && !repo.seen.isEmpty() && !sameFingerprint(repo.seen, pin)) {
                    throw new DestError(DestError.Kind.PERMANENT,
                            HOST_KEY_CHANGED + " (" + repo.seen + "); confirm it in SafeKeep if expected");
                }
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("Auth fail") || msg.contains("auth")) {
                    throw new DestError(DestError.Kind.AUTH_EXPIRED, "SFTP login failed: " + msg);
                }
                last = new DestError(DestError.Kind.NETWORK, "SFTP: " + msg);
            }
        }
        throw last != null ? last : new DestError(DestError.Kind.NETWORK, "SFTP: no route");
    }

    private volatile String lastFingerprint;

    static boolean sameFingerprint(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String fp) {
        String f = fp == null ? "" : fp.trim();
        if (f.regionMatches(true, 0, "SHA256:", 0, 7)) f = f.substring(7);
        return f.replaceAll("=+$", "");
    }

    private List<Proxy> proxies() {
        List<Proxy> out = new ArrayList<>();
        if (HttpFactory.isTailnetHost(host)) {
            out.add(new ProxySOCKS5("127.0.0.1", ProxyHelper.getTailscaleProxyPort()));
            return out;
        }
        if (proxyMode == HttpFactory.ProxyMode.DIRECT || HttpFactory.isLanHost(host)) {
            out.add(null);
            return out;
        }
        java.net.Proxy p = ProxyHelper.getHttpProxy();
        if (p != null && p.type() != java.net.Proxy.Type.DIRECT && p.address() instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress a = (java.net.InetSocketAddress) p.address();
            out.add(p.type() == java.net.Proxy.Type.SOCKS
                    ? new ProxySOCKS5(a.getHostString(), a.getPort())
                    : new ProxyHTTP(a.getHostString(), a.getPort()));
        } else if (proxyMode == HttpFactory.ProxyMode.PROXY) {
            out.add(new ProxyHTTP("127.0.0.1", 1));
        }
        if (proxyMode == HttpFactory.ProxyMode.AUTO) out.add(null);
        return out;
    }

    private Conn conn(UploadJob job) throws DestError {
        Conn c = conns.get(job.itemId);
        if (c != null && c.session.isConnected() && c.sftp.isConnected()) return c;
        if (c != null) c.close();
        c = open();
        conns.put(job.itemId, c);
        return c;
    }

    @Override
    public void release(UploadJob job) {
        Conn c = conns.remove(job.itemId);
        if (c != null) c.close();
    }

    private static long size(ChannelSftp sftp, String path) throws SftpException {
        try {
            SftpATTRS a = sftp.stat(path);
            return a.getSize();
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return -1;
            throw e;
        }
    }

    private static void mkdirs(ChannelSftp sftp, String dir) throws SftpException {
        StringBuilder cur = new StringBuilder(dir.startsWith("/") ? "/" : "");
        for (String seg : dir.split("/")) {
            if (seg.isEmpty()) continue;
            if (cur.length() > 0 && cur.charAt(cur.length() - 1) != '/') cur.append('/');
            cur.append(seg);
            if (size(sftp, cur.toString()) < 0) {
                try {
                    sftp.mkdir(cur.toString());
                } catch (SftpException e) {
                    if (size(sftp, cur.toString()) < 0) throw e;
                }
            }
        }
    }

    private static DestError map(String ctx, SftpException e) {
        if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            return new DestError(DestError.Kind.PERMANENT, ctx + ": permission denied");
        }
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("no space") || m.contains("quota")) return new DestError(DestError.Kind.QUOTA_FULL, ctx + ": disk full");
        return new DestError(DestError.Kind.NETWORK, ctx + ": " + e.getMessage());
    }

    @Override
    public void begin(UploadJob job) throws DestError {
        Conn c = conn(job);
        try {
            if (job.state.optString("tmp", "").isEmpty()) {
                String target = abs(job.remotePath);
                String dir = target.contains("/") ? target.substring(0, target.lastIndexOf('/')) : "";
                if (!dir.isEmpty()) mkdirs(c.sftp, dir);
                // A per-session name: a new session (new file key after a retry) never appends to an old part.
                String tag = java.util.UUID.randomUUID().toString().substring(0, 8);
                String tmp = (dir.isEmpty() ? "" : dir + "/") + "." + Http.name(target) + "." + tag + ".odpart";
                job.state.put("tmp", tmp);
                job.saveState(0);
            }
        } catch (SftpException e) {
            throw map("SFTP prepare", e);
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public long confirmed(UploadJob job) throws DestError {
        try {
            ChannelSftp sftp = conn(job).sftp;
            long s = size(sftp, job.state.optString("tmp"));
            // The rename happened but its answer (or our checkpoint) was lost: the target is complete.
            if (s < 0 && job.state.optBoolean("committing", false) && size(sftp, abs(job.remotePath)) == job.length) {
                return job.length;
            }
            return Math.max(0, Math.min(s, job.length));
        } catch (SftpException e) {
            throw map("SFTP status", e);
        }
    }

    @Override
    public int chunkSize(UploadJob job, long offset, int suggested) {
        return suggested;
    }

    @Override
    public long put(UploadJob job, long offset, int len) throws DestError {
        Conn c = conn(job);
        String tmp = job.state.optString("tmp");
        try {
            long remote = Math.max(0, size(c.sftp, tmp));
            if (remote != offset) return Math.min(remote, job.length);
            try (OutputStream os = c.sftp.put(tmp, (com.jcraft.jsch.SftpProgressMonitor) null,
                    remote == 0 ? ChannelSftp.OVERWRITE : ChannelSftp.APPEND)) {
                job.copyChunk(offset, len, os);
            }
            return offset + len;
        } catch (SftpException e) {
            DestError err = fullDiskOr(c, job, offset, map("SFTP write", e));
            release(job);
            throw err;
        } catch (java.io.IOException e) {
            if (job.cancelled()) {
                release(job);
                throw DestError.aborted();
            }
            DestError err = fullDiskOr(c, job, offset, DestError.network(e));
            release(job);
            throw err;
        }
    }

    /** Servers report a full disk as a generic failure: ask how much room is left before retrying. */
    private DestError fullDiskOr(Conn c, UploadJob job, long offset, DestError fallback) {
        try {
            String target = abs(job.remotePath);
            String dir = target.contains("/") ? target.substring(0, target.lastIndexOf('/')) : ".";
            SftpStatVFS v = c.sftp.statVFS(dir);
            long frag = v.getFragmentSize() > 0 ? v.getFragmentSize() : v.getBlockSize();
            if (v.getBlocks() > 0 && v.getAvailBlocks() * frag < job.length - offset) {
                return new DestError(DestError.Kind.QUOTA_FULL, "SFTP: the server's disk is full", 0, 0);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    @Override
    public RemoteRef finish(UploadJob job) throws DestError {
        Conn c = conn(job);
        String target = abs(job.remotePath);
        String tmp = job.state.optString("tmp");
        try {
            job.state.put("committing", true);
            job.saveState(job.length);
        } catch (Exception ignored) {
        }
        try {
            if (size(c.sftp, tmp) < 0) {
                long existing = size(c.sftp, target);
                if (existing == job.length) return new RemoteRef(job.remotePath, existing, null, null, null);
            }
            if (size(c.sftp, target) >= 0) c.sftp.rm(target);
            c.sftp.rename(tmp, target);
            return new RemoteRef(job.remotePath, size(c.sftp, target), null, null, null);
        } catch (SftpException e) {
            throw map("SFTP finalize", e);
        }
    }

    @Override
    public void abandon(UploadJob job) {
        try {
            String tmp = job.state.optString("tmp", "");
            if (!tmp.isEmpty()) conn(job).sftp.rm(tmp);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void delete(String remoteRef) throws DestError {
        Conn c = open();
        try {
            if (size(c.sftp, abs(remoteRef)) >= 0) c.sftp.rm(abs(remoteRef));
        } catch (SftpException e) {
            throw map("SFTP delete", e);
        } finally {
            c.close();
        }
    }

    @Override
    public JSONObject test() throws DestError {
        long t0 = System.currentTimeMillis();
        JSONObject out = new JSONObject();
        if (pin.isEmpty()) {
            // Nothing is sent but the key exchange until the user confirms this key.
            return keyForConfirmation(out, false);
        }
        Conn c;
        try {
            c = open();
        } catch (DestError e) {
            // A changed key can be re-trusted from Test, behind a stronger warning.
            if (String.valueOf(e.getMessage()).startsWith(HOST_KEY_CHANGED)) return keyForConfirmation(out, true);
            throw e;
        }
        try {
            String fp = lastFingerprint;
            out.put("hostKey", fp);
            out.put("hostKeyPinned", true);
            String dir = abs("OverDrive");
            mkdirs(c.sftp, dir);
            String probe = dir + "/.overdrive-probe-" + System.currentTimeMillis();
            try (OutputStream os = c.sftp.put(probe)) {
                os.write("OverDrive SafeKeep test".getBytes(StandardCharsets.UTF_8));
            }
            c.sftp.rm(probe);
            out.put("latencyMs", System.currentTimeMillis() - t0);
        } catch (SftpException e) {
            throw map("SFTP test", e);
        } catch (Exception e) {
            throw DestError.network(e);
        } finally {
            c.close();
        }
        return out;
    }

    private JSONObject keyForConfirmation(JSONObject out, boolean changed) throws DestError {
        String[] k = probeHostKey();
        try {
            return out.put("hostKey", k[0]).put("hostKeyBlob", k[1]).put("hostKeyPinned", false)
                    .put("hostKeyChanged", changed);
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public Quota quota() throws DestError {
        Conn c = open();
        try {
            SftpStatVFS v = c.sftp.statVFS(root.isEmpty() ? "." : root);
            long frag = v.getFragmentSize() > 0 ? v.getFragmentSize() : v.getBlockSize();
            long total = v.getBlocks() * frag;
            long free = v.getFreeBlocks() * frag;
            return total > 0 ? new Quota(total - free, total) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            c.close();
        }
    }

    @Override
    public String shareLink(String remoteRef, boolean anyone) {
        return null;
    }

    @Override
    public List<RemoteEntry> list(String dirPath) throws DestError {
        List<RemoteEntry> out = new ArrayList<>();
        Conn c = open();
        try {
            Vector<?> v = c.sftp.ls(abs(dirPath));
            for (Object o : v) {
                ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
                String n = e.getFilename();
                if (".".equals(n) || "..".equals(n)) continue;
                out.add(new RemoteEntry(n, dirPath.replaceAll("/+$", "") + "/" + n, e.getAttrs().getSize(),
                        e.getAttrs().isDir()));
            }
        } catch (SftpException e) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw map("SFTP list", e);
        } finally {
            c.close();
        }
        return out;
    }

    @Override
    public byte[] download(String remoteRef, long maxBytes) throws DestError {
        Conn c = open();
        try {
            long s = size(c.sftp, abs(remoteRef));
            if (s > maxBytes) throw new DestError(DestError.Kind.PERMANENT, "file too large to restore");
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(0, s));
            c.sftp.get(abs(remoteRef), bos);
            return bos.toByteArray();
        } catch (SftpException e) {
            throw map("SFTP download", e);
        } finally {
            c.close();
        }
    }
}
