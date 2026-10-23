package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.Ledger;
import com.overdrive.app.safekeep.SafeKeepConfig;
import com.overdrive.app.safekeep.SafeKeepSecrets;
import com.overdrive.app.safekeep.dest.s3.S3Destination;
import com.overdrive.app.safekeep.net.HttpFactory;

import org.json.JSONObject;

import java.util.Iterator;

/** Builds a driver for a configured destination; the signature says when to rebuild it. */
public final class DestinationFactory {

    private DestinationFactory() {}

    public static Destination create(SafeKeepConfig.Destination d, Ledger ledger) throws Exception {
        HttpFactory.ProxyMode mode = HttpFactory.ProxyMode.parse(d.proxyMode);
        String pin = d.params.optString("certSha256", "");
        switch (d.type) {
            case "gdrive":
                return new GoogleDriveDestination(d.id, HttpFactory.client(mode, null), ledger);
            case "onedrive":
                return new OneDriveDestination(d.id, HttpFactory.client(mode, null));
            case "dropbox":
                return new DropboxDestination(d.id, HttpFactory.client(mode, null));
            case "s3":
                return new S3Destination(d.id, d.params, HttpFactory.client(mode, pin));
            case "webdav":
            case "nextcloud":
                return new WebDavDestination(d.id, d.params, HttpFactory.client(mode, pin), ledger);
            case "sftp":
                return new SftpDestination(d.id, d.params, mode);
            default:
                return null;
        }
    }

    /** Changes when anything a driver captures at construction changes (not token refreshes). */
    public static String signature(SafeKeepConfig.Destination d) {
        StringBuilder sb = new StringBuilder(d.type).append('|').append(d.proxyMode).append('|')
                .append(d.params.toString()).append('|');
        JSONObject s = SafeKeepSecrets.get(d.id);
        Iterator<String> it = s.keys();
        java.util.TreeMap<String, String> stable = new java.util.TreeMap<>();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equals("accessToken") || k.equals("expiresAt") || k.equals("refreshedAt")) continue;
            stable.put(k, String.valueOf(s.opt(k)));
        }
        sb.append(stable.toString().hashCode());
        return sb.toString();
    }
}
