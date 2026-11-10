package com.exteragram.messenger.adblock.interop;

import androidx.annotation.Keep;

import com.exteragram.messenger.adblock.data.BlockResult;
import com.exteragram.messenger.adblock.data.FilterListMetadata;
import com.exteragram.messenger.adblock.data.UrlCosmeticResources;

import org.telegram.messenger.FileLog;

/**
 * JNI bridge to exteraGram's prebuilt {@code libetgadblock.so} (Brave adblock-rust 0.12.1 wrapped with jni-rs).
 * The JNI symbol names are bound to this exact package and class name — do not move or rename it.
 * The library ships only for arm64-v8a and armeabi-v7a; on other ABIs {@link #loadLibraries()} returns false
 * and the ad blocker stays inactive.
 */
@Keep
public class NativeAdBlock {

    private static volatile Boolean loaded;

    public static native FilterListMetadata addFilters(long filterSet, String path);

    public static native long createEngine(long filterSet);

    public static native long createFilterSet(String[] rules);

    public static native void destroyEngine(long engine);

    public static native void destroyFilterSet(long filterSet);

    public static native UrlCosmeticResources getCosmeticResources(long engine, String url);

    public static native String[] getHiddenSelectors(long engine, String[] classes, String[] ids, String[] exceptions);

    public static native BlockResult shouldBlock(long engine, String url, String sourceUrl, String requestType);

    public static native void useResources(long engine, String[] names, String[][] aliases, String[] kinds, String[] contents);

    public static synchronized boolean loadLibraries() {
        if (loaded == null) {
            try {
                System.loadLibrary("etgadblock");
                loaded = Boolean.TRUE;
            } catch (Throwable t) {
                FileLog.e(t);
                loaded = Boolean.FALSE;
            }
        }
        return loaded;
    }

    public static boolean isLoadFailed() {
        return Boolean.FALSE.equals(loaded);
    }
}
