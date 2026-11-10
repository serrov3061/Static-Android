package com.exteragram.messenger.preferences.appearance;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.MainMenuItem;
import com.exteragram.messenger.TransitionAnimation;
import com.exteragram.messenger.config.BottomNavigationBar;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.components.AltSeekbar;
import com.exteragram.messenger.utils.ExtensionsKt;
import com.exteragram.messenger.utils.ui.PopupUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.Predicate;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.StringCompanionObject;
import kotlin.math.MathKt;
import kotlin.ranges.RangesKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

public final class AppNavigationPreferencesActivity extends BasePreferencesActivity {
    private CharSequence[] bottomNavigationModes;
    private AltSeekbar predictiveBackSeekbar;
    private Drawable reorderIcon;
    private ActionBarMenuItem resetItem;
    private CharSequence[] tabletModeOptions;
    private CharSequence[] transitionAnimations;
    private final HashMap<Integer, ItemInfo> itemDetails = new HashMap<>();
    private final ArrayList<Integer> stableDividerIds = new ArrayList<>();
    private int nextDividerId = -2000;

    public static final /* synthetic */ class WhenMappings {
        public static final /* synthetic */ int[] $EnumSwitchMapping$0;

        static {
            int[] iArr = new int[AppNavigationItem.values().length];
            try {
                iArr[AppNavigationItem.DRAWER.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[AppNavigationItem.IMMERSIVE_ANIMATION.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                iArr[AppNavigationItem.BOTTOM_NAVIGATION_BAR_MODE.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                iArr[AppNavigationItem.TRANSITION_ANIMATION.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                iArr[AppNavigationItem.TABLET_MODE.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            iArr[AppNavigationItem.TELEGRAM_PREDICTIVE_BACK.ordinal()] = 6; // StaticGram
            iArr[AppNavigationItem.BACK_SCRIM.ordinal()] = 7; // StaticGram
            $EnumSwitchMapping$0 = iArr;
        }
    }

        public enum AppNavigationItem {
        DRAWER,
        IMMERSIVE_ANIMATION,
        BOTTOM_NAVIGATION_BAR_MODE,
        PREDICTIVE_BACK_ANIMATION,
        TRANSITION_ANIMATION,
        TABLET_MODE,
        TELEGRAM_PREDICTIVE_BACK, // StaticGram: appended, ids of the others unchanged
        BACK_SCRIM;

        public static java.util.List<AppNavigationItem> getEntries() {
            return java.util.Arrays.asList(values());
        }

        /* renamed from: Companion, reason: from kotlin metadata */
        public static final Companion INSTANCE;

        private AppNavigationItem() {
        }

        static {
            INSTANCE = new Companion(null);
        }

        public static final class Companion {
            public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
                this();
            }

            private Companion() {
            }

            public final AppNavigationItem fromId(int id) {
                int i = id - 150;
                if (i < 0 || i >= AppNavigationItem.getEntries().size()) {
                    return null;
                }
                return AppNavigationItem.getEntries().get(i);
            }
        }

        public final int getId() {
            return ordinal() + 150;
        }
    }

    public static final /* data */ class ItemInfo {
        private final int iconRes;
        private final CharSequence name;

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ItemInfo)) {
                return false;
            }
            ItemInfo itemInfo = (ItemInfo) other;
            return Intrinsics.areEqual(this.name, itemInfo.name) && this.iconRes == itemInfo.iconRes;
        }

        public int hashCode() {
            return (this.name.hashCode() * 31) + Integer.hashCode(this.iconRes);
        }

        public String toString() {
            CharSequence charSequence = this.name;
            return "ItemInfo(name=" + ((Object) charSequence) + ", iconRes=" + this.iconRes + ")";
        }

        public ItemInfo(CharSequence charSequence, int i) {
            this.name = charSequence;
            this.iconRes = i;
        }

        public final int getIconRes() {
            return this.iconRes;
        }

        public final CharSequence getName() {
            return this.name;
        }
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void initializeOptionStrings() {
        initItemDetails();
        this.tabletModeOptions = new CharSequence[]{LocaleController.getString(R.string.DistanceUnitsAutomatic), LocaleController.getString(R.string.PasswordOn), LocaleController.getString(R.string.PasswordOff)};
        this.bottomNavigationModes = new CharSequence[]{LocaleController.getString(R.string.BottomNavigationModeShow), LocaleController.getString(R.string.BottomNavigationModeHide), LocaleController.getString(R.string.BottomNavigationModeFloating)};
        this.transitionAnimations = new CharSequence[]{LocaleController.getString(R.string.Default), LocaleController.getString(R.string.TransitionAnimationAosp), LocaleController.getString(R.string.TransitionAnimationSpring)};
    }

    private final void initItemDetails() {
        this.itemDetails.put(Integer.valueOf(MainMenuItem.PROFILE.getId()), new ItemInfo(LocaleController.getString(R.string.MyProfile), R.drawable.left_status_profile));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.ARCHIVE.getId()), new ItemInfo(LocaleController.getString(R.string.ArchivedChats), R.drawable.msg_archive));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.BOTS.getId()), new ItemInfo(LocaleController.getString(R.string.FilterBots), R.drawable.msg_bot));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.NEW_GROUP.getId()), new ItemInfo(LocaleController.getString(R.string.NewGroup), R.drawable.msg_groups));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.CONTACTS.getId()), new ItemInfo(LocaleController.getString(R.string.Contacts), R.drawable.msg_contacts));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.NEW_CHANNEL.getId()), new ItemInfo(LocaleController.getString(R.string.NewChannel), R.drawable.msg_channel));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.CALLS.getId()), new ItemInfo(LocaleController.getString(R.string.Calls), R.drawable.msg_calls));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.SAVED.getId()), new ItemInfo(LocaleController.getString(R.string.SavedMessages), R.drawable.msg_saved));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.FEED.getId()), new ItemInfo(LocaleController.getString(R.string.Feed), R.drawable.ic_feed));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.SETTINGS.getId()), new ItemInfo(LocaleController.getString(R.string.Settings), R.drawable.msg_settings_old));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.PLUGINS.getId()), new ItemInfo(LocaleController.getString(R.string.Plugins), R.drawable.msg_plugins));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.BROWSER.getId()), new ItemInfo(LocaleController.getString(R.string.BrowserSettingsTitle), R.drawable.msg2_language));
        this.itemDetails.put(Integer.valueOf(MainMenuItem.QR.getId()), new ItemInfo(LocaleController.getString(R.string.AuthAnotherClient), R.drawable.msg_qrcode));
        ArrayList<Integer> mainMenuHiddenItems = ExteraConfig.getMainMenuHiddenItems();
        final Function1 function1 = new Function1() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda0
            @Override // kotlin.jvm.functions.Function1
            public final Object invoke(Object obj) {
                return Boolean.valueOf(AppNavigationPreferencesActivity.$r8$lambda$abRkaPj59RSDKtqDhmMPduco8WY((Integer) obj));
            }
        };
        mainMenuHiddenItems.removeIf(new Predicate() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda1
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ((Boolean) function1.invoke(obj)).booleanValue();
            }
        });
        this.stableDividerIds.clear();
        this.nextDividerId = -2000;
        Iterator<Integer> it = ExteraConfig.getMainMenuLayout().iterator();
        while (it.hasNext()) {
            if (it.next().intValue() == MainMenuItem.DIVIDER.getId()) {
                ArrayList<Integer> arrayList = this.stableDividerIds;
                int i = this.nextDividerId;
                this.nextDividerId = i - 1;
                arrayList.add(Integer.valueOf(i));
            }
        }
    }

    public static boolean $r8$lambda$abRkaPj59RSDKtqDhmMPduco8WY(Integer num) {
        return num.intValue() == MainMenuItem.DIVIDER.getId();
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public String getTitle() {
        return LocaleController.getString(R.string.AppNavigation);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity, org.telegram.ui.ActionBar.BaseFragment
    public View createView(final Context context) {
        View viewCreateView = super.createView(context);
        if (Build.VERSION.SDK_INT >= 34) {
            final AltSeekbar.OnDrag onDrag = new AltSeekbar.OnDrag() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda2
                @Override // com.exteragram.messenger.preferences.components.AltSeekbar.OnDrag
                public final void run(float f) {
                    AppNavigationPreferencesActivity.$r8$lambda$9W76nbjQWdGOuv7NzhtLBfUwV48(AppNavigationPreferencesActivity.this, f);
                }
            };
            final String string = LocaleController.getString(R.string.PredictiveBackIntensity);
            final String string2 = LocaleController.getString(R.string.BlurOff);
            final String string3 = LocaleController.getString(R.string.PredictiveBackMax);
            AltSeekbar altSeekbar = new AltSeekbar(context, onDrag, 0, 2, string, string2, string3) { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$createView$seekbar$1
                @Override // com.exteragram.messenger.preferences.components.AltSeekbar
                public boolean useExactEndpointHaptic() {
                    return true;
                }

                @Override // com.exteragram.messenger.preferences.components.AltSeekbar
                public CharSequence getTextForHeader() {
                    float fRoundToInt = MathKt.roundToInt(this.currentValue * 10.0f) / 10.0f;
                    if (fRoundToInt <= 0.0f) {
                        return this.leftTextView.getText().toString().toUpperCase(Locale.US);
                    }
                    if (fRoundToInt >= 2.0f) {
                        return this.rightTextView.getText().toString().toUpperCase(Locale.US);
                    }
                    int i = (int) fRoundToInt;
                    if (fRoundToInt == i) {
                        return String.valueOf(i);
                    }
                    StringCompanionObject stringCompanionObject = StringCompanionObject.INSTANCE;
                    return String.format(Locale.US, "%.1f", Arrays.copyOf(new Object[]{Float.valueOf(fRoundToInt)}, 1));
                }
            };
            this.predictiveBackSeekbar = altSeekbar;
            float fCoerceAtMost = RangesKt.coerceAtMost(ExteraConfig.getPredictiveBackIntensity(), 2.0f);
            if (fCoerceAtMost != ExteraConfig.getPredictiveBackIntensity()) {
                ExteraConfig.setPredictiveBackIntensity(fCoerceAtMost);
            }
            altSeekbar.setProgress(fCoerceAtMost);
        }
        ActionBarMenuItem actionBarMenuItemAddItem = this.actionBar.createMenu().addItem(0, R.drawable.msg_reset);
        actionBarMenuItemAddItem.setContentDescription(LocaleController.getString(R.string.Reset));
        actionBarMenuItemAddItem.setOnClickListener(new View.OnClickListener() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                AppNavigationPreferencesActivity.this.resetToDefault();
            }
        });
        this.resetItem = actionBarMenuItemAddItem;
        updateResetButtonVisibility(false);
        getListView$TMessagesProj().allowReorder(true);
        getListView$TMessagesProj().listenReorder(new Utilities.Callback2() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda4
            @Override // org.telegram.messenger.Utilities.Callback2
            public final void run(Object obj, Object obj2) {
                AppNavigationPreferencesActivity.this.updateConfigFromReorder(((Integer) obj).intValue(), (ArrayList) obj2);
            }
        });
        return viewCreateView;
    }

    public static void $r8$lambda$9W76nbjQWdGOuv7NzhtLBfUwV48(AppNavigationPreferencesActivity appNavigationPreferencesActivity, float f) {
        boolean zIsPredictiveBackOff = appNavigationPreferencesActivity.isPredictiveBackOff(ExteraConfig.getPredictiveBackIntensity());
        boolean zIsPredictiveBackOff2 = appNavigationPreferencesActivity.isPredictiveBackOff(f);
        ExteraConfig.setPredictiveBackIntensity(f);
        AltSeekbar altSeekbar = appNavigationPreferencesActivity.predictiveBackSeekbar;
        if (altSeekbar != null) {
            altSeekbar.updateHeader(f);
        }
        if (zIsPredictiveBackOff != zIsPredictiveBackOff2) {
            appNavigationPreferencesActivity.showRestartBulletin();
        }
    }

    private final boolean isPredictiveBackOff(float value) {
        return ((float) MathKt.roundToInt(value * 10.0f)) / 10.0f <= 0.0f;
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean z = Build.VERSION.SDK_INT >= 34 && this.predictiveBackSeekbar != null;
        Drawable drawable = this.reorderIcon;
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(getContext(), R.drawable.list_reorder);
        }
        this.reorderIcon = drawable;
        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        int id = AppNavigationItem.TABLET_MODE.getId();
        String string = LocaleController.getString(R.string.TabletMode);
        CharSequence[] charSequenceArr = this.tabletModeOptions;
        if (charSequenceArr == null) {
            charSequenceArr = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, id, (CharSequence) string, 0, charSequenceArr[ExteraConfig.getTabletMode()], (CharSequence) null, (Boolean) null, false, "tabletMode", false, false, (Boolean) null, 1908, (Object) null));
        int id2 = AppNavigationItem.BOTTOM_NAVIGATION_BAR_MODE.getId();
        String string2 = LocaleController.getString(R.string.BottomNavigationBarMode);
        CharSequence[] charSequenceArr2 = this.bottomNavigationModes;
        if (charSequenceArr2 == null) {
            charSequenceArr2 = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, id2, (CharSequence) string2, 0, charSequenceArr2[BottomNavigationBar.getMode()], (CharSequence) null, (Boolean) null, false, "bottomNavigationBarMode", false, false, (Boolean) null, 1908, (Object) null));
        int id3 = AppNavigationItem.TRANSITION_ANIMATION.getId();
        String string3 = LocaleController.getString(R.string.TransitionAnimation);
        CharSequence[] charSequenceArr3 = this.transitionAnimations;
        if (charSequenceArr3 == null) {
            charSequenceArr3 = null;
        }
        items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, id3, (CharSequence) string3, 0, charSequenceArr3[ExteraConfig.getTransitionAnimation().ordinal()], (CharSequence) null, (Boolean) null, false, "transitionAnimation", false, false, (Boolean) null, 1908, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.TransitionAnimationInfo)));
        ExtensionsKt.addIf(items, Build.VERSION.SDK_INT >= 34, BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, AppNavigationItem.TELEGRAM_PREDICTIVE_BACK.getId(), (CharSequence) LocaleController.getString(R.string.SgTelegramPredictiveBack), ExteraConfig.getTelegramPredictiveBack(), (CharSequence) null, 0, false, "sgTelegramPredictiveBack", false, 0, 440, (Object) null));
        ExtensionsKt.addIf(items, Build.VERSION.SDK_INT >= 34, UItem.asShadow(LocaleController.getString(R.string.SgTelegramPredictiveBackInfo)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, AppNavigationItem.BACK_SCRIM.getId(), (CharSequence) LocaleController.getString(R.string.SgBackScrim), ExteraConfig.getBackScrim(), (CharSequence) null, 0, false, "sgBackScrim", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.SgBackScrimInfo)));
        ExtensionsKt.addIf(items, z && !ExteraConfig.getTelegramPredictiveBack(), UItem.asCustom(AppNavigationItem.PREDICTIVE_BACK_ANIMATION.getId(), this.predictiveBackSeekbar).setLinkAlias("predictiveBackAnimation", this));
        ExtensionsKt.addIf(items, z && !ExteraConfig.getTelegramPredictiveBack(), UItem.asShadow(LocaleController.getString(R.string.PredictiveBackInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AppNavigation)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, AppNavigationItem.DRAWER.getId(), (CharSequence) LocaleController.getString(R.string.NavigationDrawer), ExteraConfig.getNavigationDrawer(), (CharSequence) null, 0, false, "navigationDrawer", false, 0, 440, (Object) null));
        ExtensionsKt.addIf(items, ExteraConfig.getNavigationDrawer(), BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, AppNavigationItem.IMMERSIVE_ANIMATION.getId(), (CharSequence) LocaleController.getString(R.string.NavigationDrawerImmersiveAnimation), ExteraConfig.getImmersiveDrawerAnimation(), (CharSequence) null, 0, false, "immersiveDrawerAnimation", false, 0, 440, (Object) null));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NavigationDrawerInfo)));
        addMenuSection(items, adapter, LocaleController.getString(R.string.MainMenuItems), ExteraConfig.getMainMenuLayout(), true);
        items.add(UItem.asShadow(LocaleController.getString(R.string.MainMenuItemsInfo)));
        if (ExteraConfig.getMainMenuHiddenItems().isEmpty()) {
            return;
        }
        addMenuSection(items, adapter, LocaleController.getString(R.string.MainMenuHiddenItems), ExteraConfig.getMainMenuHiddenItems(), false);
        items.add(UItem.asShadow(null));
    }

    private final void addMenuSection(ArrayList<UItem> items, UniversalAdapter adapter, String header, ArrayList<Integer> ids, boolean mainSection) {
        UItem uItemCreateMenuItem;
        UItem uItemCreateMenuItem2;
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(header));
        adapter.reorderSectionStart();
        Iterator<Integer> it = ids.iterator();
        int i = 0;
        while (it.hasNext()) {
            int iIntValue = it.next().intValue();
            if (iIntValue != MainMenuItem.PLUGINS.getId() || PluginsController.isPluginEngineSupported()) {
                MainMenuItem mainMenuItem = MainMenuItem.DIVIDER;
                if (iIntValue == mainMenuItem.getId()) {
                    if (mainSection && i < this.stableDividerIds.size()) {
                        UItem uItemCreateMenuItem3 = createMenuItem(this.stableDividerIds.get(i).intValue(), null);
                        if (uItemCreateMenuItem3 != null) {
                            items.add(uItemCreateMenuItem3);
                        }
                    } else if (!mainSection && (uItemCreateMenuItem = createMenuItem(mainMenuItem.getId(), null)) != null) {
                        items.add(uItemCreateMenuItem);
                    }
                    i++;
                } else {
                    ItemInfo itemInfo = this.itemDetails.get(Integer.valueOf(iIntValue));
                    if (itemInfo != null && (uItemCreateMenuItem2 = createMenuItem(iIntValue, itemInfo)) != null) {
                        items.add(uItemCreateMenuItem2);
                    }
                }
            }
        }
        adapter.reorderSectionEnd();
        if (mainSection) {
            items.add(BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, -200, (CharSequence) LocaleController.getString(R.string.MainMenuAddDivider), R.drawable.msg_add, (CharSequence) null, (CharSequence) null, (Boolean) null, false, (String) null, false, true, (Boolean) null, 1464, (Object) null));
        }
        adapter.whiteSectionEnd();
    }

    private final UItem createMenuItem(int id, ItemInfo info) {
        UItem uItemUButton$TMessagesProj$default;
        if (id <= -2000 || id == MainMenuItem.DIVIDER.getId()) {
            uItemUButton$TMessagesProj$default = BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, id, (CharSequence) LocaleController.getString(R.string.MainMenuDivider), R.drawable.msg_block, (CharSequence) null, (CharSequence) null, (Boolean) null, false, (String) null, false, false, (Boolean) null, 1976, (Object) null);
        } else {
            uItemUButton$TMessagesProj$default = info != null ? BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, id, info.getName(), info.getIconRes(), (CharSequence) null, (CharSequence) null, (Boolean) null, false, (String) null, false, false, (Boolean) null, 1976, (Object) null) : null;
        }
        if (uItemUButton$TMessagesProj$default == null) {
            return null;
        }
        uItemUButton$TMessagesProj$default.object2 = this.reorderIcon;
        return uItemUButton$TMessagesProj$default;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void updateConfigFromReorder(int sectionId, ArrayList<UItem> uItems) {
        ArrayList arrayList = new ArrayList();
        ArrayList arrayList2 = new ArrayList();
        Iterator<UItem> it = uItems.iterator();
        while (it.hasNext()) {
            UItem next = it.next();
            int i = next.id;
            if (i > -2000 && i != MainMenuItem.DIVIDER.getId()) {
                arrayList.add(Integer.valueOf(next.id));
            } else if (sectionId == 0) {
                arrayList.add(Integer.valueOf(MainMenuItem.DIVIDER.getId()));
                int i2 = next.id;
                if (i2 > -2000) {
                    i2 = this.nextDividerId;
                    this.nextDividerId = i2 - 1;
                }
                arrayList2.add(Integer.valueOf(i2));
            }
        }
        if (sectionId == 0) {
            this.stableDividerIds.clear();
            this.stableDividerIds.addAll(arrayList2);
            if (BottomNavigationBar.hidden()) {
                int id = MainMenuItem.SETTINGS.getId();
                if (!arrayList.contains(Integer.valueOf(id))) {
                    arrayList.add(Integer.valueOf(id));
                }
            }
            ExteraConfig.getMainMenuLayout().clear();
            ExteraConfig.getMainMenuLayout().addAll(arrayList);
        } else if (sectionId == 1) {
            if (BottomNavigationBar.hidden()) {
                arrayList.remove(Integer.valueOf(MainMenuItem.SETTINGS.getId()));
            }
            ExteraConfig.getMainMenuHiddenItems().clear();
            ExteraConfig.getMainMenuHiddenItems().addAll(arrayList);
        }
        saveAndNotify();
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        AppNavigationItem appNavigationItemFromId = AppNavigationItem.INSTANCE.fromId(i);
        int i2 = 0;
        if (appNavigationItemFromId != null) {
            int i3 = WhenMappings.$EnumSwitchMapping$0[appNavigationItemFromId.ordinal()];
            if (i3 == 1) {
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda5
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setNavigationDrawer(((Boolean) obj).booleanValue());
                    }
                });
                getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged, new Object[0]);
                UniversalAdapter universalAdapter = getListView$TMessagesProj().adapter;
                if (universalAdapter != null) {
                    universalAdapter.update(true);
                }
                this.parentLayout.rebuildFragments(0);
                return;
            }
            if (i3 == 7) { // StaticGram: darkening behind on swipe back
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                    @Override
                    public final void accept(Object obj) {
                        ExteraConfig.setBackScrim(((Boolean) obj).booleanValue());
                    }
                });
                return;
            }
            if (i3 == 6) { // StaticGram: back gesture style
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() {
                    @Override
                    public final void accept(Object obj) {
                        ExteraConfig.setTelegramPredictiveBack(((Boolean) obj).booleanValue());
                    }
                });
                UniversalAdapter adapter = getListView$TMessagesProj().adapter;
                if (adapter != null) {
                    adapter.update(true);
                }
                return;
            }
            if (i3 == 2) {
                toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda6
                    @Override // androidx.core.util.Consumer
                    public final void accept(Object obj) {
                        ExteraConfig.setImmersiveDrawerAnimation(((Boolean) obj).booleanValue());
                    }
                });
                return;
            }
            if (i3 == 3) {
                CharSequence[] charSequenceArr = this.bottomNavigationModes;
                if (charSequenceArr == null) {
                    charSequenceArr = null;
                }
                showListDialog(item, charSequenceArr, LocaleController.getString(R.string.BottomNavigationBarMode), BottomNavigationBar.getMode(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda7
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i4) {
                        AppNavigationPreferencesActivity.$r8$lambda$wUuxrEg0ymhtYK31jN2UMNG3nBo(AppNavigationPreferencesActivity.this, i4);
                    }
                });
                return;
            }
            if (i3 != 4) {
                if (i3 != 5) {
                    return;
                }
                CharSequence[] charSequenceArr2 = this.tabletModeOptions;
                showListDialog(item, charSequenceArr2 == null ? null : charSequenceArr2, LocaleController.getString(R.string.TabletMode), ExteraConfig.getTabletMode(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda9
                    @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                    public final void onClick(int i4) {
                        AppNavigationPreferencesActivity.$r8$lambda$FKdqUjxjgDeAzPXGJsdoCPgJ80I(AppNavigationPreferencesActivity.this, i4);
                    }
                });
                return;
            }
            CharSequence[] charSequenceArr3 = this.transitionAnimations;
            if (charSequenceArr3 == null) {
                charSequenceArr3 = null;
            }
            showListDialog(item, charSequenceArr3, LocaleController.getString(R.string.TransitionAnimation), ExteraConfig.getTransitionAnimation().ordinal(), new PopupUtils.OnItemClickListener() { // from class: com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity$$ExternalSyntheticLambda8
                @Override // com.exteragram.messenger.utils.ui.PopupUtils.OnItemClickListener
                public final void onClick(int i4) {
                    AppNavigationPreferencesActivity.$r8$lambda$OcnwmapLcYeDEa0ramhMi8X2sWc(i4);
                }
            });
            return;
        }
        if (i == -200) {
            ArrayList<Integer> arrayList = this.stableDividerIds;
            int i4 = this.nextDividerId;
            this.nextDividerId = i4 - 1;
            arrayList.add(Integer.valueOf(i4));
            ExteraConfig.getMainMenuLayout().add(Integer.valueOf(MainMenuItem.DIVIDER.getId()));
            saveAndNotify();
            return;
        }
        if (i <= -2000) {
            int iIndexOf = this.stableDividerIds.indexOf(Integer.valueOf(i));
            if (iIndexOf != -1) {
                int size = ExteraConfig.getMainMenuLayout().size();
                int i5 = 0;
                while (true) {
                    if (i2 >= size) {
                        i2 = -1;
                        break;
                    }
                    Integer num = ExteraConfig.getMainMenuLayout().get(i2);
                    int id = MainMenuItem.DIVIDER.getId();
                    if (num != null && num.intValue() == id) {
                        if (i5 == iIndexOf) {
                            break;
                        } else {
                            i5++;
                        }
                    }
                    i2++;
                }
                if (i2 != -1) {
                    this.stableDividerIds.remove(iIndexOf);
                    ExteraConfig.getMainMenuLayout().remove(i2);
                    saveAndNotify();
                    return;
                }
                return;
            }
            return;
        }
        MainMenuItem mainMenuItem = MainMenuItem.DIVIDER;
        if (i == mainMenuItem.getId()) {
            ExteraConfig.getMainMenuHiddenItems().remove(Integer.valueOf(i));
            saveAndNotify();
            return;
        }
        if (BottomNavigationBar.hidden() && i == MainMenuItem.SETTINGS.getId() && ExteraConfig.getMainMenuLayout().contains(Integer.valueOf(i))) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.MainMenuRemoveSettingsInfo)).show();
            return;
        }
        if (ExteraConfig.getMainMenuLayout().contains(Integer.valueOf(i))) {
            ExteraConfig.getMainMenuLayout().remove(Integer.valueOf(i));
            if (i != mainMenuItem.getId() && !ExteraConfig.getMainMenuHiddenItems().contains(Integer.valueOf(i))) {
                ExteraConfig.getMainMenuHiddenItems().add(0, Integer.valueOf(i));
            }
        } else if (ExteraConfig.getMainMenuHiddenItems().contains(Integer.valueOf(i))) {
            ExteraConfig.getMainMenuHiddenItems().remove(Integer.valueOf(i));
            ExteraConfig.getMainMenuLayout().add(Integer.valueOf(i));
        }
        saveAndNotify();
    }

    public static void $r8$lambda$wUuxrEg0ymhtYK31jN2UMNG3nBo(AppNavigationPreferencesActivity appNavigationPreferencesActivity, int i) {
        SharedPreferences.Editor editorEdit = ExteraConfig.getPreferences().edit();
        editorEdit.putInt("bottomNavigationBarMode", i);
        editorEdit.apply();
        BottomNavigationBar.setMode(i);
        ExteraConfig.ensureSettingsVisibility();
        appNavigationPreferencesActivity.getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged, new Object[0]);
        appNavigationPreferencesActivity.refreshEditorList();
        updateResetButtonVisibility$default(appNavigationPreferencesActivity, false, 1, null);
        appNavigationPreferencesActivity.parentLayout.rebuildFragments(0);
    }

    public static void $r8$lambda$OcnwmapLcYeDEa0ramhMi8X2sWc(int i) {
        ExteraConfig.setTransitionAnimation(TransitionAnimation.getEntries().get(i));
        if (ExteraConfig.getTransitionAnimation() != TransitionAnimation.DEFAULT) {
            SharedPreferences.Editor editorEdit = MessagesController.getGlobalMainSettings().edit();
            editorEdit.putBoolean("view_animations", true);
            editorEdit.apply();
            SharedConfig.setAnimationsEnabled(true);
        }
    }

    public static void $r8$lambda$FKdqUjxjgDeAzPXGJsdoCPgJ80I(AppNavigationPreferencesActivity appNavigationPreferencesActivity, int i) {
        ExteraConfig.setTabletMode(i);
        appNavigationPreferencesActivity.showRestartBulletin();
    }

    private final void saveAndNotify() {
        ExteraConfig.saveMainMenuLayout();
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged, new Object[0]);
        refreshEditorList();
        updateResetButtonVisibility$default(this, false, 1, null);
    }

    private final UniversalRecyclerView refreshEditorList() {
        UniversalRecyclerView listView$TMessagesProj = getListView$TMessagesProj();
        listView$TMessagesProj.hideSelector(false);
        listView$TMessagesProj.cancelClickRunnables(false);
        listView$TMessagesProj.adapter.update(true);
        return listView$TMessagesProj;
    }

    public static /* synthetic */ void updateResetButtonVisibility$default(AppNavigationPreferencesActivity appNavigationPreferencesActivity, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = true;
        }
        appNavigationPreferencesActivity.updateResetButtonVisibility(z);
    }

    private final void updateResetButtonVisibility(boolean animated) {
        if (this.resetItem == null) {
            return;
        }
        AndroidUtilities.updateViewVisibilityAnimated(this.resetItem, !Intrinsics.areEqual(ExteraConfig.getMainMenuLayout(), ExteraConfig.getDefaultMainMenuLayout()), 0.5f, animated);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void resetToDefault() {
        ExteraConfig.getMainMenuLayout().clear();
        ExteraConfig.getMainMenuLayout().addAll(ExteraConfig.getDefaultMainMenuLayout());
        ExteraConfig.getMainMenuHiddenItems().clear();
        for (MainMenuItem mainMenuItem : MainMenuItem.getEntries()) {
            if (mainMenuItem != MainMenuItem.DIVIDER && !ExteraConfig.getMainMenuLayout().contains(Integer.valueOf(mainMenuItem.getId())) && (mainMenuItem != MainMenuItem.PLUGINS || PluginsController.isPluginEngineSupported())) {
                ExteraConfig.getMainMenuHiddenItems().add(Integer.valueOf(mainMenuItem.getId()));
            }
        }
        this.stableDividerIds.clear();
        this.nextDividerId = -2000;
        Iterator<Integer> it = ExteraConfig.getMainMenuLayout().iterator();
        while (it.hasNext()) {
            if (it.next().intValue() == MainMenuItem.DIVIDER.getId()) {
                ArrayList<Integer> arrayList = this.stableDividerIds;
                int i = this.nextDividerId;
                this.nextDividerId = i - 1;
                arrayList.add(Integer.valueOf(i));
            }
        }
        ExteraConfig.saveMainMenuLayout();
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged, new Object[0]);
        refreshEditorList();
        updateResetButtonVisibility$default(this, false, 1, null);
    }
}
