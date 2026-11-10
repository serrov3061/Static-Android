package com.exteragram.messenger.debug;

import android.app.Activity;
import android.os.Debug;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * exteraGram 13.1.1: Java heap limit watcher and heap dumper. While in the foreground it checks the used
 * heap every 5 s; above the limit (after a forced GC, at most every 30 s) it offers to dump the heap
 * (.hprof zipped) and share it. Off by default; when off nothing runs.
 * StaticGram addition: optional raw heap dump when the app dies with OutOfMemoryError
 * (called from SgCrashReporter), zipped on the next launch.
 */
public final class HeapMonitor {

    public static final HeapMonitor INSTANCE = new HeapMonitor();

    private static final String OOM_PREFIX = "oom_";
    private static volatile LimitWatcher watcher;
    private static volatile boolean dumping;
    private static WeakReference<AlertDialog> alertRef;
    private static File dumpsDir;

    public interface IntCallback {
        void run(int value);
    }

    private HeapMonitor() {
    }

    public static void init() {
        if (DebugConfig.getHeapMonitorEnabled()) {
            watcher = new LimitWatcher();
        }
        if (DebugConfig.getHeapDumpOnOom()) {
            Utilities.globalQueue.postRunnable(INSTANCE::packOomDumps, 5000);
        }
    }

    public static boolean isRunning() {
        return watcher != null;
    }

    public synchronized File getDumpsDir() {
        if (dumpsDir == null) {
            dumpsDir = DebugFiles.debugFilesDir("heapdumps");
        }
        return dumpsDir;
    }

    public long getPromptStepBytes() {
        return Runtime.getRuntime().maxMemory() / 8;
    }

    public void setEnabled(boolean enabled) {
        if (DebugConfig.getHeapMonitorEnabled() == enabled) {
            return;
        }
        DebugConfig.setHeapMonitorEnabled(enabled);
        LimitWatcher w = watcher;
        if (w != null) {
            w.stop();
        }
        watcher = enabled ? new LimitWatcher() : null;
    }

    public int getLimitMb() {
        return Utilities.clamp(DebugConfig.getHeapMonitorLimitMb(), getMaxLimitMb(), 64);
    }

    public int getMaxLimitMb() {
        return Math.max(64, ((int) (Runtime.getRuntime().maxMemory() >> 20) / 16 - 1) * 16);
    }

    public void setLimitMb(int limitMb) {
        DebugConfig.setHeapMonitorLimitMb(limitMb);
        LimitWatcher w = watcher;
        if (w != null) {
            w.resetPrompt();
        }
    }

    public long getUsedBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public File getLastDump() {
        return DebugFiles.newest(getDumpsDir(), ".zip");
    }

    public long getDumpsSize() {
        return DebugFiles.dirSize(getDumpsDir());
    }

    public void deleteDumps(Runnable onDone) {
        if (dumping) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            DebugFiles.deleteAll(getDumpsDir());
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public void shareLastDump(Activity activity) {
        File f = getLastDump();
        if (f != null) {
            share(activity, f);
        }
    }

    public void dumpAndShare(Activity activity, Theme.ResourcesProvider resourcesProvider) {
        if (dumping) {
            return;
        }
        dumping = true;
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING, resourcesProvider);
        progress.setMessage("Dumping heap…");
        progress.setCanceledOnTouchOutside(false);
        progress.setCancelable(false);
        try {
            progress.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        Thread thread = new Thread(() -> {
            SystemClock.sleep(300);
            File result = null;
            Throwable error = null;
            try {
                result = dumpHeap(p -> AndroidUtilities.runOnUIThread(() -> {
                    if (p == 0) {
                        progress.setMessage("Compressing…");
                    }
                    progress.setProgress(p);
                }));
            } catch (Throwable t) {
                error = t;
            }
            final File fResult = result;
            final Throwable fError = error;
            AndroidUtilities.runOnUIThread(() -> {
                dumping = false;
                try {
                    progress.dismiss();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (fResult != null) {
                    share(activity, fResult);
                } else if (fError != null) {
                    FileLog.e(fError);
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, "Heap dump failed: " + fError).show();
                }
            });
        }, "heapDump");
        thread.start();
    }

    private void showLimitAlert(long used, long limit) {
        final LaunchActivity activity = LaunchActivity.instance;
        if (watcher == null || dumping || activity == null || activity.isFinishing()) {
            return;
        }
        AlertDialog current = alertRef != null ? alertRef.get() : null;
        if (current != null && current.isShowing()) {
            return;
        }
        long max = Runtime.getRuntime().maxMemory();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Heap limit exceeded")
                .setMessage("Java heap is at " + AndroidUtilities.formatFileSize(used, true, false) + " of " + AndroidUtilities.formatFileSize(max, true, false)
                        + " after GC, over the " + AndroidUtilities.formatFileSize(limit, true, false) + " limit.\n\n"
                        + "The app freezes while dumping. The dump holds everything in memory, so share it only with people you trust.")
                .setPositiveButton("Dump & share", (d, w) -> dumpAndShare(activity, null))
                .setNeutralButton("Turn off", (d, w) -> setEnabled(false))
                .setNegativeButton("Later", null)
                .create();
        alertRef = new WeakReference<>(dialog);
        try {
            dialog.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void collectGarbage() {
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        rt.runFinalization();
        rt.gc();
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private File dumpHeap(IntCallback onProgress) throws Exception {
        File dir = getDumpsDir();
        dir.mkdirs();
        String name = "heap_" + BuildVars.BUILD_VERSION_STRING + "_" + timestamp();
        File hprof = new File(dir, name + ".hprof");
        File part = new File(dir, name + ".zip.part");
        File zip = new File(dir, name + ".zip");
        try {
            collectGarbage();
            LimitWatcher w = watcher;
            if (w != null) {
                w.postponePrompt(getUsedBytes());
            }
            Debug.dumpHprofData(hprof.getAbsolutePath());
            compress(hprof, part, onProgress);
            if (!part.renameTo(zip)) {
                throw new IOException("Can't rename " + part.getName());
            }
            hprof.delete();
            part.delete();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.equals(zip)) {
                        f.delete();
                    }
                }
            }
            return zip;
        } catch (Throwable t) {
            hprof.delete();
            part.delete();
            throw t;
        }
    }

    private static void compress(File source, File target, IntCallback onProgress) throws IOException {
        long total = Math.max(1, source.length());
        if (onProgress != null) {
            onProgress.run(0);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target), 65536));
             FileInputStream in = new FileInputStream(source)) {
            zip.setLevel(1);
            zip.putNextEntry(new ZipEntry(source.getName()));
            byte[] buf = new byte[65536];
            long done = 0;
            int last = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                zip.write(buf, 0, n);
                done += n;
                int p = (int) (done * 100 / total);
                if (p != last) {
                    if (onProgress != null) {
                        onProgress.run(p);
                    }
                    last = p;
                }
            }
            zip.closeEntry();
        }
    }

    private void share(Activity activity, File file) {
        DebugFiles.shareDebugFile(activity, file, "application/zip", "Share heap dump");
    }

    /**
     * StaticGram: called from the uncaught exception handler when the app dies with OutOfMemoryError and
     * "Heap dump on OOM crash" is on. Writes a raw .hprof (no compression, the process is dying);
     * it is zipped on the next launch. Returns the file name or null.
     */
    public String dumpOnOom() {
        if (!DebugConfig.getHeapDumpOnOom() || dumping) {
            return null;
        }
        try {
            File dir = getDumpsDir();
            dir.mkdirs();
            // keep at most one pending raw dump
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith(OOM_PREFIX) && f.getName().endsWith(".hprof")) {
                        f.delete();
                    }
                }
            }
            File hprof = new File(dir, OOM_PREFIX + BuildVars.BUILD_VERSION_STRING + "_" + timestamp() + ".hprof");
            Debug.dumpHprofData(hprof.getAbsolutePath());
            return hprof.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private void packOomDumps() {
        try {
            File[] files = getDumpsDir().listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                String n = f.getName();
                if (!n.startsWith(OOM_PREFIX) || !n.endsWith(".hprof")) {
                    continue;
                }
                String base = n.substring(0, n.length() - ".hprof".length());
                File part = new File(f.getParentFile(), base + ".zip.part");
                File zip = new File(f.getParentFile(), base + ".zip");
                try {
                    compress(f, part, null);
                    if (part.renameTo(zip)) {
                        f.delete();
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                    part.delete();
                    f.delete();
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static final class LimitWatcher implements ForegroundDetector.Listener {

        private final DispatchQueue queue = new DispatchQueue("heapMonitorQueue");
        private final Runnable checkRunnable = this::check;
        private volatile boolean running;
        private long nextPromptBytes;
        private long lastCollectTime;

        LimitWatcher() {
            ForegroundDetector fd = ForegroundDetector.getInstance();
            if (fd != null) {
                fd.addListener(this);
            }
            updateRunning();
        }

        @Override
        public void onBecameForeground() {
            updateRunning();
        }

        @Override
        public void onBecameBackground() {
            updateRunning();
        }

        void stop() {
            ForegroundDetector fd = ForegroundDetector.getInstance();
            if (fd != null) {
                fd.removeListener(this);
            }
            running = false;
            queue.cleanupQueue();
            queue.recycle();
        }

        void resetPrompt() {
            queue.postRunnable(() -> {
                nextPromptBytes = 0;
                lastCollectTime = 0;
            });
        }

        void postponePrompt(long usedBytes) {
            queue.postRunnable(() -> nextPromptBytes = Math.max(nextPromptBytes, usedBytes + INSTANCE.getPromptStepBytes()));
        }

        private void updateRunning() {
            ForegroundDetector fd = ForegroundDetector.getInstance();
            boolean run = fd != null && fd.isForeground();
            if (running == run) {
                return;
            }
            running = run;
            queue.postRunnable(() -> {
                queue.cancelRunnable(checkRunnable);
                if (running) {
                    queue.postRunnable(checkRunnable, 5000);
                }
            });
        }

        private void check() {
            if (!running) {
                return;
            }
            final long limit = ((long) INSTANCE.getLimitMb()) << 20;
            long used = INSTANCE.getUsedBytes();
            if (used >= limit) {
                if (used >= nextPromptBytes && !dumping && !ApplicationLoader.mainInterfacePaused && SystemClock.elapsedRealtime() - lastCollectTime >= 30_000) {
                    lastCollectTime = SystemClock.elapsedRealtime();
                    INSTANCE.collectGarbage();
                    final long usedAfterGc = INSTANCE.getUsedBytes();
                    if (usedAfterGc >= Math.max(limit, nextPromptBytes)) {
                        nextPromptBytes = usedAfterGc + INSTANCE.getPromptStepBytes();
                        AndroidUtilities.runOnUIThread(() -> INSTANCE.showLimitAlert(usedAfterGc, limit));
                    }
                }
            } else {
                nextPromptBytes = 0;
            }
            if (running) {
                queue.postRunnable(checkRunnable, 5000);
            }
        }
    }
}
