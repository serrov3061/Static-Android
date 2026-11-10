package com.exteragram.messenger.pillstack.core;

import android.content.SharedPreferences;
import android.os.Looper;
import android.text.TextUtils;

import com.exteragram.messenger.backup.PreferencesUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * exteraGram pill stack settings (Kotlin PillStackConfig.kt), stored in the "pillstackconfig" preferences
 * with exteraGram's keys. No pill is active by default, so the dialogs search field stays as in Telegram
 * until the user enables pills in the preferences.
 */
public final class PillStackConfig {

    private static final SharedPreferences preferences = PreferencesUtils.getPreferences("pillstackconfig");
    private static final SharedPreferences.Editor editor = preferences.edit();
    private static final Object sync = new Object();
    private static final HashSet<Integer> pendingUpdates = new HashSet<>();
    private static List<Integer> activePills = new ArrayList<>();
    private static List<Integer> hiddenPills = new ArrayList<>();
    private static boolean configLoaded;

    static {
        loadConfig();
    }

    private PillStackConfig() {
    }

    public static SharedPreferences getPreferences() {
        return preferences;
    }

    public static SharedPreferences.Editor getEditor() {
        return editor;
    }

    public static boolean getConfigLoaded() {
        return configLoaded;
    }

    public static boolean getUseCurrentLocation() {
        return preferences.getBoolean("useCurrentLocation", true);
    }

    public static void setUseCurrentLocation(boolean value) {
        editor.putBoolean("useCurrentLocation", value).apply();
    }

    public static String getCustomWeatherLocation() {
        return preferences.getString("customWeatherLocation", null);
    }

    public static void setCustomWeatherLocation(String value) {
        editor.putString("customWeatherLocation", value).apply();
    }

    public static String getCustomWeatherAddress() {
        return preferences.getString("customWeatherAddress", null);
    }

    public static void setCustomWeatherAddress(String value) {
        editor.putString("customWeatherAddress", value).apply();
    }

    public static boolean getInfiniteScrolling() {
        return preferences.getBoolean("infiniteScrolling", true);
    }

    public static void setInfiniteScrolling(boolean value) {
        editor.putBoolean("infiniteScrolling", value).apply();
    }

    public static String getGramTargetCurrency() {
        return preferences.getString("gramTargetCurrency", "AUTO");
    }

    public static void setGramTargetCurrency(String value) {
        editor.putString("gramTargetCurrency", value).apply();
    }

    public static String getBtcTargetCurrency() {
        return preferences.getString("btcTargetCurrency", "AUTO");
    }

    public static void setBtcTargetCurrency(String value) {
        editor.putString("btcTargetCurrency", value).apply();
    }

    public static String getUsdTargetCurrency() {
        return preferences.getString("usdTargetCurrency", "AUTO");
    }

    public static void setUsdTargetCurrency(String value) {
        editor.putString("usdTargetCurrency", value).apply();
    }

    public static int getLastActivePillId() {
        return preferences.getInt("lastActivePillId", -1);
    }

    public static void setLastActivePillId(int value) {
        editor.putInt("lastActivePillId", value).apply();
    }

    public static List<Integer> getActivePills() {
        return activePills;
    }

    public static List<Integer> getHiddenPills() {
        return hiddenPills;
    }

    private static ArrayList<Integer> parsePillsList(String value) {
        ArrayList<Integer> list = new ArrayList<>();
        if (TextUtils.isEmpty(value)) {
            return list;
        }
        if (value.startsWith("[")) {
            value = value.replaceAll("[\\[\\]\"]", "");
        }
        for (String part : value.split(",")) {
            try {
                list.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        return list;
    }

    private static String serializePillsList(List<Integer> list) {
        return list.isEmpty() ? "" : TextUtils.join(",", list);
    }

    public static ArrayList<Integer> getDefaultActivePills() {
        return new ArrayList<>();
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            String active = preferences.getString("activePills", null);
            String hidden = preferences.getString("hiddenPills", null);
            if (active != null) {
                activePills = parsePillsList(active);
                hiddenPills = hidden != null ? parsePillsList(hidden) : new ArrayList<>();
            } else {
                activePills = getDefaultActivePills();
                hiddenPills = new ArrayList<>();
                for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
                    if (!activePills.contains(info.id())) {
                        hiddenPills.add(info.id());
                    }
                }
                savePillsLayout();
            }
            sanitizePills();
            configLoaded = true;
        }
    }

    public static void reloadConfig() {
        synchronized (sync) {
            configLoaded = false;
            loadConfig();
        }
    }

    public static void sanitizePills() {
        boolean changed = activePills.removeIf(id -> !PillRegistry.isRegistered(id));
        changed |= hiddenPills.removeIf(id -> !PillRegistry.isRegistered(id));
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            if (!activePills.contains(info.id()) && !hiddenPills.contains(info.id())) {
                hiddenPills.add(info.id());
                changed = true;
            }
        }
        if (changed) {
            savePillsLayout();
        }
    }

    public static void savePillsLayout() {
        editor.putString("activePills", serializePillsList(activePills)).putString("hiddenPills", serializePillsList(hiddenPills)).apply();
    }

    public static void saveLastActivePillId(int id) {
        setLastActivePillId(id);
    }

    static void postOnUiThread(int id, Object... args) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            NotificationCenter.getGlobalInstance().postNotificationName(id, args);
        } else {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(id, args));
        }
    }

    public static void notifySettingsChanged(int... ids) {
        if (ids.length == 0) {
            for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
                pendingUpdates.add(info.id());
            }
            postOnUiThread(NotificationCenter.pillStackSettingsChanged);
            return;
        }
        Object[] args = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) {
            pendingUpdates.add(ids[i]);
            args[i] = ids[i];
        }
        postOnUiThread(NotificationCenter.pillStackSettingsChanged, args);
    }

    public static boolean checkAndClearPendingUpdate(int id) {
        return pendingUpdates.remove(id);
    }

    public static boolean shouldUpdatePill(Object[] args, int... ids) {
        if (args == null || args.length == 0 || ids.length == 0) {
            return true;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                for (int id : ids) {
                    if (id == (Integer) arg) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
