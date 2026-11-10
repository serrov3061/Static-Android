package com.exteragram.messenger.plugins.catalog;

import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Index ("Git") plugin repositories (exteraGram 13.1.1 catalog/GitPluginRepository).
 * A repository is a JSON index (see docs/plugins-catalog-index.md): a GitHub repository with
 * extera-plugins.json at its root (or in a /tree/branch/path folder), or any HTTPS URL of a .json index.
 * The built-in StaticGram catalog is such an index served by @PluginsBot.
 */
public final class GitPluginRepository {

    public static final String INDEX_FILE_NAME = "extera-plugins.json";
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_INDEX_SIZE = 1024 * 1024;
    public static final int MAX_CATEGORIES = 3;
    /** StaticGram: PluginGuard checks files up to 16 MiB only, bigger entries could never be installed. */
    public static final long MAX_FILE_SIZE = 16L << 20;
    private static final int BUFFER_SIZE = 16384;

    private static final Pattern GITHUB_URL = Pattern.compile("^github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?(?:/tree/([^/]+)(/.+)?)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_BLOB_URL = Pattern.compile("^(github\\.com/[^/]+/[^/]+)/blob/(.+)/" + Pattern.quote(INDEX_FILE_NAME) + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private GitPluginRepository() {
    }

    public static final class IndexPlugin {
        public final String id;
        public final String name;
        public final String version;
        public final String fileUrl;
        public final String fileName;
        public final String sha256;
        public final long size;
        public final String description;
        public final String author;
        public final String icon;
        public final String appVersion;
        public final String sdkVersion;
        public final String requirements;
        public final String categories;
        public final Integer updatedAt;
        /** StaticGram extension: optional download counter, drives the "Popular" sort. */
        public final int downloads;
        /** StaticGram extension (docs §8): the "s<id>" source of sources.json the plugin belongs to, or null. */
        public final String source;

        IndexPlugin(String id, String name, String version, String fileUrl, String fileName, String sha256, long size, String description, String author, String icon, String appVersion, String sdkVersion, String requirements, String categories, Integer updatedAt, int downloads, String source) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.fileUrl = fileUrl;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.size = size;
            this.description = description;
            this.author = author;
            this.icon = icon;
            this.appVersion = appVersion;
            this.sdkVersion = sdkVersion;
            this.requirements = requirements;
            this.categories = categories;
            this.updatedAt = updatedAt;
            this.downloads = downloads;
            this.source = source;
        }
    }

    public static final class Index {
        public final String name;
        public final String homepage;
        public final List<IndexPlugin> plugins;
        /** StaticGram extension: optional Telegram channel username whose avatar represents the repository. */
        public final String channel;

        Index(String name, String homepage, List<IndexPlugin> plugins, String channel) {
            this.name = name;
            this.homepage = homepage;
            this.plugins = plugins;
            this.channel = channel;
        }
    }

    public static abstract class IndexResult {
        private IndexResult() {
        }

        public static final class Loaded extends IndexResult {
            public final Index index;
            public final String etag;

            Loaded(Index index, String etag) {
                this.index = index;
                this.etag = etag;
            }
        }

        public static final IndexResult NOT_MODIFIED = new IndexResult() {
            @Override
            public String toString() {
                return "NotModified";
            }
        };
        /** The index declares a newer "schema" than this client understands. */
        public static final IndexResult UNSUPPORTED = new IndexResult() {
            @Override
            public String toString() {
                return "Unsupported";
            }
        };
        public static final IndexResult FAILED = new IndexResult() {
            @Override
            public String toString() {
                return "Failed";
            }
        };
    }

    /** StaticGram extension (docs §8): one channel repository listed in sources.json. */
    public static final class Source {
        /** "s<number>", stable and unique. */
        public final String id;
        public final String title;
        /** Channel username without "@", or null. */
        public final String channel;
        public final int plugins;
        /** Absolute HTTPS URL of the source index (same host as sources.json). */
        public final String indexUrl;
        public final String updatedAt;

        Source(String id, String title, String channel, int plugins, String indexUrl, String updatedAt) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.plugins = plugins;
            this.indexUrl = indexUrl;
            this.updatedAt = updatedAt;
        }
    }

    public static abstract class SourcesResult {
        private SourcesResult() {
        }

        public static final class Loaded extends SourcesResult {
            public final List<Source> sources;
            public final String etag;

            Loaded(List<Source> sources, String etag) {
                this.sources = sources;
                this.etag = etag;
            }
        }

        public static final SourcesResult NOT_MODIFIED = new SourcesResult() {
            @Override
            public String toString() {
                return "NotModified";
            }
        };
        /** 404 / invalid / unsupported schema: the client falls back to the single built-in repository. */
        public static final SourcesResult MISSING = new SourcesResult() {
            @Override
            public String toString() {
                return "Missing";
            }
        };
        /** Network error / 5xx: keep what the client already has. */
        public static final SourcesResult FAILED = new SourcesResult() {
            @Override
            public String toString() {
                return "Failed";
            }
        };
    }

    private static final Pattern SOURCE_ID = Pattern.compile("^s[0-9]{1,11}$");
    private static final Pattern CHANNEL_USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{3,31}$");

    /**
     * Loads sources.json (docs §8). Source indexes are resolved against the final sources.json URL and must be
     * HTTPS on the same host (they are trusted like the built-in catalog); other entries are skipped.
     */
    public static SourcesResult loadSources(String url, String etag) {
        HttpUrl requested = HttpUrl.parse(url);
        if (requested == null || !requested.isHttps()) {
            return SourcesResult.MISSING;
        }
        Request.Builder builder = new Request.Builder().url(requested);
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }
        try (Response response = ExteraHttpClient.INSTANCE.getClient().newCall(builder.build()).execute()) {
            if (response.code() == 304) {
                return SourcesResult.NOT_MODIFIED;
            }
            if (response.code() == 404 || response.code() == 410) {
                return SourcesResult.MISSING;
            }
            if (!response.isSuccessful() || response.body() == null) {
                FileLog.e("PluginCatalog: sources request " + url + " failed with " + response.code());
                return SourcesResult.FAILED;
            }
            HttpUrl base = response.request().url();
            if (!base.isHttps() || !base.host().equalsIgnoreCase(requested.host())) {
                return SourcesResult.MISSING;
            }
            BufferedSource source = response.body().source();
            if (source.request(MAX_INDEX_SIZE + 1)) {
                FileLog.e("PluginCatalog: sources " + url + " is too large");
                return SourcesResult.MISSING;
            }
            JSONObject json;
            try {
                json = new JSONObject(source.getBuffer().readUtf8());
            } catch (org.json.JSONException e) {
                FileLog.e("PluginCatalog: invalid sources " + url, e);
                return SourcesResult.MISSING;
            }
            JSONArray array = json.optJSONArray("sources");
            if (json.optInt("schema", 0) != 1 || array == null) {
                return SourcesResult.MISSING;
            }
            ArrayList<Source> sources = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                Source parsed = o != null ? parseSource(o, base, requested.host()) : null;
                if (parsed != null && ids.add(parsed.id)) {
                    sources.add(parsed);
                }
            }
            return new SourcesResult.Loaded(sources, response.header("ETag"));
        } catch (Exception e) {
            FileLog.e("PluginCatalog: failed to load sources " + url, e);
            return SourcesResult.FAILED;
        }
    }

    private static Source parseSource(JSONObject json, HttpUrl base, String host) {
        String id = stringOrNull(json, "id");
        String title = stringOrNull(json, "title");
        String index = stringOrNull(json, "index");
        int plugins = json.optInt("plugins", 0);
        if (id == null || !SOURCE_ID.matcher(id).matches() || title == null || index == null || plugins < 1) {
            return null;
        }
        HttpUrl indexUrl = base.resolve(index);
        if (indexUrl == null || !indexUrl.isHttps() || !indexUrl.host().equalsIgnoreCase(host)) {
            return null;
        }
        String channel = stringOrNull(json, "channel");
        if (channel != null) {
            channel = channel.startsWith("@") ? channel.substring(1) : channel;
            if (!CHANNEL_USERNAME.matcher(channel).matches()) {
                channel = null;
            }
        }
        return new Source(id, title, channel, plugins, indexUrl.toString(), stringOrNull(json, "updated_at"));
    }

    /**
     * User input -> repository URL, or null: GitHub links become https://github.com/owner/repo[/tree/ref/path]
     * (blob links to extera-plugins.json are accepted too), other links must be HTTPS and point to a .json file.
     */
    public static String normalizeUrl(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return null;
            }
        }
        String bare = removePrefix(removePrefix(removePrefix(s, "https://"), "http://"), "www.");
        Matcher blob = GITHUB_BLOB_URL.matcher(bare);
        if (blob.matches()) {
            bare = blob.group(1) + "/tree/" + blob.group(2);
        }
        Matcher github = GITHUB_URL.matcher(bare);
        if (github.matches()) {
            String ref = github.group(3);
            String path = github.group(4);
            StringBuilder sb = new StringBuilder("https://github.com/");
            sb.append(github.group(1).toLowerCase(Locale.ROOT)).append('/').append(github.group(2).toLowerCase(Locale.ROOT));
            if (ref != null && !ref.isEmpty()) {
                sb.append("/tree/").append(ref).append(path != null ? path : "");
            }
            return sb.toString();
        }
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        HttpUrl url = HttpUrl.parse(s);
        if (url == null || !url.isHttps()) {
            return null;
        }
        List<String> segments = url.pathSegments();
        String last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        // exteraGram accepts only .../extera-plugins.json here; StaticGram accepts any .json index
        if (last == null || !last.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return null;
        }
        return url.toString();
    }

    /** Index download URLs to try in order (GitHub: raw.githubusercontent.com, then jsDelivr for a pinned ref). */
    public static List<String> getIndexUrls(String repositoryUrl) {
        Matcher m = GITHUB_URL.matcher(removePrefix(repositoryUrl, "https://"));
        if (!m.matches()) {
            return Collections.singletonList(repositoryUrl);
        }
        String owner = m.group(1);
        String repo = m.group(2);
        String ref = m.group(3) != null ? m.group(3) : "";
        String path = trimSlashes(m.group(4) != null ? m.group(4) : "");
        String file = path.isEmpty() ? INDEX_FILE_NAME : path + "/" + INDEX_FILE_NAME;
        String raw = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + (ref.isEmpty() ? "HEAD" : ref) + "/" + file;
        if (ref.isEmpty()) {
            return Collections.singletonList(raw);
        }
        ArrayList<String> urls = new ArrayList<>(2);
        urls.add(raw);
        urls.add("https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + ref + "/" + file);
        return urls;
    }

    /** The page "Open Repository" leads to: the index homepage, or the GitHub repository itself. */
    public static String getWebUrl(String repositoryUrl, String homepage) {
        if (homepage != null) {
            return homepage;
        }
        if (repositoryUrl != null && GITHUB_URL.matcher(removePrefix(repositoryUrl, "https://")).matches()) {
            return repositoryUrl;
        }
        return null;
    }

    public static IndexResult loadIndex(String url, String etag) {
        Request.Builder builder = new Request.Builder().url(url);
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }
        try (Response response = ExteraHttpClient.INSTANCE.getClient().newCall(builder.build()).execute()) {
            if (response.code() == 304) {
                return IndexResult.NOT_MODIFIED;
            }
            if (!response.isSuccessful() || response.body() == null) {
                FileLog.e("PluginCatalog: index request " + url + " failed with " + response.code());
                return IndexResult.FAILED;
            }
            BufferedSource source = response.body().source();
            if (source.request(MAX_INDEX_SIZE + 1)) {
                FileLog.e("PluginCatalog: index " + url + " is too large");
                return IndexResult.FAILED;
            }
            JSONObject json = new JSONObject(source.getBuffer().readUtf8());
            if (json.optInt("schema", 0) > SCHEMA_VERSION) {
                return IndexResult.UNSUPPORTED;
            }
            Index index = parseIndex(json, response.request().url());
            return index != null ? new IndexResult.Loaded(index, response.header("ETag")) : IndexResult.FAILED;
        } catch (Exception e) {
            FileLog.e("PluginCatalog: failed to load index " + url, e);
            return IndexResult.FAILED;
        }
    }

    private static Index parseIndex(JSONObject json, HttpUrl baseUrl) {
        String name = stringOrNull(json, "name");
        JSONArray array = json.optJSONArray("plugins");
        if (json.optInt("schema", 0) < 1 || name == null || array == null) {
            return null;
        }
        HashSet<String> ids = new HashSet<>();
        ArrayList<IndexPlugin> plugins = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            IndexPlugin plugin = o != null ? parsePlugin(o, baseUrl) : null;
            if (plugin != null && ids.add(plugin.id)) {
                plugins.add(plugin);
            }
        }
        String homepage = null;
        String homepageValue = stringOrNull(json, "homepage");
        HttpUrl homepageUrl = homepageValue != null ? HttpUrl.parse(homepageValue) : null;
        if (homepageUrl != null && homepageUrl.isHttps()) {
            homepage = homepageUrl.toString();
        }
        String channel = stringOrNull(json, "channel");
        if (channel != null) {
            channel = channel.trim();
            if (channel.startsWith("https://t.me/")) {
                channel = channel.substring(13);
            }
            channel = channel.startsWith("@") ? channel.substring(1) : channel;
            if (!channel.matches("[A-Za-z][A-Za-z0-9_]{3,31}")) {
                channel = null;
            }
        }
        return new Index(name, homepage, plugins, channel);
    }

    private static IndexPlugin parsePlugin(JSONObject json, HttpUrl baseUrl) {
        String id = stringOrNull(json, "id");
        String name = stringOrNull(json, "name");
        String version = stringOrNull(json, "version");
        if (id == null || name == null || version == null) {
            return null;
        }
        String sha256 = stringOrNull(json, "sha256");
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            return null;
        }
        long size = json.optLong("size", 0L);
        if (size <= 0 || size > MAX_FILE_SIZE) {
            return null;
        }
        String file = stringOrNull(json, "file");
        HttpUrl fileUrl = file != null ? baseUrl.resolve(file) : null;
        if (fileUrl == null || !fileUrl.isHttps()) {
            return null;
        }
        List<String> segments = fileUrl.pathSegments();
        String fileName = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (fileName == null || !isSafeFileName(fileName)) {
            return null;
        }
        String updatedAt = stringOrNull(json, "updated_at");
        return new IndexPlugin(id, name, version, fileUrl.toString(), fileName, sha256, size,
                stringOrNull(json, "description"), stringOrNull(json, "author"), stringOrNull(json, "icon"),
                stringOrNull(json, "app_version"), stringOrNull(json, "sdk_version"),
                stringOrList(json, "requirements", Integer.MAX_VALUE, ", "), stringOrList(json, "category", MAX_CATEGORIES, ","),
                updatedAt != null ? parseTime(updatedAt) : null, Math.max(0, json.optInt("downloads", 0)), stringOrNull(json, "source"));
    }

    /**
     * Downloads a plugin file into cache/plugin_catalog/<sha256>/<fileName>, enforcing the exact size and
     * SHA-256 from the index. Returns the file, or null on any mismatch / network error.
     */
    public static File downloadFile(String fileUrl, String fileName, String sha256, long size) {
        if (!isSafeFileName(fileName) || sha256 == null || !SHA256.matcher(sha256).matches()) {
            return null;
        }
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "plugin_catalog/" + sha256);
        File target = new File(dir, fileName);
        if (target.length() == size && sha256.equals(hash(target))) {
            return target;
        }
        File temp;
        try {
            dir.mkdirs();
            temp = File.createTempFile("plugin", ".part", dir);
        } catch (IOException e) {
            FileLog.e("PluginCatalog: cannot create a temp file for " + fileName, e);
            return null;
        }
        try (Response response = ExteraHttpClient.INSTANCE.getClient().newCall(new Request.Builder().url(fileUrl).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                    if (total > size) {
                        throw new IOException("file is larger than " + size + " bytes");
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (total != size || !sha256.equals(toHex(digest.digest()))) {
                throw new IOException("size or sha256 mismatch");
            }
            if (target.exists()) {
                target.delete();
            }
            if (!temp.renameTo(target)) {
                throw new IOException("cannot move " + temp.getName());
            }
            return target;
        } catch (Exception e) {
            FileLog.e("PluginCatalog: failed to download " + fileUrl, e);
            temp.delete();
            return null;
        }
    }

    /** Stable negative id of a file of an index repository: sha256(repositoryId:pluginId:sha256)[0..8] | sign bit. */
    public static long documentId(long repositoryId, String pluginId, String sha256) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((repositoryId + ":" + pluginId + ":" + sha256).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (d[i] & 0xffL);
            }
            return Long.MIN_VALUE | value;
        } catch (Exception e) {
            return Long.MIN_VALUE | ((long) (repositoryId + ":" + pluginId + ":" + sha256).hashCode() & 0xffffffffL);
        }
    }

    private static boolean isSafeFileName(String name) {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == 0) {
                return false;
            }
        }
        return true;
    }

    private static String hash(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parseTime(String value) {
        try {
            return (int) java.time.OffsetDateTime.parse(value).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static String stringOrNull(JSONObject json, String key) {
        Object v = json.opt(key);
        if (!(v instanceof String)) {
            return null;
        }
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String stringOrList(JSONObject json, String key, int limit, String separator) {
        Object v = json.opt(key);
        ArrayList<String> values = new ArrayList<>();
        if (v instanceof String) {
            values.add((String) v);
        } else if (v instanceof JSONArray) {
            JSONArray array = (JSONArray) v;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof String) {
                    values.add((String) item);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String value : values) {
            String s = value.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (count >= limit) {
                break;
            }
            if (count > 0) {
                sb.append(separator);
            }
            sb.append(s);
            count++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String removePrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    private static String trimSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }
}
