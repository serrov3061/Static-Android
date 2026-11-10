package com.exteragram.messenger.icons;

import android.graphics.Bitmap;
import android.system.Os;
import android.system.StructStat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * exteraGram DecodedIconStore: disk cache (cache/icon_bitmaps) of decoded custom pack icons as raw ARGB_8888 pixels
 * with a 16-byte header (magic, width, height, density), keyed by source path, mtime, size and target size.
 * It is pruned to 24 MB once it grows over 32 MB (checked every 4 MB written).
 */
public final class DecodedIconStore {

    public static final DecodedIconStore INSTANCE = new DecodedIconStore();

    private static final int MAGIC = 1163151665;
    private static volatile File directory;
    private static final AtomicLong writtenSincePrune = new AtomicLong(0);
    private static final AtomicBoolean pruning = new AtomicBoolean(false);

    private DecodedIconStore() {
    }

    private File directory() {
        File dir = directory;
        if (dir != null) {
            return dir;
        }
        try {
            dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "icon_bitmaps");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            directory = dir;
            return dir;
        } catch (Exception e) {
            FileLog.e("Failed to open the decoded icon cache", e);
            return null;
        }
    }

    private String entryName(File source, int width, int height, int density) {
        long modified;
        long length;
        try {
            StructStat stat = Os.stat(source.getPath());
            modified = stat.st_mtime;
            length = stat.st_size;
        } catch (Throwable ignore) {
            modified = source.lastModified();
            length = source.length();
        }
        String path = source.getPath();
        long hash = 1125899906842597L;
        for (int i = 0; i < path.length(); i++) {
            hash = path.charAt(i) + 31 * hash;
        }
        return Long.toHexString(31 * (((((((hash * 31) + modified) * 31 + length) * 31 + width) * 31) + height)) + density);
    }

    public Bitmap get(File source, int width, int height, int density) {
        File dir = directory();
        if (dir == null) {
            return null;
        }
        File file = new File(dir, entryName(source, width, height, density));
        long expected = (long) width * height * 4 + 16;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel channel = raf.getChannel();
            if (channel.size() != expected) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect((int) expected).order(ByteOrder.nativeOrder());
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    return null;
                }
            }
            buffer.flip();
            if (buffer.getInt() != MAGIC || buffer.getInt() != width || buffer.getInt() != height) {
                return null;
            }
            buffer.getInt();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            bitmap.setDensity(density);
            return bitmap;
        } catch (FileNotFoundException ignore) {
            return null;
        } catch (Throwable ignore) {
            file.delete();
            return null;
        }
    }

    public void put(File source, int width, int height, int density, Bitmap bitmap) {
        if (bitmap.getWidth() != width || bitmap.getHeight() != height || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return;
        }
        File dir = directory();
        if (dir == null) {
            return;
        }
        File file = new File(dir, entryName(source, width, height, density));
        if (file.isFile()) {
            return;
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        int size = width * height * 4 + 16;
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            buffer.putInt(MAGIC);
            buffer.putInt(width);
            buffer.putInt(height);
            buffer.putInt(density);
            bitmap.copyPixelsToBuffer(buffer);
            buffer.flip();
            try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                FileChannel channel = raf.getChannel();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return;
            }
            if (writtenSincePrune.addAndGet(size) >= 4194304) {
                writtenSincePrune.set(0);
                Utilities.globalQueue.postRunnable(() -> prune(dir));
            }
        } catch (Throwable ignore) {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private void prune(File dir) {
        if (!pruning.compareAndSet(false, true)) {
            return;
        }
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long total = 0;
            for (File f : files) {
                total += f.length();
            }
            if (total > 33554432) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (File f : files) {
                    if (total <= 25165824) {
                        break;
                    }
                    long length = f.length();
                    if (f.delete()) {
                        total -= length;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("Failed to prune the decoded icon cache", e);
        } finally {
            pruning.set(false);
        }
    }
}
