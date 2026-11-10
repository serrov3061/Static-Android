package com.exteragram.messenger.reactions;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.SetsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import kotlin.ranges.RangesKt;
import kotlin.text.StringsKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.BackSpaceButtonView;
import org.telegram.ui.Components.Reactions.CustomReactionEditText;
import org.telegram.ui.Components.Reactions.ReactionsUtils;
import org.telegram.ui.Components.Reactions.UpdateReactionsButton;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.SelectAnimatedEmojiDialog;

public final class ChannelReactionsEditActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private UpdateReactionsButton actionButton;
    FrameLayout actionButtonContainer;
    boolean actionButtonShown;
    private final ArrayList<TLRPC.TL_availableReaction> allAvailableReactions;
    private boolean backupPaid;
    private TL_stories.TL_premium_boostsStatus boostsStatus;
    private FrameLayout bottomDialogLayout;
    private final long chatId;
    private final TLRPC.ChatFull chatInfo;
    private final Runnable checkAfterFastDeleteRunnable;
    private CustomReactionEditText editText;
    private boolean emojiKeyboardVisible;
    private TextCheckCell enableReactionsCell;
    private final ReactionsEditHelper helper;
    private TLRPC.ChatFull info;
    private boolean initialPaid;
    private int initialReactionsCount;
    private Set<Long> initialSelectedIds;
    private int initialType;
    private final int maxReactionsCount;
    private NotificationCenter.ObserversGroup observers;
    private boolean paid;
    private TextCheckCell paidCheckCell;
    private boolean paused;
    private int reactionsCount;
    private SectionsScrollView scrollView;
    private SelectAnimatedEmojiDialog selectAnimatedEmojiDialog;
    private int selectedCustomReactions;
    private final ArrayList<Long> selectedEmojisIds;
    private final LinkedHashMap<Long, AnimatedEmojiSpan> selectedEmojisMap;
    private int selectedType;
    private LinearLayout switchLayout;

    public ChannelReactionsEditActivity(long j, TLRPC.ChatFull chatFull) {
        this.chatId = j;
        this.chatInfo = chatFull;
        ArrayList<TLRPC.TL_availableReaction> arrayList = new ArrayList<>();
        this.allAvailableReactions = arrayList;
        this.selectedEmojisMap = new LinkedHashMap<>();
        this.selectedEmojisIds = new ArrayList<>();
        this.helper = new ReactionsEditHelper(this.currentAccount, j, arrayList);
        this.maxReactionsCount = getMessagesController().boostsChannelLevelMax;
        this.initialSelectedIds = SetsKt.emptySet();
        this.initialType = 2;
        this.selectedType = -1;
        this.actionButtonShown = true;
        this.checkAfterFastDeleteRunnable = new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                ChannelReactionsEditActivity.this.checkMaxCustomReactions(false);
            }
        };
    }

    private final boolean getStarEnabled() {
        TextCheckCell textCheckCell = this.paidCheckCell;
        return textCheckCell != null && textCheckCell.isChecked();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean onFragmentCreate() {
        TLRPC.ChatFull chatFull = this.chatInfo;
        if (chatFull == null) {
            return false;
        }
        this.info = chatFull;
        if (getMessagesController().getChat(Long.valueOf(this.chatId)) == null) {
            TLRPC.Chat chatSync = getMessagesStorage().getChatSync(this.chatId);
            if (chatSync != null) {
                getMessagesController().putChat(chatSync, true);
            } else {
                chatSync = null;
            }
            if (chatSync == null) {
                return false;
            }
        }
        getMessagesController().getBoostsController().getBoostsStats(-this.chatId, new Utilities.Callback() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda7
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                ChannelReactionsEditActivity.m2158$r8$lambda$ARQV81sDzphTFlcmcIlwyZD7o(ChannelReactionsEditActivity.this, (TL_stories.TL_premium_boostsStatus) obj);
            }
        });
        CollectionsKt.addAll(this.allAvailableReactions, getMediaDataController().getEnabledReactionsList());
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        this.observers = getNotificationCenter().createObserversGroup(this).add(NotificationCenter.dialogDeleted);
        return super.onFragmentCreate();
    }

    /* JADX INFO: renamed from: $r8$lambda$ARQV81sDzph-TFlcmcIlw-yZD7o, reason: not valid java name */
    public static void m2158$r8$lambda$ARQV81sDzphTFlcmcIlwyZD7o(ChannelReactionsEditActivity channelReactionsEditActivity, TL_stories.TL_premium_boostsStatus tL_premium_boostsStatus) {
        channelReactionsEditActivity.boostsStatus = tL_premium_boostsStatus;
        if (Intrinsics.areEqual(channelReactionsEditActivity.selectedEmojisMap.keySet(), channelReactionsEditActivity.initialSelectedIds)) {
            return;
        }
        channelReactionsEditActivity.checkMaxCustomReactions(false);
    }

    /* JADX WARN: Type inference fix 'apply assigned field type' failed
    java.lang.UnsupportedOperationException: ArgType.getObject(), call class: class jadx.core.dex.instructions.args.ArgType$UnknownArg
    	at jadx.core.dex.instructions.args.ArgType.getObject(ArgType.java:596)
    	at jadx.core.dex.attributes.nodes.ClassTypeVarsAttr.getTypeVarsMapFor(ClassTypeVarsAttr.java:35)
    	at jadx.core.dex.nodes.utils.TypeUtils.replaceClassGenerics(TypeUtils.java:177)
    	at jadx.core.dex.visitors.typeinference.FixTypesVisitor.insertExplicitUseCast(FixTypesVisitor.java:397)
    	at jadx.core.dex.visitors.typeinference.FixTypesVisitor.tryFieldTypeWithNewCasts(FixTypesVisitor.java:359)
    	at jadx.core.dex.visitors.typeinference.FixTypesVisitor.applyFieldType(FixTypesVisitor.java:309)
    	at jadx.core.dex.visitors.typeinference.FixTypesVisitor.visit(FixTypesVisitor.java:94)
     */
    @Override // org.telegram.ui.ActionBar.BaseFragment
    public View createView(Context context) {
        this.actionBar.setTitle(LocaleController.getString(R.string.Reactions));
        this.actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        boolean z = true;
        this.actionBar.setAllowOverlayTitle(true);
        this.actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity.createView.1
            @Override // org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick
            public void onItemClick(int id) {
                if (id != -1 || ChannelReactionsEditActivity.this.checkChangesBeforeExit(true)) {
                    return;
                }
                ChannelReactionsEditActivity.this.finishFragment();
            }
        });
        SectionsScrollView.SectionsLinearLayout sectionsLinearLayout = new SectionsScrollView.SectionsLinearLayout(context);
        sectionsLinearLayout.setOrientation(1);
        SectionsScrollView sectionsScrollView = new SectionsScrollView(context, sectionsLinearLayout, this.resourceProvider);
        sectionsScrollView.setFillViewport(true);
        sectionsScrollView.addView(sectionsLinearLayout);
        this.scrollView = sectionsScrollView;
        this.actionBar.setAdaptiveBackground(sectionsScrollView);
        TextCheckCell textCheckCell = new TextCheckCell(context);
        textCheckCell.setHeight(56);
        textCheckCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundUnchecked));
        textCheckCell.setTypeface(AndroidUtilities.bold());
        textCheckCell.setColors(Theme.key_windowBackgroundCheckText, Theme.key_switchTrackBlue, Theme.key_switchTrackBlueChecked, Theme.key_switchTrackBlueThumb, Theme.key_switchTrackBlueThumbChecked);
        textCheckCell.setOnClickListener(new View.OnClickListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ChannelReactionsEditActivity.this.onEnableReactionsClick();
            }
        });
        this.enableReactionsCell = textCheckCell;
        sectionsLinearLayout.addView(textCheckCell, LayoutHelper.createLinear(-1, -2));
        sectionsLinearLayout.addView(createInfoCell$default(this, context, LocaleController.getString(R.string.ReactionAddEmojiFromAnyPack), 0, 4, null), LayoutHelper.createLinear(-1, -2));
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(1);
        LayoutTransition layoutTransition = new LayoutTransition();
        layoutTransition.setDuration(200L);
        layoutTransition.enableTransitionType(4);
        linearLayout.setLayoutTransition(layoutTransition);
        this.switchLayout = linearLayout;
        sectionsLinearLayout.addView(linearLayout, LayoutHelper.createFrame(-1, -2.0f));
        fillSwitchLayout(context);
        FrameLayout c01681CreateRootLayout = createRootLayout(context);
        SectionsScrollView sectionsScrollView2 = this.scrollView;
        if (sectionsScrollView2 == null) {
            sectionsScrollView2 = null;
        }
        c01681CreateRootLayout.addView(sectionsScrollView2);
        this.actionButtonContainer = new FrameLayout(context);
        ImageView imageView = new ImageView(context);
        imageView.setImageResource(R.drawable.gradient_bottom);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundGray, this.resourceProvider), PorterDuff.Mode.SRC_ATOP));
        FrameLayout frameLayout = this.actionButtonContainer;
        if (frameLayout == null) {
            frameLayout = null;
        }
        frameLayout.addView(imageView, LayoutHelper.createFrame(-1, -1, 119));
        UpdateReactionsButton updateReactionsButton = new UpdateReactionsButton(context, this.resourceProvider);
        updateReactionsButton.setRound();
        updateReactionsButton.setDefaultState();
        updateReactionsButton.setOnClickListener(new View.OnClickListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                ChannelReactionsEditActivity.this.applyChanges();
            }
        });
        this.actionButton = updateReactionsButton;
        FrameLayout frameLayout2 = this.actionButtonContainer;
        if (frameLayout2 == null) {
            frameLayout2 = null;
        }
        frameLayout2.addView(updateReactionsButton, LayoutHelper.createFrame(-1, 48.0f, 80, 13.0f, 13.0f, 13.0f, 13.0f));
        FrameLayout frameLayout3 = this.actionButtonContainer;
        if (frameLayout3 == null) {
            frameLayout3 = null;
        }
        c01681CreateRootLayout.addView(frameLayout3, LayoutHelper.createFrame(-1, 74.0f, 80, 0.0f, 0.0f, 0.0f, 0.0f));
        FrameLayout frameLayout4 = new FrameLayout(context) { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity.createView.6
            @Override // android.widget.FrameLayout, android.view.ViewGroup, android.view.View
            public void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (ChannelReactionsEditActivity.this.emojiKeyboardVisible && changed) {
                    FrameLayout frameLayout5 = ChannelReactionsEditActivity.this.actionButtonContainer;
                    if (frameLayout5 == null) {
                        frameLayout5 = null;
                    }
                    frameLayout5.setTranslationY(-getMeasuredHeight());
                    ChannelReactionsEditActivity.this.updateScrollViewMarginBottom(getMeasuredHeight());
                }
            }
        };
        this.bottomDialogLayout = frameLayout4;
        frameLayout4.setVisibility(4);
        FrameLayout frameLayout5 = this.bottomDialogLayout;
        if (frameLayout5 == null) {
            frameLayout5 = null;
        }
        c01681CreateRootLayout.addView(frameLayout5, LayoutHelper.createFrame(-1, -2, 80));
        fillInitialReactions();
        TextCheckCell textCheckCell2 = this.enableReactionsCell;
        if (textCheckCell2 == null) {
            textCheckCell2 = null;
        }
        String string = LocaleController.getString(R.string.EnableReactions);
        if (this.selectedType == 2 && !this.paid) {
            z = false;
        }
        textCheckCell2.setTextAndCheck(string, z, false);
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.addReactionsSpan();
        TLRPC.ChatFull chatFull = this.info;
        if ((chatFull == null ? null : chatFull).paid_media_allowed) {
            if ((chatFull != null ? chatFull : null).paid_reactions_available) {
                enableStars(false);
            }
        }
        this.initialSelectedIds = CollectionsKt.toSet(this.selectedEmojisMap.keySet());
        this.initialPaid = this.paid;
        this.initialType = this.selectedType;
        updateActionButton(false);
        this.fragmentView = c01681CreateRootLayout;
        return c01681CreateRootLayout;
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [android.view.View, com.exteragram.messenger.reactions.ChannelReactionsEditActivity$createRootLayout$1] */
    private final FrameLayout createRootLayout(Context context) {
        final FrameLayout r0 = new FrameLayout(context) { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity.createRootLayout.1
            private final ChannelReactionsEditActivity$createRootLayout$1$adjustPanLayoutHelper$1 adjustPanLayoutHelper;

            {
                this.adjustPanLayoutHelper = new ChannelReactionsEditActivity$createRootLayout$1$adjustPanLayoutHelper$1(this, ChannelReactionsEditActivity.this);
            }

            @Override // android.view.ViewGroup, android.view.View
            public void onAttachedToWindow() {
                super.onAttachedToWindow();
                this.adjustPanLayoutHelper.onAttach();
            }

            @Override // android.view.ViewGroup, android.view.View
            public void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                this.adjustPanLayoutHelper.onDetach();
            }
        };
        r0.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return r0;
    }

    public static /* synthetic */ TextInfoPrivacyCell createInfoCell$default(ChannelReactionsEditActivity channelReactionsEditActivity, Context context, CharSequence charSequence, int i, int i2, Object obj) {
        if ((i2 & 4) != 0) {
            i = 16;
        }
        return channelReactionsEditActivity.createInfoCell(context, charSequence, i);
    }

    private final TextInfoPrivacyCell createInfoCell(Context context, CharSequence text, int bottomPadding) {
        TextInfoPrivacyCell textInfoPrivacyCell = new TextInfoPrivacyCell(context, 12, this.resourceProvider);
        textInfoPrivacyCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        textInfoPrivacyCell.setTopPadding(12);
        textInfoPrivacyCell.setBottomPadding(bottomPadding);
        textInfoPrivacyCell.setText(text);
        return textInfoPrivacyCell;
    }

    /* JADX WARN: Code duplicated, block: B:33:0x00b9 A[DONT_INVERT] */
    /* JADX WARN: Code duplicated, block: B:34:0x00bb  */
    /* JADX WARN: Code duplicated, block: B:36:0x00bf  */
    private final void fillSwitchLayout(final Context context) {
        int i;
        HeaderCell headerCell = new HeaderCell(context);
        headerCell.setText(LocaleController.getString(R.string.AvailableReactions));
        headerCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        headerCell.setTextSize(15.0f);
        headerCell.setTopMargin(14);
        LinearLayout linearLayout = this.switchLayout;
        if (linearLayout == null) {
            linearLayout = null;
        }
        linearLayout.addView(headerCell, LayoutHelper.createLinear(-1, -2));
        CustomReactionEditText anonymousClass1CreateEditText = createEditText(context);
        this.editText = anonymousClass1CreateEditText;
        LinearLayout linearLayout2 = this.switchLayout;
        if (linearLayout2 == null) {
            linearLayout2 = null;
        }
        if (anonymousClass1CreateEditText == null) {
            anonymousClass1CreateEditText = null;
        }
        linearLayout2.addView(anonymousClass1CreateEditText, LayoutHelper.createLinear(-1, -2));
        TextInfoPrivacyCell textInfoPrivacyCell = new TextInfoPrivacyCell(context, 12, this.resourceProvider);
        textInfoPrivacyCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        textInfoPrivacyCell.setText(AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.ReactionCreateOwnPack), Theme.key_chat_messageLinkIn, 0, new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda16
            @Override // java.lang.Runnable
            public final void run() {
                Browser.openUrl(context, "https://t.me/stickers");
            }
        }, this.resourceProvider));
        LinearLayout linearLayout3 = this.switchLayout;
        if (linearLayout3 == null) {
            linearLayout3 = null;
        }
        linearLayout3.addView(textInfoPrivacyCell, LayoutHelper.createLinear(-1, -2));
        LinearLayout linearLayout4 = this.switchLayout;
        if (linearLayout4 == null) {
            linearLayout4 = null;
        }
        HeaderCell headerCell2 = new HeaderCell(context, this.resourceProvider);
        headerCell2.setText(LocaleController.getString(R.string.MaximumReactionsHeader));
        linearLayout4.addView(headerCell2, LayoutHelper.createLinear(-1, -2));
        TLRPC.ChatFull chatFull = this.info;
        if ((chatFull == null ? null : chatFull) instanceof TLRPC.TL_chatFull) {
            if (((chatFull == null ? null : chatFull).flags & 1048576) != 0) {
                if (chatFull == null) {
                    chatFull = null;
                }
                i = chatFull.reactions_limit;
            } else {
                i = getMessagesController().reactionsUniqMax;
            }
        } else {
            if (((chatFull == null ? null : chatFull).flags2 & 8192) != 0) {
                if (chatFull == null) {
                    chatFull = null;
                }
                i = chatFull.reactions_limit;
            } else {
                i = getMessagesController().reactionsUniqMax;
            }
        }
        this.reactionsCount = i;
        this.initialReactionsCount = i;
        SlideIntChooseView slideIntChooseView = new SlideIntChooseView(context, this.resourceProvider);
        slideIntChooseView.set(this.reactionsCount, SlideIntChooseView.Options.make(0, "MaximumReactionsValue", 1, getMessagesController().reactionsUniqMax), new Utilities.Callback() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda17
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                ChannelReactionsEditActivity.$r8$lambda$VxGye0JCN8l_pCjR53raw6inbbQ(ChannelReactionsEditActivity.this, (Integer) obj);
            }
        });
        LinearLayout linearLayout5 = this.switchLayout;
        if (linearLayout5 == null) {
            linearLayout5 = null;
        }
        linearLayout5.addView(slideIntChooseView, LayoutHelper.createLinear(-1, -2));
        TextInfoPrivacyCell textInfoPrivacyCell2 = new TextInfoPrivacyCell(context, 12, this.resourceProvider);
        textInfoPrivacyCell2.setTopPadding(12);
        TLRPC.ChatFull chatFull2 = this.info;
        if (chatFull2 == null) {
            chatFull2 = null;
        }
        textInfoPrivacyCell2.setBottomPadding(chatFull2.paid_media_allowed ? 16 : 70);
        textInfoPrivacyCell2.setText(LocaleController.getString(R.string.MaximumReactionsInfo));
        LinearLayout linearLayout6 = this.switchLayout;
        if (linearLayout6 == null) {
            linearLayout6 = null;
        }
        linearLayout6.addView(textInfoPrivacyCell2, LayoutHelper.createLinear(-1, -2));
        TLRPC.ChatFull chatFull3 = this.info;
        if (chatFull3 == null) {
            chatFull3 = null;
        }
        if (chatFull3.paid_media_allowed) {
            TextCheckCell textCheckCell = new TextCheckCell(context);
            textCheckCell.setTextAndCheck(LocaleController.getString(R.string.ChannelEnablePaidReactions), false, false);
            textCheckCell.setOnClickListener(new View.OnClickListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda18
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ChannelReactionsEditActivity.this.toggleStarsEnabled();
                }
            });
            this.paidCheckCell = textCheckCell;
            LinearLayout linearLayout7 = this.switchLayout;
            if (linearLayout7 == null) {
                linearLayout7 = null;
            }
            linearLayout7.addView(textCheckCell, LayoutHelper.createLinear(-1, -2));
            CharSequence charSequenceWithLearnMore = AndroidUtilities.withLearnMore(LocaleController.getString(R.string.ChannelEnablePaidReactionsInfo), new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda19
                @Override // java.lang.Runnable
                public final void run() {
                    Browser.openUrl(context, LocaleController.getString(R.string.ChannelEnablePaidReactionsInfoLink));
                }
            });
            LinearLayout linearLayout8 = this.switchLayout;
            (linearLayout8 != null ? linearLayout8 : null).addView(createInfoCell(context, charSequenceWithLearnMore, 70), LayoutHelper.createLinear(-1, -2));
        }
    }

    public static void $r8$lambda$VxGye0JCN8l_pCjR53raw6inbbQ(ChannelReactionsEditActivity channelReactionsEditActivity, Integer num) {
        channelReactionsEditActivity.reactionsCount = num.intValue();
        updateActionButton$default(channelReactionsEditActivity, false, 1, null);
    }

    /* JADX WARN: Type inference failed for: r2v0, types: [com.exteragram.messenger.reactions.ChannelReactionsEditActivity$createEditText$1, org.telegram.ui.Components.Reactions.CustomReactionEditText] */
    private final CustomReactionEditText createEditText(Context context) {
        final CustomReactionEditText r2 = new CustomReactionEditText(context, this.resourceProvider, this.maxReactionsCount) { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity.createEditText.1
            @Override // org.telegram.ui.Components.EditTextCaption
            public void onLineCountChanged(int oldLineCount, int newLineCount) {
                if (newLineCount > oldLineCount) {
                    SectionsScrollView sectionsScrollView = ChannelReactionsEditActivity.this.scrollView;
                    if (sectionsScrollView == null) {
                        sectionsScrollView = null;
                    }
                    sectionsScrollView.smoothScrollBy(0, AndroidUtilities.dp(30.0f));
                }
            }

            @Override // org.telegram.ui.Components.Reactions.CustomReactionEditText, org.telegram.ui.Components.EditTextBoldCursor
            public void extendActionMode(ActionMode actionMode, Menu menu) {
                super.extendActionMode(actionMode, menu);
                if (hasSelection()) {
                    menu.add(0, android.R.id.cut, 1, android.R.string.cut);
                    menu.add(0, android.R.id.copy, 2, android.R.string.copy);
                }
                if (ChannelReactionsEditActivity.this.helper.getHasClipboard()) {
                    menu.add(0, android.R.id.paste, 3, android.R.string.paste);
                }
            }

            @Override // org.telegram.ui.Components.EditTextCaption, org.telegram.ui.Components.EditTextBoldCursor, com.exteragram.messenger.components.ReceiveContentEditText, android.widget.EditText, android.widget.TextView
            public boolean onTextContextMenuItem(int id) {
                if (id == R.id.menu_delete) {
                    return ChannelReactionsEditActivity.this.deleteSelectedEmojis();
                }
                if (id == 16908320) {
                    ChannelReactionsEditActivity.this.helper.copyReactions(getText(), getEditTextSelectionStart(), getEditTextSelectionEnd());
                    return ChannelReactionsEditActivity.this.deleteSelectedEmojis();
                }
                if (id == 16908321) {
                    ChannelReactionsEditActivity.this.helper.copyReactions(getText(), getEditTextSelectionStart(), getEditTextSelectionEnd());
                    setSelection(getEditTextSelectionEnd());
                    return true;
                }
                if (id == 16908322) {
                    ChannelReactionsEditActivity.this.pasteReactionsFromClipboard();
                    return true;
                }
                return super.onTextContextMenuItem(id);
            }
        };
        r2.setOnFocused(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                ChannelReactionsEditActivity.this.showKeyboard();
            }
        });
        return r2;
    }

    /* JADX WARN: Code duplicated, block: B:26:0x003f  */
    private final void fillInitialReactions() {
        boolean z = false;
        TLRPC.ChatFull chatFull = this.info;
        TLRPC.ChatReactions chatReactions = (chatFull == null ? null : chatFull).available_reactions;
        if (chatReactions instanceof TLRPC.TL_chatReactionsAll) {
            appendDefaultReactions();
            setCheckedEnableReactionCell(0, this.paid, false);
            return;
        }
        if (chatReactions instanceof TLRPC.TL_chatReactionsSome) {
            appendChatReactions(((TLRPC.TL_chatReactionsSome) chatReactions).reactions);
            setCheckedEnableReactionCell(1, this.paid, false);
        } else if (chatReactions instanceof TLRPC.TL_chatReactionsNone) {
            if ((chatFull == null ? null : chatFull).paid_media_allowed) {
                if (chatFull == null) {
                    chatFull = null;
                }
                z = chatFull.paid_reactions_available;
            }
            if (!restoreReactionsBackup() && !z) {
                appendDefaultReactions();
            }
            setCheckedEnableReactionCell(2, this.paid, false);
        }
    }

    private final void appendReaction(SpannableStringBuilder spannableStringBuilder, TLRPC.TL_availableReaction tL_availableReaction) {
        LinkedHashMap<Long, AnimatedEmojiSpan> linkedHashMap = this.selectedEmojisMap;
        ArrayList<Long> arrayList = this.selectedEmojisIds;
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        ReactionsUtils.addReactionToEditText(tL_availableReaction, linkedHashMap, arrayList, spannableStringBuilder, selectAnimatedEmojiDialog, customReactionEditText.getFontMetricsInt());
    }

    private final void appendCustomReaction(SpannableStringBuilder spannableStringBuilder, TLRPC.TL_reactionCustomEmoji tL_reactionCustomEmoji) {
        LinkedHashMap<Long, AnimatedEmojiSpan> linkedHashMap = this.selectedEmojisMap;
        ArrayList<Long> arrayList = this.selectedEmojisIds;
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        ReactionsUtils.addReactionToEditText(tL_reactionCustomEmoji, linkedHashMap, arrayList, spannableStringBuilder, selectAnimatedEmojiDialog, customReactionEditText.getFontMetricsInt());
    }

    private final void appendReaction(SpannableStringBuilder spannableStringBuilder, long j) {
        TLRPC.TL_availableReaction tL_availableReactionFindAvailableReaction = this.helper.findAvailableReaction(j);
        if (tL_availableReactionFindAvailableReaction != null) {
            appendReaction(spannableStringBuilder, tL_availableReactionFindAvailableReaction);
            return;
        }
        TLRPC.TL_reactionCustomEmoji tL_reactionCustomEmoji = new TLRPC.TL_reactionCustomEmoji();
        tL_reactionCustomEmoji.document_id = j;
        Unit unit = Unit.INSTANCE;
        appendCustomReaction(spannableStringBuilder, tL_reactionCustomEmoji);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void onEnableReactionsClick() {
        TextCheckCell textCheckCell = this.enableReactionsCell;
        if (textCheckCell == null) {
            textCheckCell = null;
        }
        if (textCheckCell.isChecked()) {
            this.backupPaid = getStarEnabled();
            if (getStarEnabled()) {
                toggleStarsEnabled();
            }
            setCheckedEnableReactionCell(2, false, true);
            return;
        }
        setCheckedEnableReactionCell(1, this.paid, true);
        if (!this.backupPaid || this.paidCheckCell == null || getStarEnabled()) {
            return;
        }
        this.backupPaid = false;
        enableStars$default(this, false, 1, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void applyChanges() {
        UpdateReactionsButton updateReactionsButton = this.actionButton;
        if (updateReactionsButton == null) {
            updateReactionsButton = null;
        }
        if (updateReactionsButton.isLoading()) {
            return;
        }
        TL_stories.TL_premium_boostsStatus tL_premium_boostsStatus = this.boostsStatus;
        if (this.selectedType != 2 && tL_premium_boostsStatus != null) {
            int i = tL_premium_boostsStatus.level;
            int i2 = this.selectedCustomReactions;
            if (i < i2) {
                ReactionsUtils.showLimitReachedDialogForReactions(-this.chatId, i2, tL_premium_boostsStatus);
                return;
            }
        }
        TextCheckCell textCheckCell = this.paidCheckCell;
        Boolean boolValueOf = textCheckCell != null ? Boolean.valueOf(textCheckCell.isChecked()) : null;
        saveReactionsBackup();
        UpdateReactionsButton updateReactionsButton2 = this.actionButton;
        (updateReactionsButton2 != null ? updateReactionsButton2 : null).setLoading(true);
        getMessagesController().setCustomChatReactions(this.chatId, this.selectedType, grabReactions(false), this.reactionsCount, boolValueOf, new Utilities.Callback() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda12
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                ChannelReactionsEditActivity.this.onApplyError((TLRPC.TL_error) obj);
            }
        }, new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda13
            @Override // java.lang.Runnable
            public final void run() {
                ChannelReactionsEditActivity.this.finishFragment();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void onApplyError(final TLRPC.TL_error error) {
        if (isFinishing()) {
            return;
        }
        UpdateReactionsButton updateReactionsButton = this.actionButton;
        if (updateReactionsButton == null) {
            updateReactionsButton = null;
        }
        updateReactionsButton.setLoading(false);
        if (Intrinsics.areEqual(error.text, "CHAT_NOT_MODIFIED")) {
            finishFragment();
        } else {
            AndroidUtilities.runOnUIThread(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda21
                @Override // java.lang.Runnable
                public final void run() {
                    ChannelReactionsEditActivity.$r8$lambda$HM7gLu4R1QyiwZ3LQPRk8FPDg8w(ChannelReactionsEditActivity.this, error);
                }
            }, this.boostsStatus == null ? 200L : 0L);
        }
    }

    public static void $r8$lambda$HM7gLu4R1QyiwZ3LQPRk8FPDg8w(ChannelReactionsEditActivity channelReactionsEditActivity, TLRPC.TL_error tL_error) {
        TL_stories.TL_premium_boostsStatus tL_premium_boostsStatus = channelReactionsEditActivity.boostsStatus;
        if (tL_premium_boostsStatus != null && Intrinsics.areEqual(tL_error.text, "BOOSTS_REQUIRED")) {
            ReactionsUtils.showLimitReachedDialogForReactions(-channelReactionsEditActivity.chatId, channelReactionsEditActivity.selectedCustomReactions, tL_premium_boostsStatus);
        } else {
            BulletinFactory.of(channelReactionsEditActivity).createErrorBulletin(Intrinsics.areEqual(tL_error.text, "REACTIONS_TOO_MANY") ? LocaleController.formatPluralString("ReactionMaxCountError", channelReactionsEditActivity.maxReactionsCount, new Object[0]) : tL_error.text).show();
        }
    }

    private final void saveReactionsBackup() {
        if (this.selectedType != 2 || getStarEnabled()) {
            this.backupPaid = getStarEnabled();
        }
        this.helper.saveBackup(this.selectedEmojisIds, this.backupPaid);
    }

    private final boolean restoreReactionsBackup() {
        ReactionsEditHelper.Backup backupLoadBackup = this.helper.loadBackup(this.maxReactionsCount);
        if (backupLoadBackup == null) {
            return false;
        }
        if (backupLoadBackup.getPaid()) {
            this.backupPaid = true;
        }
        if (backupLoadBackup.getReactionIds().isEmpty()) {
            return false;
        }
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        Iterator it = backupLoadBackup.getReactionIds().iterator();
        while (it.hasNext()) {
            appendReaction(spannableStringBuilder, ((Number) it.next()).longValue());
        }
        customReactionEditText.append(spannableStringBuilder);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void pasteReactionsFromClipboard() {
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        if (selectAnimatedEmojiDialog == null) {
            return;
        }
        List<AnimatedEmojiSpan> clipboard = this.helper.parseClipboard();
        if (CollectionsKt.removeAll((List) clipboard, new Function1() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda25
            @Override // kotlin.jvm.functions.Function1
            public final Object invoke(Object obj) {
                return Boolean.valueOf(ChannelReactionsEditActivity.$r8$lambda$NpF7IaAjtD_3hYhPDQce9egq6Es((AnimatedEmojiSpan) obj));
            }
        }) && this.paidCheckCell != null && !getStarEnabled()) {
            enableStars$default(this, false, 1, null);
        }
        for (AnimatedEmojiSpan animatedEmojiSpan : clipboard) {
            if (!this.selectedEmojisMap.containsKey(Long.valueOf(animatedEmojiSpan.documentId))) {
                int size = this.selectedEmojisMap.size();
                selectAnimatedEmojiDialog.onEmojiClick(null, animatedEmojiSpan);
                if (this.selectedEmojisMap.size() == size) {
                    return;
                }
            }
        }
    }

    public static boolean $r8$lambda$NpF7IaAjtD_3hYhPDQce9egq6Es(AnimatedEmojiSpan animatedEmojiSpan) {
        return animatedEmojiSpan.documentId == -1;
    }

    private final void initSelectAnimatedEmojiDialog() {
        if (this.selectAnimatedEmojiDialog != null) {
            return;
        }
        final int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, this.resourceProvider);
        final Context context = getContext();
        final Theme.ResourcesProvider resourcesProvider = this.resourceProvider;
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = new SelectAnimatedEmojiDialog(ChannelReactionsEditActivity.this, context, false, null, 6, false, resourcesProvider, 16, color) { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$initSelectAnimatedEmojiDialog$dialog$1
            private boolean firstLayout = true;

            {
                setDrawBackground(false);
            }

            @Override // org.telegram.ui.SelectAnimatedEmojiDialog, android.widget.FrameLayout, android.view.ViewGroup, android.view.View
            public void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (this.firstLayout) {
                    this.firstLayout = false;
                    onShow(null);
                }
            }

            @Override // org.telegram.ui.SelectAnimatedEmojiDialog
            public void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (documentId != null) {
                    long jLongValue = documentId.longValue();
                    boolean zContainsKey = ChannelReactionsEditActivity.this.selectedEmojisMap.containsKey(documentId);
                    ChannelReactionsEditActivity channelReactionsEditActivity = ChannelReactionsEditActivity.this;
                    if (zContainsKey) {
                        channelReactionsEditActivity.removeReaction(jLongValue);
                    } else {
                        channelReactionsEditActivity.addReaction(jLongValue, document);
                    }
                }
            }
        };
        selectAnimatedEmojiDialog.setAnimationsEnabled(false);
        selectAnimatedEmojiDialog.setClipChildren(false);
        selectAnimatedEmojiDialog.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        this.selectAnimatedEmojiDialog = selectAnimatedEmojiDialog;
        FrameLayout frameLayout = this.bottomDialogLayout;
        if (frameLayout == null) {
            frameLayout = null;
        }
        frameLayout.addView(selectAnimatedEmojiDialog, LayoutHelper.createFrame(-1, -2, 80));
        BackSpaceButtonView backSpaceButtonView = new BackSpaceButtonView(getContext(), this.resourceProvider);
        backSpaceButtonView.setOnBackspace(new Utilities.Callback() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda10
            @Override // org.telegram.messenger.Utilities.Callback
            public final void run(Object obj) {
                ChannelReactionsEditActivity.this.onBackspace(((Boolean) obj).booleanValue());
            }
        });
        FrameLayout frameLayout2 = this.bottomDialogLayout;
        (frameLayout2 != null ? frameLayout2 : null).addView(backSpaceButtonView, LayoutHelper.createFrame(-1, -2.0f, 85, 0.0f, 0.0f, 8.0f, 8.0f));
        ArrayList<Long> arrayList = this.selectedEmojisIds;
        int size = arrayList.size();
        int i = 0;
        while (i < size) {
            Long l = arrayList.get(i);
            i++;
            selectAnimatedEmojiDialog.setMultiSelected(Long.valueOf(l.longValue()), false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void addReaction(long documentId, TLRPC.Document document) {
        if (this.selectedEmojisMap.size() - (this.selectedEmojisMap.containsKey(-1L) ? 1 : 0) >= this.maxReactionsCount) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.formatPluralString("ReactionMaxCountError", this.maxReactionsCount, new Object[0])).show();
            return;
        }
        try {
            CustomReactionEditText customReactionEditText = this.editText;
            CustomReactionEditText customReactionEditText2 = null;
            if (customReactionEditText == null) {
                customReactionEditText = null;
            }
            int editTextSelectionEnd = customReactionEditText.getEditTextSelectionEnd();
            Long lValueOf = Long.valueOf(documentId);
            CustomReactionEditText customReactionEditText3 = this.editText;
            if (customReactionEditText3 == null) {
                customReactionEditText3 = null;
            }
            AnimatedEmojiSpan animatedEmojiSpanCreateAnimatedEmojiSpan = ReactionsUtils.createAnimatedEmojiSpan(document, lValueOf, customReactionEditText3.getFontMetricsInt());
            animatedEmojiSpanCreateAnimatedEmojiSpan.cacheType = AnimatedEmojiDrawable.getCacheTypeForEnterView();
            animatedEmojiSpanCreateAnimatedEmojiSpan.setAdded();
            ArrayList<Long> arrayList = this.selectedEmojisIds;
            arrayList.add(RangesKt.coerceIn(editTextSelectionEnd, 0, arrayList.size()), Long.valueOf(documentId));
            this.selectedEmojisMap.put(Long.valueOf(documentId), animatedEmojiSpanCreateAnimatedEmojiSpan);
            insertSpan(animatedEmojiSpanCreateAnimatedEmojiSpan, editTextSelectionEnd);
            CustomReactionEditText customReactionEditText4 = this.editText;
            if (customReactionEditText4 != null) {
                customReactionEditText2 = customReactionEditText4;
            }
            customReactionEditText2.setSelection(editTextSelectionEnd + 1);
            SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
            if (selectAnimatedEmojiDialog != null) {
                selectAnimatedEmojiDialog.setMultiSelected(Long.valueOf(documentId), true);
            }
            checkMaxCustomReactions(true);
            animateChangesInNextRows(animatedEmojiSpanCreateAnimatedEmojiSpan);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void removeReaction(long documentId) {
        this.selectedEmojisIds.remove(Long.valueOf(documentId));
        AnimatedEmojiSpan animatedEmojiSpanRemove = this.selectedEmojisMap.remove(Long.valueOf(documentId));
        if (animatedEmojiSpanRemove == null) {
            return;
        }
        removeSpanAnimated(animatedEmojiSpanRemove);
        animateChangesInNextRows(animatedEmojiSpanRemove);
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(Long.valueOf(documentId), true);
        }
        checkMaxCustomReactions(false);
    }

    private final void forgetReaction(long documentId) {
        this.selectedEmojisMap.remove(Long.valueOf(documentId));
        this.selectedEmojisIds.remove(Long.valueOf(documentId));
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.unselect(Long.valueOf(documentId));
        }
        if (documentId == -1) {
            onStarRemoved();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void onBackspace(boolean isFast) {
        Object obj;
        if (deleteSelectedEmojis()) {
            return;
        }
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        Editable text = customReactionEditText.getText();
        if (text == null) {
            return;
        }
        CustomReactionEditText customReactionEditText2 = this.editText;
        if (customReactionEditText2 == null) {
            customReactionEditText2 = null;
        }
        int editTextSelectionEnd = customReactionEditText2.getEditTextSelectionEnd();
        Object[] spans = text.getSpans(0, text.length(), AnimatedEmojiSpan.class);
        int length = spans.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                obj = null;
                break;
            }
            obj = spans[i];
            if (text.getSpanEnd((AnimatedEmojiSpan) obj) == editTextSelectionEnd) {
                break;
            } else {
                i++;
            }
        }
        AnimatedEmojiSpan animatedEmojiSpan = (AnimatedEmojiSpan) obj;
        if (animatedEmojiSpan == null) {
            return;
        }
        forgetReaction(animatedEmojiSpan.documentId);
        if (isFast) {
            CustomReactionEditText customReactionEditText3 = this.editText;
            (customReactionEditText3 != null ? customReactionEditText3 : null).dispatchKeyEvent(new KeyEvent(0, 67));
            AndroidUtilities.cancelRunOnUIThread(this.checkAfterFastDeleteRunnable);
            AndroidUtilities.runOnUIThread(this.checkAfterFastDeleteRunnable, 350L);
            return;
        }
        removeSpanAnimated(animatedEmojiSpan);
        animateChangesInNextRows(animatedEmojiSpan);
        checkMaxCustomReactions(false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final boolean deleteSelectedEmojis() {
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        if (!customReactionEditText.hasSelection()) {
            return false;
        }
        CustomReactionEditText customReactionEditText2 = this.editText;
        if (customReactionEditText2 == null) {
            customReactionEditText2 = null;
        }
        Editable text = customReactionEditText2.getText();
        if (text != null) {
            CustomReactionEditText customReactionEditText3 = this.editText;
            if (customReactionEditText3 == null) {
                customReactionEditText3 = null;
            }
            int editTextSelectionStart = customReactionEditText3.getEditTextSelectionStart();
            CustomReactionEditText customReactionEditText4 = this.editText;
            if (customReactionEditText4 == null) {
                customReactionEditText4 = null;
            }
            AnimatedEmojiSpan[] animatedEmojiSpanArr = (AnimatedEmojiSpan[]) text.getSpans(editTextSelectionStart, customReactionEditText4.getEditTextSelectionEnd(), AnimatedEmojiSpan.class);
            if (animatedEmojiSpanArr != null) {
                for (AnimatedEmojiSpan animatedEmojiSpan : animatedEmojiSpanArr) {
                    forgetReaction(animatedEmojiSpan.documentId);
                }
            }
        }
        CustomReactionEditText customReactionEditText5 = this.editText;
        (customReactionEditText5 != null ? customReactionEditText5 : null).dispatchKeyEvent(new KeyEvent(0, 67));
        checkMaxCustomReactions(false);
        return true;
    }

    private final void insertSpan(AnimatedEmojiSpan span, int position) {
        SpannableString spannableString = new SpannableString("b");
        spannableString.setSpan(span, 0, spannableString.length(), 33);
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        Editable text = customReactionEditText.getText();
        if (text != null) {
            text.insert(position, spannableString);
        }
    }

    private final void removeSpanAnimated(final AnimatedEmojiSpan span) {
        span.setRemoved(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                ChannelReactionsEditActivity.m2162$r8$lambda$feQUJ8VXops3vdRpQ431S8vxEU(ChannelReactionsEditActivity.this, span);
            }
        });
    }

    /* JADX INFO: renamed from: $r8$lambda$feQUJ8VX-ops3vdRpQ431S8vxEU, reason: not valid java name */
    public static void m2162$r8$lambda$feQUJ8VXops3vdRpQ431S8vxEU(ChannelReactionsEditActivity channelReactionsEditActivity, AnimatedEmojiSpan animatedEmojiSpan) {
        CustomReactionEditText customReactionEditText = channelReactionsEditActivity.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        Editable text = customReactionEditText.getText();
        if (text == null) {
            return;
        }
        int spanStart = text.getSpanStart(animatedEmojiSpan);
        int spanEnd = text.getSpanEnd(animatedEmojiSpan);
        if (spanStart == -1 || spanEnd == -1) {
            return;
        }
        CustomReactionEditText customReactionEditText2 = channelReactionsEditActivity.editText;
        if (customReactionEditText2 == null) {
            customReactionEditText2 = null;
        }
        int editTextSelectionEnd = customReactionEditText2.getEditTextSelectionEnd();
        text.delete(spanStart, spanEnd);
        CustomReactionEditText customReactionEditText3 = channelReactionsEditActivity.editText;
        CustomReactionEditText customReactionEditText4 = customReactionEditText3 != null ? customReactionEditText3 : null;
        if (spanEnd <= editTextSelectionEnd) {
            editTextSelectionEnd -= spanEnd - spanStart;
        }
        customReactionEditText4.setSelection(editTextSelectionEnd);
    }

    private final void animateChangesInNextRows(AnimatedEmojiSpan span) {
        int lineForOffset;
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        Editable text = customReactionEditText.getText();
        if (text == null) {
            return;
        }
        CustomReactionEditText customReactionEditText2 = this.editText;
        Layout layout = (customReactionEditText2 != null ? customReactionEditText2 : null).getLayout();
        if (layout != null && (lineForOffset = layout.getLineForOffset(text.getSpanStart(span)) + 1) < layout.getLineCount()) {
            Object[] spans = text.getSpans(layout.getLineStart(lineForOffset), text.length(), AnimatedEmojiSpan.class);
            for (Object obj : spans) {
                ((AnimatedEmojiSpan) obj).setAnimateChanges();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void toggleStarsEnabled() {
        if (getStarEnabled()) {
            disableStars();
        } else {
            enableStars$default(this, false, 1, null);
        }
    }

    public static /* synthetic */ void enableStars$default(ChannelReactionsEditActivity channelReactionsEditActivity, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = true;
        }
        channelReactionsEditActivity.enableStars(z);
    }

    private final void enableStars(boolean withToast) {
        TextCheckCell textCheckCell = this.paidCheckCell;
        if (textCheckCell == null) {
            return;
        }
        textCheckCell.setChecked(true);
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.setMaxLength(this.maxReactionsCount + 1);
        AnimatedEmojiSpan c01691CreateStarSpan = createStarSpan();
        c01691CreateStarSpan.cacheType = AnimatedEmojiDrawable.getCacheTypeForEnterView();
        c01691CreateStarSpan.setAdded();
        this.selectedEmojisIds.add(0, -1L);
        this.selectedEmojisMap.put(-1L, c01691CreateStarSpan);
        insertSpan(c01691CreateStarSpan, 0);
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(-1L, true);
        }
        animateChangesInNextRows(c01691CreateStarSpan);
        setCheckedEnableReactionCell(this.selectedType, true, true);
        checkMaxCustomReactions(withToast);
        CustomReactionEditText customReactionEditText2 = this.editText;
        (customReactionEditText2 != null ? customReactionEditText2 : null).updateAnimatedEmoji(true);
    }

    private final void disableStars() {
        this.selectedEmojisIds.remove((Object) (-1L));
        AnimatedEmojiSpan animatedEmojiSpanRemove = this.selectedEmojisMap.remove(-1L);
        if (animatedEmojiSpanRemove != null) {
            removeSpanAnimated(animatedEmojiSpanRemove);
            animateChangesInNextRows(animatedEmojiSpanRemove);
        }
        SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(-1L, true);
        }
        onStarRemoved();
        checkMaxCustomReactions(false);
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.updateAnimatedEmoji(true);
    }

    private final void onStarRemoved() {
        TextCheckCell textCheckCell = this.paidCheckCell;
        if (textCheckCell != null) {
            textCheckCell.setChecked(false);
        }
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.setMaxLength(this.maxReactionsCount);
        if (this.selectedType == 2) {
            setCheckedEnableReactionCell(2, false, true);
        } else {
            this.paid = false;
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.exteragram.messenger.reactions.ChannelReactionsEditActivity$createStarSpan$1] */
    private final AnimatedEmojiSpan createStarSpan() {
        return new AnimatedEmojiSpan(-1L, (Paint.FontMetricsInt) null) { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity.createStarSpan.1
            private final Bitmap bitmap;
            private final int iconSize;

            {
                Drawable drawableMutate;
                int iDp = AndroidUtilities.dp(24.0f);
                this.iconSize = iDp;
                Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iDp, iDp, Bitmap.Config.ARGB_8888);
                Drawable drawable = ContextCompat.getDrawable(ChannelReactionsEditActivity.this.getContext(), R.drawable.star_small_inner);
                if (drawable != null && (drawableMutate = drawable.mutate()) != null) {
                    drawableMutate.setBounds(0, 0, iDp, iDp);
                    drawableMutate.draw(new Canvas(bitmapCreateBitmap));
                }
                this.bitmap = bitmapCreateBitmap;
            }

            @Override // org.telegram.ui.Components.AnimatedEmojiSpan, android.text.style.ReplacementSpan
            public void draw(Canvas canvas, CharSequence charSequence, int start, int end, float x, int top, int y, int bottom, Paint paint) {
                super.draw(canvas, charSequence, start, end, x, top, y, bottom, paint);
                int iSave = canvas.save();
                try {
                    canvas.translate(x, ((top + bottom) / 2.0f) - (this.iconSize / 2.0f));
                    float f = this.extraScale;
                    int i = this.iconSize;
                    canvas.scale(f, f, x + (i / 2.0f), i / 2.0f);
                    canvas.drawBitmap(this.bitmap, 0.0f, 0.0f, (Paint) null);
                } finally {
                    canvas.restoreToCount(iSave);
                }
            }

            @Override // org.telegram.ui.Components.AnimatedEmojiSpan, android.text.style.ReplacementSpan
            public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
                return super.getSize(paint, text, start, end, fm) + AndroidUtilities.dp(5.0f);
            }
        };
    }

    private final void setCheckedEnableReactionCell(int selectType, boolean paid, boolean animated) {
        if (this.selectedType == selectType && this.paid == paid) {
            return;
        }
        this.paid = paid;
        this.selectedType = selectType;
        boolean z = selectType != 2 || paid;
        TextCheckCell textCheckCell = this.enableReactionsCell;
        if (textCheckCell == null) {
            textCheckCell = null;
        }
        boolean zIsChecked = textCheckCell.isChecked();
        TextCheckCell textCheckCell2 = this.enableReactionsCell;
        if (textCheckCell2 == null) {
            textCheckCell2 = null;
        }
        textCheckCell2.setChecked(z);
        if (animated) {
            checkMaxCustomReactions(false);
        }
        if (animated && z == zIsChecked) {
            return;
        }
        int color = Theme.getColor(z ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked);
        if (!animated) {
            TextCheckCell textCheckCell3 = this.enableReactionsCell;
            (textCheckCell3 != null ? textCheckCell3 : null).setBackgroundColor(color);
        } else {
            TextCheckCell textCheckCell4 = this.enableReactionsCell;
            if (z) {
                (textCheckCell4 != null ? textCheckCell4 : null).setBackgroundColorAnimated(true, color);
            } else {
                (textCheckCell4 != null ? textCheckCell4 : null).setBackgroundColorAnimatedReverse(color);
            }
        }
        if (z) {
            showReactionsSettings(animated);
        } else {
            hideReactionsSettings(animated);
        }
    }

    private final void showReactionsSettings(boolean animated) {
        LinearLayout linearLayout = this.switchLayout;
        if (linearLayout == null) {
            linearLayout = null;
        }
        linearLayout.setVisibility(0);
        if (animated) {
            LinearLayout linearLayout2 = this.switchLayout;
            if (linearLayout2 == null) {
                linearLayout2 = null;
            }
            linearLayout2.animate().setListener(null).cancel();
            LinearLayout linearLayout3 = this.switchLayout;
            if (linearLayout3 == null) {
                linearLayout3 = null;
            }
            linearLayout3.animate().alpha(1.0f).setDuration(350L).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda14
                @Override // java.lang.Runnable
                public final void run() {
                    ChannelReactionsEditActivity.$r8$lambda$W2cOcHG5jkaQ3MNm9nW5WOWSdkQ(ChannelReactionsEditActivity.this);
                }
            }).start();
            if (this.selectedEmojisMap.isEmpty()) {
                SelectAnimatedEmojiDialog selectAnimatedEmojiDialog = this.selectAnimatedEmojiDialog;
                if (selectAnimatedEmojiDialog != null) {
                    selectAnimatedEmojiDialog.clearSelectedDocuments();
                }
                CustomReactionEditText customReactionEditText = this.editText;
                if (customReactionEditText == null) {
                    customReactionEditText = null;
                }
                customReactionEditText.setText("");
                appendDefaultReactions();
                CustomReactionEditText customReactionEditText2 = this.editText;
                (customReactionEditText2 != null ? customReactionEditText2 : null).addReactionsSpan();
                SelectAnimatedEmojiDialog selectAnimatedEmojiDialog2 = this.selectAnimatedEmojiDialog;
                if (selectAnimatedEmojiDialog2 != null) {
                    selectAnimatedEmojiDialog2.notifyDataSetChanged();
                }
                checkMaxCustomReactions(false);
            }
        }
    }

    public static void $r8$lambda$W2cOcHG5jkaQ3MNm9nW5WOWSdkQ(ChannelReactionsEditActivity channelReactionsEditActivity) {
        CustomReactionEditText customReactionEditText = channelReactionsEditActivity.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.setFocusableInTouchMode(true);
    }

    private final void hideReactionsSettings(boolean animated) {
        if (!animated) {
            LinearLayout linearLayout = this.switchLayout;
            (linearLayout != null ? linearLayout : null).setVisibility(4);
            return;
        }
        closeKeyboard();
        LinearLayout linearLayout2 = this.switchLayout;
        if (linearLayout2 == null) {
            linearLayout2 = null;
        }
        linearLayout2.animate().setListener(null).cancel();
        LinearLayout linearLayout3 = this.switchLayout;
        (linearLayout3 != null ? linearLayout3 : null).animate().alpha(0.0f).setDuration(350L).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda20
            @Override // java.lang.Runnable
            public final void run() {
                ChannelReactionsEditActivity.$r8$lambda$3Xx0mLa_zXbWx99W0LZNf5sh02Q(ChannelReactionsEditActivity.this);
            }
        }).start();
    }

    public static void $r8$lambda$3Xx0mLa_zXbWx99W0LZNf5sh02Q(ChannelReactionsEditActivity channelReactionsEditActivity) {
        CustomReactionEditText customReactionEditText = channelReactionsEditActivity.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.setFocusableInTouchMode(false);
        LinearLayout linearLayout = channelReactionsEditActivity.switchLayout;
        (linearLayout != null ? linearLayout : null).setVisibility(4);
    }

    private final boolean getHasChanges() {
        if (Intrinsics.areEqual(this.selectedEmojisMap.keySet(), this.initialSelectedIds) && this.initialPaid == this.paid) {
            if ((this.selectedType == 2) == (this.initialType == 2) && this.reactionsCount == this.initialReactionsCount) {
                return false;
            }
        }
        return true;
    }

    public static /* synthetic */ void updateActionButton$default(ChannelReactionsEditActivity channelReactionsEditActivity, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = true;
        }
        channelReactionsEditActivity.updateActionButton(z);
    }

    private final void updateActionButton(boolean animated) {
        final boolean hasChanges = getHasChanges();
        if (hasChanges == this.actionButtonShown) {
            return;
        }
        this.actionButtonShown = hasChanges;
        FrameLayout frameLayout = this.actionButtonContainer;
        if (frameLayout == null) {
            frameLayout = null;
        }
        frameLayout.animate().setListener(null).cancel();
        FrameLayout frameLayout2 = this.actionButtonContainer;
        if (!animated) {
            if (frameLayout2 == null) {
                frameLayout2 = null;
            }
            frameLayout2.setAlpha(hasChanges ? 1.0f : 0.0f);
            FrameLayout frameLayout3 = this.actionButtonContainer;
            (frameLayout3 != null ? frameLayout3 : null).setVisibility(hasChanges ? 0 : 4);
            return;
        }
        if (frameLayout2 == null) {
            frameLayout2 = null;
        }
        frameLayout2.setVisibility(0);
        FrameLayout frameLayout4 = this.actionButtonContainer;
        (frameLayout4 != null ? frameLayout4 : null).animate().alpha(hasChanges ? 1.0f : 0.0f).setDuration(350L).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda11
            @Override // java.lang.Runnable
            public final void run() {
                ChannelReactionsEditActivity.$r8$lambda$JaCJT_oEDKCa4SSxjYYEPWcu0UI(hasChanges, ChannelReactionsEditActivity.this);
            }
        }).start();
    }

    public static void $r8$lambda$JaCJT_oEDKCa4SSxjYYEPWcu0UI(boolean z, ChannelReactionsEditActivity channelReactionsEditActivity) {
        if (z) {
            return;
        }
        FrameLayout frameLayout = channelReactionsEditActivity.actionButtonContainer;
        if (frameLayout == null) {
            frameLayout = null;
        }
        frameLayout.setVisibility(4);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final boolean checkChangesBeforeExit(boolean invoked) {
        if (!getHasChanges()) {
            return false;
        }
        if (!invoked) {
            return true;
        }
        new AlertDialog.Builder(getContext(), this.resourceProvider).setTitle(LocaleController.getString(R.string.UnsavedChanges)).setMessage(LocaleController.getString(R.string.ReactionApplyChangesDialog)).setPositiveButton(LocaleController.getString(R.string.ApplyTheme), new AlertDialog.OnButtonClickListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda0
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i) {
                ChannelReactionsEditActivity.$r8$lambda$30ljqUYIzbXSyojF224v5ZHPpts(ChannelReactionsEditActivity.this, alertDialog, i);
            }
        }).setNegativeButton(LocaleController.getString(R.string.Discard), new AlertDialog.OnButtonClickListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda1
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i) {
                ChannelReactionsEditActivity.this.finishFragment();
            }
        }).show();
        return true;
    }

    public static void $r8$lambda$30ljqUYIzbXSyojF224v5ZHPpts(ChannelReactionsEditActivity channelReactionsEditActivity, AlertDialog alertDialog, int i) {
        UpdateReactionsButton updateReactionsButton = channelReactionsEditActivity.actionButton;
        if (updateReactionsButton == null) {
            updateReactionsButton = null;
        }
        updateReactionsButton.performClick();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void checkMaxCustomReactions(boolean withToast) {
        updateActionButton$default(this, false, 1, null);
        TL_stories.TL_premium_boostsStatus tL_premium_boostsStatus = this.boostsStatus;
        if (tL_premium_boostsStatus == null) {
            return;
        }
        int i = this.selectedType;
        if (i == 2) {
            UpdateReactionsButton updateReactionsButton = this.actionButton;
            (updateReactionsButton != null ? updateReactionsButton : null).removeLvlRequiredState();
            return;
        }
        if (i == 0) {
            this.selectedType = 1;
        }
        int size = grabReactions(true).size();
        this.selectedCustomReactions = size;
        if (tL_premium_boostsStatus.level >= size) {
            UpdateReactionsButton updateReactionsButton2 = this.actionButton;
            (updateReactionsButton2 != null ? updateReactionsButton2 : null).removeLvlRequiredState();
        } else {
            if (withToast) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, AndroidUtilities.replaceTags(LocaleController.formatPluralString("ReactionReachLvlForReactionShort", size, Integer.valueOf(size)))).show();
            }
            UpdateReactionsButton updateReactionsButton3 = this.actionButton;
            (updateReactionsButton3 != null ? updateReactionsButton3 : null).setLvlRequiredState(this.selectedCustomReactions);
        }
    }

    private final List<TLRPC.Reaction> grabReactions(boolean onlyCustom) {
        final ArrayList<TLRPC.Reaction> result = new ArrayList<>();
        for (Long id : this.selectedEmojisIds) {
            if (id == null || id == -1) continue;
            final TLRPC.TL_availableReaction available = this.helper.findAvailableReaction(id);
            if (available == null) {
                final TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
                custom.document_id = id;
                result.add(custom);
            } else if (!onlyCustom) {
                final TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
                emoji.emoticon = available.reaction;
                result.add(emoji);
            }
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void showKeyboard() {
        if (this.emojiKeyboardVisible) {
            return;
        }
        this.emojiKeyboardVisible = true;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        FrameLayout frameLayout = this.bottomDialogLayout;
        if (frameLayout == null) {
            frameLayout = null;
        }
        int measuredHeight = frameLayout.getMeasuredHeight();
        updateScrollViewMarginBottom(measuredHeight);
        FrameLayout frameLayout2 = this.bottomDialogLayout;
        if (frameLayout2 == null) {
            frameLayout2 = null;
        }
        frameLayout2.setVisibility(0);
        FrameLayout frameLayout3 = this.bottomDialogLayout;
        if (frameLayout3 == null) {
            frameLayout3 = null;
        }
        frameLayout3.setTranslationY(measuredHeight);
        FrameLayout frameLayout4 = this.bottomDialogLayout;
        if (frameLayout4 == null) {
            frameLayout4 = null;
        }
        frameLayout4.animate().setListener(null).cancel();
        FrameLayout frameLayout5 = this.bottomDialogLayout;
        (frameLayout5 != null ? frameLayout5 : null).animate().translationY(0.0f).withLayer().setDuration(350L).setInterpolator(CubicBezierInterpolator.DEFAULT).setUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda23
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                ChannelReactionsEditActivity.$r8$lambda$b9YGjPVGpVCusjpplFeSGLsJDPM(ChannelReactionsEditActivity.this, valueAnimator);
            }
        }).withEndAction(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda24
            @Override // java.lang.Runnable
            public final void run() {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
            }
        }).start();
    }

    public static void $r8$lambda$b9YGjPVGpVCusjpplFeSGLsJDPM(ChannelReactionsEditActivity channelReactionsEditActivity, ValueAnimator valueAnimator) {
        FrameLayout frameLayout = channelReactionsEditActivity.actionButtonContainer;
        if (frameLayout == null) {
            frameLayout = null;
        }
        float f = -((Float) valueAnimator.getAnimatedValue()).floatValue();
        FrameLayout frameLayout2 = channelReactionsEditActivity.bottomDialogLayout;
        frameLayout.setTranslationY(f * (frameLayout2 != null ? frameLayout2 : null).getMeasuredHeight());
    }

    private final void closeKeyboard() {
        if (this.emojiKeyboardVisible) {
            this.emojiKeyboardVisible = false;
            if (isClearFocusNotWorking()) {
                LinearLayout linearLayout = this.switchLayout;
                if (linearLayout == null) {
                    linearLayout = null;
                }
                linearLayout.setFocusableInTouchMode(true);
                LinearLayout linearLayout2 = this.switchLayout;
                if (linearLayout2 == null) {
                    linearLayout2 = null;
                }
                linearLayout2.requestFocus();
            } else {
                CustomReactionEditText customReactionEditText = this.editText;
                if (customReactionEditText == null) {
                    customReactionEditText = null;
                }
                customReactionEditText.clearFocus();
            }
            updateScrollViewMarginBottom(0);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
            FrameLayout frameLayout = this.bottomDialogLayout;
            if (frameLayout == null) {
                frameLayout = null;
            }
            frameLayout.animate().setListener(null).cancel();
            FrameLayout frameLayout2 = this.bottomDialogLayout;
            if (frameLayout2 == null) {
                frameLayout2 = null;
            }
            ViewPropertyAnimator viewPropertyAnimatorAnimate = frameLayout2.animate();
            FrameLayout frameLayout3 = this.bottomDialogLayout;
            viewPropertyAnimatorAnimate.translationY((frameLayout3 != null ? frameLayout3 : null).getMeasuredHeight()).setDuration(350L).withLayer().setInterpolator(CubicBezierInterpolator.DEFAULT).setUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda2
                @Override // android.animation.ValueAnimator.AnimatorUpdateListener
                public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                    ChannelReactionsEditActivity.m2161$r8$lambda$arSEJMO2mp3RLxBCaJXj4Gjwrc(ChannelReactionsEditActivity.this, valueAnimator);
                }
            }).withEndAction(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    ChannelReactionsEditActivity.$r8$lambda$SAQV_ehwphJTdBl7Mi1y_rPm75c(ChannelReactionsEditActivity.this);
                }
            }).start();
        }
    }

    /* JADX INFO: renamed from: $r8$lambda$arSEJMO2mp3RLxBC-aJXj4Gjwrc, reason: not valid java name */
    public static void m2161$r8$lambda$arSEJMO2mp3RLxBCaJXj4Gjwrc(ChannelReactionsEditActivity channelReactionsEditActivity, ValueAnimator valueAnimator) {
        FrameLayout frameLayout = channelReactionsEditActivity.actionButtonContainer;
        if (frameLayout == null) {
            frameLayout = null;
        }
        float f = -(1.0f - ((Float) valueAnimator.getAnimatedValue()).floatValue());
        FrameLayout frameLayout2 = channelReactionsEditActivity.bottomDialogLayout;
        frameLayout.setTranslationY(f * (frameLayout2 != null ? frameLayout2 : null).getMeasuredHeight());
    }

    public static void $r8$lambda$SAQV_ehwphJTdBl7Mi1y_rPm75c(ChannelReactionsEditActivity channelReactionsEditActivity) {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
        FrameLayout frameLayout = channelReactionsEditActivity.bottomDialogLayout;
        if (frameLayout == null) {
            frameLayout = null;
        }
        frameLayout.setVisibility(4);
        if (channelReactionsEditActivity.isClearFocusNotWorking()) {
            LinearLayout linearLayout = channelReactionsEditActivity.switchLayout;
            (linearLayout != null ? linearLayout : null).setFocusableInTouchMode(false);
        }
    }

    private final boolean isClearFocusNotWorking() {
        return StringsKt.startsWith(Build.MODEL.toLowerCase(Locale.ROOT), "zte", false) && Build.VERSION.SDK_INT <= 28;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void updateScrollViewMarginBottom(int margin) {
        SectionsScrollView sectionsScrollView = this.scrollView;
        View view = sectionsScrollView == null ? null : sectionsScrollView;
        if (sectionsScrollView == null) {
            sectionsScrollView = null;
        }
        ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) sectionsScrollView.getLayoutParams();
        marginLayoutParams.bottomMargin = margin;
        view.setLayoutParams(marginLayoutParams);
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean canBeginSlide() {
        return !checkChangesBeforeExit(true) && super.canBeginSlide();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && this.selectedType != 2) {
            CustomReactionEditText customReactionEditText = this.editText;
            if (customReactionEditText == null) {
                customReactionEditText = null;
            }
            customReactionEditText.setFocusableInTouchMode(true);
        }
        if (!isOpen || backward) {
            return;
        }
        initSelectAnimatedEmojiDialog();
        AndroidUtilities.runOnUIThread(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
            }
        }, 200L);
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onResume() {
        super.onResume();
        if (this.paused) {
            this.paused = false;
            CustomReactionEditText customReactionEditText = this.editText;
            if (customReactionEditText == null) {
                customReactionEditText = null;
            }
            customReactionEditText.setFocusable(true);
            CustomReactionEditText customReactionEditText2 = this.editText;
            if (customReactionEditText2 == null) {
                customReactionEditText2 = null;
            }
            customReactionEditText2.setFocusableInTouchMode(true);
            if (this.emojiKeyboardVisible) {
                CustomReactionEditText customReactionEditText3 = this.editText;
                (customReactionEditText3 != null ? customReactionEditText3 : null).removeReactionsSpan(false);
                AndroidUtilities.runOnUIThread(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$$ExternalSyntheticLambda9
                    @Override // java.lang.Runnable
                    public final void run() {
                        ChannelReactionsEditActivity.m2157$r8$lambda$50wckkxXLuZwZNP3Evo3xjkU1Y(ChannelReactionsEditActivity.this);
                    }
                }, 250L);
            }
        }
    }

    /* JADX INFO: renamed from: $r8$lambda$50wckkxXLuZwZNP3Evo-3xjkU1Y, reason: not valid java name */
    public static void m2157$r8$lambda$50wckkxXLuZwZNP3Evo3xjkU1Y(ChannelReactionsEditActivity channelReactionsEditActivity) {
        CustomReactionEditText customReactionEditText = channelReactionsEditActivity.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.requestFocus();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onPause() {
        this.paused = true;
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        customReactionEditText.setFocusable(false);
        super.onPause();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean onBackPressed(boolean invoked) {
        if (!this.emojiKeyboardVisible) {
            return !checkChangesBeforeExit(invoked) && super.onBackPressed(invoked);
        }
        if (invoked) {
            closeKeyboard();
        }
        return false;
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(this.checkAfterFastDeleteRunnable);
        NotificationCenter.ObserversGroup observersGroup = this.observers;
        if (observersGroup != null) {
            observersGroup.removeAllObservers();
        }
    }

    @Override // org.telegram.messenger.NotificationCenter.NotificationCenterDelegate
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogDeleted && ((Long) args[0]).longValue() == (-this.chatId)) {
            INavigationLayout iNavigationLayout = this.parentLayout;
            if ((iNavigationLayout != null ? iNavigationLayout.getLastFragment() : null) == this) {
                finishFragment();
            } else {
                removeSelfFromStack();
            }
        }
    }

    private final void appendDefaultReactions() {
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        Iterator it = CollectionsKt.take(this.allAvailableReactions, this.maxReactionsCount).iterator();
        while (it.hasNext()) {
            appendReaction(spannableStringBuilder, (TLRPC.TL_availableReaction) it.next());
        }
        customReactionEditText.append(spannableStringBuilder);
    }

    private final void appendChatReactions(List<? extends TLRPC.Reaction> reactions) {
        CustomReactionEditText customReactionEditText = this.editText;
        if (customReactionEditText == null) {
            customReactionEditText = null;
        }
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        ArrayList arrayList = new ArrayList();
        for (TLObject tLObject : reactions) {
            if (tLObject instanceof TLRPC.TL_reactionEmoji) {
                tLObject = getMediaDataController().getReactionsMap().get(((TLRPC.TL_reactionEmoji) tLObject).emoticon);
            } else if (!(tLObject instanceof TLRPC.TL_reactionCustomEmoji)) {
                tLObject = null;
            }
            if (tLObject != null) {
                arrayList.add(tLObject);
            }
        }
        for (TLObject tLObject2 : (java.util.List<TLObject>) CollectionsKt.take(arrayList, this.maxReactionsCount)) {
            if (tLObject2 instanceof TLRPC.TL_availableReaction) {
                appendReaction(spannableStringBuilder, (TLRPC.TL_availableReaction) tLObject2);
            } else if (tLObject2 instanceof TLRPC.TL_reactionCustomEmoji) {
                appendCustomReaction(spannableStringBuilder, (TLRPC.TL_reactionCustomEmoji) tLObject2);
            }
        }
        customReactionEditText.append(spannableStringBuilder);
    }
}
