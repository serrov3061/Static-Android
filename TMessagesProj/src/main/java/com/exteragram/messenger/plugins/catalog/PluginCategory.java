package com.exteragram.messenger.plugins.catalog;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Catalog categories (exteraGram 13.1.1 catalog/PluginCategory). The ids are the "category" values of the
 * index and match the 8 categories of @PluginsBot (pluginsbot/categories.py).
 */
public enum PluginCategory {
    APPEARANCE("appearance", R.string.PluginCategoryAppearance, R.drawable.filter_palette, Theme.key_avatar_nameInMessageViolet),
    CHATS("chats", R.string.PluginCategoryChats, R.drawable.filter_all, Theme.key_avatar_nameInMessageBlue),
    MEDIA("media", R.string.PluginCategoryMedia, R.drawable.filter_media, Theme.key_avatar_nameInMessageOrange),
    PRIVACY("privacy", R.string.PluginCategoryPrivacy, R.drawable.filter_lock, Theme.key_avatar_nameInMessageGreen),
    TOOLS("tools", R.string.PluginCategoryTools, R.drawable.filter_work, Theme.key_avatar_nameInMessageRed),
    INTEGRATIONS("integrations", R.string.PluginCategoryIntegrations, R.drawable.filter_bots, Theme.key_avatar_nameInMessageCyan),
    FUN("fun", R.string.PluginCategoryFun, R.drawable.filter_game, Theme.key_avatar_nameInMessagePink),
    LIBRARIES("libraries", R.string.PluginCategoryLibraries, R.drawable.filter_book, Theme.key_windowBackgroundWhiteGrayText);

    public static final int MAX_CATEGORIES = 3;

    private final String id;
    private final int titleRes;
    private final int iconRes;
    private final int colorKey;

    PluginCategory(String id, int titleRes, int iconRes, int colorKey) {
        this.id = id;
        this.titleRes = titleRes;
        this.iconRes = iconRes;
        this.colorKey = colorKey;
    }

    public String getId() {
        return id;
    }

    public int getTitleRes() {
        return titleRes;
    }

    public int getIconRes() {
        return iconRes;
    }

    public int getColorKey() {
        return colorKey;
    }

    /** Comma separated ids -> known categories, without duplicates, at most {@link #MAX_CATEGORIES}. */
    public static List<PluginCategory> parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<PluginCategory> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            for (PluginCategory category : values()) {
                if (category.id.equals(id)) {
                    if (!result.contains(category)) {
                        result.add(category);
                    }
                    break;
                }
            }
            if (result.size() >= MAX_CATEGORIES) {
                break;
            }
        }
        return result;
    }
}
