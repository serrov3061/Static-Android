package org.telegram.tgnet;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * StaticGram: discovers the current gramsrv address so the server can move to a
 * new IP without a client rebuild. Runs only on its own background threads,
 * never on the UI or tgnet network thread.
 *
 * Only signed DoH TXT records from _cfg.staticgram.top are applied as
 * candidates (native_sgVerifyCfgTxt). Unsigned A records for dc.staticgram.top
 * (system DNS / DoH) are never merged into that list: they lack the RSA check,
 * and native applyServerCandidates() switches to candidates[0], so a poisoned
 * A answer would become the live address. Cloudflare / non-public IPs are still
 * dropped via isPublicIpv4 on verified results. Empty resolve keeps / rotates
 * the baked-in fallback list in native code.
 */
public final class SgServerResolver {

    private static final String DOMAIN = "dc.staticgram.top";
    private static final String CFG_DOMAIN = "_cfg.staticgram.top";
    private static final String[] DOH_ENDPOINTS = {
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/resolve",
    };
    private static final int TIMEOUT_MS = 5000;
    private static final long MIN_INTERVAL_MS = 30_000;
    private static final long RESULT_TTL_MS = 10 * 60_000;

    private static final Object lock = new Object();
    private static boolean running;
    private static boolean pendingFailover;
    private static long lastStart;
    private static String[] lastResult;
    private static long lastResultTime;

    private static final ExecutorService mainExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SgServerResolver");
        t.setDaemon(true);
        return t;
    });
    // Separate pool for the system DNS call: InetAddress has no timeout and can hang.
    private static final ExecutorService dnsExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SgServerResolverDns");
        t.setDaemon(true);
        return t;
    });

    private SgServerResolver() {
    }

    /**
     * @param failover true when the current address keeps failing (pick a different one).
     */
    public static void request(boolean failover) {
        String[] reapply = null;
        synchronized (lock) {
            long now = SystemClock.elapsedRealtime();
            if (running) {
                // The running resolve is applied to every initialised account
                // when it finishes; just remember a failover request.
                pendingFailover |= failover;
                return;
            }
            if (lastStart != 0 && now - lastStart < MIN_INTERVAL_MS) {
                if (lastResult != null && now - lastResultTime < RESULT_TTL_MS) {
                    // No network: re-apply the fresh result. Covers accounts
                    // initialised after the last resolve, and lets a failover
                    // switch to another candidate / baked-in IP right away.
                    reapply = lastResult;
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("shuzagram: resolve skipped (debounce), failover=" + failover);
                    }
                    return;
                }
            } else {
                running = true;
                pendingFailover = false;
                lastStart = now;
            }
        }
        if (reapply != null) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("shuzagram: re-apply cached resolve (debounce), failover=" + failover);
            }
            apply(reapply, failover);
            return;
        }
        try {
            mainExecutor.execute(() -> run(failover));
        } catch (Throwable e) {
            FileLog.e(e);
            synchronized (lock) {
                running = false;
            }
        }
    }

    private static void run(boolean failover) {
        List<String> candidates = new ArrayList<>();
        boolean online = false;
        try {
            online = ApplicationLoader.isNetworkOnline();
            if (online) {
                // Unsigned A records are intentionally not applied. They used to
                // be merged ahead of signed TXT into the same candidate list that
                // native_sgApplyServerCandidates treats as ordered trust; a local
                // DNS poison then became candidates[0]. Keep the lookups only for
                // diagnostics when logs are on.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("shuzagram: system dns (untrusted, ignored) -> " + systemLookup());
                    for (String endpoint : DOH_ENDPOINTS) {
                        FileLog.d("shuzagram: doh A (untrusted, ignored) " + endpoint + " -> " + dohLookup(endpoint, DOMAIN, "A"));
                    }
                }
                // Signed DoH TXT only.
                for (String endpoint : DOH_ENDPOINTS) {
                    List<String> verified = new ArrayList<>();
                    for (String txt : dohLookup(endpoint, CFG_DOMAIN, "TXT")) {
                        String ip = null;
                        try {
                            ip = ConnectionsManager.native_sgVerifyCfgTxt(txt);
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                        if (ip != null && isPublicIpv4(ip)) {
                            verified.add(ip);
                        } else if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("shuzagram: TXT from " + endpoint + " rejected (bad signature/format)");
                        }
                    }
                    addAll(candidates, verified, "doh TXT " + endpoint);
                    if (!verified.isEmpty()) {
                        break;
                    }
                }
            } else if (BuildVars.LOGS_ENABLED) {
                FileLog.d("shuzagram: no network, skip resolve");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        String[] result = candidates.toArray(new String[0]);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("shuzagram: resolve done failover=" + failover + " candidates=" + candidates);
        }
        synchronized (lock) {
            if (online) {
                lastResult = result; // may be empty: native then keeps / rotates baked-in IPs
                lastResultTime = SystemClock.elapsedRealtime();
            } else {
                lastStart = 0; // nothing learned offline: let the next trigger resolve again
            }
            failover |= pendingFailover;
            running = false;
            pendingFailover = false;
        }
        // Empty result: native keeps the current address unless failover
        // (then it rotates through the baked-in list).
        apply(result, failover && online);
    }

    private static void apply(String[] ips, boolean failover) {
        try {
            ConnectionsManager.native_sgApplyServerCandidates(ips, failover);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void addAll(List<String> out, List<String> ips, String source) {
        for (String ip : ips) {
            if (isPublicIpv4(ip) && !out.contains(ip)) {
                out.add(ip);
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("shuzagram: " + source + " -> " + ips);
        }
    }

    private static List<String> systemLookup() {
        List<String> out = new ArrayList<>();
        Future<InetAddress[]> future = null;
        try {
            future = dnsExecutor.submit(() -> InetAddress.getAllByName(DOMAIN));
            InetAddress[] addresses = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (addresses != null) {
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address) {
                        out.add(address.getHostAddress());
                    }
                }
            }
        } catch (Throwable e) {
            if (future != null) {
                future.cancel(true);
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("shuzagram: system dns failed: " + e);
            }
        }
        return out;
    }

    /**
     * DNS JSON API (application/dns-json). Returns A addresses or TXT strings
     * (multi-string TXT records are concatenated, quotes removed).
     */
    private static List<String> dohLookup(String endpoint, String name, String type) {
        List<String> out = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint + "?name=" + name + "&type=" + type);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.addRequestProperty("accept", "application/dns-json");
            if (connection.getResponseCode() != 200) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("shuzagram: doh " + endpoint + " http " + connection.getResponseCode());
                }
                return out;
            }
            InputStream stream = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int read;
            while ((read = stream.read(data)) > 0) {
                buffer.write(data, 0, read);
                if (buffer.size() > 64 * 1024) {
                    break;
                }
            }
            stream.close();
            JSONObject json = new JSONObject(new String(buffer.toByteArray(), "UTF-8"));
            if (json.optInt("Status", -1) != 0) {
                return out;
            }
            JSONArray answers = json.optJSONArray("Answer");
            if (answers == null) {
                return out;
            }
            int wantType = "TXT".equals(type) ? 16 : 1;
            for (int i = 0; i < answers.length(); i++) {
                JSONObject answer = answers.optJSONObject(i);
                if (answer == null || answer.optInt("type") != wantType) {
                    continue;
                }
                String value = answer.optString("data", "");
                if (wantType == 16) {
                    value = unquoteTxt(value);
                }
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("shuzagram: doh " + endpoint + " " + type + " failed: " + e);
            }
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
        return out;
    }

    // Cloudflare returns "\"part1\" \"part2\"", Google returns the bare joined text.
    static String unquoteTxt(String value) {
        value = value.trim();
        if (!value.startsWith("\"")) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '\\' && inQuotes && i + 1 < value.length()) {
                sb.append(value.charAt(++i));
            } else if (inQuotes) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean isPublicIpv4(String ip) {
        if (ip == null) {
            return false;
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return false;
            }
            for (int j = 0; j < parts[i].length(); j++) {
                if (!Character.isDigit(parts[i].charAt(j))) {
                    return false;
                }
            }
            b[i] = Integer.parseInt(parts[i]);
            if (b[i] > 255) {
                return false;
            }
        }
        if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224) return false;
        if (b[0] == 169 && b[1] == 254) return false;
        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return false;
        if (b[0] == 192 && b[1] == 168) return false;
        if (b[0] == 100 && b[1] >= 64 && b[1] <= 127) return false;
        if (b[0] == 198 && (b[1] == 18 || b[1] == 19)) return false; // 198.18.0.0/15 fake-IP
        long v = ((long) b[0] << 24) | ((long) b[1] << 16) | ((long) b[2] << 8) | b[3];
        for (int i = 0; i < CLOUDFLARE_NETS.length; i += 2) {
            long net = CLOUDFLARE_NETS[i], bits = CLOUDFLARE_NETS[i + 1];
            long mask = (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            if ((v & mask) == net) {
                return false; // Cloudflare proxy edge: MTProto on 2398 does not pass
            }
        }
        return true;
    }

    // Cloudflare proxy ranges (network, prefix length). Must match tgnet ConnectionsManager.cpp.
    private static final long[] CLOUDFLARE_NETS = {
            0x68100000L, 13, // 104.16.0.0/13
            0x68180000L, 14, // 104.24.0.0/14
            0xAC400000L, 13, // 172.64.0.0/13
            0xBC726000L, 20, // 188.114.96.0/20
            0xA29E0000L, 15, // 162.158.0.0/15
            0x8D654000L, 18, // 141.101.64.0/18
            0x6CA2C000L, 18, // 108.162.192.0/18
            0xBE5DF000L, 20, // 190.93.240.0/20
            0xC5EAF000L, 22, // 197.234.240.0/22
            0xC6298000L, 17, // 198.41.128.0/17
            0xADF53000L, 20, // 173.245.48.0/20
            0x6715F400L, 22, // 103.21.244.0/22
            0x6716C800L, 22, // 103.22.200.0/22
            0x671F0400L, 22, // 103.31.4.0/22
            0x83004800L, 22, // 131.0.72.0/22
    };
}
