package com.exteragram.messenger.utils.chats;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.MainMenuItem;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.ui.PluginsActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionIntroActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.WebAppDisclaimerAlert;
import org.telegram.ui.bots.BotWebViewSheet;
import org.telegram.ui.web.SearchEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * exteraGram MainMenuHelper: the main menu (DialogsActivity options popup) and the navigation drawer
 * are built from the same configurable layout (ExteraConfig.mainMenuLayout / mainMenuHiddenItems,
 * edited in AppNavigationPreferencesActivity).
 * StaticGram port: PLUGINS (plus the plugins' own main-menu items) resolves only when the
 * plugin engine is supported; FEED opens the channel feed (feed/ui/FeedActivity); the QR item scans a code
 * and opens it as a link (exteraGram shows its own QRCodeSheet).
 */
public final class MainMenuHelper {

    private MainMenuHelper() {
    }

    public static final class MenuItemInfo {
        private final int iconRes;
        private final CharSequence text;
        private final Runnable onClick;
        private final Runnable onLongClick;

        public MenuItemInfo(int iconRes, CharSequence text, Runnable onClick, Runnable onLongClick) {
            this.iconRes = iconRes;
            this.text = text;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }

        public int iconRes() {
            return iconRes;
        }

        public CharSequence text() {
            return text;
        }

        public Runnable onClick() {
            return onClick;
        }

        public Runnable onLongClick() {
            return onLongClick;
        }
    }

    public static final class MenuContext {
        private final int currentAccount;
        private final BaseFragment fragment;
        private final Runnable archiveClick;

        public MenuContext(int currentAccount, BaseFragment fragment, Runnable archiveClick) {
            this.currentAccount = currentAccount;
            this.fragment = fragment;
            this.archiveClick = archiveClick;
        }

        public int currentAccount() {
            return currentAccount;
        }

        public BaseFragment fragment() {
            return fragment;
        }

        public Runnable archiveClick() {
            return archiveClick;
        }
    }

    public static final class AttachMenuBotInfo {
        private final int iconRes;
        private final CharSequence text;
        private final TLRPC.TL_attachMenuBot bot;
        private final Runnable onClick;
        private final Runnable onLongClick;

        public AttachMenuBotInfo(int iconRes, CharSequence text, TLRPC.TL_attachMenuBot bot, Runnable onClick, Runnable onLongClick) {
            this.iconRes = iconRes;
            this.text = text;
            this.bot = bot;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }

        public int iconRes() {
            return iconRes;
        }

        public CharSequence text() {
            return text;
        }

        public TLRPC.TL_attachMenuBot bot() {
            return bot;
        }

        public Runnable onClick() {
            return onClick;
        }

        public Runnable onLongClick() {
            return onLongClick;
        }
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment) {
        return new MenuContext(currentAccount, fragment, null);
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment, Runnable archiveClick) {
        return new MenuContext(currentAccount, fragment, archiveClick);
    }

    public static boolean hasArchivedChats(int account) {
        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(account).getDialogs(1);
        return dialogs != null && !dialogs.isEmpty();
    }

    public static List<MenuItemInfo> resolveDrawerMenuItems(int id, MenuContext context) {
        MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return Collections.emptyList();
        }
        if (item == MainMenuItem.BOTS) {
            List<AttachMenuBotInfo> bots = getAttachMenuBotItems(context);
            if (bots.isEmpty()) {
                return Collections.emptyList();
            }
            ArrayList<MenuItemInfo> result = new ArrayList<>(bots.size());
            for (AttachMenuBotInfo bot : bots) {
                result.add(new MenuItemInfo(bot.iconRes(), bot.text(), bot.onClick(), bot.onLongClick()));
            }
            return result;
        }
        MenuItemInfo info = resolveMenuItem(id, context);
        if (item == MainMenuItem.PLUGINS && info != null) {
            // exteraGram plugins: items that plugins add to the main menu follow the Plugins entry
            List<com.exteragram.messenger.plugins.hooks.MenuItemRecord> pluginItems = getPluginMenuItems(context);
            if (!pluginItems.isEmpty()) {
                ArrayList<MenuItemInfo> result = new ArrayList<>(pluginItems.size() + 1);
                result.add(info);
                for (com.exteragram.messenger.plugins.hooks.MenuItemRecord record : pluginItems) {
                    if (record != null && !android.text.TextUtils.isEmpty(record.getText())) {
                        result.add(new MenuItemInfo(record.getIconResId() != 0 ? record.getIconResId() : R.drawable.msg_plugins, record.getText(), createPluginClickAction(record, context), info.onClick()));
                    }
                }
                return result;
            }
        }
        return info == null ? Collections.emptyList() : Collections.singletonList(info);
    }

    // exteraGram plugins: "main_menu" items registered by plugins
    public static List<com.exteragram.messenger.plugins.hooks.MenuItemRecord> getPluginMenuItems(MenuContext context) {
        try {
            if (!PluginsController.isPluginEngineAvailable()) {
                return Collections.emptyList();
            }
            return PluginsController.getInstance().getMenuItemsForLocation(com.exteragram.messenger.plugins.PluginsConstants.MenuItemTypes.MAIN_MENU, getPluginContextData(context));
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
            return Collections.emptyList();
        }
    }

    public static Runnable createPluginClickAction(com.exteragram.messenger.plugins.hooks.MenuItemRecord record, MenuContext context) {
        return () -> record.executeClick(getPluginContextData(context));
    }

    private static java.util.Map<String, Object> getPluginContextData(MenuContext context) {
        return com.exteragram.messenger.plugins.utils.MenuContextBuilder.from(context.fragment()).withAccount(context.currentAccount()).build();
    }

    public static MenuItemInfo resolveMenuItem(int id, MenuContext context) {
        MainMenuItem item = MainMenuItem.getById(id);
        if (item == null || context.fragment() == null) {
            return null;
        }
        final int account = context.currentAccount();
        final BaseFragment fragment = context.fragment();
        switch (item) {
            case PLUGINS:
                if (PluginsController.isPluginEngineSupported()) {
                    return new MenuItemInfo(R.drawable.msg_plugins, LocaleController.getString(R.string.Plugins), () -> fragment.presentFragment(new PluginsActivity()), null);
                }
                return null;
            case PROFILE:
                return new MenuItemInfo(R.drawable.left_status_profile, LocaleController.getString(R.string.MyProfile), () -> {
                    Bundle args = new Bundle();
                    args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                    args.putBoolean("my_profile", true);
                    fragment.presentFragment(new ProfileActivity(args));
                }, null);
            case ARCHIVE:
                return new MenuItemInfo(R.drawable.msg_archive, LocaleController.getString(R.string.ArchivedChats), context.archiveClick() != null ? context.archiveClick() : () -> {
                    Bundle args = new Bundle();
                    args.putInt("folderId", 1);
                    fragment.presentFragment(new DialogsActivity(args));
                }, null);
            case NEW_GROUP:
                return new MenuItemInfo(R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup), () -> fragment.presentFragment(new GroupCreateActivity(new Bundle())), null);
            case CONTACTS:
                return new MenuItemInfo(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), () -> {
                    Bundle args = new Bundle();
                    args.putBoolean("needPhonebook", true);
                    args.putBoolean("needFinishFragment", false);
                    fragment.presentFragment(new ContactsActivity(args));
                }, null);
            case CALLS:
                return new MenuItemInfo(R.drawable.msg_calls, LocaleController.getString(R.string.Calls), () -> fragment.presentFragment(new CallLogActivity()), null);
            case NEW_CHANNEL:
                return new MenuItemInfo(R.drawable.msg_channel, LocaleController.getString(R.string.NewChannel), () -> {
                    SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                    if (!BuildVars.DEBUG_VERSION && preferences.getBoolean("channel_intro", false)) {
                        Bundle args = new Bundle();
                        args.putInt("step", 0);
                        fragment.presentFragment(new ChannelCreateActivity(args));
                    } else {
                        fragment.presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
                        preferences.edit().putBoolean("channel_intro", true).apply();
                    }
                }, null);
            case SAVED:
                return new MenuItemInfo(R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), () -> {
                    Bundle args = new Bundle();
                    args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                    fragment.presentFragment(new ChatActivity(args));
                }, null);
            case FEED:
                return new MenuItemInfo(R.drawable.ic_feed, LocaleController.getString(R.string.Feed), () -> com.exteragram.messenger.feed.ui.FeedActivity.presentFeed(fragment), null);
            case SETTINGS:
                return new MenuItemInfo(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), () -> fragment.presentFragment(new SettingsActivity()), null);
            case BROWSER:
                return new MenuItemInfo(R.drawable.msg2_language, LocaleController.getString(R.string.BrowserSettingsTitle), () -> openBrowser(fragment), null);
            case QR:
                return new MenuItemInfo(R.drawable.msg_qrcode, LocaleController.getString(R.string.AuthAnotherClient), () -> openQrScanner(fragment), null);
            default:
                return null;
        }
    }

    private static void openBrowser(BaseFragment fragment) {
        // StaticGram: our SearchEngine has no homepage field (12.10.6), the engine's site root is used instead
        SearchEngine engine = SearchEngine.getCurrent();
        String url = engine != null ? engine.search_url : null;
        if (!TextUtils.isEmpty(url)) {
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                if (uri.getScheme() != null && uri.getHost() != null) {
                    url = uri.getScheme() + "://" + uri.getHost();
                }
            } catch (Exception ignore) {
            }
        }
        if (TextUtils.isEmpty(url)) {
            url = "https://staticgram.top";
        }
        Browser.openInTelegramBrowser(fragment.getParentActivity(), url, null);
    }

    private static void openQrScanner(BaseFragment fragment) {
        LaunchActivity launchActivity = LaunchActivity.instance;
        if (launchActivity != null && android.os.Build.VERSION.SDK_INT >= 23 && launchActivity.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchActivity.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 34);
            return;
        }
        CameraScanActivity.showAsSheet(fragment, true, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public boolean processQr(String text, Runnable onLoadEnd) {
                AndroidUtilities.runOnUIThread(() -> {
                    AndroidUtilities.runOnUIThread(() -> onQrScanned(text), 150);
                    onLoadEnd.run();
                }, 600);
                return true;
            }
        });
    }

    private static void onQrScanned(String text) {
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || TextUtils.isEmpty(text)) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.contains("://") || trimmed.startsWith("t.me/") || trimmed.startsWith("staticgram.top/")) {
            Browser.openUrl(last.getParentActivity(), trimmed);
        } else {
            AndroidUtilities.addToClipboard(trimmed);
            BulletinFactory.of(last).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    public static void addConfiguredItemOptions(ItemOptions options, MenuContext context) {
        addConfiguredItemOptions(options, context, id -> false);
    }

    /** Adds the visible items of the configured layout; returns the ids that were actually added. */
    public static ArrayList<Integer> addConfiguredItemOptions(ItemOptions options, MenuContext context, IntPredicate skip) {
        ArrayList<Integer> added = new ArrayList<>();
        boolean hasItems = false;
        boolean pendingGap = false;
        ArrayList<Integer> layout = ExteraConfig.getMainMenuLayout();
        for (int i = 0; i < layout.size(); i++) {
            Integer id = layout.get(i);
            if (id == null || ExteraConfig.getMainMenuHiddenItems().contains(id)) {
                continue;
            }
            if (id == MainMenuItem.DIVIDER.getId()) {
                if (hasItems) {
                    pendingGap = true;
                }
                continue;
            }
            if (skip.test(id)) {
                continue;
            }
            if (pendingGap) {
                options.addGap();
                pendingGap = false;
            }
            if (addConfiguredItemOption(options, context, id)) {
                hasItems = true;
                added.add(id);
            }
        }
        return added;
    }

    private static boolean addConfiguredItemOption(ItemOptions options, MenuContext context, int id) {
        MainMenuItem item = MainMenuItem.getById(id);
        if (item == null) {
            return false;
        }
        if (item == MainMenuItem.ARCHIVE && !hasArchivedChats(context.currentAccount())) {
            return false;
        }
        if (item == MainMenuItem.BOTS) {
            List<AttachMenuBotInfo> bots = getAttachMenuBotItems(context);
            if (bots.isEmpty()) {
                return false;
            }
            for (AttachMenuBotInfo bot : bots) {
                options.addBot(bot.bot(), bot.onClick(), bot.onLongClick());
            }
            return true;
        }
        MenuItemInfo info = resolveMenuItem(id, context);
        if (info == null || info.onClick() == null) {
            return false;
        }
        options.add(getItemOptionsIcon(item, info.iconRes()), info.text(), info.onClick());
        bindLongClick(options, info.onLongClick());
        if (item == MainMenuItem.PLUGINS) {
            // exteraGram plugins: plugin main-menu items right after the Plugins entry
            for (com.exteragram.messenger.plugins.hooks.MenuItemRecord record : getPluginMenuItems(context)) {
                if (record != null && !android.text.TextUtils.isEmpty(record.getText())) {
                    options.add(record.getIconResId() != 0 ? record.getIconResId() : R.drawable.msg_plugins, record.getText(), createPluginClickAction(record, context));
                }
            }
        }
        return true;
    }

    // StaticGram: the popup menu keeps Telegram 12's outline icons for the items it already had
    private static int getItemOptionsIcon(MainMenuItem item, int fallback) {
        switch (item) {
            case NEW_GROUP:
                return R.drawable.outline_groups_24;
            case SAVED:
                return R.drawable.outline_saved_24;
            case SETTINGS:
                return R.drawable.msg_settings_old;
            default:
                return fallback;
        }
    }

    public static List<AttachMenuBotInfo> getAttachMenuBotItems(MenuContext context) {
        BaseFragment fragment = context.fragment();
        LaunchActivity launchActivity = findLaunchActivity(fragment);
        TLRPC.TL_attachMenuBots menuBots = MediaDataController.getInstance(context.currentAccount()).getAttachMenuBots();
        if (fragment == null || launchActivity == null || menuBots == null || menuBots.bots == null || menuBots.bots.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<AttachMenuBotInfo> result = new ArrayList<>();
        java.util.HashSet<Long> added = new java.util.HashSet<>();
        for (TLRPC.TL_attachMenuBot bot : menuBots.bots) {
            if (bot.show_in_side_menu && added.add(bot.bot_id)) {
                result.add(new AttachMenuBotInfo(R.drawable.msg_bot, bot.short_name, bot,
                        createAttachMenuBotClickAction(context, bot, launchActivity),
                        () -> BotWebViewSheet.deleteBot(context.currentAccount(), bot.bot_id, null)));
            }
        }
        return result;
    }

    private static Runnable createAttachMenuBotClickAction(MenuContext context, TLRPC.TL_attachMenuBot bot, LaunchActivity launchActivity) {
        return () -> {
            if (bot.inactive || bot.side_menu_disclaimer_needed) {
                WebAppDisclaimerAlert.show(context.fragment().getContext() != null ? context.fragment().getContext() : launchActivity, allowSendMessage -> {
                    TLRPC.TL_messages_toggleBotInAttachMenu req = new TLRPC.TL_messages_toggleBotInAttachMenu();
                    req.bot = MessagesController.getInstance(context.currentAccount()).getInputUser(bot.bot_id);
                    req.enabled = true;
                    req.write_allowed = true;
                    ConnectionsManager.getInstance(context.currentAccount()).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        bot.inactive = bot.side_menu_disclaimer_needed = false;
                        LaunchActivity.showAttachMenuBot(launchActivity, context.currentAccount(), bot, null, true);
                        MediaDataController.getInstance(context.currentAccount()).updateAttachMenuBotsInCache();
                    }), ConnectionsManager.RequestFlagInvokeAfter | ConnectionsManager.RequestFlagFailOnServerErrors);
                }, null, null);
            } else {
                LaunchActivity.showAttachMenuBot(launchActivity, context.currentAccount(), bot, null, true);
            }
        };
    }

    private static void bindLongClick(ItemOptions options, Runnable onLongClick) {
        if (onLongClick == null) {
            return;
        }
        ActionBarMenuSubItem last = options.getLast();
        if (last == null) {
            return;
        }
        last.setOnLongClickListener(v -> {
            options.dismiss();
            onLongClick.run();
            return true;
        });
    }

    private static LaunchActivity findLaunchActivity(BaseFragment fragment) {
        if (fragment == null) {
            return LaunchActivity.instance;
        }
        Activity activity = AndroidUtilities.findActivity(fragment.getContext() != null ? fragment.getContext() : fragment.getParentActivity());
        return activity instanceof LaunchActivity ? (LaunchActivity) activity : LaunchActivity.instance;
    }
}
