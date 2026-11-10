package org.telegram.messenger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * StaticGram: saves the stack trace of an uncaught Java crash and offers to send it on the next start.
 * The report lives in files/temp/ (exposed through the existing FileProvider "temp" path).
 */
public class SgCrashReporter {

    private static final String FILE_NAME = "sg_last_crash.txt";
    private static final int MAX_TRACE = 200 * 1024;
    private static boolean installed;
    private static boolean checked;

    private static File reportFile() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "temp");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(thread, throwable);
            } catch (Throwable ignore) {
            }
            try {
                // exteraGram debug monitors: optional raw heap dump on OutOfMemoryError (off by default)
                if (isOutOfMemory(throwable)) {
                    String dump = com.exteragram.messenger.debug.HeapMonitor.INSTANCE.dumpOnOom();
                    if (dump != null) {
                        try (FileOutputStream out = new FileOutputStream(reportFile(), true)) {
                            out.write(("\nheap dump: " + dump + " (Settings > long press exteraStatic settings > Debug > Share last heap dump)\n").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean isOutOfMemory(Throwable t) {
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (t instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }

    private static void write(Thread thread, Throwable throwable) throws Exception {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        sb.append("StaticGram crash report\n");
        sb.append("time: ").append(fmt.format(new Date())).append('\n');
        try {
            Context ctx = ApplicationLoader.applicationContext;
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            sb.append("app: ").append(info.versionName).append(" (").append(info.versionCode).append(")\n");
        } catch (Throwable ignore) {
        }
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            Activity a = org.telegram.ui.LaunchActivity.instance;
            if (a != null) {
                sb.append("orientation: ").append(a.getResources().getConfiguration().orientation).append('\n');
            }
            org.telegram.ui.ActionBar.BaseFragment f = org.telegram.ui.LaunchActivity.getLastFragment();
            if (f != null) {
                sb.append("screen: ").append(f.getClass().getName()).append('\n');
            }
            if (org.telegram.ui.PhotoViewer.hasInstance() && org.telegram.ui.PhotoViewer.getInstance().isVisible()) {
                sb.append("photo viewer: visible\n");
            }
        } catch (Throwable ignore) {
        }
        try {
            // Memory summary: an OutOfMemoryError trace only shows the allocation that hit
            // the limit, not what filled the heap.
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
            sb.append("heap: ").append(used).append(" / ").append(rt.maxMemory() >> 20).append(" MB")
                    .append(", native: ").append(android.os.Debug.getNativeHeapAllocatedSize() >> 20).append(" MB\n");
            try {
                // exteraGram 13.1.1 debug monitors: GC pressure and monitor state
                String[] gcKeys = {"art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time"};
                sb.append("gc:");
                for (String k : gcKeys) {
                    sb.append(' ').append(k.substring(7)).append('=').append(android.os.Debug.getRuntimeStat(k));
                }
                sb.append('\n');
                sb.append("monitors: heap ").append(com.exteragram.messenger.debug.HeapMonitor.isRunning() ? com.exteragram.messenger.debug.HeapMonitor.INSTANCE.getLimitMb() + " MB" : "off")
                        .append(", oom dump ").append(com.exteragram.messenger.debug.DebugConfig.getHeapDumpOnOom() ? "on" : "off")
                        .append(", freeze ").append(com.exteragram.messenger.debug.FreezeMonitor.isRunning() ? com.exteragram.messenger.debug.FreezeMonitor.getFreezesCount() + " recorded" : "off")
                        .append(", load ").append(com.exteragram.messenger.debug.LoadMonitor.isRunning() ? "on" : "off").append('\n');
            } catch (Throwable ignore) {
            }
            org.telegram.ui.LaunchActivity la = org.telegram.ui.LaunchActivity.instance;
            if (la != null && la.getActionBarLayout() != null && la.getActionBarLayout().getFragmentStack() != null) {
                StringBuilder st = new StringBuilder();
                for (org.telegram.ui.ActionBar.BaseFragment bf : la.getActionBarLayout().getFragmentStack()) {
                    if (st.length() > 0) st.append(" > ");
                    st.append(bf.getClass().getSimpleName());
                }
                sb.append("stack: ").append(st).append('\n');
            }
        } catch (Throwable ignore) {
        }
        sb.append("thread: ").append(thread != null ? thread.getName() : "?").append("\n\n");
        String trace = Log.getStackTraceString(throwable);
        if (trace.length() > MAX_TRACE) {
            trace = trace.substring(0, MAX_TRACE) + "\n...";
        }
        sb.append(trace);
        try (FileOutputStream out = new FileOutputStream(reportFile(), false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_TRACE + 8192)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called from LaunchActivity.onResume: once per process, offers the saved report. */
    public static void checkPending(Activity activity) {
        if (checked || activity == null || activity.isFinishing()) {
            return;
        }
        checked = true;
        final File f;
        try {
            f = reportFile();
            if (!f.exists() || f.length() == 0) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        final String text = read(f);
        if (text == null) {
            f.delete();
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (activity.isFinishing()) {
                return;
            }
            try {
                AlertDialog.Builder b = new AlertDialog.Builder(activity);
                b.setTitle(LocaleController.getString(R.string.SgCrashTitle));
                b.setMessage(LocaleController.getString(R.string.SgCrashText));
                b.setPositiveButton(LocaleController.getString(R.string.SgCrashSend), (d, w) -> share(activity, f, text));
                b.setNeutralButton(LocaleController.getString(R.string.SgCrashCopy), (d, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                        Toast.makeText(activity, LocaleController.getString(R.string.TextCopied), Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                    f.delete();
                });
                b.setNegativeButton(LocaleController.getString(R.string.Close), (d, w) -> f.delete());
                b.show();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }, 1500);
    }

    private static void share(Activity activity, File f, String text) {
        try {
            File out = new File(f.getParentFile(), "StaticGram-crash-" + (System.currentTimeMillis() / 1000) + ".txt");
            if (!f.renameTo(out)) {
                out = f;
            }
            Uri uri = Build.VERSION.SDK_INT >= 24
                    ? FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", out)
                    : Uri.fromFile(out);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(i, LocaleController.getString(R.string.SgCrashSend)));
        } catch (Throwable t) {
            FileLog.e(t);
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                Toast.makeText(activity, LocaleController.getString(R.string.TextCopied), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
            f.delete();
        }
    }
}
