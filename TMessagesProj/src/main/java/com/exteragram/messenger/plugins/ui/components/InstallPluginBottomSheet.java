package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.components.CheckBoxRow;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.pip.PipController;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.translator.core.TranslationError;
import com.exteragram.messenger.utils.chats.ChatUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;
import com.exteragram.messenger.utils.ui.UIUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Ref;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.HintView2;

public final class InstallPluginBottomSheet extends BottomSheetWithRecyclerListView {
    private static final float SECTION_GAP = 12.0f;
    private LinearLayout actionsPill;
    private UniversalAdapter adapter;
    private boolean blocked;
    private LinearLayout bottomContainer;
    private final ButtonWithCounterView button;
    private volatile boolean cancellationRequested;
    private boolean contentReady;
    private HintView2 currentHint;
    private Runnable delayedLoadingRunnable;
    private final TranslatableText descriptionText;
    private View descriptionView;
    // exteraGram 13.1.1: static analysis of the plugin code before installing
    private View analysisView;
    private com.exteragram.messenger.plugins.analyzer.PluginStaticAnalyzer.Report analysisReport;
    private boolean resultDelivered;
    private View enableAfterInstallRow;
    private boolean enableAfterInstallation;
    private String errorText;
    private TextView errorTextView;
    private View errorView;
    private final BaseFragment fragment;
    private View headerView;
    private boolean installing;
    private final boolean isUpdate;
    private final TranslatableText nameText;
    private final PluginInstallParams params;
    private final Plugin plugin;
    private final Utilities.Callback<RequiredPluginInfo> requirementDelegate;
    private final List<RequiredPluginInfo> requirements;
    private FrameLayout sheetContainer;
    private CharSequence titleText;
    private PluginTranslateButton translateButton;
    private boolean translating;
    private final boolean unknownSourceBlocked;

    public InstallPluginBottomSheet(BaseFragment baseFragment, PluginsController.PluginValidationResult pluginValidationResult, PluginInstallParams pluginInstallParams) {
        super(baseFragment.getParentActivity(), baseFragment, false, false, false, true, BottomSheetWithRecyclerListView.ActionBarType.FADING, baseFragment.getResourceProvider());
        this.fragment = baseFragment;
        this.params = pluginInstallParams;
        Plugin plugin = pluginValidationResult.getPlugin();
        if (plugin == null) {
            throw new IllegalArgumentException("Required value was null.");
        }
        this.plugin = plugin;
        this.isUpdate = PluginsController.Companion.getInstance().getPlugins().containsKey(plugin.getId());
        this.nameText = new TranslatableText(plugin.getName(), text -> text, () -> {
            onTextTransitionUpdate();
            return Unit.INSTANCE;
        });
        this.descriptionText = new TranslatableText(plugin.getDescription(), text -> LocaleUtils.fullyFormatText(text, this.fragment, this::dismiss), () -> {
            onTextTransitionUpdate();
            return Unit.INSTANCE;
        });
        List<RequiredPluginInfo> requiredPlugins = pluginInstallParams.getRequiredPlugins();
        this.requirements = requiredPlugins != null ? new ArrayList<>(requiredPlugins) : new ArrayList<>();
        boolean z = (pluginInstallParams.getTrusted() || ExteraConfig.getPluginsUnknownSources()) ? false : true;
        this.unknownSourceBlocked = z;
        this.blocked = z || pluginInstallParams.getBlockedTitle() != null;
        if (pluginInstallParams.getEnableAfterInstall() != null) {
            this.enableAfterInstallation = pluginInstallParams.getEnableAfterInstall();
        }
        this.requirementDelegate = new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                InstallPluginBottomSheet.this.onRequirementAction((RequiredPluginInfo) obj);
            }
        };
        setDelegate(new BottomSheet.BottomSheetDelegate() {
            @Override
            public boolean canDismiss() {
                return !InstallPluginBottomSheet.this.installing;
            }
        });
        Activity parentActivity = baseFragment.getParentActivity();
        this.occupyNavigationBar = true;
        setApplyBottomPadding(false);
        this.topPadding = 0.2f;
        int i = Theme.key_windowBackgroundGray;
        setBackgroundColor(getThemedColor(i));
        ActionBar actionBar = this.actionBar;
        actionBar.setBackgroundColor(getThemedColor(i));
        actionBar.setCastShadows(false);
        this.showShadow = false;
        this.titleText = plugin.getName();
        updateTitle();
        this.takeTranslationIntoAccount = true;
        RecyclerListView recyclerListView = this.recyclerListView;
        recyclerListView.setClipChildren(false);
        recyclerListView.setClipToPadding(false);
        recyclerListView.setOverScrollMode(2);
        int i2 = this.backgroundPaddingLeft;
        recyclerListView.setPadding(i2, 0, i2, 0);
        recyclerListView.setSections();
        DefaultItemAnimator defaultItemAnimator = new DefaultItemAnimator() {
            @Override
            public void onMoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                super.onMoveAnimationUpdate(holder);
                InstallPluginBottomSheet.this.containerView.invalidate();
            }
        };
        defaultItemAnimator.setSupportsChangeAnimations(false);
        defaultItemAnimator.setDelayAnimations(false);
        defaultItemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        defaultItemAnimator.setDurations(350L);
        recyclerListView.setItemAnimator(defaultItemAnimator);
        this.recyclerListView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
            @Override
            public final void onItemClick(View view, int i3) {
                InstallPluginBottomSheet.$r8$lambda$0bzmhvmogRnBU8vwylqF8Odp7Is(InstallPluginBottomSheet.this, view, i3);
            }
        });
        this.headerView = buildHeader(parentActivity);
        this.descriptionView = buildDescription(parentActivity);
        this.errorView = buildError(parentActivity);
        checkTranslatable();
        ButtonWithCounterView buttonWithCounterView = new ButtonWithCounterView(parentActivity, true, this.resourcesProvider);
        buttonWithCounterView.setRound();
        this.button = buttonWithCounterView;
        applyButtonState(false);
        LinearLayout linearLayout = new LinearLayout(parentActivity);
        linearLayout.setOrientation(1);
        linearLayout.setBackground(UIUtil.createBottomFade(getThemedColor(i)));
        linearLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48.0f));
        int iDp = this.backgroundPaddingLeft + AndroidUtilities.dp(SECTION_GAP);
        layoutParams.setMargins(iDp, AndroidUtilities.dp(SECTION_GAP), iDp, AndroidUtilities.dp(SECTION_GAP));
        linearLayout.addView(buttonWithCounterView, layoutParams);
        if (!plugin.isEnabled() && !ExteraConfig.getPluginsSafeMode()) {
            View viewCreateEnableAfterInstallRow = createEnableAfterInstallRow(parentActivity);
            viewCreateEnableAfterInstallRow.setVisibility(this.blocked ? 8 : 0);
            linearLayout.addView(viewCreateEnableAfterInstallRow, LayoutHelper.createLinear(-2, -2, 1, 0.0f, 0.0f, 0.0f, 8.0f));
            this.enableAfterInstallRow = viewCreateEnableAfterInstallRow;
        }
        linearLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public final void onLayoutChange(View view, int i3, int i4, int i5, int i6, int i7, int i8, int i9, int i10) {
                InstallPluginBottomSheet.$r8$lambda$JcFMrAkO6mT6xcKCfpOjvc83vx8(InstallPluginBottomSheet.this, view, i3, i4, i5, i6, i7, i8, i9, i10);
            }
        });
        this.bottomContainer = linearLayout;
        FrameLayout frameLayout = this.sheetContainer;
        if (frameLayout != null) {
            frameLayout.addView(linearLayout, LayoutHelper.createFrame(-1, -2, 80));
        }
        ActionBarMenu actionBarMenuCreateMenu = this.actionBar.createMenu();
        actionBarMenuCreateMenu.setPadding(0, 0, AndroidUtilities.dp(3.5f), 0);
        actionBarMenuCreateMenu.addItem(1, R.drawable.msg_openin);
        this.actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    InstallPluginBottomSheet.this.dismiss();
                } else {
                    if (id != 1) {
                        return;
                    }
                    InstallPluginBottomSheet.this.openPluginFile();
                }
            }
        });
        this.contentReady = true;
        startStaticAnalysis();
        UniversalAdapter universalAdapter = this.adapter;
        if (universalAdapter != null) {
            universalAdapter.update(false);
        }
        this.layoutManager.scrollToPositionWithOffset(0, 0);
    }

    public static void $r8$lambda$0bzmhvmogRnBU8vwylqF8Odp7Is(InstallPluginBottomSheet installPluginBottomSheet, View view, int i) {
        UItem item;
        UniversalAdapter universalAdapter = installPluginBottomSheet.adapter;
        if (universalAdapter == null || (item = universalAdapter.getItem(i)) == null) {
            return;
        }
        Object obj = item.object2;
        String str = obj instanceof String ? (String) obj : null;
        if (str == null) {
            return;
        }
        com.exteragram.messenger.utils.ExternalTelegram.openUrl(installPluginBottomSheet.getContext(), str);
    }

    public static void $r8$lambda$JcFMrAkO6mT6xcKCfpOjvc83vx8(InstallPluginBottomSheet installPluginBottomSheet, View view, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
        int i9 = i4 - i2;
        if (installPluginBottomSheet.recyclerListView.getPaddingBottom() != i9) {
            RecyclerListView recyclerListView = installPluginBottomSheet.recyclerListView;
            int i10 = installPluginBottomSheet.backgroundPaddingLeft;
            recyclerListView.setPadding(i10, 0, i10, i9);
        }
    }

    @Override
    public CharSequence getTitle() {
        CharSequence charSequence = this.titleText;
        return charSequence == null ? "" : charSequence;
    }

    @Override
    public RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        Context context = getContext();
        int i = this.currentAccount;
        BaseFragment baseFragment = getBaseFragment();
        UniversalAdapter universalAdapter = new UniversalAdapter(listView, context, i, baseFragment != null ? baseFragment.getClassGuid() : 0, true, new Utilities.Callback2() {
            @Override
            public final void run(Object obj, Object obj2) {
                InstallPluginBottomSheet.this.fillItems((ArrayList) obj, (UniversalAdapter) obj2);
            }
        }, this.resourcesProvider);
        this.adapter = universalAdapter;
        return universalAdapter;
    }

    public final void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        View view;
        if (this.contentReady) {
            View view2 = this.headerView;
            if (view2 != null) {
                items.add(UItem.asCustomShadow(view2));
                items.add(spacer(20.0f));
            }
            View view3 = this.descriptionView;
            if (view3 != null) {
                items.add(UItem.asCustom(view3));
            }
            if (this.analysisView != null) {
                items.add(spacer(12.0f));
                items.add(UItem.asCustom(this.analysisView));
            }
            if (!this.requirements.isEmpty()) {
                items.add(spacer(SECTION_GAP));
                items.add(header(LocaleController.getString(R.string.PluginRequired)));
                Iterator<RequiredPluginInfo> it = this.requirements.iterator();
                while (it.hasNext()) {
                    items.add(RequiredPluginCell.Factory.Companion.asRequirement(it.next(), this.requirementDelegate));
                }
            }
            List<String> requirements = this.plugin.getRequirements();
            List<String> list = requirements;
            int i = 0;
            if (list != null && !list.isEmpty()) {
                items.add(spacer(SECTION_GAP));
                items.add(header(LocaleController.getString(R.string.PluginRequirements)));
                int i2 = 0;
                for (String requirement : requirements) {
                    items.add(pipRow(i2 + 1000, requirement));
                    i2++;
                }
            }
            List<String> bundledPackages = this.params.getBundledPackages();
            if (bundledPackages != null) {
                if (bundledPackages.isEmpty()) {
                    bundledPackages = null;
                }
                if (bundledPackages != null) {
                    items.add(spacer(SECTION_GAP));
                    items.add(header(LocaleController.getString(R.string.PluginBundledPackages)));
                    for (String bundledPackage : bundledPackages) {
                        items.add(wheelRow(i + 1500, bundledPackage));
                        i++;
                    }
                }
            }
            if (this.errorText == null || (view = this.errorView) == null) {
                return;
            }
            items.add(spacer(SECTION_GAP));
            items.add(UItem.asCustomShadow(view));
        }
    }

    private final UItem pipRow(int id, String requirement) {
        String string = StringsKt.trim((CharSequence) requirement).toString();
        PluginRequirementsView.Companion companion = PluginRequirementsView.Companion;
        String strRequirementName = companion.requirementName(string);
        String strRequirementUrl = companion.requirementUrl(string);
        if (strRequirementUrl == null) {
            return UItem.asSettingsCell(id, string, (CharSequence) null);
        }
        String string2 = StringsKt.trim((CharSequence) StringsKt.removePrefix(string, (CharSequence) strRequirementName)).toString();
        UItem uItemAsButton = UItem.asButton(id, strRequirementName, string2.length() != 0 ? string2 : null);
        uItemAsButton.object2 = strRequirementUrl;
        return uItemAsButton;
    }

    private final UItem wheelRow(int id, String fileName) {
        String[] parts = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".whl") ? fileName.substring(0, fileName.length() - 4).split("-", -1) : null;
        if (parts == null || parts.length < 2) {
            return UItem.asSettingsCell(id, fileName, (CharSequence) null);
        }
        return UItem.asSettingsCell(id, parts[0].replace('_', '-'), (CharSequence) parts[1]);
    }

    private final UItem header(CharSequence text) {
        return UItem.asHeader(text).onBind(new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                InstallPluginBottomSheet.$r8$lambda$axbWnxDrBj8SEXz68q0_0EeZ7WI((View) obj);
            }
        });
    }

    public static void $r8$lambda$axbWnxDrBj8SEXz68q0_0EeZ7WI(View view) {
        HeaderCell headerCell = view instanceof HeaderCell ? (HeaderCell) view : null;
        if (headerCell == null) {
            return;
        }
        TextView textView = headerCell.getTextView();
        ViewGroup.LayoutParams layoutParams = textView.getLayoutParams();
        FrameLayout.LayoutParams layoutParams2 = layoutParams instanceof FrameLayout.LayoutParams ? (FrameLayout.LayoutParams) layoutParams : null;
        if (layoutParams2 == null) {
            return;
        }
        int iDp = AndroidUtilities.dp(6.0f);
        int iDp2 = AndroidUtilities.dp(34.0f);
        if (layoutParams2.topMargin == iDp && textView.getMinHeight() == iDp2) {
            return;
        }
        layoutParams2.topMargin = iDp;
        textView.setMinHeight(iDp2);
        headerCell.requestLayout();
    }

    private final UItem spacer(float heightDp) {
        UItem uItemAsSpace = UItem.asSpace(AndroidUtilities.dp(heightDp));
        uItemAsSpace.transparent = true;
        return uItemAsSpace;
    }

    @Override
    public void onViewCreated(FrameLayout containerView) {
        super.onViewCreated(containerView);
        this.sheetContainer = containerView;
    }

    @Override
    public void onPreDraw(Canvas canvas, int top, float progressToFullView) {
        LinearLayout linearLayout = this.actionsPill;
        if (linearLayout != null) {
            float f = 1.0f - progressToFullView;
            linearLayout.setAlpha(f);
            int childCount = linearLayout.getChildCount();
            for (int i = 0; i < childCount; i++) {
                linearLayout.getChildAt(i).setClickable(f > 0.5f);
            }
        }
    }

    public final void onRequirementAction(final RequiredPluginInfo info) {
        if (info.isSatisfied()) {
            return;
        }
        if (info.state == 1) {
            PluginsController.Companion.getInstance().setPluginEnabled(info.id, true, error -> AndroidUtilities.runOnUIThread(() -> InstallPluginBottomSheet.$r8$lambda$S6yUHpGKTwONSUPNmjtxj1U2NIs(error, info, this)));
            return;
        }
        String str = info.downloadUrl;
        if (str == null) {
            return;
        }
        dismiss();
        com.exteragram.messenger.utils.ExternalTelegram.openUrl(getContext(), str);
    }

    public static void $r8$lambda$S6yUHpGKTwONSUPNmjtxj1U2NIs(String str, RequiredPluginInfo requiredPluginInfo, InstallPluginBottomSheet installPluginBottomSheet) {
        String name;
        if (str == null) {
            Plugin plugin = PluginsController.Companion.getInstance().getPlugins().get(requiredPluginInfo.id);
            requiredPluginInfo.currentVersion = plugin != null ? plugin.getVersion() : null;
            if (plugin == null || (name = plugin.getName()) == null) {
                name = requiredPluginInfo.name;
            }
            requiredPluginInfo.name = name;
            requiredPluginInfo.state = 3;
            installPluginBottomSheet.errorText = null;
            UniversalAdapter universalAdapter = installPluginBottomSheet.adapter;
            if (universalAdapter != null) {
                universalAdapter.update(true);
            }
            installPluginBottomSheet.checkRequirementsSatisfied();
            return;
        }
        installPluginBottomSheet.showError(str);
    }

    private final void checkRequirementsSatisfied() {
        if (this.unknownSourceBlocked || !this.blocked || this.requirements.isEmpty()) {
            return;
        }
        for (RequiredPluginInfo info : this.requirements) {
            if (!info.isSatisfied()) {
                return;
            }
        }
        this.blocked = false;
        applyButtonState(true);
        View view = this.enableAfterInstallRow;
        if (view != null) {
            view.setVisibility(0);
        }
    }

    private final void applyButtonState(boolean animated) {
        String blockedTitle;
        String blockedSubtitle;
        ButtonWithCounterView buttonWithCounterView = this.button;
        buttonWithCounterView.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        if (this.unknownSourceBlocked) {
            blockedTitle = LocaleController.getString(R.string.PluginInstallBlocked);
        } else {
            blockedTitle = this.params.getBlockedTitle();
        }
        if (this.unknownSourceBlocked) {
            blockedSubtitle = LocaleController.getString(R.string.PluginInstallBlockedInfo);
        } else {
            blockedSubtitle = this.params.getBlockedSubtitle();
        }
        if (this.blocked && blockedTitle != null) {
            buttonWithCounterView.setColor(ColorUtils.compositeColors(ColorUtils.setAlphaComponent(getThemedColor(Theme.key_featuredStickers_addButton), 180), getThemedColor(Theme.key_windowBackgroundWhite)));
            buttonWithCounterView.setText(blockedTitle, animated);
            buttonWithCounterView.setSubText(blockedSubtitle, animated);
            buttonWithCounterView.setOnClickListener(null);
            buttonWithCounterView.setClickable(false);
            buttonWithCounterView.setFocusable(false);
            return;
        }
        buttonWithCounterView.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
        final boolean risky = this.analysisReport != null && (this.analysisReport.hasDiff ? this.analysisReport.maxNewLevel() : this.analysisReport.maxLevel()) >= com.exteragram.messenger.plugins.analyzer.PluginStaticAnalyzer.LEVEL_DANGER;
        buttonWithCounterView.setText(LocaleController.getString(risky ? (this.isUpdate ? R.string.PluginAnalysisConfirmUpdate : R.string.PluginAnalysisConfirmInstall) : (this.isUpdate ? R.string.UpdatePlugin : R.string.InstallPlugin)), animated);
        buttonWithCounterView.setSubText(null, animated);
        buttonWithCounterView.setFocusable(true);
        buttonWithCounterView.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                InstallPluginBottomSheet.this.onInstallClick();
            }
        });
    }

    private final View buildHeader(final Activity activity) {
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(1);
        linearLayout.setClipChildren(false);
        linearLayout.setClipToPadding(false);
        Drawable iconDrawable = this.params.getIconDrawable();
        if (iconDrawable != null || (this.plugin.getPack() != null && this.plugin.getIndex() >= 0)) {
            FrameLayout frameLayout = new FrameLayout(activity);
            frameLayout.setClipChildren(false);
            frameLayout.setClipToPadding(false);
            BackupImageView backupImageView = new BackupImageView(activity);
            backupImageView.setOutlineProvider(ViewOutlineProviderImpl.boundsWithRoundRect(AndroidUtilities.dpf2(SECTION_GAP)));
            backupImageView.setClipToOutline(true);
            backupImageView.getImageReceiver().setAutoRepeat(1);
            frameLayout.addView(backupImageView, LayoutHelper.createFrame(78, 78, 17));
            FrameLayout frameLayout2 = new FrameLayout(activity);
            frameLayout2.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(28.0f), getThemedColor(Theme.key_windowBackgroundGray)));
            FrameLayout frameLayout3 = new FrameLayout(activity);
            frameLayout3.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(24.0f), getThemedColor(Theme.key_featuredStickers_addButton)));
            frameLayout2.addView(frameLayout3, LayoutHelper.createFrame(24, 24, 17));
            ImageView imageView = new ImageView(activity);
            imageView.setImageResource(R.drawable.plugins_filled);
            imageView.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frameLayout2.addView(imageView, LayoutHelper.createFrame(16, 16, 17));
            frameLayout.addView(frameLayout2, LayoutHelper.createFrame(28, 28.0f, 8388693, 0.0f, 0.0f, -4.0f, -4.0f));
            linearLayout.addView(frameLayout, LayoutHelper.createLinear(78, 78, 1, 0.0f, 28.0f, 0.0f, 0.0f));
            if (iconDrawable != null) {
                backupImageView.setImageDrawable(iconDrawable);
            } else {
                PluginCell.setPlaceholderImageByIndex(backupImageView, this.plugin.getPack(), this.plugin.getIndex(), "150_150", this.resourcesProvider);
            }
        } else {
            ImageView imageView2 = new ImageView(activity);
            imageView2.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView2.setImageResource(R.drawable.plugins_filled);
            imageView2.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
            imageView2.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(78.0f), getThemedColor(Theme.key_featuredStickers_addButton)));
            int iDp = AndroidUtilities.dp(16.0f);
            imageView2.setPadding(iDp, iDp, iDp, iDp);
            linearLayout.addView(imageView2, LayoutHelper.createLinear(78, 78, 1, 0.0f, 28.0f, 0.0f, 0.0f));
        }
        TextView textView = new TextView(activity) {
            @Override
            public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                setMeasuredDimension(getMeasuredWidth(), InstallPluginBottomSheet.this.nameText.getHeight(getMeasuredHeight()));
            }

            @Override
            public void onDraw(Canvas canvas) {
                int iBeforeDraw = InstallPluginBottomSheet.this.nameText.beforeDraw(canvas);
                super.onDraw(canvas);
                InstallPluginBottomSheet.this.nameText.afterDraw(canvas, iBeforeDraw);
            }
        };
        textView.setGravity(1);
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(1, 18.0f);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setText(this.plugin.getName());
        this.nameText.setView(textView);
        linearLayout.addView(textView, LayoutHelper.createLinear(-1, -2, 0, 40.0f, 16.0f, 40.0f, 0.0f));
        EffectsTextView effectsTextView = new EffectsTextView(activity, this.fragment.getResourceProvider());
        effectsTextView.setGravity(1);
        effectsTextView.setTypeface(AndroidUtilities.regular());
        effectsTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        effectsTextView.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
        effectsTextView.setTextSize(1, 14.0f);
        effectsTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        SpannableStringBuilder spannableStringBuilderAppend = new SpannableStringBuilder(LocaleController.getString(R.string.PluginVersion)).append((CharSequence) " ");
        int length = spannableStringBuilderAppend.length();
        if (this.isUpdate) {
            Plugin plugin = PluginsController.Companion.getInstance().getPlugins().get(this.plugin.getId());
            if (plugin != null) {
                String version = plugin.getVersion();
                String str = "";
                if (version == null) {
                    version = "";
                }
                String version2 = this.plugin.getVersion();
                if (version2 != null) {
                    str = version2;
                }
                if (version.equals(str)) {
                    // StaticGram: a reinstall of the same version is just «Версия 1.1.3», not «1.1.3 → 1.1.3»
                    spannableStringBuilderAppend.append((CharSequence) str);
                } else {
                    spannableStringBuilderAppend.append((CharSequence) version).append((CharSequence) " -> ").append((CharSequence) str);
                    spannableStringBuilderAppend = LocaleUtils.replaceArrows(getContext(), spannableStringBuilderAppend, R.drawable.msg_mini_arrow_mediathin);
                    spannableStringBuilderAppend.setSpan(new StrikethroughSpan(), length, version.length() + length, 33);
                }
            }
        } else {
            spannableStringBuilderAppend.append((CharSequence) this.plugin.getVersion());
        }
        spannableStringBuilderAppend.append((CharSequence) " • ").append(LocaleUtils.formatWithUsernames(this.plugin.getAuthor(), this.fragment, this::dismiss));
        effectsTextView.setText(spannableStringBuilderAppend);
        linearLayout.addView(effectsTextView, LayoutHelper.createLinear(-1, -2, 0, 21.0f, 4.0f, 21.0f, 0.0f));
        int i = this.params.getTrusted() ? R.drawable.trusted_mini : R.drawable.unknown_mini;
        int themedColor = getThemedColor(this.params.getTrusted() ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_text_RedRegular);
        String string = LocaleController.getString(this.params.getTrusted() ? R.string.PluginSourceTrusted : R.string.PluginSourceUnknown);
        final LinearLayout linearLayout2 = new LinearLayout(activity);
        ScaleStateListAnimator.apply(linearLayout2, 0.05f, 1.5f);
        linearLayout2.setOrientation(0);
        linearLayout2.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20.0f), AndroidUtilities.dp(20.0f), ColorUtils.compositeColors(AndroidUtilities.multiplyAlphaComponent(themedColor, 0.1f), getThemedColor(Theme.key_windowBackgroundWhite))));
        linearLayout2.setPadding(AndroidUtilities.dp(SECTION_GAP), AndroidUtilities.dp(6.0f), AndroidUtilities.dp(16.0f), AndroidUtilities.dp(6.0f));
        linearLayout2.setGravity(17);
        ImageView imageView3 = new ImageView(activity);
        imageView3.setImageResource(i);
        imageView3.setColorFilter(new PorterDuffColorFilter(themedColor, PorterDuff.Mode.SRC_IN));
        linearLayout2.addView(imageView3, LayoutHelper.createLinear(14, 14, 16, 0.0f, 0.0f, 6.0f, 0.0f));
        TextView textView2 = new TextView(activity);
        textView2.setTypeface(AndroidUtilities.regular());
        textView2.setTextColor(themedColor);
        textView2.setTextSize(1, 13.0f);
        textView2.setText(string);
        linearLayout2.addView(textView2);
        linearLayout.addView(linearLayout2, LayoutHelper.createLinear(-2, -2, 17, 0.0f, SECTION_GAP, 0.0f, 0.0f));
        linearLayout2.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                InstallPluginBottomSheet.this.showSourceHint(view);
            }
        });
        int themedColor2 = getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon);
        ImageView imageView4 = new ImageView(activity);
        ScaleStateListAnimator.apply(imageView4, 0.15f, 1.5f);
        Drawable drawable = ContextCompat.getDrawable(activity, R.drawable.msg_openin);
        imageView4.setImageDrawable(drawable != null ? drawable.mutate() : null);
        imageView4.setColorFilter(new PorterDuffColorFilter(themedColor2, PorterDuff.Mode.MULTIPLY));
        imageView4.setScaleType(ImageView.ScaleType.CENTER);
        imageView4.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                InstallPluginBottomSheet.this.openPluginFile();
            }
        });
        int i2 = Theme.key_dialogButtonSelector;
        imageView4.setBackground(Theme.createSelectorDrawable(getThemedColor(i2), 1, AndroidUtilities.dp(20.0f)));
        PluginTranslateButton pluginTranslateButton = new PluginTranslateButton(activity, themedColor2);
        ScaleStateListAnimator.apply(pluginTranslateButton, 0.15f, 1.5f);
        pluginTranslateButton.setVisibility(8);
        pluginTranslateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                InstallPluginBottomSheet.this.onTranslateClick();
            }
        });
        pluginTranslateButton.setBackground(Theme.createSelectorDrawable(getThemedColor(i2), 1, AndroidUtilities.dp(20.0f)));
        this.translateButton = pluginTranslateButton;
        LinearLayout linearLayout3 = new LinearLayout(activity);
        linearLayout3.setOrientation(0);
        int iDp2 = AndroidUtilities.dp(2.0f);
        linearLayout3.setPadding(iDp2, iDp2, iDp2, iDp2);
        GlassSourceColor blurredBackgroundSourceColor = new GlassSourceColor();
        blurredBackgroundSourceColor.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        BlurredBackgroundDrawable radius = new BlurredBackgroundDrawable(blurredBackgroundSourceColor).setColorProvider(BlurredBackgroundProviderImpl.topPanelChatActivity(this.resourcesProvider)).setRadius(AndroidUtilities.dpf2(22.0f));
        radius.setShadowAlpha(0.0f);
        linearLayout3.setBackground(radius);
        linearLayout3.addView(pluginTranslateButton, LayoutHelper.createLinear(40, 40));
        linearLayout3.addView(imageView4, LayoutHelper.createLinear(40, 40));
        this.actionsPill = linearLayout3;
        if ((this.params.getTrusted() && !ExteraConfig.getPreferences().getBoolean("trusted_source_hint", false)) || (!this.params.getTrusted() && !ExteraConfig.getPreferences().getBoolean("unknown_source_hint", false))) {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public final void run() {
                    InstallPluginBottomSheet.$r8$lambda$1mwQKeA8NTran0o7I5m7IQPO56g(linearLayout2, InstallPluginBottomSheet.this);
                }
            }, 600L);
        }
        FrameLayout frameLayout4 = new FrameLayout(activity);
        frameLayout4.setClipChildren(false);
        frameLayout4.setClipToPadding(false);
        frameLayout4.addView(linearLayout, LayoutHelper.createFrame(-1, -2.0f));
        frameLayout4.addView(linearLayout3, LayoutHelper.createFrame(-2, 44.0f, 53, 0.0f, 14.0f, SECTION_GAP, 0.0f));
        return frameLayout4;
    }

    public static void $r8$lambda$1mwQKeA8NTran0o7I5m7IQPO56g(LinearLayout linearLayout, InstallPluginBottomSheet installPluginBottomSheet) {
        linearLayout.performClick();
        ExteraConfig.getEditor().putBoolean(installPluginBottomSheet.params.getTrusted() ? "trusted_source_hint" : "unknown_source_hint", true).apply();
    }

    private final View buildDescription(Activity activity) {
        EffectsTextView effectsTextView = new EffectsTextView(activity) {
            @Override
            public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                setMeasuredDimension(getMeasuredWidth(), InstallPluginBottomSheet.this.descriptionText.getHeight(getMeasuredHeight()));
            }

            @Override
            public void onDraw(Canvas canvas) {
                int iBeforeDraw = InstallPluginBottomSheet.this.descriptionText.beforeDraw(canvas);
                super.onDraw(canvas);
                InstallPluginBottomSheet.this.descriptionText.afterDraw(canvas, iBeforeDraw);
            }
        };
        effectsTextView.setGravity(3);
        effectsTextView.setTypeface(AndroidUtilities.regular());
        effectsTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        effectsTextView.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
        effectsTextView.setTextSize(1, 15.0f);
        effectsTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        effectsTextView.setText(LocaleUtils.fullyFormatText(this.plugin.getDescription(), this.fragment, this::dismiss));
        effectsTextView.setPadding(AndroidUtilities.dp(16.0f), AndroidUtilities.dp(14.0f), AndroidUtilities.dp(16.0f), AndroidUtilities.dp(SECTION_GAP));
        this.descriptionText.setView(effectsTextView);
        return effectsTextView;
    }

    private final View buildError(Activity activity) {
        int themedColor = getThemedColor(Theme.key_text_RedRegular);
        int iDp = AndroidUtilities.dp(ExteraConfig.getSectionRadiusDp());
        TextView textView = new TextView(activity);
        textView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        textView.setTextSize(1, SECTION_GAP);
        textView.setTextColor(themedColor);
        textView.setPadding(AndroidUtilities.dp(16.0f), AndroidUtilities.dp(14.0f), AndroidUtilities.dp(16.0f), AndroidUtilities.dp(SECTION_GAP));
        textView.setBackground(Theme.createRoundRectDrawable(iDp, iDp, ColorUtils.compositeColors(AndroidUtilities.multiplyAlphaComponent(themedColor, 0.1f), getThemedColor(Theme.key_windowBackgroundWhite))));
        ScaleStateListAnimator.apply(textView, 0.02f, 1.5f);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                InstallPluginBottomSheet.m1600$r8$lambda$JDAKNeU10FilbAnsaIZ2uI7PKk(InstallPluginBottomSheet.this, view);
            }
        });
        this.errorTextView = textView;
        FrameLayout frameLayout = new FrameLayout(activity);
        frameLayout.setPadding(AndroidUtilities.dp(SECTION_GAP), 0, AndroidUtilities.dp(SECTION_GAP), AndroidUtilities.dp(SECTION_GAP));
        frameLayout.addView(textView, LayoutHelper.createFrame(-1, -2.0f));
        return frameLayout;
    }

    public static void m1600$r8$lambda$JDAKNeU10FilbAnsaIZ2uI7PKk(InstallPluginBottomSheet installPluginBottomSheet, View view) {
        String str = installPluginBottomSheet.errorText;
        if (str != null && AndroidUtilities.addToClipboard(str)) {
            BulletinFactory.of(installPluginBottomSheet.topBulletinContainer, installPluginBottomSheet.resourcesProvider).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    private final void showError(String message) {
        String string = StringsKt.trim((CharSequence) message).toString();
        boolean z = this.errorText == null;
        this.errorText = string;
        TextView textView = this.errorTextView;
        if (textView != null) {
            textView.setText(string);
        }
        UniversalAdapter universalAdapter = this.adapter;
        if (universalAdapter != null) {
            universalAdapter.update(true);
        }
        if (z) {
            scrollToError();
        }
    }

    private final void scrollToError() {
        RecyclerView.Adapter adapter = this.recyclerListView.getAdapter();
        if (adapter != null) {
            this.recyclerListView.smoothScrollToPosition(adapter.getItemCount() - 1);
        }
    }

    public final void showSourceHint(final View view) {
        HintView2 hintView2 = this.currentHint;
        if (hintView2 != null) {
            hintView2.hide();
        }
        this.currentHint = null;
        final HintView2 rounding = new HintView2(getContext(), 3).setMultilineText(true).setBgColor(getThemedColor(Theme.key_undo_background)).setTextColor(getThemedColor(Theme.key_undo_infoColor)).setText(AndroidUtilities.replaceTags(LocaleController.getString(this.params.getTrusted() ? R.string.PluginSourceTrustedInfo : R.string.PluginSourceUnknownInfo))).setTextAlign(Layout.Alignment.ALIGN_CENTER).allowBlur(true).setRounding(SECTION_GAP);
        rounding.setMaxWidthPx(HintView2.cutInFancyHalf(rounding.getText(), rounding.getTextPaint()));
        this.container.addView(rounding, LayoutHelper.createFrame(-1, 100.0f, 55, 32.0f, 0.0f, 32.0f, 0.0f));
        this.currentHint = rounding;
        this.container.post(new Runnable() {
            @Override
            public final void run() {
                InstallPluginBottomSheet.$r8$lambda$51uVpX6e938kFdqF76dtHaIpIp8(view, InstallPluginBottomSheet.this, rounding);
            }
        });
    }

    public static void $r8$lambda$51uVpX6e938kFdqF76dtHaIpIp8(View view, InstallPluginBottomSheet installPluginBottomSheet, HintView2 hintView2) {
        int[] iArr = new int[2];
        view.getLocationInWindow(iArr);
        int[] iArr2 = new int[2];
        installPluginBottomSheet.container.getLocationInWindow(iArr2);
        iArr[0] = iArr[0] - iArr2[0];
        int i = iArr[1] - iArr2[1];
        iArr[1] = i;
        int iDp = (i - AndroidUtilities.dp(100.0f)) - AndroidUtilities.dp(6.0f);
        float measuredWidth = iArr[0] + (view.getMeasuredWidth() / 2.0f);
        hintView2.setTranslationY(iDp);
        hintView2.setJointPx(0.0f, (-AndroidUtilities.dp(32.0f)) + measuredWidth);
        hintView2.setDuration(5500L);
        hintView2.show();
    }

    private final View createEnableAfterInstallRow(Activity activity) {
        CheckBoxRow checkBoxRow = new CheckBoxRow(activity, LocaleController.getString(R.string.EnableAfterInstallation), this.enableAfterInstallation, this.resourcesProvider);
        checkBoxRow.setOnCheckedChange(new Function1() {
            @Override
            public final Object invoke(Object obj) {
                return InstallPluginBottomSheet.$r8$lambda$uW8h013qngMyO9QW7bp8hpiNB2E(InstallPluginBottomSheet.this, ((Boolean) obj).booleanValue());
            }
        });
        return checkBoxRow;
    }

    public static Unit $r8$lambda$uW8h013qngMyO9QW7bp8hpiNB2E(InstallPluginBottomSheet installPluginBottomSheet, boolean z) {
        installPluginBottomSheet.enableAfterInstallation = z;
        return Unit.INSTANCE;
    }

    public final void openPluginFile() {
        if (this.installing) {
            return;
        }
        File file = new File(this.params.getFilePath());
        if (file.exists()) {
            if (PluginFileViewer.canOpen(file)) {
                String name = file.getName();
                if (!StringsKt.contains((CharSequence) name, '.', false)) {
                    name = null;
                }
                if (name == null) {
                    name = this.plugin.getId() + PluginsConstants.PLUGINS_EXT;
                }
                if (PluginFileViewer.INSTANCE.open(this.fragment, file, name)) {
                    dismiss();
                    return;
                }
                return;
            }
            PluginFileViewer.openExternally(this.fragment, file, file.getName());
        }
    }

    private final void checkTranslatable() {
        if (LanguageDetector.hasSupport()) {
            TranslatableText[] translatableTextArr = {this.nameText, this.descriptionText};
            for (int i = 0; i < 2; i++) {
                final TranslatableText translatableText = translatableTextArr[i];
                String original = translatableText.getOriginal();
                if (original != null && !StringsKt.isBlank(original)) {
                    LanguageDetector.detectLanguage(original, language -> AndroidUtilities.runOnUIThread(() -> InstallPluginBottomSheet.$r8$lambda$xwESlfoHFGWhHXNA_r6mweAjQ8I(language, translatableText, this)), null);
                }
            }
        }
    }

    public static void $r8$lambda$xwESlfoHFGWhHXNA_r6mweAjQ8I(String str, TranslatableText translatableText, InstallPluginBottomSheet installPluginBottomSheet) {
        if (str == null || str.length() == 0 || Intrinsics.areEqual(str, TranslateController.UNKNOWN_LANGUAGE) || TranslatorUtils.isRestrictedLanguage(str)) {
            return;
        }
        translatableText.setLanguage(str);
        PluginTranslateButton pluginTranslateButton = installPluginBottomSheet.translateButton;
        if (pluginTranslateButton != null) {
            pluginTranslateButton.setVisibility(0);
        }
    }

    public final void onTranslateClick() {
        PluginTranslateButton pluginTranslateButton = this.translateButton;
        if (pluginTranslateButton == null || this.translating) {
            return;
        }
        int i = 0;
        if (pluginTranslateButton.getState() == 2) {
            showTexts(false);
            pluginTranslateButton.setState(0);
            return;
        }
        TranslatableText[] translatableTextArr = {this.nameText, this.descriptionText};
        final ArrayList<TranslatableText> arrayList = new ArrayList<>();
        for (int i2 = 0; i2 < 2; i2++) {
            TranslatableText translatableText = translatableTextArr[i2];
            if (translatableText.getLanguage() != null && translatableText.getTranslation() == null) {
                arrayList.add(translatableText);
            }
        }
        if (arrayList.isEmpty()) {
            showTexts(true);
            pluginTranslateButton.setState(2);
            return;
        }
        this.translating = true;
        pluginTranslateButton.setState(1);
        String resolvedTargetLanguageCode = TranslatorUtils.getResolvedTargetLanguageCode();
        final Ref.IntRef intRef = new Ref.IntRef();
        intRef.element = arrayList.size();
        final Ref.BooleanRef booleanRef = new Ref.BooleanRef();
        int size = arrayList.size();
        while (i < size) {
            Object obj = arrayList.get(i);
            i++;
            final TranslatableText translatableText2 = (TranslatableText) obj;
            translatableText2.startLoading();
            String original = translatableText2.getOriginal();
            if (original == null) {
                original = "";
            }
            String language = translatableText2.getLanguage();
            if (language == null) {
                language = "auto";
            }
            translateText(original, language, resolvedTargetLanguageCode, result -> InstallPluginBottomSheet.$r8$lambda$4owLgGt87wFgmWYGGJXyRImSMkc(booleanRef, translatableText2, intRef, this, arrayList, result));
        }
    }

    public static Unit $r8$lambda$4owLgGt87wFgmWYGGJXyRImSMkc(Ref.BooleanRef booleanRef, TranslatableText translatableText, Ref.IntRef intRef, InstallPluginBottomSheet installPluginBottomSheet, List<TranslatableText> list, String str) {
        if (str == null) {
            booleanRef.element = true;
        } else {
            translatableText.setTranslation(str);
        }
        int i = intRef.element - 1;
        intRef.element = i;
        if (i == 0) {
            installPluginBottomSheet.onTranslationFinished(list, !booleanRef.element);
        }
        return Unit.INSTANCE;
    }

    private final void translateText(String text, String fromLanguage, String toLanguage, final Function1<? super String, Unit> done) {
        final Ref.BooleanRef booleanRef = new Ref.BooleanRef();
        final Function1<String, Unit> function1 = result -> InstallPluginBottomSheet.$r8$lambda$QmIdZAyHeJKamuDsNUC71z3zbGQ(booleanRef, done, result);
        TranslatorUtils.translate(text, fromLanguage, toLanguage, new TranslatorUtils.TranslateCallback() {
            @Override
            public void onSuccess(String translatedText) {
                function1.invoke(translatedText == null || translatedText.trim().isEmpty() ? null : translatedText);
            }

            @Override
            public void onFailed() {
                function1.invoke(null);
            }
        });
    }

    public static Unit $r8$lambda$QmIdZAyHeJKamuDsNUC71z3zbGQ(final Ref.BooleanRef booleanRef, final Function1<? super String, Unit> function1, final String str) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public final void run() {
                InstallPluginBottomSheet.$r8$lambda$cj8Fxmv3WzdlRmOhCU2DoQ437yc(booleanRef, function1, str);
            }
        });
        return Unit.INSTANCE;
    }

    public static void $r8$lambda$cj8Fxmv3WzdlRmOhCU2DoQ437yc(Ref.BooleanRef booleanRef, Function1<? super String, Unit> function1, String str) {
        if (booleanRef.element) {
            return;
        }
        booleanRef.element = true;
        function1.invoke(str);
    }

    private final void onTranslationFinished(List<TranslatableText> texts, boolean success) {
        this.translating = false;
        if (success) {
            showTexts(true);
            PluginTranslateButton pluginTranslateButton = this.translateButton;
            if (pluginTranslateButton != null) {
                pluginTranslateButton.setState(2);
            }
        } else {
            PluginTranslateButton pluginTranslateButton2 = this.translateButton;
            if (pluginTranslateButton2 != null) {
                pluginTranslateButton2.setState(0);
            }
            if (isShowing()) {
                BulletinFactory.of(this.topBulletinContainer, this.resourcesProvider).createErrorBulletin(LocaleController.getString(R.string.TranslationFailedAlert2)).show();
            }
        }
        for (TranslatableText text : texts) {
            text.stopLoading();
        }
    }

    private final void showTexts(boolean translated) {
        String original;
        this.nameText.show(translated);
        this.descriptionText.show(translated);
        TranslatableText translatableText = this.nameText;
        if (translated) {
            original = translatableText.getTranslation();
            if (original == null) {
                original = this.nameText.getOriginal();
            }
        } else {
            original = translatableText.getOriginal();
        }
        this.titleText = original;
        updateTitle();
    }

    public final void onTextTransitionUpdate() {
        ViewGroup viewGroup = this.containerView;
        if (viewGroup != null) {
            viewGroup.invalidate();
        }
    }

    public final void onInstallClick() {
        if (this.installing) {
            if (this.cancellationRequested) {
                return;
            }
            this.cancellationRequested = true;
            this.button.setLoading(true);
            this.button.setSubText(null, true);
            return;
        }
        ConcurrentHashMap<String, PluginsController.PluginsEngine> engines = PluginsController.Companion.getEngines();
        String engine = this.plugin.getEngine();
        if (engine == null) {
            engine = PluginsConstants.PYTHON;
        }
        PluginsController.PluginsEngine pluginsEngine = engines.get(engine);
        if (pluginsEngine == null) {
            return;
        }
        this.installing = true;
        this.cancellationRequested = false;
        setCanDismissWithSwipe(false);
        setCanDismissWithTouchOutside(false);
        Runnable runnable = this.delayedLoadingRunnable;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
        Runnable runnable2 = new Runnable() {
            @Override
            public final void run() {
                InstallPluginBottomSheet.this.button.setLoading(true);
            }
        };
        this.delayedLoadingRunnable = runnable2;
        AndroidUtilities.runOnUIThread(runnable2, 250L);
        // StaticGram antivirus: the server must clear the file before it is installed
        final PluginsController.PluginsEngine engineToUse = pluginsEngine;
        com.exteragram.messenger.plugins.PluginGuard.checkBeforeInstall(this.params.getFilePath(), guardError -> {
            if (guardError != null || this.cancellationRequested) {
                InstallPluginBottomSheet.$r8$lambda$0iFSAj_4pVkRlrIMq9sW2Re2HQQ(this, guardError);
                return;
            }
            // static analysis: the user reviewed this exact file, the engines only install reviewed files
            final com.exteragram.messenger.plugins.analyzer.PluginStaticAnalyzer.Report reviewed = this.analysisReport;
            final String filePath = this.params.getFilePath();
            final InstallerDelegateImpl installerDelegate = new InstallerDelegateImpl();
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                if (com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.isActive()) {
                    com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.approve(reviewed != null && reviewed.sha256 != null
                            ? reviewed.sha256 : com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.sha256(new java.io.File(filePath)));
                }
                engineToUse.loadPluginFromFile(filePath, this.plugin, error -> AndroidUtilities.runOnUIThread(() -> InstallPluginBottomSheet.$r8$lambda$0iFSAj_4pVkRlrIMq9sW2Re2HQQ(this, error)), installerDelegate);
            });
        });
    }

    public static void $r8$lambda$0iFSAj_4pVkRlrIMq9sW2Re2HQQ(final InstallPluginBottomSheet installPluginBottomSheet, String str) {
        Runnable runnable = installPluginBottomSheet.delayedLoadingRunnable;
        if (runnable != null) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
        installPluginBottomSheet.delayedLoadingRunnable = null;
        installPluginBottomSheet.button.setLoading(false);
        installPluginBottomSheet.setCancelable(true);
        installPluginBottomSheet.setCanDismissWithSwipe(true);
        installPluginBottomSheet.setCanDismissWithTouchOutside(true);
        installPluginBottomSheet.installing = false;
        if (installPluginBottomSheet.cancellationRequested) {
            installPluginBottomSheet.cancellationRequested = false;
            installPluginBottomSheet.applyButtonState(true);
            return;
        }
        if (str != null) {
            installPluginBottomSheet.applyButtonState(true);
            if (StringsKt.contains((CharSequence) str, (CharSequence) "Installation cancelled", false)) {
                return;
            }
            installPluginBottomSheet.showError(str);
            return;
        }
        installPluginBottomSheet.deliverResult(null);
        if (installPluginBottomSheet.enableAfterInstallation && !ExteraConfig.getPluginsSafeMode()) {
            installPluginBottomSheet.installing = true;
            installPluginBottomSheet.setCanDismissWithSwipe(false);
            installPluginBottomSheet.setCanDismissWithTouchOutside(false);
            installPluginBottomSheet.button.setLoading(true);
            PluginsController.Companion.getInstance().setPluginEnabled(installPluginBottomSheet.plugin.getId(), true, error -> AndroidUtilities.runOnUIThread(() -> InstallPluginBottomSheet.m1601$r8$lambda$U5co24RCnkNfmnx5ohZVPCpsK0(installPluginBottomSheet, error)));
            return;
        }
        installPluginBottomSheet.dismiss();
        installPluginBottomSheet.showSuccessBulletin(installPluginBottomSheet.fragment, installPluginBottomSheet.plugin);
    }

    public static void m1601$r8$lambda$U5co24RCnkNfmnx5ohZVPCpsK0(InstallPluginBottomSheet installPluginBottomSheet, String str) {
        installPluginBottomSheet.installing = false;
        installPluginBottomSheet.setCanDismissWithSwipe(true);
        installPluginBottomSheet.setCanDismissWithTouchOutside(true);
        installPluginBottomSheet.button.setLoading(false);
        if (str != null) {
            installPluginBottomSheet.applyButtonState(true);
            installPluginBottomSheet.showError(str);
        } else {
            installPluginBottomSheet.dismiss();
            installPluginBottomSheet.showSuccessBulletin(installPluginBottomSheet.fragment, installPluginBottomSheet.plugin);
        }
    }

    /** Reports pip progress on the install button and lets the user cancel the dependency download. */
    private final class InstallerDelegateImpl implements PipController.InstallerDelegate {

        @Override
        public void onProgress(final String text) {
            final InstallPluginBottomSheet installPluginBottomSheet = InstallPluginBottomSheet.this;
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public final void run() {
                    showInstallProgress(installPluginBottomSheet, text);
                }
            });
        }

        private void showInstallProgress(InstallPluginBottomSheet installPluginBottomSheet, String str) {
            Runnable runnable = installPluginBottomSheet.delayedLoadingRunnable;
            if (runnable != null) {
                AndroidUtilities.cancelRunOnUIThread(runnable);
            }
            installPluginBottomSheet.delayedLoadingRunnable = null;
            if (installPluginBottomSheet.cancellationRequested) {
                return;
            }
            installPluginBottomSheet.button.setLoading(false);
            installPluginBottomSheet.button.setText(LocaleController.getString(R.string.Cancel), true);
            installPluginBottomSheet.button.setSubText(str, true);
        }

        @Override
        public boolean isCancelled() {
            return InstallPluginBottomSheet.this.cancellationRequested;
        }
    }

    private final void showSuccessBulletin(BaseFragment fragment, final Plugin plugin) {
        final BulletinFactory bulletinFactoryOf = BulletinFactory.of(fragment);
        final String name = plugin.getName();
        if (plugin.getPack() != null && plugin.getIndex() >= 0) {
            TLRPC.TL_inputStickerSetShortName tL_inputStickerSetShortName = new TLRPC.TL_inputStickerSetShortName();
            tL_inputStickerSetShortName.short_name = plugin.getPack();
            final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
            final Runnable runnable = new Runnable() {
                @Override
                public final void run() {
                    InstallPluginBottomSheet.$r8$lambda$bCqSZM6hxYuEJX0VdupAXrW9z48(atomicBoolean, InstallPluginBottomSheet.this, plugin, bulletinFactoryOf);
                }
            };
            AndroidUtilities.runOnUIThread(runnable, 300L);
            MediaDataController.getInstance(UserConfig.selectedAccount).getStickerSet(tL_inputStickerSetShortName, 0, true, stickerSet -> AndroidUtilities.runOnUIThread(() -> InstallPluginBottomSheet.$r8$lambda$PcrlDgT4JGXCfZRinXFErAkdLs0(atomicBoolean, stickerSet, plugin, runnable, this, name, bulletinFactoryOf)));
            return;
        }
        showSimpleSuccessBulletin(plugin, bulletinFactoryOf);
    }

    public static void $r8$lambda$bCqSZM6hxYuEJX0VdupAXrW9z48(AtomicBoolean atomicBoolean, InstallPluginBottomSheet installPluginBottomSheet, Plugin plugin, BulletinFactory bulletinFactory) {
        if (atomicBoolean.getAndSet(true)) {
            return;
        }
        installPluginBottomSheet.showSimpleSuccessBulletin(plugin, bulletinFactory);
    }

    public static void $r8$lambda$PcrlDgT4JGXCfZRinXFErAkdLs0(AtomicBoolean atomicBoolean, TLRPC.TL_messages_stickerSet tL_messages_stickerSet, final Plugin plugin, Runnable runnable, InstallPluginBottomSheet installPluginBottomSheet, String str, BulletinFactory bulletinFactory) {
        Bulletin bulletinCreateSimpleBulletin;
        int index;
        if (atomicBoolean.get()) {
            return;
        }
        TLRPC.Document document = null;
        if ((tL_messages_stickerSet != null ? tL_messages_stickerSet.documents : null) != null && !tL_messages_stickerSet.documents.isEmpty() && (index = plugin.getIndex()) >= 0 && index < tL_messages_stickerSet.documents.size()) {
            document = tL_messages_stickerSet.documents.get(index);
        }
        if (document == null || atomicBoolean.getAndSet(true)) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(runnable);
        SpannableStringBuilder spannableStringBuilderReplaceTags = AndroidUtilities.replaceTags(LocaleController.formatString(installPluginBottomSheet.isUpdate ? R.string.PluginUpdated : R.string.PluginInstalled, str));
        PluginsController.Companion companion = PluginsController.Companion;
        Plugin plugin2 = companion.getInstance().getPlugins().get(plugin.getId());
        if (plugin2 != null && companion.getInstance().hasPluginSettings(plugin.getId()) && plugin2.isEnabled()) {
            bulletinCreateSimpleBulletin = bulletinFactory.createEmojiBulletin(document, spannableStringBuilderReplaceTags, LocaleController.getString(R.string.Settings), new Runnable() {
                @Override
                public final void run() {
                    PluginsController.Companion.openPluginSettings(plugin.getId());
                }
            });
        } else {
            bulletinCreateSimpleBulletin = bulletinFactory.createSimpleBulletin(document, spannableStringBuilderReplaceTags);
        }
        bulletinCreateSimpleBulletin.show();
    }

    private final void showSimpleSuccessBulletin(final Plugin plugin, BulletinFactory bf) {
        String string = LocaleController.formatString(this.isUpdate ? R.string.PluginUpdated : R.string.PluginInstalled, plugin.getName());
        PluginsController.Companion companion = PluginsController.Companion;
        Plugin plugin2 = companion.getInstance().getPlugins().get(plugin.getId());
        if (plugin2 != null && companion.getInstance().hasPluginSettings(plugin.getId()) && plugin2.isEnabled()) {
            bf.createSimpleBulletin(R.raw.contact_check, string, LocaleController.getString(R.string.Settings), new Runnable() {
                @Override
                public final void run() {
                    PluginsController.Companion.openPluginSettings(plugin.getId());
                }
            }).show();
        } else {
            bf.createSimpleBulletin(R.raw.contact_check, string).show();
        }
    }

    private void deliverResult(String error) {
        if (this.resultDelivered) {
            return;
        }
        this.resultDelivered = true;
        final Utilities.Callback<String> callback = this.params != null ? this.params.getResultCallback() : null;
        if (callback != null) {
            callback.run(error);
        }
    }

    @Override
    public void dismiss() {
        deliverResult(com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.DECLINED);
        HintView2 hintView2 = this.currentHint;
        if (hintView2 != null) {
            hintView2.hide();
        }
        this.currentHint = null;
        super.dismiss();
    }

    @Override
    public void onSwipeStarts() {
        HintView2 hintView2 = this.currentHint;
        if (hintView2 != null) {
            hintView2.hide();
        }
        this.currentHint = null;
    }

    public static final class PluginInstallParams {

        public static final Companion Companion = new Companion();
        private String blockedSubtitle;
        private String blockedTitle;
        private List<String> bundledPackages;
        private String filePath;
        private Drawable iconDrawable;
        private List<RequiredPluginInfo> requiredPlugins;
        private boolean trusted;
        // StaticGram: installs started outside the chat (dev server, bundled plugins) wait for the sheet
        private Utilities.Callback<String> resultCallback;
        private Boolean enableAfterInstall;

        public final Utilities.Callback<String> getResultCallback() {
            return this.resultCallback;
        }

        public final void setResultCallback(Utilities.Callback<String> callback) {
            this.resultCallback = callback;
        }

        public final Boolean getEnableAfterInstall() {
            return this.enableAfterInstall;
        }

        public final void setEnableAfterInstall(boolean enable) {
            this.enableAfterInstall = enable;
        }

        public static final PluginInstallParams of(MessageObject messageObject) {
            return Companion.of(messageObject);
        }

        public PluginInstallParams(String str, boolean z) {
            this.filePath = str;
            this.trusted = z;
        }

        public final String getFilePath() {
            return this.filePath;
        }

        public final void setFilePath(String str) {
            this.filePath = str;
        }

        public final boolean getTrusted() {
            return this.trusted;
        }

        public final void setTrusted(boolean z) {
            this.trusted = z;
        }

        public final Drawable getIconDrawable() {
            return this.iconDrawable;
        }

        public final void setIconDrawable(Drawable drawable) {
            this.iconDrawable = drawable;
        }

        public final List<RequiredPluginInfo> getRequiredPlugins() {
            return this.requiredPlugins;
        }

        public final void setRequiredPlugins(List<RequiredPluginInfo> list) {
            this.requiredPlugins = list;
        }

        public final List<String> getBundledPackages() {
            return this.bundledPackages;
        }

        public final void setBundledPackages(List<String> list) {
            this.bundledPackages = list;
        }

        public final String getBlockedTitle() {
            return this.blockedTitle;
        }

        public final void setBlockedTitle(String str) {
            this.blockedTitle = str;
        }

        public final String getBlockedSubtitle() {
            return this.blockedSubtitle;
        }

        public final void setBlockedSubtitle(String str) {
            this.blockedSubtitle = str;
        }

        public static final class Companion {
            private Companion() {
            }

            /**
             * A plugin is "trusted" when it comes from a trusted plugins channel (forwarded from it or posted there).
             * exteraGram keeps that list on its API; StaticGram knows only exteraGram's default trusted channel.
             */
            public final PluginInstallParams of(MessageObject messageObject) {
                String pathToMessage = ChatUtils.getInstance().getPathToMessage(messageObject);
                boolean trusted = false;
                if (messageObject.isForwarded()) {
                    Long forwardedFromId = messageObject.getForwardedFromId();
                    if (forwardedFromId != null) {
                        trusted = isTrustedChannel(-forwardedFromId);
                    }
                } else if (messageObject.isFromChannel() && !messageObject.isFromChat()) {
                    trusted = isTrustedChannel(-messageObject.getDialogId());
                }
                return new PluginInstallParams(pathToMessage, trusted);
            }

            private static boolean isTrustedChannel(long channelId) {
                return com.exteragram.messenger.badges.BadgesController.isTrustedPluginChannel(channelId);
            }
        }
    }

    private void startStaticAnalysis() {
        if (!com.exteragram.messenger.ExteraConfig.getPluginsStaticAnalysis() || this.params == null || this.params.getFilePath() == null) {
            return;
        }
        final java.io.File file = new java.io.File(this.params.getFilePath());
        // exteraGram: an update / reinstall is compared with the installed version (previousPath)
        final String pluginId = this.isUpdate ? this.plugin.getId() : null;
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final java.io.File previous = pluginId != null ? com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate.installedFile(pluginId) : null;
            final com.exteragram.messenger.plugins.analyzer.PluginStaticAnalyzer.Report report = com.exteragram.messenger.plugins.analyzer.PluginStaticAnalyzer.analyze(file, previous, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (!isShowing()) return;
                this.analysisReport = report;
                this.analysisView = com.exteragram.messenger.plugins.analyzer.PluginAnalysisViews.createVerdictCard(getContext(), report, this.resourcesProvider,
                        () -> com.exteragram.messenger.plugins.analyzer.PluginAnalysisViews.showReport(getContext(), report, this.resourcesProvider));
                if (this.adapter != null) this.adapter.update(true);
                applyButtonState(true);
            });
        });
    }
}
