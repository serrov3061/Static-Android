package com.exteragram.messenger.plugins.pip;

import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.utils.PluginFiles;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Pair;

/**
 * exteraGram plugins: a tiny pip. Resolves plugin requirements against PyPI, installs pure-Python wheels into
 * plugins/libs/&lt;package&gt;-&lt;version&gt; and keeps a registry of which plugin uses which package version.
 */
public final class PipController {

    private static final String ENV_OS_NAME = "posix";
    private static final String ENV_PLATFORM_SYSTEM = "Linux";
    private static final String ENV_SYS_PLATFORM = "linux";
    private static final long MAX_METADATA_BYTES = 4194304;
    private static final long MAX_REGISTRY_BYTES = 4194304;
    private static final long MAX_WHEEL_BYTES = 262144000;
    private static final int HTTP_TIMEOUT_MS = 30000;

    private static final Pattern REGEX_NORMALIZE = Pattern.compile("[-_.]+");
    private static final Pattern REGEX_REQ_PARSE = Pattern.compile("^([a-zA-Z0-9_.-]+)(.*)");
    private static final Pattern REGEX_REQ_SPECS = Pattern.compile("^(===|==|>=|<=|>|<|~=|!=)\\s*(.*)");
    private static final Pattern REGEX_REQ_EXTRA = Pattern.compile("\\[.*?]");
    private static final Pattern REGEX_REQ_PAREN = Pattern.compile("[()]");
    private static final Pattern REGEX_VERSION_SPLIT = Pattern.compile("([0-9]+)|([a-zA-Z]+)");
    private static final Pattern REGEX_MARKER_TOKEN = Pattern.compile("(\"[^\"]*\"|'[^']*'|===|==|!=|>=|<=|~=|>|<|\\(|\\)|\\b(?:and|or|in|not)\\b|[A-Za-z_][A-Za-z0-9_]*|\\S+)");
    private static final Pattern REGEX_VERSION_WILDCARD = Pattern.compile("(?i)(?:^|[.\\-_])\\*$");
    private static final Pattern REGEX_MARKER_VARIABLE = Pattern.compile("[a-z_][a-z0-9_]*");

    private static final Gson gson = new Gson();
    /** package -> version -> ids of the plugins that use it */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> registry = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> installLocks = new ConcurrentHashMap<>();
    private static final PluginFiles.ArchiveLimits wheelArchiveLimits = new PluginFiles.ArchiveLimits(50000L, 524288000L, MAX_WHEEL_BYTES, 500.0d);
    private static String pythonVersion = "3.11.10";

    public static final PipController INSTANCE = new PipController();
    private static final Set<String> PREINSTALLED_PACKAGES;

    static {
        try {
            INSTANCE.loadRegistry();
        } catch (Throwable th) {
            FileLog.e("PipController: Failed to initialize registry", th);
        }
        Set<String> preinstalled = new LinkedHashSet<>();
        for (String name : new String[]{"beautifulsoup4", "debugpy", "lxml", "packaging", "pillow", "requests", "pyyaml"}) {
            preinstalled.add(INSTANCE.normalizePackageName(name));
        }
        PREINSTALLED_PACKAGES = Collections.unmodifiableSet(preinstalled);
    }

    public interface InstallerDelegate {
        boolean isCancelled();

        void onProgress(String text);
    }

    private PipController() {
    }

    private File getLibsDir() {
        try {
            return PluginFiles.mkdirs(new File(PluginsController.Companion.getInstance().getPluginsDir(), "libs"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private File getRegistryFile() {
        return new File(getLibsDir(), "registry.json");
    }

    public final String getPythonVersion() {
        return pythonVersion;
    }

    public final void setPythonVersion(String str) {
        pythonVersion = str;
    }

    public static final class WheelCandidate {
        private final String downloadUrl;
        private final String expectedSha256;
        private final String version;

        public WheelCandidate(String version, String downloadUrl, String expectedSha256) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.expectedSha256 = expectedSha256;
        }

        public final String getVersion() {
            return this.version;
        }

        public final String getDownloadUrl() {
            return this.downloadUrl;
        }

        public final String getExpectedSha256() {
            return this.expectedSha256;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof WheelCandidate)) {
                return false;
            }
            WheelCandidate o = (WheelCandidate) other;
            return version.equals(o.version) && downloadUrl.equals(o.downloadUrl) && java.util.Objects.equals(expectedSha256, o.expectedSha256);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(version, downloadUrl, expectedSha256);
        }

        @Override
        public String toString() {
            return "WheelCandidate(version=" + version + ", downloadUrl=" + downloadUrl + ", expectedSha256=" + expectedSha256 + ")";
        }
    }

    public static final class ParsedVersion {
        private final int epoch;
        private final List<String> parts;
        private final String publicVersion;

        public ParsedVersion(int epoch, String publicVersion, List<String> parts) {
            this.epoch = epoch;
            this.publicVersion = publicVersion;
            this.parts = parts;
        }

        public final int getEpoch() {
            return this.epoch;
        }

        public final String getPublicVersion() {
            return this.publicVersion;
        }

        public final List<String> getParts() {
            return this.parts;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ParsedVersion)) {
                return false;
            }
            ParsedVersion o = (ParsedVersion) other;
            return epoch == o.epoch && publicVersion.equals(o.publicVersion) && parts.equals(o.parts);
        }

        @Override
        public int hashCode() {
            return (((Integer.hashCode(epoch) * 31) + publicVersion.hashCode()) * 31) + parts.hashCode();
        }

        @Override
        public String toString() {
            return "ParsedVersion(epoch=" + epoch + ", publicVersion=" + publicVersion + ", parts=" + parts + ")";
        }
    }

    private String normalizePackageName(String name) {
        return REGEX_NORMALIZE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
    }

    // region registry

    private synchronized void loadRegistry() {
        File registryFile = getRegistryFile();
        if (!registryFile.exists()) {
            registry.clear();
            return;
        }
        try {
            Map<String, Map<String, Set<String>>> map = gson.fromJson(PluginFiles.readText(registryFile, MAX_REGISTRY_BYTES), new TypeToken<Map<String, Map<String, Set<String>>>>() {
            }.getType());
            registry.clear();
            if (map != null) {
                for (Map.Entry<String, Map<String, Set<String>>> entry : map.entrySet()) {
                    registry.put(normalizePackageName(entry.getKey()), toConcurrent(entry.getValue()));
                }
            }
        } catch (Exception e) {
            FileLog.e("PipController: Failed to load registry", e);
            registry.clear();
            try {
                PluginFiles.move(registryFile, new File(getLibsDir(), "registry.json.bak"), true);
            } catch (Throwable th) {
                FileLog.e("PipController: Failed to backup invalid registry", th);
            }
        }
    }

    private static ConcurrentHashMap<String, Set<String>> toConcurrent(Map<String, ? extends Set<String>> versions) {
        ConcurrentHashMap<String, Set<String>> result = new ConcurrentHashMap<>();
        if (versions != null) {
            for (Map.Entry<String, ? extends Set<String>> entry : versions.entrySet()) {
                Set<String> users = ConcurrentHashMap.newKeySet();
                if (entry.getValue() != null) {
                    users.addAll(entry.getValue());
                }
                result.put(entry.getKey(), users);
            }
        }
        return result;
    }

    private synchronized Map<String, Map<String, Set<String>>> snapshotRegistry() {
        LinkedHashMap<String, Map<String, Set<String>>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> entry : registry.entrySet()) {
            LinkedHashMap<String, Set<String>> versions = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> version : entry.getValue().entrySet()) {
                versions.put(version.getKey(), new LinkedHashSet<>(version.getValue()));
            }
            snapshot.put(entry.getKey(), versions);
        }
        return snapshot;
    }

    private synchronized void restoreRegistry(Map<String, ? extends Map<String, ? extends Set<String>>> snapshot) {
        registry.clear();
        for (Map.Entry<String, ? extends Map<String, ? extends Set<String>>> entry : snapshot.entrySet()) {
            registry.put(entry.getKey(), toConcurrent(entry.getValue()));
        }
    }

    private synchronized void saveRegistry() {
        try {
            PluginFiles.writeTextAtomic(getRegistryFile(), gson.toJson(snapshotRegistry()));
        } catch (Exception e) {
            FileLog.e("PipController: Failed to save registry", e);
        }
    }

    /** Removes a package version from the registry and from disk. */
    private void dropPackage(String pkg, String version) {
        ConcurrentHashMap<String, Set<String>> versions = registry.get(pkg);
        if (versions != null) {
            versions.remove(version);
            if (versions.isEmpty()) {
                registry.remove(pkg);
            }
        }
        deletePackage(pkg, version);
    }

    // endregion

    public final void cleanup() {
        try {
            cleanupInternal();
        } catch (Exception e) {
            FileLog.e("PipController: cleanup failed", e);
        }
    }

    public final List<String> installDependencies(List<String> requirements, String pluginId) throws Exception {
        return installDependencies(requirements, pluginId, null);
    }

    /** Installs the requirements of a plugin and returns the library paths to put on sys.path. */
    public final List<String> installDependencies(List<String> requirements, String pluginId, InstallerDelegate delegate) throws Exception {
        ArrayList<String> paths = new ArrayList<>();
        LinkedHashSet<Pair<String, String>> installed = new LinkedHashSet<>();
        Map<String, Map<String, Set<String>>> snapshot = snapshotRegistry();
        try {
            for (String requirement : requirements) {
                if (delegate != null && delegate.isCancelled()) {
                    throw new IOException("Installation cancelled");
                }
                if (requirement.trim().isEmpty()) {
                    continue;
                }
                Pair<String, List<Pair<String, String>>> parsed = parseRequirement(requirement);
                String pkg = normalizePackageName(parsed.getFirst());
                if (!PREINSTALLED_PACKAGES.contains(pkg)) {
                    resolveAndInstall(pkg, parsed.getSecond(), installed, pluginId, delegate);
                }
            }
            updateRegistryForPlugin(pluginId, installed);
            for (Pair<String, String> pair : installed) {
                paths.add(getLibPath(pair.getFirst(), pair.getSecond()).getAbsolutePath());
            }
            saveRegistry();
            return paths;
        } catch (Exception e) {
            FileLog.e("PipController: Failed to install dependencies for " + pluginId + ": " + requirements, e);
            restoreRegistry(snapshot);
            removeOrphanedDirectories();
            throw e;
        } finally {
            installLocks.clear();
        }
    }

    private void resolveAndInstall(String pkg, List<Pair<String, String>> specs, Set<Pair<String, String>> installedAccumulator, String pluginId, InstallerDelegate delegate) throws IOException {
        if (delegate != null && delegate.isCancelled()) {
            throw new IOException("Installation cancelled");
        }
        String version;
        synchronized (installLocks.computeIfAbsent(pkg, k -> new Object())) {
            if (delegate != null && delegate.isCancelled()) {
                throw new IOException("Installation cancelled");
            }
            Map.Entry<String, Set<String>> active = null;
            ConcurrentHashMap<String, Set<String>> versions = registry.get(pkg);
            if (versions != null) {
                for (Map.Entry<String, Set<String>> entry : versions.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        active = entry;
                        break;
                    }
                }
            }
            if (active != null) {
                // only one version of a package can be imported at a time, so every plugin shares the active one
                version = active.getKey();
                String users = android.text.TextUtils.join(", ", active.getValue());
                if (!checkVersionSatisfies(version, specs)) {
                    throw new IOException("Dependency conflict for '" + pkg + "': Active version " + version + " (used by " + users + ") does not satisfy requirement '" + formatSpecs(specs) + "'.");
                }
                FileLog.d("PipController: Reusing active version " + version + " of " + pkg + " (used by " + users + ") for " + pluginId);
            } else {
                String found = findInstalledVersion(pkg, specs);
                if (found != null) {
                    version = found;
                } else if ((found = findVersionOnDisk(pkg, specs)) != null) {
                    FileLog.d("PipController: Found " + pkg + " " + found + " on disk, adopting.");
                    version = found;
                } else {
                    version = installPackage(pkg, specs, delegate);
                }
            }
        }
        Pair<String, String> previous = null;
        for (Pair<String, String> pair : installedAccumulator) {
            if (pair.getFirst().equals(pkg)) {
                previous = pair;
                break;
            }
        }
        if (previous != null) {
            if (VersionComparator.INSTANCE.compare(version, previous.getSecond()) <= 0) {
                return;
            }
            FileLog.d("PipController: Upgrading " + pkg + " from " + previous.getSecond() + " to " + version + " for " + pluginId);
            installedAccumulator.remove(previous);
        }
        registry.computeIfAbsent(pkg, k -> new ConcurrentHashMap<>()).computeIfAbsent(version, k -> ConcurrentHashMap.newKeySet()).add(pluginId);
        installedAccumulator.add(new Pair<>(pkg, version));
        File metadataFile = findMetadataFile(pkg, version);
        if (metadataFile == null || !metadataFile.exists()) {
            return;
        }
        for (String dependency : parseDependenciesFromMetadata(metadataFile)) {
            if (delegate != null && delegate.isCancelled()) {
                throw new IOException("Installation cancelled");
            }
            Pair<String, List<Pair<String, String>>> parsed = parseRequirement(dependency);
            resolveAndInstall(normalizePackageName(parsed.getFirst()), parsed.getSecond(), installedAccumulator, pluginId, delegate);
        }
    }

    private static String formatSpecs(List<Pair<String, String>> specs) {
        if (specs.isEmpty()) {
            return "(latest)";
        }
        StringBuilder sb = new StringBuilder();
        for (Pair<String, String> spec : specs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(spec.getFirst()).append(spec.getSecond());
        }
        return sb.toString();
    }

    private void updateRegistryForPlugin(String pluginId, Set<Pair<String, String>> currentlyNeeded) {
        ArrayList<Pair<String, String>> unused = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> entry : registry.entrySet()) {
            for (Map.Entry<String, Set<String>> version : entry.getValue().entrySet()) {
                Pair<String, String> pair = new Pair<>(entry.getKey(), version.getKey());
                Set<String> users = version.getValue();
                if (users.contains(pluginId) && !currentlyNeeded.contains(pair)) {
                    users.remove(pluginId);
                    if (users.isEmpty()) {
                        unused.add(pair);
                    }
                }
            }
        }
        for (Pair<String, String> pair : unused) {
            dropPackage(pair.getFirst(), pair.getSecond());
        }
    }

    private void cleanupInternal() {
        ArrayList<Pair<String, String>> unused = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> entry : registry.entrySet()) {
            for (Map.Entry<String, Set<String>> version : entry.getValue().entrySet()) {
                if (version.getValue().isEmpty()) {
                    unused.add(new Pair<>(entry.getKey(), version.getKey()));
                }
            }
        }
        for (Pair<String, String> pair : unused) {
            dropPackage(pair.getFirst(), pair.getSecond());
        }
        removeOrphanedDirectories();
    }

    private void removeOrphanedDirectories() {
        LinkedHashSet<String> known = new LinkedHashSet<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> entry : registry.entrySet()) {
            for (String version : entry.getValue().keySet()) {
                try {
                    known.add(getLibPath(entry.getKey(), version).getCanonicalPath());
                } catch (IOException ignored) {
                }
            }
        }
        File[] files = getLibsDir().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isDirectory()) {
                continue;
            }
            try {
                if (!known.contains(file.getCanonicalPath())) {
                    FileLog.d("PipController: Removing orphaned library: " + file.getName());
                    PluginFiles.deleteRecursively(file);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public final Set<String> activeLibraryPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> entry : registry.entrySet()) {
            for (Map.Entry<String, Set<String>> version : entry.getValue().entrySet()) {
                if (version.getValue().isEmpty()) {
                    continue;
                }
                try {
                    paths.add(getLibPath(entry.getKey(), version.getKey()).getAbsolutePath());
                } catch (IOException e) {
                    FileLog.e("PipController: Failed to resolve path for " + entry.getKey() + " " + version.getKey(), e);
                }
            }
        }
        return paths;
    }

    public final void uninstallDependencies(String pluginId) {
        ArrayList<Pair<String, String>> unused = new ArrayList<>();
        boolean changed = false;
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> entry : registry.entrySet()) {
            for (Map.Entry<String, Set<String>> version : entry.getValue().entrySet()) {
                Set<String> users = version.getValue();
                if (users.remove(pluginId)) {
                    changed = true;
                    if (users.isEmpty()) {
                        unused.add(new Pair<>(entry.getKey(), version.getKey()));
                    }
                }
            }
        }
        for (Pair<String, String> pair : unused) {
            dropPackage(pair.getFirst(), pair.getSecond());
        }
        if (changed) {
            saveRegistry();
        }
    }

    // region download & install

    private String installPackage(String pkg, List<Pair<String, String>> specs, InstallerDelegate delegate) throws IOException {
        FileLog.d("PipController: Installing " + pkg + " " + formatSpecs(specs));
        if (delegate != null) {
            delegate.onProgress("Resolving " + pkg + "...");
        }
        JsonObject metadata;
        HttpURLConnection connection = executeWithRetry("https://pypi.org/pypi/" + pkg + "/json", delegate);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                if (code == 404) {
                    throw new IOException("Package " + pkg + " not found on PyPI");
                }
                throw new IOException("Failed to fetch metadata for " + pkg + ": " + code);
            }
            try (InputStream in = new SizeLimitedInputStream(connection.getInputStream(), 64L * 1024 * 1024)) {
                metadata = gson.fromJson(new String(readAll(in), StandardCharsets.UTF_8), JsonObject.class);
            }
        } finally {
            connection.disconnect();
        }
        if (metadata == null) {
            throw new IOException("Invalid metadata response from PyPI for " + pkg);
        }
        JsonObject releases = metadata.getAsJsonObject("releases");
        if (releases == null) {
            throw new IOException("No releases found for " + pkg + " on PyPI");
        }
        ArrayList<String> matching = new ArrayList<>();
        for (String version : releases.keySet()) {
            if (checkVersionSatisfies(version, specs)) {
                matching.add(version);
            }
        }
        List<String> candidates = new ArrayList<>(filterPreReleases(matching, specs));
        Collections.sort(candidates, (a, b) -> VersionComparator.INSTANCE.compare(b, a));
        boolean pinned = false;
        for (Pair<String, String> spec : specs) {
            if ("==".equals(spec.getFirst()) || "===".equals(spec.getFirst())) {
                pinned = true;
                break;
            }
        }
        WheelCandidate wheel = null;
        for (String version : candidates) {
            JsonArray artifacts = releases.getAsJsonArray(version);
            if (artifacts == null) {
                continue;
            }
            for (JsonElement artifact : artifacts) {
                wheel = selectWheelCandidate(version, artifact.getAsJsonObject(), pinned);
                if (wheel != null) {
                    break;
                }
            }
            if (wheel != null) {
                break;
            }
        }
        if (wheel == null) {
            JsonObject info = metadata.getAsJsonObject("info");
            String requiresPython = info != null ? getStringOrNull(info, "requires_python") : null;
            if (requiresPython != null && !checkVersionSatisfies(pythonVersion, parseSpecs(requiresPython))) {
                throw new IOException("Package " + pkg + " requires Python " + requiresPython + ", but current is " + pythonVersion);
            }
            throw new IOException("No pure-Python wheel found for " + pkg + ". Binary packages are not supported.");
        }

        File libPath = getLibPath(pkg, wheel.getVersion());
        File tmpDir = PluginFiles.mkdirs(new File(getLibsDir(), "tmp_" + pkg + "_" + System.currentTimeMillis()));
        File extracted = new File(tmpDir, "extracted");
        try {
            if (delegate != null) {
                delegate.onProgress("Downloading " + pkg + " " + wheel.getVersion() + "...");
            }
            File wheelFile = new File(tmpDir, "package.whl");
            HttpURLConnection download = executeWithRetry(wheel.getDownloadUrl(), delegate);
            try {
                int code = download.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("Download failed: " + code);
                }
                long contentLength = download.getContentLengthLong();
                if (contentLength > MAX_WHEEL_BYTES) {
                    throw new IOException("Wheel for " + pkg + " is too large: " + contentLength + " bytes (limit " + MAX_WHEEL_BYTES + ")");
                }
                try (InputStream in = new SizeLimitedInputStream(download.getInputStream(), MAX_WHEEL_BYTES)) {
                    PluginFiles.writeFrom(wheelFile, in, Long.MAX_VALUE);
                }
            } finally {
                download.disconnect();
            }
            if (wheel.getExpectedSha256() != null && !calculateSha256(wheelFile).equalsIgnoreCase(wheel.getExpectedSha256())) {
                throw new IOException("Checksum mismatch");
            }
            if (delegate != null) {
                delegate.onProgress("Extracting " + pkg + " " + wheel.getVersion() + "...");
            }
            PluginFiles.extractZip(wheelFile, extracted, wheelArchiveLimits, () -> delegate != null && delegate.isCancelled());
            if (libPath.exists()) {
                PluginFiles.deleteRecursively(libPath);
            }
            PluginFiles.move(extracted, libPath, true);
            return wheel.getVersion();
        } catch (IOException | RuntimeException e) {
            FileLog.e("PipController: Failed to download/install " + pkg, e);
            throw e;
        } finally {
            if (!PluginFiles.deleteRecursively(tmpDir)) {
                FileLog.e("PipController: Failed to delete temp package directory " + tmpDir);
            }
        }
    }

    private WheelCandidate selectWheelCandidate(String version, JsonObject artifact, boolean allowYanked) {
        if (!"bdist_wheel".equals(getStringOrNull(artifact, "packagetype"))) {
            return null;
        }
        if (!allowYanked && Boolean.TRUE.equals(getBooleanOrNull(artifact, "yanked"))) {
            return null;
        }
        String filename = getStringOrNull(artifact, "filename");
        if (filename == null || !isPurePythonWheelCompatible(filename)) {
            return null;
        }
        String requiresPython = getStringOrNull(artifact, "requires_python");
        if (requiresPython != null && !checkVersionSatisfies(pythonVersion, parseSpecs(requiresPython))) {
            return null;
        }
        String url = getStringOrNull(artifact, "url");
        if (url == null) {
            return null;
        }
        JsonObject digests = artifact.getAsJsonObject("digests");
        return new WheelCandidate(version, url, digests != null ? getStringOrNull(digests, "sha256") : null);
    }

    private String getStringOrNull(JsonObject jsonObject, String key) {
        JsonElement element = jsonObject.has(key) ? jsonObject.get(key) : null;
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    private Boolean getBooleanOrNull(JsonObject jsonObject, String key) {
        JsonElement element = jsonObject.has(key) ? jsonObject.get(key) : null;
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPurePythonWheelCompatible(String filename) {
        if (!filename.endsWith(".whl")) {
            return false;
        }
        String[] parts = filename.substring(0, filename.length() - 4).split("-", -1);
        if (parts.length < 5) {
            return false;
        }
        String pythonTags = parts[parts.length - 3];
        String abiTag = parts[parts.length - 2];
        String platformTag = parts[parts.length - 1];
        if (!"none".equals(abiTag) || !"any".equals(platformTag)) {
            return false;
        }
        for (String tag : pythonTags.split("\\.", -1)) {
            if (isPythonTagCompatible(tag)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPythonTagCompatible(String tag) {
        String[] parts = pythonVersion.split("\\.", -1);
        Integer major = parts.length > 0 ? toIntOrNull(parts[0]) : null;
        Integer minor = parts.length > 1 ? toIntOrNull(parts[1]) : null;
        if (major == null || minor == null) {
            return false;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals("py" + major) || lower.equals("cp" + major + minor)) {
            return true;
        }
        String prefix = "py" + major;
        Integer tagMinor = toIntOrNull(lower.startsWith(prefix) ? lower.substring(prefix.length()) : lower);
        return tagMinor != null && tagMinor <= minor;
    }

    private HttpURLConnection executeWithRetry(String url, InstallerDelegate delegate) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (delegate != null && delegate.isCancelled()) {
                throw new IOException("Installation cancelled");
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(HTTP_TIMEOUT_MS);
                connection.setReadTimeout(HTTP_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "application/json, */*");
                connection.getResponseCode(); // performs the request
                return connection;
            } catch (IOException e) {
                if (connection != null) {
                    connection.disconnect();
                }
                last = e;
                FileLog.w("PipController: Network error, retrying (" + attempt + "/3): " + e.getMessage());
                try {
                    Thread.sleep(attempt * 1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (delegate != null && delegate.isCancelled()) {
            throw new IOException("Installation cancelled");
        }
        throw last != null ? last : new IOException("Unknown network error");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // endregion

    private String findVersionOnDisk(String pkg, List<Pair<String, String>> specs) {
        String prefix = pkg + "-";
        File[] files = getLibsDir().listFiles();
        if (files == null) {
            return null;
        }
        ArrayList<String> versions = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith(prefix)) {
                String version = file.getName().substring(prefix.length());
                if (version.length() > 0 && checkVersionSatisfies(version, specs)) {
                    versions.add(version);
                }
            }
        }
        return maxVersion(filterPreReleases(versions, specs));
    }

    private String findInstalledVersion(String pkg, List<Pair<String, String>> specs) {
        ConcurrentHashMap<String, Set<String>> versions = registry.get(pkg);
        if (versions == null) {
            return null;
        }
        ArrayList<String> matching = new ArrayList<>();
        for (String version : versions.keySet()) {
            if (checkVersionSatisfies(version, specs)) {
                matching.add(version);
            }
        }
        return maxVersion(filterPreReleases(matching, specs));
    }

    private static String maxVersion(List<String> versions) {
        return versions.isEmpty() ? null : Collections.max(versions, VersionComparator.INSTANCE);
    }

    private File getLibPath(String pkg, String version) throws IOException {
        if (version.contains("/") || version.contains("\\") || version.contains("..")) {
            throw new IOException("Invalid version: " + version);
        }
        return new File(getLibsDir(), pkg + "-" + version);
    }

    private void deletePackage(String pkg, String version) {
        try {
            File libPath = getLibPath(pkg, version);
            if (libPath.exists()) {
                PluginFiles.deleteRecursively(libPath);
                FileLog.d("PipController: Deleted package " + libPath.getName());
            }
        } catch (Throwable th) {
            FileLog.e("PipController: Failed to delete package " + pkg + "-" + version, th);
        }
    }

    // region requirement / version parsing (PEP 440 / PEP 508 subset)

    private Pair<String, List<Pair<String, String>>> parseRequirement(String req) {
        String withoutMarker = req.split(";", -1)[0].trim();
        String withoutExtras = REGEX_REQ_EXTRA.matcher(withoutMarker).replaceAll("").trim();
        String cleaned = REGEX_REQ_PAREN.matcher(withoutExtras).replaceAll("").trim();
        Matcher matcher = REGEX_REQ_PARSE.matcher(cleaned);
        if (!matcher.find()) {
            return new Pair<>(cleaned, Collections.<Pair<String, String>>emptyList());
        }
        return new Pair<>(matcher.group(1), parseSpecs(matcher.group(2)));
    }

    private List<Pair<String, String>> parseSpecs(String specsString) {
        ArrayList<Pair<String, String>> specs = new ArrayList<>();
        if (specsString.trim().isEmpty()) {
            return specs;
        }
        for (String part : specsString.split(",", -1)) {
            Matcher matcher = REGEX_REQ_SPECS.matcher(part.trim());
            if (matcher.find()) {
                specs.add(new Pair<>(matcher.group(1), matcher.group(2).trim()));
            }
        }
        return specs;
    }

    private List<String> parseDependenciesFromMetadata(File metadataFile) {
        ArrayList<String> dependencies = new ArrayList<>();
        try {
            PluginFiles.forEachLine(metadataFile, MAX_METADATA_BYTES, line -> {
                if (!line.startsWith("Requires-Dist:")) {
                    return;
                }
                String value = line.substring(14).trim();
                if (value.contains(";")) {
                    String[] parts = value.split(";", 2);
                    if (isMarkerCompatible(parts[1].trim())) {
                        dependencies.add(parts[0].trim());
                    }
                } else {
                    dependencies.add(value);
                }
            });
        } catch (Exception e) {
            FileLog.e("PipController: Failed to parse metadata", e);
        }
        return dependencies;
    }

    private boolean isMarkerCompatible(String marker) {
        return new MarkerParser(marker).parse();
    }

    private File findMetadataFile(String pkg, String version) {
        try {
            File[] distInfo = getLibPath(pkg, version).listFiles((dir, name) -> name.endsWith(".dist-info"));
            return distInfo != null && distInfo.length > 0 ? new File(distInfo[0], "METADATA") : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Evaluates PEP 508 environment markers ("python_version >= '3.8' and sys_platform != 'win32'"). */
    public static final class MarkerParser {
        private int position;
        private final List<String> tokens = new ArrayList<>();

        public MarkerParser(String marker) {
            Matcher matcher = REGEX_MARKER_TOKEN.matcher(marker);
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
        }

        public final boolean parse() {
            if (tokens.isEmpty()) {
                return true;
            }
            try {
                return parseOr();
            } catch (Exception e) {
                FileLog.e("PipController: Failed to evaluate marker: " + android.text.TextUtils.join(" ", tokens), e);
                return true;
            }
        }

        private boolean parseOr() {
            boolean result = parseAnd();
            while (matchKeyword("or")) {
                boolean right = parseAnd();
                result = result || right;
            }
            return result;
        }

        private boolean parseAnd() {
            boolean result = parseFactor();
            while (matchKeyword("and")) {
                boolean right = parseFactor();
                result = result && right;
            }
            return result;
        }

        private boolean parseFactor() {
            if (match("(")) {
                boolean result = parseOr();
                match(")");
                return result;
            }
            return parseComparison();
        }

        private boolean parseComparison() {
            String left = next();
            String operator = left != null ? nextOperator() : null;
            String right = operator != null ? next() : null;
            if (right == null) {
                return true;
            }
            return evaluateComparison(left, operator, right);
        }

        private String nextOperator() {
            String token = peek(0);
            if (token == null) {
                return null;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("not")) {
                String following = peek(1);
                if (following != null && following.toLowerCase(Locale.ROOT).equals("in")) {
                    position += 2;
                    return "not in";
                }
            }
            switch (lower) {
                case "<":
                case ">":
                case "!=":
                case "<=":
                case "==":
                case ">=":
                case "in":
                case "~=":
                case "===":
                    position++;
                    return lower;
                default:
                    return null;
            }
        }

        private boolean evaluateComparison(String left, String operator, String right) {
            String leftVariable = markerVariableName(left);
            String rightVariable = markerVariableName(right);
            // unknown variables never exclude a dependency
            if (leftVariable != null && markerValue(leftVariable) == null || rightVariable != null && markerValue(rightVariable) == null) {
                return true;
            }
            String leftValue = leftVariable != null ? markerValue(leftVariable) : unquote(left);
            String rightValue = rightVariable != null ? markerValue(rightVariable) : unquote(right);
            boolean leftIsVersion = "python_version".equals(leftVariable) || "python_full_version".equals(leftVariable);
            boolean rightIsVersion = "python_version".equals(rightVariable) || "python_full_version".equals(rightVariable);
            if (leftIsVersion && !rightIsVersion) {
                return INSTANCE.checkVersionSatisfies(leftValue, Collections.singletonList(new Pair<>(operator, rightValue)));
            }
            if (rightIsVersion && !leftIsVersion) {
                return INSTANCE.checkVersionSatisfies(rightValue, Collections.singletonList(new Pair<>(invertVersionOperator(operator), leftValue)));
            }
            switch (operator) {
                case "==":
                case "===":
                    return leftValue.equalsIgnoreCase(rightValue);
                case "!=":
                    return !leftValue.equalsIgnoreCase(rightValue);
                case "in":
                    return rightValue.toLowerCase(Locale.ROOT).contains(leftValue.toLowerCase(Locale.ROOT));
                case "not in":
                    return !rightValue.toLowerCase(Locale.ROOT).contains(leftValue.toLowerCase(Locale.ROOT));
                default:
                    return true;
            }
        }

        private String markerVariableName(String token) {
            if (token.startsWith("'") || token.startsWith("\"")) {
                return null;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            return REGEX_MARKER_VARIABLE.matcher(lower).matches() ? lower : null;
        }

        private String markerValue(String name) {
            if (name == null) {
                return null;
            }
            switch (name) {
                case "os_name":
                    return ENV_OS_NAME;
                case "python_version": {
                    String[] parts = INSTANCE.getPythonVersion().split("\\.", -1);
                    return parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];
                }
                case "python_full_version":
                    return INSTANCE.getPythonVersion();
                case "extra":
                    return "";
                case "sys_platform":
                    return ENV_SYS_PLATFORM;
                case "implementation_name":
                    return "cpython";
                case "platform_system":
                    return ENV_PLATFORM_SYSTEM;
                case "platform_python_implementation":
                    return "CPython";
                default:
                    return null;
            }
        }

        private String invertVersionOperator(String operator) {
            switch (operator) {
                case "<":
                    return ">";
                case ">":
                    return "<";
                case "<=":
                    return ">=";
                case ">=":
                    return "<=";
                default:
                    return operator;
            }
        }

        private String unquote(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if (first == '\'' && last == '\'' || first == '"' && last == '"') {
                    return value.substring(1, value.length() - 1);
                }
            }
            return value;
        }

        private boolean match(String expected) {
            if (!expected.equals(peek(0))) {
                return false;
            }
            position++;
            return true;
        }

        private boolean matchKeyword(String expected) {
            String token = peek(0);
            if (token == null || !token.toLowerCase(Locale.ROOT).equals(expected)) {
                return false;
            }
            position++;
            return true;
        }

        private String next() {
            String token = peek(0);
            if (token != null) {
                position++;
            }
            return token;
        }

        private String peek(int offset) {
            int index = position + offset;
            return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
        }
    }

    public final boolean checkVersionSatisfies(String version, List<Pair<String, String>> specs) {
        for (Pair<String, String> spec : specs) {
            String operator = spec.getFirst();
            String target = spec.getSecond();
            int cmp = VersionComparator.INSTANCE.compare(version, target);
            boolean ok;
            switch (operator) {
                case "<":
                    ok = cmp < 0;
                    break;
                case ">":
                    ok = cmp > 0;
                    break;
                case "<=":
                    ok = cmp <= 0;
                    break;
                case ">=":
                    ok = cmp >= 0;
                    break;
                case "==":
                    ok = isWildcardVersionSpec(target) ? matchesVersionWildcard(version, target) : cmp == 0;
                    break;
                case "!=":
                    ok = !(isWildcardVersionSpec(target) ? matchesVersionWildcard(version, target) : cmp == 0);
                    break;
                case "===":
                    ok = version.equals(target);
                    break;
                case "~=": {
                    // compatible release: ~=1.4.2 means >=1.4.2, ==1.4.*
                    ok = cmp >= 0;
                    List<String> parts = new ArrayList<>(Arrays.asList(target.split("\\.", -1)));
                    if (ok && parts.size() >= 2) {
                        parts.remove(parts.size() - 1);
                        Integer last = toIntOrNull(parts.get(parts.size() - 1));
                        if (last != null) {
                            parts.set(parts.size() - 1, String.valueOf(last + 1));
                            ok = VersionComparator.INSTANCE.compare(version, android.text.TextUtils.join(".", parts)) < 0;
                        }
                    }
                    break;
                }
                default:
                    ok = true;
                    break;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private boolean isWildcardVersionSpec(String spec) {
        return REGEX_VERSION_WILDCARD.matcher(spec.trim()).find();
    }

    private List<String> filterPreReleases(List<String> versions, List<Pair<String, String>> specs) {
        if (versions.isEmpty() || specsAllowPreRelease(specs)) {
            return versions;
        }
        ArrayList<String> stable = new ArrayList<>();
        for (String version : versions) {
            if (!isPreReleaseVersion(version)) {
                stable.add(version);
            }
        }
        return stable.isEmpty() ? versions : stable;
    }

    private boolean specsAllowPreRelease(List<Pair<String, String>> specs) {
        for (Pair<String, String> spec : specs) {
            if (isPreReleaseVersion(spec.getSecond())) {
                return true;
            }
        }
        return false;
    }

    private boolean isPreReleaseVersion(String version) {
        for (String part : parseVersion(version).getParts()) {
            switch (part) {
                case "a":
                case "b":
                case "c":
                case "rc":
                case "dev":
                case "beta":
                case "alpha":
                    return true;
            }
        }
        return false;
    }

    private boolean matchesVersionWildcard(String version, String spec) {
        String trimmed = spec.trim();
        int plus = trimmed.indexOf('+');
        String prefix = plus >= 0 ? trimmed.substring(0, plus) : trimmed;
        for (String suffix : new String[]{".*", "-*", "_*"}) {
            if (prefix.endsWith(suffix)) {
                prefix = prefix.substring(0, prefix.length() - suffix.length());
            }
        }
        int end = prefix.length();
        while (end > 0 && ".-_".indexOf(prefix.charAt(end - 1)) >= 0) {
            end--;
        }
        prefix = prefix.substring(0, end);
        ParsedVersion specVersion = parseVersion(prefix);
        ParsedVersion actualVersion = parseVersion(version);
        if (prefix.contains("!") && specVersion.getEpoch() != actualVersion.getEpoch()) {
            return false;
        }
        List<Integer> expected = parseVersionReleaseParts(prefix);
        if (expected.isEmpty()) {
            return true;
        }
        List<Integer> actual = parseVersionReleaseParts(version);
        if (actual.size() < expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!actual.get(i).equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> parseVersionReleaseParts(String version) {
        ArrayList<Integer> result = new ArrayList<>();
        for (String part : parseVersion(version).getPublicVersion().split("[.\\-_]", -1)) {
            Integer value = toIntOrNull(part);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private static Integer toIntOrNull(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Fails the download once more than {@code maxBytes} were read. */
    public static final class SizeLimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long maxBytes;
        private long total;

        public SizeLimitedInputStream(InputStream inputStream, long maxBytes) {
            this.delegate = inputStream;
            this.maxBytes = maxBytes;
        }

        private int track(int read) throws IOException {
            if (read > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Download exceeds maximum allowed size of " + maxBytes + " bytes");
                }
            }
            return read;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                track(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return track(delegate.read(b, off, len));
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private String calculateSha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public final ParsedVersion parseVersion(String version) {
        String normalized = version.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("v")) {
            normalized = normalized.substring(1);
        }
        int plus = normalized.indexOf('+');
        if (plus >= 0) {
            normalized = normalized.substring(0, plus);
        }
        String[] epochSplit = normalized.split("!", 2);
        int epoch = 0;
        if (epochSplit.length == 2) {
            Integer value = toIntOrNull(epochSplit[0]);
            epoch = value != null ? value : 0;
        }
        String publicVersion = epochSplit.length == 2 ? epochSplit[1] : epochSplit[0];
        ArrayList<String> parts = new ArrayList<>();
        Matcher matcher = REGEX_VERSION_SPLIT.matcher(publicVersion);
        while (matcher.find()) {
            parts.add(matcher.group());
        }
        return new ParsedVersion(epoch, publicVersion, parts);
    }

    /** PEP 440-ish ordering: dev &lt; alpha &lt; beta &lt; rc &lt; release &lt; post. */
    public static final class VersionComparator implements Comparator<String> {
        public static final VersionComparator INSTANCE = new VersionComparator();

        private VersionComparator() {
        }

        @Override
        public int compare(String v1, String v2) {
            ParsedVersion a = PipController.INSTANCE.parseVersion(v1);
            ParsedVersion b = PipController.INSTANCE.parseVersion(v2);
            int cmp = Integer.compare(a.getEpoch(), b.getEpoch());
            if (cmp != 0) {
                return cmp;
            }
            List<String> partsA = a.getParts();
            List<String> partsB = b.getParts();
            int length = Math.max(partsA.size(), partsB.size());
            for (int i = 0; i < length; i++) {
                String s1 = i < partsA.size() ? partsA.get(i) : null;
                String s2 = i < partsB.size() ? partsB.get(i) : null;
                if (s1 == null && s2 != null && toIntOrNull(s2) != null) {
                    s1 = "0";
                }
                if (s2 == null && s1 != null && toIntOrNull(s1) != null) {
                    s2 = "0";
                }
                if (s1 == null) {
                    s1 = "";
                }
                if (s2 == null) {
                    s2 = "";
                }
                if (s1.equals(s2)) {
                    continue;
                }
                Integer n1 = toIntOrNull(s1);
                Integer n2 = toIntOrNull(s2);
                if (n1 != null && n2 != null) {
                    int c = Integer.compare(n1, n2);
                    if (c != 0) {
                        return c;
                    }
                } else {
                    if (n1 != null) {
                        return 1;
                    }
                    if (n2 != null) {
                        return -1;
                    }
                    int w1 = getWeight(s1);
                    int w2 = getWeight(s2);
                    if (w1 != w2) {
                        return Integer.compare(w1, w2);
                    }
                    int c = s1.compareTo(s2);
                    if (c != 0) {
                        return c;
                    }
                }
            }
            return 0;
        }

        private int getWeight(String s) {
            switch (s) {
                case "post":
                    return 110;
                case "":
                    return 100;
                case "rc":
                case "c":
                    return 80;
                case "beta":
                case "b":
                    return 70;
                case "alpha":
                case "a":
                    return 60;
                case "dev":
                    return 50;
                default:
                    return 0;
            }
        }
    }
}
