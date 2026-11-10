package com.exteragram.messenger.plugins.analyzer;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * StaticGram: every way a plugin gets onto the device or gets switched on goes through the static
 * analysis while the "Static analysis" setting is on. exteraGram only analyses in its install sheet;
 * here the engines refuse plugin files whose SHA-256 the user has not seen analysed (install sheet,
 * dev server, bundled plugins, plugin code calling the engines), and enabling an installed plugin that
 * was never analysed (installed before the analyzer, with the setting off, or changed on disk since)
 * shows the analysis first. With the setting off nothing here blocks anything.
 * <p>
 * Approved hashes are kept in noBackupFilesDir/plugin_analyzer/approved.json, signed with the
 * analyzer cache keystore key.
 */
public final class PluginAnalysisGate {

    /** Callback text when the user closed the analysis without installing / enabling. */
    public static final String DECLINED = "Installation cancelled by user";
    public static final String NOT_ANALYZED = "Static analysis: this plugin file was not reviewed in the install sheet";
    public static final String NO_ACTIVITY = "Static analysis: open StaticGram to review this plugin";

    private static final int MAX_APPROVED = 512;
    private static final Object lock = new Object();
    private static LinkedHashSet<String> approved;

    private PluginAnalysisGate() {
    }

    /** The analysis is on: the plugins engine and the "Static analysis" setting are both enabled. */
    public static boolean isActive() {
        return ExteraConfig.getPluginsEngine() && ExteraConfig.getPluginsStaticAnalysis();
    }

    // ---- hashes

    public static String sha256(File file) {
        try {
            if (file == null) return null;
            if (file.isDirectory()) return treeHash(file);
            if (file.isFile()) return AnalyzerCache.sha256Hex(file);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    /** Hash of an installed Elyx directory: sorted relative paths with their SHA-256 (no __pycache__). */
    static String treeHash(File root) throws Exception {
        final String rootPath = root.getCanonicalPath() + File.separator;
        final ArrayList<String> lines = new ArrayList<>();
        final ArrayList<File> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            final File dir = stack.remove(stack.size() - 1);
            final File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                final String canonical = child.getCanonicalPath();
                if (!canonical.startsWith(rootPath)) continue;
                if (child.isDirectory()) {
                    if (!"__pycache__".equals(child.getName())) stack.add(child);
                } else if (child.isFile()) {
                    lines.add(canonical.substring(rootPath.length()).replace(File.separatorChar, '/') + "\0" + AnalyzerCache.sha256Hex(child));
                }
            }
            if (lines.size() > 20000) break;
        }
        final String[] sorted = lines.toArray(new String[0]);
        Arrays.sort(sorted);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String line : sorted) {
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return "tree:" + AnalyzerCache.toHex(digest.digest());
    }

    /** What is installed for a plugin: the .py file, or the Elyx plugin directory. */
    public static File installedFile(String pluginId) {
        try {
            final PluginsController controller = PluginsController.Companion.getInstance();
            final File dir = controller.getPluginsDir();
            if (pluginId == null || dir == null) return null;
            final Plugin plugin = controller.getPlugins().get(pluginId);
            if (plugin != null && PluginsConstants.ELYX.equals(plugin.getEngine())) {
                final File elyx = new File(new File(dir, "ElyxPlugins"), pluginId);
                return elyx.isDirectory() ? elyx : null;
            }
            final File py = new File(dir, pluginId + ".py");
            if (py.isFile()) return py;
            final File elyx = new File(new File(dir, "ElyxPlugins"), pluginId);
            return elyx.isDirectory() ? elyx : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ---- approvals

    private static File approvedFile() {
        return new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "plugin_analyzer/approved.json");
    }

    private static LinkedHashSet<String> approvedSet() {
        if (approved != null) return approved;
        approved = new LinkedHashSet<>();
        try {
            final File file = approvedFile();
            if (file.isFile()) {
                final byte[] data;
                try (InputStream in = new FileInputStream(file)) {
                    final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    data = out.toByteArray();
                }
                final String text = new String(data, StandardCharsets.UTF_8);
                final int nl = text.indexOf('\n');
                if (nl > 0) {
                    final String json = text.substring(nl + 1);
                    final String sign = AnalyzerCache.getInstance().sign("approved", "", json);
                    if (sign != null && MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8), text.substring(0, nl).getBytes(StandardCharsets.UTF_8))) {
                        final JSONArray arr = new JSONObject(json).optJSONArray("hashes");
                        if (arr != null) for (int i = 0; i < arr.length(); i++) approved.add(arr.getString(i));
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return approved;
    }

    public static boolean isApproved(String sha) {
        if (sha == null) return false;
        synchronized (lock) {
            return approvedSet().contains(sha);
        }
    }

    public static void approve(String sha) {
        if (sha == null) return;
        synchronized (lock) {
            final LinkedHashSet<String> set = approvedSet();
            set.remove(sha);
            set.add(sha);
            while (set.size() > MAX_APPROVED) set.remove(set.iterator().next());
            try {
                final JSONArray arr = new JSONArray();
                for (String s : set) arr.put(s);
                final String json = new JSONObject().put("hashes", arr).toString();
                final String sign = AnalyzerCache.getInstance().sign("approved", "", json);
                if (sign == null) return;
                final File file = approvedFile();
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                final File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write((sign + "\n" + json).getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(file)) tmp.delete();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /** Engines, before installing {@code file}: null to go on, otherwise the error. */
    public static String checkInstall(File file) {
        if (!isActive()) return null;
        final String sha = sha256(file);
        return isApproved(sha) ? null : NOT_ANALYZED;
    }

    /** Elyx engine after a successful install of an approved archive: the extracted tree is approved too. */
    public static void approveInstalled(String pluginId) {
        if (!isActive()) return;
        approve(sha256(installedFile(pluginId)));
    }

    // ---- installs that do not come from the install sheet (dev server, bundled plugins)

    /**
     * Installs {@code path} (and enables it when {@code enable}) after the user reviewed the analysis in
     * the install sheet. A dev-server update of an installed, reviewed plugin that adds nothing new is
     * installed directly. callback(null or error) runs on the UI thread.
     */
    public static void requestInstall(String path, boolean trusted, boolean enable, Utilities.Callback<String> callback) {
        final Utilities.Callback<String> done = error -> AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) callback.run(error);
        });
        Utilities.globalQueue.postRunnable(() -> {
            final File file = path != null ? new File(path) : null;
            if (file == null || !file.isFile()) {
                done.run("Plugin file is no longer available.");
                return;
            }
            if (!isActive()) {
                installDirect(file, enable, done);
                return;
            }
            final PluginsController.PluginsEngine engine = PluginsController.Companion.getPluginEngine(file);
            String pluginId = null;
            if (engine instanceof PythonPluginsEngine) {
                pluginId = ((PythonPluginsEngine) engine).fetchParameterValue(path, "id");
            }
            final File installed = pluginId != null ? installedFile(pluginId) : null;
            if (installed != null && isApproved(sha256(installed))) {
                final PluginStaticAnalyzer.Report report = PluginStaticAnalyzer.analyze(file, installed, true);
                if (report.error == null && report.hasDiff && report.newCount() == 0) {
                    approve(report.sha256 != null ? report.sha256 : sha256(file));
                    installDirect(file, enable, done);
                    return;
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (fragment == null || fragment.getParentActivity() == null) {
                    done.run(NO_ACTIVITY);
                    return;
                }
                final InstallPluginBottomSheet.PluginInstallParams params = new InstallPluginBottomSheet.PluginInstallParams(path, trusted);
                params.setEnableAfterInstall(enable);
                params.setResultCallback(done);
                PluginsController.Companion.getInstance().showInstallDialog(fragment, params);
            });
        });
    }

    private static void installDirect(File file, boolean enable, Utilities.Callback<String> done) {
        final PluginsController.PluginsEngine engine = PluginsController.Companion.getPluginEngine(file);
        if (engine == null) {
            done.run("Plugin engine is not available");
            return;
        }
        engine.loadPluginFromFile(file.getAbsolutePath(), null, error -> {
            if (error != null || !enable) {
                done.run(error);
                return;
            }
            final String id = engine instanceof PythonPluginsEngine ? ((PythonPluginsEngine) engine).fetchParameterValue(file.getAbsolutePath(), "id") : null;
            if (id == null) {
                done.run(null);
                return;
            }
            PluginsController.Companion.getInstance().setPluginEnabled(id, true, done);
        }, null);
    }

    // ---- enabling an installed plugin

    /**
     * PluginsController.setPluginEnabled(id, true) on the plugins queue: {@code proceed} runs on the plugins
     * queue once the installed plugin is reviewed; otherwise {@code callback} gets the error/DECLINED.
     */
    public static void confirmEnable(String pluginId, Runnable proceed, Utilities.Callback<String> callback) {
        if (!isActive()) {
            proceed.run();
            return;
        }
        final File installed = installedFile(pluginId);
        if (installed == null) {
            proceed.run();
            return;
        }
        final String sha = sha256(installed);
        if (isApproved(sha)) {
            proceed.run();
            return;
        }
        final PluginStaticAnalyzer.Report report = installed.isFile() ? PluginStaticAnalyzer.analyze(installed, null, true) : PluginStaticAnalyzer.analyze(installed);
        if (report.error == null && report.maxLevel() < PluginStaticAnalyzer.LEVEL_CAUTION) {
            approve(sha);
            proceed.run();
            return;
        }
        final Plugin plugin = PluginsController.Companion.getInstance().getPlugins().get(pluginId);
        final String name = plugin != null && plugin.getName() != null ? plugin.getName() : pluginId;
        AndroidUtilities.runOnUIThread(() -> {
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            final Activity activity = fragment != null ? fragment.getParentActivity() : null;
            if (activity == null || activity.isFinishing()) {
                if (callback != null) callback.run(NO_ACTIVITY);
                return;
            }
            showEnableSheet(fragment, name, report, allowed -> {
                if (allowed) {
                    PluginsController.Companion.runOnPluginsQueue(() -> {
                        // the file must still be the one the user reviewed
                        if (sha != null && sha.equals(sha256(installedFile(pluginId)))) {
                            approve(sha);
                            proceed.run();
                        } else if (callback != null) {
                            callback.run(NOT_ANALYZED);
                        }
                    });
                } else if (callback != null) {
                    callback.run(DECLINED);
                }
            });
        });
    }

    private static void showEnableSheet(BaseFragment fragment, String name, PluginStaticAnalyzer.Report report, Utilities.Callback<Boolean> result) {
        final Activity context = fragment.getParentActivity();
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final BottomSheet sheet = new BottomSheet(context, false, rp);
        sheet.fixNavigationBar();
        final boolean[] answered = new boolean[1];
        final Utilities.Callback<Boolean> once = allowed -> {
            if (!answered[0]) {
                answered[0] = true;
                result.run(allowed);
            }
        };
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));

        final TextView title = new TextView(context);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        title.setText(name);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 0));

        final TextView info = new TextView(context);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        info.setText(LocaleController.getString(R.string.PluginAnalysisEnableInfo));
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 14));

        layout.addView(PluginAnalysisViews.createVerdictCard(context, report, rp, () -> PluginAnalysisViews.showReport(context, report, rp)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));

        final boolean risky = report.error != null || report.maxLevel() >= PluginStaticAnalyzer.LEVEL_DANGER;
        final ButtonWithCounterView enable = new ButtonWithCounterView(context, true, rp);
        enable.setRound();
        enable.setText(LocaleController.getString(risky ? R.string.PluginAnalysisConfirmEnable : R.string.Enable), false);
        enable.setOnClickListener(v -> {
            once.run(true);
            sheet.dismiss();
        });
        layout.addView(enable, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 8));

        final ButtonWithCounterView cancel = new ButtonWithCounterView(context, false, rp);
        cancel.setRound();
        cancel.setText(LocaleController.getString(R.string.Cancel), false);
        cancel.setOnClickListener(v -> {
            once.run(false);
            sheet.dismiss();
        });
        layout.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 8));

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(layout);
        sheet.setCustomView(scroll);
        sheet.setOnDismissListener(d -> once.run(false));
        fragment.showDialog(sheet);
    }
}
