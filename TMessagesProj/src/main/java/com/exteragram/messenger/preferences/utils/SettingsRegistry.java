package com.exteragram.messenger.preferences.utils;

import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.ai.ui.activities.AiPreferencesActivity;
import com.exteragram.messenger.feed.ui.FeedChannelsActivity;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.ui.PluginsInfoActivity;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.GeneralPreferencesActivity;
import com.exteragram.messenger.preferences.MainPreferencesActivity;
import com.exteragram.messenger.preferences.OtherPreferencesActivity;
import com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity;
import com.exteragram.messenger.preferences.appearance.AppearancePreferencesActivity;
import com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity;
import com.exteragram.messenger.preferences.chats.SwipeActionsPreferencesActivity;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index of exteraGram settings: entries for the Telegram settings search and
 * link aliases (https://t.me/exteraSettings?s=alias) to open a setting directly.
 * Items register themselves through UItem.setSearchable()/setLinkAlias() while a
 * preference screen fills its list; the index is built by creating every screen once.
 */
public class SettingsRegistry {

    // StaticGram: exteraGram's https://t.me/exteraSettings?s= links are generated on our domain
    public static final String SETTINGS_LINK = "https://staticgram.top/staticSettings?s=";
    public static final String BADGE_ALIAS = "badge";
    public static final String BADGE_DEEPLINK = "sg://settings/extera/badge";
    public static final String SETTINGS_DEEPLINK = "sg://staticSettings?s=";

    private static final Map<Class<? extends BaseFragment>, Integer> categoriesIcons;

    static {
        LinkedHashMap<Class<? extends BaseFragment>, Integer> map = new LinkedHashMap<>();
        map.put(MainPreferencesActivity.class, R.drawable.extera_outline);
        map.put(GeneralPreferencesActivity.class, R.drawable.msg_media);
        map.put(AppearancePreferencesActivity.class, R.drawable.msg_theme);
        map.put(ChatsPreferencesActivity.class, R.drawable.msg_discussion);
        map.put(PluginsInfoActivity.class, R.drawable.msg_plugins);
        map.put(OtherPreferencesActivity.class, R.drawable.msg_fave);
        map.put(AiPreferencesActivity.class, R.drawable.msg_bot);
        map.put(AppNavigationPreferencesActivity.class, R.drawable.msg_list);
        map.put(PillStackPreferencesActivity.class, R.drawable.outline_header_search);
        map.put(FeedChannelsActivity.class, R.drawable.ic_feed);
        map.put(SwipeActionsPreferencesActivity.class, R.drawable.menu_reply);
        map.put(com.exteragram.messenger.preferences.chats.InlineResultsPreferencesActivity.class, R.drawable.msg_calculator); // exteraGram 13.1.1
        map.put(com.exteragram.messenger.preferences.BadgePreferencesActivity.class, R.drawable.ic_sg_badge); // StaticGram: «Avatar frame» lives here
        categoriesIcons = Collections.unmodifiableMap(map);
    }

    public static List<String> newFeatures = Collections.unmodifiableList(Arrays.asList(
            "customSavePath", "disableNotificationDelay", "Camera-ExtendedSettings-StartWithWideAngle", "zoomSlider", "widePosts",
            "swipeActions", "swipeActionsLoop", "swipeActionsReversed", "aiFeatures", "inlineMathResult", "stickerTime",
            "hideDialogsSearchBar", "Appearance-M3Styles-ChatHeader", "Appearance-M3Styles-NavigationBar",
            "Appearance-M3Styles-FloatingButton", "Appearance-Sections", "glassOutlineStyle", "glassMessageMenu",
            "feedBottomTab", "aiTemperature", "AI-Service-Reasoning"));

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private boolean entriesFetched;
    private String entriesLangCode;
    private final ConcurrentHashMap<Integer, Entry> preparedEntries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> entriesStringAlias = new ConcurrentHashMap<>();

    private static final class SingletonHolder {
        private static final SettingsRegistry INSTANCE = new SettingsRegistry();
    }

    public static SettingsRegistry getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private static String describe(UItem item) {
        return String.format("(UItem ID: %s; View type: %s; View: %s; Text: %s; Subtext: %s)", item.id, item.viewType,
                item.view == null ? null : item.view.getClass().getName(), item.text, TextUtils.concat(item.subtext, item.animatedText));
    }

    public static boolean isValidForSearch(UItem item) {
        if (item.id != 0 && !TextUtils.isEmpty(item.text)) {
            return true;
        }
        FileLog.e("[Extera] UItems with ID 0 or empty text cannot be added as search result. " + describe(item));
        return false;
    }

    public static boolean isValidForLinkAliases(UItem item) {
        if (item.id != 0) {
            return true;
        }
        FileLog.e("[Extera] Cannot set link aliases for UItems with ID 0. " + describe(item));
        return false;
    }

    public void addSearchEntry(BaseFragment fragment, UItem item) {
        if (!isValidForSearch(item)) {
            return;
        }
        Entry entry = Entry.fromUItem(fragment, item);
        preparedEntries.putIfAbsent(entry.guid, entry);
    }

    /** "New" badge for a feature alias: shown for one day after the first display, then never again. */
    public static boolean markAsNewFeature(String alias) {
        if (!newFeatures.contains(alias) || ExteraConfig.getDoNotMarkAsNew().contains(alias)) {
            return false;
        }
        Long shownAt = ExteraConfig.getNewFeaturesShowedAt().get(alias);
        if (shownAt == null || shownAt == 0) {
            ExteraConfig.getNewFeaturesShowedAt().put(alias, System.currentTimeMillis());
            ExteraConfig.getEditor().putString("newFeaturesShowedAt", ExteraConfig.getGSON().toJson(ExteraConfig.getNewFeaturesShowedAt())).apply();
            return true;
        }
        if (Math.abs(System.currentTimeMillis() - shownAt) <= DAY_MS) {
            return true;
        }
        ExteraConfig.getNewFeaturesShowedAt().remove(alias);
        ExteraConfig.getEditor().putString("newFeaturesShowedAt", ExteraConfig.getGSON().toJson(ExteraConfig.getNewFeaturesShowedAt()));
        ExteraConfig.getDoNotMarkAsNew().add(alias);
        ExteraConfig.getEditor().putString("doNotMarkAsNew", ExteraConfig.getGSON().toJson(ExteraConfig.getDoNotMarkAsNew())).apply();
        return false;
    }

    public void addLinkAliasForOption(String alias, BaseFragment fragment, UItem item) {
        if (!isValidForLinkAliases(item)) {
            return;
        }
        if (markAsNewFeature(alias) && item.text != null && item.text.length() > 0 && item.text.toString().charAt(item.text.toString().length() - 1) != 'd') {
            item.text = LocaleUtils.applyNewSpan(item.text.toString());
        }
        if (entriesStringAlias.containsKey(alias)) {
            return;
        }
        Entry entry = preparedEntries.get(generateGUIDForUItem(fragment.getClass(), item));
        if (entry == null) {
            entry = Entry.fromUItem(fragment, item);
        }
        entriesStringAlias.put(alias, entry);
    }

    /** Opens the setting for a link alias; with a plugin id the plugin's settings are opened instead. */
    public void handleLink(String alias, String pluginId) {
        if (!TextUtils.isEmpty(pluginId)) {
            PluginsController.openPluginSettings(pluginId, alias);
            return;
        }
        if (BADGE_ALIAS.equals(alias)) {
            // StaticGram: badge settings screen (not a search entry): sg://staticSettings?s=badge, sg://settings/extera/badge
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (LaunchActivity.instance == null) {
                    return;
                }
                // a badge screen already in the stack: go back to it instead of nesting another copy
                org.telegram.ui.ActionBar.INavigationLayout layout = LaunchActivity.instance.getActionBarLayout();
                java.util.List<BaseFragment> stack = layout != null ? layout.getFragmentStack() : null;
                if (stack != null && !stack.isEmpty()) {
                    int idx = -1;
                    for (int i = stack.size() - 1; i >= 0; i--) {
                        if (stack.get(i) instanceof com.exteragram.messenger.preferences.BadgePreferencesActivity) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx >= 0) {
                        int last = stack.size() - 1;
                        if (idx < last) {
                            for (int i = last - 1; i > idx; i--) {
                                layout.removeFragmentFromStack(stack.get(i));
                            }
                            stack.get(stack.size() - 1).finishFragment();
                        }
                        return;
                    }
                }
                LaunchActivity.instance.presentFragment(new com.exteragram.messenger.preferences.BadgePreferencesActivity());
            });
            return;
        }
        createEntriesIfNeeded();
        Entry entry = entriesStringAlias.get(alias);
        if (entry == null && com.exteragram.messenger.preferences.BadgePreferencesActivity.AVATAR_FRAME_ALIAS.equals(alias)) {
            // StaticGram: the badge screen registers its items only once opened; open it at the «Avatar frame» row
            openActivity(com.exteragram.messenger.preferences.BadgePreferencesActivity.class, com.exteragram.messenger.preferences.BadgePreferencesActivity.ID_FRAME);
            return;
        }
        if (entry == null) {
            onSettingNotFound();
            return;
        }
        openActivity(entry.fragmentClass, entry.itemId);
    }

    public void onSettingNotFound() {
        onSettingNotFound(LaunchActivity.getLastFragment());
    }

    public void onSettingNotFound(BaseFragment fragment) {
        BulletinFactory.of(fragment).createEmojiBulletin("🤷‍♂️", LocaleController.getString(R.string.NoSuchSetting)).show();
    }

    public String getFirstSettingLink(Class<? extends BaseFragment> cls, UItem item) {
        int guid = generateGUIDForUItem(cls, item);
        for (Map.Entry<String, Entry> e : entriesStringAlias.entrySet()) {
            if (e.getValue().guid == guid) {
                return SETTINGS_LINK + e.getKey();
            }
        }
        return null;
    }

    public ProfileActivity.SearchAdapter.SearchResult[] getSearchResults() {
        createEntriesIfNeeded();
        ProfileActivity.SearchAdapter.SearchResult[] results = new ProfileActivity.SearchAdapter.SearchResult[preparedEntries.size()];
        int i = 0;
        for (Entry entry : preparedEntries.values()) {
            if (i >= results.length) {
                break;
            }
            results[i++] = entry.toSearchResult();
        }
        return i == results.length ? results : Arrays.copyOf(results, i);
    }

    private int getCategoryIcon(Class<?> cls) {
        Integer icon = categoriesIcons.get(cls);
        return icon != null ? icon : 0;
    }

    private BaseFragment initiateFragment(Class<? extends BaseFragment> cls) {
        try {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last == null) {
                return null;
            }
            BaseFragment fragment = cls.getDeclaredConstructor().newInstance();
            fragment.setParentFragment(last);
            fragment.createActionBar(last.getContext());
            return fragment;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void openActivity(Class<? extends BaseFragment> cls, Integer itemId) {
        BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null) {
            return;
        }
        BaseFragment fragment = initiateFragment(cls);
        if (fragment == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> last.presentFragment(fragment));
        if (itemId != null && fragment instanceof BasePreferencesActivity) {
            AndroidUtilities.runOnUIThread(() -> ((BasePreferencesActivity) fragment).scrollToItem(itemId));
        }
    }

    public boolean isSearchIndexReady() {
        return entriesFetched && TextUtils.equals(entriesLangCode, getCurrentLangCode());
    }

    private static String getCurrentLangCode() {
        LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
        return info == null ? "" : info.getKey();
    }

    private void createEntriesIfNeeded() {
        String lang = getCurrentLangCode();
        if (entriesFetched) {
            if (TextUtils.equals(entriesLangCode, lang)) {
                return;
            }
            preparedEntries.clear();
        }
        for (Class<? extends BaseFragment> cls : categoriesIcons.keySet()) {
            initiateFragment(cls);
        }
        entriesFetched = true;
        entriesLangCode = lang;
    }

    private static int generateGUIDForUItem(Class<?> cls, UItem item) {
        return Objects.hash(cls.getName(), item.id);
    }

    public static final class Entry {
        private final int guid;
        private final int itemId;
        private final String title;
        private final String subtext;
        private final int icon;
        private final Class<? extends BaseFragment> fragmentClass;

        private Entry(int guid, int itemId, String title, String subtext, int icon, Class<? extends BaseFragment> fragmentClass) {
            this.guid = guid;
            this.itemId = itemId;
            this.title = title;
            this.subtext = subtext;
            this.icon = icon;
            this.fragmentClass = fragmentClass;
        }

        public static Entry fromUItem(BaseFragment fragment, UItem item) {
            Class<? extends BaseFragment> cls = fragment.getClass();
            return new Entry(generateGUIDForUItem(cls, item), item.id, item.text == null ? null : String.valueOf(item.text),
                    fragment instanceof BasePreferencesActivity ? ((BasePreferencesActivity) fragment).getTitle() : null,
                    getInstance().getCategoryIcon(cls), cls);
        }

        public ProfileActivity.SearchAdapter.SearchResult toSearchResult() {
            return new ProfileActivity.SearchAdapter.SearchResult(guid, title, String.valueOf(itemId), subtext, icon,
                    () -> getInstance().openActivity(fragmentClass, itemId));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry e = (Entry) o;
            return guid == e.guid && itemId == e.itemId && icon == e.icon && Objects.equals(title, e.title)
                    && Objects.equals(subtext, e.subtext) && Objects.equals(fragmentClass, e.fragmentClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(guid, itemId, icon, title, subtext, fragmentClass);
        }

        @Override
        public String toString() {
            return "Entry[guid=" + guid + ", itemId=" + itemId + ", title=" + title + ", subtext=" + subtext + ", icon=" + icon + ", fragmentClass=" + fragmentClass + "]";
        }
    }
}
