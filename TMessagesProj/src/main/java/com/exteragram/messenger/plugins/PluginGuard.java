package com.exteragram.messenger.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BulletinFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * StaticGram plugin antivirus (server: internal/app/pluginguard).
 *
 * Every plugin file is sent to the server before it is installed or enabled
 * (POST {sg_plugin_check_url}, default badges.staticgram.top/plugins/v1/check);
 * the server flags obfuscated (marshal, PyArmor, compiled/encoded code) and
 * backdoored plugins. A flagged file is never installed or enabled, and an
 * installed plugin that gets flagged later (start-up + every 6 h re-check) is
 * switched off. Known-bad hashes are remembered locally so a flagged plugin
 * stays off even offline. Installation fails closed: no verdict, no install.
 */
public final class PluginGuard {

    public static final String MESSAGE_BLOCKED = "Плагин помечен как вредоносный. Установка прервана.";
    public static final String MESSAGE_ENABLE_BLOCKED = "Плагин помечен как вредоносный. Включение запрещено.";
    public static final String MESSAGE_UNAVAILABLE = "Не удалось проверить плагин на вредоносность. Проверьте соединение и попробуйте ещё раз.";

    private static final String DEFAULT_URL = "https://badges.staticgram.top/plugins/v1/check";
    private static final String PREFS = "sg_plugin_guard";
    private static final String KEY_BLOCKED = "blocked";
    private static final int MAX_BYTES = 16 << 20;
    private static final long RECHECK_MS = 6 * 60 * 60 * 1000L;

    private static volatile boolean recheckScheduled;

    public static final class Verdict {
        public final boolean ok;       // the server answered
        public final boolean blocked;
        public final String sha256;
        public boolean removeNow; // StaticGram: status "blocked" = confirmed by an admin, uninstall
        public final String summary;

        Verdict(boolean ok, boolean blocked, String sha256, String summary) {
            this.ok = ok;
            this.blocked = blocked;
            this.sha256 = sha256;
            this.summary = summary;
        }
    }

    private PluginGuard() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> blockedSet() {
        return new HashSet<>(prefs().getStringSet(KEY_BLOCKED, new HashSet<>()));
    }

    private static void remember(String sha, boolean blocked) {
        if (sha == null || sha.isEmpty()) {
            return;
        }
        Set<String> set = blockedSet();
        boolean changed = blocked ? set.add(sha) : set.remove(sha);
        if (changed) {
            prefs().edit().putStringSet(KEY_BLOCKED, set).apply();
        }
    }

    private static String checkUrl() {
        try {
            String url = AppGlobalConfig.getInstance(UserConfig.selectedAccount).sgPluginCheckUrl.get();
            if (url != null && url.startsWith("https://")) {
                return url;
            }
        } catch (Throwable ignore) {
        }
        return DEFAULT_URL;
    }

    private static byte[] read(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() > MAX_BYTES) {
            return null;
        }
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Blocking check; call off the UI thread. */
    public static Verdict checkSync(File file, String name) {
        byte[] data;
        try {
            data = read(file);
        } catch (Exception e) {
            FileLog.e("plugin guard: read", e);
            return new Verdict(false, false, "", "");
        }
        if (data == null) {
            return new Verdict(false, false, "", "");
        }
        String sha = sha256(data);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(checkUrl()).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            if (name != null) {
                conn.setRequestProperty("X-Plugin-Name", URLEncoder.encode(name, "UTF-8"));
            }
            try {
                conn.setRequestProperty("X-SG-User", String.valueOf(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()));
            } catch (Throwable ignore) {
            }
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                return new Verdict(false, blockedSet().contains(sha), sha, "");
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && body.size() < 256 * 1024) {
                    body.write(buf, 0, n);
                }
            }
            JSONObject json = new JSONObject(body.toString("UTF-8"));
            boolean blocked = json.optBoolean("blocked", false);
            remember(sha, blocked);
            Verdict verdict = new Verdict(true, blocked, sha, json.optString("summary", ""));
            verdict.removeNow = blocked && "blocked".equals(json.optString("status", ""));
            return verdict;
        } catch (Exception e) {
            FileLog.e("plugin guard: check", e);
            return new Verdict(false, blockedSet().contains(sha), sha, "");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Install gate: result is null to go on, or the error text to show.
     * Offline it falls back to the local blocklist (only known-bad files are refused).
     */
    public static void checkBeforeInstall(String path, Utilities.Callback<String> result) {
        Utilities.globalQueue.postRunnable(() -> {
            File file = path != null ? new File(path) : null;
            Verdict v = checkSync(file, file != null ? file.getName() : null);
            String error = null;
            if (v.blocked) {
                error = MESSAGE_BLOCKED;
            } else if (!v.ok && file != null) {
                // Server unreachable: fall back to the local blocklist instead of blocking every
                // install (the static analysis sheet still reviews the code before it runs).
                try {
                    byte[] data = read(file);
                    if (data != null && blockedSet().contains(sha256(data))) {
                        error = MESSAGE_BLOCKED;
                    }
                } catch (Exception ignore) {
                }
            }
            String out = error;
            AndroidUtilities.runOnUIThread(() -> result.run(out));
        });
    }

    private static File pythonFile(PluginsController controller, String pluginId) {
        if (controller == null || pluginId == null || controller.getPluginsDir() == null) {
            return null;
        }
        File f = new File(controller.getPluginsDir(), pluginId + ".py");
        return f.isFile() ? f : null;
    }

    /** True when the installed plugin's file is on the local blocklist. */
    public static boolean isLocallyBlocked(PluginsController controller, String pluginId) {
        File f = pythonFile(controller, pluginId);
        if (f == null) {
            return false;
        }
        try {
            byte[] data = read(f);
            return data != null && blockedSet().contains(sha256(data));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Enabling a plugin (runs on the plugins queue): ask the server; offline,
     * fall back to the local blocklist.
     */
    public static boolean blocksEnable(PluginsController controller, String pluginId) {
        File f = pythonFile(controller, pluginId);
        if (f == null) {
            return false;
        }
        Verdict v = checkSync(f, f.getName());
        return v.blocked;
    }

    /** Before the engines load plugins: keep locally known bad plugins off. */
    public static void applyLocalBlocks(PluginsController controller) {
        try {
            Set<String> blocked = blockedSet();
            File dir = controller.getPluginsDir();
            if (blocked.isEmpty() || dir == null) {
                return;
            }
            File[] files = dir.listFiles((d, n) -> n.endsWith(".py"));
            if (files == null) {
                return;
            }
            SharedPreferences.Editor editor = null;
            for (File f : files) {
                byte[] data = read(f);
                if (data != null && blocked.contains(sha256(data))) {
                    String id = f.getName().substring(0, f.getName().length() - 3);
                    if (editor == null) {
                        editor = controller.getPreferences().edit();
                    }
                    editor.putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + id, false);
                }
            }
            if (editor != null) {
                editor.apply();
            }
        } catch (Throwable e) {
            FileLog.e("plugin guard: local blocks", e);
        }
    }

    /** Re-check every installed Python plugin now and every 6 hours. */
    public static void scheduleRecheck() {
        if (recheckScheduled) {
            return;
        }
        recheckScheduled = true;
        Utilities.globalQueue.postRunnable(PluginGuard::recheckLoop, 15000);
    }

    private static void recheckLoop() {
        try {
            recheckInstalled();
        } catch (Throwable e) {
            FileLog.e("plugin guard: recheck", e);
        }
        Utilities.globalQueue.postRunnable(PluginGuard::recheckLoop, RECHECK_MS);
    }

    private static void recheckInstalled() {
        PluginsController controller = PluginsController.Companion.getInstance();
        Map<String, Plugin> plugins = controller.getPlugins();
        if (plugins == null || plugins.isEmpty()) {
            return;
        }
        ArrayList<String> disabled = new ArrayList<>();
        ArrayList<String> removed = new ArrayList<>();
        for (Plugin plugin : new ArrayList<>(plugins.values())) {
            File f = pythonFile(controller, plugin.getId());
            if (f == null) {
                continue;
            }
            Verdict v = checkSync(f, f.getName());
            if (v.blocked) {
                controller.getPreferences().edit().putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + plugin.getId(), false).apply();
                controller.setPluginEnabled(plugin.getId(), false, err -> { });
                if (v.removeNow) {
                    final String id = plugin.getId();
                    AndroidUtilities.runOnUIThread(() -> controller.deletePlugin(id, err -> { }));
                    removed.add(plugin.getName() != null ? plugin.getName() : plugin.getId());
                } else {
                    disabled.add(plugin.getName() != null ? plugin.getName() : plugin.getId());
                }
            }
        }
        if (!removed.isEmpty()) {
            String text = "Вредоносный плагин удалён: " + android.text.TextUtils.join(", ", removed);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, text).show();
                } catch (Throwable ignore) {
                }
            });
        }
        if (!disabled.isEmpty()) {
            String text = "Плагин помечен как вредоносный и выключен: " + android.text.TextUtils.join(", ", disabled);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, text).show();
                } catch (Throwable ignore) {
                }
            });
        }
    }
}
