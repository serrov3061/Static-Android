package com.exteragram.messenger.debug;

import android.app.Activity;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseArray;

import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * exteraGram 13.1.1: main-thread freeze watchdog. While the app is in the foreground a background
 * thread pings the main looper every 200 ms; when a ping is not answered within 100 ms it samples the
 * main thread stack every 20 ms until it is, and if the stall exceeded the threshold appends a report
 * (hot frames, stacks, GC, heap, busy threads, NotificationCenter observers, live objects).
 * Off by default; when off nothing runs.
 */
public final class FreezeMonitor {

    public static final FreezeMonitor INSTANCE = new FreezeMonitor();

    private static final String REPORT_NAME = "freeze_report.txt";
    private static final String[] APP_FRAME_PREFIXES = {"org.telegram.", "com.exteragram.", "com.chaquo.", "dev.exterahook."};
    private static final String[] IDLE_TOP_FRAMES = {"android.os.MessageQueue.nativePollOnce", "java.lang.Object.wait", "java.lang.Thread.sleep", "sun.misc.Unsafe.park", "jdk.internal.misc.Unsafe.park", "java.net.", "libcore.io.Linux.", "android.system.Os.", "dalvik.system.VMStack.getThreadStackTrace"};
    private static final String[] COUNTED_CLASSES = {"org.telegram.ui.LaunchActivity", "org.telegram.ui.ActionBar.BaseFragment", "org.telegram.ui.ChatActivity", "org.telegram.ui.DialogsActivity", "org.telegram.ui.ProfileActivity", "org.telegram.ui.Components.ChatActivityEnterView", "org.telegram.ui.Components.EmojiView", "org.telegram.ui.Cells.ChatMessageCell", "org.telegram.messenger.ImageReceiver", "org.telegram.ui.Components.RLottieDrawable", "org.telegram.ui.Components.AnimatedFileDrawable", "org.telegram.ui.Components.AnimatedEmojiDrawable", "android.graphics.Bitmap"};
    private static final String[] GC_STATS = {"art.gc.blocking-gc-count", "art.gc.blocking-gc-time", "art.gc.gc-count", "art.gc.gc-time"};

    private static volatile Watchdog watchdog;
    private static volatile int freezes;
    private static final Object reportLock = new Object();
    private static Map<Integer, String> notificationNames;
    private static File reportsDir;

    private FreezeMonitor() {
    }

    public static void init() {
        if (DebugConfig.getFreezeMonitorEnabled()) {
            watchdog = new Watchdog();
        }
    }

    public static boolean isRunning() {
        return watchdog != null;
    }

    public static int getFreezesCount() {
        return freezes;
    }

    public synchronized File getReportsDir() {
        if (reportsDir == null) {
            reportsDir = DebugFiles.debugFilesDir("freezereports");
        }
        return reportsDir;
    }

    private File getReportFile() {
        return new File(getReportsDir(), REPORT_NAME);
    }

    public void setEnabled(boolean enabled) {
        if (DebugConfig.getFreezeMonitorEnabled() == enabled) {
            return;
        }
        DebugConfig.setFreezeMonitorEnabled(enabled);
        Watchdog w = watchdog;
        if (w != null) {
            w.stop();
        }
        watchdog = enabled ? new Watchdog() : null;
    }

    public int getThresholdMs() {
        return Utilities.clamp(DebugConfig.getFreezeMonitorThresholdMs(), 2000, 200);
    }

    public void setThresholdMs(int thresholdMs) {
        DebugConfig.setFreezeMonitorThresholdMs(thresholdMs);
    }

    public File getReport() {
        File f = getReportFile();
        return f.length() > 0 ? f : null;
    }

    public long getReportsSize() {
        return DebugFiles.dirSize(getReportsDir());
    }

    public void deleteReports(Runnable onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (reportLock) {
                DebugFiles.deleteAll(getReportsDir());
                freezes = 0;
            }
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public void shareReport(Activity activity) {
        Utilities.globalQueue.postRunnable(() -> {
            File copy = null;
            Throwable error = null;
            try {
                copy = copyReport();
            } catch (Throwable t) {
                error = t;
            }
            final File fCopy = copy;
            final Throwable fError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (fCopy != null) {
                    DebugFiles.shareDebugFile(activity, fCopy, "text/plain", "Share freeze report");
                } else if (fError != null) {
                    FileLog.e(fError);
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, "Freeze report failed: " + fError).show();
                }
            });
        });
    }

    private File copyReport() throws Exception {
        synchronized (reportLock) {
            File dir = getReportsDir();
            File target = new File(dir, "freeze_" + BuildVars.BUILD_VERSION_STRING + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!REPORT_NAME.equals(f.getName())) {
                        f.delete();
                    }
                }
            }
            try (FileInputStream in = new FileInputStream(getReportFile()); FileOutputStream out = new FileOutputStream(target, false)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return target;
        }
    }

    private void appendReport(String text) {
        synchronized (reportLock) {
            try {
                getReportsDir().mkdirs();
                File f = getReportFile();
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                if (f.length() > 2_000_000) {
                    byte[] tail = new byte[1_000_000];
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        raf.seek(raf.length() - tail.length);
                        raf.readFully(tail);
                    }
                    try (FileOutputStream out = new FileOutputStream(f, false)) {
                        out.write("##### (older records trimmed)\n".getBytes(StandardCharsets.UTF_8));
                        out.write(tail);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private void showFreezeBulletin(long durationMs) {
        final LaunchActivity activity = LaunchActivity.instance;
        if (watchdog == null || activity == null || activity.isFinishing()) {
            return;
        }
        try {
            BulletinFactory.global().createSimpleBulletin(R.raw.error, "Main thread froze for " + formatSeconds(durationMs), "Share", () -> shareReport(activity)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.1f s", ms / 1000.0);
    }

    private static String formatMb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String padStart(String s, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < len; i++) {
            sb.append(' ');
        }
        return sb.append(s).toString();
    }

    private static String percent(int count, int total) {
        return padStart(String.valueOf(count * 100 / Math.max(1, total)), 4);
    }

    static String appLine() {
        return "app " + BuildVars.BUILD_VERSION_STRING + " (" + DebugFiles.versionCode() + "), " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    static String heapLine() {
        long used = HeapMonitor.INSTANCE.getUsedBytes();
        long max = Runtime.getRuntime().maxMemory();
        return "java " + formatMb(used) + " / " + formatMb(max) + " (" + (used * 100 / Math.max(1, max)) + "%), native " + formatMb(Debug.getNativeHeapAllocatedSize());
    }

    /** blocking-gc-count, blocking-gc-time, gc-count, gc-time (ART runtime stats). */
    static long[] gcStats() {
        long[] result = new long[GC_STATS.length];
        for (int i = 0; i < GC_STATS.length; i++) {
            try {
                String v = Debug.getRuntimeStat(GC_STATS[i]);
                result[i] = v != null ? Long.parseLong(v.trim()) : 0;
            } catch (Throwable ignore) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static List<String> frameNames(StackTraceElement[] trace, int limit) {
        int n = Math.min(trace.length, limit);
        ArrayList<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(trace[i].toString());
        }
        return list;
    }

    private static String screenName() {
        try {
            BaseFragment f = LaunchActivity.getLastFragmentIncludeMainTabs();
            return f != null ? f.getClass().getName() : "none";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static String fragmentStack() {
        try {
            LaunchActivity a = LaunchActivity.instance;
            List<BaseFragment> stack = a != null && a.getActionBarLayout() != null ? a.getActionBarLayout().getFragmentStack() : null;
            ArrayList<BaseFragment> copy = stack != null ? new ArrayList<>(stack) : new ArrayList<>();
            StringBuilder sb = new StringBuilder("(").append(copy.size()).append(") ");
            for (int i = 0; i < copy.size(); i++) {
                if (i > 0) sb.append(" > ");
                sb.append(copy.get(i).getClass().getSimpleName());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String otherThreads(Thread mainThread) {
        StringBuilder sb = new StringBuilder();
        try {
            int count = 0;
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (t == mainThread || t == Thread.currentThread()) {
                    continue;
                }
                Thread.State state = t.getState();
                if (state != Thread.State.RUNNABLE && state != Thread.State.BLOCKED) {
                    continue;
                }
                List<String> frames = frameNames(e.getValue(), 20);
                if (frames.isEmpty() || startsWithAny(frames.get(0), IDLE_TOP_FRAMES)) {
                    continue;
                }
                sb.append('"').append(t.getName()).append("\" ").append(state).append('\n');
                for (String f : frames) {
                    sb.append("    at ").append(f).append('\n');
                }
                if (++count >= 40) {
                    break;
                }
            }
        } catch (Exception e) {
            sb.append("thread dump failed: ").append(e).append('\n');
        }
        return sb.length() > 0 ? sb.toString() : "(none)\n";
    }

    private static synchronized Map<Integer, String> getNotificationNames() {
        if (notificationNames == null) {
            HashMap<Integer, String> map = new HashMap<>();
            try {
                for (Field f : NotificationCenter.class.getFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                        map.put(f.getInt(null), f.getName());
                    }
                }
            } catch (Throwable ignore) {
            }
            notificationNames = map;
        }
        return notificationNames;
    }

    private static String delegateName(Object delegate) {
        if (delegate == null) {
            return "null";
        }
        Class<?> cls = delegate.getClass();
        if (cls.getName().endsWith("WeakObserversGroupImpl")) {
            try {
                Field f = cls.getDeclaredField("reference");
                f.setAccessible(true);
                Object o = ((WeakReference<?>) f.get(delegate)).get();
                return (o != null ? o.getClass().getName() : "cleared") + " (weak)";
            } catch (Throwable ignore) {
            }
        }
        return cls.getName();
    }

    private static <K> String joinTop(List<Map.Entry<K, Integer>> entries, int limit, boolean valueFirst) {
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(entries.get(i).getKey()).append(' ').append(entries.get(i).getValue());
        }
        return sb.toString();
    }

    private static String observers() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"account", "global"};
        NotificationCenter[] centers = {NotificationCenter.getInstance(UserConfig.selectedAccount), NotificationCenter.getGlobalInstance()};
        for (int c = 0; c < centers.length; c++) {
            try {
                Field field = NotificationCenter.class.getDeclaredField("observers");
                field.setAccessible(true);
                SparseArray<?> array = (SparseArray<?>) field.get(centers[c]);
                LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
                HashMap<String, Integer> byClass = new HashMap<>();
                int total = 0;
                for (int i = 0; i < array.size(); i++) {
                    List<?> list = (List<?>) array.valueAt(i);
                    total += list.size();
                    String idName = getNotificationNames().get(array.keyAt(i));
                    if (idName == null) {
                        idName = String.valueOf(array.keyAt(i));
                    }
                    ids.put(idName, list.size());
                    for (int j = 0; j < list.size(); j++) {
                        String n = delegateName(list.get(j));
                        int dot = n.lastIndexOf('.');
                        if (dot >= 0) n = n.substring(dot + 1);
                        Integer old = byClass.get(n);
                        byClass.put(n, (old != null ? old : 0) + 1);
                    }
                }
                sb.append(names[c]).append(": ").append(total).append(" registrations in ").append(ids.size()).append(" ids\n");
                sb.append("  biggest ids: ").append(joinTop(new ArrayList<>(ids.entrySet()), 8, false)).append('\n');
                sb.append("  by class: ").append(joinTop(new ArrayList<>(byClass.entrySet()), 12, false)).append('\n');
            } catch (Exception e) {
                sb.append(names[c]).append(": failed ").append(e).append('\n');
            }
        }
        return sb.toString();
    }

    private static long[] countInstances(Class<?>[] classes) throws Exception {
        Class<?> vmDebug = Class.forName("dalvik.system.VMDebug");
        try {
            Method m = vmDebug.getDeclaredMethod("countInstancesOfClasses", Class[].class, boolean.class);
            return (long[]) m.invoke(null, classes, true);
        } catch (ReflectiveOperationException e) {
            Method m = vmDebug.getDeclaredMethod("countInstancesOfClass", Class.class, boolean.class);
            long[] result = new long[classes.length];
            for (int i = 0; i < classes.length; i++) {
                result[i] = (Long) m.invoke(null, classes[i], true);
            }
            return result;
        }
    }

    static String liveObjects() {
        try {
            Class<?>[] classes = new Class[COUNTED_CLASSES.length];
            for (int i = 0; i < classes.length; i++) {
                classes[i] = Class.forName(COUNTED_CLASSES[i]);
            }
            HeapMonitor.INSTANCE.collectGarbage();
            long[] counts = countInstances(classes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < classes.length; i++) {
                sb.append(padStart(String.valueOf(counts[i]), 7)).append("  ").append(classes[i].getName()).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "unavailable: " + t + "\n";
        }
    }

    private static String environment() {
        String plugins;
        try {
            ArrayList<String> list = new ArrayList<>();
            for (Plugin p : PluginsController.getInstance().getPlugins().values()) {
                if (p.isEnabled()) {
                    list.add(p.getId() + " " + p.getVersion());
                }
            }
            Collections.sort(list);
            plugins = list.isEmpty() ? "none" : android.text.TextUtils.join(", ", list);
        } catch (Throwable t) {
            plugins = "unavailable: " + t;
        }
        Boolean premium;
        try {
            premium = UserConfig.getInstance(UserConfig.selectedAccount).isPremium();
        } catch (Throwable t) {
            premium = null;
        }
        long uptime = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000;
        ForegroundDetector fd = ForegroundDetector.getInstance();
        return "-- environment --\n" + appLine()
                + "\nprocess uptime " + uptime + " s, foreground " + (fd != null ? fd.isForeground() : null)
                + "\nheap now: " + heapLine()
                + "\naccounts active " + UserConfig.getActivatedAccountsCount() + ", premium " + premium
                + "\nenabled plugins: " + plugins + "\n";
    }

    private static final class Freeze {
        long durationMs;
        int thresholdMs;
        int samples;
        long maxGapMs;
        String screen;
        String fragmentStack;
        long[] gcBefore;
        String heapBefore;
        HashMap<String, Integer> states;
        HashMap<String, Integer> hot;
        HashMap<String, Integer> hotDepth;
        HashMap<String, Integer> topFrames;
        HashMap<List<String>, Integer> stacks;
        String otherThreads;
        String observers;
    }

    private static void inc(Map<String, Integer> map, String key) {
        Integer v = map.get(key);
        map.put(key, (v != null ? v : 0) + 1);
    }

    private static final class Watchdog implements ForegroundDetector.Listener {

        private final DispatchQueue queue = new DispatchQueue("freezeMonitorQueue");
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Thread mainThread = Looper.getMainLooper().getThread();
        private final Runnable checkRunnable = this::check;
        private volatile boolean running;
        private volatile boolean stopped;

        Watchdog() {
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
            stopped = true;
            running = false;
            queue.cleanupQueue();
            queue.recycle();
        }

        private void updateRunning() {
            ForegroundDetector fd = ForegroundDetector.getInstance();
            boolean run = !stopped && fd != null && fd.isForeground();
            if (running == run) {
                return;
            }
            running = run;
            queue.postRunnable(() -> {
                queue.cancelRunnable(checkRunnable);
                if (running) {
                    queue.postRunnable(checkRunnable);
                }
            });
        }

        private void check() {
            if (!running) {
                return;
            }
            try {
                final CountDownLatch ping = new CountDownLatch(1);
                long posted = SystemClock.uptimeMillis();
                if (mainHandler.post(ping::countDown) && !ping.await(100, TimeUnit.MILLISECONDS)) {
                    Freeze freeze = sample(ping, posted);
                    if (freeze != null) {
                        record(freeze);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (running) {
                queue.postRunnable(checkRunnable, 200);
            }
        }

        private Freeze sample(CountDownLatch ping, long posted) throws InterruptedException {
            Freeze f = new Freeze();
            f.thresholdMs = INSTANCE.getThresholdMs();
            f.gcBefore = gcStats();
            f.heapBefore = heapLine();
            f.screen = screenName();
            f.fragmentStack = fragmentStack();
            f.states = new HashMap<>();
            f.hot = new HashMap<>();
            f.hotDepth = new HashMap<>();
            f.topFrames = new HashMap<>();
            f.stacks = new HashMap<>();
            long maxGap = 0;
            long last = posted;
            int samples = 0;
            while (!stopped) {
                long now = SystemClock.uptimeMillis();
                maxGap = Math.max(maxGap, now - last);
                long elapsed = now - posted;
                if (elapsed < 120_000) {
                    inc(f.states, mainThread.getState().name());
                    List<String> frames = frameNames(mainThread.getStackTrace(), 150);
                    samples++;
                    if (!frames.isEmpty()) {
                        inc(f.topFrames, frames.get(0));
                        List<String> key = new ArrayList<>(frames.subList(0, Math.min(40, frames.size())));
                        Integer v = f.stacks.get(key);
                        f.stacks.put(key, (v != null ? v : 0) + 1);
                        for (int i = 0; i < frames.size(); i++) {
                            Integer d = f.hotDepth.get(frames.get(i));
                            if (i > (d != null ? d : -1)) {
                                f.hotDepth.put(frames.get(i), i);
                            }
                        }
                        for (String s : new HashSet<>(frames)) {
                            inc(f.hot, s);
                        }
                    }
                    if (f.otherThreads == null && elapsed + 20 >= f.thresholdMs) {
                        f.otherThreads = otherThreads(mainThread);
                        f.observers = observers();
                    }
                }
                if (ping.await(20, TimeUnit.MILLISECONDS)) {
                    long duration = SystemClock.uptimeMillis() - posted;
                    if (duration < f.thresholdMs) {
                        return null;
                    }
                    f.durationMs = duration;
                    f.samples = samples;
                    f.maxGapMs = maxGap;
                    return f;
                }
                last = now;
            }
            return null;
        }

        private void record(final Freeze f) {
            long[] gcAfter = gcStats();
            int n = ++freezes;
            int samples = f.samples;
            StringBuilder sb = new StringBuilder();
            sb.append("\n=== Freeze #").append(n).append(" at ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append(": ");
            sb.append("main thread blocked ").append(f.durationMs).append(" ms\n");
            sb.append("threshold ").append(f.thresholdMs).append(" ms, ").append(samples).append(" samples every 20 ms from 100 ms\n");
            if (f.maxGapMs > 2000) {
                sb.append("WARNING: the monitor thread itself stalled for ").append(f.maxGapMs).append(" ms, the system probably froze the whole process; this record may be bogus\n");
            }
            sb.append("screen at start: ").append(f.screen).append('\n');
            sb.append("fragment stack ").append(f.fragmentStack).append('\n');
            sb.append("GC during freeze: blocking ").append(gcAfter[0] - f.gcBefore[0]).append(" (").append(gcAfter[1] - f.gcBefore[1]).append(" ms), ");
            sb.append("all ").append(gcAfter[2] - f.gcBefore[2]).append(" (").append(gcAfter[3] - f.gcBefore[3]).append(" ms)\n");
            sb.append("heap before: ").append(f.heapBefore).append('\n');
            sb.append("heap after:  ").append(heapLine()).append('\n');
            sb.append("main thread states: ").append(joinTop(new ArrayList<>(f.states.entrySet()), Integer.MAX_VALUE, false)).append('\n');
            if (samples > 0) {
                sb.append("\n-- hot app frames (share of samples, outermost first) --\n");
                ArrayList<Map.Entry<String, Integer>> hot = new ArrayList<>();
                for (Map.Entry<String, Integer> e : f.hot.entrySet()) {
                    if (e.getValue() * 100 >= samples * 5 && startsWithAny(e.getKey(), APP_FRAME_PREFIXES)) {
                        hot.add(e);
                    }
                }
                Collections.sort(hot, (a, b) -> {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    if (c != 0) {
                        return c;
                    }
                    Integer da = f.hotDepth.get(a.getKey());
                    Integer db = f.hotDepth.get(b.getKey());
                    return Integer.compare(db != null ? db : 0, da != null ? da : 0);
                });
                for (int i = 0; i < Math.min(60, hot.size()); i++) {
                    sb.append(percent(hot.get(i).getValue(), samples)).append("%  ").append(hot.get(i).getKey()).append('\n');
                }
                sb.append("\n-- top of stack --\n");
                ArrayList<Map.Entry<String, Integer>> top = new ArrayList<>(f.topFrames.entrySet());
                Collections.sort(top, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(10, top.size()); i++) {
                    sb.append(percent(top.get(i).getValue(), samples)).append("%  ").append(top.get(i).getKey()).append('\n');
                }
                sb.append("\n-- most frequent stacks --\n");
                ArrayList<Map.Entry<List<String>, Integer>> stacks = new ArrayList<>(f.stacks.entrySet());
                Collections.sort(stacks, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (int i = 0; i < Math.min(5, stacks.size()); i++) {
                    int c = stacks.get(i).getValue();
                    sb.append('[').append(c).append(" samples, ").append(c * 100 / samples).append("%]\n");
                    for (String s : stacks.get(i).getKey()) {
                        sb.append("    at ").append(s).append('\n');
                    }
                }
            }
            if (f.otherThreads != null) {
                sb.append("\n-- other busy threads at the threshold --\n").append(f.otherThreads);
            }
            if (f.observers != null) {
                sb.append("\n-- NotificationCenter observers at the threshold --\n").append(f.observers);
            }
            sb.append('\n');
            sb.append(environment());
            sb.append("\n-- live objects (after GC) --\n");
            sb.append(liveObjects());
            INSTANCE.appendReport(sb.toString());
            AndroidUtilities.runOnUIThread(() -> INSTANCE.showFreezeBulletin(f.durationMs));
        }
    }
}
