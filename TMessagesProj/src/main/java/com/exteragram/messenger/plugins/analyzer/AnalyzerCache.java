package com.exteragram.messenger.plugins.analyzer;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * exteraGram 13.1.1 AnalyzerCache: analysis reports keyed by the plugin file's SHA-256 under
 * {@code noBackupFilesDir/plugin_analyzer/reports/<buildId>/<sha256>.json}. Each file is
 * "HMAC\njson" with an HMAC-SHA256 key kept in the AndroidKeyStore (alias plugin_analyzer_cache),
 * so a report written by anything else is ignored. Reports live 3 days; another analyzer build id
 * (new app version or new rules) drops the old reports.
 */
public final class AnalyzerCache {

    private static final String EXT = ".json";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "plugin_analyzer_cache";
    private static final long MAX_AGE_MS = 259200000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static volatile AnalyzerCache instance;

    private final File root;
    private SecretKey key;
    private boolean keyLoaded;
    private boolean pruned;

    public AnalyzerCache(File root) {
        this.root = root;
    }

    public static AnalyzerCache getInstance() {
        AnalyzerCache cache = instance;
        if (cache == null) {
            synchronized (AnalyzerCache.class) {
                cache = instance;
                if (cache == null) {
                    instance = cache = new AnalyzerCache(new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "plugin_analyzer/reports"));
                }
            }
        }
        return cache;
    }

    /** Analyzer build id: rules version + app build (exteraGram reads assets/plugin_analyzer/build_id). */
    public static String buildId() {
        return "sg" + PluginStaticAnalyzer.ANALYZER_VERSION + "-" + BuildVars.BUILD_VERSION_STRING;
    }

    public static String toHex(byte[] bytes) {
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int b = bytes[i] & 0xff;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 15];
        }
        return new String(out);
    }

    public static String sha256Hex(byte[] data) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    public static String sha256Hex(File file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                digest.update(buf, 0, n);
            }
        }
        return toHex(digest.digest());
    }

    public PluginStaticAnalyzer.Report getReport(String sha256) {
        final String json = get(buildId(), sha256);
        return json == null ? null : PluginStaticAnalyzer.Report.fromJson(json);
    }

    public void putReport(String sha256, PluginStaticAnalyzer.Report report) {
        final String json = report.toJson();
        if (json != null) put(buildId(), sha256, json);
    }

    public synchronized void prune(String buildId) {
        final File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.getName().equals(buildId)) deleteRecursively(file);
        }
    }

    public synchronized void expire() {
        expire(root, System.currentTimeMillis() - MAX_AGE_MS);
    }

    private static void expire(File dir, long before) {
        final File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) expire(f, before);
            else if (f.lastModified() < before) f.delete();
        }
    }

    private static void deleteRecursively(File file) {
        final File[] children = file.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        file.delete();
    }

    private synchronized void maintain(String buildId) {
        if (pruned) return;
        pruned = true;
        try {
            prune(buildId);
            expire();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public String get(String buildId, String sha256) {
        maintain(buildId);
        final File file = file(buildId, sha256);
        if (!file.isFile() || file.lastModified() < System.currentTimeMillis() - MAX_AGE_MS) return null;
        try {
            final String text = new String(readAll(file), StandardCharsets.UTF_8);
            final int nl = text.indexOf('\n');
            if (nl <= 0) return null;
            final String json = text.substring(nl + 1);
            final String sign = sign(buildId, sha256, json);
            if (sign == null) return null;
            if (MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8), text.substring(0, nl).getBytes(StandardCharsets.UTF_8))) {
                return json;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public void put(String buildId, String sha256, String json) {
        maintain(buildId);
        final String sign = sign(buildId, sha256, json);
        if (sign == null) return;
        try {
            final File file = file(buildId, sha256);
            final File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            final File tmp = new File(parent, file.getName() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write((sign + "\n" + json).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (Exception ignore) {
        }
    }

    private File file(String buildId, String sha256) {
        return new File(new File(root, buildId), sha256 + EXT);
    }

    /** HMAC-SHA256(buildId \n sha256 \n json) with the keystore key; null when no key. */
    String sign(String buildId, String sha256, String json) {
        final SecretKey secretKey = getKey();
        if (secretKey == null) return null;
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secretKey);
            mac.update((buildId + "\n" + sha256 + "\n").getBytes(StandardCharsets.UTF_8));
            return toHex(mac.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized SecretKey getKey() {
        if (!keyLoaded) {
            keyLoaded = true;
            key = loadKey();
        }
        return key;
    }

    private static SecretKey loadKey() {
        try {
            final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            final Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build());
            return generator.generateKey();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
