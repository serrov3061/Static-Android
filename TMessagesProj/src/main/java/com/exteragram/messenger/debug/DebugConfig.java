package com.exteragram.messenger.debug;

import com.exteragram.messenger.config.BooleanPref;
import com.exteragram.messenger.config.IntegerPref;

/** Debug switches of exteraGram (stored in "exteraconfig" like ExteraConfig). */
public final class DebugConfig {

    private DebugConfig() {
    }

    private static final BooleanPref debugCameraMetrics = new BooleanPref(false, "debugCameraMetrics");
    private static final BooleanPref forceCompactSavedMusic = new BooleanPref(false, "forceCompactSavedMusic");
    private static final BooleanPref disableApiRequests = new BooleanPref(false, "disableApiRequests");
    private static final BooleanPref disableChatFadeWallpaperBlend = new BooleanPref(false, "disableChatFadeWallpaperBlend");
    private static final BooleanPref chatFadeUseWhiteBackground = new BooleanPref(false, "chatFadeUseWhiteBackground");
    private static final BooleanPref heapMonitorEnabled = new BooleanPref(false, "heapMonitorEnabled");
    private static final IntegerPref heapMonitorLimitMb = new IntegerPref(256, "heapMonitorLimitMb");
    private static final BooleanPref loadMonitorEnabled = new BooleanPref(false, "loadMonitorEnabled");
    private static final IntegerPref loadMonitorCpuPercent = new IntegerPref(5, "loadMonitorCpuPercent");
    private static final BooleanPref freezeMonitorEnabled = new BooleanPref(false, "freezeMonitorEnabled");
    private static final IntegerPref freezeMonitorThresholdMs = new IntegerPref(700, "freezeMonitorThresholdMs");
    // StaticGram: raw heap dump from the crash handler when the app dies with OutOfMemoryError
    private static final BooleanPref heapDumpOnOom = new BooleanPref(false, "sgHeapDumpOnOom");

    public static boolean getDebugCameraMetrics() {
        return debugCameraMetrics.get();
    }

    public static void setDebugCameraMetrics(boolean value) {
        debugCameraMetrics.set(value);
    }

    public static boolean getForceCompactSavedMusic() {
        return forceCompactSavedMusic.get();
    }

    public static void setForceCompactSavedMusic(boolean value) {
        forceCompactSavedMusic.set(value);
    }

    public static boolean getDisableApiRequests() {
        return disableApiRequests.get();
    }

    public static void setDisableApiRequests(boolean value) {
        disableApiRequests.set(value);
    }

    public static boolean getDisableChatFadeWallpaperBlend() {
        return disableChatFadeWallpaperBlend.get();
    }

    public static void setDisableChatFadeWallpaperBlend(boolean value) {
        disableChatFadeWallpaperBlend.set(value);
    }

    public static boolean getChatFadeUseWhiteBackground() {
        return chatFadeUseWhiteBackground.get();
    }

    public static void setChatFadeUseWhiteBackground(boolean value) {
        chatFadeUseWhiteBackground.set(value);
    }

    public static boolean getHeapMonitorEnabled() {
        return heapMonitorEnabled.get();
    }

    public static void setHeapMonitorEnabled(boolean value) {
        heapMonitorEnabled.set(value);
    }

    public static int getHeapMonitorLimitMb() {
        return heapMonitorLimitMb.get();
    }

    public static void setHeapMonitorLimitMb(int value) {
        heapMonitorLimitMb.set(value);
    }

    public static boolean getLoadMonitorEnabled() {
        return loadMonitorEnabled.get();
    }

    public static void setLoadMonitorEnabled(boolean value) {
        loadMonitorEnabled.set(value);
    }

    public static int getLoadMonitorCpuPercent() {
        return loadMonitorCpuPercent.get();
    }

    public static void setLoadMonitorCpuPercent(int value) {
        loadMonitorCpuPercent.set(value);
    }

    public static boolean getFreezeMonitorEnabled() {
        return freezeMonitorEnabled.get();
    }

    public static void setFreezeMonitorEnabled(boolean value) {
        freezeMonitorEnabled.set(value);
    }

    public static boolean getHeapDumpOnOom() {
        return heapDumpOnOom.get();
    }

    public static void setHeapDumpOnOom(boolean value) {
        heapDumpOnOom.set(value);
    }

    public static int getFreezeMonitorThresholdMs() {
        return freezeMonitorThresholdMs.get();
    }

    public static void setFreezeMonitorThresholdMs(int value) {
        freezeMonitorThresholdMs.set(value);
    }

}
