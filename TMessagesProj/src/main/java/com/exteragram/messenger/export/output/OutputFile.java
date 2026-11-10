package com.exteragram.messenger.export.output;

import android.util.Log;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Port of exteraGram's export OutputFile (tdesktop Export::Output::File); rewritten by hand, JADX output was broken. */
public class OutputFile {
    public final File _file;
    private boolean _inStats = false;
    private long _offset = 0;
    private final Stats _stats;

    public OutputFile(String path, Stats stats) {
        File file = new File(path);
        this._file = file;
        this._stats = stats;
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            file.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String PrepareRelativePath(String folder, String suggested) {
        if (!new File(folder + "/" + suggested).exists()) {
            return suggested;
        }
        int dot = suggested.lastIndexOf('.');
        int slash = suggested.lastIndexOf('/');
        if (dot <= slash) {
            dot = -1;
        }
        String base = dot >= 0 ? suggested.substring(0, dot) : suggested;
        String ext = dot >= 0 ? suggested.substring(dot) : "";
        int i = 0;
        String result;
        do {
            i++;
            result = base + " (" + i + ")" + ext;
        } while (new File(folder + "/" + result).exists());
        return result;
    }

    public long size() {
        return _offset;
    }

    public boolean empty() {
        return _offset == 0;
    }

    public AbstractWriter.Result writeBlock(String block) {
        return writeBlock(block.getBytes(StandardCharsets.UTF_8));
    }

    public AbstractWriter.Result writeBlock(NativeByteBuffer buffer) {
        AbstractWriter.Result result = writeBlockAttempt(buffer);
        if (!result.isSuccess()) {
            FileLog.e("export: failed to write block to " + _file);
        }
        return result;
    }

    public AbstractWriter.Result writeBlock(byte[] bytes) {
        AbstractWriter.Result result = writeBlockAttempt(bytes);
        if (!result.isSuccess()) {
            FileLog.e("export: failed to write block to " + _file);
        }
        return result;
    }

    private void countFile() {
        if (_stats != null && !_inStats) {
            _inStats = true;
            _stats.incrementFiles();
        }
    }

    public AbstractWriter.Result writeBlockAttempt(String block) {
        return writeBlockAttempt(block.getBytes(StandardCharsets.UTF_8));
    }

    public AbstractWriter.Result writeBlockAttempt(byte[] bytes) {
        countFile();
        if (bytes.length == 0) {
            Log.e("StaticGram", "export: size of block to write was zero!");
            return AbstractWriter.Result.Success();
        }
        try (FileOutputStream out = new FileOutputStream(_file, true)) {
            out.write(bytes);
            _offset += bytes.length;
            if (_stats != null) {
                _stats.incrementBytes(bytes.length);
            }
            return AbstractWriter.Result.Success();
        } catch (Exception e) {
            FileLog.e(e);
            return AbstractWriter.Result.Error();
        }
    }

    public AbstractWriter.Result writeBlockAttempt(NativeByteBuffer buffer) {
        countFile();
        int limit = buffer.buffer.limit();
        if (limit == 0) {
            Log.e("StaticGram", "export: size of block to write was zero!");
            return AbstractWriter.Result.Success();
        }
        try (RandomAccessFile file = new RandomAccessFile(_file, "rw"); FileChannel channel = file.getChannel()) {
            file.seek(file.length());
            channel.write(buffer.buffer);
            _offset += limit;
            if (_stats != null) {
                _stats.incrementBytes(limit);
            }
            return AbstractWriter.Result.Success();
        } catch (Exception e) {
            FileLog.e(e);
            return AbstractWriter.Result.Error();
        }
    }

    public static class Stats {
        private final AtomicInteger _files = new AtomicInteger(0);
        private final AtomicLong _bytes = new AtomicLong(0);

        public void incrementFiles() {
            _files.getAndIncrement();
        }

        public void incrementBytes(int i) {
            _bytes.addAndGet(i);
        }

        public int filesCount() {
            return _files.get();
        }

        public long bytesCount() {
            return _bytes.get();
        }
    }
}
