package com.exteragram.messenger.plugins;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * StaticGram: official plugins bundled in assets/official_plugins (index.json + plugin files).
 * They are not preinstalled: the Plugins screen lists the ones that are not installed yet
 * and installs one on tap through the engine's normal install path, then enables it
 * (through the static analysis install sheet while the analysis is on).
 */
public final class OfficialPlugins {

    public static final String ASSETS_DIR = "official_plugins";

    public static final class Entry {
        public final String id;
        public final String file;
        public final String version;
        public final String icon;
        private final String name;
        private final String nameRu;
        private final String description;
        private final String descriptionRu;

        Entry(JSONObject o) {
            id = o.optString("id");
            file = o.optString("file", id + ".py");
            version = o.optString("version");
            icon = o.optString("icon");
            name = o.optString("name", id);
            nameRu = o.optString("name_ru", name);
            description = o.optString("description");
            descriptionRu = o.optString("description_ru", description);
        }

        private static boolean isRu() {
            try {
                String lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
                return "ru".equals(lang) || "uk".equals(lang) || "be".equals(lang) || "kk".equals(lang);
            } catch (Throwable t) {
                return false;
            }
        }

        public String getName() {
            return isRu() ? nameRu : name;
        }

        public String getDescription() {
            return isRu() ? descriptionRu : description;
        }

        public int getIconResId() {
            if (icon == null || icon.isEmpty()) {
                return R.drawable.msg_plugins;
            }
            Context ctx = ApplicationLoader.applicationContext;
            int res = ctx.getResources().getIdentifier(icon, "drawable", ctx.getPackageName());
            return res != 0 ? res : R.drawable.msg_plugins;
        }
    }

    private static volatile List<Entry> entries;

    private OfficialPlugins() {
    }

    public static List<Entry> getAll() {
        List<Entry> list = entries;
        if (list != null) {
            return list;
        }
        ArrayList<Entry> result = new ArrayList<>();
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(ASSETS_DIR + "/index.json")) {
            JSONArray arr = new JSONObject(readAll(in)).getJSONArray("plugins");
            for (int i = 0; i < arr.length(); i++) {
                Entry e = new Entry(arr.getJSONObject(i));
                if (!e.id.isEmpty()) {
                    result.add(e);
                }
            }
        } catch (Throwable t) {
            FileLog.e("official plugins index", t);
        }
        entries = list = Collections.unmodifiableList(result);
        return list;
    }

    /** Bundled plugins that are not installed yet (installed ones are shown in the normal list). */
    public static List<Entry> getNotInstalled() {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : getAll()) {
            if (!PluginsController.Companion.getInstance().getPlugins().containsKey(e.id)) {
                out.add(e);
            }
        }
        return out;
    }

    public static boolean isOfficial(String pluginId) {
        for (Entry e : getAll()) {
            if (e.id.equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    /** Copies the bundled file out of the APK, installs it and enables it. callback(error or null) on the UI thread. */
    public static void install(Entry entry, Utilities.Callback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            File file;
            try {
                File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), ASSETS_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("cannot create " + dir);
                }
                // the engine recognises plugin files by the .plugin extension
                file = new File(dir, entry.id + PluginsConstants.PLUGINS_EXT);
                try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(ASSETS_DIR + "/" + entry.file);
                     OutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> callback.run(String.valueOf(t.getMessage())));
                return;
            }
            if (PluginsController.Companion.getPluginEngine(file) == null) {
                AndroidUtilities.runOnUIThread(() -> callback.run("Plugin engine is not available"));
                return;
            }
            // static analysis: like any other plugin, the bundled one is installed from the install sheet
            // (with "enable after installation" preset); with the analysis off it is installed directly.
            com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.requestInstall(file.getAbsolutePath(), true, true, callback);
        });
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
