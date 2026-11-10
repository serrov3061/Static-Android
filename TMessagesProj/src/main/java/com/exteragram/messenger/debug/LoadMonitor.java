package com.exteragram.messenger.debug;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.health.HealthStats;
import android.os.health.SystemHealthManager;
import android.os.health.TimerStat;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * exteraGram 13.1.1: background CPU / traffic load monitor. Samples process CPU, per-thread CPU
 * (/proc/self/task/N/stat), uid traffic and battery once a minute (and on foreground/background/screen
 * changes). When a background episode of 5+ min uses more than the set share of one core it writes a
 * JSON report and offers to share it. Off by default; when off nothing runs.
 */
public final class LoadMonitor {

    public static final LoadMonitor INSTANCE = new LoadMonitor();

    private static final String[] STATE_NAMES = {"foreground", "background", "screenOff"};
    private static final String[] SKIPPED_FRAMES = {"java.", "javax.", "kotlin.", "android.", "com.android.", "dalvik.", "libcore.", "sun.", "jdk.", "de.robv.android.xposed.", LoadMonitor.class.getName()};
    private static final String UNATTRIBUTED = "<unattributed>";
    private static final long SAMPLE_INTERVAL_MS = 60_000L;

    private static volatile Sampler sampler;
    private static volatile PendingAlert pendingAlert;
    private static WeakReference<AlertDialog> alertRef;
    private static final HashMap<String, Map<Integer, String>> healthKeyNames = new HashMap<>();
    private static final HashMap<String, String> threadOrigins = new HashMap<>();
    private static final HashMap<String, Integer> originAttempts = new HashMap<>();
    private static File reportsDir;
    private static long clockTicks;

    private LoadMonitor() {
    }

    public static void init() {
        if (DebugConfig.getLoadMonitorEnabled()) {
            sampler = new Sampler();
        }
    }

    public static boolean isRunning() {
        return sampler != null;
    }

    public synchronized File getReportsDir() {
        if (reportsDir == null) {
            reportsDir = DebugFiles.debugFilesDir("loadreports");
        }
        return reportsDir;
    }

    private static long getClockTicks() {
        if (clockTicks <= 0) {
            long t = 0;
            try {
                t = Os.sysconf(OsConstants._SC_CLK_TCK);
            } catch (Throwable ignore) {
            }
            clockTicks = t > 0 ? t : 100;
        }
        return clockTicks;
    }

    public void setEnabled(boolean enabled) {
        if (DebugConfig.getLoadMonitorEnabled() == enabled) {
            return;
        }
        DebugConfig.setLoadMonitorEnabled(enabled);
        Sampler s = sampler;
        if (s != null) {
            s.stop();
        }
        sampler = enabled ? new Sampler() : null;
        if (!enabled) {
            pendingAlert = null;
        }
    }

    public int getCpuPercent() {
        return Utilities.clamp(DebugConfig.getLoadMonitorCpuPercent(), 50, 1);
    }

    public void setCpuPercent(int percent) {
        DebugConfig.setLoadMonitorCpuPercent(percent);
    }

    public String getSummary() {
        Sampler s = sampler;
        String summary = s != null ? s.summary : null;
        if (summary != null) {
            return summary;
        }
        return formatDuration(Process.getElapsedCpuTime()) + " CPU since launch";
    }

    public File getLastReport() {
        return DebugFiles.newest(getReportsDir(), ".json");
    }

    public long getReportsSize() {
        return DebugFiles.dirSize(getReportsDir());
    }

    public void deleteReports(Runnable onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            DebugFiles.deleteAll(getReportsDir());
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public void shareLastReport(Activity activity) {
        File f = getLastReport();
        if (f != null) {
            share(activity, f);
        }
    }

    public void captureAndShare(Activity activity) {
        final Sampler s = sampler;
        final Utilities.Callback<Runnable> post = r -> { // 13.2: globalQueue is a DispatchQueue2
            if (s != null) s.queue.postRunnable(r); else Utilities.globalQueue.postRunnable(r);
        };
        post.run(() -> {
            File file = null;
            Throwable error = null;
            try {
                JSONObject report = s != null ? s.captureReport("manual") : baseReport("manual");
                file = writeReport(report, "manual");
            } catch (Throwable t) {
                error = t;
            }
            final File fFile = file;
            final Throwable fError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (fFile != null) {
                    share(activity, fFile);
                } else if (fError != null) {
                    FileLog.e(fError);
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, "Load report failed: " + fError).show();
                }
            });
        });
    }

    private void share(Activity activity, File file) {
        DebugFiles.shareDebugFile(activity, file, "application/json", "Share load report");
    }

    private void showPendingAlert() {
        final PendingAlert alert = pendingAlert;
        if (alert == null) {
            return;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        if (sampler == null || activity == null || activity.isFinishing()) {
            return;
        }
        AlertDialog current = alertRef != null ? alertRef.get() : null;
        if (current != null && current.isShowing()) {
            return;
        }
        pendingAlert = null;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("High background load")
                .setMessage(alert.message)
                .setPositiveButton("Share", (d, w) -> share(activity, alert.file))
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

    /** 0 foreground, 1 background, 2 screen off. */
    private static int currentState() {
        PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            return 2;
        }
        ForegroundDetector fd = ForegroundDetector.getInstance();
        return fd != null && fd.isForeground() ? 0 : 1;
    }

    private static Boolean isPlugged() {
        try {
            Intent intent = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return intent != null ? intent.getIntExtra("plugged", 0) != 0 : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject batteryJson() throws JSONException {
        return new JSONObject().put("level", LiteMode.getBatteryLevel()).put("plugged", isPlugged());
    }

    private static long uidRxBytes() {
        return TrafficStats.getUidRxBytes(Process.myUid());
    }

    private static long uidTxBytes() {
        return TrafficStats.getUidTxBytes(Process.myUid());
    }

    private static final class ThreadStat {
        final String name;
        final long ticks;

        ThreadStat(String name, long ticks) {
            this.name = name;
            this.ticks = ticks;
        }
    }

    private static HashMap<Integer, ThreadStat> readThreads() {
        HashMap<Integer, ThreadStat> map = new HashMap<>();
        String[] tasks = new File("/proc/self/task").list();
        if (tasks == null) {
            return map;
        }
        byte[] buf = new byte[1024];
        for (String task : tasks) {
            int tid;
            try {
                tid = Integer.parseInt(task);
            } catch (NumberFormatException e) {
                continue;
            }
            try (FileInputStream in = new FileInputStream("/proc/self/task/" + task + "/stat")) {
                int n = in.read(buf);
                if (n > 0) {
                    ThreadStat stat = parseStat(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (stat != null) {
                        map.put(tid, stat);
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return map;
    }

    private static ThreadStat parseStat(String stat) {
        int open = stat.indexOf('(');
        int close = stat.lastIndexOf(')');
        if (open < 0 || close <= open || close + 2 >= stat.length()) {
            return null;
        }
        String[] parts = stat.substring(close + 2).split(" ");
        if (parts.length < 13) {
            return null;
        }
        try {
            long utime = Long.parseLong(parts[11]);
            long stime = Long.parseLong(parts[12]);
            return new ThreadStat(stat.substring(open + 1, close), utime + stime);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long ticksToMs(long ticks) {
        return ticks * 1000 / getClockTicks();
    }

    private static HashMap<String, Long> threadDeltas(Map<Integer, ThreadStat> previous, Map<Integer, ThreadStat> current) {
        HashMap<String, Long> map = new HashMap<>();
        for (Map.Entry<Integer, ThreadStat> e : current.entrySet()) {
            ThreadStat cur = e.getValue();
            ThreadStat prev = previous.get(e.getKey());
            long ticks = prev == null || !prev.name.equals(cur.name) ? cur.ticks : cur.ticks - prev.ticks;
            if (ticks > 0) {
                Long old = map.get(cur.name);
                map.put(cur.name, (old != null ? old : 0L) + ticksToMs(ticks));
            }
        }
        return map;
    }

    private static List<Map.Entry<String, Long>> topThreads(Map<String, Long> threads, int limit) {
        ArrayList<Map.Entry<String, Long>> list = new ArrayList<>(threads.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        return new ArrayList<>(list.subList(0, Math.min(limit, list.size())));
    }

    private static JSONArray threadsJson(List<Map.Entry<String, Long>> threads, boolean withOrigin) throws JSONException {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Long> e : threads) {
            JSONObject o = new JSONObject().put("name", e.getKey()).put("cpuMs", (long) e.getValue());
            if (withOrigin) {
                o.put("at", originOf(e.getKey()));
            }
            array.put(o);
        }
        return array;
    }

    private static String originOf(String name) {
        synchronized (threadOrigins) {
            return threadOrigins.get(name);
        }
    }

    /** Finds the first app frame of threads whose (15-char /proc) names are given, at most 8 tries per name. */
    private static void resolveOrigins(List<String> names) {
        ArrayList<String> wanted = new ArrayList<>();
        synchronized (threadOrigins) {
            for (String n : names) {
                if (UNATTRIBUTED.equals(n) || threadOrigins.containsKey(n)) {
                    continue;
                }
                Integer attempts = originAttempts.get(n);
                if ((attempts != null ? attempts : 0) >= 8) {
                    continue;
                }
                wanted.add(n);
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        Map<Thread, StackTraceElement[]> traces;
        try {
            traces = Thread.getAllStackTraces();
        } catch (Throwable t) {
            return;
        }
        synchronized (threadOrigins) {
            for (String n : wanted) {
                Integer attempts = originAttempts.get(n);
                originAttempts.put(n, (attempts != null ? attempts : 0) + 1);
            }
            for (Map.Entry<Thread, StackTraceElement[]> e : traces.entrySet()) {
                String name = e.getKey().getName();
                if (name.length() > 15) {
                    name = name.substring(0, 15);
                }
                if (wanted.contains(name) && !threadOrigins.containsKey(name)) {
                    String frame = appFrame(e.getValue());
                    if (frame != null) {
                        threadOrigins.put(name, frame);
                    }
                }
            }
        }
    }

    private static String appFrame(StackTraceElement[] trace) {
        outer:
        for (StackTraceElement el : trace) {
            for (String p : SKIPPED_FRAMES) {
                if (el.getClassName().startsWith(p)) {
                    continue outer;
                }
            }
            String cls = el.getClassName();
            int dot = cls.lastIndexOf('.');
            return (dot >= 0 ? cls.substring(dot + 1) : cls) + "." + el.getMethodName() + ":" + el.getLineNumber();
        }
        return null;
    }

    private static HealthStats takeHealthStats() {
        try {
            SystemHealthManager m = (SystemHealthManager) ApplicationLoader.applicationContext.getSystemService(Context.SYSTEM_HEALTH_SERVICE);
            return m != null ? m.takeMyUidSnapshot() : null;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String healthKeyName(String dataType, int key) {
        Map<Integer, String> names;
        synchronized (healthKeyNames) {
            names = healthKeyNames.get(dataType);
            if (names == null) {
                HashMap<Integer, String> map = new HashMap<>();
                try {
                    Class<?> cls = Class.forName(dataType.indexOf('.') >= 0 ? dataType : "android.os.health." + dataType);
                    for (Field f : cls.getFields()) {
                        if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                            map.put(f.getInt(null), f.getName());
                        }
                    }
                } catch (Throwable ignore) {
                }
                names = map;
                healthKeyNames.put(dataType, names);
            }
        }
        String s = names.get(key);
        return s != null ? s : String.valueOf(key);
    }

    private static JSONObject healthJson(HealthStats stats) throws JSONException {
        String type = stats.getDataType();
        JSONObject o = new JSONObject().put("type", type);
        if (stats.getMeasurementKeyCount() > 0) {
            JSONObject j = new JSONObject();
            for (int i = 0; i < stats.getMeasurementKeyCount(); i++) {
                int k = stats.getMeasurementKeyAt(i);
                j.put(healthKeyName(type, k), stats.getMeasurement(k));
            }
            o.put("measurements", j);
        }
        if (stats.getTimerKeyCount() > 0) {
            JSONObject j = new JSONObject();
            for (int i = 0; i < stats.getTimerKeyCount(); i++) {
                int k = stats.getTimerKeyAt(i);
                j.put(healthKeyName(type, k), new JSONArray().put(stats.getTimerCount(k)).put(stats.getTimerTime(k)));
            }
            o.put("timers", j);
        }
        if (stats.getMeasurementsKeyCount() > 0) {
            JSONObject j = new JSONObject();
            for (int i = 0; i < stats.getMeasurementsKeyCount(); i++) {
                int k = stats.getMeasurementsKeyAt(i);
                JSONObject m = new JSONObject();
                for (Map.Entry<String, Long> e : stats.getMeasurements(k).entrySet()) {
                    m.put(e.getKey(), (long) e.getValue());
                }
                j.put(healthKeyName(type, k), m);
            }
            o.put("measurementMaps", j);
        }
        if (stats.getTimersKeyCount() > 0) {
            JSONObject j = new JSONObject();
            for (int i = 0; i < stats.getTimersKeyCount(); i++) {
                int k = stats.getTimersKeyAt(i);
                JSONObject m = new JSONObject();
                for (Map.Entry<String, TimerStat> e : stats.getTimers(k).entrySet()) {
                    m.put(e.getKey(), new JSONArray().put(e.getValue().getCount()).put(e.getValue().getTime()));
                }
                j.put(healthKeyName(type, k), m);
            }
            o.put("timerMaps", j);
        }
        if (stats.getStatsKeyCount() > 0) {
            JSONObject j = new JSONObject();
            for (int i = 0; i < stats.getStatsKeyCount(); i++) {
                int k = stats.getStatsKeyAt(i);
                JSONObject m = new JSONObject();
                for (Map.Entry<String, HealthStats> e : stats.getStats(k).entrySet()) {
                    m.put(e.getKey(), healthJson(e.getValue()));
                }
                j.put(healthKeyName(type, k), m);
            }
            o.put("stats", j);
        }
        return o;
    }

    private static JSONObject baseReport(String trigger) throws JSONException {
        Context context = ApplicationLoader.applicationContext;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        HashMap<String, Long> byName = new HashMap<>();
        for (ThreadStat t : readThreads().values()) {
            Long old = byName.get(t.name);
            byName.put(t.name, (old != null ? old : 0L) + ticksToMs(t.ticks));
        }
        List<Map.Entry<String, Long>> top = topThreads(byName, 30);
        ArrayList<String> names = new ArrayList<>();
        for (Map.Entry<String, Long> e : top) {
            names.add(e.getKey());
        }
        resolveOrigins(names);
        JSONObject report = new JSONObject()
                .put("format", 1)
                .put("trigger", trigger)
                .put("createdAt", System.currentTimeMillis())
                .put("app", new JSONObject().put("version", BuildVars.BUILD_VERSION_STRING).put("code", DebugFiles.versionCode()).put("debug", BuildVars.DEBUG_VERSION))
                .put("device", new JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("cores", Runtime.getRuntime().availableProcessors()))
                .put("battery", batteryJson());
        JSONObject settings = new JSONObject()
                .put("accounts", UserConfig.getActivatedAccountsCount())
                .put("proxy", SharedConfig.isProxyEnabled())
                .put("logs", BuildVars.LOGS_ENABLED)
                .put("powerSave", pm != null && pm.isPowerSaveMode())
                .put("ignoringBatteryOptimizations", pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName()));
        report.put("settings", settings);
        report.put("process", new JSONObject()
                .put("uptimeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
                .put("cpuMs", Process.getElapsedCpuTime())
                .put("state", STATE_NAMES[currentState()])
                .put("threads", threadsJson(top, true)));
        HealthStats health = takeHealthStats();
        report.put("healthNow", health != null ? healthJson(health) : null);
        return report;
    }

    private File writeReport(JSONObject report, String trigger) throws Exception {
        File dir = getReportsDir();
        dir.mkdirs();
        File file = new File(dir, "load_" + BuildVars.BUILD_VERSION_STRING + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + trigger + ".json");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(report.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        File[] files = dir.listFiles();
        if (files != null) {
            ArrayList<File> json = new ArrayList<>();
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    json.add(f);
                }
            }
            Collections.sort(json, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 10; i < json.size(); i++) {
                json.get(i).delete();
            }
        }
        return file;
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        if (ms < 1000) {
            return ms + "ms";
        }
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m " + (s % 60) + "s";
        }
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    private static String formatPercent(long cpuMs, long wallMs) {
        return String.format(Locale.US, "%.1f%%", wallMs > 0 ? cpuMs * 100.0 / wallMs : 0.0);
    }

    private static String describeEpisode(long wallMs, long cpuMs, long rx, long tx, List<Map.Entry<String, Long>> threads) {
        StringBuilder sb = new StringBuilder();
        sb.append("In ").append(formatDuration(wallMs)).append(" in the background, the app used ").append(formatPercent(cpuMs, wallMs)).append(" of one core: ");
        sb.append(formatDuration(cpuMs)).append(" of CPU");
        if (rx >= 0 && tx >= 0) {
            sb.append(", ").append(AndroidUtilities.formatFileSize(rx)).append(" received, ").append(AndroidUtilities.formatFileSize(tx)).append(" sent");
        }
        sb.append('.');
        if (!threads.isEmpty()) {
            sb.append("\n\nBusiest threads: ");
            for (int i = 0; i < threads.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(threads.get(i).getKey()).append(' ').append(formatDuration(threads.get(i).getValue()));
            }
            sb.append('.');
        }
        sb.append("\n\nThe report holds thread names, traffic counters and battery stats, no message content.");
        return sb.toString();
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            s += v;
        }
        return s;
    }

    private static final class PendingAlert {
        final File file;
        final String message;

        PendingAlert(File file, String message) {
            this.file = file;
            this.message = message;
        }
    }

    private static final class Episode {
        final long startedAt;
        final long startRealtime;
        final long cpuMs;
        final long rxBytes;
        final long txBytes;
        final Map<Integer, ThreadStat> threads;
        final HealthStats health;
        boolean reported;

        Episode(long startedAt, long startRealtime, long cpuMs, long rxBytes, long txBytes, Map<Integer, ThreadStat> threads, HealthStats health) {
            this.startedAt = startedAt;
            this.startRealtime = startRealtime;
            this.cpuMs = cpuMs;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
            this.threads = threads;
            this.health = health;
        }
    }

    private static final class Sampler implements ForegroundDetector.Listener, NotificationCenter.NotificationCenterDelegate {

        final DispatchQueue queue = new DispatchQueue("loadMonitorQueue");
        private final Runnable sampleRunnable = () -> {
            sampleSafe();
            scheduleNext();
        };
        private final long startedAt = System.currentTimeMillis();
        private final long[] wallMs = new long[STATE_NAMES.length];
        private final long[] cpuMs = new long[STATE_NAMES.length];
        private final long[] rxBytes = new long[STATE_NAMES.length];
        private final long[] txBytes = new long[STATE_NAMES.length];
        private final HashMap<String, long[]> threadMs = new HashMap<>();
        private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
        private NotificationCenter.ObserversGroup observers;
        private volatile String summary;
        private Episode episode;
        private long lastRealtime;
        private long lastCpuMs;
        private long lastRxBytes = -1;
        private long lastTxBytes = -1;
        private int lastState;
        private HashMap<Integer, ThreadStat> threads = new HashMap<>();
        private HealthStats startHealth;
        private JSONObject startBattery;
        private HealthStats recentHealth;
        private long recentHealthRealtime;

        Sampler() {
            ForegroundDetector fd = ForegroundDetector.getInstance();
            if (fd != null) {
                fd.addListener(this);
            }
            AndroidUtilities.runOnUIThread(() -> observers = NotificationCenter.getGlobalInstance().createObserversGroup(this).add(NotificationCenter.screenStateChanged));
            queue.postRunnable(() -> {
                try {
                    startHealth = recentHealthStats();
                    startBattery = batteryJson();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                lastRealtime = SystemClock.elapsedRealtime();
                lastCpuMs = Process.getElapsedCpuTime();
                lastRxBytes = uidRxBytes();
                lastTxBytes = uidTxBytes();
                threads = readThreads();
                updateState();
                scheduleNext();
            });
        }

        void stop() {
            ForegroundDetector fd = ForegroundDetector.getInstance();
            if (fd != null) {
                fd.removeListener(this);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (observers != null) {
                    observers.removeAllObservers();
                }
                observers = null;
            });
            queue.cleanupQueue();
            queue.recycle();
        }

        @Override
        public void onBecameForeground() {
            queue.postRunnable(this::sampleSafe);
            AndroidUtilities.runOnUIThread(INSTANCE::showPendingAlert, 1000);
        }

        @Override
        public void onBecameBackground() {
            queue.postRunnable(this::sampleSafe);
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.screenStateChanged) {
                queue.postRunnable(this::sampleSafe);
            }
        }

        private void scheduleNext() {
            queue.cancelRunnable(sampleRunnable);
            queue.postRunnable(sampleRunnable, SAMPLE_INTERVAL_MS);
        }

        private void sampleSafe() {
            try {
                sample();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }

        private void sample() throws JSONException {
            long now = SystemClock.elapsedRealtime();
            long cpu = Process.getElapsedCpuTime();
            long rx = uidRxBytes();
            long tx = uidTxBytes();
            HashMap<Integer, ThreadStat> current = readThreads();
            int state = lastState;
            long wall = now - lastRealtime;
            long cpuDelta = cpu - lastCpuMs;
            long rxDelta = rx >= 0 && lastRxBytes >= 0 ? rx - lastRxBytes : 0;
            long txDelta = tx >= 0 && lastTxBytes >= 0 ? tx - lastTxBytes : 0;
            HashMap<String, Long> deltas = threadDeltas(threads, current);
            long attributed = 0;
            for (Long v : deltas.values()) {
                attributed += v;
            }
            long unattributed = cpuDelta - attributed;
            if (unattributed > 0) {
                deltas.put(UNATTRIBUTED, unattributed);
            }
            ArrayList<String> busy = new ArrayList<>();
            for (Map.Entry<String, Long> e : topThreads(deltas, 3)) {
                if (e.getValue() >= 200) {
                    busy.add(e.getKey());
                }
            }
            resolveOrigins(busy);
            wallMs[state] += wall;
            cpuMs[state] += cpuDelta;
            rxBytes[state] += rxDelta;
            txBytes[state] += txDelta;
            for (Map.Entry<String, Long> e : deltas.entrySet()) {
                long[] arr = threadMs.get(e.getKey());
                if (arr == null) {
                    arr = new long[STATE_NAMES.length];
                    threadMs.put(e.getKey(), arr);
                }
                arr[state] += e.getValue();
            }
            if (wall >= 1000) {
                samples.addLast(new JSONObject()
                        .put("t", System.currentTimeMillis())
                        .put("state", STATE_NAMES[state])
                        .put("wallMs", wall)
                        .put("cpuMs", cpuDelta)
                        .put("rxBytes", rxDelta)
                        .put("txBytes", txDelta)
                        .put("plugged", isPlugged())
                        .put("threads", threadsJson(topThreads(deltas, 3), false)));
                while (samples.size() > 240) {
                    samples.removeFirst();
                }
            }
            lastRealtime = now;
            lastCpuMs = cpu;
            lastRxBytes = rx;
            lastTxBytes = tx;
            threads = current;
            summary = formatDuration(sum(cpuMs)) + " CPU, " + AndroidUtilities.formatFileSize(sum(rxBytes) + sum(txBytes));
            checkEpisode(now, cpu, rx, tx, current);
            updateState();
        }

        private void updateState() {
            int state = currentState();
            lastState = state;
            if (state == 0) {
                episode = null;
            } else if (episode == null) {
                episode = new Episode(System.currentTimeMillis(), lastRealtime, lastCpuMs, lastRxBytes, lastTxBytes, threads, recentHealthStats());
            }
        }

        private void checkEpisode(long now, long cpu, long rx, long tx, Map<Integer, ThreadStat> current) {
            Episode ep = episode;
            if (ep == null) {
                return;
            }
            long wall = now - ep.startRealtime;
            if (ep.reported || wall < 300_000) {
                return;
            }
            long cpuUsed = cpu - ep.cpuMs;
            if (cpuUsed * 100 < (long) INSTANCE.getCpuPercent() * wall) {
                return;
            }
            ep.reported = true;
            File file;
            try {
                file = INSTANCE.writeReport(buildReport("background"), "background");
            } catch (Throwable t) {
                FileLog.e(t);
                return;
            }
            long rxUsed = rx < 0 || ep.rxBytes < 0 ? -1 : rx - ep.rxBytes;
            long txUsed = tx < 0 || ep.txBytes < 0 ? -1 : tx - ep.txBytes;
            pendingAlert = new PendingAlert(file, describeEpisode(wall, cpuUsed, rxUsed, txUsed, topThreads(threadDeltas(ep.threads, current), 3)));
            ForegroundDetector fd = ForegroundDetector.getInstance();
            if (fd != null && fd.isForeground()) {
                AndroidUtilities.runOnUIThread(INSTANCE::showPendingAlert);
            }
        }

        private HealthStats recentHealthStats() {
            long now = SystemClock.elapsedRealtime();
            if (recentHealth == null || now - recentHealthRealtime > 60_000) {
                recentHealth = takeHealthStats();
                recentHealthRealtime = now;
            }
            return recentHealth;
        }

        JSONObject captureReport(String trigger) throws JSONException {
            sample();
            return buildReport(trigger);
        }

        JSONObject buildReport(String trigger) throws JSONException {
            JSONObject report = baseReport(trigger);
            JSONObject states = new JSONObject();
            for (int i = 0; i < STATE_NAMES.length; i++) {
                states.put(STATE_NAMES[i], new JSONObject().put("wallMs", wallMs[i]).put("cpuMs", cpuMs[i]).put("rxBytes", rxBytes[i]).put("txBytes", txBytes[i]));
            }
            ArrayList<Map.Entry<String, long[]>> list = new ArrayList<>(threadMs.entrySet());
            Collections.sort(list, (a, b) -> Long.compare(sum(b.getValue()), sum(a.getValue())));
            JSONArray threadsArray = new JSONArray();
            for (int i = 0; i < Math.min(30, list.size()); i++) {
                Map.Entry<String, long[]> e = list.get(i);
                JSONObject o = new JSONObject().put("name", e.getKey()).put("cpuMs", sum(e.getValue())).put("at", originOf(e.getKey()));
                for (int s = 0; s < STATE_NAMES.length; s++) {
                    o.put(STATE_NAMES[s], e.getValue()[s]);
                }
                threadsArray.put(o);
            }
            report.put("monitor", new JSONObject()
                    .put("startedAt", startedAt)
                    .put("sampleIntervalMs", SAMPLE_INTERVAL_MS)
                    .put("batteryAtStart", startBattery)
                    .put("states", states)
                    .put("threads", threadsArray)
                    .put("samples", new JSONArray(samples)));
            Episode ep = episode;
            if (ep != null) {
                long now = SystemClock.elapsedRealtime();
                long cpu = Process.getElapsedCpuTime();
                long rx = uidRxBytes();
                long tx = uidTxBytes();
                report.put("episode", new JSONObject()
                        .put("startedAt", ep.startedAt)
                        .put("wallMs", now - ep.startRealtime)
                        .put("cpuMs", cpu - ep.cpuMs)
                        .put("rxBytes", rx < 0 || ep.rxBytes < 0 ? -1L : rx - ep.rxBytes)
                        .put("txBytes", tx < 0 || ep.txBytes < 0 ? -1L : tx - ep.txBytes)
                        .put("threads", threadsJson(topThreads(threadDeltas(ep.threads, readThreads()), 30), true)));
                if (ep.health != null) {
                    report.put("healthEpisodeStart", healthJson(ep.health));
                }
            }
            if (startHealth != null) {
                report.put("healthStart", healthJson(startHealth));
            }
            return report;
        }
    }
}
