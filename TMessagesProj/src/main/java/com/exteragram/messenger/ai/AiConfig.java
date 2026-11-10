package com.exteragram.messenger.ai;

import android.content.SharedPreferences;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.exteragram.messenger.backup.PreferencesUtils;
import com.exteragram.messenger.config.BooleanPref;
import com.exteragram.messenger.config.IntegerPref;
import com.exteragram.messenger.config.StringPref;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

/**
 * Port of exteraGram's AiConfig (Kotlin file facade) to Java.
 *
 * Storage (all local, on the device only):
 * - SharedPreferences "aiConfig": "services" (JSON list of {@link Service}: endpoint URL, model and the user's API key),
 *   "roles" (custom roles), "history" (conversation history), "ai_hint_hidden".
 * - SharedPreferences "exteraconfig" (via BasePref): saveHistory, responseStreaming, temperature, showResponseOnly,
 *   insertAsQuote, onDevice*, selectedRole, replaceTelegramEditor/Summaries; "selectedServiceId" and the legacy
 *   "selectedService" hash are read directly (not registered as backup keys, as exteraGram excludes them).
 *
 * exteraGram's backup (backup/PreferencesUtils) exports only "roles" from "aiConfig": API keys ("services") and the
 * conversation history are never part of a settings backup.
 */
public final class AiConfig {

    /**
     * Template used for a new service form and as the "nothing configured" fallback.
     * It has no API key, so {@link AiController#canUseAI()} stays false until the user adds a key.
     */
    public static final Service DEFAULT_SERVICE = new Service("default", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.5-flash", null);
    public static final Service ON_DEVICE_SERVICE;

    private static final SharedPreferences preferences = PreferencesUtils.getPreferences("aiConfig");
    private static SharedPreferences.Editor editor;

    private static final BooleanPref saveHistory;
    private static final BooleanPref responseStreaming;
    private static final IntegerPref temperature;
    private static final BooleanPref showResponseOnly;
    private static final BooleanPref insertAsQuote;
    private static final BooleanPref onDeviceReasoning;
    private static final BooleanPref onDevicePreviewModel;
    private static final BooleanPref onDeviceFastModel;
    private static final StringPref selectedRole;

    static {
        Service onDevice = new Service("on-device", null, "Gemini Nano", null);
        onDevice.setKind(Service.Kind.ON_DEVICE);
        ON_DEVICE_SERVICE = onDevice;
        migrateLegacyConfig();
        saveHistory = new BooleanPref(true, "saveHistory");
        responseStreaming = new BooleanPref(true, "responseStreaming");
        temperature = new IntegerPref(10, "temperature");
        showResponseOnly = new BooleanPref(false, "showResponseOnly");
        insertAsQuote = new BooleanPref(true, "insertAsQuote");
        onDeviceReasoning = new BooleanPref(false, "onDeviceReasoning");
        onDevicePreviewModel = new BooleanPref(false, "onDevicePreviewModel");
        onDeviceFastModel = new BooleanPref(false, "onDeviceFastModel");
        selectedRole = new StringPref(Suggestions.values()[0].getRole().getName(), "selectedRole");
    }

    private AiConfig() {
    }

    public static void ensureConfigMigrated() {
    }

    public static SharedPreferences getPreferences() {
        return preferences;
    }

    public static synchronized SharedPreferences.Editor getEditor() {
        if (editor == null) {
            editor = preferences.edit();
        }
        return editor;
    }

    /** Old exteraGram builds kept services/roles/history in "pillstackconfig". */
    private static void migrateLegacyConfig() {
        try {
            SharedPreferences legacy = PreferencesUtils.getPreferences("pillstackconfig");
            SharedPreferences.Editor e = preferences.edit();
            ArrayList<String> moved = new ArrayList<>();
            for (String key : new String[]{"services", "roles", "history"}) {
                if (!preferences.contains(key)) {
                    Object value = legacy.getAll().get(key);
                    if (value instanceof String) {
                        e.putString(key, (String) value);
                        moved.add(key);
                    }
                }
            }
            if (moved.isEmpty() || !e.commit()) {
                return;
            }
            SharedPreferences.Editor le = legacy.edit();
            for (String key : moved) {
                le.remove(key);
            }
            le.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean getSaveHistory() {
        return saveHistory.get();
    }

    public static void setSaveHistory(boolean value) {
        saveHistory.set(value);
    }

    public static boolean getResponseStreaming() {
        return responseStreaming.get();
    }

    public static void setResponseStreaming(boolean value) {
        responseStreaming.set(value);
    }

    /** 0..20, temperature = value / 20 * backend max temperature. */
    public static int getTemperature() {
        return temperature.get();
    }

    public static void setTemperature(int value) {
        temperature.set(value);
    }

    public static boolean getShowResponseOnly() {
        return showResponseOnly.get();
    }

    public static void setShowResponseOnly(boolean value) {
        showResponseOnly.set(value);
    }

    public static boolean getInsertAsQuote() {
        return insertAsQuote.get();
    }

    public static void setInsertAsQuote(boolean value) {
        insertAsQuote.set(value);
    }

    public static boolean getOnDeviceReasoning() {
        return onDeviceReasoning.get();
    }

    public static void setOnDeviceReasoning(boolean value) {
        onDeviceReasoning.set(value);
    }

    public static boolean getOnDevicePreviewModel() {
        return onDevicePreviewModel.get();
    }

    public static void setOnDevicePreviewModel(boolean value) {
        onDevicePreviewModel.set(value);
    }

    public static boolean getOnDeviceFastModel() {
        return onDeviceFastModel.get();
    }

    public static void setOnDeviceFastModel(boolean value) {
        onDeviceFastModel.set(value);
    }

    public static boolean getReplaceTelegramEditor() {
        return getReplaceTelegramEditor(UserConfig.selectedAccount);
    }

    /** exteraGram default: replace Telegram's AI editor for non-Premium accounts (only when an AI service is set up). */
    public static boolean getReplaceTelegramEditor(int account) {
        return ExteraConfig.getPreferences().getBoolean("replaceTelegramEditor", !UserConfig.getInstance(account).isPremium());
    }

    public static void setReplaceTelegramEditor(boolean value) {
        ExteraConfig.getEditor().putBoolean("replaceTelegramEditor", value).apply();
    }

    public static boolean getReplaceTelegramSummaries() {
        return getReplaceTelegramSummaries(UserConfig.selectedAccount);
    }

    /** exteraGram default: replace Telegram's summaries/AI translation for non-Premium accounts (only when an AI service is set up). */
    public static boolean getReplaceTelegramSummaries(int account) {
        return ExteraConfig.getPreferences().getBoolean("replaceTelegramSummaries", !UserConfig.getInstance(account).isPremium());
    }

    public static void setReplaceTelegramSummaries(boolean value) {
        ExteraConfig.getEditor().putBoolean("replaceTelegramSummaries", value).apply();
    }

    public static String getSelectedServiceId() {
        return ExteraConfig.getPreferences().getString("selectedServiceId", null);
    }

    public static void setSelectedServiceId(String id) {
        if (id == null) {
            ExteraConfig.getEditor().remove("selectedServiceId").apply();
        } else {
            ExteraConfig.getEditor().putString("selectedServiceId", id).apply();
        }
    }

    private static int getSelectedServiceHash() {
        return ExteraConfig.getPreferences().getInt("selectedService", DEFAULT_SERVICE.getLegacyHash());
    }

    public static String getSelectedRole() {
        return selectedRole.get();
    }

    public static void setSelectedRole(String name) {
        selectedRole.set(name);
    }

    public static Service getSelectedService() {
        ArrayList<Service> services = getServices();
        String selectedId = getSelectedServiceId();
        if (selectedId != null && selectedId.length() != 0) {
            for (Service service : services) {
                if (selectedId.equals(service.getId())) {
                    return service;
                }
            }
            setSelectedServiceId(null);
        }
        if (ExteraConfig.getPreferences().contains("selectedService")) {
            int hash = getSelectedServiceHash();
            Service found = null;
            for (Service service : services) {
                if (service.getLegacyHash() == hash) {
                    found = service;
                    break;
                }
            }
            if (found != null) {
                setSelectedServiceId(found.getId());
            }
            ExteraConfig.getEditor().remove("selectedService").apply();
            if (found != null) {
                return found;
            }
        }
        return services.isEmpty() ? DEFAULT_SERVICE : services.get(0);
    }

    public static void setSelectedServices(Service service) {
        setSelectedServiceId(service.getId());
        ExteraConfig.getEditor().remove("selectedService").apply();
    }

    public static void clearSelectedService() {
        setSelectedServiceId(null);
        ExteraConfig.getEditor().remove("selectedService").apply();
    }

    public static ArrayList<Service> getServices() {
        String json = preferences.getString("services", null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            ArrayList<Service> list = ExteraConfig.getGSON().fromJson(json, new TypeToken<ArrayList<Service>>() {
            }.getType());
            if (list == null) {
                list = new ArrayList<>();
            }
            boolean changed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                Service service = list.get(i);
                if (service == null) {
                    list.remove(i);
                    changed = true;
                } else if (service.ensureId()) {
                    changed = true;
                }
            }
            if (changed) {
                saveServices(list);
            }
            return list;
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public static void saveServices(ArrayList<Service> services) {
        getEditor().putString("services", ExteraConfig.getGSON().toJson(services)).apply();
    }

    public static void saveRoles(ArrayList<Role> roles) {
        getEditor().putString("roles", ExteraConfig.getGSON().toJson(roles)).apply();
    }

    public static ArrayList<Role> getRoles() {
        String json = preferences.getString("roles", null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            ArrayList<Role> list = ExteraConfig.getGSON().fromJson(json, new TypeToken<ArrayList<Role>>() {
            }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public static void setSelectedAiRole(Role role) {
        setSelectedRole(role.getName());
    }

    public static ArrayList<Message> getConversationHistory() {
        String json = preferences.getString("history", null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            ArrayList<Message> list = ExteraConfig.getGSON().fromJson(json, new TypeToken<ArrayList<Message>>() {
            }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    public static void saveConversationHistory(ArrayList<Message> history) {
        getEditor().putString("history", ExteraConfig.getGSON().toJson(history)).apply();
    }

    public static void clearConversationHistory() {
        getEditor().remove("history").apply();
    }

    public static void removeLastFromHistory() {
        ArrayList<Message> history = getConversationHistory();
        if (history.size() >= 2) {
            history.remove(history.size() - 1);
            history.remove(history.size() - 1);
            saveConversationHistory(history);
        }
    }
}
