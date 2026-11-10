package com.exteragram.messenger.backup;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.translator.TranslationProviders;
import com.exteragram.messenger.utils.chats.ChatUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ShareAlert;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Port of exteraGram's backup/PreferencesUtils: SharedPreferences access plus settings backup and restore.
 * <p>
 * Backup = JSON {"exteraconfig":{...},"aiConfig":{...},"pillstackconfig":{...},"mainconfig":{...}} with a
 * whitelist and value validation per key (same as exteraGram), written through {@link InvisibleEncryptor}.
 * StaticGram writes ".staticgram" files and reads both ".staticgram" and exteraGram's ".extera".
 * Never exported: AI services with API keys (aiConfig "services"), AI history, weather location,
 * plugin/icon-pack state and other device-local keys (see excludedExteraKeys / whitelists).
 */
public class PreferencesUtils {

    public static final String BACKUP_EXTENSION = ".staticgram";
    public static final String LEGACY_BACKUP_EXTENSION = ".extera";

    private static PreferencesUtils instance;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> excludedExteraKeys = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "pluginsUnknownSources", "editingIconPackId", "iconPacksLayout", "iconPacksHidden", "updateScheduleTimestamp",
            "sdkUpdateScheduleTimestamp", "selectedService", "selectedServiceId", "lastActivePillId", "customWeatherLocation",
            "customWeatherAddress", "debugCameraMetrics", "forceCompactSavedMusic", "disableApiRequests",
            "disableChatFadeWallpaperBlend", "chatFadeUseWhiteBackground", "heapMonitorEnabled", "heapMonitorLimitMb")));

    private static final BackupItem[] extraExteraKeys = {
            new BackupItem("bottomNavigationBarMode", Integer.class), new BackupItem("mainMenuLayout", String.class),
            new BackupItem("mainMenuHiddenItems", String.class), new BackupItem("targetLangSend", String.class),
            new BackupItem("pluginsEngine", Boolean.class), new BackupItem("pinnedPlugins", Set.class),
            new BackupItem("hideStickerTime", Boolean.class), new BackupItem("tabCounter", Boolean.class),
            new BackupItem("springAnimations", Boolean.class), new BackupItem("aospTransitions", Boolean.class),
            new BackupItem("stickerCornerRoundness", Integer.class), new BackupItem("saveHistory", Boolean.class),
            new BackupItem("responseStreaming", Boolean.class), new BackupItem("temperature", Integer.class),
            new BackupItem("showResponseOnly", Boolean.class), new BackupItem("insertAsQuote", Boolean.class),
            new BackupItem("onDeviceReasoning", Boolean.class), new BackupItem("onDevicePreviewModel", Boolean.class),
            new BackupItem("onDeviceFastModel", Boolean.class), new BackupItem("replaceTelegramEditor", Boolean.class),
            new BackupItem("replaceTelegramSummaries", Boolean.class), new BackupItem("selectedRole", String.class),
            new BackupItem("infiniteScrolling", Boolean.class), new BackupItem("useCurrentLocation", Boolean.class),
            new BackupItem("gramTargetCurrency", String.class), new BackupItem("btcTargetCurrency", String.class),
            new BackupItem("usdTargetCurrency", String.class)};
    private static final BackupItem[] aiConfigKeys = {new BackupItem("roles", String.class)};
    private static final BackupItem[] pillStackConfigKeys = {new BackupItem("activePills", String.class), new BackupItem("hiddenPills", String.class)};
    private static final BackupItem[] mainConfigKeys = {new BackupItem("ChatSwipeAction", Integer.class), new BackupItem("mediaColumnsCount", Integer.class), new BackupItem("bubbleRadius", Integer.class), new BackupItem("fons_size", Integer.class)};
    private static final String[] configs = {"exteraconfig", "aiConfig", "pillstackconfig", "mainconfig"};

    public static PreferencesUtils getInstance() {
        if (instance == null) {
            instance = new PreferencesUtils();
        }
        return instance;
    }

    private static Context getContext() {
        if (ApplicationLoader.applicationContext != null) {
            return ApplicationLoader.applicationContext;
        }
        return AndroidUtilities.getActivity();
    }

    public static SharedPreferences getPreferences(String name) {
        return getContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    /** Resets all exteraGram settings (exteraGram also clears aiConfig and pillstackconfig). */
    public static void clearPreferences() {
        getPreferences("aiConfig").edit().clear().apply();
        PillStackConfig.getEditor().clear().apply();
        ExteraConfig.getEditor().clear().apply();
        ExteraConfig.reloadConfig();
        PillStackConfig.reloadConfig();
    }

    /** StaticGram-settings-20261022-ab12.staticgram, or "&lt;name&gt;-ab12.staticgram" for a user-entered name. */
    public static String generateBackupName(String name) {
        StringBuilder sb = new StringBuilder();
        if (name == null) {
            name = "StaticGram-settings-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        } else {
            name = name.trim().replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_");
            if (name.isEmpty()) {
                name = "backup";
            }
        }
        sb.append(name);
        sb.append("-");
        sb.append(Utilities.generateRandomString(4));
        sb.append(BACKUP_EXTENSION);
        return sb.toString();
    }

    public static boolean isBackupFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(BACKUP_EXTENSION) || lower.endsWith(LEGACY_BACKUP_EXTENSION);
    }

    private BackupItem findBackupItem(String config, String key) {
        if (TextUtils.isEmpty(config) || TextUtils.isEmpty(key)) {
            return null;
        }
        switch (config) {
            case "aiConfig":
                return findBackupItem(aiConfigKeys, key);
            case "pillstackconfig":
                return findBackupItem(pillStackConfigKeys, key);
            case "exteraconfig":
                for (BackupItem item : extraExteraKeys) {
                    if (item.key.equals(key)) {
                        return item;
                    }
                }
                if (excludedExteraKeys.contains(key)) {
                    return null;
                }
                for (BackupItem item : ExteraConfig.getBackupKeys()) {
                    if (item.key.equals(key)) {
                        return item;
                    }
                }
                return null;
            case "mainconfig":
                return findBackupItem(mainConfigKeys, key);
            default:
                return null;
        }
    }

    private BackupItem findBackupItem(BackupItem[] items, String key) {
        for (BackupItem item : items) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        return null;
    }

    private boolean isExpectedValue(String config, String key, Object value) {
        BackupItem item = findBackupItem(config, key);
        if (item == null || value == null) {
            return false;
        }
        JsonElement json = value instanceof JsonElement ? (JsonElement) value : gson.toJsonTree(value);
        try {
            if (item.clazz.equals(Boolean.class)) {
                return json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean();
            }
            if (item.clazz.equals(Float.class)) {
                return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber() && isExpectedFloat(key, json.getAsFloat());
            }
            if (item.clazz.equals(String.class)) {
                return json.isJsonPrimitive() && json.getAsJsonPrimitive().isString() && isExpectedString(config, key, json.getAsString());
            }
            if (item.clazz.equals(Set.class)) {
                return isExpectedStringSet(key, json);
            }
            if (item.clazz.equals(Long.class)) {
                return false;
            }
            Integer exact = getExactInteger(json);
            return exact != null && isExpectedInteger(key, exact);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private Integer getExactInteger(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            try {
                return new BigDecimal(json.getAsString()).intValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Long getExactLong(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            try {
                return new BigDecimal(json.getAsString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
            }
        }
        return null;
    }

    // restored from smali (JADX lost the comparisons)
    private boolean isExpectedFloat(String key, float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            return false;
        }
        switch (key) {
            case "sectionRadius":
            case "avatarCorners":
                return f >= 0f && f <= 28f;
            case "flashIntensity":
            case "flashWarmth":
                return f >= 0f && f <= 1f;
            case "stickerSize":
                return f >= 4f && f <= 20f;
            case "predictiveBackIntensity":
                return f >= 0f && f <= 5f;
            default:
                return false;
        }
    }

    private boolean isExpectedInteger(String key, int i) {
        switch (key) {
            case "titleText":
            case "stickerCornerRoundness":
            case "doubleTapSeekDuration":
                return i >= 0 && i <= 3;
            case "stickerShape":
            case "cameraType":
            case "downloadSpeedBoost":
            case "tabletMode":
            case "stickerTimeMode":
            case "videoMessagesCamera":
            case "transitionAnimation":
            case "dividerStyle":
            case "tabIcons":
            case "iconPack":
            case "glassOutlineStyle":
            case "bottomNavigationBarMode":
            case "bottomButton":
            case "showIdAndDc":
                return i >= 0 && i <= 2;
            case "bubbleRadius":
                return i >= 0 && i <= 17;
            case "ChatSwipeAction":
                return i >= 0 && i <= 5;
            case "mediaColumnsCount":
                return i >= 2 && i <= 9;
            case "fons_size":
                return i >= 12 && i <= 30;
            case "doubleTapAction":
                return i >= 0 && i <= 8;
            case "doNotUseProxy":
                return i >= 0 && i <= 7;
            case "eventType":
                return i >= 0 && i <= 4;
            case "temperature":
                return i >= 0 && i <= 20;
            case "doubleTapActionOutOwner":
                return i >= 0 && i <= 9;
            case "translationProvider":
                return i >= 0 && i <= TranslationProviders.getLastIndex();
            default:
                return false;
        }
    }

    private boolean isExpectedString(String config, String key, String value) {
        if (value == null || value.length() > 1048576) {
            return false;
        }
        switch (key) {
            case "selectedRole":
                return !TextUtils.isEmpty(value) && value.length() <= 256;
            case "swipeActions":
                return value.isEmpty() || value.matches("^\\d{1,2}(,\\d{1,2})*$");
            case "customSavePath":
                return value.matches("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$");
            case "mainMenuLayout":
                return isExpectedMainMenuLayout(value, true);
            case "targetLang":
            case "targetLangSend":
                return value.equalsIgnoreCase("app") || value.matches("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$");
            case "mainMenuHiddenItems":
                return isExpectedMainMenuLayout(value, false);
            default:
                if ("aiConfig".equals(config) && key.equals("roles")) {
                    return isExpectedRoles(value);
                }
                if ("pillstackconfig".equals(config) && (key.equals("activePills") || key.equals("hiddenPills"))) {
                    return isExpectedPillsLayout(value);
                }
                if (key.equals("gramTargetCurrency") || key.equals("btcTargetCurrency") || key.equals("usdTargetCurrency")) {
                    return value.matches("^[A-Z]{3,5}$");
                }
                return !TextUtils.isEmpty(value);
        }
    }

    // restored from smali
    private boolean isExpectedStringSet(String key, JsonElement json) {
        if (!key.equals("pinnedPlugins") || !json.isJsonArray() || json.getAsJsonArray().size() > 1000) {
            return false;
        }
        HashSet<String> set = new HashSet<>();
        for (JsonElement el : json.getAsJsonArray()) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                return false;
            }
            String s = el.getAsString();
            if (TextUtils.isEmpty(s) || s.length() > 255 || !set.add(s)) {
                return false;
            }
        }
        return true;
    }

    private boolean isExpectedMainMenuLayout(String value, boolean allowDividers) {
        JsonElement json;
        try {
            json = gson.fromJson(value, JsonElement.class);
        } catch (Exception e) {
            return false;
        }
        if (json == null || !json.isJsonArray() || json.getAsJsonArray().size() > 100) {
            return false;
        }
        HashSet<Integer> set = new HashSet<>();
        for (JsonElement el : json.getAsJsonArray()) {
            Integer exact = getExactInteger(el);
            if (exact == null) {
                return false;
            }
            if (exact == -1) {
                if (!allowDividers) {
                    return false;
                }
            } else if (!set.add(exact)) {
                return false;
            }
        }
        return true;
    }

    private boolean isExpectedRoles(String value) {
        JsonElement json;
        try {
            json = gson.fromJson(value, JsonElement.class);
        } catch (Exception e) {
            return false;
        }
        if (json == null || !json.isJsonArray() || json.getAsJsonArray().size() > 100) {
            return false;
        }
        HashSet<String> names = new HashSet<>();
        for (JsonElement el : json.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                return false;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!isRequiredString(obj, "name", 256) || !isRequiredString(obj, "prompt", 65536) || !names.add(obj.get("name").getAsString())
                    || (obj.has("emojiId") && !obj.get("emojiId").isJsonNull() && getExactLong(obj.get("emojiId")) == null)) {
                return false;
            }
            if (obj.has("isSuggestion") && !obj.get("isSuggestion").isJsonNull() && (!obj.get("isSuggestion").isJsonPrimitive() || !obj.get("isSuggestion").getAsJsonPrimitive().isBoolean())) {
                return false;
            }
        }
        return true;
    }

    private boolean isRequiredString(JsonObject obj, String key, int maxLength) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isString()
                && !TextUtils.isEmpty(obj.get(key).getAsString()) && obj.get(key).getAsString().length() <= maxLength;
    }

    private boolean isExpectedPillsLayout(String value) {
        if (value.length() > 4096 || TextUtils.isEmpty(value)) {
            return TextUtils.isEmpty(value);
        }
        HashSet<Integer> set = new HashSet<>();
        for (String part : value.split(",")) {
            int id;
            try {
                id = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (id <= 0 || id > 100000 || !set.add(id)) {
                return false;
            }
        }
        return set.size() <= 100;
    }

    /** Writes the backup file to the cache dir and opens the share sheet (send to a chat / Saved Messages). */
    public void exportSettings(BaseFragment fragment) {
        File file = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), generateBackupName(null));
        if (file.exists()) {
            file.delete();
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(getBackup(true));
            writer.flush();
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        ShareAlert alert = new ShareAlert(fragment.getParentActivity(), null, null, null, null, false, null, null, false, false, false, null, null) {
            @Override
            protected void onSend(LongSparseArray<TLRPC.Dialog> dids, int count, TLRPC.TL_forumTopic topic, boolean showToast) {
                if (showToast) {
                    AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.SettingsSaved)).show(), 250);
                }
            }
        };
        alert.setSendingFile(file.getAbsolutePath());
        fragment.showDialog(alert);
    }

    public String getBackup(boolean encode) {
        JsonObject root = new JsonObject();
        for (String config : configs) {
            JsonObject obj = toJsonObject(config, getPreferences(config).getAll());
            if (obj.size() > 0) {
                root.add(config, obj);
            }
        }
        String json = gson.toJson((JsonElement) root);
        return encode ? InvisibleEncryptor.encode(json) : json;
    }

    private JsonObject toJsonObject(String config, Map<String, ?> map) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (isExpectedValue(config, entry.getKey(), entry.getValue())) {
                obj.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
            }
        }
        return obj;
    }

    public void importSettings(File file, Activity activity, INavigationLayout layout) {
        if (!isBackup(file)) {
            return;
        }
        JsonObject root = getJsonObject(file);
        for (String config : configs) {
            importConfig(root, config);
        }
        ExteraConfig.reloadConfig();
        PillStackConfig.reloadConfig();
        reloadMainConfig();
        try {
            com.exteragram.messenger.plugins.PluginsController.getInstance().restart(); // exteraGram plugins: apply restored engine settings
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
        // exteraGram also re-checks on-device AI models (ai stage)
        LocaleController.getInstance().recreateFormatters();
        Theme.reloadAllResources(activity);
        if (layout != null) {
            layout.rebuildAllFragmentViews(false, false);
        }
        NotificationCenter nc = AccountInstance.getInstance(UserConfig.selectedAccount).getNotificationCenter();
        nc.postNotificationName(NotificationCenter.reloadInterface);
        nc.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_CHAT);
        nc.postNotificationName(NotificationCenter.mainUserInfoChanged);
        nc.postNotificationName(NotificationCenter.dialogFiltersUpdated);
    }

    /** exteraGram calls SharedConfig.reloadConfig() (12.10.6); we only re-read the four backed-up mainconfig keys. */
    private static void reloadMainConfig() {
        SharedPreferences prefs = getPreferences("mainconfig");
        if (prefs.contains("fons_size")) {
            SharedConfig.fontSize = prefs.getInt("fons_size", SharedConfig.fontSize);
            SharedConfig.fontSizeIsDefault = false;
        }
        SharedConfig.bubbleRadius = prefs.getInt("bubbleRadius", SharedConfig.bubbleRadius);
        SharedConfig.mediaColumnsCount = prefs.getInt("mediaColumnsCount", SharedConfig.mediaColumnsCount);
        if (prefs.contains("ChatSwipeAction")) {
            SharedConfig.updateChatListSwipeSetting(prefs.getInt("ChatSwipeAction", 0));
        }
    }

    private void importConfig(JsonObject root, String config) {
        JsonObject obj = getConfigObject(root, config);
        if (obj == null) {
            return;
        }
        SharedPreferences.Editor editor = getPreferences(config).edit();
        for (String key : obj.keySet()) {
            JsonElement value = obj.get(key);
            BackupItem item = findBackupItem(config, key);
            if (item == null || !isExpectedValue(config, key, value)) {
                continue;
            }
            if (item.clazz.equals(Boolean.class)) {
                editor.putBoolean(key, value.getAsBoolean());
            } else if (item.clazz.equals(Float.class)) {
                editor.putFloat(key, value.getAsFloat());
            } else if (item.clazz.equals(String.class)) {
                editor.putString(key, value.getAsString());
            } else if (item.clazz.equals(Set.class)) {
                HashSet<String> set = new HashSet<>();
                for (JsonElement el : value.getAsJsonArray()) {
                    set.add(el.getAsString());
                }
                editor.putStringSet(key, set);
            } else {
                editor.putInt(key, value.getAsInt());
            }
            if ("exteraconfig".equals(config) && key.equals("iconPack")) {
                editor.remove("iconPacksLayout");
                editor.remove("iconPacksHidden");
            }
        }
        editor.apply();
    }

    private JsonObject getConfigObject(JsonObject root, String config) {
        if (root == null) {
            return null;
        }
        if (root.has(config) && root.get(config).isJsonObject()) {
            return root.getAsJsonObject(config);
        }
        if (!"mainconfig".equals(config)) {
            return null;
        }
        for (String key : root.keySet()) {
            if (key.matches("^mainconfig\\d+$") && root.get(key).isJsonObject()) {
                return root.getAsJsonObject(key);
            }
        }
        return null;
    }

    public JsonObject getJsonObject(File file) {
        try {
            String content = readAndDecryptFile(file);
            if (content != null) {
                return gson.fromJson(content, JsonObject.class);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private String readAndDecryptFile(File file) throws Exception {
        if (file.length() > 16 * 1024 * 1024) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String content = sb.toString();
            if (InvisibleEncryptor.isEncrypted(content)) {
                content = InvisibleEncryptor.decode(content);
            }
            return content;
        }
    }

    private boolean checkKeys(JsonObject root) {
        for (String config : configs) {
            JsonObject obj = getConfigObject(root, config);
            if (obj == null) {
                continue;
            }
            for (String key : obj.keySet()) {
                JsonElement value = obj.get(key);
                if (isExpectedValue(config, key, value)) {
                    return true;
                }
                FileLog.e("Unexpected value: " + key + " " + value);
            }
        }
        return false;
    }

    public boolean isBackup(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocumentName() == null || !isBackupFileName(messageObject.getDocumentName())) {
            return false;
        }
        String path = ChatUtils.getInstance().getPathToMessage(messageObject);
        return !TextUtils.isEmpty(path) && isBackup(new File(path));
    }

    public boolean isBackup(File file) {
        if (file == null || !isBackupFileName(file.getName()) || !file.exists()) {
            return false;
        }
        JsonObject root = getJsonObject(file);
        return root != null && checkKeys(root);
    }

    public int getDiff(File file) {
        return getDiff(getJsonObject(file));
    }

    public int getDiff(JsonObject backup) {
        if (backup == null) {
            return 0;
        }
        int diff = 0;
        JsonObject current = gson.fromJson(getBackup(false), JsonObject.class);
        for (String name : backup.keySet()) {
            String config = name.matches("^mainconfig\\d+$") ? "mainconfig" : name;
            if (!backup.get(name).isJsonObject()) {
                continue;
            }
            JsonObject obj = backup.getAsJsonObject(name);
            JsonObject currentObj = current != null && current.has(config) ? current.getAsJsonObject(config) : null;
            for (String key : obj.keySet()) {
                JsonElement value = obj.get(key);
                if (currentObj != null && currentObj.has(key) && value.equals(currentObj.get(key))) {
                    continue;
                }
                if (isExpectedValue(config, key, value)) {
                    diff++;
                }
            }
        }
        return diff;
    }

    public static class BackupItem implements Serializable {
        public final String key;
        public final Class<?> clazz;

        public BackupItem(String key, Class<?> clazz) {
            this.key = key;
            this.clazz = clazz;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            return key.equals(((BackupItem) obj).key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }
}
