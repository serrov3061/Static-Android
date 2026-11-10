package com.exteragram.messenger.preferences;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.ui.PluginsActivity;
import com.exteragram.messenger.preferences.appearance.AppearancePreferencesActivity;
import com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity;
import com.exteragram.messenger.preferences.components.HeaderSettingsCell;
import com.exteragram.messenger.utils.ExtensionsKt;
import com.exteragram.messenger.utils.system.VibratorUtils;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

public final class MainPreferencesActivity extends BasePreferencesActivity {
    private HeaderSettingsCell headerSettingsCell;

    public static final /* synthetic */ class WhenMappings {
        public static final /* synthetic */ int[] $EnumSwitchMapping$0;

        static {
            int[] iArr = new int[PreferenceItem.values().length];
            try {
                iArr[PreferenceItem.HEADER_CELL.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[PreferenceItem.GENERAL_CATEGORY.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                iArr[PreferenceItem.APPEARANCE_CATEGORY.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                iArr[PreferenceItem.CHATS_CATEGORY.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                iArr[PreferenceItem.PLUGINS_CATEGORY.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                iArr[PreferenceItem.OTHER_CATEGORY.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                iArr[PreferenceItem.CHANNEL.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            try {
                iArr[PreferenceItem.FORUM.ordinal()] = 8;
            } catch (NoSuchFieldError unused8) {
            }
            try {
                iArr[PreferenceItem.CROWDIN.ordinal()] = 9;
            } catch (NoSuchFieldError unused9) {
            }
            try {
                iArr[PreferenceItem.WEBSITE.ordinal()] = 10;
            } catch (NoSuchFieldError unused10) {
            }
            try {
                iArr[PreferenceItem.BADGE.ordinal()] = 11;
            } catch (NoSuchFieldError unused11) {
            }
            $EnumSwitchMapping$0 = iArr;
        }
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public boolean needHideTitle() {
        return true;
    }

        public enum PreferenceItem {
        HEADER_CELL,
        GENERAL_CATEGORY,
        APPEARANCE_CATEGORY,
        CHATS_CATEGORY,
        PLUGINS_CATEGORY,
        OTHER_CATEGORY,
        CHANNEL,
        FORUM,
        CROWDIN,
        WEBSITE,
        BADGE; // StaticGram: «exteraStatic бейдж» (appended so the other ids keep their values)

        public static java.util.List<PreferenceItem> getEntries() {
            return java.util.Arrays.asList(values());
        }

        private final int id = ordinal() + 1;

        private PreferenceItem() {
        }

        public final int getId() {
            return this.id;
        }
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public String getTitle() {
        return LocaleController.getString(R.string.Preferences);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity, org.telegram.ui.ActionBar.BaseFragment
    public View createView(Context context) {
        this.headerSettingsCell = new HeaderSettingsCell(context);
        View viewCreateView = super.createView(context);
        this.fragmentView = viewCreateView;
        return viewCreateView;
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HeaderSettingsCell headerSettingsCell = this.headerSettingsCell;
        if (headerSettingsCell == null) {
            headerSettingsCell = null;
        }
        items.add(UItem.asCustomShadow(headerSettingsCell, 224 /* StaticGram: 52+96+16+title+28 */).setId(PreferenceItem.HEADER_CELL.getId()));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Categories)));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.GENERAL_CATEGORY, (CharSequence) LocaleController.getString(R.string.General), R.drawable.msg_media, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "general", false, false, (Boolean) null, 1912, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.APPEARANCE_CATEGORY, (CharSequence) LocaleController.getString(R.string.Appearance), R.drawable.msg_theme, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "appearance", false, false, (Boolean) null, 1912, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.CHATS_CATEGORY, (CharSequence) LocaleController.getString(R.string.SearchAllChatsShort), R.drawable.msg_discussion, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "chats", false, false, (Boolean) null, 1912, (Object) null));
        // StaticGram: the Plugins entry is always shown (exteraGram hides it on ABIs without the plugin SDK)
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.PLUGINS_CATEGORY, (CharSequence) LocaleController.getString(R.string.Plugins), R.drawable.msg_plugins, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "plugins", false, false, (Boolean) null, 1912, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.OTHER_CATEGORY, (CharSequence) LocaleController.getString(R.string.LocalOther), R.drawable.msg_fave, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "other", false, false, (Boolean) null, 1912, (Object) null));
        // StaticGram: exteraStatic badge (preview, custom emoji/text, buy/upgrade)
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.BADGE, (CharSequence) LocaleController.getString(R.string.ExteraBadge), R.drawable.ic_sg_badge, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "badge", false, false, (Boolean) null, 1912, (Object) null));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.Links)));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.CHANNEL, (CharSequence) LocaleController.getString(R.string.ProfileChannel), R.drawable.msg_channel, (CharSequence) "StaticGram новости", (CharSequence) null, (Boolean) null, false, "channel", false, false, (Boolean) null, 1904, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.FORUM, (CharSequence) LocaleController.getString(R.string.SearchAllChatsShort), R.drawable.msg_groups, (CharSequence) "@staticgramchat", (CharSequence) null, (Boolean) null, false, "chat", false, false, (Boolean) null, 1904, (Object) null));
        // StaticGram: exteraGram's Crowdin entry is hidden (no translation project of our own)
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) PreferenceItem.WEBSITE, (CharSequence) LocaleController.getString(R.string.Website), R.drawable.msg_language, (CharSequence) "docs.staticgram.top", (CharSequence) null, Boolean.FALSE, false, "website", false, false, (Boolean) null, 1872, (Object) null));
        items.add(UItem.asShadow());
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        if (i <= 0 || i > PreferenceItem.getEntries().size()) {
            return;
        }
        switch (WhenMappings.$EnumSwitchMapping$0[PreferenceItem.getEntries().get(item.id - 1).ordinal()]) {
            case 1:
                if (!BuildVars.isBetaApp() || true) { // exteraGram: !BuildVars.PM_BUILD (no Play Market build here)
                    Activity parentActivity = getParentActivity();
                    LaunchActivity launchActivity = parentActivity instanceof LaunchActivity ? (LaunchActivity) parentActivity : null;
                    if (launchActivity != null) {
                        launchActivity.checkAppUpdate(true, null);
                        break;
                    }
                }
                break;
            case 2:
                presentFragment(new GeneralPreferencesActivity());
                break;
            case 3:
                presentFragment(new AppearancePreferencesActivity());
                break;
            case 4:
                presentFragment(new ChatsPreferencesActivity());
                break;
            case 5:
                presentFragment(new PluginsActivity());
                break;
            case 6:
                presentFragment(new OtherPreferencesActivity());
                break;
            case 7:
                { // StaticGram: our news channel on our own server (channel id 2); @staticgrams belongs to someone else here
                    android.os.Bundle args = new android.os.Bundle();
                    args.putLong("chat_id", 2L);
                    presentFragment(new org.telegram.ui.ChatActivity(args));
                }
                break;
            case 8:
                com.exteragram.messenger.utils.ExternalTelegram.open(getParentActivity(), "https://t.me/staticgramchat"); // StaticGram: our chat in Telegram
                break;
            case 9:
                break;
            case 10:
                Browser.openUrl(getParentActivity(), "https://docs.staticgram.top");
                break;
            case 11:
                presentFragment(new BadgePreferencesActivity());
                break;
            default:
                com.exteragram.messenger.utils.ExteraCompat.noWhenBranchMatched();
                break;
        }
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        if (i <= 0 || i > PreferenceItem.getEntries().size()) {
            return false;
        }
        if (PreferenceItem.getEntries().get(item.id - 1) == PreferenceItem.HEADER_CELL) {
            ExteraConfig.setUseSystemIconShape(!ExteraConfig.getUseSystemIconShape());
            view.performHapticFeedback(VibratorUtils.getType(3), 1);
            view.invalidate();
            return true;
        }
        return super.onLongClick(item, view, position, x, y);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public int getListTopPadding(int topInset) {
        return topInset + AndroidUtilities.dp(12.0f);
    }
}
