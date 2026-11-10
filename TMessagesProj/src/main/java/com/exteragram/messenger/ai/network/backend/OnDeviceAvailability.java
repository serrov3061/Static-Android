package com.exteragram.messenger.ai.network.backend;

/**
 * exteraGram's on-device model support (ML Kit GenAI Prompt API / Gemini Nano via AICore).
 *
 * Not available in StaticGram: com.google.mlkit:genai-prompt is not a dependency (it needs a newer minSdk and AICore).
 * Every check reports "not supported", so the on-device service never appears and nothing is downloaded.
 */
public final class OnDeviceAvailability {

    public static final int STATUS_UNAVAILABLE = 0;

    public interface DownloadListener {
        default void onStarted(long bytesToDownload) {
        }

        default void onProgress(float progress, long bytesDownloaded) {
        }

        default void onCompleted() {
        }

        default void onFailed(Exception e) {
        }
    }

    private OnDeviceAvailability() {
    }

    public static int getStatus() {
        return STATUS_UNAVAILABLE;
    }

    public static boolean isSupported() {
        return false;
    }

    public static boolean isReady() {
        return false;
    }

    public static boolean needsDownload() {
        return false;
    }

    public static boolean isDownloading() {
        return false;
    }

    public static boolean isDownloadInProgress() {
        return false;
    }

    public static int getDownloadPercent() {
        return -1;
    }

    public static String getModelName() {
        return null;
    }

    public static boolean isThinkingSupported() {
        return false;
    }

    public static boolean isSystemPromptSupported() {
        return false;
    }

    public static int getTokenLimit() {
        return 0;
    }

    public static int getConfigGeneration() {
        return 0;
    }

    public static void check() {
    }

    public static void warmup() {
    }

    public static void refresh(Runnable onDone) {
        if (onDone != null) {
            onDone.run();
        }
    }

    public static void onModelConfigChanged(Runnable onDone) {
        if (onDone != null) {
            onDone.run();
        }
    }

    public static void download(int account, DownloadListener listener) {
        if (listener != null) {
            listener.onFailed(new UnsupportedOperationException("On-device AI is not available"));
        }
    }

    public static void notifyServicesUpdated() {
    }
}
