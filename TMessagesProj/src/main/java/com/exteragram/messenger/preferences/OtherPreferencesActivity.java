package com.exteragram.messenger.preferences;

import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.api.db.DatabaseHelper;
import com.exteragram.messenger.api.dto.BoostySubscriberDTO;
import com.exteragram.messenger.backup.PreferencesUtils;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.components.BoostyBottomSheet;
import com.exteragram.messenger.components.SupporterBottomSheet;
import com.exteragram.messenger.export.ui.ExportActivity;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.utils.ExtensionsKt;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.utils.system.VibratorUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import kotlin.TuplesKt;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;
import kotlin.collections.SetsKt;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.StringCompanionObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LinkifyPort;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class OtherPreferencesActivity extends BasePreferencesActivity {

    /* renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE = new Companion(null);
    private List<Donate> donates = CollectionsKt.emptyList();
    private final ArrayList<BoostySubscriberDTO> subscribers = new ArrayList<>();
    private final IconInfo defaultDonateIcon = new IconInfo(R.drawable.msg_payment_card, 0);

    public static final /* synthetic */ class WhenMappings {
        public static final /* synthetic */ int[] $EnumSwitchMapping$0;

        static {
            int[] iArr = new int[OtherItem.values().length];
            try {
                iArr[OtherItem.CRASHLYTICS.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[OtherItem.ANALYTICS.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                iArr[OtherItem.RESET_SETTINGS.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                iArr[OtherItem.DELETE_ACCOUNT.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                iArr[OtherItem.EXPORT_SETTINGS.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                iArr[OtherItem.EXPORT_DATA.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                iArr[OtherItem.IMPORT_SETTINGS.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            $EnumSwitchMapping$0 = iArr;
        }
    }

    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        public final List<Donate> getDonates() {
            Set<String> stringSetConfigValue = RemoteUtils.getStringSetConfigValue("donates", SetsKt.emptySet());
            ArrayList arrayList = new ArrayList();
            Iterator<String> it = stringSetConfigValue.iterator();
            while (it.hasNext()) {
                List listSplit$default = com.exteragram.messenger.utils.ExteraCompat.split((CharSequence) it.next(), "#");
                if (listSplit$default.size() == 2) {
                    arrayList.add(new Donate((String) listSplit$default.get(0), (String) listSplit$default.get(1)));
                }
            }
            return arrayList;
        }
    }

        public enum OtherItem {
        CRASHLYTICS,
        ANALYTICS,
        EXPORT_SETTINGS,
        EXPORT_DATA,
        IMPORT_SETTINGS,
        RESET_SETTINGS,
        DELETE_ACCOUNT,
        DONATE;

        public static java.util.List<OtherItem> getEntries() {
            return java.util.Arrays.asList(values());
        }

        private final int id = ordinal() + 1;

        private OtherItem() {
        }

        public final int getId() {
            return this.id;
        }
    }

    public static final /* data */ class IconInfo {
        private final int iconColor;
        private final int iconResId;

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IconInfo)) {
                return false;
            }
            IconInfo iconInfo = (IconInfo) other;
            return this.iconResId == iconInfo.iconResId && this.iconColor == iconInfo.iconColor;
        }

        public int hashCode() {
            return (Integer.hashCode(this.iconResId) * 31) + Integer.hashCode(this.iconColor);
        }

        public String toString() {
            return "IconInfo(iconResId=" + this.iconResId + ", iconColor=" + this.iconColor + ")";
        }

        public IconInfo(int i, int i2) {
            this.iconResId = i;
            this.iconColor = i2;
        }

        public final int getIconColor() {
            return this.iconColor;
        }

        public final int getIconResId() {
            return this.iconResId;
        }
    }

    public static final /* data */ class Donate {
        private final String details;
        private final String name;

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Donate)) {
                return false;
            }
            Donate donate = (Donate) other;
            return Intrinsics.areEqual(this.name, donate.name) && Intrinsics.areEqual(this.details, donate.details);
        }

        public int hashCode() {
            return (this.name.hashCode() * 31) + this.details.hashCode();
        }

        public String toString() {
            return "Donate(name=" + this.name + ", details=" + this.details + ")";
        }

        public Donate(String str, String str2) {
            this.name = str;
            this.details = str2;
        }

        public final String getDetails() {
            return this.details;
        }

        public final String getName() {
            return this.name;
        }
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity, org.telegram.ui.ActionBar.BaseFragment
    public View createView(Context context) {
        DatabaseHelper.getBoostySubscribers(new Consumer() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda0
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                OtherPreferencesActivity.this.subscribers.addAll((List) obj);
            }
        });
        return super.createView(context);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public String getTitle() {
        return LocaleController.getString(R.string.LocalOther);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int i;
        this.donates = INSTANCE.getDonates();
        Collection<Plugin> collectionValues = PluginsController.getInstance().getPlugins().values();
        if (collectionValues.isEmpty()) {
            i = 0;
        } else {
            Iterator it = collectionValues.iterator();
            int i2 = 0;
            while (it.hasNext()) {
                if (((Plugin) it.next()).isEnabled() && (i2 = i2 + 1) < 0) {
                    CollectionsKt.throwCountOverflow();
                }
            }
            i = i2;
        }
        boolean zIsCurrentThemeDark = Theme.isCurrentThemeDark();
        addDonateSection(items, LocaleController.getString(R.string.Support), this.donates, MapsKt.mapOf(TuplesKt.to("mastercard", new IconInfo(R.drawable.mastercard_icon, zIsCurrentThemeDark ? -1 : -16777216)), TuplesKt.to("tonkeeper", new IconInfo(R.drawable.ton_icon, zIsCurrentThemeDark ? -14207411 : -15727073)), TuplesKt.to("space", new IconInfo(R.drawable.ton_space_icon, -13587978)), TuplesKt.to("boosty", new IconInfo(R.drawable.boosty_icon, zIsCurrentThemeDark ? -1118482 : -14406868))), 0);
        items.add(UItem.asHeader("Google"));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.CRASHLYTICS, (CharSequence) "Crashlytics", ExteraConfig.getUseGoogleCrashlytics(), (CharSequence) null, 0, false, "crashlytics", false, R.drawable.msg_report, 184, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.ANALYTICS, (CharSequence) "Analytics", ExteraConfig.getUseGoogleAnalytics(), (CharSequence) null, 0, false, "analytics", false, R.drawable.msg_data, 184, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.AnalyticsInfo)));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.EXPORT_SETTINGS, (CharSequence) LocaleController.getString(R.string.ExportSettings), R.drawable.msg_settings, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "exportSettings", false, false, (Boolean) null, 1912, (Object) null));
        ExtensionsKt.addIf(items, true /* StaticGram: export is available to everyone (exteraGram: developers only) */, BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.EXPORT_DATA, (CharSequence) LocaleController.getString(R.string.ExportData), R.drawable.msg_archive, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "exportData", false, false, (Boolean) null, 1912, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.IMPORT_SETTINGS, (CharSequence) LocaleController.getString(R.string.ImportSettings), R.drawable.msg_download, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "importSettings", false, false, (Boolean) null, 1912, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.RESET_SETTINGS, (CharSequence) LocaleController.getString(R.string.ResetSettings), R.drawable.msg_reset, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "resetSettings", false, false, (Boolean) null, 1912, (Object) null));
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, (Enum) OtherItem.DELETE_ACCOUNT, (CharSequence) LocaleController.getString(R.string.DeleteAccount), R.drawable.msg_clearcache, (CharSequence) null, (CharSequence) null, (Boolean) null, false, "deleteAccount", true, false, (Boolean) null, 1656, (Object) null));
        items.add(UItem.asShadow());
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        OtherItem otherItem = OtherItem.DONATE;
        if (i >= otherItem.getId() && i < otherItem.getId() + this.donates.size()) {
            return handleDonateLongClick(item, view);
        }
        return super.onLongClick(item, view, position, x, y);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        OtherItem otherItem = OtherItem.DONATE;
        if (i >= otherItem.getId() && i < otherItem.getId() + this.donates.size()) {
            Donate donate = getDonate(i);
            if (donate != null) {
                handleDonateClick(donate);
            }
            return;
        }
        if (i <= 0 || i >= otherItem.getId()) {
            return;
        }
        switch (WhenMappings.$EnumSwitchMapping$0[OtherItem.getEntries().get(i - 1).ordinal()]) {
            case 1:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new androidx.core.util.Consumer() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda2
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setUseGoogleCrashlytics(((Boolean) obj).booleanValue());
                    }
                });
                handleCrashlyticsClick();
                break;
            case 2:
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new androidx.core.util.Consumer() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda3
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setUseGoogleAnalytics(((Boolean) obj).booleanValue());
                    }
                });
                handleAnalyticsClick();
                break;
            case 3:
                handleResetSettingsClick();
                break;
            case 4:
                handleDeleteAccountClick();
                break;
            case 5:
                PreferencesUtils.getInstance().exportSettings(this);
                break;
            case 6:
                presentFragment(new ExportActivity(null));
                break;
            case 7:
                openImportPicker();
                break;
        }
    }

    private final void addDonateSection(ArrayList<UItem> items, String headerTitle, List<Donate> donateList, Map<String, IconInfo> donateIcons, int idOffset) {
        Object obj;
        IconInfo iconInfo;
        List<Donate> list = donateList;
        if (list == null || list.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(headerTitle));
        boolean z = false;
        int i = 0;
        for (Object obj2 : donateList) {
            int i2 = i + 1;
            if (i < 0) {
                CollectionsKt.throwIndexOverflow();
            }
            Donate donate = (Donate) obj2;
            String lowerCase = donate.getName().toLowerCase(Locale.ROOT);
            Iterator it = donateIcons.entrySet().iterator();
            while (true) {
                obj = null;
                if (!it.hasNext()) {
                    break;
                }
                Object next = it.next();
                if (com.exteragram.messenger.utils.ExteraCompat.contains(lowerCase, (String) ((Map.Entry) next).getKey(), false)) {
                    obj = next;
                    break;
                }
            }
            Map.Entry entry = (Map.Entry) obj;
            if (entry == null || (iconInfo = (IconInfo) entry.getValue()) == null) {
                iconInfo = this.defaultDonateIcon;
            }
            IconInfo iconInfo2 = iconInfo;
            boolean z2 = z;
            UItem uItemUButton$TMessagesProj$default = BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, OtherItem.DONATE.getId() + idOffset + i, (CharSequence) donate.getName(), 0, (CharSequence) null, (CharSequence) null, (Boolean) null, true, (String) null, false, false, (Boolean) null, 1980, (Object) null);
            if (iconInfo2.getIconColor() == 0) {
                uItemUButton$TMessagesProj$default.setIcon(iconInfo2.getIconResId());
            } else {
                uItemUButton$TMessagesProj$default.setColorfulIcon(iconInfo2.getIconResId(), iconInfo2.getIconColor());
            }
            items.add(uItemUButton$TMessagesProj$default);
            i = i2;
            z = z2;
        }
        items.add(UItem.asShadow(AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.GetBadgeInfo), new Runnable() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                SupporterBottomSheet.showAlert(OtherPreferencesActivity.this, null);
            }
        })));
    }

    private final Donate getDonate(int id) {
        int id2 = id - OtherItem.DONATE.getId();
        if (id2 < 0 || id2 >= this.donates.size()) {
            return null;
        }
        return this.donates.get(id2);
    }

    private final void handleCrashlyticsClick() {
        // exteraGram: Firebase crashlytics collection toggle; not in StaticGram
    }

    private final Unit handleAnalyticsClick() {
        // exteraGram: Firebase analytics collection toggle; not in StaticGram
        return Unit.INSTANCE;
    }

    private final void handleResetSettingsClick() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.ResetPreferencesInfo)));
        builder.setTitle(LocaleController.getString(R.string.ResetSettings));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Reset), new AlertDialog.OnButtonClickListener() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda4
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i) {
                OtherPreferencesActivity.$r8$lambda$DShbhmj_8_W37UQxRBukG8QD66E(OtherPreferencesActivity.this, alertDialog, i);
            }
        });
        AlertDialog alertDialogCreate = builder.create();
        showDialog(alertDialogCreate);
        View button = alertDialogCreate.getButton(-1);
        TextView textView = button instanceof TextView ? (TextView) button : null;
        if (textView != null) {
            textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    public static void $r8$lambda$DShbhmj_8_W37UQxRBukG8QD66E(OtherPreferencesActivity otherPreferencesActivity, AlertDialog alertDialog, int i) {
        PreferencesUtils.clearPreferences();
        otherPreferencesActivity.parentLayout.rebuildFragments(0);
        otherPreferencesActivity.getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged, new Object[0]);
        otherPreferencesActivity.getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated, new Object[0]);
        LocaleController.getInstance().recreateFormatters();
        Theme.reloadAllResources(otherPreferencesActivity.getParentActivity());
        BulletinFactory.of(otherPreferencesActivity).createErrorBulletin(LocaleController.getString(R.string.ResetPreferences), otherPreferencesActivity.resourceProvider).show();
    }

    private final void handleDeleteAccountClick() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setMessage(LocaleController.getString(R.string.TosDeclineDeleteAccount));
        builder.setTitle(LocaleController.getString(R.string.DeleteAccount));
        builder.setPositiveButton(LocaleController.getString(R.string.Deactivate), new AlertDialog.OnButtonClickListener() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda5
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i) {
                OtherPreferencesActivity.m1650$r8$lambda$vTi_wXRb_f1M9isgtAXKE_w2do(OtherPreferencesActivity.this, alertDialog, i);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog alertDialogCreate = builder.create();
        alertDialogCreate.setOnShowListener(new DialogInterface.OnShowListener() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda6
            @Override // android.content.DialogInterface.OnShowListener
            public final void onShow(DialogInterface dialogInterface) {
                OtherPreferencesActivity.$r8$lambda$dVNaH0qjGRIV502FTtIuADYa9nA(alertDialogCreate, dialogInterface);
            }
        });
        showDialog(alertDialogCreate);
    }

    /* renamed from: $r8$lambda$vTi_wXRb_f1M9is-gtAXKE_w2do, reason: not valid java name */
    public static void m1650$r8$lambda$vTi_wXRb_f1M9isgtAXKE_w2do(final OtherPreferencesActivity otherPreferencesActivity, AlertDialog alertDialog, int i) {
        final AlertDialog alertDialog2 = new AlertDialog(otherPreferencesActivity.getParentActivity(), 3);
        alertDialog2.setCanCancel(false);
        alertDialog2.show();
        Utilities.globalQueue.postRunnable(new Runnable() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                OtherPreferencesActivity.$r8$lambda$ilM5cqEH6Q8fGXs5ROPy5rBCyec(otherPreferencesActivity, alertDialog2);
            }
        }, 500L);
    }

    public static void $r8$lambda$ilM5cqEH6Q8fGXs5ROPy5rBCyec(final OtherPreferencesActivity otherPreferencesActivity, final AlertDialog alertDialog) {
        ConnectionsManager connectionsManager = otherPreferencesActivity.getConnectionsManager();
        TL_account.deleteAccount deleteaccount = new TL_account.deleteAccount();
        deleteaccount.reason = "StaticGram";
        connectionsManager.sendRequest(deleteaccount, new RequestDelegate() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda8
            @Override // org.telegram.tgnet.RequestDelegate
            public final void run(TLObject tLObject, TLRPC.TL_error tL_error) {
                AndroidUtilities.runOnUIThread(new Runnable() { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$$ExternalSyntheticLambda9
                    @Override // java.lang.Runnable
                    public final void run() {
                        OtherPreferencesActivity.$r8$lambda$wafXI4sX6H1dACsVEwON_Y0b8JM(alertDialog, tLObject, otherPreferencesActivity, tL_error);
                    }
                });
            }
        });
    }

    public static void $r8$lambda$wafXI4sX6H1dACsVEwON_Y0b8JM(AlertDialog alertDialog, TLObject tLObject, OtherPreferencesActivity otherPreferencesActivity, TLRPC.TL_error tL_error) {
        String str;
        try {
            alertDialog.dismiss();
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (tLObject instanceof TLRPC.TL_boolTrue) {
            otherPreferencesActivity.getMessagesController().performLogout(0);
            return;
        }
        if (tL_error == null || tL_error.code != -1000) {
            String string = LocaleController.getString(R.string.ErrorOccurred);
            String strConcat = (tL_error == null || (str = tL_error.text) == null) ? null : "\n".concat(str);
            if (strConcat == null) {
                strConcat = "";
            }
            String str2 = string + strConcat;
            AlertDialog.Builder builder = new AlertDialog.Builder(otherPreferencesActivity.getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.AppName));
            builder.setMessage(str2);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            builder.show();
        }
    }

    public static void $r8$lambda$dVNaH0qjGRIV502FTtIuADYa9nA(AlertDialog alertDialog, DialogInterface dialogInterface) {
        View button = alertDialog.getButton(-1);
        final TextView textView = button instanceof TextView ? (TextView) button : null;
        if (textView == null) {
            return;
        }
        textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        textView.setEnabled(false);
        final CharSequence text = textView.getText();
        new CountDownTimer(30000L, 100L) { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity$handleDeleteAccountClick$1$1$1

            @Override // android.os.CountDownTimer
            public void onTick(long millisUntilFinished) {
                TextView textView2 = textView;
                StringCompanionObject stringCompanionObject = StringCompanionObject.INSTANCE;
                textView2.setText(String.format(Locale.getDefault(), "%s • %d", Arrays.copyOf(new Object[]{text, Long.valueOf((millisUntilFinished / 1000) + 1)}, 2)));
            }

            @Override // android.os.CountDownTimer
            public void onFinish() {
                textView.setText(text);
                textView.setEnabled(true);
            }
        }.start();
    }

    private final void handleDonateClick(final Donate donate) {
        String lowerCase = donate.getName().toLowerCase(Locale.getDefault());
        if (com.exteragram.messenger.utils.ExteraCompat.contains((CharSequence) lowerCase, (CharSequence) "ton", false)) {
            long j = UserConfig.getInstance(this.currentAccount).clientUserId;
            String str = "ton://transfer/" + donate.getDetails() + "?text=" + j;
            if (!Browser.isInternalUri(Uri.parse(str), new boolean[1])) {
                Browser.openUrl(getParentActivity(), str);
                return;
            } else {
                if (AndroidUtilities.addToClipboard(donate.getDetails())) {
                    BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    return;
                }
                return;
            }
        }
        if (com.exteragram.messenger.utils.ExteraCompat.contains((CharSequence) lowerCase, (CharSequence) "boosty", false) && !this.subscribers.isEmpty()) {
            if (getParentActivity() == null) {
                return;
            }
            showDialog(new BoostyBottomSheet(getParentActivity(), this.subscribers) { // from class: com.exteragram.messenger.preferences.OtherPreferencesActivity.handleDonateClick.1
                @Override // com.exteragram.messenger.components.BoostyBottomSheet
                public void onButtonClick() {
                    Browser.openUrl(OtherPreferencesActivity.this.getParentActivity(), donate.getDetails());
                }
            });
        } else if (LinkifyPort.WEB_URL.matcher(donate.getDetails()).matches()) {
            Browser.openUrl(getParentActivity(), donate.getDetails());
        } else if (AndroidUtilities.addToClipboard(donate.getDetails())) {
            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    private final boolean handleDonateLongClick(UItem item, View view) {
        Donate donate = getDonate(item.id);
        if (donate != null && AndroidUtilities.addToClipboard(donate.getDetails())) {
            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
        view.performHapticFeedback(VibratorUtils.getType(3), 1);
        return false;
    }

    // StaticGram: import a settings backup (.staticgram or exteraGram .extera) picked from storage
    private static final int REQUEST_IMPORT_SETTINGS = 4207;

    private void openImportPicker() {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT_SETTINGS);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_SETTINGS || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null || getParentActivity() == null) {
            return;
        }
        final Uri uri = data.getData();
        String name = null;
        try (android.database.Cursor cursor = getParentActivity().getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                if (!cursor.isNull(1) && cursor.getLong(1) > 16 * 1024 * 1024) {
                    name = null;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (name == null || !PreferencesUtils.isBackupFileName(name)) {
            org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ImportSettingsWrongFile)).show();
            return;
        }
        final String ext = name.toLowerCase(Locale.ROOT).endsWith(PreferencesUtils.LEGACY_BACKUP_EXTENSION) ? PreferencesUtils.LEGACY_BACKUP_EXTENSION : PreferencesUtils.BACKUP_EXTENSION;
        final java.io.File file = new java.io.File(getParentActivity().getCacheDir(), "import_settings" + ext);
        try (java.io.InputStream in = getParentActivity().getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            if (in == null) {
                return;
            }
            byte[] buffer = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > 16 * 1024 * 1024) {
                    return;
                }
                out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        if (!PreferencesUtils.getInstance().isBackup(file)) {
            org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ImportSettingsWrongFile)).show();
            return;
        }
        new com.exteragram.messenger.backup.BackupBottomSheet(this, file).showIfPossible();
    }
}
