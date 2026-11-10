package com.exteragram.messenger.plugins;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.hooks.EventHookRecord;
import com.exteragram.messenger.plugins.hooks.HookRecord;
import com.exteragram.messenger.plugins.hooks.MenuItemRecord;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;
import com.exteragram.messenger.plugins.hooks.XposedHookRecord;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.pip.PipController;
import com.exteragram.messenger.plugins.ui.PluginsActivity;
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import com.exteragram.messenger.plugins.ui.components.SafeModeBottomSheet;
import com.exteragram.messenger.plugins.utils.MenuContextBuilder;
import com.exteragram.messenger.plugins.utils.NativeCrashHandler;
import com.exteragram.messenger.plugins.utils.PluginsPerf;
import com.exteragram.messenger.plugins.utils.PluginsWatchdog;
import com.exteragram.messenger.utils.chats.ChatUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.InputStream;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import kotlin.TuplesKt;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.StringsKt;
import com.exteragram.messenger.plugins.utils.PluginFiles;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

public final class PluginsController implements PluginsHooks {
    public static final int PLUGIN_FILE_ICON_ID_START = 101;
    public static final int PLUGIN_FILE_ICON_NONE = -1;
    public static final String PREF_PLUGIN_ENABLED_KEY_PREFIX = "plugin_enabled_";
    private volatile Map<String, ? extends List<EventHookRecord>> exactMatchEventHooksCache;
    private final ConcurrentHashMap<Integer, Drawable> fileIconDrawablesById;
    private final ConcurrentHashMap<String, Integer> fileIconIdsByExtension;
    private final ConcurrentHashMap<String, Set<HookRecord>> hooks;
    private volatile boolean hooksCacheDirty;
    private final Object hooksCacheLock;
    private volatile boolean initialized;
    private volatile long initStartNanos; // sg: startup timing (PluginsPerf)
    private final ConcurrentHashMap<String, List<String>> interestedPluginsCache;
    private final ConcurrentHashMap<String, MenuItemRecord> menuItemsById;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MenuItemRecord>> menuItemsByMenuType;
    private final AtomicInteger nextFileIconId;
    private final ConcurrentHashMap<String, Plugin> plugins;
    private File pluginsDir;
    private SharedPreferences preferences;
    private final ConcurrentHashMap<String, List<SettingItem>> settings;
    private volatile List<EventHookRecord> substringMatchEventHooksCache;
    private final Runnable updateNotificationRunnable;
    private final PluginsWatchdog watchdog;

    public static final Companion Companion = new Companion();
    private static final ConcurrentHashMap<String, PluginsEngine> enginesMap = new ConcurrentHashMap<>(MapsKt.mapOf(TuplesKt.to(PluginsConstants.PYTHON, new PythonPluginsEngine()), TuplesKt.to(PluginsConstants.ELYX, new ElyxPluginsEngine())));

    public interface EngineHookCaller<T> {
        HookResult<T> call(PluginsEngine engine, T obj, String pluginId);
    }

    public interface PluginsEngine {
        boolean canOpenInExternalApp();

        void checkDevServer();

        void clearPluginSettings(String pluginId);

        void deletePlugin(String pluginId, Utilities.Callback<String> callback);

        void executeOnAppEvent(String eventType);

        HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String requestName, int account, TLObject response, TLRPC.TL_error error, String pluginId);

        HookResult<TLObject> executePreRequestHook(String requestName, int account, TLObject request, String pluginId);

        HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params, String pluginId);

        HookResult<TLRPC.Update> executeUpdateHook(String updateName, int account, TLRPC.Update update, String pluginId);

        HookResult<TLRPC.Updates> executeUpdatesHook(String containerName, int account, TLRPC.Updates updates, String pluginId);

        Map<String, ?> getAllPluginSettings(String pluginId);

        String getPluginPath(String id);

        Object getPluginSetting(String pluginId, String key, Object defaultValue);

        void init(Runnable callback);

        boolean isEngineAvailable();

        boolean isPlugin(File file, MessageObject messageObject);

        void loadPluginFromFile(String filePath, Plugin pluginMetadata, Utilities.Callback<String> callback, PipController.InstallerDelegate delegate);

        List<SettingItem> loadPluginSettings(String id);

        void openInExternalApp(String id);

        void openPluginSetting(Plugin plugin, String linkAlias, BaseFragment fragment);

        void openPluginSetting(String pluginId, String linkAlias, BaseFragment fragment);

        void openPluginSettings(Plugin plugin, BaseFragment fragment);

        void openPluginSettings(String id, BaseFragment fragment);

        void setPluginEnabled(String pluginId, boolean enabled, Utilities.Callback<String> callback);

        void setPluginSetting(String pluginId, String key, Object value);

        void sharePlugin(String id);

        void showInstallDialog(BaseFragment fragment, InstallPluginBottomSheet.PluginInstallParams params);

        void shutdown(Runnable callback);
    }

    // region exteraGram plugins: entry points for the client (network, updates, sending).
    // Cheap when no plugin is loaded, and a failing plugin never breaks the original flow.

    /** exteraGram 13.1.1: the sensitive-request gate snapshots a protected request only while plugin hooks can run. */
    public static boolean areHooksActive() {
        return hooksActive();
    }

    private static boolean hooksActive() {
        PluginsController controller = SingletonHolder.INSTANCE.getINSTANCE();
        return controller.initialized && !controller.plugins.isEmpty() && Companion.isPluginEngineAvailable();
    }

    /** @return the request to send, or null when a plugin cancelled it */
    public static TLObject preRequestHook(String requestName, int account, TLObject request) {
        if (request == null || !hooksActive()) {
            return request;
        }
        try {
            return getInstance().executePreRequestHook(requestName, account, request);
        } catch (Throwable th) {
            FileLog.e(th);
            return request;
        }
    }

    /** @return the (possibly replaced) response and error, or null when nothing changed */
    public static PluginsHooks.PostRequestResult postRequestHook(String requestName, int account, TLObject response, TLRPC.TL_error error) {
        if (!hooksActive()) {
            return null;
        }
        try {
            return getInstance().executePostRequestHook(requestName, account, response, error);
        } catch (Throwable th) {
            FileLog.e(th);
            return null;
        }
    }

    /** @return the update to process, or null when a plugin dropped it */
    public static TLRPC.Update updateHook(int account, TLRPC.Update update) {
        if (update == null || !hooksActive()) {
            return update;
        }
        try {
            return getInstance().executeUpdateHook(update.getClass().getSimpleName(), account, update);
        } catch (Throwable th) {
            FileLog.e(th);
            return update;
        }
    }

    /** @return the updates container to process, or null when a plugin dropped it */
    public static TLRPC.Updates updatesHook(int account, TLRPC.Updates updates) {
        if (updates == null || !hooksActive()) {
            return updates;
        }
        try {
            return getInstance().executeUpdatesHook(updates.getClass().getSimpleName(), account, updates);
        } catch (Throwable th) {
            FileLog.e(th);
            return updates;
        }
    }

    /** @return the message to send, or null when a plugin cancelled it */
    public static SendMessagesHelper.SendMessageParams sendMessageHook(int account, SendMessagesHelper.SendMessageParams params) {
        if (params == null || !hooksActive()) {
            return params;
        }
        try {
            return getInstance().executeSendMessageHook(account, params);
        } catch (Throwable th) {
            FileLog.e(th);
            return params;
        }
    }

    // endregion

    public static final void applyArtOpts() {
        Companion.applyArtOpts();
    }

    public static final void clearFileIcons() {
        Companion.clearFileIcons();
    }

    public static final ConcurrentHashMap<String, PluginsEngine> getEngines() {
        return Companion.getEngines();
    }

    public static final int getFileIconId(String str) {
        return Companion.getFileIconId(str);
    }

    public static final PluginsController getInstance() {
        return Companion.getInstance();
    }

    public static final PluginsEngine getPluginEngine(File file) {
        return Companion.getPluginEngine(file);
    }

    public static final Drawable getPluginFileIconDrawable(int i) {
        return Companion.getPluginFileIconDrawable(i);
    }

    public static final boolean isPlugin(File file, MessageObject messageObject) {
        return Companion.isPlugin(file, messageObject);
    }

    public static final boolean isPlugin(MessageObject messageObject) {
        return Companion.isPlugin(messageObject);
    }

    public static final boolean isPluginEngineAvailable() {
        return Companion.isPluginEngineAvailable();
    }

    public static final boolean isPluginEngineSupported() {
        return Companion.isPluginEngineSupported();
    }

    public static final boolean isPluginFileIcon(int i) {
        return Companion.isPluginFileIcon(i);
    }

    public static final boolean isPluginPinned(String str) {
        return Companion.isPluginPinned(str);
    }

    public static final void openPluginSettings(String str) {
        Companion.openPluginSettings(str);
    }

    public static final void openPluginSettings(String str, String str2) {
        Companion.openPluginSettings(str, str2);
    }

    public static final int registerFileIcon(String str, Drawable drawable) {
        return Companion.registerFileIcon(str, drawable);
    }

    public static final void runOnPluginsQueue(Runnable runnable) {
        Companion.runOnPluginsQueue(runnable);
    }

    public static final void setPluginPinned(String str, boolean z) {
        Companion.setPluginPinned(str, z);
    }

    public static final void unregisterFileIcon(String str) {
        Companion.unregisterFileIcon(str);
    }

    public final void clearPluginSettingsPreferences(String str) {
        clearPluginSettingsPreferences(str, false);
    }

    private PluginsController() {
        this.plugins = new ConcurrentHashMap<>();
        this.settings = new ConcurrentHashMap<>();
        this.menuItemsById = new ConcurrentHashMap<>();
        this.menuItemsByMenuType = new ConcurrentHashMap<>();
        this.hooks = new ConcurrentHashMap<>();
        this.interestedPluginsCache = new ConcurrentHashMap<>();
        this.fileIconIdsByExtension = new ConcurrentHashMap<>();
        this.fileIconDrawablesById = new ConcurrentHashMap<>();
        this.nextFileIconId = new AtomicInteger(101);
        this.substringMatchEventHooksCache = CollectionsKt.emptyList();
        this.exactMatchEventHooksCache = MapsKt.emptyMap();
        this.hooksCacheLock = new Object();
        this.hooksCacheDirty = true;
        this.pluginsDir = new File(ApplicationLoader.getFilesDirFixed(), PluginsConstants.PLUGINS);
        this.preferences = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
        this.watchdog = new PluginsWatchdog(this);
        this.updateNotificationRunnable = new Runnable() {
            @Override
            public final void run() {
                PluginsController.$r8$lambda$TRI271OAbh24WtM0hRmdWUfkDcA();
            }
        };
    }

    public final ConcurrentHashMap<String, Plugin> getPlugins() {
        return this.plugins;
    }

    public final ConcurrentHashMap<String, List<SettingItem>> getSettings() {
        return this.settings;
    }

    public final File getPluginsDir() {
        return this.pluginsDir;
    }

    public final void setPluginsDir(File file) {
        this.pluginsDir = file;
    }

    public final SharedPreferences getPreferences() {
        return this.preferences;
    }

    public final void setPreferences(SharedPreferences sharedPreferences) {
        this.preferences = sharedPreferences;
    }

    public final PluginsWatchdog getWatchdog() {
        return this.watchdog;
    }

    public static void $r8$lambda$TRI271OAbh24WtM0hRmdWUfkDcA() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginsUpdated, new Object[0]);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginMenuItemsUpdated, new Object[0]);
    }

    public final PluginsEngine getPluginEngine(String pluginId) {
        Plugin plugin;
        if (pluginId == null || pluginId.length() == 0 || (plugin = this.plugins.get(pluginId)) == null) {
            return null;
        }
        PluginsEngine cachedEngine = plugin.getCachedEngine();
        if (cachedEngine != null) {
            return cachedEngine;
        }
        String engine = plugin.getEngine();
        if (engine == null) {
            return null;
        }
        PluginsEngine pluginsEngine = Companion.getEngines().get(engine);
        if (pluginsEngine != null) {
            plugin.setCachedEngine(pluginsEngine);
        }
        return pluginsEngine;
    }

    public final void init() {
        init(false, null);
    }

    public final void init(Runnable onDone) {
        init(false, onDone);
    }

    public final void init(boolean startWithSafeMode) {
        init(startWithSafeMode, null);
    }

    public final void init(boolean startWithSafeMode, final Runnable onDone) {
        if (!Companion.isPluginEngineSupported() || !ExteraConfig.getPluginsEngine()) {
            this.initialized = false;
            if (onDone != null) {
                onDone.run();
                return;
            }
            return;
        }
        this.initStartNanos = PluginsPerf.start();
        NativeCrashHandler.checkAndHandleNativeCrash();
        this.watchdog.start();
        ensurePreferences();
        try {
            boolean z = this.preferences.getBoolean("had_crash", false);
            String string = this.preferences.getString("crashed_plugin_id", null);
            boolean z2 = (string != null && Intrinsics.areEqual(string, "manual!")) || startWithSafeMode;
            this.preferences.edit().remove("had_crash").remove("crashed_plugin_id").apply();
            if (z) {
                if (string != null && !z2) {
                    SharedPreferences.Editor editorEdit = this.preferences.edit();
                    editorEdit.putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX.concat(string), false);
                    editorEdit.apply();
                } else {
                    SharedPreferences.Editor editor = ExteraConfig.getEditor();
                    ExteraConfig.setPluginsSafeMode(true);
                    editor.putBoolean("pluginsSafeMode", true).apply();
                }
                if (!z2) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public final void run() {
                            PluginsController.m1546$r8$lambda$CYXYoZ0xzdvzt3MkDUbIPdLop8();
                        }
                    }, 800L);
                }
            } else {
                SharedPreferences.Editor editor2 = ExteraConfig.getEditor();
                ExteraConfig.setPluginsSafeMode(startWithSafeMode);
                editor2.putBoolean("pluginsSafeMode", startWithSafeMode).apply();
            }
        } catch (Exception unused) {
        }
        File file = new File(ApplicationLoader.getFilesDirFixed(), PluginsConstants.PLUGINS);
        this.pluginsDir = file;
        try {
            PluginFiles.mkdirs(this.pluginsDir);
        } catch (java.io.IOException e) {
            FileLog.e("Unable to create plugins directory", e);
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        final Runnable runnable = new Runnable() {
            @Override
            public final void run() {
                PluginsController.m1550$r8$lambda$NrbUySgvJM8bTBfeR7YR1Brtxo(atomicInteger, PluginsController.this, onDone);
            }
        };
        Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginGuard.applyLocalBlocks(PluginsController.this);
                PluginsController.m1544$r8$lambda$8p8Kwkk37JFIqGET_TR6NbwgbQ(runnable);
            }
        });
    }

    public static void m1546$r8$lambda$CYXYoZ0xzdvzt3MkDUbIPdLop8() {
        BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment != null) {
            new SafeModeBottomSheet(lastFragment).show();
        }
    }

    public static void m1550$r8$lambda$NrbUySgvJM8bTBfeR7YR1Brtxo(AtomicInteger atomicInteger, PluginsController pluginsController, Runnable runnable) {
        if (atomicInteger.incrementAndGet() >= Companion.getEngines().size()) {
            pluginsController.initialized = true;
            PluginsPerf.log("engine ready (" + pluginsController.plugins.size() + " plugins)", pluginsController.initStartNanos);
            PluginGuard.scheduleRecheck();
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    public static void m1544$r8$lambda$8p8Kwkk37JFIqGET_TR6NbwgbQ(Runnable runnable) {
        Iterator<PluginsEngine> it = Companion.getEngines().values().iterator();
        while (it.hasNext()) {
            try {
                it.next().init(runnable);
            } catch (Throwable th) {
                FileLog.e("Failed to initialize plugin engine", th);
                runnable.run();
            }
        }
    }

    public final void checkDevServers() {
        Iterator<PluginsEngine> it = Companion.getEngines().values().iterator();
        while (it.hasNext()) {
            it.next().checkDevServer();
        }
    }

    public final void shutdown(final Runnable onDone) {
        if (this.initialized) {
            Companion.runOnPluginsQueue(new Runnable() {
                @Override
                public final void run() {
                    PluginsController.$r8$lambda$izbPeygnK0RqsbS2EM_K8BGUT8A(PluginsController.this, onDone);
                }
            });
        } else if (onDone != null) {
            onDone.run();
        }
    }

    public static void $r8$lambda$izbPeygnK0RqsbS2EM_K8BGUT8A(final PluginsController pluginsController, final Runnable runnable) {
        final AtomicInteger atomicInteger = new AtomicInteger(0);
        Runnable runnable2 = new Runnable() {
            @Override
            public final void run() {
                PluginsController.m1549$r8$lambda$JqkXOllW4WPbvRAkDKW_90FcXQ(atomicInteger, pluginsController, runnable);
            }
        };
        Iterator<PluginsEngine> it = Companion.getEngines().values().iterator();
        while (it.hasNext()) {
            it.next().shutdown(runnable2);
        }
    }

    public static void m1549$r8$lambda$JqkXOllW4WPbvRAkDKW_90FcXQ(AtomicInteger atomicInteger, PluginsController pluginsController, Runnable runnable) {
        if (atomicInteger.incrementAndGet() >= Companion.getEngines().size()) {
            pluginsController.watchdog.stop();
            pluginsController.plugins.clear();
            pluginsController.settings.clear();
            pluginsController.initialized = false;
            FileLog.d("Plugin system shut down.");
            if (runnable != null) {
                runnable.run();
            }
        }
    }

    public final void restart() {
        restart(ExteraConfig.getPluginsSafeMode());
    }

    public final void restart(final boolean startWithSafeMode) {
        FileLog.d("Restarting plugins engine...");
        shutdown(new Runnable() {
            @Override
            public final void run() {
                PluginsController.$r8$lambda$ZPkFPPTQ3SLZXEDTuCogRsCGwUM(PluginsController.this, startWithSafeMode);
            }
        });
    }

    public static void $r8$lambda$ZPkFPPTQ3SLZXEDTuCogRsCGwUM(PluginsController pluginsController, boolean z) {
        if (ExteraConfig.getPluginsEngine()) {
            pluginsController.init(z, new Runnable() {
                @Override
                public final void run() {
                    FileLog.d("Plugins engine restarted.");
                }
            });
        }
    }
    public final boolean isInitialized() {
        return this.initialized;
    }

    public final boolean isPluginActive(String pluginId) {
        Plugin plugin;
        return (pluginId == null || pluginId.length() == 0 || (plugin = this.plugins.get(pluginId)) == null || !plugin.isEnabled()) ? false : true;
    }

    public final boolean isPluginActive(Plugin plugin) {
        return plugin != null && this.plugins.get(plugin.getId()) == plugin && plugin.isEnabled();
    }

    public final int registerFileIconInternal(String extension, Drawable drawable) {
        String strNormalizeFileExtension = normalizeFileExtension(extension);
        if (strNormalizeFileExtension == null || drawable == null) {
            return -1;
        }
        Integer num = this.fileIconIdsByExtension.get(strNormalizeFileExtension);
        if (num != null) {
            this.fileIconDrawablesById.put(num, drawable);
            return num.intValue();
        }
        int andIncrement = this.nextFileIconId.getAndIncrement();
        Integer numPutIfAbsent = this.fileIconIdsByExtension.putIfAbsent(strNormalizeFileExtension, Integer.valueOf(andIncrement));
        if (numPutIfAbsent != null) {
            andIncrement = numPutIfAbsent.intValue();
        }
        this.fileIconDrawablesById.put(Integer.valueOf(andIncrement), drawable);
        return andIncrement;
    }

    public final void unregisterFileIconInternal(String extension) {
        Integer numRemove;
        String strNormalizeFileExtension = normalizeFileExtension(extension);
        if (strNormalizeFileExtension == null || (numRemove = this.fileIconIdsByExtension.remove(strNormalizeFileExtension)) == null) {
            return;
        }
        this.fileIconDrawablesById.remove(numRemove);
    }

    public final int getFileIconIdInternal(String fileName) {
        Integer num;
        if (fileName != null && fileName.length() != 0 && !this.fileIconIdsByExtension.isEmpty()) {
            int iLastIndexOf$default = StringsKt.lastIndexOf((CharSequence) fileName, '.', StringsKt.getLastIndex((CharSequence) fileName), false);
            String strNormalizeFileExtension = normalizeFileExtension(iLastIndexOf$default >= 0 ? fileName.substring(iLastIndexOf$default + 1) : fileName);
            if (strNormalizeFileExtension != null && (num = this.fileIconIdsByExtension.get(strNormalizeFileExtension)) != null) {
                return num.intValue();
            }
        }
        return -1;
    }

    public final void clearFileIconsInternal() {
        this.fileIconIdsByExtension.clear();
        this.fileIconDrawablesById.clear();
    }

    private final String normalizeFileExtension(String extension) {
        if (extension == null || extension.length() == 0) {
            return null;
        }
        return extension.toLowerCase(Locale.ROOT);
    }

    public final List<SettingItem> getPluginSettingsList(String pluginId) {
        if (pluginId == null || pluginId.length() == 0) {
            return null;
        }
        return this.settings.get(pluginId);
    }

    public final void setPluginEnabled(final String pluginId, final boolean enabled, final Utilities.Callback<String> callback) {
        Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginsController.$r8$lambda$GRs7We6PFyg6apyapVWYinmnxH0(PluginsController.this, pluginId, enabled, callback);
            }
        });
    }

    public static void $r8$lambda$GRs7We6PFyg6apyapVWYinmnxH0(PluginsController pluginsController, String str, boolean z, Utilities.Callback callback) {
        PluginsEngine pluginEngine = pluginsController.getPluginEngine(str);
        if (pluginEngine == null || str == null) {
            return;
        }
        if (z && PluginGuard.blocksEnable(pluginsController, str)) {
            if (callback != null) {
                callback.run(PluginGuard.MESSAGE_ENABLE_BLOCKED);
            }
            return;
        }
        if (z) {
            // StaticGram: a plugin that was never analysed (or changed since) is reviewed before it runs
            com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.confirmEnable(str, () -> {
                pluginEngine.setPluginEnabled(str, true, callback);
                pluginsController.interestedPluginsCache.clear();
            }, callback);
            return;
        }
        pluginEngine.setPluginEnabled(str, z, callback);
        pluginsController.interestedPluginsCache.clear();
    }

    public final void deletePlugin(final String pluginId, final Utilities.Callback<String> callback) {
        Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PluginsController.$r8$lambda$GqXcZtNTihu2o6QC3dyJOsjNgck(PluginsController.this, pluginId, callback);
            }
        });
    }

    public static void $r8$lambda$GqXcZtNTihu2o6QC3dyJOsjNgck(PluginsController pluginsController, String str, Utilities.Callback callback) {
        PluginsEngine pluginEngine = pluginsController.getPluginEngine(str);
        if (pluginEngine == null || str == null) {
            return;
        }
        pluginEngine.deletePlugin(str, callback);
    }

    public final void cleanupPlugin(String pluginId) {
        removeHooksByPluginId(pluginId);
        invalidatePluginSettings(pluginId);
        removeMenuItemsByPluginId(pluginId);
    }

    public final String getPluginPath(String id) {
        PluginsEngine pluginEngine;
        if (id == null || id.length() == 0 || (pluginEngine = getPluginEngine(id)) == null) {
            return null;
        }
        return pluginEngine.getPluginPath(id);
    }

    public final void showInstallDialog(BaseFragment fragment, MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        showInstallDialog(fragment, InstallPluginBottomSheet.PluginInstallParams.Companion.of(messageObject));
    }

    /** exteraGram 13.1.1 plugin catalog: opens (or offers to add) an index / Git plugin repository. */
    public final void openGitRepositoryLink(BaseFragment fragment, String url) {
        com.exteragram.messenger.plugins.catalog.PluginRepositoryLinks.openGit(fragment, url);
    }

    /**
     * exteraGram 13.1.1 adds a channel as a plugin repository here. StaticGram has no channel repositories,
     * so the link opens the channel itself.
     */
    public final void openRepositoryLink(BaseFragment fragment, String username) {
        if (fragment != null && username != null && !username.isEmpty()) {
            fragment.getMessagesController().openByUserName(username, fragment, 0);
        }
    }

    public final void showInstallDialog(BaseFragment fragment, String filePath, boolean trusted) {
        if (filePath == null || filePath.length() == 0) {
            return;
        }
        showInstallDialog(fragment, new InstallPluginBottomSheet.PluginInstallParams(filePath, trusted));
    }

    public final void showInstallDialog(final BaseFragment fragment, InstallPluginBottomSheet.PluginInstallParams params) {
        if (fragment == null || !AndroidUtilities.isActivityRunning(fragment.getParentActivity())) {
            return;
        }
        String filePath = params != null ? params.getFilePath() : null;
        if (filePath == null || filePath.length() == 0) {
            return;
        }
        File file = new File(params.getFilePath());
        if (!ExteraConfig.getPluginsEngine()) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.PluginNotEnabled, file.getName()), LocaleController.getString(R.string.Enable), 2750, new Runnable() {
                @Override
                public final void run() {
                    fragment.presentFragment(new PluginsActivity());
                }
            }).show();
            return;
        }
        PluginsEngine pluginEngine = Companion.getPluginEngine(file);
        if (pluginEngine == null) {
            return;
        }
        pluginEngine.showInstallDialog(fragment, params);
    }

    public final void loadPluginSettings() {
        loadPluginSettings(null);
    }

    public final void loadPluginSettings(final String pluginId) {
        if (pluginId == null || pluginId.length() == 0) {
            for (String str : this.plugins.keySet()) {
                Plugin plugin = this.plugins.get(str);
                if (isPluginActive(plugin)) {
                    loadPluginSettings(str);
                } else if (plugin != null) {
                    invalidatePluginSettings(str);
                }
            }
            return;
        }
        Runnable runnable = new Runnable() {
            @Override
            public final void run() {
                PluginsController.$r8$lambda$NIummZv5ylJ7GWVYy9_HekCWNy0(PluginsController.this, pluginId);
            }
        };
        if (isOnPluginsQueueThread()) {
            runnable.run();
        } else {
            Companion.runOnPluginsQueue(runnable);
        }
    }

    public static void $r8$lambda$NIummZv5ylJ7GWVYy9_HekCWNy0(PluginsController pluginsController, String str) {
        try {
            PluginsEngine pluginEngine = pluginsController.getPluginEngine(str);
            if (pluginEngine == null) {
                return;
            }
            List<SettingItem> listLoadPluginSettings = pluginEngine.loadPluginSettings(str);
            if (listLoadPluginSettings == null) {
                boolean had = pluginsController.settings.containsKey(str);
                pluginsController.invalidatePluginSettings(str);
                Plugin p = pluginsController.plugins.get(str);
                if (!had && p != null && p.getSettingsError() != null) {
                    pluginsController.notifyPluginSettingsUnregistered(str); // sg: let the card show the error
                }
                return;
            }
            pluginsController.settings.put(str, listLoadPluginSettings);
            FileLog.d("Registered settings for plugin " + str);
            pluginsController.notifyPluginSettingsRegistered(str);
        } catch (Throwable th) {
            FileLog.e("Failed to register settings for plugin " + str, th);
            Plugin p = pluginsController.plugins.get(str);
            if (p != null) {
                p.setSettingsError(th);
            }
            pluginsController.invalidatePluginSettings(str);
            pluginsController.notifyPluginSettingsUnregistered(str);
        }
    }

    public final boolean hasPluginSettings(String pluginId) {
        return (pluginId == null || pluginId.length() == 0 || !this.settings.containsKey(pluginId)) ? false : true;
    }

    public final void invalidatePluginSettings(String pluginId) {
        List<SettingItem> listRemove;
        if (pluginId == null || pluginId.length() == 0 || (listRemove = this.settings.remove(pluginId)) == null) {
            return;
        }
        Iterator<SettingItem> it = listRemove.iterator();
        while (it.hasNext()) {
            it.next().cleanup();
        }
        notifyPluginSettingsUnregistered(pluginId);
    }

    public final void clearPluginSettingsPreferences(String pluginId, boolean clearEnabledState) {
        if (pluginId == null || pluginId.length() == 0) {
            return;
        }
        PluginsEngine pluginEngine = getPluginEngine(pluginId);
        if (pluginEngine != null) {
            pluginEngine.clearPluginSettings(pluginId);
        } else {
            Iterator<PluginsEngine> it = Companion.getEngines().values().iterator();
            while (it.hasNext()) {
                it.next().clearPluginSettings(pluginId);
            }
        }
        ensurePreferences();
        if (clearEnabledState) {
            String str = PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId;
            if (this.preferences.contains(str)) {
                SharedPreferences.Editor editorEdit = this.preferences.edit();
                editorEdit.remove(str);
                editorEdit.apply();
            }
        }
    }

    public final Map<String, ?> getPluginSettingsPreferences(String pluginId) {
        PluginsEngine pluginEngine = getPluginEngine(pluginId);
        if (pluginEngine != null) {
            return pluginEngine.getAllPluginSettings(pluginId);
        }
        return null;
    }

    public final boolean hasPluginSettingsPreferences(String pluginId) {
        Map<String, ?> pluginSettingsPreferences = getPluginSettingsPreferences(pluginId);
        return !(pluginSettingsPreferences == null || pluginSettingsPreferences.isEmpty());
    }

    public final boolean getPluginSettingBoolean(String pluginId, String key, boolean defaultValue) {
        PluginsEngine pluginEngine;
        if (pluginId != null && pluginId.length() != 0 && key != null && key.length() != 0 && (pluginEngine = getPluginEngine(pluginId)) != null) {
            Object pluginSetting = pluginEngine.getPluginSetting(pluginId, key, Boolean.valueOf(defaultValue));
            if (pluginSetting instanceof Boolean) {
                return ((Boolean) pluginSetting).booleanValue();
            }
        }
        return defaultValue;
    }

    public final String getPluginSettingString(String pluginId, String key, String defaultValue) {
        PluginsEngine pluginEngine;
        Object pluginSetting;
        return (pluginId == null || pluginId.length() == 0 || key == null || key.length() == 0 || (pluginEngine = getPluginEngine(pluginId)) == null || (pluginSetting = pluginEngine.getPluginSetting(pluginId, key, defaultValue)) == null) ? defaultValue : pluginSetting.toString();
    }

    public final int getPluginSettingInt(String pluginId, String key, int defaultValue) {
        PluginsEngine pluginEngine;
        if (pluginId != null && pluginId.length() != 0 && key != null && key.length() != 0 && (pluginEngine = getPluginEngine(pluginId)) != null) {
            Object pluginSetting = pluginEngine.getPluginSetting(pluginId, key, Integer.valueOf(defaultValue));
            if (pluginSetting instanceof Number) {
                return ((Number) pluginSetting).intValue();
            }
        }
        return defaultValue;
    }

    public final void setPluginSetting(String pluginId, String key, Object value) {
        setPluginSettingAndTriggerOnChange(pluginId, key, value, null);
    }

    public final void setPluginSettingAndTriggerOnChange(String pluginId, String key, Object value, PyObject onChangeCallback) {
        PluginsEngine pluginEngine;
        if (pluginId == null || pluginId.length() == 0 || key == null || key.length() == 0 || !isPluginActive(pluginId) || (pluginEngine = getPluginEngine(pluginId)) == null) {
            return;
        }
        pluginEngine.setPluginSetting(pluginId, key, value);
        if (onChangeCallback != null) {
            try {
                onChangeCallback.call(value);
            } catch (Exception e) {
                FileLog.e("Error executing on_change callback for " + pluginId + "/" + key, e);
            }
        }
        loadPluginSettings(pluginId);
    }

    private final void addHook(String pluginId, HookRecord newHook, String logMessage) {
        if (pluginId == null || pluginId.length() == 0 || newHook == null) {
            return;
        }
        ConcurrentHashMap<String, Set<HookRecord>> concurrentHashMap = this.hooks;
        final Function1 function1 = new Function1() {
            @Override
            public final Object invoke(Object obj) {
                return PluginsController.$r8$lambda$bFCGo4qve6jYoEhoW_CcYQyIBq8((String) obj);
            }
        };
        if (concurrentHashMap.computeIfAbsent(pluginId, new Function() {
            @Override
            public final Object apply(Object obj) {
                return PluginsController.m1555$r8$lambda$V9DYDu372WRW1KDk_icvlxw3nU(function1, obj);
            }
        }).add(newHook)) {
            FileLog.d(logMessage);
            this.interestedPluginsCache.clear();
            this.hooksCacheDirty = true;
        }
    }

    public static Set m1555$r8$lambda$V9DYDu372WRW1KDk_icvlxw3nU(Function1 function1, Object obj) {
        return (Set) function1.invoke(obj);
    }

    public static Set $r8$lambda$bFCGo4qve6jYoEhoW_CcYQyIBq8(String str) {
        return new CopyOnWriteArraySet();
    }

    public final void addEventHook(String pluginId, String hookName, boolean matchSubstring, int priority) {
        addHook(pluginId, new EventHookRecord(pluginId, hookName, matchSubstring, priority), "Added event hook '" + hookName + "' for plugin " + pluginId);
    }

    private final void removeHook(String pluginId, Function1<? super HookRecord, Boolean> filter, String logMessage) {
        Set<HookRecord> set;
        if (pluginId == null || pluginId.length() == 0 || (set = this.hooks.get(pluginId)) == null || set.isEmpty()) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        ArrayList arrayList2 = new ArrayList();
        for (HookRecord hookRecord : set) {
            if (filter.invoke(hookRecord).booleanValue()) {
                arrayList2.add(hookRecord);
            } else {
                arrayList.add(hookRecord);
            }
        }
        if (arrayList2.isEmpty()) {
            return;
        }
        int size = arrayList2.size();
        int i = 0;
        while (i < size) {
            Object obj = arrayList2.get(i);
            i++;
            ((HookRecord) obj).cleanup();
        }
        boolean zIsEmpty = arrayList.isEmpty();
        ConcurrentHashMap<String, Set<HookRecord>> concurrentHashMap = this.hooks;
        if (zIsEmpty) {
            concurrentHashMap.remove(pluginId);
        } else {
            concurrentHashMap.put(pluginId, new CopyOnWriteArraySet(arrayList));
        }
        FileLog.d(logMessage);
        this.interestedPluginsCache.clear();
        this.hooksCacheDirty = true;
    }

    public final void removeEventHook(String pluginId, final String hookName) {
        removeHook(pluginId, new Function1() {
            @Override
            public final Object invoke(Object obj) {
                return Boolean.valueOf(PluginsController.m1558$r8$lambda$irUSMfwTkkVsshZk4DQLFIDV2Q(hookName, (HookRecord) obj));
            }
        }, "Removed event hook(s) matching name '" + hookName + "' for plugin " + pluginId);
    }

    public static boolean m1558$r8$lambda$irUSMfwTkkVsshZk4DQLFIDV2Q(String str, HookRecord hookRecord) {
        return (hookRecord instanceof EventHookRecord) && Intrinsics.areEqual(((EventHookRecord) hookRecord).getHookName(), str);
    }

    /** exteraGram 13.1.1: a plugin hooked a method with this name declared in one of the classes. */
    public final boolean hasXposedHook(String methodName, Class<?>... classes) {
        try {
            for (Set<HookRecord> records : hooks.values()) {
                if (records == null) {
                    continue;
                }
                for (HookRecord record : records.toArray(new HookRecord[0])) {
                    if (!(record instanceof com.exteragram.messenger.plugins.hooks.XposedHookRecord)) {
                        continue;
                    }
                    java.lang.reflect.Member member = ((com.exteragram.messenger.plugins.hooks.XposedHookRecord) record).getHookedMethod();
                    if (member == null || !methodName.equals(member.getName())) {
                        continue;
                    }
                    for (Class<?> cls : classes) {
                        if (cls == member.getDeclaringClass()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public final void addXposedHook(String pluginId, XC_MethodHook.Unhook unhook) {
        addHook(pluginId, unhook != null ? new XposedHookRecord(unhook) : null, "Added Xposed hook for plugin " + pluginId);
    }

    public final void addXposedHooks(String pluginId, ArrayList<XC_MethodHook.Unhook> unhooks) {
        if (unhooks == null) {
            return;
        }
        Iterator<XC_MethodHook.Unhook> it = unhooks.iterator();
        while (it.hasNext()) {
            addXposedHook(pluginId, it.next());
        }
    }

    public final void removeXposedHook(String pluginId, final XC_MethodHook.Unhook unhook) {
        removeHook(pluginId, new Function1() {
            @Override
            public final Object invoke(Object obj) {
                return Boolean.valueOf(PluginsController.$r8$lambda$htrrOUG8xUM_bIJfiVnNVxY_i18(unhook, (HookRecord) obj));
            }
        }, "Removed Xposed hook for plugin " + pluginId);
    }

    public static boolean $r8$lambda$htrrOUG8xUM_bIJfiVnNVxY_i18(XC_MethodHook.Unhook unhook, HookRecord hookRecord) {
        return (hookRecord instanceof XposedHookRecord) && ((XposedHookRecord) hookRecord).matches(unhook);
    }

    public final void removeHooksByPluginId(String pluginId) {
        Set<HookRecord> setRemove;
        if (pluginId == null || pluginId.length() == 0 || (setRemove = this.hooks.remove(pluginId)) == null) {
            return;
        }
        Iterator<HookRecord> it = setRemove.iterator();
        while (it.hasNext()) {
            it.next().cleanup();
        }
        FileLog.d("Removed all (" + setRemove.size() + ") hooks for plugin " + pluginId);
        this.interestedPluginsCache.clear();
        this.hooksCacheDirty = true;
    }

    public final String addMenuItem(String pluginId, PyObject pyMenuItemData) {
        if (Companion.isPluginEngineAvailable() && pyMenuItemData != null && pluginId != null && pluginId.length() != 0) {
            try {
                final MenuItemRecord menuItemRecord = new MenuItemRecord(pluginId, pyMenuItemData);
                String menuType = menuItemRecord.getMenuType();
                if (menuType == null) {
                    android.util.Log.w("sgPlugins", "addMenuItem: plugin " + pluginId + " item without menu_type"); // sg: logcat
                    return null;
                }
                MenuItemRecord menuItemRecord2 = this.menuItemsById.get(menuItemRecord.getItemId());
                if (menuItemRecord2 != null && !Intrinsics.areEqual(menuItemRecord2.getPluginId(), pluginId)) {
                    FileLog.w("Plugin " + pluginId + " tried to add a menu item: " + menuItemRecord.getItemId() + ", which is already used by plugin " + menuItemRecord2.getPluginId());
                    android.util.Log.w("sgPlugins", "addMenuItem: " + pluginId + " item " + menuItemRecord.getItemId() + " already used by " + menuItemRecord2.getPluginId()); // sg: logcat
                    return null;
                }
                if (menuItemRecord2 != null) {
                    CopyOnWriteArrayList<MenuItemRecord> copyOnWriteArrayList = this.menuItemsByMenuType.get(menuItemRecord2.getMenuType());
                    if (copyOnWriteArrayList != null) {
                        copyOnWriteArrayList.remove(menuItemRecord2);
                    }
                    menuItemRecord2.markRemoved();
                }
                this.menuItemsById.put(menuItemRecord.getItemId(), menuItemRecord);
                ConcurrentHashMap<String, CopyOnWriteArrayList<MenuItemRecord>> concurrentHashMap = this.menuItemsByMenuType;
                final Function2 function2 = new Function2() {
                    @Override
                    public final Object invoke(Object obj, Object obj2) {
                        return PluginsController.$r8$lambda$PnMDHZ4aRveF9I0VgNHW2f1JUEU(menuItemRecord, (String) obj, (CopyOnWriteArrayList) obj2);
                    }
                };
                concurrentHashMap.compute(menuType, new BiFunction() {
                    @Override
                    public final Object apply(Object obj, Object obj2) {
                        return PluginsController.$r8$lambda$ObuTyILOrv8Lv83Lljknveq0jHI(function2, obj, obj2);
                    }
                });
                FileLog.d("Added menu item: " + menuItemRecord.getItemId() + " for plugin " + pluginId + " in type " + menuType);
                android.util.Log.i("sgPlugins", "addMenuItem: " + menuItemRecord.getItemId() + " plugin=" + pluginId + " type=" + menuType); // sg: logcat
                notifyMenuItemsUpdated();
                return menuItemRecord.getItemId();
            } catch (Exception unused) {
                FileLog.e("addMenuItem failed for plugin " + pluginId, unused);
                android.util.Log.e("sgPlugins", "addMenuItem failed for plugin " + pluginId, unused); // sg: logcat
            }
        } else {
            android.util.Log.w("sgPlugins", "addMenuItem rejected: plugin=" + pluginId + " engineAvailable=" + Companion.isPluginEngineAvailable() + " data=" + (pyMenuItemData != null)); // sg: logcat
        }
        return null;
    }

    public static CopyOnWriteArrayList $r8$lambda$ObuTyILOrv8Lv83Lljknveq0jHI(Function2 function2, Object obj, Object obj2) {
        return (CopyOnWriteArrayList) function2.invoke(obj, obj2);
    }

    public static CopyOnWriteArrayList $r8$lambda$PnMDHZ4aRveF9I0VgNHW2f1JUEU(final MenuItemRecord menuItemRecord, String str, CopyOnWriteArrayList copyOnWriteArrayList) {
        ArrayList arrayList;
        if (copyOnWriteArrayList == null) {
            arrayList = new ArrayList();
        } else {
            arrayList = new ArrayList(copyOnWriteArrayList);
            CollectionsKt.removeAll((List) arrayList, new Function1() {
                @Override
                public final Object invoke(Object obj) {
                    return Boolean.valueOf(Intrinsics.areEqual(((MenuItemRecord) obj).getItemId(), menuItemRecord.getItemId()));
                }
            });
        }
        arrayList.add(menuItemRecord);
        final Function1 function1 = new Function1() {
            @Override
            public final Object invoke(Object obj) {
                return Integer.valueOf(((MenuItemRecord) obj).getPriority());
            }
        };
        CollectionsKt.sortWith(arrayList, Comparator.comparingInt(new ToIntFunction() {
            @Override
            public final int applyAsInt(Object obj) {
                return ((Number) function1.invoke(obj)).intValue();
            }
        }).reversed());
        return new CopyOnWriteArrayList(arrayList);
    }

    public final boolean removeMenuItem(String pluginId, String itemId) {
        MenuItemRecord menuItemRecordRemove;
        if (itemId == null || itemId.length() == 0 || (menuItemRecordRemove = this.menuItemsById.remove(itemId)) == null || menuItemRecordRemove.getMenuType() == null) {
            return false;
        }
        if (!Intrinsics.areEqual(menuItemRecordRemove.getPluginId(), pluginId)) {
            this.menuItemsById.put(itemId, menuItemRecordRemove);
            return false;
        }
        CopyOnWriteArrayList<MenuItemRecord> copyOnWriteArrayList = this.menuItemsByMenuType.get(menuItemRecordRemove.getMenuType());
        if (copyOnWriteArrayList != null) {
            copyOnWriteArrayList.remove(menuItemRecordRemove);
        }
        menuItemRecordRemove.markRemoved();
        FileLog.d("Removed menu item: " + itemId + " for plugin " + pluginId);
        notifyMenuItemsUpdated();
        return true;
    }

    public final void removeMenuItemsByPluginId(String pluginId) {
        if (pluginId == null || pluginId.length() == 0) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        for (MenuItemRecord menuItemRecord : this.menuItemsById.values()) {
            if (Intrinsics.areEqual(menuItemRecord.getPluginId(), pluginId)) {
                arrayList.add(menuItemRecord.getItemId());
            }
        }
        if (arrayList.isEmpty()) {
            return;
        }
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            removeMenuItem(pluginId, (String) it.next());
        }
        FileLog.d("Removed all menu items for plugin: " + pluginId);
    }

    public final List<MenuItemRecord> getMenuItemsForLocation(String menuType, MenuContextBuilder builder) {
        if (builder == null) {
            return getMenuItemsForLocation(menuType, new HashMap());
        }
        return getMenuItemsForLocation(menuType, (Map<String, ? extends Object>) builder.build());
    }

    public final List<MenuItemRecord> getMenuItemsForLocation(String menuType, Map<String, ? extends Object> contextData) {
        CopyOnWriteArrayList<MenuItemRecord> copyOnWriteArrayList;
        if (!Companion.isPluginEngineAvailable() || menuType == null || menuType.length() == 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<MenuItemRecord> linkedHashSet = new LinkedHashSet();
        CopyOnWriteArrayList<MenuItemRecord> copyOnWriteArrayList2 = this.menuItemsByMenuType.get(menuType);
        if (copyOnWriteArrayList2 != null && !copyOnWriteArrayList2.isEmpty()) {
            linkedHashSet.addAll(copyOnWriteArrayList2);
        }
        if (Intrinsics.areEqual(PluginsConstants.MenuItemTypes.MAIN_MENU, menuType) && (copyOnWriteArrayList = this.menuItemsByMenuType.get(PluginsConstants.MenuItemTypes.DRAWER_MENU)) != null && !copyOnWriteArrayList.isEmpty()) {
            linkedHashSet.addAll(copyOnWriteArrayList);
        }
        if (linkedHashSet.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList arrayList = new ArrayList();
        android.util.Log.d("sgPlugins", "getMenuItemsForLocation: " + menuType + " registered=" + linkedHashSet.size()); // sg: logcat
        for (MenuItemRecord menuItemRecord : linkedHashSet) {
            if (isPluginActive(menuItemRecord.getPluginId())) {
                this.watchdog.onPluginExecutionStarted(menuItemRecord.getPluginId());
                try {
                    if (menuItemRecord.checkCondition(contextData)) {
                        arrayList.add(menuItemRecord);
                    } else {
                        android.util.Log.d("sgPlugins", "getMenuItemsForLocation: " + menuType + " item " + menuItemRecord.getItemId() + " hidden by condition"); // sg: logcat
                    }
                    this.watchdog.onPluginExecutionFinished(menuItemRecord.getPluginId());
                } catch (Throwable th) {
                    this.watchdog.onPluginExecutionFinished(menuItemRecord.getPluginId());
                    throw th;
                }
            }
        }
        return arrayList;
    }

    public final void notifyPluginsChanged() {
        AndroidUtilities.cancelRunOnUIThread(this.updateNotificationRunnable);
        AndroidUtilities.runOnUIThread(this.updateNotificationRunnable, 150L);
    }

    public final void executeOnAppEvent(String eventType) {
        if (this.initialized) {
            Companion companion = Companion;
            if (!companion.isPluginEngineAvailable() || eventType == null) {
                return;
            }
            FileLog.d("Execute scripts on app event ".concat(eventType));
            Iterator<PluginsEngine> it = companion.getEngines().values().iterator();
            while (it.hasNext()) {
                try {
                    it.next().executeOnAppEvent(eventType);
                } catch (Throwable th) {
                    FileLog.e("Failed to dispatch app event " + eventType, th);
                }
            }
        }
    }

    private final List<String> getInterestedPluginIds(String eventName) {
        if (eventName == null || eventName.length() == 0) {
            return Collections.emptyList();
        }
        List<String> cached = this.interestedPluginsCache.get(eventName);
        if (cached != null) {
            return cached;
        }
        rebuildHooksCacheIfNeeded();
        // plugin id -> highest priority of its hooks matching this event
        HashMap<String, Integer> priorities = new HashMap<>();
        List<EventHookRecord> exact = this.exactMatchEventHooksCache.get(eventName);
        if (exact != null) {
            for (EventHookRecord record : exact) {
                if (record.getPluginId() != null) {
                    priorities.merge(record.getPluginId(), record.getPriority(), Math::max);
                }
            }
        }
        for (EventHookRecord record : this.substringMatchEventHooksCache) {
            if (record.matches(eventName) && record.getPluginId() != null) {
                priorities.merge(record.getPluginId(), record.getPriority(), Math::max);
            }
        }
        List<String> result;
        if (priorities.isEmpty()) {
            result = Collections.emptyList();
        } else {
            ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(priorities.entrySet());
            Collections.sort(entries, (a, b) -> {
                int byPriority = Integer.compare(b.getValue(), a.getValue());
                return byPriority == 0 ? a.getKey().compareTo(b.getKey()) : byPriority;
            });
            result = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : entries) {
                if (isPluginActive(entry.getKey())) {
                    result.add(entry.getKey());
                }
            }
        }
        this.interestedPluginsCache.put(eventName, result);
        if (!result.isEmpty()) {
            FileLog.d("Calculated and cached potential plugins for '" + eventName + "': " + result);
        }
        return result;
    }

    private final void rebuildHooksCacheIfNeeded() {
        if (!this.hooksCacheDirty) {
            return;
        }
        synchronized (this.hooksCacheLock) {
            if (!this.hooksCacheDirty) {
                return;
            }
            HashMap<String, List<EventHookRecord>> exact = new HashMap<>();
            ArrayList<EventHookRecord> substring = new ArrayList<>();
            for (Set<HookRecord> records : this.hooks.values()) {
                for (HookRecord hookRecord : records) {
                    if (!(hookRecord instanceof EventHookRecord)) {
                        continue;
                    }
                    EventHookRecord record = (EventHookRecord) hookRecord;
                    if (record.isMatchSubstring()) {
                        substring.add(record);
                    } else if (record.getHookName() != null) {
                        exact.computeIfAbsent(record.getHookName(), k -> new ArrayList<>()).add(record);
                    }
                }
            }
            this.exactMatchEventHooksCache = exact;
            this.substringMatchEventHooksCache = substring;
            this.hooksCacheDirty = false;
        }
    }

    private final void ensurePreferences() {
        this.preferences = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
    }

    private final boolean isOnPluginsQueueThread() {
        return (Utilities.pluginsQueue == null || Utilities.pluginsQueue.getHandler() == null || !Intrinsics.areEqual(Thread.currentThread(), Utilities.pluginsQueue.getHandler().getLooper().getThread())) ? false : true;
    }

    private final void notifyPluginSettingsRegistered(final String pluginId) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginSettingsRegistered, pluginId);
            }
        });
    }

    private final void notifyPluginSettingsUnregistered(final String pluginId) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginSettingsUnregistered, pluginId);
            }
        });
    }

    private final void notifyMenuItemsUpdated() {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginMenuItemsUpdated, new Object[0]);
            }
        });
    }

    private final <T> T executeGenericHook(String hookName, T initialObject, EngineHookCaller<T> caller) {
        if (!Companion.isPluginEngineAvailable()) {
            return initialObject;
        }
        List<String> interestedPluginIds = getInterestedPluginIds(hookName);
        if (interestedPluginIds.isEmpty()) {
            return initialObject;
        }
        for (String pluginId : interestedPluginIds) {
            PluginsEngine pluginEngine;
            if (!isPluginActive(pluginId) || (pluginEngine = getPluginEngine(pluginId)) == null) {
                continue;
            }
            this.watchdog.onPluginExecutionStarted(pluginId);
            try {
                HookResult<T> hookResult = caller.call(pluginEngine, initialObject, pluginId);
                if (hookResult.getCancel()) {
                    return null;
                }
                initialObject = hookResult.getResult();
                if (hookResult.isFinal()) {
                    return initialObject;
                }
            } catch (Throwable th) {
                // exteraGram plugins: a broken plugin must never break the request/update pipeline
                FileLog.e("Plugin " + pluginId + " failed in hook " + hookName, th);
            } finally {
                this.watchdog.onPluginExecutionFinished(pluginId);
            }
        }
        return initialObject;
    }

    @Override
    public TLObject executePreRequestHook(final String requestName, final int account, TLObject request) {
        return executeGenericHook(requestName, request, (engine, obj, pluginId) -> engine.executePreRequestHook(requestName, account, obj, pluginId));
    }

    @Override
    public PluginsHooks.PostRequestResult executePostRequestHook(final String requestName, final int account, TLObject response, TLRPC.TL_error error) {
        PluginsHooks.PostRequestResult postRequestResult = executeGenericHook(requestName, new PluginsHooks.PostRequestResult(response, error),
                (engine, obj, pluginId) -> engine.executePostRequestHook(requestName, account, obj != null ? obj.getResponse() : null, obj != null ? obj.getError() : null, pluginId));
        return postRequestResult == null ? new PluginsHooks.PostRequestResult(response, error) : postRequestResult;
    }

    @Override
    public TLRPC.Update executeUpdateHook(final String updateName, final int account, TLRPC.Update update) {
        return executeGenericHook(updateName, update, (engine, obj, pluginId) -> engine.executeUpdateHook(updateName, account, obj, pluginId));
    }

    @Override
    public TLRPC.Updates executeUpdatesHook(final String containerName, final int account, TLRPC.Updates updates) {
        return executeGenericHook(containerName, updates, (engine, obj, pluginId) -> engine.executeUpdatesHook(containerName, account, obj, pluginId));
    }

    @Override
    public SendMessagesHelper.SendMessageParams executeSendMessageHook(final int account, SendMessagesHelper.SendMessageParams params) {
        return executeGenericHook(PluginsConstants.SEND_MESSAGE_HOOK, params, (engine, obj, pluginId) -> engine.executeSendMessageHook(account, obj, pluginId));
    }

    public static final class HookResult<T> {
        private boolean cancel;
        private boolean isFinal;
        private T result;

        public HookResult(T t, boolean z, boolean z2) {
            this.result = t;
            this.cancel = z;
            this.isFinal = z2;
        }

        public final T getResult() {
            return this.result;
        }

        public final void setResult(T t) {
            this.result = t;
        }

        public final boolean getCancel() {
            return this.cancel;
        }

        public final void setCancel(boolean z) {
            this.cancel = z;
        }
        public final boolean isFinal() {
            return this.isFinal;
        }

        public final void setFinal(boolean z) {
            this.isFinal = z;
        }
    }

    public static final class PluginValidationResult {
        private String error;
        private Plugin plugin;

        public PluginValidationResult(Plugin plugin, String str) {
            this.plugin = plugin;
            this.error = str;
        }

        public final Plugin getPlugin() {
            return this.plugin;
        }

        public final void setPlugin(Plugin plugin) {
            this.plugin = plugin;
        }

        public final String getError() {
            return this.error;
        }

        public final void setError(String str) {
            this.error = str;
        }
    }

    public static final class SingletonHolder {
        public static final SingletonHolder INSTANCE = new SingletonHolder();

        private static final PluginsController HOLDER = new PluginsController();

        private SingletonHolder() {
        }

        public final PluginsController getINSTANCE() {
            return HOLDER;
        }
    }

    public static final class Companion {
        private static volatile Boolean engineSupported;

        /**
         * exteraStatic: the plugin SDK is pure Python and bundled with the app, so the engine
         * runs on every ABI Chaquopy supports (arm64-v8a, armeabi-v7a, x86, x86_64).
         */
        public final boolean isPluginEngineSupported() {
            return true;
        }

        private Companion() {
        }

        public final ConcurrentHashMap<String, PluginsEngine> getEngines() {
            return PluginsController.enginesMap;
        }

        public final PluginsController getInstance() {
            return SingletonHolder.INSTANCE.getINSTANCE();
        }

        public final int registerFileIcon(String extension, Drawable drawable) {
            return getInstance().registerFileIconInternal(extension, drawable);
        }

        public final void unregisterFileIcon(String extension) {
            getInstance().unregisterFileIconInternal(extension);
        }

        public final void clearFileIcons() {
            getInstance().clearFileIconsInternal();
        }

        public final int getFileIconId(String fileName) {
            return getInstance().getFileIconIdInternal(fileName);
        }

        public final boolean isPluginFileIcon(int icon) {
            if (icon < 101) {
                return false;
            }
            return getInstance().fileIconDrawablesById.containsKey(Integer.valueOf(icon));
        }

        public final Drawable getPluginFileIconDrawable(int icon) {
            return (Drawable) getInstance().fileIconDrawablesById.get(Integer.valueOf(icon));
        }

        public final boolean isPluginEngineAvailable() {
            if (isPluginEngineSupported() && ExteraConfig.getPluginsEngine() && !ExteraConfig.getPluginsSafeMode()) {
                PluginsEngine pluginsEngine = (PluginsEngine) PluginsController.enginesMap.get(PluginsConstants.PYTHON);
                if (pluginsEngine != null && pluginsEngine.isEngineAvailable()) {
                    return true;
                }
                for (PluginsEngine pluginsEngine2 : getEngines().values()) {
                    if (pluginsEngine2 != pluginsEngine) {
                        try {
                            if (pluginsEngine2.isEngineAvailable()) {
                                return true;
                            }
                        } catch (Throwable th) {
                            FileLog.e("Error checking engine availability.", th);
                        }
                    }
                }
            }
            return false;
        }

        public final void applyArtOpts() {
            if (ExteraConfig.getPreferences().getBoolean("pluginsEngine", false) && ExteraConfig.getPluginsDisableArtOpts() && isPluginEngineSupported()) {
                try {
                    XposedBridge.disableProfileSaver();
                } catch (Throwable th) {
                    FileLog.e(th);
                }
            }
        }

        public final boolean isPlugin(MessageObject messageObject) {
            String pathToMessage = ChatUtils.getInstance().getPathToMessage(messageObject);
            return (messageObject == null || messageObject.getDocumentName() == null || TextUtils.isEmpty(pathToMessage) || !isPlugin(new File(pathToMessage), messageObject) || !isPluginEngineSupported()) ? false : true;
        }

        public final boolean isPlugin(File file, MessageObject messageObject) {
            if (file == null) {
                return false;
            }
            Iterator<PluginsEngine> it = getEngines().values().iterator();
            while (it.hasNext()) {
                if (it.next().isPlugin(file, messageObject)) {
                    return true;
                }
            }
            return false;
        }

        public final PluginsEngine getPluginEngine(File file) {
            if (file == null) {
                return null;
            }
            for (PluginsEngine pluginsEngine : getEngines().values()) {
                if (pluginsEngine.isPlugin(file, null)) {
                    return pluginsEngine;
                }
            }
            return null;
        }

        public final void openPluginSettings(String pluginId) {
            openPluginSettings(pluginId, null);
        }

        public final void openPluginSettings(String pluginId, String linkAlias) {
            final BaseFragment lastFragment;
            if (pluginId == null || pluginId.length() == 0 || (lastFragment = LaunchActivity.getLastFragment()) == null) {
                return;
            }
            if (!ExteraConfig.getPluginsEngine()) {
                BulletinFactory.of(lastFragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.PluginEngineNotEnabled, pluginId), LocaleController.getString(R.string.Enable), 2750, new Runnable() {
                    @Override
                    public final void run() {
                        lastFragment.presentFragment(new PluginsActivity());
                    }
                }).show();
                return;
            }
            Plugin plugin = getInstance().getPlugins().get(pluginId);
            if (plugin == null) {
                BulletinFactory.of(lastFragment).createEmojiBulletin("🤷\u200d♂️", LocaleController.formatString(R.string.PluginNotFound, pluginId)).show();
                return;
            }
            if (!getInstance().hasPluginSettings(pluginId)) {
                BulletinFactory.of(lastFragment).createEmojiBulletin("🤷\u200d♂️", LocaleController.formatString(R.string.PluginHasNoSettings, plugin.getName())).show();
                return;
            }
            PluginsEngine pluginEngine = getInstance().getPluginEngine(pluginId);
            if (pluginEngine != null) {
                if (linkAlias == null) {
                    pluginEngine.openPluginSettings(pluginId, lastFragment);
                } else {
                    pluginEngine.openPluginSetting(pluginId, linkAlias, lastFragment);
                }
            }
        }

        public final boolean isPluginPinned(String pluginId) {
            return (pluginId == null || pluginId.length() == 0 || !ExteraConfig.getPinnedPlugins().contains(pluginId)) ? false : true;
        }

        public final void setPluginPinned(String pluginId, boolean isPinned) {
            if (pluginId == null || pluginId.length() == 0) {
                return;
            }
            HashSet<String> hashSet = new HashSet<>(ExteraConfig.getPinnedPlugins());
            if (!isPinned) {
                hashSet.remove(pluginId);
            } else {
                hashSet.add(pluginId);
            }
            ExteraConfig.setPinnedPlugins(hashSet);
            ExteraConfig.getEditor().putStringSet("pinnedPlugins", hashSet).apply();
            getInstance().notifyPluginsChanged();
        }

        public final void runOnPluginsQueue(Runnable runnable) {
            if (Utilities.pluginsQueue == null || !Utilities.pluginsQueue.isAlive()) {
                synchronized (PluginsController.class) {
                    if (Utilities.pluginsQueue == null || !Utilities.pluginsQueue.isAlive()) {
                        Utilities.pluginsQueue = new DispatchQueue("pluginsQueue");
                    }
                }
            }
            Utilities.pluginsQueue.postRunnable(runnable);
        }
    }
}
