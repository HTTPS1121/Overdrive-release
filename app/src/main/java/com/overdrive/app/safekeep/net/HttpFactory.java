package com.overdrive.app.safekeep.net;

import com.overdrive.app.mqtt.ProxyHelper;
import com.overdrive.app.safekeep.util.StreamDigests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Proxy-aware OkHttp clients for SafeKeep. Every request re-reads the live proxy:
 * AUTO = proxy then direct, PROXY = proxy only (fails closed), DIRECT = never proxy.
 * LAN hosts always go direct; tailnet hosts go through the Tailscale SOCKS listener.
 */
public final class HttpFactory {

    public enum ProxyMode {
        AUTO, PROXY, DIRECT;

        public static ProxyMode parse(String s) {
            if ("proxy".equalsIgnoreCase(s)) return PROXY;
            if ("direct".equalsIgnoreCase(s)) return DIRECT;
            return AUTO;
        }
    }

    private static final Proxy DEAD_PROXY =
            new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1));

    private static volatile OkHttpClient base;
    private static volatile String lastRoute = "";

    private HttpFactory() {}

    private static OkHttpClient base() {
        OkHttpClient b = base;
        if (b != null) return b;
        synchronized (HttpFactory.class) {
            if (base == null) {
                base = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        // Needed to walk AUTO's proxy → direct route list. Upload bodies are one-shot,
                        // so OkHttp never replays one once sending has begun.
                        .retryOnConnectionFailure(true)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build();
            }
            return base;
        }
    }

    /** Client for a destination. {@code certSha256Pin} (hex) additionally trusts one leaf cert. */
    public static OkHttpClient client(ProxyMode mode, String certSha256Pin) {
        OkHttpClient.Builder b = base().newBuilder().proxySelector(new Selector(mode));
        if (certSha256Pin != null && !certSha256Pin.trim().isEmpty()) {
            try {
                PinTrustManager tm = new PinTrustManager(certSha256Pin.trim());
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, null);
                b.sslSocketFactory(ctx.getSocketFactory(), tm);
                b.hostnameVerifier(new PinHostnameVerifier(certSha256Pin.trim()));
            } catch (Exception ignored) {
                // Fall back to platform trust; the pinned host then fails TLS visibly.
            }
        }
        return b.build();
    }

    /** "proxy" or "direct" for the most recent SafeKeep connection route, for the status card. */
    public static String lastRoute() {
        return lastRoute;
    }

    static void noteRoute(Proxy p) {
        lastRoute = (p == null || p.type() == Proxy.Type.DIRECT) ? "direct" : "proxy";
    }

    private static final class Selector extends ProxySelector {
        private final ProxyMode mode;

        Selector(ProxyMode mode) {
            this.mode = mode;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            List<Proxy> out;
            if (isTailnetHost(host)) {
                out = Collections.singletonList(new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress("127.0.0.1", ProxyHelper.getTailscaleProxyPort())));
            } else if (mode == ProxyMode.DIRECT || isLanHost(host)) {
                out = Collections.singletonList(Proxy.NO_PROXY);
            } else {
                Proxy p = ProxyHelper.getHttpProxy();
                if (mode == ProxyMode.PROXY) {
                    out = Collections.singletonList(Proxy.NO_PROXY.equals(p) ? DEAD_PROXY : p);
                } else {
                    out = ProxyHelper.proxyRouteChain(p);
                }
            }
            noteRoute(out.get(0));
            return out;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (mode != ProxyMode.DIRECT) ProxyHelper.invalidateCache();
            if (mode == ProxyMode.AUTO) lastRoute = "direct";
        }
    }

    /** RFC1918 / link-local / ULA literals and home-network suffixes. No DNS lookups. */
    public static boolean isLanHost(String host) {
        return ProxyHelper.isLanHost(host);
    }

    /** Tailscale CGNAT range 100.64.0.0/10 or MagicDNS names. */
    public static boolean isTailnetHost(String host) {
        return ProxyHelper.isTailnetHost(host);
    }

    /**
     * Trust-on-first-use helper: completes a TLS handshake WITHOUT validating the chain and
     * returns the leaf certificate's SHA-256 plus its subject, so the user can pin it.
     */
    public static String[] fetchLeafCert(String url, ProxyMode mode) throws IOException {
        // The leaf is captured during the handshake: OkHttp's cleaned peer chain is empty for a
        // chain nothing trusts, which is exactly the self-signed case this exists for.
        final java.util.concurrent.atomic.AtomicReference<X509Certificate> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        X509TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {
                if (c != null && c.length > 0) seen.compareAndSet(null, c[0]);
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trustAll}, null);
            OkHttpClient c = base().newBuilder()
                    .proxySelector(new Selector(mode))
                    .sslSocketFactory(ctx.getSocketFactory(), trustAll)
                    .hostnameVerifier((h, s) -> true)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build();
            try (Response r = c.newCall(new Request.Builder().url(url).head().build()).execute()) {
                X509Certificate leaf = seen.get();
                if (leaf == null) throw new IOException("Server did not present a TLS certificate");
                return new String[]{sha256Hex(leaf.getEncoded()), leaf.getSubjectX500Principal().getName()};
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    static String sha256Hex(byte[] der) throws Exception {
        return StreamDigests.hex(MessageDigest.getInstance("SHA-256").digest(der));
    }

    private static boolean leafMatches(Certificate[] chain, String pin) {
        try {
            return chain != null && chain.length > 0
                    && sha256Hex(chain[0].getEncoded()).equalsIgnoreCase(pin.replace(":", ""));
        } catch (Exception e) {
            return false;
        }
    }

    private static final class PinTrustManager implements X509TrustManager {
        private final String pin;
        private final X509TrustManager platform;

        PinTrustManager(String pin) throws Exception {
            this.pin = pin;
            TrustManagerFactory f = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            f.init((java.security.KeyStore) null);
            X509TrustManager found = null;
            for (TrustManager t : f.getTrustManagers()) {
                if (t instanceof X509TrustManager) {
                    found = (X509TrustManager) t;
                    break;
                }
            }
            if (found == null) throw new IllegalStateException("No platform trust manager");
            this.platform = found;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            platform.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (leafMatches(chain, pin)) return;
            platform.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return platform.getAcceptedIssuers();
        }
    }

    private static final class PinHostnameVerifier implements HostnameVerifier {
        private final String pin;

        PinHostnameVerifier(String pin) {
            this.pin = pin;
        }

        @Override
        public boolean verify(String hostname, SSLSession session) {
            try {
                if (leafMatches(session.getPeerCertificates(), pin)) return true;
            } catch (Exception ignored) {
            }
            return HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session);
        }
    }
}
