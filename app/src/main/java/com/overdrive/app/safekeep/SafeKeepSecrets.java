package com.overdrive.app.safekeep;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-destination secrets (OAuth tokens, passwords, keys) in their own config section,
 * one {@code ENC:} blob per destination. Writes fail closed: a secret is never stored in
 * clear text. The section is listed in ConfigBackupService's credential sections.
 */
public final class SafeKeepSecrets {

    public static final String SECTION = "safeKeepCredentials";
    private static final int SHELL_DAEMON_UID = 2000;

    private static final ConcurrentHashMap<String, JSONObject> cache = new ConcurrentHashMap<>();
    private static volatile String lastSectionSig;

    private SafeKeepSecrets() {}

    /** A stored blob that could not be decrypted (e.g. a transient device-id read failure). */
    private static final JSONObject UNREADABLE = new JSONObject();

    /** Decrypted secrets, {@link #UNREADABLE} when a blob exists but won't open. Only good reads are cached. */
    private static JSONObject load(String destId) {
        JSONObject c = cache.get(destId);
        if (c != null) return c;
        try {
            JSONObject s = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            String blob = s == null ? "" : s.optString(destId, "");
            if (blob.isEmpty()) {
                c = new JSONObject();
            } else {
                String plain = CredentialCipher.decrypt(blob);
                if (plain == null || plain.isEmpty()) return UNREADABLE;
                c = new JSONObject(plain);
            }
        } catch (Exception e) {
            return UNREADABLE;
        }
        cache.put(destId, c);
        return c;
    }

    /** Decrypted secrets for a destination (a copy; never null). */
    public static JSONObject get(String destId) {
        JSONObject c = load(destId);
        try {
            return new JSONObject(c.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Merges {@code updates} into the destination's secrets. Returns false (nothing stored) on
     * failure, including when the stored blob can't be read: merging into an empty copy would
     * overwrite the good one.
     */
    public static synchronized boolean merge(String destId, JSONObject updates) {
        JSONObject loaded = load(destId);
        if (loaded == UNREADABLE) return false;
        JSONObject cur;
        try {
            cur = new JSONObject(loaded.toString());
        } catch (Exception e) {
            return false;
        }
        try {
            Iterator<String> it = updates.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = updates.opt(k);
                if (v == null || JSONObject.NULL.equals(v)) cur.remove(k);
                else cur.put(k, v);
            }
        } catch (Exception e) {
            return false;
        }
        return write(destId, cur);
    }

    public static synchronized boolean put(String destId, JSONObject secrets) {
        return write(destId, secrets == null ? new JSONObject() : secrets);
    }

    private static boolean write(String destId, JSONObject secrets) {
        String enc = CredentialCipher.encrypt(secrets.toString());
        if (!CredentialCipher.isEncrypted(enc)) return false;
        try {
            boolean ok = UnifiedConfigManager.updateSection(SECTION, new JSONObject().put(destId, enc));
            if (ok) cache.put(destId, new JSONObject(secrets.toString()));
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** Removes a destination's secrets entirely (read-modify-write of the whole section). */
    public static synchronized boolean remove(String destId) {
        cache.remove(destId);
        try {
            if (android.os.Process.myUid() != SHELL_DAEMON_UID) return false;
            return UnifiedConfigManager.runUnderConfigLock(() -> {
                try {
                    JSONObject s = UnifiedConfigManager.forceReload().optJSONObject(SECTION);
                    if (s == null || !s.has(destId)) return true;
                    JSONObject copy = new JSONObject(s.toString());
                    copy.remove(destId);
                    return UnifiedConfigManager.replaceSection(SECTION, copy);
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            return false;
        }
    }

    public static void invalidate() {
        cache.clear();
    }

    /** A whole-config write (restore) replaced the section: drop cached copies if it changed. */
    public static void invalidateIfChanged(JSONObject section) {
        String sig = section == null ? "" : section.toString();
        if (!sig.equals(lastSectionSig)) {
            lastSectionSig = sig;
            cache.clear();
        }
    }
}
