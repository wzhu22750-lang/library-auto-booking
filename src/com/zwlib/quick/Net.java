package com.zwlib.quick;

import android.util.Base64;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP + cookie jar.
 *
 * The cookie jar is explicit (not android.net.CookieHandler) because the whole SSO
 * refresh has to work from a background BroadcastReceiver, where touching WebView's
 * CookieManager is only done to sync what we already carry.
 */
final class Net {

    static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Request signing key, mirroring the site's axios interceptor:
     *   X-request-id       = uuid4
     *   X-request-date     = epoch millis
     *   X-hmac-request-key = hex(HMAC_SHA256("seat::" + id + "::" + ms + "::" + METHOD, key))
     * The key itself is handed out by the public getSysSet endpoint as an
     * AES-CBC-wrapped blob (key/iv are literal strings in the bundle), so it is
     * fetched and unwrapped at runtime instead of being hardcoded here.
     */
    static volatile String signKey;

    /** last response, for short diagnostic codes in the UI */
    static volatile int lastHttp = -1;
    static volatile boolean lastJson = false;
    static volatile String lastBody = "";
    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static final class Resp {
        int code = -1;
        String body = "";
        String url = "";
        String location;
        boolean ok() { return code >= 200 && code < 300; }
    }

    /** host -> (cookie name -> value) */
    static final class Jar {
        final Map<String, Map<String, String>> byHost = new HashMap<String, Map<String, String>>();

        private Map<String, String> bucket(String url) {
            String host = hostOf(url);
            Map<String, String> m = byHost.get(host);
            if (m == null) {
                m = new HashMap<String, String>();
                byHost.put(host, m);
            }
            return m;
        }

        String header(String url) {
            Map<String, String> m = byHost.get(hostOf(url));
            if (m == null || m.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        void absorb(String url, String setCookie) {
            if (setCookie == null) {
                return;
            }
            int semi = setCookie.indexOf(';');
            String pair = (semi >= 0 ? setCookie.substring(0, semi) : setCookie).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                return;
            }
            bucket(url).put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }

        /** seed from the live WebView jar (foreground only) */
        void seed(String url) {
            try {
                String c = CookieManager.getInstance().getCookie(url);
                if (c == null) {
                    return;
                }
                for (String part : c.split(";")) {
                    absorb(url, part);
                }
            } catch (Throwable ignored) {
            }
        }

        /** push everything back so the WebView shares the refreshed session */
        void pushToWebView(String url) {
            try {
                Map<String, String> m = byHost.get(hostOf(url));
                if (m == null) {
                    return;
                }
                for (Map.Entry<String, String> e : m.entrySet()) {
                    CookieManager.getInstance().setCookie(url, e.getKey() + "=" + e.getValue());
                }
                CookieManager.getInstance().flush();
            } catch (Throwable ignored) {
            }
        }

        String toJson() {
            JSONObject root = new JSONObject();
            try {
                for (Map.Entry<String, Map<String, String>> e : byHost.entrySet()) {
                    JSONObject o = new JSONObject();
                    for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                        o.put(c.getKey(), c.getValue());
                    }
                    root.put(e.getKey(), o);
                }
            } catch (Exception ignored) {
            }
            return root.toString();
        }

        static Jar fromJson(String s) {
            Jar j = new Jar();
            if (s == null || s.length() < 3) {
                return j;
            }
            try {
                JSONObject root = new JSONObject(s);
                for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String host = it.next();
                    JSONObject o = root.optJSONObject(host);
                    if (o == null) {
                        continue;
                    }
                    Map<String, String> m = new HashMap<String, String>();
                    for (Iterator<String> k = o.keys(); k.hasNext(); ) {
                        String n = k.next();
                        m.put(n, o.optString(n));
                    }
                    j.byHost.put(host, m);
                }
            } catch (Exception ignored) {
            }
            return j;
        }

        static String hostOf(String url) {
            try {
                return new URL(url).getHost();
            } catch (Exception e) {
                return url;
            }
        }
    }

    static Resp request(String method, String url, String token, String json, Jar jar) {
        return request(method, url, token, json, jar, true);
    }

    static Resp request(String method, String url, String token, String json, Jar jar, boolean follow) {
        Resp r = hop(method, url, token, json, jar);
        int n = 0;
        while (follow && r.code >= 300 && r.code < 400 && r.location != null && n++ < 8) {
            String next = absolute(r.url, r.location);
            if (next == null) {
                break;
            }
            r = hop("GET", next, token, null, jar);
        }
        return r;
    }

    static Resp postJson(String url, String token, String json, Jar jar) {
        return request("POST", url, token, json == null ? "{}" : json, jar);
    }

    private static Resp hop(String method, String url, String token, String json, Jar jar) {
        Resp r = new Resp();
        r.url = url;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("loginType", "PC");
            c.setRequestProperty("Origin", "https://zwlib.ruc.edu.cn");
            c.setRequestProperty("Referer", "https://zwlib.ruc.edu.cn/jsq-v/");
            if (jar != null) {
                String ck = jar.header(url);
                if (ck != null) {
                    c.setRequestProperty("Cookie", ck);
                }
            }
            if (token != null) {
                c.setRequestProperty("token", token);
            }
            sign(c, method);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                OutputStream os = c.getOutputStream();
                os.write(json.getBytes(UTF8));
                os.close();
            }
            r.code = c.getResponseCode();
            r.location = c.getHeaderField("Location");
            if (jar != null) {
                Map<String, List<String>> heads = c.getHeaderFields();
                for (Map.Entry<String, List<String>> e : heads.entrySet()) {
                    if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                        for (String v : e.getValue()) {
                            jar.absorb(url, v);
                        }
                    }
                }
            }
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = read(in);
            lastHttp = r.code;
            String t = r.body == null ? "" : r.body.trim();
            lastJson = t.startsWith("{") || t.startsWith("[");
            lastBody = t.length() > 200 ? t.substring(0, 200) : t;
        } catch (Exception e) {
            r.code = -1;
            r.body = String.valueOf(e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    private static void sign(HttpURLConnection c, String method) {
        String k = signKey;
        if (k == null || k.isEmpty()) {
            return;
        }
        try {
            String rid = UUID.randomUUID().toString();
            long ts = System.currentTimeMillis();
            c.setRequestProperty("X-request-id", rid);
            c.setRequestProperty("X-request-date", String.valueOf(ts));
            c.setRequestProperty("X-hmac-request-key", hmacSignature(k, rid, ts, method));
        } catch (Throwable ignored) {
        }
    }

    /** Pure: the exact string the site signs, then HMAC-SHA256 as lowercase hex. */
    static String hmacSignature(String key, String requestId, long timestamp, String method) {
        String msg = "seat::" + requestId + "::" + timestamp + "::" + method.toUpperCase();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(UTF8), "HmacSHA256"));
            byte[] out = mac.doFinal(msg.getBytes(UTF8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** I.decrypt(hmacKey) from the bundle: AES-CBC with literal key/iv. */
    static String unwrapKey(String b64) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec("server_date_time".getBytes(UTF8), "AES"),
                    new IvParameterSpec("client_date_time".getBytes(UTF8)));
            byte[] pt = c.doFinal(Base64.decode(b64, Base64.DEFAULT));
            return new String(pt, UTF8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String absolute(String base, String loc) {
        try {
            if (loc.startsWith("http://") || loc.startsWith("https://")) {
                return loc;
            }
            URL b = new URL(base);
            if (loc.startsWith("/")) {
                return b.getProtocol() + "://" + b.getHost()
                        + (b.getPort() > 0 ? ":" + b.getPort() : "") + loc;
            }
            String path = b.getPath();
            int slash = path.lastIndexOf('/');
            return b.getProtocol() + "://" + b.getHost()
                    + (b.getPort() > 0 ? ":" + b.getPort() : "")
                    + (slash >= 0 ? path.substring(0, slash + 1) : "/") + loc;
        } catch (Exception e) {
            return null;
        }
    }

    static String read(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) {
                    break;
                }
            }
            return new String(bos.toByteArray(), UTF8);
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** pulls ?token=xxx out of a URL (the SSO round trip ends on one) */
    static String tokenFromUrl(String url) {
        try {
            int q = url.indexOf('?');
            if (q < 0) {
                return null;
            }
            String query = url.substring(q + 1);
            int hash = query.indexOf('#');
            if (hash >= 0) {
                query = query.substring(0, hash);
            }
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "token".equals(kv.substring(0, eq))) {
                    return kv.substring(eq + 1);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
