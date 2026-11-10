package com.exteragram.messenger.utils.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.util.Pair;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.TabIconsMode;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.LinkedHashMap;

/**
 * exteraGram folder tab icons (FolderIcons.kt): emoticon of a dialog filter -> filter_* drawable.
 * StaticGram: the emoticon comes from the server dialogFilter.emoticon and is cached in "extera_folders"
 * prefs (no DB migration of the dialog_filter table).
 */
public abstract class FolderIcons {
    public static final String DEFAULT_TAB_EMOTICON = "💬"; // 💬
    public static LinkedHashMap<String, Integer> folderIcons = new LinkedHashMap<>();

    static {
        folderIcons.put("🐱", R.drawable.filter_cat);
        folderIcons.put("📕", R.drawable.filter_book);
        folderIcons.put("💰", R.drawable.filter_money);
        folderIcons.put("🎮", R.drawable.filter_game);
        folderIcons.put("💡", R.drawable.filter_light);
        folderIcons.put("👌", R.drawable.filter_like);
        folderIcons.put("🎵", R.drawable.filter_note);
        folderIcons.put("🎨", R.drawable.filter_palette);
        folderIcons.put("✈", R.drawable.filter_travel);
        folderIcons.put("⚽", R.drawable.filter_sport);
        folderIcons.put("⭐", R.drawable.filter_favorite);
        folderIcons.put("🎓", R.drawable.filter_study);
        folderIcons.put("🛫", R.drawable.filter_airplane);
        folderIcons.put("👤", R.drawable.filter_private);
        folderIcons.put("👥", R.drawable.filter_group);
        folderIcons.put("💬", R.drawable.filter_all);
        folderIcons.put("✅", R.drawable.filter_unread);
        folderIcons.put("🤖", R.drawable.filter_bots);
        folderIcons.put("👑", R.drawable.filter_crown);
        folderIcons.put("🌹", R.drawable.filter_flower);
        folderIcons.put("🏠", R.drawable.filter_home);
        folderIcons.put("❤", R.drawable.filter_love);
        folderIcons.put("🎭", R.drawable.filter_mask);
        folderIcons.put("🍸", R.drawable.filter_party);
        folderIcons.put("📈", R.drawable.filter_trade);
        folderIcons.put("💼", R.drawable.filter_work);
        folderIcons.put("🔔", R.drawable.filter_unmuted);
        folderIcons.put("📢", R.drawable.filter_channels);
        folderIcons.put("📁", R.drawable.filter_custom);
        folderIcons.put("📋", R.drawable.filter_setup);
    }

    public static int getIconWidth() {
        return AndroidUtilities.dp(24.0f);
    }

    public static int getPadding() {
        return ExteraConfig.getTabIcons() == TabIconsMode.ICONS_AND_TITLES ? AndroidUtilities.dp(4.0f) : 0;
    }

    public static int getTotalIconWidth() {
        return ExteraConfig.getTabIcons() != TabIconsMode.TITLES_ONLY ? getIconWidth() + getPadding() : 0;
    }

    public static int getTabIcon(String emoticon) {
        if (emoticon != null) {
            String key = emoticon.replace("️", "");
            Integer res = folderIcons.get(key);
            if (res != null) {
                return res;
            }
        }
        return R.drawable.filter_custom;
    }

    // exteraGram: the default emoticon of a folder by its flags, Pair(name, emoticon); empty for custom folders
    public static Pair<String, String> getEmoticonFromFlags(int flags) {
        final int all = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        final int contacts = MessagesController.DIALOG_FILTER_FLAG_CONTACTS;
        final int nonContacts = MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
        final int groups = MessagesController.DIALOG_FILTER_FLAG_GROUPS;
        final int bots = MessagesController.DIALOG_FILTER_FLAG_BOTS;
        final int channels = MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
        final int v1 = flags & all;
        String name;
        String emoticon;
        if ((flags & all) == all) {
            if ((MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ & flags) != 0) {
                name = LocaleController.getString(R.string.FilterNameUnread);
                emoticon = "\u2705";
            } else if ((MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED & flags) != 0) {
                name = LocaleController.getString(R.string.FilterNameNonMuted);
                emoticon = "\uD83D\uDD14";
            } else {
                return new Pair<>("", "");
            }
        } else if ((v1 & contacts) != 0) {
            final int rest = v1 & ~contacts;
            if (rest == 0 || ((rest & nonContacts) != 0 && (rest & ~nonContacts) == 0)) {
                name = LocaleController.getString(R.string.FilterContacts);
                emoticon = "\uD83D\uDC64";
            } else {
                return new Pair<>("", "");
            }
        } else if ((v1 & nonContacts) != 0) {
            if ((v1 & ~nonContacts) == 0) {
                name = LocaleController.getString(R.string.FilterNonContacts);
                emoticon = "\uD83D\uDC64";
            } else {
                return new Pair<>("", "");
            }
        } else if ((v1 & groups) != 0) {
            if ((v1 & ~groups) == 0) {
                name = LocaleController.getString(R.string.FilterGroups);
                emoticon = "\uD83D\uDC65";
            } else {
                return new Pair<>("", "");
            }
        } else if ((v1 & bots) != 0) {
            if ((v1 & ~bots) == 0) {
                name = LocaleController.getString(R.string.FilterBots);
                emoticon = "\uD83E\uDD16";
            } else {
                return new Pair<>("", "");
            }
        } else if ((v1 & channels) != 0) {
            if ((v1 & ~channels) == 0) {
                name = LocaleController.getString(R.string.FilterChannels);
                emoticon = "\uD83D\uDCE2";
            } else {
                return new Pair<>("", "");
            }
        } else {
            return new Pair<>("", "");
        }
        return new Pair<>(name, emoticon);
    }

    public static int getPaddingTab() {
        return AndroidUtilities.dp(24.0f);
    }

    // ----- StaticGram: emoticon cache -----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("extera_folders", Context.MODE_PRIVATE);
    }

    public static String getCachedEmoticon(int account, int filterId) {
        return prefs().getString(account + "_" + filterId, null);
    }

    public static void cacheEmoticon(int account, int filterId, String emoticon) {
        String key = account + "_" + filterId;
        if (emoticon == null || emoticon.isEmpty()) {
            prefs().edit().remove(key).apply();
        } else {
            prefs().edit().putString(key, emoticon).apply();
        }
    }
}
