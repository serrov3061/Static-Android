package com.exteragram.messenger.plugins.utils;

import android.content.SharedPreferences;
import java.io.File;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public final class NativeCrashHandler {
    private static final String CRASH_FLAG_FILENAME = "native_crash.flag";
    public static final NativeCrashHandler INSTANCE = new NativeCrashHandler();

    public static final native void init(String flagPath);

    /**
     * exteraGram plugins: the native signal handler lives in exteraGram's libtmessages; StaticGram's native
     * library may not export it, so a missing symbol must never crash the app at startup.
     */
    public static void safeInit() {
        try {
            init(getCrashFlagPath());
        } catch (Throwable ignored) {
            // UnsatisfiedLinkError: native crash detection is unavailable in this build
        }
    }

    private NativeCrashHandler() {
    }

    public static final void checkAndHandleNativeCrash() {
        File file = new File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME);
        if (file.exists() && ApplicationLoader.applicationContext != null) {
            FileLog.e("Native crash detected. Enabling safe mode for plugins.");
            SharedPreferences.Editor editorEdit = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0).edit();
            editorEdit.putBoolean("had_crash", true);
            editorEdit.apply();
            file.delete();
        }
    }

    public static final String getCrashFlagPath() {
        return new File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME).getAbsolutePath();
    }
}
