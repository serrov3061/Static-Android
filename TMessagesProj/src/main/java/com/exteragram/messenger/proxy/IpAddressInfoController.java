package com.exteragram.messenger.proxy;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * IP address info via ipwho.is (exteraGram 12.10.6, rewritten on HttpURLConnection).
 * <p>
 * StaticGram: the request is made ONLY by an explicit user action (menu item in a chat,
 * "look up" in the proxy alert). Only the public IP address being looked up is sent
 * (GET https://ipwho.is/&lt;ip&gt;); ipwho.is also sees the device's own IP of the request.
 * Private / loopback / link-local / multicast addresses are never sent.
 */
public final class IpAddressInfoController {

    public static final IpAddressInfoController INSTANCE = new IpAddressInfoController();

    private static final String API_URL = "https://ipwho.is/";
    private static final int TIMEOUT_MS = 4000;
    private static final long TTL_OK = 86400000L;
    private static final long TTL_FAIL = 300000L;

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-f:.]+$", Pattern.CASE_INSENSITIVE);

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IpAddressInfo");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 100;
        }
    };

    private IpAddressInfoController() {
    }

    public static final class IpAddressInfo {
        public final String ip;
        public final String country;
        public final String countryCode;
        public final String region;
        public final String city;
        public final String isp;
        public final String organization;
        public final String domain;
        public final String timezone;

        public IpAddressInfo(String ip, String country, String countryCode, String region, String city, String isp, String organization, String domain, String timezone) {
            this.ip = ip;
            this.country = country;
            this.countryCode = countryCode;
            this.region = region;
            this.city = city;
            this.isp = isp;
            this.organization = organization;
            this.domain = domain;
            this.timezone = timezone;
        }

        @Override
        public String toString() {
            return "IpAddressInfo(ip=" + ip + ", country=" + country + ", countryCode=" + countryCode + ", region=" + region + ", city=" + city + ", isp=" + isp + ", organization=" + organization + ", domain=" + domain + ", timezone=" + timezone + ")";
        }
    }

    private static final class CacheEntry {
        final IpAddressInfo info;
        final long time;
        final long ttl;

        CacheEntry(IpAddressInfo info, long time, long ttl) {
            this.info = info;
            this.time = time;
            this.ttl = ttl;
        }
    }

    /** Cancellable lookup handle (replacement for okhttp3.Call). */
    public static final class Request implements Runnable {
        private volatile boolean canceled;
        private volatile HttpURLConnection connection;

        public boolean isCanceled() {
            return canceled;
        }

        public void cancel() {
            canceled = true;
            HttpURLConnection c = connection;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }

        @Override
        public void run() {
            cancel();
        }
    }

    /** Returns the normalized public IP address if {@code url} host is a literal public IP, else null. No network. */
    public static String extractIpAddress(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        String host = null;
        try {
            host = Uri.parse(url).getHost();
            if (host == null && !url.contains("://")) {
                host = Uri.parse("http://" + url).getHost();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (!isIpAddress(host)) {
            return null;
        }
        try {
            // literal address: no DNS lookup is performed
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                return null;
            }
            return address.getHostAddress();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Looks the address up on ipwho.is. Callback runs on a background thread (or synchronously when cached),
     * with {@code null} on failure. Returns a cancel handle or null when the answer came from the cache.
     */
    public static Request requestIpAddressInfo(final String ipAddress, final Utilities.Callback<IpAddressInfo> callback) {
        CacheEntry cached = getCachedInfo(ipAddress);
        if (cached != null) {
            callback.run(cached.info);
            return null;
        }
        if (!isIpAddress(ipAddress)) {
            callback.run(null);
            return null;
        }
        final Request request = new Request();
        executor.execute(() -> {
            if (request.isCanceled()) {
                return;
            }
            IpAddressInfo info = null;
            boolean failed = false;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(API_URL + Uri.encode(ipAddress, ":."));
                connection = (HttpURLConnection) url.openConnection();
                request.connection = connection;
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    info = parseIpAddressInfo(ipAddress, readAll(connection.getInputStream()));
                }
            } catch (Exception e) {
                if (!request.isCanceled()) {
                    FileLog.e(e);
                }
                failed = true;
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (request.isCanceled()) {
                return;
            }
            putCachedInfo(ipAddress, failed ? null : info);
            callback.run(info);
        });
        return request;
    }

    public static String joinInfo(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }

    private static boolean isIpAddress(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return IPV6_CHARS.matcher(host).matches();
        }
        if (!IPV4.matcher(host).matches()) {
            return false;
        }
        for (String part : host.split("\\.")) {
            int v = Utilities.parseInt(part);
            if (v < 0 || v >= 256) {
                return false;
            }
        }
        return true;
    }

    private static CacheEntry getCachedInfo(String ipAddress) {
        synchronized (cache) {
            CacheEntry entry = cache.get(ipAddress);
            if (entry == null) {
                return null;
            }
            if (SystemClock.elapsedRealtime() - entry.time > entry.ttl) {
                cache.remove(ipAddress);
                return null;
            }
            return entry;
        }
    }

    private static void putCachedInfo(String ipAddress, IpAddressInfo info) {
        synchronized (cache) {
            cache.put(ipAddress, new CacheEntry(info, SystemClock.elapsedRealtime(), info != null ? TTL_OK : TTL_FAIL));
        }
    }

    private static IpAddressInfo parseIpAddressInfo(String ipAddress, String response) {
        try {
            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("success", false)) {
                return null;
            }
            JSONObject connection = json.optJSONObject("connection");
            JSONObject timezone = json.optJSONObject("timezone");
            return new IpAddressInfo(
                    json.optString("ip", ipAddress),
                    json.optString("country", ""),
                    json.optString("country_code", ""),
                    json.optString("region", ""),
                    json.optString("city", ""),
                    connection != null ? connection.optString("isp", "") : "",
                    connection != null ? connection.optString("org", "") : "",
                    connection != null ? connection.optString("domain", "") : "",
                    timezone != null ? timezone.optString("id", "") : ""
            );
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total > 256 * 1024) {
                    break;
                }
            }
            return out.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
