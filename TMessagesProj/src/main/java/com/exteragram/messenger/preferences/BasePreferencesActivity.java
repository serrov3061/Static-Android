package com.exteragram.messenger.preferences;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.os.Build;
import android.os.Parcelable;
import android.view.View;
import android.widget.FrameLayout;

import androidx.collection.LongSparseArray;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.ui.ChatHeaderUiHelper;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

import java.util.ArrayList;

/**
 * Base screen of exteraGram preferences (BasePreferencesActivity.kt): glass action bar over a
 * sectioned UniversalRecyclerView, list dialogs, setting links (long press) and search registration.
 * The uCheck/uButton helpers keep exteraGram's Kotlin default-argument masks, so the ported screens
 * can call them unchanged.
 */
public abstract class BasePreferencesActivity extends BaseFragment {

    private ChatActivityFadeView fadeView;
    private float headerFadeAlpha;
    private ValueAnimator headerFadeAnimator;
    private boolean headerFadeShown;
    private IBlur3Capture iBlur3Capture;
    private final RectF iBlur3PositionActionBar = new RectF();
    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final GlassSourceColor iBlur3SourceColor = new GlassSourceColor();
    private final GlassSourceRenderNode iBlur3SourceGlass;
    protected LinearLayoutManager layoutManager;
    public UniversalRecyclerView listView;
    private Parcelable recyclerViewState;
    private final DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;

    public abstract void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);

    public abstract String getTitle();

    public abstract void onClick(UItem item, View view, int position, float x, float y);

    public BasePreferencesActivity() {
        scrollableViewNoiseSuppressor = Build.VERSION.SDK_INT >= 31 ? new DownscaleScrollableNoiseSuppressor() : null;
        iBlur3SourceGlass = Build.VERSION.SDK_INT >= 31 ? new GlassSourceRenderNode(iBlur3SourceColor) : null;
        iBlur3Positions.add(iBlur3PositionActionBar);
    }

    public boolean hasHeaderFade() {
        return true;
    }

    public void initializeOptionStrings() {
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    public boolean needHideTitle() {
        return false;
    }

    public final UniversalRecyclerView getListView$TMessagesProj() {
        return listView;
    }

    public final void setListView$TMessagesProj(UniversalRecyclerView view) {
        listView = view;
    }

    public final LinearLayoutManager getLayoutManager() {
        return layoutManager;
    }

    public final void setLayoutManager(LinearLayoutManager manager) {
        layoutManager = manager;
    }

    public final ChatActivityFadeView getFadeView() {
        return fadeView;
    }

    public final void setFadeView(ChatActivityFadeView view) {
        fadeView = view;
    }

    @Override
    public View createView(Context context) {
        initializeOptionStrings();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);

        HeaderContentView contentView = new HeaderContentView(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        if (actionBar.menu == null) {
            actionBar.createMenu();
        }
        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        BlurredBackgroundDrawableViewFactory factory;
        if (iBlur3SourceGlass != null && Build.VERSION.SDK_INT >= 31) {
            factory = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        } else {
            factory = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
        factory.setSourceRootView(new ViewPositionWatcher(contentView), contentView);
        actionBar.setGlassPadding(AndroidUtilities.dp(12));
        actionBar.setGlassTitleTextSize(20);
        actionBar.setupGlass(factory, BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()));
        ChatHeaderUiHelper.applyChatHeaderGlassStyle(actionBar, true);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);

        final UniversalRecyclerView list = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        list.setSections();
        if (needHideTitle()) {
            actionBar.setAdaptiveBackground(list, true, -1, -1); // StaticGram: no solid fill, the glass bar (as on other settings pages) shows when scrolled; only the title fades
        }
        list.adapter.setApplyBackground(false);
        list.setClipToPadding(false);
        list.setPadding(0, getListTopPadding(AndroidUtilities.statusBarHeight), 0, 0);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        list.setLayoutManager(layoutManager);
        if (recyclerViewState != null) {
            layoutManager.onRestoreInstanceState(recyclerViewState);
            recyclerViewState = null;
        }
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateHeaderFade(true);
                if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
            }
        });
        list.addEdgeEffectListener(() -> list.postOnAnimation(this::blur3_InvalidateBlur));
        listView = list;
        iBlur3Capture = new ViewGroupPartRenderer(list, contentView, list::drawChild);
        contentView.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (hasHeaderFade()) {
            ChatActivityFadeView fade = new ChatActivityFadeView(context);
            fade.setupColorKey(Theme.key_windowBackgroundGray);
            fade.setFadeTopAlpha(0);
            fadeView = fade;
            contentView.addView(fade, LayoutHelper.createFrameMatchParent());
        }
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP));
        fragmentView = contentView;
        return contentView;
    }

    @Override
    public void clearViews() {
        if (fragmentView != null && layoutManager != null) {
            recyclerViewState = layoutManager.onSaveInstanceState();
        }
        super.clearViews();
    }

    public final class HeaderContentView extends FrameLayout implements Theme.Colorable {

        public HeaderContentView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            updateTopFade();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            updateHeaderFade(false);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null && iBlur3SourceGlass != null) {
                blur3_InvalidateBlur();
                if (!iBlur3SourceGlass.inRecording()) {
                    RecordingCanvas recordingCanvas = iBlur3SourceGlass.beginRecording(getMeasuredWidth(), getMeasuredHeight());
                    recordingCanvas.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                    if (SharedConfig.chatBlurEnabled()) {
                        scrollableViewNoiseSuppressor.draw(recordingCanvas, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                    }
                    iBlur3SourceGlass.endRecording();
                }
            }
            super.dispatchDraw(canvas);
        }

        @Override
        public void updateColors() {
            int color = getThemedColor(Theme.key_windowBackgroundGray);
            setBackgroundColor(color);
            iBlur3SourceColor.setColor(color);
            updateTopFade();
        }
    }

    private void blur3_InvalidateBlur() {
        if (iBlur3Capture == null || fragmentView == null || Build.VERSION.SDK_INT < 31 || scrollableViewNoiseSuppressor == null || !SharedConfig.chatBlurEnabled()) {
            return;
        }
        int width = fragmentView.getMeasuredWidth();
        int height = fragmentView.getMeasuredHeight();
        int extra = AndroidUtilities.dp(48);
        iBlur3PositionActionBar.set(0, -extra, width, actionBar.getMeasuredHeight() + extra);
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, width, height);
    }

    private void updateTopFade() {
        if (fadeView == null) {
            return;
        }
        ChatHeaderUiHelper.setupChatTopFade(fadeView, actionBar, getThemedColor(Theme.key_windowBackgroundGray), actionBar.getMeasuredHeight(), true);
        fadeView.setFadeTopAlpha(Math.round(headerFadeAlpha * 255.0f));
    }

    private void updateHeaderFade(boolean animated) {
        if (fadeView == null || listView == null) {
            return;
        }
        boolean show = listView.canScrollVertically(-1);
        if (headerFadeShown == show) {
            return;
        }
        headerFadeShown = show;
        if (headerFadeAnimator != null) {
            headerFadeAnimator.cancel();
        }
        headerFadeAnimator = null;
        if (!animated) {
            setHeaderFadeAlpha(show ? 1.0f : 0.0f);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(headerFadeAlpha, show ? 1.0f : 0.0f);
        animator.addUpdateListener(a -> setHeaderFadeAlpha((Float) a.getAnimatedValue()));
        animator.setDuration(320);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        headerFadeAnimator = animator;
        animator.start();
    }

    private void setHeaderFadeAlpha(float alpha) {
        headerFadeAlpha = alpha;
        if (fadeView != null) {
            fadeView.setFadeTopAlpha(Math.round(alpha * 255.0f));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getTopOffset(int tag) {
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }

            @Override
            public int getBottomOffset(int tag) {
                return BasePreferencesActivity.this.getBottomInset();
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Bulletin.removeDelegate(this);
    }

    /** Called before a settings link scrolls to the row (e.g. to expand a collapsed group). */
    protected void onOpenedByLink(int itemId) {
    }

    public final void scrollToItem(final int itemId) {
        if (listView == null) {
            return;
        }
        onOpenedByLink(itemId);
        int position = listView.findPositionByItemId(itemId);
        if (position >= 0 && position < (listView.adapter != null ? listView.adapter.getItemCount() : 0)) {
            layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(80));
            listView.highlightRow(() -> listView.findPositionByItemId(itemId));
            return;
        }
        SettingsRegistry.getInstance().onSettingNotFound(this);
    }

    /** Kotlin default-argument bridge (mask 64: useRadioCell = icons == null, mask 128: skipIfValueNotUpdated = true). */
    public static void showListDialog$default(BasePreferencesActivity fragment, UItem item, CharSequence[] options, int[] icons, String title, int currentValue, PopupUtils.OnItemClickListener onSelected, boolean useRadioCell, boolean skipIfValueNotUpdated, int mask, Object unused) {
        if ((mask & 64) != 0) {
            useRadioCell = icons == null;
        }
        if ((mask & 128) != 0) {
            skipIfValueNotUpdated = true;
        }
        fragment.showListDialog(item, options, icons, title, currentValue, onSelected, useRadioCell, skipIfValueNotUpdated);
    }

    public final void showListDialog(UItem item, CharSequence[] options, String title, int currentValue, PopupUtils.OnItemClickListener onSelected) {
        showListDialog(item, options, null, title, currentValue, onSelected, true, true);
    }

    public final void showListDialog(UItem item, CharSequence[] options, int[] icons, String title, int currentValue, PopupUtils.OnItemClickListener onSelected) {
        showListDialog(item, options, icons, title, currentValue, onSelected, icons == null, true);
    }

    public final void showListDialog(UItem item, CharSequence[] options, int[] icons, String title, int currentValue, PopupUtils.OnItemClickListener onSelected, boolean useRadioCell) {
        showListDialog(item, options, icons, title, currentValue, onSelected, useRadioCell, true);
    }

    public final void showListDialog(final UItem item, final CharSequence[] options, int[] icons, String title, final int currentValue, final PopupUtils.OnItemClickListener onSelected, boolean useRadioCell, final boolean skipIfValueNotUpdated) {
        if (getParentActivity() == null) {
            return;
        }
        PopupUtils.showDialog(options, icons, title, currentValue, getContext(), which -> {
            if (skipIfValueNotUpdated && currentValue == which) {
                return;
            }
            onSelected.onClick(which);
            View view = listView.findViewByItemId(item.id);
            if (view instanceof TextCell) {
                ((TextCell) view).setValue(options[which], true);
            }
            if (listView.adapter != null) {
                listView.adapter.update(true);
            }
        }, resourceProvider, useRadioCell);
    }

    public final void showRestartBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.RestartRequired), LocaleController.getString(R.string.BotUnblock), () -> {
            Context context = getContext();
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            Intent restart = Intent.makeRestartActivityTask(launch != null ? launch.getComponent() : null);
            restart.setPackage(context.getPackageName());
            context.startActivity(restart);
            Runtime.getRuntime().exit(0);
        }).show();
    }

    public final void toggleBooleanSettingAndRefresh$TMessagesProj(UItem item, Consumer<Boolean> stateSetter) {
        View view = listView.findViewByItemId(item.id);
        boolean checked = !item.checked;
        stateSetter.accept(checked);
        item.setChecked(checked);
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(checked, true);
        } else if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(checked);
        }
        if (listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        if (actionBar == null) {
            return super.isLightStatusBar();
        }
        return ChatHeaderUiHelper.isLightChatStatusBar(actionBar, getThemedColor(Theme.key_windowBackgroundGray), true);
    }

    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        String link = SettingsRegistry.getInstance().getFirstSettingLink(getClass(), item);
        if (link == null) {
            return false;
        }
        showCopyLinkOptions(view, link);
        return true;
    }

    public final void showCopyLinkOptions(View view, final String settingLink) {
        final ItemOptions options = ItemOptions.makeOptions(this, view);
        options.setLongPressSelectionEnabled(false);
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
            if (AndroidUtilities.addToClipboard(settingLink)) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
            }
        });
        options.add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> shareSettingLink(options.getContext(), settingLink));
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.show();
    }

    private void shareSettingLink(Context context, String link) {
        showDialog(new ShareAlert(context, null, link, false, link, false, resourceProvider) {
            @Override
            protected void onSend(LongSparseArray<TLRPC.Dialog> dids, int count, TLRPC.TL_forumTopic topic, boolean showToast) {
                if (!showToast) {
                    return;
                }
                String text;
                if (dids != null && dids.size() == 1) {
                    long did = dids.valueAt(0).id;
                    if (did == 0 || did == getUserConfig().clientUserId) {
                        text = LocaleController.getString(R.string.SettingLinkToSavedMessages);
                    } else {
                        text = LocaleController.formatString(R.string.SettingLinkToUser, getMessagesController().getPeerName(did, true));
                    }
                } else {
                    text = LocaleController.formatString(R.string.SettingLinkToChats, LocaleController.formatPluralString("Chats", count));
                }
                Bulletin bulletin = BulletinFactory.of(BasePreferencesActivity.this).createSimpleBulletin(R.raw.forward, text);
                bulletin.hideAfterBottomSheet = false;
                bulletin.show(true);
            }
        });
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        listView.setPadding(0, getListTopPadding(top), 0, bottom);
        listView.setClipToPadding(false);
    }

    public int getListTopPadding(int topInset) {
        return topInset + ActionBar.getCurrentActionBarHeight();
    }

    public static /* synthetic */ UItem uCheck$TMessagesProj$default(BasePreferencesActivity basePreferencesActivity, Enum r4, CharSequence charSequence, boolean z, CharSequence charSequence2, int i, boolean z2, String str, boolean z3, int i2, int i3, Object obj) {
        if (obj != null) {
            com.exteragram.messenger.utils.ExteraCompat.unsupported("Super calls with default arguments not supported in this target, function: uCheck");
            return null;
        }
        if ((i3 & 8) != 0) {
            charSequence2 = null;
        }
        if ((i3 & 16) != 0) {
            i = 0;
        }
        if ((i3 & 32) != 0) {
            z2 = true;
        }
        if ((i3 & 64) != 0) {
            str = null;
        }
        if ((i3 & 128) != 0) {
            z3 = true;
        }
        if ((i3 & 256) != 0) {
            i2 = 0;
        }
        return basePreferencesActivity.uCheck$TMessagesProj((Enum<?>) r4, charSequence, z, charSequence2, i, z2, str, z3, i2);
    }

    public final UItem uCheck$TMessagesProj(Enum<?> enumEntry, CharSequence text, boolean checked, CharSequence subtext, int checkBoxIcon, boolean searchable, String linkAlias, boolean multiline, int icon) {
        return uCheck$TMessagesProj(enumEntry.ordinal() + 1, text, checked, subtext, checkBoxIcon, searchable, linkAlias, multiline, icon);
    }

    public static /* synthetic */ UItem uCheck$TMessagesProj$default(BasePreferencesActivity basePreferencesActivity, int i, CharSequence charSequence, boolean z, CharSequence charSequence2, int i2, boolean z2, String str, boolean z3, int i3, int i4, Object obj) {
        if (obj != null) {
            com.exteragram.messenger.utils.ExteraCompat.unsupported("Super calls with default arguments not supported in this target, function: uCheck");
            return null;
        }
        if ((i4 & 8) != 0) {
            charSequence2 = null;
        }
        if ((i4 & 16) != 0) {
            i2 = 0;
        }
        if ((i4 & 32) != 0) {
            z2 = true;
        }
        if ((i4 & 64) != 0) {
            str = null;
        }
        if ((i4 & 128) != 0) {
            z3 = true;
        }
        if ((i4 & 256) != 0) {
            i3 = 0;
        }
        return basePreferencesActivity.uCheck$TMessagesProj(i, charSequence, z, charSequence2, i2, z2, str, z3, i3);
    }

    public final UItem uCheck$TMessagesProj(int id, CharSequence text, boolean checked, CharSequence subtext, int checkBoxIcon, boolean searchable, String linkAlias, boolean multiline, int icon) {
        UItem uItemAsCheck;
        if (subtext != null) {
            uItemAsCheck = UItem.asCheck(id, text, subtext, multiline);
        } else {
            uItemAsCheck = UItem.asCheck(id, text);
        }
        uItemAsCheck.setChecked(checked);
        if (icon != 0) {
            uItemAsCheck.iconResId = icon;
        }
        if (checkBoxIcon != 0) {
            uItemAsCheck.setCheckBoxIcon(checkBoxIcon);
        }
        if (searchable) {
            uItemAsCheck.setSearchable(this);
        }
        if (linkAlias != null) {
            uItemAsCheck.setLinkAlias(linkAlias, this);
        }
        return uItemAsCheck;
    }

    public static /* synthetic */ UItem uButton$TMessagesProj$default(BasePreferencesActivity basePreferencesActivity, Enum r3, CharSequence charSequence, int i, CharSequence charSequence2, CharSequence charSequence3, Boolean bool, boolean z, String str, boolean z2, boolean z3, Boolean bool2, int i2, Object obj) {
        if (obj != null) {
            com.exteragram.messenger.utils.ExteraCompat.unsupported("Super calls with default arguments not supported in this target, function: uButton");
            return null;
        }
        if ((i2 & 4) != 0) {
            i = 0;
        }
        if ((i2 & 8) != 0) {
            charSequence2 = null;
        }
        if ((i2 & 16) != 0) {
            charSequence3 = null;
        }
        if ((i2 & 32) != 0) {
            bool = null;
        }
        if ((i2 & 64) != 0) {
            z = true;
        }
        if ((i2 & 128) != 0) {
            str = null;
        }
        if ((i2 & 256) != 0) {
            z2 = false;
        }
        if ((i2 & 512) != 0) {
            z3 = false;
        }
        if ((i2 & 1024) != 0) {
            bool2 = null;
        }
        return basePreferencesActivity.uButton$TMessagesProj((Enum<?>) r3, charSequence, i, charSequence2, charSequence3, bool, z, str, z2, z3, bool2);
    }

    public final UItem uButton$TMessagesProj(Enum<?> enumEntry, CharSequence text, int icon, CharSequence value, CharSequence subtext, Boolean showDivider, boolean searchable, String linkAlias, boolean red, boolean accent, Boolean prioritizeTitleOverValue) {
        return uButton$TMessagesProj(enumEntry.ordinal() + 1, text, icon, value, subtext, showDivider, searchable, linkAlias, red, accent, prioritizeTitleOverValue);
    }

    public static /* synthetic */ UItem uButton$TMessagesProj$default(BasePreferencesActivity basePreferencesActivity, int i, CharSequence charSequence, int i2, CharSequence charSequence2, CharSequence charSequence3, Boolean bool, boolean z, String str, boolean z2, boolean z3, Boolean bool2, int i3, Object obj) {
        if (obj != null) {
            com.exteragram.messenger.utils.ExteraCompat.unsupported("Super calls with default arguments not supported in this target, function: uButton");
            return null;
        }
        if ((i3 & 4) != 0) {
            i2 = 0;
        }
        if ((i3 & 8) != 0) {
            charSequence2 = null;
        }
        if ((i3 & 16) != 0) {
            charSequence3 = null;
        }
        if ((i3 & 32) != 0) {
            bool = null;
        }
        if ((i3 & 64) != 0) {
            z = true;
        }
        if ((i3 & 128) != 0) {
            str = null;
        }
        if ((i3 & 256) != 0) {
            z2 = false;
        }
        if ((i3 & 512) != 0) {
            z3 = false;
        }
        if ((i3 & 1024) != 0) {
            bool2 = null;
        }
        return basePreferencesActivity.uButton$TMessagesProj(i, charSequence, i2, charSequence2, charSequence3, bool, z, str, z2, z3, bool2);
    }

    public final UItem uButton$TMessagesProj(int id, CharSequence text, int icon, CharSequence value, CharSequence subtext, Boolean showDivider, boolean searchable, String linkAlias, boolean red, boolean accent, Boolean prioritizeTitleOverValue) {
        UItem uItemAsButton;
        if (subtext != null && subtext.length() != 0) {
            uItemAsButton = UItem.asButtonWithSubtext(id, icon, text, subtext, 64, 60);
        } else if (value != null && value.length() != 0 && icon != 0) {
            uItemAsButton = UItem.asButton(id, icon, text, value);
        } else if (value != null && value.length() != 0) {
            uItemAsButton = UItem.asButton(id, text, value);
        } else if (icon != 0) {
            uItemAsButton = UItem.asButton(id, icon, text);
        } else {
            uItemAsButton = UItem.asButton(id, text);
        }
        if (red) {
            uItemAsButton.red();
        }
        if (accent) {
            uItemAsButton.accent();
        }
        if (searchable) {
            uItemAsButton.setSearchable(this);
        }
        if (linkAlias != null) {
            uItemAsButton.setLinkAlias(linkAlias, this);
        }
        if (showDivider != null) {
            uItemAsButton.showDivider(showDivider.booleanValue());
        }
        if (prioritizeTitleOverValue != null) {
            uItemAsButton.prioritizeTitleOverValue(prioritizeTitleOverValue.booleanValue());
        }
        return uItemAsButton;
    }
}
