package com.exteragram.messenger.utils;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.ElyxPluginsEngine;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.preferences.MainPreferencesActivity;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.telegram.ui.LaunchActivity;

/**
 * exteraGram intent/deeplink handler, called from LaunchActivity.handleIntent.
 * Settings links (StaticGram): sg://staticSettings?s=alias[&p=plugin], https://staticgram.top/staticSettings?s=alias,
 * sg://extera opens the preferences root. exteraGram's tg://extera and https://t.me/exteraSettings?s= are still accepted.
 * Plugin repositories (exteraGram 13.1.1 catalog): sg://staticPluginRepo?git=<url>, https://staticgram.top/staticPluginRepo?git=<url>
 * and exteraGram's https://t.me/exteraPluginRepo?git=<url> (?c=<channel> opens the channel, StaticGram has no channel repositories).
 * exteraGram also handles tg://export, feed, support, donate, emoji, user, chat, the plugins safe-mode
 * action and opening .extera/.icons files; .plugin files, Elyx archives (.elyx, .eaf, .elyx.zip, .eaf.zip)
 * and the plugins safe-mode action are handled here.
 */
public final class IntentsController {

    public static final IntentsController INSTANCE = new IntentsController();

    public static final String PLUGINS_SAFE_MODE_ACTION = "com.exteragram.plugins.safemode";

    private IntentsController() {
    }

    /** File extension a plugin engine recognizes in {@code name} (".plugin" or an Elyx archive extension), or null. */
    public static String pluginExtension(String name) {
        if (name == null) {
            return null;
        }
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(PluginsConstants.PLUGINS_EXT)) {
            return PluginsConstants.PLUGINS_EXT;
        }
        return ElyxPluginsEngine.isArchiveName(name) ? ElyxPluginsEngine.archiveExtension(name) : null;
    }

    private static boolean isIconPackName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".icons");
    }

    // content:// URIs often carry the real file name only in OpenableColumns.DISPLAY_NAME
    private static String displayName(Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    // copies a shared file into files/temp (the install sheet reads it from a path)
    public File copyToTempFile(Uri uri) throws IOException {
        return copyToTempFile(uri, PluginsConstants.PLUGINS_EXT);
    }

    public File copyToTempFile(Uri uri, String extension) throws IOException {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "temp");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "temp_file_" + System.currentTimeMillis() + (extension != null ? extension : PluginsConstants.PLUGINS_EXT));
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(file)) {
            if (in == null) {
                throw new IOException("Unable to open " + uri);
            }
            byte[] buffer = new byte[16384];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                // .plugin is one source file; Elyx archives may bundle assets and wheels
                if (total > (PluginsConstants.PLUGINS_EXT.equals(extension) ? 32L : 256L) * 1024 * 1024) {
                    throw new IOException("Plugin file is too large");
                }
                out.write(buffer, 0, read);
            }
        }
        file.deleteOnExit();
        return file;
    }

    private static boolean isPluginRepoLink(String scheme, String host, Uri data) {
        String repo = com.exteragram.messenger.plugins.catalog.PluginRepositoryLinks.LINK_PATH;
        String exteraRepo = com.exteragram.messenger.plugins.catalog.PluginRepositoryLinks.EXTERA_LINK_PATH;
        if ("tg".equals(scheme) || "sg".equals(scheme)) {
            return repo.equalsIgnoreCase(host) || exteraRepo.equalsIgnoreCase(host);
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && host != null && !data.getPathSegments().isEmpty()) {
            String first = data.getPathSegments().get(0);
            return ((host.equals("staticgram.top") || host.equals("www.staticgram.top")) && repo.equals(first))
                    || ((host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog")) && exteraRepo.equals(first));
        }
        return false;
    }

    public boolean handleIntent(Intent intent) {
        if (intent != null && PLUGINS_SAFE_MODE_ACTION.equals(intent.getAction())) {
            // exteraGram plugins: "start plugins in safe mode" action
            ExteraConfig.setPluginsSafeMode(true);
            ExteraConfig.getEditor().putBoolean("pluginsSafeMode", true).apply();
            if (ExteraConfig.getPluginsEngine() && PluginsController.getInstance().isInitialized()) {
                PluginsController.getInstance().restart(true);
            }
            return true;
        }
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return false;
        }
        Uri data = intent.getData();
        if (data == null || data.getScheme() == null) {
            return false;
        }
        String scheme = data.getScheme();
        String host = data.getHost();
        String pluginExtension = ("content".equals(scheme) || "file".equals(scheme)) ? pluginExtension(data.getPath()) : null;
        if (pluginExtension == null && "content".equals(scheme)) {
            pluginExtension = pluginExtension(displayName(data));
        }
        if (pluginExtension == null && ("content".equals(scheme) || "file".equals(scheme))) {
            // exteraGram icon packs: a ".icons" archive opened from another app -> install sheet
            String name = "content".equals(scheme) ? displayName(data) : null;
            if (isIconPackName(data.getPath()) || isIconPackName(name)) {
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (fragment == null) {
                    return false;
                }
                try {
                    com.exteragram.messenger.icons.IconManager.INSTANCE.handleIconPack(fragment, copyToTempFile(data, ".icons").getAbsolutePath());
                } catch (Exception e) {
                    FileLog.e(e);
                }
                return true;
            }
        }
        if (pluginExtension != null) {
            // exteraGram plugins: a .plugin file or an Elyx archive opened from another app -> install sheet (unknown source)
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null) {
                return false;
            }
            try {
                PluginsController.getInstance().showInstallDialog(fragment, copyToTempFile(data, pluginExtension).getAbsolutePath(), false);
            } catch (Exception e) {
                FileLog.e(e);
            }
            return true;
        }
        if (isPluginRepoLink(scheme, host, data)) {
            String git = data.getQueryParameter("git");
            String channel = data.getQueryParameter("c");
            AndroidUtilities.runOnUIThread(() -> {
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (fragment == null) {
                    return;
                }
                if (channel != null && !channel.isEmpty()) {
                    PluginsController.getInstance().openRepositoryLink(fragment, channel);
                } else if (git != null && !git.isEmpty()) {
                    PluginsController.getInstance().openGitRepositoryLink(fragment, git);
                } else {
                    fragment.presentFragment(new com.exteragram.messenger.plugins.catalog.ui.PluginRepositoriesActivity());
                }
            });
            return true;
        }
        if ("tg".equals(scheme) || "sg".equals(scheme)) {
            if ("staticSettings".equalsIgnoreCase(host) || "exteraSettings".equalsIgnoreCase(host)) {
                String alias = data.getQueryParameter("s");
                String page = data.getQueryParameter("page");
                if (page != null) {
                    // StaticGram: sg://staticSettings?page=general|appearance|chats|other|plugins|navigation opens a section directly
                    AndroidUtilities.runOnUIThread(() -> {
                        if (LaunchActivity.instance == null) return;
                        org.telegram.ui.ActionBar.BaseFragment f;
                        switch (page) {
                            case "general": f = new com.exteragram.messenger.preferences.GeneralPreferencesActivity(); break;
                            case "appearance": f = new com.exteragram.messenger.preferences.appearance.AppearancePreferencesActivity(); break;
                            case "chats": f = new com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity(); break;
                            case "other": f = new com.exteragram.messenger.preferences.OtherPreferencesActivity(); break;
                            case "plugins": f = new com.exteragram.messenger.plugins.ui.PluginsActivity(); break;
                            case "navigation": f = new com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity(); break;
                            default: f = new MainPreferencesActivity(); break;
                        }
                        LaunchActivity.instance.presentFragment(f);
                    });
                    return true;
                }
                if (alias == null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (LaunchActivity.instance != null) {
                            LaunchActivity.instance.presentFragment(new MainPreferencesActivity());
                        }
                    });
                } else {
                    SettingsRegistry.getInstance().handleLink(alias, data.getQueryParameter("p"));
                }
                return true;
            }
            if ("extera".equals(host) || "static".equals(host)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.presentFragment(new MainPreferencesActivity());
                    }
                });
                return true;
            }
            return false;
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && host != null
                && !data.getPathSegments().isEmpty()
                && (((host.equals("staticgram.top") || host.equals("www.staticgram.top")) && "staticSettings".equals(data.getPathSegments().get(0)))
                    || ((host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog")) && "exteraSettings".equals(data.getPathSegments().get(0))))) {
            SettingsRegistry.getInstance().handleLink(data.getQueryParameter("s"), data.getQueryParameter("p"));
            return true;
        }
        return false;
    }
}
