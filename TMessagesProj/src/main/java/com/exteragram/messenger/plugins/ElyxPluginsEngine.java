package com.exteragram.messenger.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.pip.PipController;
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import com.exteragram.messenger.plugins.ui.components.PluginFileViewer;
import com.exteragram.messenger.plugins.ui.components.RequiredPluginInfo;
import com.exteragram.messenger.plugins.utils.PluginFiles;
import com.exteragram.messenger.plugins.utils.PyObjectUtils;
import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elyx structured plugins (exteraGram SDK 1.4.5.x, docs "Elyx"): ZIP-compatible .elyx / .eaf archives
 * with a refmap, metainfo, any number of modules, assets, localization and bundled wheels.
 *
 * exteraGram implements this engine in its compiled Python SDK (elyxcore._plugin_engine.ElyxEngine, a
 * PluginsEngine registered next to the Python engine). Here the engine is Java (lifecycle, plugin list,
 * install sheet, settings and hooks, reusing PythonPluginsEngine's interpreter and plumbing) and the
 * Python half lives in elyxcore._engine (archive format, extraction, isolated imports, wheels, environment).
 *
 * Installed layout under the plugins directory: ElyxPlugins/&lt;id&gt;/ (project), elyx_local_libs/&lt;id&gt;/
 * (bundled wheels), archives/&lt;id&gt;.&lt;ext&gt; (installed archive, for sharing), elyx_backups/&lt;id&gt;/
 * (previous version while an update is loading).
 */
public final class ElyxPluginsEngine implements PluginsController.PluginsEngine {
    private static final String MODULE = "elyxcore._engine";
    private static final String SAFE_MODE_ENABLE_ERROR = "Plugins cannot be enabled while safe mode is active";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(>=|<=|==|>|<)(.+)$");

    private final ConcurrentHashMap<String, PyObject> pluginInstances = new ConcurrentHashMap<>();
    /** archive path -> password entered in the install flow (kept until the archive is installed) */
    private final ConcurrentHashMap<String, String> pendingPasswords = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public static boolean isArchiveName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : PluginsConstants.ELYX_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static String archiveExtension(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String ext : PluginsConstants.ELYX_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return ".elyx";
    }

    public final ConcurrentHashMap<String, PyObject> getPluginInstances() {
        return this.pluginInstances;
    }

    // region plumbing

    private static PluginsController controller() {
        return PluginsController.Companion.getInstance();
    }

    private static PythonPluginsEngine pythonEngine() {
        PluginsController.PluginsEngine engine = PluginsController.Companion.getEngines().get(PluginsConstants.PYTHON);
        return engine instanceof PythonPluginsEngine ? (PythonPluginsEngine) engine : null;
    }

    /** The Python half can be called: set up and the Python SDK still running (engines shut down in any order). */
    private boolean runtimeAlive() {
        PythonPluginsEngine py = pythonEngine();
        return this.ready && py != null && py.isSdkReady();
    }

    private static PyObject module() throws Exception {
        PythonPluginsEngine py = pythonEngine();
        Python python = py != null ? py.getPythonOrNull() : null;
        if (python == null) {
            throw new Exception("Python interpreter is not initialized");
        }
        return python.getModule(MODULE);
    }

    private static JSONObject call(String function, Object... args) throws Exception {
        PyObject result = module().callAttr(function, args);
        return new JSONObject(result.toString());
    }

    private static String errorOf(JSONObject result) {
        String error = result.optString("error", "");
        return error.length() == 0 ? "Unknown Elyx error" : error;
    }

    private static String optString(JSONObject json, String key) {
        if (json == null || json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, null);
        return value == null || value.length() == 0 ? null : value;
    }

    private static List<String> optStringList(JSONObject json, String key) {
        ArrayList<String> out = new ArrayList<>();
        JSONArray array = json != null ? json.optJSONArray(key) : null;
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, null);
                if (value != null && value.length() > 0) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static void setEnabledPref(String pluginId, boolean enabled) {
        SharedPreferences.Editor editor = controller().getPreferences().edit();
        editor.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, enabled);
        editor.apply();
    }

    private static boolean getEnabledPref(String pluginId) {
        return controller().getPreferences().getBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
    }

    /** Builds the Plugin model from elyxcore._plugin.ElyxPlugin.info(). */
    private static Plugin buildPlugin(JSONObject info) {
        String id = optString(info, "id");
        String name = optString(info, "name");
        Plugin plugin = new Plugin(id, name != null ? name : id);
        plugin.setEngine(PluginsConstants.ELYX);
        String author = optString(info, "author");
        plugin.setAuthor(author != null ? author : LocaleController.getString(R.string.PluginNoAuthor));
        String description = optString(info, "description");
        plugin.setDescription(description != null ? description : LocaleController.getString(R.string.PluginNoDescription));
        plugin.setIcon(optString(info, "icon"));
        String version = optString(info, "version");
        plugin.setVersion(version != null ? version : "1.0.0");
        String appVersion = optString(info, "app_version");
        if (appVersion != null) {
            plugin.setAppVersion(appVersion);
        }
        String sdkVersion = optString(info, "sdk_version");
        if (sdkVersion != null) {
            plugin.setSdkVersion(sdkVersion);
        }
        plugin.setRequirements(optStringList(info, "requirements"));
        return plugin;
    }

    /** Metadata errors plus the host compatibility checks (app_version, sdk_version, legacy min_version). */
    private static String validationError(JSONObject info) {
        List<String> errors = optStringList(info, "errors");
        if (!errors.isEmpty()) {
            return TextUtils.join("\n", errors);
        }
        String error = checkVersion("app version", optString(info, "app_version"), BuildVars.BUILD_VERSION_STRING);
        if (error == null) {
            error = checkVersion("sdk version", optString(info, "sdk_version"), PythonPluginsEngine.Companion.getSDK_VERSION());
        }
        String minVersion = optString(info, "min_version");
        if (error == null && minVersion != null && !AppUtils.compareVersions(">=", BuildVars.BUILD_VERSION_STRING, minVersion.trim())) {
            error = "Plugin requires app version " + minVersion + " or higher. Your app version is " + BuildVars.BUILD_VERSION_STRING;
        }
        return error;
    }

    private static String checkVersion(String what, String expression, String current) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(expression.trim());
        if (!matcher.matches() || matcher.group(1) == null || matcher.group(2) == null) {
            return "Invalid " + what + ": " + expression;
        }
        if (current == null || !AppUtils.compareVersions(matcher.group(1), current, matcher.group(2).trim())) {
            return "Plugin requires " + what + " " + expression + ", but current is " + current;
        }
        return null;
    }

    private static String engineConflict(String pluginId) {
        Plugin installed = controller().getPlugins().get(pluginId);
        if (installed != null && installed.getEngine() != null && !PluginsConstants.ELYX.equals(installed.getEngine())) {
            return "Plugin id '" + pluginId + "' is already used by an installed " + installed.getEngine() + " plugin.";
        }
        return null;
    }

    // endregion

    // region engine lifecycle

    @Override
    public void init(final Runnable callback) {
        // posted: runs after every engine's init() call, so the Python SDK is initialized by then
        PluginsController.Companion.runOnPluginsQueue(() -> {
            try {
                initInternal();
            } catch (Throwable th) {
                FileLog.e("Failed to initialize the Elyx plugins engine", th);
                android.util.Log.e("sgPlugins", "Failed to initialize the Elyx plugins engine", th);
            } finally {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(callback);
                }
            }
        });
    }

    private void initInternal() throws Exception {
        this.ready = false;
        PythonPluginsEngine py = pythonEngine();
        if (py == null || !py.isSdkReady()) {
            return;
        }
        module().callAttr("setup", controller().getPluginsDir().getAbsolutePath());
        this.ready = true;
        // static analysis reads .elyx archives through the installer (same member checks, password)
        com.exteragram.messenger.plugins.analyzer.PluginStaticAnalyzer.archiveReader = path -> this.ready ? call("read_sources", path, this.pendingPasswords.get(path)) : null;
        JSONArray ids = new JSONArray(module().callAttr("installed_ids").toString());
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, null);
            if (id == null) {
                continue;
            }
            try {
                loadInstalled(id, null);
            } catch (Throwable th) {
                FileLog.e("Failed to load Elyx plugin " + id + ". Reason: " + th.getMessage(), th);
                android.util.Log.e("sgPlugins", "Failed to load Elyx plugin " + id, th);
                Plugin plugin = controller().getPlugins().get(id);
                if (plugin == null || !PluginsConstants.ELYX.equals(plugin.getEngine())) {
                    plugin = new Plugin(id, id);
                    plugin.setEngine(PluginsConstants.ELYX);
                    plugin.setAuthor(LocaleController.getString(R.string.PluginNoAuthor));
                }
                plugin.setError(th);
                plugin.setEnabled(false);
                controller().getPlugins().put(id, plugin);
            }
        }
        controller().notifyPluginsChanged();
        FileLog.d("Elyx plugins engine initialized: " + ids.length() + " plugin(s)");
    }

    @Override
    public boolean isEngineAvailable() {
        PythonPluginsEngine py = pythonEngine();
        return this.ready && py != null && py.isEngineAvailable();
    }

    @Override
    public void shutdown(Runnable callback) {
        try {
            for (String id : new ArrayList<>(this.pluginInstances.keySet())) {
                unloadPlugin(id);
            }
            this.pluginInstances.clear();
            if (runtimeAlive()) {
                try {
                    module().callAttr("stop");
                } catch (Throwable th) {
                    FileLog.e("Failed to stop the Elyx runtime", th);
                }
            }
        } catch (Throwable th) {
            FileLog.e(th);
        }
        this.ready = false;
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void checkDevServer() {
        // the Python engine's development server also serves the elyx_* commands
    }

    @Override
    public boolean isPlugin(File file, MessageObject messageObject) {
        // by the file name only: PluginsController.getPluginEngine(File) picks the engine the same way
        return file != null && isArchiveName(file.getName());
    }

    // endregion

    // region load / unload

    private JSONObject readInstalled(String pluginId) throws Exception {
        JSONObject result = call("read_installed", pluginId, null);
        if (!result.optBoolean("ok")) {
            throw new Exception(errorOf(result));
        }
        return result.getJSONObject("plugin");
    }

    /** Registers an installed plugin and, when it was enabled, loads and enables it (PythonPluginsEngine.loadPlugin). */
    private void loadInstalled(String pluginId, PipController.InstallerDelegate delegate) throws Exception {
        JSONObject info = readInstalled(pluginId);
        Plugin metadata = buildPlugin(info);
        if (!pluginId.equals(metadata.getId())) {
            metadata = new Plugin(pluginId, metadata.getName());
            metadata.setEngine(PluginsConstants.ELYX);
        }
        String conflict = engineConflict(pluginId);
        if (conflict != null) {
            throw new Exception(conflict);
        }
        boolean enabled = getEnabledPref(pluginId);
        if (this.pluginInstances.containsKey(pluginId)) {
            unloadPlugin(pluginId);
        }
        metadata.setEnabled(false);
        metadata.setError(null);
        controller().getPlugins().put(pluginId, metadata);
        String error = validationError(info);
        if (error != null) {
            throw new Exception(error);
        }
        if (ExteraConfig.getPluginsSafeMode()) {
            return;
        }
        PythonPluginsEngine py = pythonEngine();
        if (!enabled) {
            if (delegate != null && py != null) {
                py.installDependenciesFor(pluginId, metadata, delegate);
            }
            return;
        }
        try {
            createPluginInstance(pluginId, metadata, delegate);
            setPluginEnabled(pluginId, true, null);
        } catch (Throwable th) {
            setEnabledPref(pluginId, false);
            unloadPlugin(pluginId);
            throw th;
        }
    }

    private void createPluginInstance(String pluginId, Plugin metadata, PipController.InstallerDelegate delegate) throws Exception {
        PythonPluginsEngine py = pythonEngine();
        if (py == null) {
            throw new Exception("Python plugins engine is not available");
        }
        py.requireBasePluginClassForEngine();
        py.installDependenciesFor(pluginId, metadata, delegate);
        PyObject instance;
        controller().getWatchdog().onPluginExecutionStarted(pluginId);
        try {
            instance = module().callAttr("load_plugin", pluginId, null);
        } catch (PyException e) {
            throw new Exception("Failed to load plugin: " + e.getMessage(), e);
        } finally {
            controller().getWatchdog().onPluginExecutionFinished(pluginId);
        }
        if (instance == null) {
            throw new Exception("Failed to create plugin instance: " + pluginId);
        }
        instance.put("id", metadata.getId());
        instance.put("name", metadata.getName());
        instance.put("description", metadata.getDescription());
        instance.put("author", metadata.getAuthor());
        instance.put("version", metadata.getVersion());
        instance.put("icon", metadata.getIcon());
        instance.put("app_version", metadata.getAppVersion());
        instance.put("sdk_version", metadata.getSdkVersion());
        instance.put("requirements", metadata.getRequirements());
        instance.put("enabled", Boolean.FALSE);
        instance.put("initialized", Boolean.FALSE);
        instance.put("error_message", (Object) null);
        this.pluginInstances.put(pluginId, instance);
    }

    public final void unloadPlugin(String pluginId) {
        PythonPluginsEngine.invalidateSettingsCache(pluginId);
        PyObject instance = this.pluginInstances.remove(pluginId);
        Plugin plugin = controller().getPlugins().get(pluginId);
        if (plugin != null) {
            plugin.setEnabled(false);
        }
        try {
            if (instance != null) {
                if (PyObjectUtils.getBoolean(instance, "initialized", false)) {
                    controller().getWatchdog().onPluginExecutionStarted(pluginId);
                    try {
                        instance.callAttr(PluginsConstants.ON_PLUGIN_UNLOAD);
                    } catch (Throwable th) {
                        FileLog.e("Error during on_plugin_unload for " + pluginId, th);
                    } finally {
                        controller().getWatchdog().onPluginExecutionFinished(pluginId);
                    }
                }
                try {
                    if (instance.containsKey("_cleanup")) {
                        instance.callAttr("_cleanup");
                    }
                } catch (Throwable th) {
                    FileLog.e("Error during SDK cleanup for " + pluginId, th);
                }
                try {
                    instance.put("initialized", Boolean.FALSE);
                    instance.put("enabled", Boolean.FALSE);
                } catch (Throwable ignore) {
                }
            }
            if (runtimeAlive()) {
                try {
                    module().callAttr("unload_plugin", pluginId);
                } catch (Throwable th) {
                    FileLog.e("Failed to unload modules of Elyx plugin " + pluginId, th);
                }
            }
        } finally {
            controller().cleanupPlugin(pluginId);
            if (instance != null) {
                try {
                    instance.close();
                } catch (Throwable th) {
                    FileLog.e("Failed to close plugin instance " + pluginId, th);
                }
            }
        }
    }

    @Override
    public void setPluginEnabled(String pluginId, boolean enabled, final Utilities.Callback<String> callback) {
        try {
            Plugin plugin = controller().getPlugins().get(pluginId);
            if (plugin == null) {
                throw new Exception("Plugin not found: " + pluginId);
            }
            if (enabled && ExteraConfig.getPluginsSafeMode()) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(SAFE_MODE_ENABLE_ERROR));
                }
                return;
            }
            PyObject instance = this.pluginInstances.get(pluginId);
            if (enabled && instance == null) {
                createPluginInstance(pluginId, plugin, null);
                instance = this.pluginInstances.get(pluginId);
                if (instance == null) {
                    throw new Exception("Failed to create plugin instance: " + pluginId);
                }
            }
            if ((instance != null && PyObjectUtils.getBoolean(instance, "initialized", false)) == enabled && !plugin.hasError()) {
                if (callback != null) {
                    callback.run(null);
                }
                return;
            }
            if (!enabled) {
                setEnabledPref(pluginId, false);
                unloadPlugin(pluginId);
            } else {
                controller().cleanupPlugin(pluginId);
                controller().getWatchdog().onPluginExecutionStarted(pluginId);
                try {
                    instance.callAttr(PluginsConstants.ON_PLUGIN_LOAD);
                } finally {
                    controller().getWatchdog().onPluginExecutionFinished(pluginId);
                }
                instance.put("initialized", Boolean.TRUE);
                instance.put("error_message", (Object) null);
                plugin.setError(null);
                instance.put("enabled", Boolean.TRUE);
                plugin.setEnabled(true);
                setEnabledPref(pluginId, true);
                controller().loadPluginSettings(pluginId);
            }
            controller().notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            }
        } catch (final Throwable th) {
            FileLog.e("Unexpected error setting enabled state for " + pluginId, th);
            Plugin plugin = controller().getPlugins().get(pluginId);
            if (enabled && plugin != null) {
                plugin.setError(th);
            }
            setEnabledPref(pluginId, false);
            unloadPlugin(pluginId);
            controller().notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(AppUtils.stackTraceToString(th)));
            }
        }
    }

    /** Dev server (elyx_changes): reload an installed plugin from its directory, keeping its enabled state. */
    public final void reloadPlugin(final String pluginId, final Utilities.Callback<String> callback) {
        // StaticGram static analysis: changed files of an enabled plugin are reviewed before they run
        PluginsController.Companion.runOnPluginsQueue(() -> {
            if (!getEnabledPref(pluginId)) {
                reloadPluginInternal(pluginId, callback);
                return;
            }
            com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.confirmEnable(pluginId, () -> reloadPluginInternal(pluginId, callback), callback);
        });
    }

    private void reloadPluginInternal(final String pluginId, final Utilities.Callback<String> callback) {
        PluginsController.Companion.runOnPluginsQueue(() -> {
            String error = null;
            try {
                unloadPlugin(pluginId);
                loadInstalled(pluginId, null);
            } catch (Throwable th) {
                FileLog.e("Failed to reload Elyx plugin " + pluginId, th);
                Plugin plugin = controller().getPlugins().get(pluginId);
                if (plugin != null) {
                    plugin.setError(th);
                }
                error = AppUtils.stackTraceToString(th);
            }
            controller().notifyPluginsChanged();
            if (callback != null) {
                callback.run(error);
            }
        });
    }

    @Override
    public void deletePlugin(String pluginId, final Utilities.Callback<String> callback) {
        unloadPlugin(pluginId);
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            try {
                py.removeDependenciesFor(pluginId);
            } catch (Exception e) {
                FileLog.e("Failed to uninstall dependencies for " + pluginId, e);
            }
        }
        try {
            module().callAttr("delete_plugin", pluginId);
        } catch (Throwable th) {
            FileLog.e("Failed to delete files of Elyx plugin " + pluginId, th);
        }
        if (PluginsController.Companion.isPluginPinned(pluginId)) {
            PluginsController.Companion.setPluginPinned(pluginId, false);
        }
        controller().clearPluginSettingsPreferences(pluginId, true);
        controller().getPlugins().remove(pluginId);
        controller().notifyPluginsChanged();
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null));
        }
    }

    // endregion

    // region install

    @Override
    public void loadPluginFromFile(final String filePath, final Plugin pluginMetadata, final Utilities.Callback<String> callback, final PipController.InstallerDelegate delegate) {
        PluginsController.Companion.runOnPluginsQueue(() -> installFromFile(filePath, pluginMetadata, callback, delegate));
    }

    private void installFromFile(String filePath, Plugin pluginMetadata, Utilities.Callback<String> callback, PipController.InstallerDelegate delegate) {
        PluginsController controller = controller();
        String id = null;
        boolean wasEnabled = false;
        boolean hadOriginal = false;
        boolean installed = false;
        try {
            if (!this.ready) {
                throw new Exception("Elyx plugins engine is not available");
            }
            if (!new File(filePath).exists()) {
                throw new Exception("Plugin file is no longer available. It may have been moved or deleted.");
            }
            // StaticGram static analysis: only an archive the user reviewed is installed
            final String analysisError = com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.checkInstall(new File(filePath));
            if (analysisError != null) {
                throw new Exception(analysisError);
            }
            String password = this.pendingPasswords.get(filePath);
            if (pluginMetadata == null) {
                JSONObject inspected = call("inspect_archive", filePath, password, null);
                if (!inspected.optBoolean("ok")) {
                    throw new Exception(errorOf(inspected));
                }
                JSONObject info = inspected.getJSONObject("plugin");
                String error = validationError(info);
                if (error != null) {
                    throw new Exception(error);
                }
                pluginMetadata = buildPlugin(info);
            }
            id = pluginMetadata.getId();
            String conflict = engineConflict(id);
            if (conflict != null) {
                throw new Exception(conflict);
            }
            wasEnabled = getEnabledPref(id);
            hadOriginal = controller.getPlugins().containsKey(id);
            if (hadOriginal) {
                unloadPlugin(id);
                PythonPluginsEngine py = pythonEngine();
                if (py != null) {
                    py.removeDependenciesFor(id);
                }
            }
            if (delegate != null && delegate.isCancelled()) {
                throw new Exception("Installation cancelled");
            }
            JSONObject result = call("install_archive", filePath, password, null);
            if (!result.optBoolean("ok")) {
                throw new Exception(errorOf(result));
            }
            installed = true;
            if (com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.isActive()) {
                // the archive kept by the installer must still be the reviewed one (no swap while installing)
                PyObject stored = module().callAttr("archive_path", id);
                if (stored == null || com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.checkInstall(new File(stored.toString())) != null) {
                    throw new Exception(com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.NOT_ANALYZED);
                }
                com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.approveInstalled(id);
            }
            loadInstalled(id, delegate);
            module().callAttr("commit_install", id);
            this.pendingPasswords.remove(filePath);
            controller.notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null));
            }
        } catch (final Throwable th) {
            FileLog.e("Unexpected error installing Elyx plugin from " + filePath, th);
            android.util.Log.e("sgPlugins", "Unexpected error installing Elyx plugin from " + filePath, th);
            if (id != null) {
                boolean restored = false;
                if (installed) {
                    try {
                        restored = module().callAttr("rollback_install", id).toBoolean();
                    } catch (Throwable e) {
                        FileLog.e("Failed to roll back Elyx plugin " + id, e);
                    }
                }
                if (restored || (!installed && hadOriginal)) {
                    // the previous version is back in place (or was never replaced): load it again
                    setEnabledPref(id, wasEnabled);
                    try {
                        loadInstalled(id, null);
                    } catch (Exception e) {
                        FileLog.e("Failed to reload original plugin after update failure for " + id, e);
                        Plugin original = controller.getPlugins().get(id);
                        if (original != null) {
                            original.setError(e);
                        }
                    }
                } else if (installed) {
                    controller.cleanupPlugin(id);
                    PythonPluginsEngine py = pythonEngine();
                    if (py != null) {
                        try {
                            py.removeDependenciesFor(id);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                    PyObject instance = this.pluginInstances.remove(id);
                    if (instance != null) {
                        try {
                            instance.close();
                        } catch (Throwable ignore) {
                        }
                    }
                    controller.clearPluginSettingsPreferences(id, true);
                    controller.getPlugins().remove(id);
                }
            }
            controller.notifyPluginsChanged();
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(AppUtils.stackTraceToString(th)));
            }
        }
    }

    @Override
    public void showInstallDialog(final BaseFragment fragment, final InstallPluginBottomSheet.PluginInstallParams params) {
        PluginsController.Companion.runOnPluginsQueue(() -> inspectAndShow(fragment, params, this.pendingPasswords.get(params.getFilePath())));
    }

    private void inspectAndShow(final BaseFragment fragment, final InstallPluginBottomSheet.PluginInstallParams params, final String password) {
        final String filePath = params.getFilePath();
        final String fileName = new File(filePath).getName();
        JSONObject result;
        try {
            if (!this.ready) {
                throw new Exception("Elyx plugins engine is not available");
            }
            result = call("inspect_archive", filePath, password, null);
        } catch (Throwable th) {
            FileLog.e("Failed to read Elyx archive " + filePath, th);
            showError(fragment, fileName, AppUtils.stackTraceToString(th));
            return;
        }
        if (!result.optBoolean("ok")) {
            String kind = result.optString("error_kind", "");
            if ("password_required".equals(kind) || "wrong_password".equals(kind)) {
                final boolean wrong = "wrong_password".equals(kind);
                AndroidUtilities.runOnUIThread(() -> showPasswordDialog(fragment, params, wrong));
            } else {
                showError(fragment, fileName, errorOf(result));
            }
            return;
        }
        if (password != null) {
            this.pendingPasswords.put(filePath, password);
        }
        JSONObject info = result.optJSONObject("plugin");
        String error = info != null ? validationError(info) : "Invalid plugin archive";
        if (error == null) {
            error = engineConflict(optString(info, "id"));
        }
        if (error != null) {
            showError(fragment, optString(info, "name") != null ? optString(info, "name") : fileName, error);
            return;
        }
        final Plugin plugin = buildPlugin(info);
        List<RequiredPluginInfo> requirements = requiredPlugins(info);
        params.setRequiredPlugins(requirements);
        for (RequiredPluginInfo requirement : requirements) {
            if (!requirement.isSatisfied()) {
                params.setBlockedTitle(LocaleController.getString(R.string.PluginRequired));
                params.setBlockedSubtitle(LocaleController.getString(R.string.PluginRequiredMissing));
                break;
            }
        }
        List<String> wheels = optStringList(info, "wheels");
        params.setBundledPackages(wheels.isEmpty() ? null : wheels);
        final String iconFile = optString(info, "custom_icon_file");
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (iconFile != null && params.getIconDrawable() == null) {
                params.setIconDrawable(loadIcon(iconFile));
            }
            try {
                new InstallPluginBottomSheet(fragment, new PluginsController.PluginValidationResult(plugin, null), params).show();
            } catch (Throwable th) {
                FileLog.e("Failed to show the Elyx install sheet", th);
            }
        });
    }

    /** States of the plugins listed in metainfo "requires" ({id, version, url}). */
    private static List<RequiredPluginInfo> requiredPlugins(JSONObject info) {
        JSONArray requires = info.optJSONArray("requires");
        if (requires == null || requires.length() == 0) {
            return Collections.emptyList();
        }
        ArrayList<RequiredPluginInfo> out = new ArrayList<>();
        for (int i = 0; i < requires.length(); i++) {
            JSONObject item = requires.optJSONObject(i);
            String id = optString(item, "id");
            if (id == null) {
                continue;
            }
            RequiredPluginInfo requirement = new RequiredPluginInfo(id);
            requirement.requiredVersion = optString(item, "version");
            requirement.downloadUrl = optString(item, "url");
            Plugin installed = controller().getPlugins().get(id);
            if (installed == null) {
                requirement.state = RequiredPluginInfo.STATE_MISSING;
            } else {
                requirement.name = installed.getName();
                requirement.currentVersion = installed.getVersion();
                if (requirement.requiredVersion != null && (installed.getVersion() == null
                        || !AppUtils.compareVersions(">=", installed.getVersion(), requirement.requiredVersion))) {
                    requirement.state = RequiredPluginInfo.STATE_OUTDATED;
                } else if (!installed.isEnabled()) {
                    requirement.state = RequiredPluginInfo.STATE_DISABLED;
                } else {
                    requirement.state = RequiredPluginInfo.STATE_SATISFIED;
                }
            }
            out.add(requirement);
        }
        return out;
    }

    private static Drawable loadIcon(String path) {
        try {
            File file = new File(path);
            if (path.toLowerCase(Locale.ROOT).endsWith(".svg")) {
                Bitmap bitmap = SvgHelper.getBitmap(file, AndroidUtilities.dp(78), AndroidUtilities.dp(78), false);
                return bitmap != null ? new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap) : null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int size = Math.max(options.outWidth, options.outHeight);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (size / (options.inSampleSize * 2) >= AndroidUtilities.dp(78)) {
                options.inSampleSize *= 2;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            return bitmap != null ? new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap) : null;
        } catch (Throwable th) {
            FileLog.e(th);
            return null;
        }
    }

    private static void showError(final BaseFragment fragment, final String name, final String error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.PluginInstallError, name), LocaleUtils.createCopySpan(fragment), () -> {
                if (AndroidUtilities.addToClipboard(error)) {
                    BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            }).show();
        });
    }

    /** Password prompt for encrypted archives (exteraGram: elyxcore._install.InstallAlert.show_password_dialog). */
    private void showPasswordDialog(final BaseFragment fragment, final InstallPluginBottomSheet.PluginInstallParams params, boolean wrongPassword) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        Context context = activity;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHintText(LocaleController.getString(R.string.LoginPassword));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setBackground(null);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));
        if (wrongPassword) {
            editText.setErrorText(LocaleController.getString(R.string.CheckPasswordWrong));
            TextView errorView = new TextView(context);
            errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
            errorView.setText(LocaleController.getString(R.string.CheckPasswordWrong));
            layout.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
        }
        final String filePath = params.getFilePath();
        final Runnable[] submit = new Runnable[1];
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.EnterPassword));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> submit[0].run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> {
            this.pendingPasswords.remove(filePath);
            dialog.dismiss();
        });
        final AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        submit[0] = () -> {
            String password = editText.getText() != null ? editText.getText().toString() : "";
            if (password.length() == 0) {
                AndroidUtilities.shakeViewSpring(editText);
                return;
            }
            AndroidUtilities.hideKeyboard(editText);
            dialog.dismiss();
            PluginsController.Companion.runOnPluginsQueue(() -> inspectAndShow(fragment, params, password));
        };
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit[0].run();
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
            if (wrongPassword) {
                AndroidUtilities.shakeViewSpring(editText);
            }
        }, 150));
        fragment.showDialog(dialog);
    }

    // endregion

    // region files

    @Override
    public String getPluginPath(String id) {
        try {
            PyObject path = module().callAttr("main_file_path", id);
            if (path != null) {
                return path.toString();
            }
        } catch (Throwable th) {
            FileLog.e(th);
        }
        return new File(new File(controller().getPluginsDir(), "ElyxPlugins"), id).getAbsolutePath();
    }

    @Override
    public boolean canOpenInExternalApp() {
        return true;
    }

    @Override
    public void openInExternalApp(String id) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return;
        }
        File file = new File(getPluginPath(id));
        if (file.isFile()) {
            PluginFileViewer.INSTANCE.open(fragment, file, file.getName());
        }
    }

    @Override
    public void sharePlugin(String id) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        try {
            File tempDir = new File(ApplicationLoader.getFilesDirFixed(), "temp");
            PluginFiles.mkdirs(tempDir);
            PyObject stored = module().callAttr("archive_path", id);
            File target;
            if (stored != null) {
                File source = new File(stored.toString());
                target = new File(tempDir, id + archiveExtension(source.getName()));
                PluginFiles.copy(source, target, true);
            } else {
                target = new File(tempDir, id + ".elyx");
                module().callAttr("make_archive", id, target.getAbsolutePath());
            }
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", target);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setType("application/octet-stream");
            fragment.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            target.deleteOnExit();
        } catch (Throwable th) {
            FileLog.e("Failed to share Elyx plugin " + id, th);
        }
    }

    // endregion

    // region settings (stored by plugin id, shared with the Python engine)

    @Override
    public Object getPluginSetting(String pluginId, String key, Object defaultValue) {
        PythonPluginsEngine py = pythonEngine();
        return py != null ? py.getPluginSetting(pluginId, key, defaultValue) : defaultValue;
    }

    @Override
    public void setPluginSetting(String pluginId, String key, Object value) {
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            py.setPluginSetting(pluginId, key, value);
        }
    }

    @Override
    public void clearPluginSettings(String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            py.clearPluginSettings(pluginId);
        }
    }

    @Override
    public Map<String, ?> getAllPluginSettings(String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        return py != null ? py.getAllPluginSettings(pluginId) : null;
    }

    @Override
    public List<SettingItem> loadPluginSettings(String id) {
        Plugin plugin = null;
        try {
            plugin = controller().getPlugins().get(id);
            PyObject instance = this.pluginInstances.get(id);
            PythonPluginsEngine py = pythonEngine();
            if (controller().isPluginActive(plugin) && instance != null && py != null) {
                PyObject definitions = instance.callAttr(PluginsConstants.CREATE_SETTINGS);
                plugin.setSettingsError(null);
                if (definitions == null) {
                    return null;
                }
                List<PyObject> list = definitions.asList();
                if (list.isEmpty()) {
                    return null;
                }
                return py.parsePySettingDefinitions(list);
            }
            controller().invalidatePluginSettings(id);
            return null;
        } catch (Throwable e) {
            FileLog.e("Failed to load settings for plugin " + id, e);
            if (plugin != null) {
                plugin.setSettingsError(e);
            }
            return null;
        }
    }

    @Override
    public void openPluginSettings(String id, BaseFragment fragment) {
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            py.openPluginSettings(id, fragment);
        }
    }

    @Override
    public void openPluginSettings(Plugin plugin, BaseFragment fragment) {
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            py.openPluginSettings(plugin, fragment);
        }
    }

    @Override
    public void openPluginSetting(Plugin plugin, String linkAlias, BaseFragment fragment) {
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            py.openPluginSetting(plugin, linkAlias, fragment);
        }
    }

    @Override
    public void openPluginSetting(String pluginId, String linkAlias, BaseFragment fragment) {
        PythonPluginsEngine py = pythonEngine();
        if (py != null) {
            py.openPluginSetting(pluginId, linkAlias, fragment);
        }
    }

    // endregion

    // region hooks

    @Override
    public void executeOnAppEvent(String eventType) {
        if (!this.ready || ExteraConfig.getPluginsSafeMode() || this.pluginInstances.isEmpty()) {
            return;
        }
        PyObject event;
        try {
            PythonPluginsEngine py = pythonEngine();
            Python python = py != null ? py.getPythonOrNull() : null;
            if (python == null) {
                return;
            }
            event = python.getModule("base_plugin").get("AppEvent").call(eventType);
        } catch (Throwable th) {
            FileLog.e("Failed to create app event " + eventType, th);
            return;
        }
        try {
            for (Map.Entry<String, PyObject> entry : this.pluginInstances.entrySet()) {
                String id = entry.getKey();
                if (!controller().isPluginActive(id)) {
                    continue;
                }
                controller().getWatchdog().onPluginExecutionStarted(id);
                try {
                    entry.getValue().callAttr(PluginsConstants.ON_APP_EVENT, event);
                } catch (Throwable e) {
                    FileLog.e("Failed to execute app " + eventType + " for " + id, e);
                } finally {
                    controller().getWatchdog().onPluginExecutionFinished(id);
                }
            }
        } finally {
            PyObjectUtils.closeSafely(event);
        }
    }

    @Override
    public PluginsController.HookResult<TLObject> executePreRequestHook(String requestName, int account, TLObject request, String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        if (py == null) {
            return new PluginsController.HookResult<>(request, false, false);
        }
        return py.executePreRequestHookOn(this.pluginInstances.get(pluginId), requestName, account, request, pluginId);
    }

    @Override
    public PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String requestName, int account, TLObject response, TLRPC.TL_error error, String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        if (py == null) {
            return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(response, error), false, false);
        }
        return py.executePostRequestHook(requestName, account, response, error, this.pluginInstances.get(pluginId));
    }

    @Override
    public PluginsController.HookResult<TLRPC.Update> executeUpdateHook(String updateName, int account, TLRPC.Update update, String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        if (py == null) {
            return new PluginsController.HookResult<>(update, false, false);
        }
        return py.executeUpdateHookOn(this.pluginInstances.get(pluginId), updateName, account, update, pluginId);
    }

    @Override
    public PluginsController.HookResult<TLRPC.Updates> executeUpdatesHook(String containerName, int account, TLRPC.Updates updates, String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        if (py == null) {
            return new PluginsController.HookResult<>(updates, false, false);
        }
        return py.executeUpdatesHookOn(this.pluginInstances.get(pluginId), containerName, account, updates, pluginId);
    }

    @Override
    public PluginsController.HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params, String pluginId) {
        PythonPluginsEngine py = pythonEngine();
        if (py == null) {
            return new PluginsController.HookResult<>(params, false, false);
        }
        return py.executeSendMessageHookOn(this.pluginInstances.get(pluginId), account, params, pluginId);
    }

    // endregion
}
