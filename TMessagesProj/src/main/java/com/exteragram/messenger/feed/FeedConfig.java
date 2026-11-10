package com.exteragram.messenger.feed;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * exteraGram feed settings per account (preferences "feedconfig{account}"): channels excluded from the
 * feed ("All"), whether archived channels are included and (exteraGram 13.1.1) user categories: named sets
 * of channels shown as tabs at the top of the feed. Every change of what the feed shows bumps
 * {@link #getGeneration()}; changes of the category list bump {@link #getCategoriesVersion()}.
 */
public final class FeedConfig {

    private static final FeedConfig[] instances = new FeedConfig[16];
    private static final Object[] lockObjects = new Object[16];

    static {
        for (int i = 0; i < lockObjects.length; i++) {
            lockObjects[i] = new Object();
        }
    }

    private volatile Set<Long> excludedChannels;
    private volatile int generation;
    private volatile boolean includeArchived;
    private final SharedPreferences preferences;
    private volatile List<Category> categories = Collections.emptyList();
    private volatile Category selectedCategory;
    private volatile int categoriesVersion;
    private int nextCategoryId = 2;

    public static FeedConfig getInstance(int num) {
        FeedConfig config = instances[num];
        if (config != null) {
            return config;
        }
        synchronized (lockObjects[num]) {
            config = instances[num];
            if (config == null) {
                config = new FeedConfig(num);
                instances[num] = config;
            }
        }
        return config;
    }

    private FeedConfig(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("feedconfig" + account, 0);
        includeArchived = preferences.getBoolean("includeArchived", false);
        Set<String> stored = preferences.getStringSet("excludedChannels", null);
        if (stored != null) {
            HashSet<Long> set = new HashSet<>();
            for (String s : stored) {
                try {
                    set.add(Long.parseLong(s));
                } catch (NumberFormatException ignore) {
                }
            }
            excludedChannels = set;
        } else {
            excludedChannels = Collections.emptySet();
        }
        categories = readCategories(preferences.getString("categories", null));
        int selectedId = preferences.getInt("selectedCategory", 0);
        int maxId = 0;
        for (Category category : categories) {
            if (category.id == selectedId && !category.isHidden) {
                selectedCategory = category;
            }
            maxId = Math.max(maxId, category.id);
        }
        nextCategoryId = Math.max(preferences.getInt("nextCategoryId", 2), maxId + 1);
    }

    /** A user category: a named set of channels (dialog ids), shown as a feed tab unless hidden. */
    public static final class Category {
        private final int id;
        private final String title;
        private final long emojiId;
        private final Set<Long> channels;
        private final boolean isHidden;

        public Category(int id, String title, long emojiId, Set<Long> channels, boolean isHidden) {
            this.id = id;
            this.title = title;
            this.emojiId = emojiId;
            this.channels = channels;
            this.isHidden = isHidden;
        }

        public int getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public long getEmojiId() {
            return emojiId;
        }

        public Set<Long> getChannels() {
            return channels;
        }

        public boolean getIsHidden() {
            return isHidden;
        }

        Category withHidden(boolean hidden) {
            return new Category(id, title, emojiId, channels, hidden);
        }

        Category withChannels(Set<Long> newChannels) {
            return new Category(id, title, emojiId, newChannels, isHidden);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Category)) {
                return false;
            }
            Category other = (Category) o;
            return id == other.id && Objects.equals(title, other.title) && emojiId == other.emojiId && Objects.equals(channels, other.channels) && isHidden == other.isHidden;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, title, emojiId, channels, isHidden);
        }
    }

    public static final class Snapshot {
        private final boolean includeArchived;
        private final Set<Long> excludedChannels;
        /** Channels of the selected category, null for "All". */
        private final Set<Long> categoryChannels;
        private final int generation;

        public Snapshot(boolean includeArchived, Set<Long> excludedChannels, Set<Long> categoryChannels, int generation) {
            this.includeArchived = includeArchived;
            this.excludedChannels = excludedChannels;
            this.categoryChannels = categoryChannels;
            this.generation = generation;
        }

        public boolean getIncludeArchived() {
            return includeArchived;
        }

        public Set<Long> getExcludedChannels() {
            return excludedChannels;
        }

        public int getGeneration() {
            return generation;
        }

        /** A category shows its channels even when they are hidden from All. */
        public boolean isIncluded(long dialogId) {
            if (categoryChannels != null) {
                return categoryChannels.contains(dialogId);
            }
            return !excludedChannels.contains(dialogId);
        }
    }

    public boolean getIncludeArchived() {
        return includeArchived;
    }

    public synchronized void setIncludeArchived(boolean value) {
        if (includeArchived == value) {
            return;
        }
        includeArchived = value;
        generation++;
        preferences.edit().putBoolean("includeArchived", value).apply();
    }

    public boolean isExcluded(long dialogId) {
        return excludedChannels.contains(dialogId);
    }

    /** Whether the posts of a channel are shown in the current feed tab (All or the selected category). */
    public boolean isShownInFeed(long dialogId) {
        Category category = selectedCategory;
        if (category == null) {
            return !excludedChannels.contains(dialogId);
        }
        return category.channels.contains(dialogId);
    }

    public synchronized void setExcluded(long dialogId, boolean excluded) {
        HashSet<Long> set = new HashSet<>(excludedChannels);
        boolean changed = excluded ? set.add(dialogId) : set.remove(dialogId);
        if (changed) {
            applyExcluded(set);
        }
    }

    public Set<Long> getExcludedSnapshot() {
        return excludedChannels;
    }

    public synchronized void removeExcluded(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        HashSet<Long> set = new HashSet<>(excludedChannels);
        if (set.removeAll(ids)) {
            applyExcluded(set);
        }
    }

    public synchronized void clearExcluded() {
        if (excludedChannels.isEmpty()) {
            return;
        }
        applyExcluded(Collections.emptySet());
    }

    public synchronized void excludeAll(Collection<Long> ids) {
        HashSet<Long> set = new HashSet<>(excludedChannels);
        if (set.addAll(ids)) {
            applyExcluded(set);
        }
    }

    private void applyExcluded(Set<Long> updated) {
        excludedChannels = updated;
        if (selectedCategory == null) {
            generation++;
        }
        HashSet<String> strings = new HashSet<>();
        for (Long id : updated) {
            strings.add(String.valueOf(id));
        }
        preferences.edit().putStringSet("excludedChannels", strings).apply();
    }

    public int getGeneration() {
        return generation;
    }

    public synchronized Snapshot snapshot() {
        Category category = selectedCategory;
        return new Snapshot(includeArchived, excludedChannels, category != null ? category.channels : null, generation);
    }

    // region categories (exteraGram 13.1.1)

    public List<Category> getCategories() {
        return categories;
    }

    public List<Category> getVisibleCategories() {
        ArrayList<Category> visible = new ArrayList<>();
        for (Category category : categories) {
            if (!category.isHidden) {
                visible.add(category);
            }
        }
        return visible;
    }

    public Category getCategory(int id) {
        for (Category category : categories) {
            if (category.id == id) {
                return category;
            }
        }
        return null;
    }

    public Category getSelectedCategory() {
        return selectedCategory;
    }

    /** 0 is "All". */
    public int getSelectedCategoryId() {
        Category category = selectedCategory;
        return category != null ? category.id : 0;
    }

    public int getCategoriesVersion() {
        return categoriesVersion;
    }

    public synchronized boolean selectCategory(int id) {
        Category category = null;
        if (id != 0) {
            category = getCategory(id);
            if (category == null || category.isHidden) {
                return false;
            }
        }
        if (getSelectedCategoryId() == id) {
            return false;
        }
        selectedCategory = category;
        generation++;
        categoriesVersion++;
        preferences.edit().putInt("selectedCategory", id).apply();
        return true;
    }

    /** Creates (id <= 0 or unknown) or replaces a category; keeps its hidden state. */
    public synchronized Category saveCategory(int id, String title, long emojiId, Set<Long> channels) {
        if (id <= 0 || getCategory(id) == null) {
            id = nextCategoryId++;
        }
        Category old = getCategory(id);
        Category category = new Category(id, title, emojiId, new HashSet<>(channels), old != null && old.isHidden);
        ArrayList<Category> updated = new ArrayList<>(categories);
        int index = -1;
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id == id) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            updated.set(index, category);
        } else {
            updated.add(category);
        }
        applyCategories(updated);
        return category;
    }

    public synchronized boolean setCategoryHidden(int id, boolean hidden) {
        for (int i = 0; i < categories.size(); i++) {
            Category category = categories.get(i);
            if (category.id == id) {
                if (category.isHidden == hidden) {
                    return false;
                }
                ArrayList<Category> updated = new ArrayList<>(categories);
                updated.set(i, category.withHidden(hidden));
                applyCategories(updated);
                return true;
            }
        }
        return false;
    }

    public synchronized void deleteCategory(int id) {
        ArrayList<Category> updated = new ArrayList<>();
        for (Category category : categories) {
            if (category.id != id) {
                updated.add(category);
            }
        }
        if (updated.size() != categories.size()) {
            applyCategories(updated);
        }
    }

    public synchronized void reorderCategories(List<Integer> ids) {
        LinkedHashMap<Integer, Category> byId = new LinkedHashMap<>();
        for (Category category : categories) {
            byId.put(category.id, category);
        }
        ArrayList<Category> updated = new ArrayList<>();
        for (Integer id : ids) {
            Category category = byId.get(id);
            if (category != null) {
                updated.add(category);
            }
        }
        for (Category category : categories) {
            if (!ids.contains(category.id)) {
                updated.add(category);
            }
        }
        if (!updated.equals(categories)) {
            applyCategories(updated);
        }
    }

    /** Drops channels (e.g. left or no longer eligible) from every category. */
    public synchronized void removeFromCategories(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        boolean changed = false;
        ArrayList<Category> updated = new ArrayList<>(categories.size());
        for (Category category : categories) {
            boolean contains = false;
            for (Long dialogId : category.channels) {
                if (ids.contains(dialogId)) {
                    contains = true;
                    break;
                }
            }
            if (contains) {
                HashSet<Long> channels = new HashSet<>(category.channels);
                channels.removeAll(ids);
                updated.add(category.withChannels(channels));
                changed = true;
            } else {
                updated.add(category);
            }
        }
        if (changed) {
            applyCategories(updated);
        }
    }

    private void applyCategories(List<Category> updated) {
        Category previous = selectedCategory;
        Category selected = null;
        if (previous != null) {
            for (Category category : updated) {
                if (category.id == previous.id && !category.isHidden) {
                    selected = category;
                    break;
                }
            }
        }
        categories = updated;
        selectedCategory = selected;
        categoriesVersion++;
        int previousId = previous != null ? previous.id : 0;
        int selectedId = selected != null ? selected.id : 0;
        if (previousId != selectedId || !Objects.equals(previous != null ? previous.channels : null, selected != null ? selected.channels : null)) {
            generation++;
        }
        preferences.edit()
            .putString("categories", writeCategories(updated))
            .putInt("selectedCategory", selectedId)
            .putInt("nextCategoryId", nextCategoryId)
            .apply();
    }

    private static List<Category> readCategories(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            ArrayList<Category> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                JSONArray channelsArray = object.optJSONArray("channels");
                HashSet<Long> channels = new HashSet<>();
                if (channelsArray != null) {
                    for (int j = 0; j < channelsArray.length(); j++) {
                        channels.add(channelsArray.getLong(j));
                    }
                }
                list.add(new Category(object.getInt("id"), object.optString("title"), object.optLong("emoji"), channels, object.optBoolean("hidden")));
            }
            return list;
        } catch (Exception e) {
            FileLog.e(e);
            return Collections.emptyList();
        }
    }

    private static String writeCategories(List<Category> list) {
        JSONArray array = new JSONArray();
        try {
            for (Category category : list) {
                JSONObject object = new JSONObject();
                object.put("id", category.id);
                object.put("title", category.title);
                object.put("emoji", category.emojiId);
                JSONArray channels = new JSONArray();
                for (Long dialogId : category.channels) {
                    channels.put(dialogId.longValue());
                }
                object.put("channels", channels);
                if (category.isHidden) {
                    object.put("hidden", true);
                }
                array.put(object);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return array.toString();
    }

    // endregion
}
