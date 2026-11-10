package com.exteragram.messenger.plugins.utils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * exteraGram plugins: small replacement for the org.simplifiles helpers exteraGram uses
 * (plain java.io / java.util.zip, with zip-slip and zip-bomb protection for archive extraction).
 */
public final class PluginFiles {

    private PluginFiles() {
    }

    /** Limits for {@link #extractZip}; mirrors simplifiles' SecurityPolicy. */
    public static final class ArchiveLimits {
        public final long maxEntries;
        public final long maxTotalUncompressedSize;
        public final long maxSingleFileSize;
        public final double maxCompressionRatio;

        public ArchiveLimits(long maxEntries, long maxTotalUncompressedSize, long maxSingleFileSize, double maxCompressionRatio) {
            this.maxEntries = maxEntries;
            this.maxTotalUncompressedSize = maxTotalUncompressedSize;
            this.maxSingleFileSize = maxSingleFileSize;
            this.maxCompressionRatio = maxCompressionRatio;
        }
    }

    public static File mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) {
            return dir;
        }
        if (dir.exists()) {
            throw new IOException("Not a directory: " + dir);
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Unable to create directory: " + dir);
        }
        return dir;
    }

    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        boolean ok = true;
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    ok &= deleteRecursively(child);
                }
            }
        }
        return file.delete() && ok;
    }

    /** Creates the directory if needed and removes everything inside it. */
    public static void clean(File dir) throws IOException {
        mkdirs(dir);
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    throw new IOException("Unable to delete " + child);
                }
            }
        }
    }

    public static void touch(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        if (!file.exists()) {
            new FileOutputStream(file, true).close();
        } else {
            file.setLastModified(System.currentTimeMillis());
        }
    }

    public static void copy(File src, File dst, boolean replace) throws IOException {
        if (!src.exists()) {
            throw new IOException("Source does not exist: " + src);
        }
        if (dst.exists()) {
            if (!replace) {
                throw new IOException("Target already exists: " + dst);
            }
            if (!deleteRecursively(dst)) {
                throw new IOException("Unable to replace " + dst);
            }
        }
        if (src.isDirectory()) {
            mkdirs(dst);
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copy(child, new File(dst, child.getName()), replace);
                }
            }
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        try (InputStream in = new FileInputStream(src)) {
            writeFrom(dst, in, Long.MAX_VALUE);
        }
    }

    public static void move(File src, File dst, boolean replace) throws IOException {
        if (!src.exists()) {
            throw new IOException("Source does not exist: " + src);
        }
        if (dst.exists()) {
            if (!replace) {
                throw new IOException("Target already exists: " + dst);
            }
            if (!deleteRecursively(dst)) {
                throw new IOException("Unable to replace " + dst);
            }
        }
        File parent = dst.getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        if (src.renameTo(dst)) {
            return;
        }
        copy(src, dst, true);
        deleteRecursively(src);
    }

    public static String readText(File file, long maxBytes) throws IOException {
        if (file.length() > maxBytes) {
            throw new IOException("File is too large: " + file);
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copyStream(in, out, maxBytes);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void forEachLine(File file, long maxBytes, Consumer<String> action) throws IOException {
        if (file.length() > maxBytes) {
            throw new IOException("File is too large: " + file);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                action.accept(line);
            }
        }
    }

    public static void writeFrom(File file, InputStream in, long maxBytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            copyStream(in, out, maxBytes);
        }
    }

    /** Writes to a temp file next to the target and renames it into place. */
    public static void writeFromAtomic(File file, InputStream in) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        File tmp = new File(parent, "." + file.getName() + ".tmp" + System.nanoTime());
        try {
            writeFrom(tmp, in, Long.MAX_VALUE);
            if (!tmp.renameTo(file)) {
                if (file.exists() && !file.delete()) {
                    throw new IOException("Unable to replace " + file);
                }
                if (!tmp.renameTo(file)) {
                    throw new IOException("Unable to move " + tmp + " to " + file);
                }
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public static void writeTextAtomic(File file, String text) throws IOException {
        writeFromAtomic(file, new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static void zipDirectory(File dir, File zipFile) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("Not a directory: " + dir);
        }
        File parent = zipFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        File tmp = new File(parent, "." + zipFile.getName() + ".tmp" + System.nanoTime());
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmp))) {
                zipInto(out, dir, "");
            }
            move(tmp, zipFile, true);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void zipInto(ZipOutputStream out, File dir, String prefix) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (isSymlink(child)) {
                continue;
            }
            String name = prefix + child.getName();
            if (child.isDirectory()) {
                out.putNextEntry(new ZipEntry(name + "/"));
                out.closeEntry();
                zipInto(out, child, name + "/");
            } else {
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(child)) {
                    copyStream(in, out, Long.MAX_VALUE);
                }
                out.closeEntry();
            }
        }
    }

    /**
     * Extracts a zip archive into {@code targetDir} (the target is replaced). Rejects absolute paths, ".." segments,
     * duplicate entries and entries escaping the target, and enforces the given size/ratio limits.
     */
    public static void extractZip(File archive, File targetDir, ArchiveLimits limits, BooleanSupplier cancelled) throws IOException {
        if (targetDir.exists() && !deleteRecursively(targetDir)) {
            throw new IOException("Unable to replace " + targetDir);
        }
        mkdirs(targetDir);
        String rootPath = targetDir.getCanonicalPath() + File.separator;
        long total = 0;
        long count = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new IOException("Extraction cancelled");
                }
                ZipEntry entry = entries.nextElement();
                if (++count > limits.maxEntries) {
                    throw new IOException("Archive has too many entries");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
                    throw new IOException("Unsafe archive entry: " + entry.getName());
                }
                for (String segment : name.split("/")) {
                    if (segment.equals("..")) {
                        throw new IOException("Unsafe archive entry: " + entry.getName());
                    }
                }
                if (!seen.add(name)) {
                    throw new IOException("Duplicate archive entry: " + entry.getName());
                }
                File out = new File(targetDir, name);
                String outPath = out.getCanonicalPath();
                if (!(outPath + File.separator).startsWith(rootPath)) {
                    throw new IOException("Archive entry escapes the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    mkdirs(out);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    mkdirs(parent);
                }
                long compressed = entry.getCompressedSize();
                long written = 0;
                try (InputStream in = zip.getInputStream(entry); OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        written += read;
                        total += read;
                        if (written > limits.maxSingleFileSize) {
                            throw new IOException("Archive entry is too large: " + entry.getName());
                        }
                        if (total > limits.maxTotalUncompressedSize) {
                            throw new IOException("Archive is too large when extracted");
                        }
                        if (compressed > 0 && written > 1048576 && (double) written / compressed > limits.maxCompressionRatio) {
                            throw new IOException("Suspicious compression ratio: " + entry.getName());
                        }
                        os.write(buffer, 0, read);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursively(targetDir);
            throw e;
        }
    }

    private static void copyStream(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Data exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
    }

    private static boolean isSymlink(File file) {
        try {
            File canonicalParent = file.getParentFile() == null ? file : new File(file.getParentFile().getCanonicalFile(), file.getName());
            return !canonicalParent.getCanonicalFile().equals(canonicalParent.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }
}
