package com.exteragram.messenger.adblock.backend;

import android.content.SharedPreferences;
import android.util.Base64;

import com.exteragram.messenger.backup.PreferencesUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads filter lists into {@code files/adblock/<base64(url)>.txt} and keeps their metadata
 * in the "ublock_subscriptions" preferences (same layout as exteraGram).
 */
public class SubscriptionsManager {

    private static final long DEFAULT_EXPIRATION = TimeUnit.DAYS.toMillis(5);
    private static final Pattern redirectPattern = Pattern.compile("!\\s*Redirect:\\s*(\\S+)");

    private static volatile SubscriptionsManager instance;

    private final Object lock = new Object();
    private final DispatchQueue queue = new DispatchQueue("SubscriptionsManager");
    private final SharedPreferences prefs = PreferencesUtils.getPreferences("ublock_subscriptions");

    public static SubscriptionsManager getInstance() {
        SubscriptionsManager local = instance;
        if (local == null) {
            synchronized (SubscriptionsManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new SubscriptionsManager();
                }
            }
        }
        return local;
    }

    private File getFileForUrl(String url) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "adblock");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE) + ".txt");
    }

    public void update(String[] urls, Runnable onDone) {
        queue.postRunnable(() -> {
            LinkedHashSet<String> toFetch = new LinkedHashSet<>();
            long now = System.currentTimeMillis();
            for (FilterMetadata metadata : getSubscriptions()) {
                if (now >= metadata.expires || !getFileForUrl(metadata.url).exists()) {
                    toFetch.add(metadata.url);
                }
            }
            synchronized (lock) {
                for (String url : urls) {
                    if (!prefs.contains("metadata_" + url) && !prefs.contains("redirect_" + url)) {
                        toFetch.add(url);
                    }
                }
            }
            for (String url : toFetch) {
                fetchSubscription(url, 0);
            }
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    public boolean hasFilters() {
        return !getSubscriptionFilePaths().isEmpty();
    }

    private void unsubscribe(String url) {
        synchronized (lock) {
            prefs.edit().remove("metadata_" + url).apply();
        }
        File file = getFileForUrl(url);
        if (file.exists()) {
            file.delete();
        }
    }

    public List<FilterMetadata> getSubscriptions() {
        ArrayList<FilterMetadata> result = new ArrayList<>();
        synchronized (lock) {
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("metadata_")) {
                    try {
                        String json = prefs.getString(key, null);
                        if (json != null) {
                            result.add(FilterMetadata.fromJson(new JSONObject(json)));
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<String> getSubscriptionFilePaths() {
        ArrayList<String> paths = new ArrayList<>();
        for (FilterMetadata metadata : getSubscriptions()) {
            File file = getFileForUrl(metadata.url);
            if (file.exists()) {
                paths.add(file.getAbsolutePath());
            }
        }
        return paths;
    }

    private boolean fetchSubscription(String url, int depth) {
        try {
            AdBlockHttp.Response response = AdBlockHttp.get(url);
            if (!response.isSuccessful()) {
                return false;
            }
            String content = new String(response.body, StandardCharsets.UTF_8);
            String redirect = extractRedirect(content);
            if (redirect != null) {
                if (depth < 3 && fetchSubscription(redirect, depth + 1)) {
                    unsubscribe(url);
                    synchronized (lock) {
                        prefs.edit().putString("redirect_" + url, redirect).apply();
                    }
                    return true;
                }
                return false;
            }
            FilterMetadata metadata = parseMetadata(url, content);
            File file = getFileForUrl(url);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            synchronized (lock) {
                prefs.edit().putString("metadata_" + url, metadata.toJson().toString()).apply();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractRedirect(String content) {
        Matcher matcher = redirectPattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private FilterMetadata parseMetadata(String url, String content) {
        String title = extractMetadataValue(content, "Title");
        String homepage = extractMetadataValue(content, "Homepage");
        return new FilterMetadata(url, title != null ? title : "Unnamed list", homepage != null ? homepage : "", countRules(content), calculateExpiration(content));
    }

    private String extractMetadataValue(String content, String key) {
        Matcher matcher = Pattern.compile("!\\s*" + key + ":\\s*(.+)").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private int countRules(String content) {
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("!")) {
                count++;
            }
        }
        return count;
    }

    private long calculateExpiration(String content) {
        String value = extractMetadataValue(content, "Expires");
        if (value != null) {
            try {
                int amount = Integer.parseInt(value.replaceAll("[^0-9]", ""));
                if (value.contains("hour")) {
                    return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(amount);
                }
                if (value.contains("day")) {
                    return System.currentTimeMillis() + TimeUnit.DAYS.toMillis(amount);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return System.currentTimeMillis() + DEFAULT_EXPIRATION;
    }

    public static class FilterMetadata {
        public final String url;
        public final String title;
        public final String homepage;
        public final int rulesCount;
        public final long expires;

        private FilterMetadata(String url, String title, String homepage, int rulesCount, long expires) {
            this.url = url;
            this.title = title;
            this.homepage = homepage;
            this.rulesCount = rulesCount;
            this.expires = expires;
        }

        private static FilterMetadata fromJson(JSONObject json) throws JSONException {
            return new FilterMetadata(json.getString("url"), json.getString("title"), json.getString("homepage"), json.getInt("rulesCount"), json.getLong("expires"));
        }

        private JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("url", url);
            json.put("title", title);
            json.put("homepage", homepage);
            json.put("rulesCount", rulesCount);
            json.put("expires", expires);
            return json;
        }
    }
}
