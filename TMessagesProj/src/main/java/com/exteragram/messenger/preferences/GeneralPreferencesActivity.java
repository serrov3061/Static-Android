package com.exteragram.messenger.preferences;

import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.util.Consumer;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.translator.TranslationProviders;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.ExtensionsKt;
import com.exteragram.messenger.utils.text.ZalgoFilter;
import com.exteragram.messenger.utils.ui.PopupUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.regex.Pattern;
import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.jvm.functions.Function0;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

public final class GeneralPreferencesActivity extends BasePreferencesActivity {
    private static final Pattern SAVE_PATH_PATTERN = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$");

    /* renamed from: fiveMinutesAgo$delegate, reason: from kotlin metadata */
    private final Lazy fiveMinutesAgo = LazyKt.lazy(new Function0() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda0
        @Override // kotlin.jvm.functions.Function0
        public final Object invoke() {
            return Integer.valueOf(GeneralPreferencesActivity.m1645$r8$lambda$U3axzru9WxR8DMYs4Fwp9CNRKA(GeneralPreferencesActivity.this));
        }
    });
    private CharSequence[] idOptions;
    private CharSequence[] translationProviders;

    public static final /* synthetic */ class WhenMappings {
        public static final /* synthetic */ int[] $EnumSwitchMapping$0;

        static {
            int[] iArr = new int[GeneralItem.values().length];
            try {
                iArr[GeneralItem.SHOW_TRANSLATE_BUTTON.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[GeneralItem.SHOW_TRANSLATE_CHAT_BUTTON.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                iArr[GeneralItem.TRANSLATION_PROVIDERS.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                iArr[GeneralItem.TRANSLATION_TARGET_LANGUAGE.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                iArr[GeneralItem.DO_NOT_TRANSLATE_LANGUAGES.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                iArr[GeneralItem.DISABLE_NUMBER_ROUNDING.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                iArr[GeneralItem.FORMAT_TIME_WITH_SECONDS.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            try {
                iArr[GeneralItem.RELATIVE_LAST_SEEN.ordinal()] = 8;
            } catch (NoSuchFieldError unused8) {
            }
            try {
                iArr[GeneralItem.IN_APP_VIBRATION.ordinal()] = 9;
            } catch (NoSuchFieldError unused9) {
            }
            try {
                iArr[GeneralItem.DISABLE_NOTIFICATION_DELAY.ordinal()] = 10;
            } catch (NoSuchFieldError unused10) {
            }
            try {
                iArr[GeneralItem.FILTER_ZALGO.ordinal()] = 11;
            } catch (NoSuchFieldError unused11) {
            }
            try {
                iArr[GeneralItem.UPLOAD_SPEED_BOOST.ordinal()] = 12;
            } catch (NoSuchFieldError unused12) {
            }
            try {
                iArr[GeneralItem.CUSTOM_SAVE_PATH.ordinal()] = 13;
            } catch (NoSuchFieldError unused13) {
            }
            try {
                iArr[GeneralItem.HIDE_PHONE_NUMBER.ordinal()] = 14;
            } catch (NoSuchFieldError unused14) {
            }
            try {
                iArr[GeneralItem.SHOW_ID_AND_DC.ordinal()] = 15;
            } catch (NoSuchFieldError unused15) {
            }
            try {
                iArr[GeneralItem.HIDE_ARCHIVE_FOLDER.ordinal()] = 16;
            } catch (NoSuchFieldError unused16) {
            }
            try {
                iArr[GeneralItem.ARCHIVE_ON_PULL.ordinal()] = 17;
            } catch (NoSuchFieldError unused17) {
            }
            try {
                iArr[GeneralItem.DISABLE_UNARCHIVE_SWIPE.ordinal()] = 18;
            } catch (NoSuchFieldError unused18) {
            }
            $EnumSwitchMapping$0 = iArr;
        }
    }

        public enum GeneralItem {
        SHOW_TRANSLATE_BUTTON,
        SHOW_TRANSLATE_CHAT_BUTTON,
        TRANSLATION_PROVIDERS,
        TRANSLATION_TARGET_LANGUAGE,
        DO_NOT_TRANSLATE_LANGUAGES,
        DISABLE_NUMBER_ROUNDING,
        FORMAT_TIME_WITH_SECONDS,
        RELATIVE_LAST_SEEN,
        IN_APP_VIBRATION,
        FILTER_ZALGO,
        DOWNLOAD_SPEED_BOOST,
        UPLOAD_SPEED_BOOST,
        CUSTOM_SAVE_PATH,
        HIDE_PHONE_NUMBER,
        SHOW_ID_AND_DC,
        HIDE_ARCHIVE_FOLDER,
        ARCHIVE_ON_PULL,
        DISABLE_UNARCHIVE_SWIPE,
        DISABLE_NOTIFICATION_DELAY;

        public static java.util.List<GeneralItem> getEntries() {
            return java.util.Arrays.asList(values());
        }

        private final int id = ordinal() + 1;

        private GeneralItem() {
        }

        public final int getId() {
            return this.id;
        }
    }

    /* renamed from: $r8$lambda$U3axzru9WxR8DMYs4-Fwp9CNRKA, reason: not valid java name */
    public static int m1645$r8$lambda$U3axzru9WxR8DMYs4Fwp9CNRKA(GeneralPreferencesActivity generalPreferencesActivity) {
        return ConnectionsManager.getInstance(generalPreferencesActivity.currentAccount).getCurrentTime() - 300;
    }

    private final int getFiveMinutesAgo() {
        return ((Number) this.fiveMinutesAgo.getValue()).intValue();
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void initializeOptionStrings() {
        this.idOptions = new CharSequence[]{LocaleController.getString(R.string.Hide), "Telegram API", "Bot API"};
        this.translationProviders = TranslationProviders.names();
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public String getTitle() {
        return LocaleController.getString(R.string.General);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.TranslateMessages)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.SHOW_TRANSLATE_BUTTON, (CharSequence) LocaleController.getString(R.string.ShowTranslateButton), getContextTranslateValue(), (CharSequence) null, 0, false, "showTranslateButton", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.SHOW_TRANSLATE_CHAT_BUTTON, (CharSequence) LocaleController.getString(R.string.ShowTranslateChatButton), getChatTranslateValue(), (CharSequence) null, isChatTranslateLocked() ? R.drawable.permission_locked : 0, false, "showTranslateChatButton", false, 0, 424, (Object) null));
        GeneralItem generalItem = GeneralItem.TRANSLATION_PROVIDERS;
        String string = LocaleController.getString(R.string.TranslationProvider);
        CharSequence[] charSequenceArr = this.translationProviders;
        if (charSequenceArr == null) {
            charSequenceArr = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) generalItem, (CharSequence) string, 0, charSequenceArr[ExteraConfig.getTranslationProvider()], (CharSequence) null, (Boolean) null, false, "translationProvider", false, false, (Boolean) null, 1908, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.TRANSLATION_TARGET_LANGUAGE, (CharSequence) LocaleController.getString(R.string.TranslationTarget), 0, (CharSequence) ExteraConfig.getCurrentLangName(), (CharSequence) null, (Boolean) null, false, "translationTargetLanguage", false, false, (Boolean) null, 1908, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.DO_NOT_TRANSLATE_LANGUAGES, (CharSequence) LocaleController.getString(R.string.DoNotTranslate), 0, getDoNotTranslateValue(), (CharSequence) null, (Boolean) null, false, "doNotTranslateLanguages", false, false, (Boolean) null, 1908, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.TranslateMessagesInfo1)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.DISABLE_NUMBER_ROUNDING, (CharSequence) LocaleController.getString(R.string.DisableNumberRounding), ExteraConfig.getDisableNumberRounding(), (CharSequence) "1.23K -> 1,234", 0, false, "disableNumberRounding", false, 0, 304, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.FORMAT_TIME_WITH_SECONDS, (CharSequence) LocaleController.getString(R.string.FormatTimeWithSeconds), ExteraConfig.getFormatTimeWithSeconds(), (CharSequence) "12:34 -> 12:34:56", 0, false, "formatTimeWithSeconds", false, 0, 304, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.IN_APP_VIBRATION, (CharSequence) LocaleController.getString(R.string.InAppVibration), ExteraConfig.getInAppVibration(), (CharSequence) null, 0, false, "inAppVibration", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.DISABLE_NOTIFICATION_DELAY, (CharSequence) LocaleController.getString(R.string.DisableNotificationDelay), ExteraConfig.getDisableNotificationDelay(), (CharSequence) null, 0, false, "disableNotificationDelay", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.FILTER_ZALGO, (CharSequence) LocaleController.getString(R.string.FilterZalgo), ExteraConfig.getFilterZalgo(), (CharSequence) null, 0, false, "filterZalgo", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.formatString(R.string.FilterZalgoInfo, ZalgoFilter.filter("Z̷͍͌ā̸̜l̸̞̂g̷͍̝o̶̩̓"))));
        items.add(UItem.asHeader(LocaleController.getString(R.string.DownloadSpeedBoost)));
        items.add(UItem.asSlideView(GeneralItem.DOWNLOAD_SPEED_BOOST.getId(), new String[]{LocaleController.getString(R.string.BlurOff), LocaleController.getString(R.string.SpeedFast), LocaleController.getString(R.string.Ultra)}, ExteraConfig.getDownloadSpeedBoost(), new Utilities.Callback() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda1
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                ExteraConfig.setDownloadSpeedBoost(((Integer) obj).intValue());
            }
        }).setLinkAlias("downloadSpeedBoost", this));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.UPLOAD_SPEED_BOOST, (CharSequence) LocaleController.getString(R.string.UploadSpeedBoost), ExteraConfig.getUploadSpeedBoost(), (CharSequence) null, 0, false, "uploadSpeedBoost", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.SpeedBoostInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.StorageSettings)));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.CUSTOM_SAVE_PATH, (CharSequence) LocaleController.getString(R.string.CustomSavePath), 0, (CharSequence) getCustomSavePathDisplayValue(), (CharSequence) null, (Boolean) null, false, "customSavePath", false, false, (Boolean) null, 1908, (Object) null));
        items.add(UItem.asShadow(getCustomSavePathInfo()));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Profile)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.RELATIVE_LAST_SEEN, (CharSequence) LocaleController.getString(R.string.RelativeLastSeen), ExteraConfig.getRelativeLastSeen(), (CharSequence) LocaleController.formatDateOnline(getFiveMinutesAgo(), null, new boolean[1]) /* exteraGram: relative variant */, 0, false, "relativeLastSeen", false, 0, 304, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.HIDE_PHONE_NUMBER, (CharSequence) LocaleController.getString(R.string.HidePhoneNumber), ExteraConfig.getHidePhoneNumber(), (CharSequence) null, 0, false, "hidePhoneNumber", false, 0, 440, (Object) null));
        GeneralItem generalItem2 = GeneralItem.SHOW_ID_AND_DC;
        String string2 = LocaleController.getString(R.string.ShowIdAndDc);
        CharSequence[] charSequenceArr2 = this.idOptions;
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) generalItem2, (CharSequence) string2, 0, (charSequenceArr2 != null ? charSequenceArr2 : null)[ExteraConfig.getShowIdAndDc()], (CharSequence) null, (Boolean) null, false, "showIdAndDc", false, false, (Boolean) null, 1908, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ShowIdAndDcInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ArchivedChats)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.HIDE_ARCHIVE_FOLDER, (CharSequence) LocaleController.getString(R.string.HideArchiveFolder), ExteraConfig.getHideArchiveFolder(), (CharSequence) null, 0, false, "hideArchiveFolder", false, 0, 440, (Object) null));
        ExtensionsKt.addIf(items, !ExteraConfig.getHideArchiveFolder(), BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.ARCHIVE_ON_PULL, (CharSequence) LocaleController.getString(R.string.ArchiveOnPull), ExteraConfig.getArchiveOnPull(), (CharSequence) null, 0, false, "archiveOnPull", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) GeneralItem.DISABLE_UNARCHIVE_SWIPE, (CharSequence) LocaleController.getString(R.string.DisableUnarchiveSwipe), ExteraConfig.getDisableUnarchiveSwipe(), (CharSequence) null, 0, false, "disableUnarchiveSwipe", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.DisableUnarchiveSwipeInfo)));
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        if (i <= 0 || i > GeneralItem.getEntries().size()) {
            return;
        }
        switch (WhenMappings.$EnumSwitchMapping$0[GeneralItem.getEntries().get(item.id - 1).ordinal()]) {
            case 1:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda2
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        GeneralPreferencesActivity.this.getMessagesController().getTranslateController().setContextTranslateEnabled(((Boolean) obj).booleanValue());
                    }
                });
                handleContextTranslateClick();
                break;
            case 2:
                handleChatTranslateClick(item);
                break;
            case 3:
                CharSequence[] charSequenceArr = this.translationProviders;
                if (charSequenceArr == null) {
                    charSequenceArr = null;
                }
                showListDialog(item, charSequenceArr, LocaleController.getString(R.string.TranslationProvider), ExteraConfig.getTranslationProvider(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda7
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        GeneralPreferencesActivity.m1648$r8$lambda$pnIgrcaoicP1fGeYl5KQLx5eEI(GeneralPreferencesActivity.this, i2);
                    }
                });
                break;
            case 4:
                presentFragment(new RestrictedLanguagesSelectActivity(1)); // translation target language
                break;
            case 5:
                presentFragment(new RestrictedLanguagesSelectActivity());
                break;
            case 6:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda8
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setDisableNumberRounding(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 7:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda9
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setFormatTimeWithSeconds(((Boolean) obj).booleanValue());
                    }
                });
                handleFormatTimeWithSecondsClick();
                break;
            case 8:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda10
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setRelativeLastSeen(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 9:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda11
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setInAppVibration(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 10:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda12
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setDisableNotificationDelay(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 11:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda13
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setFilterZalgo(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 12:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda14
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setUploadSpeedBoost(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 13:
                showCustomSavePathDialog();
                break;
            case 14:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda15
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setHidePhoneNumber(((Boolean) obj).booleanValue());
                    }
                });
                handleHidePhoneNumberClick();
                break;
            case 15:
                CharSequence[] charSequenceArr2 = this.idOptions;
                showListDialog(item, charSequenceArr2 == null ? null : charSequenceArr2, LocaleController.getString(R.string.ShowIdAndDc), ExteraConfig.getShowIdAndDc(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda3
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i2) {
                        GeneralPreferencesActivity.$r8$lambda$KrWwAmRC7Guvl72AisMkEr5VwJU(GeneralPreferencesActivity.this, i2);
                    }
                });
                break;
            case 16:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda4
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setHideArchiveFolder(((Boolean) obj).booleanValue());
                    }
                });
                MessagesController.getInstance(this.currentAccount).checkArchiveFolder();
                break;
            case 17:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda5
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setArchiveOnPull(((Boolean) obj).booleanValue());
                    }
                });
                break;
            case 18:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda6
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setDisableUnarchiveSwipe(((Boolean) obj).booleanValue());
                    }
                });
                break;
        }
    }

    /* renamed from: $r8$lambda$pnIgrca-oicP1fGeYl5KQLx5eEI, reason: not valid java name */
    public static void m1648$r8$lambda$pnIgrcaoicP1fGeYl5KQLx5eEI(GeneralPreferencesActivity generalPreferencesActivity, int i) {
        ExteraConfig.setTranslationProvider(i);
        TranslatorUtils.ensureTargetLanguageCompatibleWithProvider();
        generalPreferencesActivity.parentLayout.rebuildFragments(0);
    }

    public static void $r8$lambda$KrWwAmRC7Guvl72AisMkEr5VwJU(GeneralPreferencesActivity generalPreferencesActivity, int i) {
        ExteraConfig.setShowIdAndDc(i);
        generalPreferencesActivity.parentLayout.rebuildFragments(0);
    }

    private final CharSequence getDoNotTranslateValue() {
        HashSet<String> restrictedLanguages = RestrictedLanguagesSelectActivity.getRestrictedLanguages();
        boolean[] zArr = new boolean[1];
        ArrayList arrayList = new ArrayList();
        Iterator it = restrictedLanguages.iterator();
        while (it.hasNext()) {
            String strCapitalFirst = TranslateAlert2.capitalFirst(TranslateAlert2.languageName((String) it.next(), zArr));
            if (strCapitalFirst != null) {
                arrayList.add(strCapitalFirst);
            }
        }
        return android.text.TextUtils.join(", ", arrayList);
    }

    private final boolean getContextTranslateValue() {
        return getMessagesController().getTranslateController().isContextTranslateEnabled();
    }

    private final void handleContextTranslateClick() {
        NotificationCenter.getInstance(this.currentAccount).postNotificationName(NotificationCenter.updateSearchSettings, new Object[0]);
        this.parentLayout.rebuildFragments(0);
    }

    private final boolean getChatTranslateValue() {
        return getMessagesController().getTranslateController().isChatTranslateEnabled();
    }

    private final boolean isChatTranslateLocked() {
        return (getUserConfig().isPremium() || TranslatorUtils.isAlternativeProvider()) ? false : true;
    }

    private final void handleChatTranslateClick(UItem item) {
        if (!item.checked && isChatTranslateLocked()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.msg_translate, LocaleController.getString(R.string.ShowTranslateChatButtonLocked), LocaleController.getString(R.string.MoreInfo), new Runnable() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda20
                @Override // java.lang.Runnable
                public final void run() {
                    GeneralPreferencesActivity generalPreferencesActivity = GeneralPreferencesActivity.this;
                    generalPreferencesActivity.showDialog(new PremiumFeatureBottomSheet(generalPreferencesActivity, 13, false));
                }
            }).show();
            return;
        }
        toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda21
            @Override // androidx.core.util.Consumer
            public final void accept(Object obj) {
                GeneralPreferencesActivity.this.getMessagesController().getTranslateController().setChatTranslateEnabled(((Boolean) obj).booleanValue());
            }
        });
        NotificationCenter.getInstance(this.currentAccount).postNotificationName(NotificationCenter.updateSearchSettings, new Object[0]);
        this.parentLayout.rebuildFragments(0);
    }

    private final void handleFormatTimeWithSecondsClick() {
        LocaleController.getInstance().recreateFormatters();
        this.parentLayout.rebuildFragments(0);
    }

    private final void handleHidePhoneNumberClick() {
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged, new Object[0]);
        this.parentLayout.rebuildFragments(0);
    }

    private final String getCustomSavePathDisplayValue() {
        return TextUtils.isEmpty(ExteraConfig.getCustomSavePath()) ? LocaleController.getString(R.string.CustomSavePathDefault) : ExteraConfig.getCustomSavePath();
    }

    private final String getCustomSavePathInfo() {
        return TextUtils.isEmpty(ExteraConfig.getCustomSavePath()) ? LocaleController.getString(R.string.CustomSavePathInfo) : LocaleController.formatString(R.string.CustomSavePathInfoFolder, ExteraConfig.getCustomSavePath());
    }

    private final void showCustomSavePathDialog() {
        if (getContext() == null) {
            return;
        }
        final EditTextBoldCursor editTextBoldCursor = new EditTextBoldCursor(getContext());
        editTextBoldCursor.lineYFix = true;
        editTextBoldCursor.setTextSize(1, 18.0f);
        editTextBoldCursor.setText(ExteraConfig.getCustomSavePath());
        editTextBoldCursor.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, this.resourceProvider));
        editTextBoldCursor.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, this.resourceProvider));
        editTextBoldCursor.setHintText(LocaleController.getString(R.string.CustomSavePathHint));
        editTextBoldCursor.setFocusable(true);
        editTextBoldCursor.setSingleLine(true);
        editTextBoldCursor.setInputType(1);
        editTextBoldCursor.setBackground(null);
        int color = Theme.getColor(Theme.key_windowBackgroundWhiteInputField, this.resourceProvider);
        int i = Theme.key_windowBackgroundWhiteInputFieldActivated;
        editTextBoldCursor.setLineColors(color, Theme.getColor(i, this.resourceProvider), Theme.getColor(Theme.key_text_RedRegular, this.resourceProvider));
        editTextBoldCursor.setCursorColor(Theme.getColor(i, this.resourceProvider));
        editTextBoldCursor.setPadding(0, AndroidUtilities.dp(6.0f), 0, AndroidUtilities.dp(6.0f));
        LinearLayout linearLayout = new LinearLayout(getContext());
        linearLayout.setOrientation(1);
        linearLayout.addView(editTextBoldCursor, LayoutHelper.createLinear(-1, -2, 24.0f, 0.0f, 24.0f, 10.0f));
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), this.resourceProvider);
        builder.setTitle(LocaleController.getString(R.string.CustomSavePath));
        builder.makeCustomMaxHeight();
        builder.setView(linearLayout);
        builder.setWidth(AndroidUtilities.dp(292.0f));
        builder.setPositiveButton(LocaleController.getString(R.string.Done), new AlertDialog.OnButtonClickListener() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda16
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i2) {
                GeneralPreferencesActivity.$r8$lambda$TGgWqWciHfVoWWoSPJGiGkq3kAc(editTextBoldCursor, GeneralPreferencesActivity.this, alertDialog, i2);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), new AlertDialog.OnButtonClickListener() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda17
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i2) {
                alertDialog.dismiss();
            }
        });
        AlertDialog alertDialogCreate = builder.create();
        alertDialogCreate.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda18
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                AndroidUtilities.hideKeyboard(editTextBoldCursor);
            }
        });
        alertDialogCreate.setOnShowListener(new DialogInterface.OnShowListener() { // from class: com.exteragram.messenger.preferences.GeneralPreferencesActivity$$ExternalSyntheticLambda19
            @Override // android.content.DialogInterface.OnShowListener
            public final void onShow(DialogInterface dialogInterface) {
                GeneralPreferencesActivity.m1646$r8$lambda$fkxN65LZaTfMEbFYf6yGJljFos(editTextBoldCursor, dialogInterface);
            }
        });
        alertDialogCreate.setDismissDialogByButtons(false);
        showDialog(alertDialogCreate);
    }

    public static void $r8$lambda$TGgWqWciHfVoWWoSPJGiGkq3kAc(EditTextBoldCursor editTextBoldCursor, GeneralPreferencesActivity generalPreferencesActivity, AlertDialog alertDialog, int i) {
        String string;
        String string2;
        Editable text = editTextBoldCursor.getText();
        if (text == null || (string2 = text.toString()) == null || (string = StringsKt.trim((CharSequence) string2).toString()) == null) {
            string = "";
        }
        if (string.length() > 0 && !SAVE_PATH_PATTERN.matcher(string).matches()) {
            AndroidUtilities.shakeView(editTextBoldCursor);
            return;
        }
        ExteraConfig.setCustomSavePath(string);
        generalPreferencesActivity.getListView$TMessagesProj().adapter.update(true);
        alertDialog.dismiss();
    }

    /* renamed from: $r8$lambda$fkxN65LZaTfMEbFYf6y-GJljFos, reason: not valid java name */
    public static void m1646$r8$lambda$fkxN65LZaTfMEbFYf6yGJljFos(EditTextBoldCursor editTextBoldCursor, DialogInterface dialogInterface) {
        editTextBoldCursor.requestFocus();
        editTextBoldCursor.setSelection(editTextBoldCursor.length());
        AndroidUtilities.showKeyboard(editTextBoldCursor);
    }
}
