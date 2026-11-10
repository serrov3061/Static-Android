package com.exteragram.messenger.utils.chats;

import android.content.SharedPreferences;
import android.util.SparseArray;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * exteraGram: "My role" restriction of a chat folder. A folder that includes groups and/or
 * channels can be limited to chats where the current user is the owner or an admin.
 * StaticGram: modes are stored locally per account (exteraGram also mirrors them to the
 * cloud storage of its API bot, which does not exist on the StaticGram network).
 */
public final class FolderRoles {

    public static final int MODE_NONE = 0;
    public static final int MODE_OWNER = 1;
    public static final int MODE_ADMIN = 2;

    public static final String TYPE_OWNER = "folder_role_owner";
    public static final String TYPE_ADMIN = "folder_role_admin";

    public static final long SPAN_UID_OWNER = Long.MIN_VALUE + 10;
    public static final long SPAN_UID_ADMIN = Long.MIN_VALUE + 11;

    // picker flags (UsersSelectActivity filterFlags), above every DIALOG_FILTER_FLAG_*
    public static final int PICKER_FLAG_OWNER = 1 << 29;
    public static final int PICKER_FLAG_ADMIN = 1 << 30;

    private static final String KEY = "folder_roles";

    public static final class Snapshot {
        public final Map<Integer, Integer> modes;
        public final long updatedAt;

        public Snapshot(Map<Integer, Integer> modes, long updatedAt) {
            this.modes = modes;
            this.updatedAt = updatedAt;
        }
    }

    private static final Snapshot EMPTY = new Snapshot(Collections.emptyMap(), 0);
    private static final FolderRoles[] instances = new FolderRoles[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    private volatile Snapshot snapshot;

    public static FolderRoles getInstance(int account) {
        synchronized (instances) {
            FolderRoles roles = instances[account];
            if (roles == null) {
                roles = new FolderRoles(account);
                instances[account] = roles;
            }
            return roles;
        }
    }

    private FolderRoles(int account) {
        this.account = account;
        Snapshot decoded = decode(getPreferences().getString(KEY, null));
        snapshot = decoded == null ? EMPTY : decoded;
    }

    public static boolean supportsFlags(int filterFlags) {
        return (filterFlags & (MessagesController.DIALOG_FILTER_FLAG_GROUPS | MessagesController.DIALOG_FILTER_FLAG_CHANNELS)) != 0;
    }

    public static int effectiveMode(int filterFlags, int mode) {
        return supportsFlags(filterFlags) ? mode : MODE_NONE;
    }

    public static String chatTypeOf(int mode) {
        if (mode == MODE_OWNER) {
            return TYPE_OWNER;
        } else if (mode == MODE_ADMIN) {
            return TYPE_ADMIN;
        }
        return null;
    }

    public static int modeOf(String chatType) {
        if (TYPE_OWNER.equals(chatType)) {
            return MODE_OWNER;
        } else if (TYPE_ADMIN.equals(chatType)) {
            return MODE_ADMIN;
        }
        return MODE_NONE;
    }

    public static long spanUidOf(int mode) {
        return mode == MODE_OWNER ? SPAN_UID_OWNER : SPAN_UID_ADMIN;
    }

    public static boolean isRoleSpanUid(long uid) {
        return uid == SPAN_UID_OWNER || uid == SPAN_UID_ADMIN;
    }

    public static int toPickerFlags(int mode) {
        if (mode == MODE_OWNER) {
            return PICKER_FLAG_OWNER;
        } else if (mode == MODE_ADMIN) {
            return PICKER_FLAG_ADMIN;
        }
        return 0;
    }

    public static int fromPickerFlags(int flags) {
        if ((flags & PICKER_FLAG_OWNER) != 0) {
            return MODE_OWNER;
        }
        return (flags & PICKER_FLAG_ADMIN) != 0 ? MODE_ADMIN : MODE_NONE;
    }

    public static int withPickerMode(int flags, int mode) {
        return (flags & ~(PICKER_FLAG_OWNER | PICKER_FLAG_ADMIN)) | toPickerFlags(mode);
    }

    private SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(KEY + account, 0);
    }

    public int getMode(int filterId) {
        Integer mode = snapshot.modes.get(filterId);
        return mode != null ? mode : MODE_NONE;
    }

    public boolean hasAny() {
        return !snapshot.modes.isEmpty();
    }

    public boolean allows(int filterId, TLRPC.Chat chat) {
        int mode = getMode(filterId);
        if (mode == MODE_OWNER) {
            return chat.creator;
        } else if (mode == MODE_ADMIN) {
            return ChatObject.hasAdminRights(chat);
        }
        return true;
    }

    public void setMode(int filterId, int filterFlags, int requestedMode) {
        int mode = effectiveMode(filterFlags, requestedMode);
        if (getMode(filterId) == mode) {
            return;
        }
        HashMap<Integer, Integer> modes = pruned(snapshot.modes, filterId);
        if (mode == MODE_NONE) {
            modes.remove(filterId);
        } else {
            modes.put(filterId, mode);
        }
        commit(new Snapshot(modes, System.currentTimeMillis()));
    }

    /** Drops modes of folders that no longer exist. */
    public void onFiltersChanged() {
        UserConfig userConfig = UserConfig.getInstance(account);
        if (!userConfig.isClientActivated() || !userConfig.filtersLoaded) {
            return;
        }
        HashMap<Integer, Integer> modes = pruned(snapshot.modes, null);
        if (modes.size() != snapshot.modes.size()) {
            commit(new Snapshot(modes, System.currentTimeMillis()));
        }
    }

    public void clear() {
        snapshot = EMPTY;
        getPreferences().edit().clear().apply();
    }

    private HashMap<Integer, Integer> pruned(Map<Integer, Integer> modes, Integer keepId) {
        SparseArray<MessagesController.DialogFilter> filters = MessagesController.getInstance(account).dialogFiltersById;
        HashMap<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : modes.entrySet()) {
            int id = entry.getKey();
            if (keepId != null && id == keepId || filters.get(id) != null) {
                result.put(id, entry.getValue());
            }
        }
        return result;
    }

    private void commit(Snapshot newSnapshot) {
        snapshot = newSnapshot;
        getPreferences().edit().putString(KEY, encode(newSnapshot)).apply();
        MessagesController.getInstance(account).sortDialogs(null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> storage.resetAllUnreadCounters(false));
    }

    private static String encode(Snapshot value) {
        try {
            JSONObject modes = new JSONObject();
            for (Map.Entry<Integer, Integer> entry : value.modes.entrySet()) {
                modes.put(String.valueOf(entry.getKey()), entry.getValue().intValue());
            }
            return new JSONObject().put("ts", value.updatedAt).put("f", modes).toString();
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    private static Snapshot decode(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(value);
            JSONObject modesJson = json.optJSONObject("f");
            HashMap<Integer, Integer> modes = new HashMap<>();
            if (modesJson != null) {
                Iterator<String> keys = modesJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int mode = modesJson.optInt(key, MODE_NONE);
                    if (mode == MODE_OWNER || mode == MODE_ADMIN) {
                        modes.put(Integer.parseInt(key), mode);
                    }
                }
            }
            return new Snapshot(modes, json.optLong("ts"));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }
}
