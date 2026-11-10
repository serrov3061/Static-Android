package com.exteragram.messenger.icons;

import com.exteragram.messenger.export.output.FileManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * exteraGram icon pack storage (IconPackStorage.kt): custom packs live in files/icon_packs/&lt;packId&gt;/ with a
 * metadata.json ({schemaVersion, packName, packId, author, version, icons: {resourceName: file}}) next to the icon files.
 * Packs are shared as ".icons" ZIP archives.
 *
 * exteraGram reads archives with its own org.simplifiles library; the build it bundles is not published (no Maven
 * release matches its API) and needs java.nio desugaring, so StaticGram uses java.util.zip with the same
 * SecurityPolicy: at most 3000 entries, 100 MB uncompressed in total, 10 MB per file, compression ratio 250,
 * no absolute / traversing / duplicate paths, only STORED and DEFLATED entries; metadata.json is limited to 256 KB.
 * The coroutine functions of exteraGram are blocking here; callers run them off the main thread.
 */
public final class IconPackStorage {

    public static final IconPackStorage INSTANCE = new IconPackStorage();

    private static final String METADATA = "metadata.json";
    private static final long MAX_METADATA_SIZE = 262144;
    private static final long MAX_ENTRIES = 3000;
    private static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 104857600;
    private static final long MAX_SINGLE_FILE_SIZE = 10485760;
    private static final double MAX_COMPRESSION_RATIO = 250.0d;
    private static final Pattern RESOURCE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private static volatile File iconPacksDirectory;
    private static Map<String, IconPack> cachedCustomPacks;
    private static final HashMap<String, IconPack> cachedPacksById = new HashMap<>();

    private IconPackStorage() {
    }

    public static final class IconPackStorageException extends Exception {
        private final IconPackStorageError error;

        public IconPackStorageException(IconPackStorageError error) {
            super(error.name());
            this.error = error;
        }

        public IconPackStorageError getError() {
            return error;
        }
    }

    public File getIconPacksDirectory() {
        File dir = iconPacksDirectory;
        if (dir != null) {
            return dir;
        }
        dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "icon_packs");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        iconPacksDirectory = dir;
        return dir;
    }

    private static boolean isPlainFileName(String path) {
        if (path.isEmpty() || ".".equals(path) || "..".equals(path)) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == 0 || c == '/' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    boolean isValidPackId(String packId) {
        return packId != null && !packId.trim().isEmpty() && packId.indexOf('/') < 0 && packId.indexOf('\\') < 0
                && !".".equals(packId) && !"..".equals(packId);
    }

    /** SimpliDirectory.resolveInside: the file of {@code relative} under {@code root}, refusing paths that leave it. */
    private static File resolveInside(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.indexOf('\0') >= 0 || relative.startsWith("/") || relative.startsWith("\\")) {
            throw new SecurityException("Unsafe path: " + relative);
        }
        File canonicalRoot = root.getCanonicalFile();
        File file = new File(canonicalRoot, relative).getCanonicalFile();
        if (!file.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new SecurityException("Path escapes the pack: " + relative);
        }
        return file;
    }

    private static String relativePath(File file, File root) {
        String path = file.getPath().substring(root.getPath().length() + 1);
        return File.separatorChar == '/' ? path : path.replace(File.separatorChar, '/');
    }

    IconPack parseMetadata(JSONObject json, File packRoot, File location) throws JSONException, IOException {
        String name = json.getString("packName");
        String id = json.getString("packId");
        if (!isValidPackId(id)) {
            throw new SecurityException("Invalid icon pack id: " + id);
        }
        String author = json.optString("author", "Unknown");
        String version = json.optString("version", "1.0");
        JSONObject iconsJson = json.optJSONObject("icons");
        LinkedHashMap<String, String> icons = new LinkedHashMap<>();
        File canonicalRoot = packRoot != null ? packRoot.getCanonicalFile() : null;
        if (iconsJson != null) {
            Iterator<String> keys = iconsJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = iconsJson.getString(key);
                if (canonicalRoot != null) {
                    File file = resolveInside(canonicalRoot, value);
                    if (!isPlainFileName(value)) {
                        value = relativePath(file, canonicalRoot);
                    }
                }
                icons.put(key, value);
            }
        }
        return new IconPack(id, name, author, version, icons, null, location);
    }

    private IconPack parseMetadataFile(File file) {
        try {
            return parseMetadata(new JSONObject(readText(file, MAX_METADATA_SIZE)), file.getParentFile(), null);
        } catch (Exception e) {
            FileLog.e("Error parsing metadata.json", e);
            return null;
        }
    }

    private static String readText(File file, long maxBytes) throws IOException {
        long length = file.length();
        if (length > maxBytes) {
            throw new IOException("File is too large: " + file);
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, length));
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("File is too large: " + file);
                }
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static void writeTextAtomic(File file, String text) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (out instanceof FileOutputStream) {
                ((FileOutputStream) out).getFD().sync();
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Failed to replace " + file);
        }
    }

    // region Archives (org.simplifiles SecurityPolicy / ZipArchiveExtractor / ZipArchiveWriter)

    /** Validates the archive like simplifiles ArchiveValidator (first issue wins) and extracts it into {@code targetDir}. */
    private static void extractArchive(File archive, File targetDir) throws IOException, IconPackStorageException {
        try (ZipFile zip = new ZipFile(archive)) {
            ArrayList<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                entries.add(e.nextElement());
                if (entries.size() > MAX_ENTRIES) {
                    throw new IconPackStorageException(IconPackStorageError.TOO_MANY_FILES);
                }
            }
            HashSet<String> paths = new HashSet<>();
            HashSet<String> filePaths = new HashSet<>();
            HashSet<String> fileParents = new HashSet<>();
            HashSet<String> directories = new HashSet<>();
            long total = 0;
            for (ZipEntry entry : entries) {
                String normalized = normalizeEntryPath(entry.getName());
                if (normalized == null) {
                    throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                }
                int method = entry.getMethod();
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                }
                if (!paths.add(normalized)) {
                    throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                }
                if (entry.isDirectory()) {
                    if (filePaths.contains(normalized) || hasAncestor(normalized, filePaths)) {
                        throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                    }
                    directories.add(normalized);
                    continue;
                }
                if (directories.contains(normalized) || hasAncestor(normalized, filePaths) || fileParents.contains(normalized)) {
                    throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                }
                filePaths.add(normalized);
                int slash = normalized.indexOf('/');
                while (slash >= 0) {
                    fileParents.add(normalized.substring(0, slash));
                    slash = normalized.indexOf('/', slash + 1);
                }
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0) {
                    throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                }
                if (size > MAX_SINGLE_FILE_SIZE) {
                    throw new IconPackStorageException(IconPackStorageError.FILE_TOO_LARGE);
                }
                if (compressed >= 0 && size > 0) {
                    double ratio = compressed == 0 ? Double.POSITIVE_INFINITY : (double) size / compressed;
                    if (ratio > MAX_COMPRESSION_RATIO) {
                        throw new IconPackStorageException(IconPackStorageError.COMPRESSION_RATIO_TOO_HIGH);
                    }
                }
                total += size;
            }
            if (total > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                throw new IconPackStorageException(IconPackStorageError.ARCHIVE_TOO_LARGE);
            }

            File root = targetDir.getCanonicalFile();
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IconPackStorageException(IconPackStorageError.STORAGE_ERROR);
            }
            byte[] buffer = new byte[16384];
            for (ZipEntry entry : entries) {
                String normalized = normalizeEntryPath(entry.getName());
                File out = resolveInside(root, normalized);
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IconPackStorageException(IconPackStorageError.STORAGE_ERROR);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IconPackStorageException(IconPackStorageError.STORAGE_ERROR);
                }
                long limit = entry.getSize();
                try (InputStream in = zip.getInputStream(entry); OutputStream os = new FileOutputStream(out)) {
                    long written = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        written += read;
                        if (written > limit) { // the central directory lied about the size
                            throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
                        }
                        os.write(buffer, 0, read);
                    }
                }
            }
        } catch (ZipException e) {
            FileLog.e(e);
            throw new IconPackStorageException(IconPackStorageError.INVALID_ARCHIVE);
        }
    }

    private static boolean hasAncestor(String path, Set<String> existing) {
        int slash = path.indexOf('/');
        while (slash >= 0) {
            if (existing.contains(path.substring(0, slash))) {
                return true;
            }
            slash = path.indexOf('/', slash + 1);
        }
        return false;
    }

    /** simplifiles ArchivePathAnalyzer: normalized relative path, or null for empty, absolute or traversing paths. */
    private static String normalizeEntryPath(String name) {
        if (name == null) {
            return null;
        }
        String path = name.replace('\\', '/');
        if (path.isEmpty() || path.indexOf('\0') >= 0 || path.startsWith("/") || (path.length() > 1 && path.charAt(1) == ':')) {
            return null;
        }
        ArrayList<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return android.text.TextUtils.join("/", parts);
    }

    /** SimpliDirectory.zipTo with ArchiveSaveOptions(compressionLevel = 0): files sorted by path. */
    private static void zipDirectory(File dir, File target) throws IOException {
        File root = dir.getCanonicalFile();
        ArrayList<File> files = new ArrayList<>();
        collectFiles(root, files);
        java.util.Collections.sort(files, (a, b) -> a.getPath().compareTo(b.getPath()));
        byte[] buffer = new byte[16384];
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target))) {
            zos.setLevel(0);
            for (File file : files) {
                zos.putNextEntry(new ZipEntry(relativePath(file, root)));
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        return file.delete() || !file.exists();
    }

    private static boolean copyRecursively(File from, File to) {
        try {
            if (from.isDirectory()) {
                if (!to.isDirectory() && !to.mkdirs()) {
                    return false;
                }
                File[] children = from.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!copyRecursively(child, new File(to, child.getName()))) {
                            return false;
                        }
                    }
                }
                return true;
            }
            if (to.exists()) {
                return false; // OverwritePolicy.ERROR
            }
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // endregion

    private JSONObject extractPackArchive(File file, File targetDir) throws Exception {
        extractArchive(file, targetDir);
        File metadata = new File(targetDir, METADATA);
        if (!metadata.isFile()) {
            FileLog.e("Icon pack archive does not contain metadata.json");
            throw new IconPackStorageException(IconPackStorageError.MISSING_METADATA);
        }
        if (metadata.length() > MAX_METADATA_SIZE) {
            FileLog.e("Icon pack metadata.json is too large");
            throw new IconPackStorageException(IconPackStorageError.METADATA_TOO_LARGE);
        }
        return new JSONObject(readText(metadata, MAX_METADATA_SIZE));
    }

    private IconPackStorageError errorFromException(Exception e) {
        if (e instanceof IconPackStorageException) {
            return ((IconPackStorageException) e).getError();
        }
        if (e instanceof ZipException) {
            return IconPackStorageError.INVALID_ARCHIVE;
        }
        if (e instanceof SecurityException || e instanceof JSONException) {
            return IconPackStorageError.INVALID_METADATA;
        }
        return IconPackStorageError.UNKNOWN;
    }

    private File createTempCacheDirectory(String prefix) {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), prefix + "_" + System.currentTimeMillis());
        dir.mkdirs();
        return dir;
    }

    public synchronized IconPack findPackById(String packId) {
        if (!isValidPackId(packId)) {
            return null;
        }
        Map<String, IconPack> all = cachedCustomPacks;
        if (all != null) {
            IconPack pack = all.get(packId);
            if (pack != null) {
                return pack;
            }
        }
        if (cachedPacksById.containsKey(packId)) {
            return cachedPacksById.get(packId);
        }
        IconPack pack = null;
        File metadata = new File(new File(getIconPacksDirectory(), packId), METADATA);
        if (metadata.isFile()) {
            pack = parseMetadataFile(metadata);
        }
        cachedPacksById.put(packId, pack);
        return pack;
    }

    private void invalidatePack(String packId) {
        cachedCustomPacks = null;
        cachedPacksById.remove(packId);
    }

    private synchronized void invalidateAllPacks() {
        cachedCustomPacks = null;
        cachedPacksById.clear();
    }

    /** The icon file of {@code resourceName} in an installed pack, or null when it is missing or outside the pack. */
    public File resolveIconFile(String packId, String resourceName) {
        if (!isValidPackId(packId) || resourceName == null || !RESOURCE_NAME.matcher(resourceName).matches()) {
            return null;
        }
        try {
            IconPack pack = findPackById(packId);
            String fileName = pack != null ? pack.getIcons().get(resourceName) : null;
            if (fileName == null) {
                return null;
            }
            File root = new File(getIconPacksDirectory(), packId).getCanonicalFile();
            File file = new File(root, fileName).getCanonicalFile();
            if (file.getPath().startsWith(root.getPath() + File.separator) && file.isFile()) {
                return file;
            }
        } catch (Exception e) {
            FileLog.e("Failed to resolve icon file for pack " + packId, e);
        }
        return null;
    }

    /** Zips an installed pack into cache/shared_packs/&lt;name&gt;.icons for sharing (blocking). */
    public File bundlePackBlocking(String packId) {
        IconPack pack;
        if (!isValidPackId(packId) || (pack = findPackById(packId)) == null) {
            return null;
        }
        File dir = new File(getIconPacksDirectory(), packId);
        if (!dir.isDirectory()) {
            return null;
        }
        File shared = new File(ApplicationLoader.applicationContext.getCacheDir(), "shared_packs");
        try {
            deleteRecursively(shared);
            shared.mkdirs();
            File target = new File(shared, FileManager.fileNameFromUserString(pack.getName()) + ".icons");
            zipDirectory(dir, target);
            return target;
        } catch (Exception e) {
            FileLog.e("Failed to bundle pack: " + packId, e);
            return null;
        }
    }

    public synchronized List<IconPack> getCustomPacks() {
        Map<String, IconPack> all = cachedCustomPacks;
        if (all != null) {
            return new ArrayList<>(all.values());
        }
        ArrayList<IconPack> packs = new ArrayList<>();
        File[] dirs = getIconPacksDirectory().listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File dir : dirs) {
                File metadata = new File(dir, METADATA);
                if (metadata.isFile()) {
                    IconPack pack = parseMetadataFile(metadata);
                    if (pack != null) {
                        packs.add(pack);
                    }
                }
            }
        }
        LinkedHashMap<String, IconPack> map = new LinkedHashMap<>(Math.max(16, packs.size() * 2));
        for (IconPack pack : packs) {
            map.put(pack.getId(), pack);
        }
        cachedCustomPacks = map;
        cachedPacksById.clear();
        return packs;
    }

    public synchronized boolean saveIconPackMetadata(IconPack iconPack) {
        if (!isValidPackId(iconPack.getId())) {
            FileLog.e("Invalid icon pack id: " + iconPack.getId());
            return false;
        }
        try {
            File dir = new File(getIconPacksDirectory(), iconPack.getId());
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            JSONObject json = new JSONObject();
            json.put("schemaVersion", 1);
            json.put("packName", iconPack.getName());
            json.put("packId", iconPack.getId());
            json.put("author", iconPack.getAuthor());
            json.put("version", iconPack.getVersion());
            JSONObject icons = new JSONObject();
            for (Map.Entry<String, String> entry : iconPack.getIcons().entrySet()) {
                icons.put(entry.getKey(), entry.getValue());
            }
            json.put("icons", icons);
            writeTextAtomic(new File(dir, METADATA), json.toString(4));
            invalidatePack(iconPack.getId());
            return true;
        } catch (Exception e) {
            FileLog.e("Error saving metadata", e);
            return false;
        }
    }

    public synchronized void deletePack(String packId) {
        if (!isValidPackId(packId)) {
            return;
        }
        File dir = new File(getIconPacksDirectory(), packId);
        if (dir.exists()) {
            deleteRecursively(dir);
            invalidatePack(packId);
        }
    }

    /** Installs (or replaces) a pack from a ".icons" archive (blocking). */
    public IconPackStorageResult<Void> installPack(File file) {
        File temp = createTempCacheDirectory("install");
        try {
            String id = parseMetadata(extractPackArchive(file, temp), temp, null).getId();
            File target = new File(getIconPacksDirectory(), id);
            File trash = new File(getIconPacksDirectory(), id + "_trash_" + System.currentTimeMillis());
            if (target.exists() && !target.renameTo(trash) && !deleteRecursively(target)) {
                return new IconPackStorageResult.Failure<>(IconPackStorageError.STORAGE_ERROR);
            }
            if (!temp.renameTo(target) && !copyRecursively(temp, target)) {
                if (trash.exists()) {
                    deleteRecursively(target);
                    trash.renameTo(target);
                }
                return new IconPackStorageResult.Failure<>(IconPackStorageError.STORAGE_ERROR);
            }
            if (trash.exists()) {
                deleteRecursively(trash);
            }
            invalidateAllPacks();
            return new IconPackStorageResult.Success<>(null);
        } catch (IconPackStorageException e) {
            FileLog.e("Pack installation failed: " + e.getError());
            return new IconPackStorageResult.Failure<>(e.getError());
        } catch (Exception e) {
            FileLog.e("Pack installation failed", e);
            return new IconPackStorageResult.Failure<>(errorFromException(e));
        } finally {
            deleteRecursively(temp);
        }
    }

    /** Extracts a ".icons" archive into a temporary directory for the install preview (blocking). */
    public IconPackStorageResult<IconPack> parsePackFromZip(File file) {
        File temp = createTempCacheDirectory("preview");
        try {
            return new IconPackStorageResult.Success<>(parseMetadata(extractPackArchive(file, temp), temp, temp));
        } catch (IconPackStorageException e) {
            FileLog.e("Failed to parse pack for preview: " + e.getError());
            deleteRecursively(temp);
            return new IconPackStorageResult.Failure<>(e.getError());
        } catch (Exception e) {
            FileLog.e("Failed to parse pack for preview", e);
            deleteRecursively(temp);
            return new IconPackStorageResult.Failure<>(errorFromException(e));
        }
    }

    static String extensionOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    static String lowerExtension(File file) {
        return extensionOf(file).toLowerCase(Locale.ROOT);
    }
}
