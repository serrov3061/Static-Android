package com.exteragram.messenger.plugins;

import android.view.View;
import com.exteragram.messenger.plugins.models.CustomSetting;
import com.exteragram.messenger.plugins.models.DividerSetting;
import com.exteragram.messenger.plugins.models.EditTextSetting;
import com.exteragram.messenger.plugins.models.HeaderSetting;
import com.exteragram.messenger.plugins.models.InputSetting;
import com.exteragram.messenger.plugins.models.SelectorSetting;
import com.exteragram.messenger.plugins.models.SwitchSetting;
import java.util.LinkedHashSet;
import org.telegram.ui.Components.UItem;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import androidx.core.content.FileProvider;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.models.TextSetting;
import com.exteragram.messenger.plugins.pip.PipController;
import com.exteragram.messenger.plugins.ui.PluginSettingsActivity;
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import com.exteragram.messenger.plugins.ui.components.PluginFileViewer;
import com.exteragram.messenger.plugins.ui.components.PythonSdkUpdateAlert;
import com.exteragram.messenger.plugins.utils.PluginsPerf;
import com.exteragram.messenger.plugins.utils.PyObjectUtils;
import com.exteragram.messenger.plugins.utils.SdkSignature;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.Result;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.collections.ArraysKt;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;
import kotlin.collections.SetsKt;
import kotlin.io.CloseableKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.ArrayIteratorKt;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Ref;
import kotlin.jvm.internal.StringCompanionObject;
import kotlin.ranges.RangesKt;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import kotlin.time.DurationKt;
import com.exteragram.messenger.plugins.utils.PluginFiles;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

public final class PythonPluginsEngine implements PluginsController.PluginsEngine {
    private static final long MAX_SDK_VERSION_BYTES = 65536;
    private static final String SAFE_MODE_ENABLE_ERROR = "Plugins cannot be enabled while safe mode is active";
    private static boolean SDK_BETA;
    private static File SDK_DIR; // exteraStatic: unused, the SDK ships as app sources
    private static volatile Set<String> SDK_MODULE_NAMES = Collections.emptySet();
    private static String SDK_VERSION;
    private static volatile boolean sdkInitialized;
    private volatile PyObject basePluginClass;
    private PyObject debuggerListener;
    private PyObject devServerClass;
    private volatile Python python;

    public static final Companion Companion = new Companion();
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(>=|<=|==|>|<)(.+)$");
    private static final String[] SDK_REQUIRED_MODULES = {"_sdk_version", "base_plugin", "plugin_settings"};
    private static final PluginFiles.ArchiveLimits SDK_ARCHIVE_LIMITS = new PluginFiles.ArchiveLimits(100000, 2147483648L, 536870912, 500.0d);
    private final ConcurrentHashMap<String, PyObject> pluginInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> settingsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> dependencyPaths = ConcurrentHashMap.newKeySet();
    // sg: per-plugin startup timings of the last loadPlugin (PluginsPerf; plugins queue only)
    private long perfImportMs;
    private long perfEnableMs;
    // sg: SDK modules looked up from Java on hot paths (settings, app events, metadata). python.getModule
    // is importlib.import_module over JNI on every call; the module objects live until the SDK is unloaded.
    private final ConcurrentHashMap<String, PyObject> sdkModules = new ConcurrentHashMap<>();
    // sg: set to the plugins-queue thread while loadPlugins imports the installed plugins; import caches are refreshed once per batch
    private volatile Thread loadingBatch;

    public interface PyMethodCaller<T> {
        PyObject call(PyObject instance, T value);
    }

    @Override
    public boolean canOpenInExternalApp() {
        return true;
    }

    public static final class Companion {
        private Companion() {
        }

        public final String getSDK_VERSION() {
            return PythonPluginsEngine.SDK_VERSION;
        }

        public final void setSDK_VERSION(String str) {
            PythonPluginsEngine.SDK_VERSION = str;
        }

        public final boolean getSDK_BETA() {
            return PythonPluginsEngine.SDK_BETA;
        }

        public final void setSDK_BETA(boolean z) {
            PythonPluginsEngine.SDK_BETA = z;
        }

        private final boolean sdkModuleExists(File sdkDir, String moduleName) {
            if (new File(sdkDir, moduleName + ".so").exists()) {
                return true;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(moduleName);
            sb.append(".pyc");
            return new File(sdkDir, sb.toString()).exists();
        }

        public final void deleteFileIfExists(File file) {
            if (file == null || !file.exists()) {
                return;
            }
            try {
                PluginFiles.deleteRecursively(file);
            } catch (Throwable th) {
                FileLog.e(th);
            }
        }

        public final boolean isSdkDirValid(File sdkDir) {
            if (sdkDir == null || !sdkDir.isDirectory()) {
                return false;
            }
            for (String str : PythonPluginsEngine.SDK_REQUIRED_MODULES) {
                if (!sdkModuleExists(sdkDir, str)) {
                    return false;
                }
            }
            return true;
        }

        public final String canonicalPathOrNull(String path) {
            if (path == null || path.length() == 0) {
                return null;
            }
            try {
                return new File(path).getCanonicalPath();
            } catch (Throwable th) {
                return null;
            }
        }

        public final Set<String> topLevelModuleNames(File dir) {
            File[] fileArrListFiles;
            if (dir == null || (fileArrListFiles = dir.listFiles()) == null) {
                return SetsKt.emptySet();
            }
            HashSet<String> hashSet = new HashSet<>();
            for (File file : fileArrListFiles) {
                String name = file.getName();
                if (file.isDirectory()) {
                    if (!Intrinsics.areEqual(name, "__pycache__") && !StringsKt.endsWith(name, ".dist-info", false) && !StringsKt.endsWith(name, ".data", false)) {
                        hashSet.add(name);
                    }
                } else if (StringsKt.endsWith(name, ".py", false) || StringsKt.endsWith(name, ".pyc", false) || StringsKt.endsWith(name, ".so", false)) {
                    hashSet.add(StringsKt.substringBefore(name, '.', name));
                }
            }
            return hashSet;
        }
    }

    public final ConcurrentHashMap<String, PyObject> getPluginInstances() {
        return this.pluginInstances;
    }

    public final PyObject getDebuggerListener() {
        return this.debuggerListener;
    }

    public final void setDebuggerListener(PyObject pyObject) {
        this.debuggerListener = pyObject;
    }

    public final PyObject getBasePluginClass() {
        return this.basePluginClass;
    }

    public final void setBasePluginClass(PyObject pyObject) {
        this.basePluginClass = pyObject;
    }

    private final PluginsController getPluginsController() {
        return PluginsController.Companion.getInstance();
    }

    private final synchronized Python getPython() {
        if (this.python == null) {
            initPython();
            if (this.python == null) {
                FileLog.e("Python initialization failed, unable to proceed."); android.util.Log.e("sgPlugins", "Python initialization failed, unable to proceed."); // sg: logcat
                return null;
            }
        }
        return this.python;
    }

    private final void initPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
            }
            this.python = Python.getInstance();
        } catch (Exception e) {
            FileLog.e("Failed to initialize Python", e); android.util.Log.e("sgPlugins", "Failed to initialize Python", e); // sg: logcat
        }
    }

    @Override
    public boolean isPlugin(File file, MessageObject messageObject) {
        return file != null && StringsKt.endsWith(file.getName().toLowerCase(Locale.ROOT), PluginsConstants.PLUGINS_EXT, false);
    }

    @Override
    public boolean isEngineAvailable() {
        return (this.python == null || !sdkInitialized || this.basePluginClass == null) ? false : true;
    }

    private final synchronized PyObject requireBasePluginClass() throws Exception {
        {
            if (ExteraConfig.getPluginsSafeMode()) {
                throw new Exception(SAFE_MODE_ENABLE_ERROR);
            }
            if (!sdkInitialized) {
                throw new Exception("Python plugin SDK is not initialized");
            }
            PyObject pyObject = this.basePluginClass;
            if (pyObject != null) {
                return pyObject;
            }
            Python python = this.python;
            if (python == null) {
                throw new Exception("Python interpreter is not initialized");
            }
            try {
                PyObject pyObject2 = (PyObject) python.getModule("base_plugin").get((Object) "BasePlugin");
                if (pyObject2 != null) {
                    this.basePluginClass = pyObject2;
                    return pyObject2;
                }
                throw new Exception("BasePlugin class is missing from the Python SDK");
            } catch (Throwable th) {
                throw new Exception("Failed to initialize Python plugin runtime", th);
            }
        }
    }

    /**
     * exteraStatic: the plugin SDK is our own open, pure-Python implementation of the public
     * exteraGram plugin API (TMessagesProj/src/main/python_sdk). Chaquopy packs it with the app
     * sources, so there is no archive to extract, verify or update: importing the bootstrap module
     * is enough.
     */
    private final boolean initSdk() {
        String str;
        Boolean bool;
        sdkInitialized = false;
        Python python = this.python;
        if (python == null) {
            return false;
        }
        try {
            PyObject module = python.getModule("_sdk_version");
            PyObject started = module.callAttr("__start__");
            sdkInitialized = started != null && started.toBoolean();
            PyObject version = module.get("__version__");
            if (version == null || (str = version.toJava(String.class)) == null) {
                str = SDK_VERSION;
            }
            SDK_VERSION = str;
            PyObject beta = module.get("__beta__");
            SDK_BETA = (beta == null || (bool = beta.toJava(Boolean.class)) == null) ? SDK_BETA : bool;
            HashSet<String> names = new HashSet<>();
            PyObject modules = module.get("TOP_LEVEL_MODULES");
            if (modules != null) {
                for (PyObject name : python.getBuiltins().callAttr("list", modules).asList()) {
                    names.add(name.toString());
                }
            }
            SDK_MODULE_NAMES = Collections.unmodifiableSet(names);
            if (this.basePluginClass == null && sdkInitialized && !ExteraConfig.getPluginsSafeMode()) {
                try {
                    requireBasePluginClass();
                } catch (Exception e5) {
                    FileLog.e("Failed to load BasePlugin class", e5);
                }
            }
            FileLog.d("Python plugin SDK " + SDK_VERSION + " initialized: " + sdkInitialized);
            return sdkInitialized;
        } catch (Throwable th7) {
            FileLog.e("Failed to initialize Python SDK bootstrap", th7); android.util.Log.e("sgPlugins", "Failed to initialize Python SDK bootstrap", th7); // sg: logcat
            return false;
        }
    }

    private static String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private final void stopAndUnloadSdk() {
        Python python = this.python;
        if (python == null) {
            return;
        }
        this.basePluginClass = null;
        this.sdkModules.clear();
        PyObject module = python.getModule("sys");
        PyObject sysModules = (PyObject) module.get((Object) "modules");
        if (sysModules != null) {
            try {
                PyObject pyObjectCallAttr = sysModules.callAttr("get", "_sdk_version");
                if (pyObjectCallAttr != null) {
                    pyObjectCallAttr.callAttr("__stop__", new Object[0]);
                }
            } catch (Throwable th) {
                FileLog.e("Failed to stop Python SDK bootstrap", th);
            }
        }
        sdkInitialized = false;
        if (sysModules == null) {
            return;
        }
        // drop SDK modules (and their submodules) so a restart re-imports a clean SDK state
        try {
            for (PyObject key : python.getBuiltins().callAttr("list", sysModules).asList()) {
                String name = key.toString();
                String top = StringsKt.substringBefore(name, '.', name);
                if (SDK_MODULE_NAMES.contains(top)) {
                    sysModules.callAttr("pop", name, null);
                }
            }
        } catch (Throwable th) {
            FileLog.e("Failed to unload Python SDK modules", th);
        }
    }

    /** Called by the Python SDK when a plugin changed its settings directly (BasePlugin.set_setting). */
    public static void invalidateSettingsCache(String pluginId) {
        if (pluginId == null) {
            return;
        }
        for (PluginsController.PluginsEngine engine : PluginsController.Companion.getEngines().values()) {
            if (engine instanceof PythonPluginsEngine) {
                ((PythonPluginsEngine) engine).settingsCache.remove(pluginId);
            }
        }
    }

    @Override
    public void init(Runnable callback) {
        long perfStart = PluginsPerf.start();
        if (getPython() == null) {
            callback.run();
            return;
        }
        PluginsPerf.log("python start", perfStart);
        try {
            perfStart = PluginsPerf.start();
            if (!initSdk()) {
                callback.run();
                return;
            }
            PluginsPerf.log("sdk init (" + SDK_VERSION + ")", perfStart);
            Python python = this.python;
            if (python == null) {
                callback.run();
                return;
            }
            perfStart = PluginsPerf.start();
            if (!ExteraConfig.getPluginsSafeMode()) {
                try {
                    String[] strArr = (String[]) python.getModule("plugin_settings").callAttr("init", getPluginsController().getPluginsDir().getAbsolutePath(), getPluginsController().getPreferences().getAll()).toJava(String[].class);
                    if (!(strArr.length == 0)) {
                        SharedPreferences.Editor editorEdit = getPluginsController().getPreferences().edit();
                        for (String str : strArr) {
                            editorEdit.remove(str);
                        }
                        editorEdit.apply();
                        FileLog.d("Migrated " + strArr.length + " plugin settings from SharedPreferences to JSON.");
                    }
                } catch (PyException e) {
                    FileLog.e("Failed to initialize plugin_settings module", e);
                }
            }
            PluginsPerf.log("settings init", perfStart);
            perfStart = PluginsPerf.start();
            if (!ExteraConfig.getPluginsSafeMode()) {
                // compiled plugin code: Cython/C extension suffixes for this ABI, plugins + plugins/libs on sys.path,
                // writable app-private TMPDIR for ctypes-loaded libraries
                try {
                    python.getModule("_sg_native").callAttr("install", getPluginsController().getPluginsDir().getAbsolutePath(), ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath());
                } catch (Throwable e) {
                    FileLog.e("Failed to set up native plugin code support", e);
                }
            }
            PluginsPerf.log("native init", perfStart);
            PipController.INSTANCE.cleanup();
            loadPlugins(callback);
            checkDevServer();
        } catch (Throwable th) {
            FileLog.e("Failed to initialize Python plugins engine", th); android.util.Log.e("sgPlugins", "Failed to initialize Python plugins engine", th); // sg: logcat
            callback.run();
        }
    }

    @Override
    public void checkDevServer() {
        if (ExteraConfig.getPluginsSafeMode()) {
            return;
        }
        if (ExteraConfig.getPluginsDevMode()) {
            runDevServer();
        } else {
            stopDevServer();
        }
    }

    private final void runDevServer() {
        Python python;
        if (ExteraConfig.getPluginsSafeMode() || (python = getPython()) == null) {
            return;
        }
        if (this.devServerClass != null) {
            stopDevServer();
        }
        try {
            PyObject pyObject = (PyObject) python.getModule(PluginsConstants.DevServer.MODULE).get(PluginsConstants.DevServer.CLASS);
            this.devServerClass = pyObject;
            if (pyObject == null) {
                return;
            }
            if (pyObject != null) {
                pyObject.callAttrThrows(PluginsConstants.DevServer.START_SERVER, new Object[0]);
            }
            FileLog.d("Dev server started successfully.");
        } catch (Throwable th) {
            FileLog.e("Failed to initialize dev server", th);
            this.devServerClass = null;
        }
    }

    private final void stopDevServer() {
        PyObject pyObject = this.devServerClass;
        if (pyObject == null) {
            return;
        }
        try {
            pyObject.callAttrThrows(PluginsConstants.DevServer.STOP_SERVER, new Object[0]);
            FileLog.d("Dev server stopped successfully.");
            this.devServerClass = null;
        } catch (Throwable th) {
            try {
                FileLog.e("Failed to stop dev server", th);
            } finally {
                this.devServerClass = null;
            }
        }
    }

    @Override
    public void shutdown(Runnable callback) {
        if (getPython() == null) {
            callback.run();
            return;
        }
        try {
            stopDevServer();
            Iterator it = new ArrayList(this.pluginInstances.keySet()).iterator();
            while (it.hasNext()) {
                unloadPlugin((String) it.next());
            }
            PyObject pyObject = this.debuggerListener;
            if (pyObject != null) {
                pyObject.close();
            }
            this.debuggerListener = null;
            this.pluginInstances.clear();
            synchronized (this) {
                removePluginPathsFromSysPath();
                stopAndUnloadSdk();
                this.python = null;
                sdkInitialized = false;
            }
            FileLog.d("Python plugin engine shut down.");
        } catch (Exception e) {
            FileLog.e(e);
        }
        callback.run();
    }

    public final void loadPlugins(final Runnable callback) {
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PythonPluginsEngine.$r8$lambda$ZHZbkOYG7xmxl2kV0ImdQjtKwC4(PythonPluginsEngine.this, callback);
            }
        });
    }

    public static void $r8$lambda$ZHZbkOYG7xmxl2kV0ImdQjtKwC4(PythonPluginsEngine pythonPluginsEngine, Runnable runnable) {
        Python python = pythonPluginsEngine.getPython();
        if (python == null) {
            if (runnable != null) {
                AndroidUtilities.runOnUIThread(runnable);
                return;
            }
            return;
        }
        final long batchStart = PluginsPerf.start();
        try {
            PyObject pyObject = (PyObject) python.getModule("sys").get("path");
            String absolutePath = pythonPluginsEngine.getPluginsController().getPluginsDir().getAbsolutePath();
            if (!ExteraConfig.getPluginsSafeMode() && pyObject != null && !pyObject.callAttr("__contains__", absolutePath).toBoolean()) {
                pyObject.callAttr("append", absolutePath);
            }
            File[] fileArrListFiles = pythonPluginsEngine.getPluginsController().getPluginsDir().listFiles(new FilenameFilter() {
                @Override
                public final boolean accept(File file, String str) {
                    return StringsKt.endsWith(str.toLowerCase(Locale.ROOT), ".py", false);
                }
            });
            if (fileArrListFiles == null) {
                pythonPluginsEngine.getPluginsController().notifyPluginsChanged();
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                    return;
                }
                return;
            }
            pythonPluginsEngine.invalidateImportCaches(pythonPluginsEngine.getPluginsController().getPluginsDir());
            pythonPluginsEngine.loadingBatch = Thread.currentThread();
            try {
            for (File file : fileArrListFiles) {
                String strSubstring = file.getName().substring(0, file.getName().length() - 3);
                PluginsController.PluginValidationResult validation = null;
                long pluginStart = PluginsPerf.start();
                try {
                    validation = pythonPluginsEngine.validatePluginFromFile(file.getAbsolutePath());
                    if (validation.getError() != null) {
                        throw new Exception(validation.getError());
                    }
                    long metadataMs = PluginsPerf.since(pluginStart);
                    pythonPluginsEngine.perfImportMs = 0L;
                    pythonPluginsEngine.perfEnableMs = 0L;
                    pythonPluginsEngine.loadPlugin(strSubstring, file.getAbsolutePath(), validation.getPlugin());
                    if (pluginStart != 0L) {
                        PluginsPerf.write("plugin " + strSubstring + ": metadata " + metadataMs + " ms, import " + pythonPluginsEngine.perfImportMs + " ms, enable " + pythonPluginsEngine.perfEnableMs + " ms, total " + PluginsPerf.since(pluginStart) + " ms");
                    }
                } catch (Throwable th) {
                    FileLog.e("Failed to load plugin " + file.getName() + ". Reason: " + th.getMessage(), th); android.util.Log.e("sgPlugins", "Failed to load plugin " + file.getName() + ". Reason: " + th.getMessage(), th); // sg: logcat
                    Plugin plugin2 = validation != null ? validation.getPlugin() : null;
                    Plugin plugin;
                    if (plugin2 != null) {
                        plugin = new Plugin(strSubstring, plugin2.getName());
                        plugin.setAuthor(plugin2.getAuthor());
                        plugin.setDescription(plugin2.getDescription());
                        plugin.setIcon(plugin2.getIcon());
                        plugin.setVersion(plugin2.getVersion());
                        plugin.setAppVersion(plugin2.getAppVersion());
                        plugin.setSdkVersion(plugin2.getSdkVersion());
                        plugin.setRequirements(plugin2.getRequirements());
                        plugin.setEngine(plugin2.getEngine());
                    } else {
                        plugin = new Plugin(strSubstring, strSubstring);
                        plugin.setAuthor(LocaleController.getString(R.string.PluginNoAuthor));
                        plugin.setVersion("1.0");
                        plugin.setEngine(PluginsConstants.PYTHON);
                    }
                    plugin.setError(th);
                    plugin.setEnabled(false);
                    pythonPluginsEngine.getPluginsController().getPlugins().put(strSubstring, plugin);
                }
            }
            } finally {
                pythonPluginsEngine.loadingBatch = null;
            }
            pythonPluginsEngine.getPluginsController().notifyPluginsChanged();
            int enabled = 0;
            for (Plugin p : pythonPluginsEngine.getPluginsController().getPlugins().values()) {
                if (p.isEnabled()) {
                    enabled++;
                }
            }
            PluginsPerf.log("plugins loaded: " + pythonPluginsEngine.getPluginsController().getPlugins().size() + " (enabled " + enabled + ")", batchStart);
            FileLog.d("Python plugin system initialized. Total: " + pythonPluginsEngine.getPluginsController().getPlugins().size() + ", Enabled: " + enabled); android.util.Log.i("sgPlugins", "Python plugin system initialized. Total: " + pythonPluginsEngine.getPluginsController().getPlugins().size() + ", Enabled: " + enabled); // sg: logcat
            if (runnable != null) {
                AndroidUtilities.runOnUIThread(runnable);
            }
        } catch (PyException e) {
            FileLog.e("Failed to setup Python environment for plugins", e); android.util.Log.e("sgPlugins", "Failed to setup Python environment for plugins", e); // sg: logcat
            if (runnable != null) {
                AndroidUtilities.runOnUIThread(runnable);
            }
        }
    }

    public final void loadPlugin(String pluginId, String filePath) throws Exception {
        loadPlugin(pluginId, filePath, null, null);
    }

    public final void loadPlugin(String pluginId, String filePath, Plugin metadata) throws Exception {
        loadPlugin(pluginId, filePath, metadata, null);
    }

    public final void loadPlugin(String pluginId, String filePath, Plugin metadata, PipController.InstallerDelegate delegate) throws Exception {
        boolean z = getPluginsController().getPreferences().getBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new Exception("Plugin file not found: " + filePath);
        }
        if (metadata == null) {
            PluginsController.PluginValidationResult pluginValidationResultValidatePluginFromFile = validatePluginFromFile(filePath);
            if (pluginValidationResultValidatePluginFromFile.getError() != null) {
                throw new Exception(pluginValidationResultValidatePluginFromFile.getError());
            }
            metadata = pluginValidationResultValidatePluginFromFile.getPlugin();
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Required value was null.");
        }
        if (!Intrinsics.areEqual(pluginId, metadata.getId())) {
            throw new Exception("Plugin ID mismatch. Expected: " + pluginId + ", but found: " + metadata.getId() + " in metadata.");
        }
        if (this.pluginInstances.containsKey(pluginId)) {
            unloadPlugin(pluginId);
        }
        metadata.setEnabled(false);
        metadata.setError(null);
        getPluginsController().getPlugins().put(pluginId, metadata);
        if (ExteraConfig.getPluginsSafeMode()) {
            return;
        }
        if (!z) {
            if (delegate != null) {
                installPluginDependencies(pluginId, metadata, delegate);
                return;
            }
            return;
        }
        try {
            long perfStart = PluginsPerf.start();
            createPluginInstance(pluginId, filePath, metadata, delegate);
            this.perfImportMs = PluginsPerf.since(perfStart);
            perfStart = PluginsPerf.start();
            setPluginEnabled(pluginId, true, null);
            this.perfEnableMs = PluginsPerf.since(perfStart);
        } catch (Throwable th) {
            SharedPreferences.Editor editorEdit = getPluginsController().getPreferences().edit();
            editorEdit.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
            editorEdit.apply();
            unloadPlugin(pluginId);
            throw th;
        }
    }

    private final void installPluginDependencies(String pluginId, Plugin pluginMetadata, PipController.InstallerDelegate delegate) throws Exception {
        PyObject module;
        List<String> requirements = pluginMetadata.getRequirements();
        List<String> list = requirements;
        if (list == null || list.isEmpty()) {
            return;
        }
        List<String> listInstallDependencies = PipController.INSTANCE.installDependencies(requirements, pluginId, delegate);
        List<String> list2 = listInstallDependencies;
        LinkedHashMap<String, Set<String>> linkedHashMap = new LinkedHashMap<>();
        for (String obj : list2) {
            linkedHashMap.put(obj, Companion.topLevelModuleNames(new File(obj)));
        }
        Set<String> set = SDK_MODULE_NAMES;
        for (Map.Entry<String, Set<String>> entry : linkedHashMap.entrySet()) {
            String str = entry.getKey();
            Set<String> setIntersect = new LinkedHashSet<>(entry.getValue());
            setIntersect.retainAll(set);
            if (!setIntersect.isEmpty()) {
                throw new Exception("Dependency " + new File(str).getName() + " of " + pluginId + " provides " + CollectionsKt.joinToString(setIntersect, ", ", "", "", -1, "...", null) + ", which would replace the plugins SDK module of the same name.");
            }
        }
        for (Map.Entry<String, Set<String>> entry2 : linkedHashMap.entrySet()) {
            disableShadowedPlugins(pluginId, new File(entry2.getKey()).getName(), entry2.getValue());
        }
        Python python = getPython();
        PyObject pyObject = (python == null || (module = python.getModule("sys")) == null) ? null : (PyObject) module.get("path");
        if (pyObject == null) {
            return;
        }
        for (int size = listInstallDependencies.size() - 1; size >= 0; size--) {
            String str2 = listInstallDependencies.get(size);
            removeFromSysPath(pyObject, str2);
            pyObject.callAttr("insert", 0, str2);
            this.dependencyPaths.add(str2);
        }
    }

    private final void disableShadowedPlugins(String pluginId, String dependencyName, Set<String> providedModules) {
        for (String str : providedModules) {
            if (!Intrinsics.areEqual(str, pluginId)) {
                if (new File(getPluginsController().getPluginsDir(), str + ".py").exists()) {
                    FileLog.w("Disabling plugin '" + str + "': dependency " + dependencyName + " of " + pluginId + " now owns that module name");
                    unloadPlugin(str);
                    SharedPreferences.Editor editorEdit = getPluginsController().getPreferences().edit();
                    StringBuilder sb = new StringBuilder(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX);
                    sb.append(str);
                    editorEdit.putBoolean(sb.toString(), false);
                    editorEdit.apply();
                    Plugin plugin = getPluginsController().getPlugins().get(str);
                    if (plugin != null) {
                        plugin.setError(new Exception("Plugin id '" + str + "' is now provided by " + dependencyName + ", a dependency of '" + pluginId + "'. Rename or delete one of them."));
                    }
                }
            }
        }
    }

    private final void removePluginDependencies(String pluginId) {
        PipController.INSTANCE.uninstallDependencies(pluginId);
        pruneDependencyPaths();
    }

    private final void pruneDependencyPaths() {
        PyObject module;
        if (this.dependencyPaths.isEmpty()) {
            return;
        }
        Set<String> setActiveLibraryPaths = PipController.INSTANCE.activeLibraryPaths();
        ConcurrentHashMap.KeySetView<String, Boolean> keySetView = this.dependencyPaths;
        ArrayList<String> arrayList = new ArrayList<>();
        for (String obj : keySetView) {
            if (!setActiveLibraryPaths.contains(obj)) {
                arrayList.add(obj);
            }
        }
        if (arrayList.isEmpty()) {
            return;
        }
        try {
            Python python = this.python;
            PyObject module2 = python != null ? python.getModule("sys") : null;
            PyObject pyObject = module2 != null ? (PyObject) module2.get((Object) "path") : null;
            PyObject pyObject2 = module2 != null ? (PyObject) module2.get((Object) "modules") : null;
            PyObject pyObject3 = module2 != null ? (PyObject) module2.get((Object) "path_importer_cache") : null;
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                Object obj2 = arrayList.get(i);
                i++;
                String str = (String) obj2;
                if (pyObject != null) {
                    removeFromSysPath(pyObject, str);
                }
                if (pyObject3 != null) {
                    pyObject3.callAttr("pop", str, null);
                }
                if (pyObject2 != null) {
                    removeModulesUnderPath(pyObject2, str);
                }
                this.dependencyPaths.remove(str);
            }
            Python python2 = this.python;
            if (python2 == null || (module = python2.getModule("importlib")) == null) {
                return;
            }
            module.callAttr("invalidate_caches", new Object[0]);
        } catch (PyException e) {
            FileLog.e("Failed to drop unused dependency paths from the Python environment", e);
        }
    }

    private final void removeModulesUnderPath(PyObject sysModules, String path) {
        Python python = this.python;
        if (python == null) {
            return;
        }
        String str2 = path + File.separator;
        String strCanonicalPathOrNull2 = Companion.canonicalPathOrNull(path);
        String str3 = strCanonicalPathOrNull2 != null ? strCanonicalPathOrNull2 + File.separator : str2;
        ArrayList<String> arrayList = new ArrayList<>();
        for (PyObject pyObject2 : python.getBuiltins().callAttr("list", sysModules).asList()) {
            String name = pyObject2 != null ? pyObject2.toString() : null;
            PyObject module = name != null ? sysModules.callAttr("get", name) : null;
            if (module == null) {
                continue;
            }
            PyObject pyObject3 = module.get("__file__");
            String location = pyObject3 != null ? pyObject3.toString() : null;
            if (location == null) {
                // namespace packages have no __file__, use the first __path__ entry instead
                try {
                    PyObject pathAttr = module.get("__path__");
                    List<PyObject> paths = pathAttr != null ? pathAttr.asList() : null;
                    location = paths != null && !paths.isEmpty() && paths.get(0) != null ? paths.get(0).toString() : null;
                } catch (Throwable ignore) {
                    location = null;
                }
                if (location == null) {
                    continue;
                }
            }
            String canonical;
            if (location.startsWith(str2) || ((canonical = Companion.canonicalPathOrNull(location)) != null && canonical.startsWith(str3))) {
                arrayList.add(name);
            }
        }
        for (String name : arrayList) {
            sysModules.callAttr("pop", name, null);
        }
        if (arrayList.isEmpty()) {
            return;
        }
        FileLog.d("Unloaded " + arrayList.size() + " module(s) belonging to removed dependency " + new File(path).getName());
    }

    private final void removePluginPathsFromSysPath() {
        PyObject module;
        PyObject pyObject;
        Python python = this.python;
        if (python == null || (module = python.getModule("sys")) == null || (pyObject = (PyObject) module.get((Object) "path")) == null) {
            return;
        }
        ArrayList<String> arrayList = new ArrayList<>(this.dependencyPaths);
        arrayList.add(getPluginsController().getPluginsDir().getAbsolutePath());
        for (String path : arrayList) {
            removeFromSysPath(pyObject, path);
        }
        this.dependencyPaths.clear();
    }

    private final void removeFromSysPath(PyObject sysPath, String path) {
        while (sysPath.callAttr("__contains__", path).toBoolean()) {
            sysPath.callAttr("remove", path);
        }
    }

    private final void createPluginInstance(String pluginId, String filePath, Plugin pluginMetadata, PipController.InstallerDelegate delegate) throws Exception {
        PyObject pyObjectRequireBasePluginClass = requireBasePluginClass();
        installPluginDependencies(pluginId, pluginMetadata, delegate);
        List<String> requirements = pluginMetadata.getRequirements();
        if (this.loadingBatch == Thread.currentThread() && (requirements == null || requirements.isEmpty()) && !isModuleImported(pluginId)) {
            // sg: startup batch: caches were refreshed once before the batch, the module was never
            // imported and no dependency was installed for it, so there is nothing to evict or re-scan
        } else {
            refreshImportCaches(pluginId, new File(filePath).getParentFile());
        }
        getPluginsController().getWatchdog().onPluginExecutionStarted(pluginId);
        PyObject module;
        try {
            Python python = getPython();
            if (python == null || (module = python.getModule(pluginId)) == null) {
                throw new Exception("Failed to import plugin module: " + pluginId);
            }
        } catch (PyException e) {
            throw new Exception("Failed to import plugin module: " + e.getMessage(), e);
        } finally {
            getPluginsController().getWatchdog().onPluginExecutionFinished(pluginId);
        }
        PyObject pyObject = module.get("__file__");
        String string = pyObject != null ? pyObject.toString() : null;
        if (!Intrinsics.areEqual(Companion.canonicalPathOrNull(string), Companion.canonicalPathOrNull(filePath))) {
            if (string == null) {
                string = "built into the runtime";
            }
            throw new Exception("Plugin id '" + pluginId + "' is already taken by the Python module " + string + ". Change '__id__' to a name that no other module uses.");
        }
        try {
            PyObject pyObjectCallAttr = pyObjectRequireBasePluginClass.callAttr("_findPluginClass", module);
            if (pyObjectCallAttr == null) {
                throw new Exception("Could not find a class inheriting from BasePlugin in " + pluginId + ".py. Make sure your main plugin class extends BasePlugin.");
            }
            PyObject pyObjectCall = pyObjectCallAttr.call();
            pyObjectCall.put("id", pluginMetadata.getId());
            pyObjectCall.put("name", pluginMetadata.getName());
            pyObjectCall.put("description", pluginMetadata.getDescription());
            pyObjectCall.put("author", pluginMetadata.getAuthor());
            pyObjectCall.put("version", pluginMetadata.getVersion());
            pyObjectCall.put("icon", pluginMetadata.getIcon());
            pyObjectCall.put("app_version", pluginMetadata.getAppVersion());
            pyObjectCall.put("sdk_version", pluginMetadata.getSdkVersion());
            pyObjectCall.put("requirements", pluginMetadata.getRequirements());
            pyObjectCall.put("enabled", Boolean.FALSE);
            pyObjectCall.put("initialized", Boolean.FALSE);
            pyObjectCall.put("error_message", (Object) null);
            this.pluginInstances.put(pluginId, pyObjectCall);
        } catch (PyException e) {
            throw new Exception("Failed to import plugin module: " + e.getMessage(), e);
        }
    }

    public final void unloadPlugin(String pluginId) {
        this.settingsCache.remove(pluginId);
        PyObject pyObjectRemove = this.pluginInstances.remove(pluginId);
        Plugin plugin = getPluginsController().getPlugins().get(pluginId);
        if (plugin != null) {
            plugin.setEnabled(false);
        }
        try {
            if (pyObjectRemove != null) {
                try {
                    if (PyObjectUtils.getBoolean(pyObjectRemove, "initialized", false)) {
                        getPluginsController().getWatchdog().onPluginExecutionStarted(pluginId);
                        try {
                            pyObjectRemove.callAttr(PluginsConstants.ON_PLUGIN_UNLOAD);
                        } catch (Throwable th) {
                            FileLog.e("Error during on_plugin_unload for " + pluginId, th);
                        } finally {
                            getPluginsController().getWatchdog().onPluginExecutionFinished(pluginId);
                        }
                    }
                    // sg: SDK 1.4.5 cleanup (file/intent handlers, logger handlers and levels)
                    try {
                        if (pyObjectRemove.containsKey("_cleanup")) {
                            pyObjectRemove.callAttr("_cleanup");
                        }
                    } catch (Throwable th) {
                        FileLog.e("Error during SDK cleanup for " + pluginId, th);
                    }
                } catch (PyException e) {
                    FileLog.e("Failed to unload plugin " + pluginId, e);
                    return; // cleanup + close happen in finally

                }
                pyObjectRemove.put("initialized", Boolean.FALSE);
                pyObjectRemove.put("enabled", Boolean.FALSE);
            }
            File cacheDir = new File(getPluginsController().getPluginsDir(), "__pycache__");
            File[] cached = cacheDir.isDirectory() ? cacheDir.listFiles() : null;
            if (cached != null) {
                for (File file : cached) {
                    if (file.getName().startsWith(pluginId + ".cpython-")) {
                        Companion.deleteFileIfExists(file);
                        break;
                    }
                }
            }
            refreshImportCaches(pluginId, getPluginsController().getPluginsDir());
        } finally {
            getPluginsController().cleanupPlugin(pluginId);
            closePluginInstance(pluginId, pyObjectRemove);
        }
    }

    private static void closePluginInstance(String pluginId, PyObject instance) {
        if (instance == null) {
            return;
        }
        try {
            instance.close();
        } catch (Throwable th) {
            FileLog.e("Failed to close plugin instance " + pluginId, th);
        }
    }

    /** Cached {@code python.getModule(name)} for SDK modules (dropped in stopAndUnloadSdk). */
    private PyObject sdkModule(Python python, String name) {
        PyObject module = this.sdkModules.get(name);
        if (module == null) {
            module = python.getModule(name);
            if (module != null) {
                this.sdkModules.put(name, module);
            }
        }
        return module;
    }

    /** One import-cache refresh for the whole startup batch (see createPluginInstance). */
    private void invalidateImportCaches(File moduleDir) {
        Python python = this.python;
        if (python == null) {
            return;
        }
        try {
            PyObject pathImporterCache = (PyObject) python.getModule("sys").get((Object) "path_importer_cache");
            if (moduleDir != null && pathImporterCache != null) {
                pathImporterCache.callAttr("pop", moduleDir.getAbsolutePath(), null);
            }
            python.getModule("importlib").callAttr("invalidate_caches", new Object[0]);
        } catch (PyException e) {
            FileLog.e("Failed to invalidate import caches", e);
        }
    }

    private boolean isModuleImported(String name) {
        Python python = this.python;
        if (python == null) {
            return false;
        }
        try {
            PyObject sysModules = (PyObject) python.getModule("sys").get((Object) "modules");
            return sysModules == null || sysModules.callAttr("__contains__", name).toBoolean();
        } catch (Throwable t) {
            return true;
        }
    }

    private final void refreshImportCaches(String pluginId, File moduleDir) {
        Python python = this.python;
        if (python == null) {
            return;
        }
        try {
            PyObject module = python.getModule("sys");
            PyObject pyObject = (PyObject) module.get((Object) "modules");
            if (pyObject != null) {
                evictPluginModule(pyObject, pluginId, moduleDir);
            }
            PyObject pyObject2 = (PyObject) module.get((Object) "path_importer_cache");
            if (moduleDir != null && pyObject2 != null) {
                pyObject2.callAttr("pop", moduleDir.getAbsolutePath(), null);
            }
            python.getModule("importlib").callAttr("invalidate_caches", new Object[0]);
        } catch (PyException e) {
            FileLog.e("Failed to refresh import caches for " + pluginId, e);
        }
    }

    private final void evictPluginModule(PyObject sysModules, String pluginId, File moduleDir) {
        String strCanonicalPathOrNull;
        PyObject pyObjectCallAttr = sysModules.callAttr("get", pluginId);
        if (pyObjectCallAttr == null) {
            return;
        }
        PyObject pyObject = (PyObject) pyObjectCallAttr.get("__file__");
        String string = pyObject != null ? pyObject.toString() : null;
        if (moduleDir != null) {
            strCanonicalPathOrNull = Companion.canonicalPathOrNull(new File(moduleDir, pluginId + ".py").getAbsolutePath());
        } else {
            strCanonicalPathOrNull = null;
        }
        if (strCanonicalPathOrNull != null && Intrinsics.areEqual(Companion.canonicalPathOrNull(string), strCanonicalPathOrNull)) {
            sysModules.callAttr("pop", pluginId, null);
            return;
        }
        if (string == null) {
            string = "the Python runtime";
        }
        FileLog.w("Keeping module '" + pluginId + "' loaded: it belongs to " + string + ", not to a plugin");
    }

    @Override
    public void setPluginEnabled(String pluginId, boolean enabled, final Utilities.Callback<String> callback) {
        Plugin plugin;
        try {
            Plugin plugin2 = getPluginsController().getPlugins().get(pluginId);
            if (plugin2 == null) {
                throw new Exception("Plugin not found: " + pluginId);
            }
            if (enabled && ExteraConfig.getPluginsSafeMode()) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public final void run() {
                            callback.run(PythonPluginsEngine.SAFE_MODE_ENABLE_ERROR);
                        }
                    });
                    return;
                }
                return;
            }
            PyObject pyObject = this.pluginInstances.get(pluginId);
            if (enabled && pyObject == null) {
                createPluginInstance(pluginId, getPluginPath(pluginId), plugin2, null);
                pyObject = this.pluginInstances.get(pluginId);
                if (pyObject == null) {
                    throw new Exception("Failed to create plugin instance: " + pluginId);
                }
            }
            if ((pyObject != null && PyObjectUtils.getBoolean(pyObject, "initialized", false)) == enabled && !plugin2.hasError()) {
                if (callback != null) {
                    callback.run(null);
                    return;
                }
                return;
            }
            if (!enabled) {
                SharedPreferences.Editor editorEdit = getPluginsController().getPreferences().edit();
                editorEdit.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
                editorEdit.apply();
                unloadPlugin(pluginId);
            } else {
                if (pyObject == null) {
                    throw new IllegalArgumentException("Required value was null.");
                }
                getPluginsController().cleanupPlugin(pluginId);
                getPluginsController().getWatchdog().onPluginExecutionStarted(pluginId);
                try {
                    pyObject.callAttr(PluginsConstants.ON_PLUGIN_LOAD, new Object[0]);
                    getPluginsController().getWatchdog().onPluginExecutionFinished(pluginId);
                    Boolean bool = Boolean.TRUE;
                    pyObject.put("initialized", (Object) bool);
                    pyObject.put("error_message", (Object) null);
                    plugin2.setError(null);
                    pyObject.put("enabled", (Object) bool);
                    plugin2.setEnabled(true);
                    SharedPreferences.Editor editorEdit2 = getPluginsController().getPreferences().edit();
                    editorEdit2.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, true);
                    editorEdit2.apply();
                    getPluginsController().loadPluginSettings(pluginId);
                } catch (Throwable th) {
                    getPluginsController().getWatchdog().onPluginExecutionFinished(pluginId);
                    throw th;
                }
            }
            getPluginsController().notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public final void run() {
                        callback.run(null);
                    }
                });
            }
        } catch (Throwable th2) {
            FileLog.e("Unexpected error setting enabled state for " + pluginId, th2);
            if (enabled && (plugin = getPluginsController().getPlugins().get(pluginId)) != null) {
                plugin.setError(th2);
            }
            SharedPreferences.Editor editorEdit3 = getPluginsController().getPreferences().edit();
            editorEdit3.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
            editorEdit3.apply();
            unloadPlugin(pluginId);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public final void run() {
                        callback.run(AppUtils.stackTraceToString(th2));
                    }
                });
            }
        }
    }

    @Override
    public void deletePlugin(String pluginId, final Utilities.Callback<String> callback) {
        unloadPlugin(pluginId);
        try {
            removePluginDependencies(pluginId);
        } catch (Exception e) {
            FileLog.e("Failed to uninstall dependencies for " + pluginId, e);
        }
        Companion.deleteFileIfExists(new File(getPluginsController().getPluginsDir(), pluginId + ".py"));
        PluginsController.Companion companion = PluginsController.Companion;
        if (companion.isPluginPinned(pluginId)) {
            companion.setPluginPinned(pluginId, false);
        }
        getPluginsController().clearPluginSettingsPreferences(pluginId, true);
        getPluginsController().getPlugins().remove(pluginId);
        getPluginsController().notifyPluginsChanged();
        if (callback != null) {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public final void run() {
                    callback.run(null);
                }
            });
        }
    }

    @Override
    public String getPluginPath(String id) {
        return getPluginsController().getPluginsDir().getAbsolutePath() + File.separator + id + ".py";
    }

    @Override
    public void openInExternalApp(String id) {
        BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
        if (safeLastFragment == null) {
            return;
        }
        File file = new File(getPluginPath(id));
        if (file.exists()) {
            PluginFileViewer.INSTANCE.open(safeLastFragment, file, id + PluginsConstants.PLUGINS_EXT);
        }
    }

    @Override
    public void sharePlugin(String id) {
        Activity parentActivity;
        BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
        if (safeLastFragment == null || (parentActivity = safeLastFragment.getParentActivity()) == null) {
            return;
        }
        String pluginPath = getPluginPath(id);
        File file = new File(ApplicationLoader.getFilesDirFixed(), "temp");
        File file2 = new File(file, id + PluginsConstants.PLUGINS_EXT);
        try {
            PluginFiles.mkdirs(file);
            PluginFiles.copy(new File(pluginPath), file2, true);
            Uri uriForFile = FileProvider.getUriForFile(parentActivity, ApplicationLoader.getApplicationId() + ".provider", file2);
            Intent intent = new Intent("android.intent.action.SEND");
            intent.setFlags(1);
            intent.putExtra("android.intent.extra.STREAM", uriForFile);
            intent.setType("application/x-plugin");
            safeLastFragment.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            file2.deleteOnExit();
        } catch (IOException e) {
            FileLog.e(e);
        } catch (IllegalArgumentException e2) {
            FileLog.e(e2);
        } catch (Exception e3) {
            FileLog.e(e3);
        }
    }

    public final void loadPluginFromFile(String filePath, Plugin pluginMetadata, Utilities.Callback<String> callback) {
        loadPluginFromFile(filePath, pluginMetadata, callback, null);
    }

    @Override
    public void loadPluginFromFile(final String filePath, final Plugin pluginMetadata, final Utilities.Callback<String> callback, final PipController.InstallerDelegate delegate) {
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PythonPluginsEngine.m1563$r8$lambda$NGT_LVDpZfx1FtJ6x4iJPoreg0(pluginMetadata, PythonPluginsEngine.this, filePath, delegate, callback);
            }
        });
    }

    public static void m1563$r8$lambda$NGT_LVDpZfx1FtJ6x4iJPoreg0(Plugin plugin, PythonPluginsEngine pythonPluginsEngine, String str, PipController.InstallerDelegate installerDelegate, final Utilities.Callback<String> callback) {
        PluginsController controller = pythonPluginsEngine.getPluginsController();
        String id = null;
        File file = null;
        File backup = null;
        boolean hadOriginal = false;
        boolean wasEnabled = false;
        try {
            Plugin metadata = plugin;
            if (metadata == null) {
                PluginsController.PluginValidationResult validation = pythonPluginsEngine.validatePluginFromFile(str);
                if (validation.getError() != null) {
                    throw new Exception(validation.getError());
                }
                metadata = validation.getPlugin();
                if (metadata == null) {
                    throw new IllegalArgumentException("Required value was null.");
                }
            }
            id = metadata.getId();
            wasEnabled = controller.getPreferences().getBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + id, false);
            file = new File(controller.getPluginsDir(), id + ".py");
            hadOriginal = file.exists();
            if (hadOriginal) {
                // keep the installed version as a backup until the new one is loaded
                pythonPluginsEngine.unloadPlugin(id);
                backup = new File(controller.getPluginsDir(), id + ".py.bak");
                Companion.deleteFileIfExists(backup);
                PluginFiles.move(file, backup, false);
                pythonPluginsEngine.removePluginDependencies(id);
            }
            if (!new File(str).exists()) {
                throw new Exception("Plugin file is no longer available. It may have been moved or deleted.");
            }
            PluginFiles.copy(new File(str), file, true);
            // StaticGram static analysis: only a file the user reviewed is installed (checked on the copy)
            final String analysisError = com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.checkInstall(file);
            if (analysisError != null) {
                throw new Exception(analysisError);
            }
            pythonPluginsEngine.loadPlugin(id, file.getAbsolutePath(), metadata, installerDelegate);
            Companion.deleteFileIfExists(backup);
            controller.notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            }
        } catch (final Throwable th) {
            FileLog.e("Unexpected error loading plugin from file: " + str, th); android.util.Log.e("sgPlugins", "Unexpected error loading plugin from file: " + str, th); // sg: logcat
            if (id != null) {
                boolean restored = false;
                Companion.deleteFileIfExists(file);
                if (hadOriginal && backup != null && backup.exists()) {
                    try {
                        PluginFiles.move(backup, file, true);
                        SharedPreferences.Editor editorEdit = controller.getPreferences().edit();
                        editorEdit.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + id, wasEnabled);
                        editorEdit.apply();
                        try {
                            pythonPluginsEngine.loadPlugin(id, file.getAbsolutePath());
                        } catch (Exception e) {
                            FileLog.e("Failed to reload original plugin after update failure for " + id, e);
                            Plugin original = controller.getPlugins().get(id);
                            if (original != null) {
                                original.setError(e);
                            }
                        }
                        restored = true;
                    } catch (Exception e2) {
                        FileLog.e("Failed to restore backup for plugin " + id, e2);
                    }
                }
                if (!restored) {
                    controller.cleanupPlugin(id);
                    try {
                        pythonPluginsEngine.removePluginDependencies(id);
                    } catch (Exception e3) {
                        FileLog.e(e3);
                    }
                    closePluginInstance(id, pythonPluginsEngine.pluginInstances.remove(id));
                    controller.clearPluginSettingsPreferences(id, true);
                    controller.getPlugins().remove(id);
                    Companion.deleteFileIfExists(file);
                }
            }
            controller.notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(AppUtils.stackTraceToString(th)));
            }
        }
    }

    private final String findModuleNameOwner(String pluginId) {
        if (SDK_MODULE_NAMES.contains(pluginId)) {
            return "the plugins SDK";
        }
        Iterator<String> it = PipController.INSTANCE.activeLibraryPaths().iterator();
        while (it.hasNext()) {
            File file = new File(it.next());
            if (Companion.topLevelModuleNames(file).contains(pluginId)) {
                return "the installed dependency " + file.getName();
            }
        }
        Python python = getPython();
        if (python == null) {
            return null;
        }
        try {
            PyObject pyObjectCallAttr = python.getModule("importlib.util").callAttr("find_spec", pluginId);
            if (pyObjectCallAttr == null) {
                return null;
            }
            PyObject pyObject = (PyObject) pyObjectCallAttr.get("origin");
            String string = pyObject != null ? pyObject.toString() : null;
            if (string != null) {
                Companion companion = Companion;
                if (Intrinsics.areEqual(companion.canonicalPathOrNull(string), companion.canonicalPathOrNull(new File(getPluginsController().getPluginsDir(), pluginId + ".py").getAbsolutePath()))) {
                    return null;
                }
                return string;
            }
            PyObject pyObject2 = (PyObject) pyObjectCallAttr.get("submodule_search_locations");
            if (pyObject2 != null) {
                String strCanonicalPathOrNull = Companion.canonicalPathOrNull(new File(getPluginsController().getPluginsDir(), pluginId).getAbsolutePath());
                for (PyObject location : python.getBuiltins().callAttr("list", pyObject2).asList()) {
                    String path = location.toString();
                    if (!Intrinsics.areEqual(Companion.canonicalPathOrNull(path), strCanonicalPathOrNull)) {
                        return path;
                    }
                }
                return null;
            }
            return "another Python module";
        } catch (PyException e) {
            FileLog.w("Treating plugin id '" + pluginId + "' as taken: " + e.getMessage());
            return "another Python module";
        } catch (Throwable th) {
            FileLog.e("Failed to check whether plugin id '" + pluginId + "' is free", th);
            return null;
        }
    }

    public final PluginsController.PluginValidationResult validatePluginFromFile(String filePath) {
        if (!new File(filePath).exists()) {
            return new PluginsController.PluginValidationResult(null, "Plugin file not found.");
        }
        try {
            Map<String, String> pluginMetadata = parsePluginMetadata(filePath);
            String str = pluginMetadata.get("id");
            String str2 = pluginMetadata.get("name");
            if (TextUtils.isEmpty(str) || TextUtils.isEmpty(str2)) {
                return new PluginsController.PluginValidationResult(null, "Plugin metadata must contain non-empty '__id__' and '__name__'.");
            }
            if (str == null) {
                throw new IllegalArgumentException("Required value was null.");
            }
            if (!str.matches("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
                return new PluginsController.PluginValidationResult(null, "Plugin '__id__' must be 2-32 characters long, start with a letter, and contain only latin letters, numbers, dashes and underscores.");
            }
            Plugin installedPlugin = getPluginsController().getPlugins().get(str);
            if (installedPlugin != null && installedPlugin.getEngine() != null && !PluginsConstants.PYTHON.equals(installedPlugin.getEngine())) {
                return new PluginsController.PluginValidationResult(null, "Plugin id '" + str + "' is already used by an installed " + installedPlugin.getEngine() + " plugin.");
            }
            String strFindModuleNameOwner = findModuleNameOwner(str);
            if (strFindModuleNameOwner != null) {
                return new PluginsController.PluginValidationResult(null, "Plugin '__id__' is already used as a Python module name by " + strFindModuleNameOwner + ". Choose a different '__id__'.");
            }
            String str3 = pluginMetadata.get("app_version");
            if (str3 != null) {
                Matcher matcher = VERSION_PATTERN.matcher(str3);
                if (!matcher.matches()) {
                    return new PluginsController.PluginValidationResult(null, "Invalid appVersion: ".concat(str3));
                }
                String strGroup = matcher.group(1);
                if (strGroup == null) {
                    return new PluginsController.PluginValidationResult(null, "Invalid appVersion: ".concat(str3));
                }
                String strGroup2 = matcher.group(2);
                if (strGroup2 == null) {
                    return new PluginsController.PluginValidationResult(null, "Invalid appVersion: ".concat(str3));
                }
                if (!AppUtils.compareVersions(strGroup, BuildVars.BUILD_VERSION_STRING, strGroup2.trim())) {
                    return new PluginsController.PluginValidationResult(null, "Plugin requires app version " + str3 + ", but current is " + BuildVars.BUILD_VERSION_STRING);
                }
            }
            String str4 = pluginMetadata.get("sdk_version");
            if (str4 != null) {
                Matcher matcher2 = VERSION_PATTERN.matcher(str4);
                if (!matcher2.matches()) {
                    return new PluginsController.PluginValidationResult(null, "Invalid sdkVersion: ".concat(str4));
                }
                String strGroup3 = matcher2.group(1);
                if (strGroup3 == null) {
                    return new PluginsController.PluginValidationResult(null, "Invalid sdkVersion: ".concat(str4));
                }
                String strGroup4 = matcher2.group(2);
                if (strGroup4 == null) {
                    return new PluginsController.PluginValidationResult(null, "Invalid sdkVersion: ".concat(str4));
                }
                if (!AppUtils.compareVersions(strGroup3, SDK_VERSION, strGroup4.trim())) {
                    return new PluginsController.PluginValidationResult(null, "Plugin requires sdk version " + str4 + ", but current is " + SDK_VERSION);
                }
            }
            if (str2 != null) {
                Plugin plugin = new Plugin(str, str2);
                plugin.setEngine(PluginsConstants.PYTHON);
                String string3 = pluginMetadata.get("author");
                if (string3 == null) {
                    string3 = LocaleController.getString(R.string.PluginNoAuthor);
                }
                plugin.setAuthor(string3);
                String string4 = pluginMetadata.get("description");
                if (string4 == null) {
                    string4 = LocaleController.getString(R.string.PluginNoDescription);
                }
                plugin.setDescription(string4);
                plugin.setIcon(pluginMetadata.get("icon"));
                String str5 = pluginMetadata.get("version");
                if (str5 == null) {
                    str5 = "1.0";
                }
                plugin.setVersion(str5);
                plugin.setAppVersion(str3);
                plugin.setSdkVersion(str4);
                String str6 = pluginMetadata.get("requirements");
                if (str6 != null && str6.length() != 0) {
                    ArrayList<String> requirements = new ArrayList<>();
                    for (String requirement : str6.split(",(?!\\s*[<>=!~(])")) {
                        String trimmed = requirement.trim();
                        if (trimmed.length() > 0) {
                            requirements.add(trimmed);
                        }
                    }
                    plugin.setRequirements(requirements);
                }
                plugin.setEnabled(getPluginsController().getPreferences().getBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + str, false));
                return new PluginsController.PluginValidationResult(plugin, null);
            }
            throw new IllegalArgumentException("Required value was null.");
        } catch (PyException e) {
            FileLog.e("Failed to parse metadata from " + filePath + ". Error: " + e.getMessage(), e); android.util.Log.e("sgPlugins", "Failed to parse metadata from " + filePath + ". Error: " + e.getMessage(), e); // sg: logcat
            return new PluginsController.PluginValidationResult(null, e.getMessage());
        } catch (Throwable th) {
            FileLog.e("Unexpected error validating plugin " + filePath, th); android.util.Log.e("sgPlugins", "Unexpected error validating plugin " + filePath, th); // sg: logcat
            return new PluginsController.PluginValidationResult(null, th.getMessage());
        }
    }

    public final List<SettingItem> parsePySettingDefinitions(List<? extends PyObject> definitions) {
        ArrayList<SettingItem> result = new ArrayList<>(definitions.size());
        for (PyObject item : definitions) {
            String type = PyObjectUtils.getString(item, "type", null);
            if (type == null) {
                FileLog.w("A setting item in a plugin is missing its 'type'. Skipping.");
                continue;
            }
            String key = PyObjectUtils.getString(item, "key", null);
            String text = PyObjectUtils.getString(item, "text", null);
            String subtext = PyObjectUtils.getString(item, "subtext", null);
            String icon = PyObjectUtils.getString(item, "icon", null);
            PyObject onChange = item.get("on_change");
            PyObject onLongClick = item.get("on_long_click");
            String linkAlias = PyObjectUtils.getString(item, "link_alias", null);
            PyObject defaultValue = item.get("default");
            PyObject onClick = item.get("on_click");
            PyObject createSubFragment = item.get("create_sub_fragment");
            SettingItem setting = null;
            switch (type) {
                case PluginsConstants.Settings.TYPE_DIVIDER:
                    setting = new DividerSetting(text);
                    break;
                case PluginsConstants.Settings.TYPE_SELECTOR: {
                    String[] items = PyObjectUtils.getStringArray(item, "items", null);
                    if (key != null && text != null && items != null && items.length != 0 && defaultValue != null) {
                        setting = new SelectorSetting(key, text, defaultValue.toInt(), items, icon, onChange, onLongClick, linkAlias);
                    }
                    break;
                }
                case PluginsConstants.Settings.TYPE_INPUT:
                    if (key != null && text != null) {
                        String value = defaultValue != null ? defaultValue.toString() : null;
                        setting = new InputSetting(key, text, value != null ? value : "", subtext, icon, onChange, onLongClick, linkAlias);
                    }
                    break;
                case PluginsConstants.Settings.TYPE_TEXT:
                    if (text != null) {
                        boolean accent = PyObjectUtils.getBoolean(item, "accent", false);
                        boolean red = PyObjectUtils.getBoolean(item, "red", false);
                        setting = new TextSetting(text, subtext, icon, accent, red, onClick, createSubFragment, onLongClick, linkAlias);
                    }
                    break;
                case PluginsConstants.Settings.TYPE_SWITCH:
                    if (key != null && text != null && defaultValue != null) {
                        setting = new SwitchSetting(key, text, defaultValue.toBoolean(), subtext, icon, onChange, onLongClick, linkAlias);
                    }
                    break;
                case PluginsConstants.Settings.TYPE_HEADER:
                    if (text != null) {
                        setting = new HeaderSetting(text);
                    }
                    break;
                case PluginsConstants.Settings.TYPE_CUSTOM: {
                    PyObject view = item.get("view");
                    PyObject uItem = item.get("item");
                    PyObject factory = item.get("factory");
                    PyObject factoryArgs = item.get("factory_args");
                    if (factory != null) {
                        CustomSetting.Factory<?> javaFactory = PyObjectUtils.toJavaCompat(factory, CustomSetting.Factory.class);
                        if (javaFactory != null) {
                            setting = factoryArgs == null
                                    ? new CustomSetting(javaFactory, onClick, createSubFragment, onLongClick, linkAlias)
                                    : new CustomSetting(javaFactory, factoryArgs, onClick, createSubFragment, onLongClick, linkAlias);
                        }
                    } else if (uItem != null) {
                        UItem javaItem = PyObjectUtils.toJavaCompat(uItem, UItem.class);
                        if (javaItem != null) {
                            setting = new CustomSetting(javaItem, onClick, createSubFragment, onLongClick, linkAlias);
                        }
                    } else if (view != null) {
                        View javaView = PyObjectUtils.toJavaCompat(view, View.class);
                        if (javaView != null) {
                            setting = new CustomSetting(javaView, onClick, createSubFragment, onLongClick, linkAlias);
                        }
                    }
                    break;
                }
                case PluginsConstants.Settings.TYPE_EDIT_TEXT: {
                    String hint = PyObjectUtils.getString(item, "hint", null);
                    boolean multiline = PyObjectUtils.getBoolean(item, "multiline", false);
                    int maxLength = PyObjectUtils.getInt(item, "max_length", 256);
                    String mask = PyObjectUtils.getString(item, "mask", null);
                    if (key != null && hint != null) {
                        String value = defaultValue != null ? defaultValue.toString() : null;
                        setting = new EditTextSetting(key, hint, value != null ? value : "", multiline, maxLength, mask, onChange);
                    }
                    break;
                }
                default:
                    break;
            }
            if (setting != null) {
                result.add(setting);
            } else {
                FileLog.w("Skipping plugin setting item of type '" + type + "' with missing/invalid fields (key=" + key + ", text=" + text + ")");
            }
        }
        return result;
    }

    @Override
    public List<SettingItem> loadPluginSettings(String id) {
        Plugin plugin = null;
        try {
            plugin = getPluginsController().getPlugins().get(id);
            PyObject pyObject = this.pluginInstances.get(id);
            if (getPluginsController().isPluginActive(plugin) && pyObject != null) {
                PyObject pyObjectCallAttr = pyObject.callAttr(PluginsConstants.CREATE_SETTINGS, new Object[0]);
                plugin.setSettingsError(null);
                if (pyObjectCallAttr == null) {
                    return null;
                }
                List<PyObject> listAsList = pyObjectCallAttr.asList();
                if (listAsList.isEmpty()) {
                    return null;
                }
                return parsePySettingDefinitions(listAsList);
            }
            getPluginsController().invalidatePluginSettings(id);
            return null;
        } catch (Throwable e) {
            // sg: create_settings() failures used to only hide the gear button; log them and show them on the card
            FileLog.e("Failed to load settings for plugin " + id, e);
            android.util.Log.e("sgPlugins", "create_settings failed for " + id, e);
            if (plugin != null) {
                plugin.setSettingsError(e);
            }
            return null;
        }
    }

    @Override
    public void executeOnAppEvent(String eventType) {
        Python python;
        PyObject module;
        PyObject appEventClass;
        if (!sdkInitialized || ExteraConfig.getPluginsSafeMode() || (python = getPython()) == null || (module = sdkModule(python, "base_plugin")) == null || (appEventClass = module.get("AppEvent")) == null) {
            return;
        }
        PyObject event = appEventClass.call(eventType);
        try {
            PyObject debugger = this.debuggerListener;
            if (debugger != null) {
                try {
                    debugger.callAttr(PluginsConstants.ON_APP_EVENT, event);
                } catch (PyException e) {
                    FileLog.e("Failed to execute app event for debugger listener", e);
                }
            }
            for (Map.Entry<String, PyObject> entry : this.pluginInstances.entrySet()) {
                String key = entry.getKey();
                if (!getPluginsController().isPluginActive(key)) {
                    continue;
                }
                getPluginsController().getWatchdog().onPluginExecutionStarted(key);
                try {
                    entry.getValue().callAttr(PluginsConstants.ON_APP_EVENT, event);
                } catch (PyException e2) {
                    FileLog.e("Failed to execute app " + eventType + " for " + key, e2);
                } finally {
                    getPluginsController().getWatchdog().onPluginExecutionFinished(key);
                }
            }
        } finally {
            PyObjectUtils.closeSafely(event);
        }
    }

    private final <T> PluginsController.HookResult<T> executeHook(PyObject pluginInstance, T initialValue, Class<T> valueClass, String pyResultKey, PyMethodCaller<T> caller, Utilities.Callback<Throwable> errorLogger) {
        if (pluginInstance != null) {
            try {
                PyObject pyObjectCall = caller.call(pluginInstance, initialValue);
                if (pyObjectCall != null) {
                    try {
                        String string = PyObjectUtils.getString(pyObjectCall, PluginsConstants.STRATEGY, PluginsConstants.Strategy.DEFAULT);
                        if (string != null && StringsKt.endsWith(string, PluginsConstants.Strategy.CANCEL, false)) {
                            PluginsController.HookResult<T> hookResult = new PluginsController.HookResult<>(null, true, false);
                            PyObjectUtils.closeSafely(pyObjectCall);
                            return hookResult;
                        }
                        if (string != null && (StringsKt.endsWith(string, PluginsConstants.Strategy.MODIFY, false) || StringsKt.endsWith(string, PluginsConstants.Strategy.MODIFY_FINAL, false))) {
                            PyObject pyObject = (PyObject) pyObjectCall.get((Object) pyResultKey);
                            if (pyObject != null) {
                                try {
                                    initialValue = (T) pyObject.toJava(valueClass);
                                } catch (ClassCastException e) {
                                    errorLogger.run(e);
                                }
                            }
                            if (StringsKt.endsWith(string, PluginsConstants.Strategy.MODIFY_FINAL, false)) {
                                PluginsController.HookResult<T> hookResult2 = new PluginsController.HookResult<>(initialValue, false, true);
                                PyObjectUtils.closeSafely(pyObjectCall);
                                return hookResult2;
                            }
                        }
                        PyObjectUtils.closeSafely(pyObjectCall);
                    } catch (Throwable th) {
                        PyObjectUtils.closeSafely(pyObjectCall);
                        throw th;
                    }
                }
            } catch (Throwable th2) {
                errorLogger.run(th2);
            }
        }
        return new PluginsController.HookResult<>(initialValue, false, false);
    }

    private final <T> PluginsController.HookResult<T> executeHook(String pluginId, T initialValue, Class<T> valueClass, String pyResultKey, PyMethodCaller<T> caller, Utilities.Callback<Throwable> errorLogger) {
        return executeHook(this.pluginInstances.get(pluginId), initialValue, valueClass, pyResultKey, caller, errorLogger);
    }

    @Override
    public PluginsController.HookResult<TLObject> executePreRequestHook(final String requestName, final int account, TLObject request, final String pluginId) {
        return executeHook(pluginId, request, (Class<TLObject>) TLObject.class, PluginsConstants.REQUEST, (PyMethodCaller<TLObject>) new PyMethodCaller() {
            @Override
            public final PyObject call(PyObject pyObject, Object obj) {
                return pyObject.callAttr("pre_request_hook", requestName, Integer.valueOf(account), (TLObject) obj);
            }
        }, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                FileLog.e("Failed to execute pre_request_hook in " + pluginId + " for " + requestName, (Throwable) obj);
            }
        });
    }

    public final PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String requestName, int account, TLObject response, TLRPC.TL_error error, PyObject pluginInstance) {
        if (pluginInstance != null) {
            try {
                PyObject pyObjectCallAttr = pluginInstance.callAttr("post_request_hook", requestName, Integer.valueOf(account), response, error);
                if (pyObjectCallAttr != null) {
                    try {
                        String string = PyObjectUtils.getString(pyObjectCallAttr, PluginsConstants.STRATEGY, "");
                        if (string != null && (StringsKt.endsWith(string, PluginsConstants.Strategy.MODIFY, false) || StringsKt.endsWith(string, PluginsConstants.Strategy.MODIFY_FINAL, false))) {
                            PyObject pyObject = (PyObject) pyObjectCallAttr.get(PluginsConstants.RESPONSE);
                            if (pyObject != null) {
                                try {
                                    response = (TLObject) pyObject.toJava(TLObject.class);
                                } catch (ClassCastException e) {
                                    FileLog.e("Bad response type from post_request_hook for " + requestName, e);
                                }
                            }
                            PyObject pyObject2 = (PyObject) pyObjectCallAttr.get("error");
                            if (pyObject2 != null) {
                                try {
                                    error = (TLRPC.TL_error) pyObject2.toJava(TLRPC.TL_error.class);
                                } catch (ClassCastException e2) {
                                    FileLog.e("Bad error type from post_request_hook for " + requestName, e2);
                                }
                            }
                            if (StringsKt.endsWith(string, PluginsConstants.Strategy.MODIFY_FINAL, false)) {
                                PluginsController.HookResult<PluginsHooks.PostRequestResult> hookResult = new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(response, error), false, true);
                                PyObjectUtils.closeSafely(pyObjectCallAttr);
                                return hookResult;
                            }
                        }
                        PyObjectUtils.closeSafely(pyObjectCallAttr);
                    } catch (Throwable th) {
                        PyObjectUtils.closeSafely(pyObjectCallAttr);
                        throw th;
                    }
                }
            } catch (Throwable th2) {
                FileLog.e("Failed to execute post_request_hook for " + requestName, th2);
            }
        }
        return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(response, error), false, false);
    }

    @Override
    public PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String requestName, int account, TLObject response, TLRPC.TL_error error, String pluginId) {
        return executePostRequestHook(requestName, account, response, error, this.pluginInstances.get(pluginId));
    }

    @Override
    public PluginsController.HookResult<TLRPC.Update> executeUpdateHook(final String updateName, final int account, TLRPC.Update update, String pluginId) {
        return executeHook(pluginId, update, (Class<TLRPC.Update>) TLRPC.Update.class, PluginsConstants.UPDATE, (PyMethodCaller<TLRPC.Update>) new PyMethodCaller() {
            @Override
            public final PyObject call(PyObject pyObject, Object obj) {
                return pyObject.callAttr("on_update_hook", updateName, Integer.valueOf(account), (TLRPC.Update) obj);
            }
        }, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                FileLog.e("Failed to execute on_update_hook for " + updateName, (Throwable) obj);
            }
        });
    }

    @Override
    public PluginsController.HookResult<TLRPC.Updates> executeUpdatesHook(final String containerName, final int account, TLRPC.Updates updates, String pluginId) {
        return executeHook(pluginId, updates, (Class<TLRPC.Updates>) TLRPC.Updates.class, PluginsConstants.UPDATES, (PyMethodCaller<TLRPC.Updates>) new PyMethodCaller() {
            @Override
            public final PyObject call(PyObject pyObject, Object obj) {
                return pyObject.callAttr("on_updates_hook", containerName, Integer.valueOf(account), (TLRPC.Updates) obj);
            }
        }, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                FileLog.e("Failed to execute on_updates_hook for " + containerName, (Throwable) obj);
            }
        });
    }

    @Override
    public PluginsController.HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(final int account, SendMessagesHelper.SendMessageParams params, final String pluginId) {
        return executeHook(pluginId, params, (Class<SendMessagesHelper.SendMessageParams>) SendMessagesHelper.SendMessageParams.class, PluginsConstants.PARAMS, (PyMethodCaller<SendMessagesHelper.SendMessageParams>) new PyMethodCaller() {
            @Override
            public final PyObject call(PyObject pyObject, Object obj) {
                return pyObject.callAttr("on_send_message_hook", Integer.valueOf(account), (SendMessagesHelper.SendMessageParams) obj);
            }
        }, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                FileLog.e("Failed to execute on_send_message_hook for " + pluginId, (Throwable) obj);
            }
        });
    }

    public final String fetchParameterValue(String filePath, String parameterName) {
        if (filePath == null) {
            return null;
        }
        try {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                return parsePluginMetadata(filePath).get(parameterName);
            }
        } catch (Exception unused) {
        }
        return null;
    }

    public final Map<String, String> parsePluginMetadata(String filePath) {
        HashMap<String, String> map = new HashMap<>();
        if (filePath != null) {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                Python python = getPython();
                if (python == null) {
                    FileLog.e("Python engine not initialized, cannot parse metadata for ".concat(filePath));
                    return map;
                }
                try {
                    PyObject parser = sdkModule(python, "extera_utils.metadata_parser");
                    // sg: installed plugins reuse the parsed metadata while their content (sha256) is unchanged
                    File cacheFile = metadataCacheFileFor(file);
                    PyObject pyObjectCallAttr = cacheFile != null
                            ? parser.callAttr("get_metadata_cached", filePath, cacheFile.getAbsolutePath())
                            : parser.callAttr("get_metadata", filePath);
                    if (pyObjectCallAttr != null) {
                        for (Map.Entry<PyObject, PyObject> entry : pyObjectCallAttr.asMap().entrySet()) {
                            map.put(entry.getKey().toString(), entry.getValue().toString());
                        }
                    }
                } catch (PyException e) {
                    FileLog.e("Failed to parse metadata from " + filePath + ". Error: " + e.getMessage(), e);
                    throw e;
                }
            }
        }
        return map;
    }

    /** Metadata cache file for a plugin installed in the plugins directory, else null (files being installed etc). */
    private File metadataCacheFileFor(File file) {
        try {
            File pluginsDir = getPluginsController().getPluginsDir();
            File parent = file.getAbsoluteFile().getParentFile();
            if (pluginsDir == null || parent == null || !parent.equals(pluginsDir.getAbsoluteFile())) {
                return null;
            }
            return new File(new File(pluginsDir, ".cache"), "metadata.json");
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Object getPluginSetting(String pluginId, String key, Object defaultValue) {
        Object java2;
        ConcurrentHashMap<String, Object> concurrentHashMap = this.settingsCache.get(pluginId);
        if (concurrentHashMap != null && concurrentHashMap.containsKey(key)) {
            return concurrentHashMap.get(key);
        }
        Python python = getPython();
        if (python != null) {
            try {
                PyObject pyObjectCallAttr = sdkModule(python, "plugin_settings").callAttr("get_setting", pluginId, key, defaultValue);
                if (pyObjectCallAttr != null) {
                    if (defaultValue instanceof Boolean) {
                        java2 = Boolean.valueOf(pyObjectCallAttr.toBoolean());
                    } else if (defaultValue instanceof Integer) {
                        java2 = Integer.valueOf(pyObjectCallAttr.toInt());
                    } else if (defaultValue instanceof String) {
                        java2 = pyObjectCallAttr.toString();
                    } else if (defaultValue instanceof Float) {
                        java2 = Float.valueOf(pyObjectCallAttr.toFloat());
                    } else if (defaultValue instanceof Long) {
                        java2 = Long.valueOf(pyObjectCallAttr.toLong());
                    } else if (defaultValue == null) {
                        java2 = pyObjectCallAttr.toJava(Object.class);
                    } else {
                        java2 = pyObjectCallAttr.toJava(defaultValue.getClass());
                    }
                    if (java2 != null) { // ConcurrentHashMap rejects null values
                        this.settingsCache.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>()).put(key, java2);
                    }
                    return java2;
                }
            } catch (PyException e) {
                FileLog.e("Failed to get plugin setting " + pluginId + "/" + key, e);
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public void setPluginSetting(String pluginId, String key, Object value) {
        ConcurrentHashMap<String, Object> cache = this.settingsCache.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>());
        if (value != null) {
            cache.put(key, value);
        } else {
            cache.remove(key);
        }
        Python python = getPython();
        if (python == null) {
            return;
        }
        try {
            sdkModule(python, "plugin_settings").callAttr("set_setting", pluginId, key, value);
        } catch (PyException e) {
            FileLog.e("Failed to set plugin setting " + pluginId + "/" + key, e);
        }
    }

    @Override
    public void clearPluginSettings(String pluginId) {
        this.settingsCache.remove(pluginId);
        Python python = getPython();
        if (python == null) {
            return;
        }
        try {
            sdkModule(python, "plugin_settings").callAttr("clear_settings", pluginId);
        } catch (PyException e) {
            FileLog.e("Failed to clear plugin settings for " + pluginId, e);
        }
    }

    @Override
    public Map<String, ?> getAllPluginSettings(String pluginId) {
        Python python = getPython();
        if (python == null) {
            return null;
        }
        try {
            PyObject pyObjectCallAttr = sdkModule(python, "plugin_settings").callAttr("get_all_settings", pluginId);
            if (pyObjectCallAttr != null) {
                HashMap<String, Object> map = new HashMap<>();
                ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();
                for (Map.Entry<PyObject, PyObject> entry : pyObjectCallAttr.asMap().entrySet()) {
                    PyObject key = entry.getKey();
                    PyObject value = entry.getValue();
                    if (key != null) {
                        Object javaValue = value != null ? value.toJava(Object.class) : null;
                        map.put(key.toString(), javaValue);
                        if (javaValue != null) {
                            cache.put(key.toString(), javaValue);
                        }
                    }
                }
                this.settingsCache.put(pluginId, cache);
                return map;
            }
        } catch (PyException e) {
            FileLog.e("Failed to get all plugin settings for " + pluginId, e);
        }
        return null;
    }

    @Override
    public void showInstallDialog(final BaseFragment fragment, InstallPluginBottomSheet.PluginInstallParams params) {
        File file = new File(params.getFilePath());
        String name = fetchParameterValue(params.getFilePath(), "name");
        if (TextUtils.isEmpty(name) && file.exists()) {
            name = file.getName();
        }
        final String strFetchParameterValue = name;
        final PluginsController.PluginValidationResult pluginValidationResultValidatePluginFromFile = validatePluginFromFile(params.getFilePath());
        if (pluginValidationResultValidatePluginFromFile.getPlugin() != null) {
            new InstallPluginBottomSheet(fragment, pluginValidationResultValidatePluginFromFile, params).show();
        } else {
            if (params.getResultCallback() != null) {
                final String validationError = pluginValidationResultValidatePluginFromFile.getError();
                AndroidUtilities.runOnUIThread(() -> params.getResultCallback().run(validationError != null ? validationError : "Invalid plugin file"));
            }
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public final void run() {
                    BaseFragment baseFragment = fragment;
                    BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.PluginInstallError, strFetchParameterValue), LocaleUtils.createCopySpan(baseFragment), new Runnable() {
                        @Override
                        public final void run() {
                            PythonPluginsEngine.$r8$lambda$9hTMkDvZ_f5UE5Nq3UiVjEBnuis(pluginValidationResultValidatePluginFromFile, baseFragment);
                        }
                    }).show();
                }
            });
        }
    }

    public static void $r8$lambda$9hTMkDvZ_f5UE5Nq3UiVjEBnuis(PluginsController.PluginValidationResult pluginValidationResult, BaseFragment baseFragment) {
        if (AndroidUtilities.addToClipboard(pluginValidationResult.getError())) {
            BulletinFactory.of(baseFragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    @Override
    public void openPluginSettings(String id, BaseFragment fragment) {
        Plugin plugin = getPluginsController().getPlugins().get(id);
        if (plugin != null) {
            openPluginSettings(plugin, fragment);
        }
    }

    @Override
    public void openPluginSettings(final Plugin plugin, final BaseFragment fragment) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                fragment.presentFragment(new PluginSettingsActivity(plugin));
            }
        });
    }

    @Override
    public void openPluginSetting(final Plugin plugin, final String linkAlias, final BaseFragment fragment) {
        PluginsController.Companion.runOnPluginsQueue(new Runnable() {
            @Override
            public final void run() {
                PythonPluginsEngine.m1562$r8$lambda$HwG9FDb6KK4BeK6Uwi_5nVHUrA(PythonPluginsEngine.this, plugin, linkAlias, fragment);
            }
        });
    }

    public static void m1562$r8$lambda$HwG9FDb6KK4BeK6Uwi_5nVHUrA(PythonPluginsEngine pythonPluginsEngine, Plugin plugin, String str, final BaseFragment baseFragment) {
        final PluginSettingsActivity settingsLinkPrefix;
        if (pythonPluginsEngine.getPluginsController().isPluginActive(plugin)) {
            FileLog.d("Opening plugin setting: " + plugin.getId() + "/" + str);
            if (!str.contains(":")) {
                settingsLinkPrefix = new PluginSettingsActivity(plugin, str);
            } else {
                List<SettingItem> list = pythonPluginsEngine.getPluginsController().getSettings().get(plugin.getId());
                if (list == null) {
                    return;
                }
                String[] strArr = str.split(":", -1);
                int length = strArr.length - 1;
                List<SettingItem> pySettingDefinitions = list;
                TextSetting textSetting = null;
                for (int i = 0; i < length; i++) {
                    String part = strArr[i];
                    for (SettingItem next : pySettingDefinitions) {
                        if ((next instanceof TextSetting) && Intrinsics.areEqual(part, next.getLinkAlias())) {
                            textSetting = (TextSetting) next;
                            try {
                                if (!pythonPluginsEngine.getPluginsController().isPluginActive(plugin)) {
                                    return;
                                }
                                PyObject createSubFragmentCallback = textSetting.getCreateSubFragmentCallback();
                                PyObject subItems = createSubFragmentCallback != null ? createSubFragmentCallback.call() : null;
                                if (subItems != null) {
                                    pySettingDefinitions = pythonPluginsEngine.parsePySettingDefinitions(subItems.asList());
                                }
                            } catch (Exception ignored) {
                            }
                            break;
                        }
                    }
                    if (textSetting == null && pySettingDefinitions.isEmpty()) {
                        SettingsRegistry.getInstance().onSettingNotFound(baseFragment);
                        return;
                    }
                }
                if (textSetting == null) {
                    return;
                }
                settingsLinkPrefix = new PluginSettingsActivity(plugin, textSetting.getText(), pySettingDefinitions, textSetting.getCreateSubFragmentCallback(), strArr[strArr.length - 1])
                        .setSettingsLinkPrefix(TextUtils.join(":", Arrays.copyOf(strArr, strArr.length - 1)));
            }
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public final void run() {
                    baseFragment.presentFragment(settingsLinkPrefix);
                }
            });
        }
    }

    @Override
    public void openPluginSetting(String pluginId, String linkAlias, BaseFragment fragment) {
        Plugin plugin = getPluginsController().getPlugins().get(pluginId);
        if (plugin != null) {
            openPluginSetting(plugin, linkAlias, fragment);
        }
    }

    // region Elyx: ElyxPluginsEngine shares this engine's interpreter, SDK state, dependency handling,
    // settings storage and hook plumbing (Elyx plugins are BasePlugin instances too).

    public final Python getPythonOrNull() {
        return getPython();
    }

    public final boolean isSdkReady() {
        return this.python != null && sdkInitialized;
    }

    public final PyObject requireBasePluginClassForEngine() throws Exception {
        return requireBasePluginClass();
    }

    public final void installDependenciesFor(String pluginId, Plugin pluginMetadata, PipController.InstallerDelegate delegate) throws Exception {
        installPluginDependencies(pluginId, pluginMetadata, delegate);
    }

    public final void removeDependenciesFor(String pluginId) {
        removePluginDependencies(pluginId);
    }

    public final PluginsController.HookResult<TLObject> executePreRequestHookOn(PyObject instance, final String requestName, final int account, TLObject request, final String pluginId) {
        return executeHook(instance, request, TLObject.class, PluginsConstants.REQUEST, (pyObject, obj) -> pyObject.callAttr("pre_request_hook", requestName, Integer.valueOf(account), obj),
                th -> FileLog.e("Failed to execute pre_request_hook in " + pluginId + " for " + requestName, th));
    }

    public final PluginsController.HookResult<TLRPC.Update> executeUpdateHookOn(PyObject instance, final String updateName, final int account, TLRPC.Update update, final String pluginId) {
        return executeHook(instance, update, TLRPC.Update.class, PluginsConstants.UPDATE, (pyObject, obj) -> pyObject.callAttr("on_update_hook", updateName, Integer.valueOf(account), obj),
                th -> FileLog.e("Failed to execute on_update_hook in " + pluginId + " for " + updateName, th));
    }

    public final PluginsController.HookResult<TLRPC.Updates> executeUpdatesHookOn(PyObject instance, final String containerName, final int account, TLRPC.Updates updates, final String pluginId) {
        return executeHook(instance, updates, TLRPC.Updates.class, PluginsConstants.UPDATES, (pyObject, obj) -> pyObject.callAttr("on_updates_hook", containerName, Integer.valueOf(account), obj),
                th -> FileLog.e("Failed to execute on_updates_hook in " + pluginId + " for " + containerName, th));
    }

    public final PluginsController.HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHookOn(PyObject instance, final int account, SendMessagesHelper.SendMessageParams params, final String pluginId) {
        return executeHook(instance, params, SendMessagesHelper.SendMessageParams.class, PluginsConstants.PARAMS, (pyObject, obj) -> pyObject.callAttr("on_send_message_hook", Integer.valueOf(account), obj),
                th -> FileLog.e("Failed to execute on_send_message_hook for " + pluginId, th));
    }

    // endregion

    public static final class Updater {
        private static int TAG;
        private static volatile boolean isLoading;
        private static long lastCheckUpdateTime;
        private static boolean notifyWhenChangeStatus;
        private static int status;

            public static final Companion Companion = new Companion();
        private static final Pattern PYTHON_SDK_APP_VERSION_PATTERN = Pattern.compile("^app_version(>=|<=|==)(.+)$");
        private static final Pattern PYTHON_SDK_APP_VERSION_CODE_PATTERN = Pattern.compile("^app_version_code(>=|<=|==)(.+)$");
        private static final Runnable notifyRunnable = new Runnable() {
            @Override
            public final void run() {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginsPySdkInfoChanged, new Object[0]);
            }
        };

        public static final void checkUpdates() {
            Companion.checkUpdates();
        }

        public static final void checkUpdates(boolean z) {
            Companion.checkUpdates(z);
        }

        public static final void deleteSdkUpdateFile() {
            Companion.deleteSdkUpdateFile();
        }

        public static final void enableAutoUpdate() {
            Companion.enableAutoUpdate();
        }

        public static final File getPythonCurrentSdkFile() {
            return Companion.getPythonCurrentSdkFile();
        }

        public static final File getPythonSdkUpdateFile() {
            return Companion.getPythonSdkUpdateFile();
        }

        public static final File getPythonSdkUpdateMetaFile() {
            return Companion.getPythonSdkUpdateMetaFile();
        }

        public static final CharSequence getStateString() {
            return Companion.getStateString();
        }

        public static final CharSequence getVersion() {
            return Companion.getVersion();
        }

        public static final String hashBytes(InputStream inputStream) {
            return Companion.hashBytes(inputStream);
        }

        public static final boolean isAppVersionCodeCompatible(String str, String str2) {
            return Companion.isAppVersionCodeCompatible(str, str2);
        }

        public static final boolean isAppVersionCompatible(String str, String str2) {
            return Companion.isAppVersionCompatible(str, str2);
        }

        public static final boolean isPendingUpdateVerified(File file) {
            return Companion.isPendingUpdateVerified(file);
        }

        public static final boolean isSdkFromApk() {
            return Companion.isSdkFromApk();
        }

        public static final boolean isSdkVersionNewer(String str, boolean z) {
            return Companion.isSdkVersionNewer(str, z);
        }

        public static final Companion.PythonSdkUpdateInfo parsePythonSdkUpdateResponse(TLRPC.messages_Messages messages_messages) {
            return Companion.parsePythonSdkUpdateResponse(messages_messages);
        }

        public static final File requestSdkFromApkFile() {
            return Companion.requestSdkFromApkFile();
        }

        public static final void restoreSdkFromApk() {
            Companion.restoreSdkFromApk();
        }

        public static final void savePythonSdkArchive(Companion.PythonSdkUpdateInfo pythonSdkUpdateInfo, boolean z) throws IOException {
            Companion.savePythonSdkArchive(pythonSdkUpdateInfo, z);
        }

        public static final InputStream sdkFromApk() throws IOException {
            return Companion.sdkFromApk();
        }

        public static final void setBuildFromApk(boolean z) {
            Companion.setBuildFromApk(z);
        }

        public static final void zipFolder(File file, File file2) throws IOException {
            Companion.zipFolder(file, file2);
        }

        public static final class Companion {
            private Companion() {
            }

            public final int getStatus() {
                return Updater.status;
            }

            public final void setStatus(int i) {
                Updater.status = i;
            }

            public final boolean getNotifyWhenChangeStatus() {
                return Updater.notifyWhenChangeStatus;
            }

            public final void setNotifyWhenChangeStatus(boolean z) {
                Updater.notifyWhenChangeStatus = z;
            }

            public final CharSequence getVersion() {
                String sdk_version = (ExteraConfig.getPluginsEngine() && PythonPluginsEngine.sdkInitialized) ? PythonPluginsEngine.Companion.getSDK_VERSION() : null;
                boolean sdk_beta = PythonPluginsEngine.Companion.getSDK_BETA();
                if (sdk_version == null && PythonPluginsEngine.SDK_DIR != null) {
                    File file = new File(PythonPluginsEngine.SDK_DIR, "v.txt");
                    if (file.exists()) {
                        try {
                            String string = PluginFiles.readText(file, 65536L).trim();
                            sdk_beta = string.endsWith("|1");
                            sdk_version = StringsKt.substringBefore(string, '|', string);
                        } catch (IOException e) {
                            FileLog.e(e);
                        }
                    }
                }
                if (sdk_version == null) {
                    return "SDK not loaded";
                }
                return "v" + sdk_version + (sdk_beta ? "-beta" : "");
            }

            public final CharSequence getStateString() {
                int status = getStatus();
                if (status == 0) {
                    return getVersion();
                }
                if (status == 1) {
                    return LocaleController.getString(R.string.CheckingForUpdates);
                }
                if (status != 2) {
                    if (status == 3) {
                        return LocaleController.getString(R.string.LoadingUpdate);
                    }
                    if (status != 4) {
                        return null;
                    }
                    return LocaleController.getString(R.string.RestartPluginSystemToApplyUpdate);
                }
                return LocaleController.getString(R.string.LatestVersionInstalled) + " (" + ((Object) getVersion()) + ")";
            }

            public static final class PythonSdkUpdateInfo extends TLRPC.TL_help_appUpdate {
                private String abi;
                private String appVersion;
                private String appVersionCode;
                private String appVersionCodeOperator;
                private String appVersionOperator;
                private boolean available;
                private String channel;
                private TLRPC.Message message;
                private String signature;

                public PythonSdkUpdateInfo() {
                    clear();
                }

                public final TLRPC.Message getMessage() {
                    return this.message;
                }

                public final void setMessage(TLRPC.Message message) {
                    this.message = message;
                }

                public final boolean getAvailable() {
                    return this.available;
                }

                public final void setAvailable(boolean z) {
                    this.available = z;
                }

                public final String getChannel() {
                    return this.channel;
                }

                public final void setChannel(String str) {
                    this.channel = str;
                }

                public final String getAppVersionOperator() {
                    return this.appVersionOperator;
                }

                public final void setAppVersionOperator(String str) {
                    this.appVersionOperator = str;
                }

                public final String getAppVersion() {
                    return this.appVersion;
                }

                public final void setAppVersion(String str) {
                    this.appVersion = str;
                }

                public final String getAppVersionCodeOperator() {
                    return this.appVersionCodeOperator;
                }

                public final void setAppVersionCodeOperator(String str) {
                    this.appVersionCodeOperator = str;
                }

                public final String getAppVersionCode() {
                    return this.appVersionCode;
                }

                public final void setAppVersionCode(String str) {
                    this.appVersionCode = str;
                }

                public final String getAbi() {
                    return this.abi;
                }

                public final void setAbi(String str) {
                    this.abi = str;
                }

                public final String getSignature() {
                    return this.signature;
                }

                public final void setSignature(String str) {
                    this.signature = str;
                }

                public final void clear() {
                    this.message = null;
                    this.available = false;
                    this.can_not_skip = false;
                    this.channel = null;
                    this.version = null;
                    this.appVersion = null;
                    this.appVersionOperator = null;
                    this.appVersionCode = null;
                    this.appVersionCodeOperator = null;
                    this.document = null;
                    this.abi = null;
                    this.signature = null;
                }

                public final boolean canInstall() {
                    String str;
                    String str2 = this.appVersion;
                    String str3 = this.appVersionOperator;
                    String str4 = this.appVersionCode;
                    String str5 = this.appVersionCodeOperator;
                    String str6 = this.version;
                    return (str2 == null || str3 == null || Updater.Companion.isAppVersionCompatible(str3, str2)) && !((str4 != null && str5 != null && !Updater.Companion.isAppVersionCodeCompatible(str5, str4)) || str6 == null || str6.length() == 0 || !Updater.Companion.isSdkVersionNewer(str6, Intrinsics.areEqual(this.channel, "beta")) || this.document == null || !Intrinsics.areEqual(this.abi, Build.SUPPORTED_ABIS[0]) || (str = this.signature) == null || str.length() == 0);
                }
            }

            public final InputStream sdkFromApk() throws IOException {
                return ApplicationLoader.applicationContext.getAssets().open("plugins_pysdk/sdk-" + Build.SUPPORTED_ABIS[0] + ".zip");
            }

            public final boolean isSdkFromApk() {
                return sdkFromApkMarker().exists() || requestSdkFromApkFile().exists();
            }

            public final boolean isSdkFromCurrentApk() {
                File fileSdkFromApkMarker = sdkFromApkMarker();
                if (!fileSdkFromApkMarker.exists()) {
                    return false;
                }
                try {
                    return Intrinsics.areEqual(PluginFiles.readText(fileSdkFromApkMarker, 65536L), currentApkStamp());
                } catch (Throwable th) {
                    return false;
                }
            }

            public final void setBuildFromApk(boolean fromApk) {
                File fileSdkFromApkMarker = sdkFromApkMarker();
                if (!fromApk) {
                    PythonPluginsEngine.Companion.deleteFileIfExists(fileSdkFromApkMarker);
                    return;
                }
                try {
                    PluginFiles.writeTextAtomic(fileSdkFromApkMarker, currentApkStamp());
                } catch (Throwable th) {
                    FileLog.e(th);
                }
            }

            private final File sdkFromApkMarker() {
                return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), ".currentSdkFromApk");
            }

            private final String currentApkStamp() {
                String str = ApplicationLoader.applicationContext.getApplicationInfo().sourceDir;
                return str + ":" + new File(str).lastModified();
            }

            public final String hashBytes(InputStream inputStream) {
                try (InputStream in = inputStream) {
                    MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
                    byte[] buffer = new byte[1048576];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        messageDigest.update(buffer, 0, read);
                    }
                    byte[] digest = messageDigest.digest();
                    StringBuilder sb = new StringBuilder(digest.length * 2);
                    for (byte b : digest) {
                        sb.append(String.format("%02x", b));
                    }
                    return sb.toString();
                } catch (IOException | NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            public final File getPythonSdkUpdateFile() {
                return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "newSdk");
            }

            public final File getPythonCurrentSdkFile() {
                return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "currentSdk");
            }

            public final File getPythonSdkUpdateMetaFile() {
                return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "newSdk.meta");
            }

            public final boolean isPendingUpdateVerified(File archive) {
                File pythonSdkUpdateMetaFile = getPythonSdkUpdateMetaFile();
                if (!pythonSdkUpdateMetaFile.exists()) {
                    FileLog.e("Pending Python SDK update has no signature");
                    return false;
                }
                try {
                    String[] parts = PluginFiles.readText(pythonSdkUpdateMetaFile, 65536L).trim().split("\\|", -1);
                    if (parts.length != 3) {
                        FileLog.e("Malformed signature of a pending Python SDK update");
                        return false;
                    }
                    return SdkSignature.verify(archive, parts[0], parts[1], parts[2]);
                } catch (IOException e) {
                    FileLog.e("Failed to read pending Python SDK update signature", e);
                    return false;
                }
            }

            public final File requestSdkFromApkFile() {
                return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), ".restoreSdk");
            }

            public final void deleteSdkUpdateFile() {
                PythonPluginsEngine.Companion.deleteFileIfExists(getPythonSdkUpdateMetaFile());
                File pythonSdkUpdateFile = getPythonSdkUpdateFile();
                if (pythonSdkUpdateFile.exists()) {
                    PythonPluginsEngine.Companion.deleteFileIfExists(pythonSdkUpdateFile);
                    updateStatus(0);
                }
            }

            public final void enableAutoUpdate() {
                ExteraConfig.setPluginsPySdkAutoUpdate(true);
                if (getNotifyWhenChangeStatus()) {
                    Updater.notifyRunnable.run();
                }
            }

            public final void checkUpdates() {
                checkUpdates(false);
            }

            public final void checkUpdates(boolean force) {
                // exteraStatic: the open SDK is bundled with the app and updated with it; the vendor
                // update channel (signed SDK archives) is not used.
                if (true) {
                    return;
                }
                long jCurrentTimeMillis = System.currentTimeMillis();
                if ((getStatus() != 1 || Math.abs(jCurrentTimeMillis - Updater.lastCheckUpdateTime) >= 6000) && getStatus() <= 2) {
                    if (!force) {
                        boolean z = Math.abs(jCurrentTimeMillis - ExteraConfig.getSdkUpdateScheduleTimestamp()) < 3600000L;
                        if (!ExteraConfig.getPluginsEngine() || ExteraConfig.getPluginsSafeMode() || z) {
                            return;
                        }
                    }
                    ExteraConfig.setSdkUpdateScheduleTimestamp(jCurrentTimeMillis);
                    updateStatus(1);
                    Updater.lastCheckUpdateTime = jCurrentTimeMillis;
                    RemoteUtils.searchMessages("python_sdk", new TLRPC.TL_inputMessagesFilterDocument(), (res, error) -> onUpdateSearchResult(res, error), 3000);
                }
            }

            private void onUpdateSearchResult(TLRPC.messages_Messages messages_messages, TLRPC.TL_error tL_error) {
                Companion companion = Updater.Companion;
                final PythonSdkUpdateInfo pythonSdkUpdateResponse;
                boolean z;
                boolean z2 = false;
                if (tL_error != null) {
                    FileLog.e("Failed to search messages with sdk updates: " + tL_error.text);
                } else if (messages_messages != null && (pythonSdkUpdateResponse = companion.parsePythonSdkUpdateResponse(messages_messages)) != null) {
                    if (ExteraConfig.getPluginsPySdkAutoUpdate()) {
                        z = false;
                    } else {
                        final BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
                        if (safeLastFragment != null) {
                            AndroidUtilities.runOnUIThread(new Runnable() {
                                @Override
                                public final void run() {
                                    BaseFragment baseFragment = safeLastFragment;
                                    baseFragment.showDialog(PythonSdkUpdateAlert.create(baseFragment, pythonSdkUpdateResponse));
                                }
                            });
                        }
                        z = true;
                    }
                    if (z) {
                        z2 = z;
                    } else {
                        try {
                            companion.savePythonSdkArchive(pythonSdkUpdateResponse, false);
                            z2 = true;
                        } catch (IOException e) {
                            String channel = pythonSdkUpdateResponse.getChannel();
                            TLRPC.Message message = pythonSdkUpdateResponse.getMessage();
                            FileLog.e("Failed to load python-plugins-sdk file (" + channel + ", message id = " + (message != null ? Integer.valueOf(message.id) : null) + ")", e);
                            z2 = z;
                        }
                    }
                }
                if (z2) {
                    return;
                }
                Updater.Companion.updateStatus(2);
            }

            public final void restoreSdkFromApk() {
                touchFile(requestSdkFromApkFile());
            }

            private final void touchFile(File file) {
                try {
                    PluginFiles.touch(file);
                } catch (Throwable th) {
                    FileLog.e(th);
                }
            }

            public final void updateStatus(int newStatus) {
                setStatus(newStatus);
                if (getNotifyWhenChangeStatus()) {
                    AndroidUtilities.cancelRunOnUIThread(Updater.notifyRunnable);
                    AndroidUtilities.runOnUIThread(Updater.notifyRunnable, newStatus == 1 ? 0L : 600L);
                }
            }

            public final PythonSdkUpdateInfo parsePythonSdkUpdateResponse(TLRPC.messages_Messages res) {
                PythonSdkUpdateInfo info = new PythonSdkUpdateInfo();
                for (TLRPC.Message next : res.messages) {
                    if (!(next instanceof TLRPC.TL_message)) {
                        continue;
                    }
                    TLRPC.TL_message message = (TLRPC.TL_message) next;
                    if (TextUtils.isEmpty(message.message) || !(message.media instanceof TLRPC.TL_messageMediaDocument)) {
                        continue;
                    }
                    boolean stable = message.message.contains("python_sdk_stable");
                    boolean beta = message.message.contains("python_sdk_beta");
                    if (!stable && !beta || beta && !ExteraConfig.getPluginsPySdkBetaVersions()) {
                        continue;
                    }
                    // the post is "<changelog>\npython_sdk_<channel>\nkey=value..."
                    StringBuilder changelog = new StringBuilder();
                    boolean inHeader = false;
                    for (String rawLine : message.message.split("\n")) {
                        String line = rawLine.trim();
                        if (TextUtils.isEmpty(line) && inHeader) {
                            continue;
                        }
                        if (line.startsWith("python_sdk_")) {
                            info.setChannel(beta ? "beta" : "stable");
                            inHeader = true;
                        } else if (!inHeader) {
                            changelog.append(line).append("\n");
                        } else {
                            Matcher versionMatcher = Updater.PYTHON_SDK_APP_VERSION_PATTERN.matcher(line);
                            Matcher versionCodeMatcher = Updater.PYTHON_SDK_APP_VERSION_CODE_PATTERN.matcher(line);
                            if (versionMatcher.matches()) {
                                info.setAppVersionOperator(versionMatcher.group(1));
                                String value = versionMatcher.group(2);
                                info.setAppVersion(value != null ? value.trim() : null);
                            } else if (versionCodeMatcher.matches()) {
                                info.setAppVersionCodeOperator(versionCodeMatcher.group(1));
                                String value = versionCodeMatcher.group(2);
                                info.setAppVersionCode(value != null ? value.trim() : null);
                            } else {
                                String[] pair = line.split("=", 2);
                                if (pair.length == 2) {
                                    String key = pair[0].trim();
                                    String value = pair[1].trim();
                                    switch (key) {
                                        case "can_not_skip":
                                            info.can_not_skip = Boolean.parseBoolean(value);
                                            break;
                                        case "abi":
                                            info.setAbi(value);
                                            break;
                                        case "version":
                                            info.version = value;
                                            break;
                                        case "signature":
                                            info.setSignature(value);
                                            break;
                                    }
                                }
                            }
                        }
                    }
                    info.document = message.media.document;
                    if (info.canInstall()) {
                        info.text = changelog.toString();
                        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
                        for (TLRPC.MessageEntity entity : message.entities) {
                            if (!(entity instanceof TLRPC.TL_messageEntityPre)) {
                                entities.add(entity);
                            }
                        }
                        info.entities = entities;
                        info.setMessage(next);
                        break;
                    }
                    info.clear();
                }
                if (info.getMessage() == null) {
                    return null;
                }
                info.setAvailable(info.document != null && !TextUtils.isEmpty(info.version));
                return info;
            }

            public final boolean isSdkVersionNewer(String remoteVersion, boolean isBeta) {
                if (!ExteraConfig.getPluginsPySdkBetaVersions() && PythonPluginsEngine.Companion.getSDK_BETA()) {
                    return !isBeta;
                }
                if (PythonPluginsEngine.Companion.getSDK_VERSION() != null) {
                    return AppUtils.compareVersions(">", remoteVersion, PythonPluginsEngine.Companion.getSDK_VERSION());
                }
                return false;
            }

            public final boolean isAppVersionCompatible(String operator, String targetVersion) {
                return AppUtils.compareVersions(operator, BuildVars.BUILD_VERSION_STRING, targetVersion);
            }

            public final boolean isAppVersionCodeCompatible(String operator, String targetVersion) {
                // exteraGram plugins: app_version_code constraints refer to exteraGram build numbers
                int versionCode = 0;
                try {
                    versionCode = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
                } catch (Exception ignored) {
                }
                return AppUtils.compareVersions(operator, versionCode, Integer.parseInt(targetVersion));
            }

            public final void zipFolder(File sourceDir, File zipFile) throws IOException {
                PluginFiles.zipDirectory(sourceDir, zipFile);
            }

            public final void copyArchiveToPluginsDirectory(final PythonSdkUpdateInfo update, TLRPC.Document document, final boolean autoRestartEngine) {
                final File pythonSdkUpdateFile = getPythonSdkUpdateFile();
                final File pathToAttach = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document);
                Utilities.globalQueue.postRunnable(new Runnable() {
                    @Override
                    public final void run() {
                        PythonPluginsEngine.Updater.Companion.m1572$r8$lambda$Nb4_qHBJ0JtMgKWq7nD16DhLg(pathToAttach, update, pythonSdkUpdateFile, autoRestartEngine);
                    }
                });
            }

            public static void m1572$r8$lambda$Nb4_qHBJ0JtMgKWq7nD16DhLg(File file, PythonSdkUpdateInfo pythonSdkUpdateInfo, File file2, final boolean z) {
                final Ref.BooleanRef booleanRef = new Ref.BooleanRef();
                if (!SdkSignature.verify(file, pythonSdkUpdateInfo.version, pythonSdkUpdateInfo.getAbi(), pythonSdkUpdateInfo.getSignature())) {
                    String channel = pythonSdkUpdateInfo.getChannel();
                    TLRPC.Message message = pythonSdkUpdateInfo.getMessage();
                    FileLog.e("Rejected Python SDK update with an invalid signature (" + channel + ", message id = " + (message != null ? Integer.valueOf(message.id) : null) + ")");
                } else {
                    try {
                        PluginFiles.copy(file, file2, true);
                        PluginFiles.writeTextAtomic(Updater.Companion.getPythonSdkUpdateMetaFile(), pythonSdkUpdateInfo.version + "|" + pythonSdkUpdateInfo.getAbi() + "|" + pythonSdkUpdateInfo.getSignature());
                        booleanRef.element = true;
                    } catch (Exception e) {
                        FileLog.e("Failed to copy plugins-sdk file", e);
                    }
                }
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public final void run() {
                        PythonPluginsEngine.Updater.Companion.m1570$r8$lambda$6ut4Ap5MRyyIRCTmPYnUBe_pW4(booleanRef, z);
                    }
                });
            }

            public static void m1570$r8$lambda$6ut4Ap5MRyyIRCTmPYnUBe_pW4(Ref.BooleanRef booleanRef, boolean z) {
                Updater.isLoading = false;
                if (!booleanRef.element) {
                    Updater.Companion.updateStatus(2);
                } else if (z) {
                    PluginsController.Companion.getInstance().restart();
                } else {
                    Updater.Companion.updateStatus(4);
                }
            }

            public final void savePythonSdkArchive(final PythonSdkUpdateInfo update, final boolean autoRestartEngine) throws IOException {
                TLRPC.Message message = update.getMessage();
                final TLRPC.Document document = update.document;
                if (Updater.isLoading || message == null || document == null) {
                    return;
                }
                MessageObject messageObject = new MessageObject(UserConfig.selectedAccount, message, false, true);
                Updater.isLoading = true;
                updateStatus(3);
                if (!messageObject.mediaExists) {
                    Updater.TAG = DownloadController.getInstance(UserConfig.selectedAccount).generateObserverTag();
                    FileLoader.getInstance(UserConfig.selectedAccount).loadFile(document, messageObject, 1, 0);
                    DownloadController.getInstance(UserConfig.selectedAccount).addLoadingFileObserver(FileLoader.getAttachFileName(document), messageObject, new DownloadController.FileDownloadProgressListener() {
                        @Override
                        public void onProgressDownload(String fileName, long downloadSize, long totalSize) {
                        }

                        @Override
                        public void onProgressUpload(String fileName, long downloadSize, long totalSize, boolean isEncrypted) {
                        }

                        @Override
                        public void onFailedDownload(String fileName, boolean canceled) {
                            FileLog.e("Failed to load plugins-sdk file");
                            PythonPluginsEngine.Updater.isLoading = false;
                            PythonPluginsEngine.Updater.Companion.updateStatus(2);
                        }

                        @Override
                        public void onSuccessDownload(String fileName) {
                            PythonPluginsEngine.Updater.Companion.copyArchiveToPluginsDirectory(update, document, autoRestartEngine);
                        }

                        @Override
                        public int getObserverTag() {
                            return PythonPluginsEngine.Updater.TAG;
                        }
                    });
                    return;
                }
                copyArchiveToPluginsDirectory(update, document, autoRestartEngine);
            }
        }

        private Updater() {
        }
    }
}
