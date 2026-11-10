package com.exteragram.messenger.plugins.utils;

import android.util.Log;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Startup timings of the plugin engine, written only while logs are enabled
 * ({@link BuildVars#LOGS_ENABLED}: debug builds or "Enable logs" in the debug menu).
 * Goes to FileLog and to logcat with the tag {@value #TAG}.
 */
public final class PluginsPerf {

    public static final String TAG = "sgPluginsPerf";

    private PluginsPerf() {
    }

    public static boolean enabled() {
        return BuildVars.LOGS_ENABLED;
    }

    /** Start mark; 0 when logging is off, so callers pay nothing. */
    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    /** Milliseconds since {@code startNanos} (0 when logging is off). */
    public static long since(long startNanos) {
        return startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Logs "{@code what} in N ms". */
    public static void log(String what, long startNanos) {
        if (startNanos == 0L || !enabled()) {
            return;
        }
        write(what + " in " + since(startNanos) + " ms");
    }

    public static void write(String message) {
        if (!enabled()) {
            return;
        }
        FileLog.d(TAG + ": " + message);
        Log.i(TAG, message);
    }
}
