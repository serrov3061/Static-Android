package com.exteragram.messenger.debug;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;

/** exteraGram 13.1.1 DebugFiles.kt: where debug monitors keep their files and how they are shared. */
public final class DebugFiles {

    private DebugFiles() {
    }

    public static File debugFilesDir(String name) {
        Context context = ApplicationLoader.applicationContext;
        File base = null;
        try {
            base = context.getExternalFilesDir(null);
        } catch (Throwable ignore) {
        }
        if (base == null) {
            // StaticGram: files/temp is exposed through our FileProvider ("temp" path), the cache dir is not
            base = new File(ApplicationLoader.getFilesDirFixed(), "temp");
        }
        return new File(base, name);
    }

    public static void shareDebugFile(Activity activity, File file, String mimeType, String title) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(mimeType)
                    .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, title));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The app's versionCode (the library module's BuildConfig has none). */
    static int versionCode() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    static long dirSize(File dir) {
        File[] files = dir.listFiles();
        long size = 0;
        if (files != null) {
            for (File f : files) {
                size += f.length();
            }
        }
        return size;
    }

    static File newest(File dir, String suffix) {
        File[] files = dir.listFiles();
        File best = null;
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(suffix) && (best == null || f.lastModified() > best.lastModified())) {
                    best = f;
                }
            }
        }
        return best;
    }

    static void deleteAll(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }
}
