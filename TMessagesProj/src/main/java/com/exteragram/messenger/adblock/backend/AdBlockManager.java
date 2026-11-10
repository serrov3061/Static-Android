package com.exteragram.messenger.adblock.backend;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.adblock.interop.AdBlock;
import com.exteragram.messenger.adblock.interop.NativeAdBlock;
import com.exteragram.messenger.utils.network.RemoteUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * Ad blocker lifecycle for the in-app browser (WebView pages opened from links / ArticleViewer; not bot mini apps).
 * exteraGram gates the whole feature with the remote-config flag "use_adblock" (default false); StaticGram has no
 * exteraGram remote config, so the flag defaults to true here and can still be switched off through the same key.
 */
public abstract class AdBlockManager {

    private static final String[] FILTERS = {
            "https://ublockorigin.github.io/uAssetsCDN/filters/filters.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/badware.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/privacy.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/unbreak.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/quick-fixes.min.txt",
            "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
            "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/thirdparties/easylist.txt",
            "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/thirdparties/easyprivacy.txt",
            "https://cdn.jsdelivr.net/gh/dimisa-RUAdList/RUAdListCDN@main/lists/ruadlist.ubo.min.txt"
    };

    private static final ArrayList<Runnable> readyCallbacks = new ArrayList<>();
    private static boolean loading;
    private static DispatchQueue queue;

    private static synchronized DispatchQueue getQueue() {
        if (queue == null) {
            queue = new DispatchQueue("AdBlockManager");
        }
        return queue;
    }

    private static boolean isFeatureEnabled() {
        return RemoteUtils.getBooleanConfigValue("use_adblock", true);
    }

    /** Whether the toggle can be shown at all: feature flag on and the native engine loads on this ABI. */
    public static boolean isAvailable() {
        return isFeatureEnabled() && NativeAdBlock.loadLibraries();
    }

    /** Whether requests are actually being filtered right now. */
    public static boolean isActive() {
        return ExteraConfig.getEnableAdBlock() && AdBlock.isReady();
    }

    /** Loads the engine if the user has the ad blocker on (called when a browser WebView is created). */
    public static void initialize() {
        if (ExteraConfig.getEnableAdBlock()) {
            getQueue().postRunnable(() -> load(null));
        }
    }

    /**
     * exteraGram calls this from ExteraConfig.init() (engine warm-up 10 s after start, downloads filter lists).
     * Not wired on StaticGram yet, so no network traffic happens until the user opens the in-app browser.
     */
    public static void preload() {
        if (ExteraConfig.getEnableAdBlock()) {
            getQueue().postRunnable(() -> load(null), 10000L);
        }
    }

    public static void setEnabled(boolean enabled, Runnable onReady) {
        ExteraConfig.setEnableAdBlock(enabled);
        getQueue().postRunnable(() -> {
            if (enabled) {
                load(onReady);
            } else {
                readyCallbacks.clear();
                AdBlock.destroy();
            }
        });
    }

    private static void load(Runnable onReady) {
        if (!ExteraConfig.getEnableAdBlock()) {
            return;
        }
        if (AdBlock.isReady()) {
            if (onReady != null) {
                AndroidUtilities.runOnUIThread(onReady);
            }
            return;
        }
        if (!isFeatureEnabled() || !NativeAdBlock.loadLibraries()) {
            return;
        }
        if (onReady != null) {
            readyCallbacks.add(onReady);
        }
        if (loading) {
            return;
        }
        loading = true;
        ScriptletsManager scriptlets = ScriptletsManager.getInstance();
        if (!scriptlets.isDownloaded()) {
            scriptlets.download(ok -> getQueue().postRunnable(AdBlockManager::applyResourcesSafe));
        }
        SubscriptionsManager subscriptions = SubscriptionsManager.getInstance();
        if (subscriptions.hasFilters()) {
            build();
            subscriptions.update(FILTERS, null);
        } else {
            subscriptions.update(FILTERS, () -> getQueue().postRunnable(AdBlockManager::build));
        }
    }

    private static void applyResourcesSafe() {
        try {
            AdBlock.applyResources();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void build() {
        loading = false;
        if (ExteraConfig.getEnableAdBlock()) {
            try {
                AdBlock.build();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (readyCallbacks.isEmpty()) {
            return;
        }
        ArrayList<Runnable> callbacks = new ArrayList<>(readyCallbacks);
        readyCallbacks.clear();
        if (AdBlock.isReady()) {
            AndroidUtilities.runOnUIThread(() -> {
                for (int i = 0; i < callbacks.size(); i++) {
                    callbacks.get(i).run();
                }
            });
        }
    }
}
