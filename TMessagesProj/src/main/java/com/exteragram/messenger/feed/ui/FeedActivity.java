package com.exteragram.messenger.feed.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.utils.ui.MainTabsUiHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatActivityContainer;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.ChatSearchTabs;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.MainTabsActivityController;

import java.util.List;

/**
 * exteraGram channel feed screen: an embedded ChatActivity in MODE_SEARCH / SEARCH_FEED that shows the
 * merged posts of the subscribed channels (FeedController). Opened from the main menu / drawer item FEED
 * or as a bottom tab (ExteraConfig.showFeedTab, in place of Contacts).
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int MENU_SETTINGS = 75;
    private static final int MENU_MARK_ALL_READ = 76;

    private ChatActivityContainer chatContainer;
    private boolean embeddedChatCreated;
    private boolean hasMainTabs;
    private int lastConfigGeneration;
    private WindowInsetsCompat lastWindowInsets;
    private MainTabsActivityController mainTabsActivityController;
    private boolean resumedOnce;
    private boolean uiActiveHeld;
    private boolean uiResumedHeld;
    private boolean viewportFullyVisible;
    private int lastSubtitleCount = -1;
    // exteraGram 13.1.1: category tabs
    private FeedCategoryTabsView categoryTabs;
    private ValueAnimator categoryTransitionAnimator;
    private int lastCategoriesVersion;
    private int lastSelectedCategoryId;
    private int pendingCategoryId = -1;

    private final Runnable loadNewPosts = () -> {
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null && uiResumedHeld) {
            chatActivity.loadNewerFeed(true);
        }
    };
    private final Runnable reapplySubtitle = () -> setFeedSubtitle(lastSubtitleCount);

    public FeedActivity() {
        this(null);
    }

    public FeedActivity(Bundle args) {
        super(args);
    }

    public static void presentFeed(BaseFragment fragment) {
        LaunchActivity launchActivity = LaunchActivity.instance;
        if (!AndroidUtilities.isTablet() || launchActivity == null || launchActivity.getRightActionBarLayout() == null) {
            if (fragment != null) {
                fragment.presentFragment(new FeedActivity());
            }
            return;
        }
        INavigationLayout layout = launchActivity.getRightActionBarLayout();
        if (layout.getLastFragment() instanceof FeedActivity) {
            return;
        }
        if (!layout.getFragmentStack().isEmpty()) {
            while (layout.getFragmentStack().size() - 1 > 0) {
                layout.removeFragmentFromStack(layout.getFragmentStack().get(0));
            }
            layout.closeLastFragment(false);
        }
        layout.presentFragment(new INavigationLayout.NavigationParams(new FeedActivity()).setNoAnimation(true));
    }

    public void setMainTabsActivityController(MainTabsActivityController controller) {
        mainTabsActivityController = controller;
    }

    private ChatActivity getChatActivity() {
        return chatContainer != null ? chatContainer.chatActivity : null;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        viewportFullyVisible = !hasMainTabs;
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, FeedController.feedNeedReload);
        lastConfigGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        lastSelectedCategoryId = FeedConfig.getInstance(currentAccount).getSelectedCategoryId();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(loadNewPosts);
        AndroidUtilities.cancelRunOnUIThread(reapplySubtitle);
        destroyEmbeddedChat();
        if (uiResumedHeld) {
            uiResumedHeld = false;
            FeedController.getInstance(currentAccount).setUiResumed(false);
        }
        if (uiActiveHeld) {
            uiActiveHeld = false;
            FeedController.getInstance(currentAccount).setUiActive(false);
        }
        Bulletin.removeDelegate(this);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, FeedController.feedNeedReload);
        super.onFragmentDestroy();
    }

    private void destroyEmbeddedChat() {
        cancelCategoryTransition();
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null) {
            if (!hasMainTabs && embeddedChatCreated) {
                chatActivity.saveFeedScrollPosition();
            }
            chatActivity.setFeedChannelsChangedCallback(null);
            if (embeddedChatCreated) {
                chatActivity.onFragmentDestroy();
            }
        }
        embeddedChatCreated = false;
        chatContainer = null;
        categoryTabs = null;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity == null || chatActivity.getActionBar() == null || !chatActivity.getActionBar().isActionModeShowed()) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            chatActivity.clearSelectionMode();
        }
        return false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            if ((Boolean) args[2] || chatContainer == null || !FeedController.getInstance(currentAccount).isIncludedChannelPost((Long) args[0])) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(loadNewPosts);
            AndroidUtilities.runOnUIThread(loadNewPosts, 1000L);
        } else if (id == FeedController.feedNeedReload) {
            ChatActivity chatActivity = getChatActivity();
            if (chatActivity != null) {
                chatActivity.onFeedChannelsChanged(args.length > 0 && Boolean.TRUE.equals(args[0]));
            }
            updateFeedSubtitle();
        } else if (id == NotificationCenter.updateInterfaces) {
            // StaticGram: ChatAvatarContainer.updateSubtitle hides the subtitle outside MODE_DEFAULT, show it again
            AndroidUtilities.cancelRunOnUIThread(reapplySubtitle);
            AndroidUtilities.runOnUIThread(reapplySubtitle);
        }
    }

    @Override
    public View createView(Context context) {
        destroyEmbeddedChat();
        lastWindowInsets = null;
        actionBar.setAddToContainer(false);
        actionBar.setVisibility(View.GONE);
        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
                lastWindowInsets = insets;
                return addTabsBottomInset(insets, AndroidUtilities.dp(MainTabsUiHelper.getTabsViewHeightDp()));
            });
            frameLayout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    if (lastWindowInsets != null) {
                        ViewCompat.dispatchApplyWindowInsets(v, lastWindowInsets);
                    } else {
                        v.requestApplyInsets();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
        }
        Bundle args = new Bundle();
        args.putInt("chatMode", ChatActivity.MODE_SEARCH);
        args.putInt("searchType", FeedController.SEARCH_FEED);
        args.putBoolean("hasMainTabs", hasMainTabs);
        chatContainer = new ChatActivityContainer(context, getParentLayout(), args) {
            private boolean activityCreated;

            @Override
            protected void initChatActivity() {
                if (activityCreated) {
                    return;
                }
                activityCreated = true;
                embeddedChatCreated = true;
                super.initChatActivity();
                updateFeedViewportActive(viewportFullyVisible);
                setupChatActionBar();
                setupChatTitle();
                setupCategoryTabs();
                if (lastWindowInsets != null && fragmentView != null) {
                    ViewCompat.dispatchApplyWindowInsets(fragmentView, lastWindowInsets);
                }
            }
        };
        ChatActivity chatActivity = chatContainer.chatActivity;
        chatActivity.isInsideContainer = false;
        chatActivity.setFeedChannelsChangedCallback(this::updateFeedSubtitle);
        chatActivity.setFeedViewportActive(viewportFullyVisible);
        if (!uiResumedHeld) {
            chatContainer.onPause();
        }
        frameLayout.addView(chatContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (!uiActiveHeld) {
            uiActiveHeld = true;
            FeedController.getInstance(currentAccount).setUiActive(true);
        }
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getTopOffset(int tag) {
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }

            @Override
            public int getBottomOffset(int tag) {
                return hasMainTabs ? AndroidUtilities.dp(MainTabsUiHelper.getTabsViewHeightDp()) : 0;
            }
        });
        return fragmentView;
    }

    private static WindowInsetsCompat addTabsBottomInset(WindowInsetsCompat insets, int extra) {
        int systemBars = WindowInsetsCompat.Type.systemBars();
        int navigationBars = WindowInsetsCompat.Type.navigationBars();
        return new WindowInsetsCompat.Builder(insets)
            .setInsets(systemBars, addBottom(insets.getInsets(systemBars), extra))
            .setInsets(navigationBars, addBottom(insets.getInsets(navigationBars), extra))
            .setInsetsIgnoringVisibility(systemBars, addBottom(insets.getInsetsIgnoringVisibility(systemBars), extra))
            .setInsetsIgnoringVisibility(navigationBars, addBottom(insets.getInsetsIgnoringVisibility(navigationBars), extra))
            .build();
    }

    @Override
    public void updateMainTabsVisibility() { // exteraGram 13.1.1
        if (mainTabsActivityController != null) {
            mainTabsActivityController.setTabsVisible(com.exteragram.messenger.config.BottomNavigationBar.visible());
        }
    }

    private static Insets addBottom(Insets insets, int extra) {
        return Insets.of(insets.left, insets.top, insets.right, insets.bottom + extra);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateMainTabsVisibility();
        if (chatContainer != null) {
            chatContainer.onResume();
            updateFeedViewportActive(viewportFullyVisible);
        }
        if (!uiResumedHeld) {
            uiResumedHeld = true;
            FeedController.getInstance(currentAccount).setUiResumed(true);
        }
        if (hasMainTabs && fragmentView != null && lastWindowInsets != null) {
            ViewCompat.dispatchApplyWindowInsets(fragmentView, lastWindowInsets);
        }
        boolean categorySwitched = applyCategoryChanges();
        int generation = FeedConfig.getInstance(currentAccount).getGeneration();
        ChatActivity chatActivity = getChatActivity();
        if (categorySwitched) {
            lastConfigGeneration = generation;
        } else if (generation != lastConfigGeneration) {
            lastConfigGeneration = generation;
            if (chatActivity != null) {
                chatActivity.applyFeedConfigChange();
            }
        } else if (resumedOnce && chatActivity != null) {
            chatActivity.reconcileFeedList();
            chatActivity.refreshFeedUnreadDivider();
            if (!FeedController.getInstance(currentAccount).getMessages().isEmpty()) {
                chatActivity.loadNewerFeed(true);
            }
        }
        resumedOnce = true;
        updateFeedSubtitle();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        viewportFullyVisible = true;
        updateFeedViewportActive(true);
    }

    @Override
    public void onBecomeFullyHidden() {
        viewportFullyVisible = false;
        updateFeedViewportActive(false);
        super.onBecomeFullyHidden();
    }

    @Override
    public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        if (hasMainTabs) {
            viewportFullyVisible = false;
            updateFeedViewportActive(false);
        }
        super.onTransitionAnimationStart(isOpen, backward);
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (hasMainTabs) {
            viewportFullyVisible = isOpen;
            updateFeedViewportActive(isOpen);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (chatContainer != null) {
            updateFeedViewportActive(false);
            chatContainer.onPause();
        }
        if (uiResumedHeld) {
            uiResumedHeld = false;
            FeedController.getInstance(currentAccount).setUiResumed(false);
        }
    }

    private void updateFeedViewportActive(boolean active) {
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null) {
            chatActivity.setFeedViewportActive(active);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (categoryTabs != null && categoryTabs.isTouchInside(ev)) {
            return false;
        }
        ChatActivity chatActivity = getChatActivity();
        return chatActivity == null || chatActivity.getActionBar() == null || !chatActivity.getActionBar().isActionModeShowed();
    }

    @Override
    public void onParentScrollToTop() {
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null) {
            chatActivity.onPageDownClicked();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null) {
            return chatActivity.isLightStatusBar();
        }
        return !Theme.isCurrentThemeDark();
    }

    private void setupChatActionBar() {
        ChatActivity chatActivity = getChatActivity();
        ActionBar chatActionBar = chatActivity != null ? chatActivity.getActionBar() : null;
        if (chatActionBar == null) {
            return;
        }
        ActionBarMenu menu = chatActionBar.createMenu();
        if (menu.getItem(MENU_MARK_ALL_READ) == null) {
            menu.addItem(MENU_MARK_ALL_READ, R.drawable.msg_markread, chatActivity.themeDelegate).setContentDescription(LocaleController.getString(R.string.FeedMarkAllRead));
        }
        if (menu.getItem(MENU_SETTINGS) == null) {
            menu.addItem(MENU_SETTINGS, R.drawable.msg_settings, chatActivity.themeDelegate).setContentDescription(LocaleController.getString(R.string.FeedSettings));
        }
        ActionBar.ActionBarMenuOnItemClick original = chatActionBar.getActionBarMenuOnItemClick();
        chatActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && hasMainTabs && !chatActionBar.isActionModeShowed()) {
                    return;
                }
                if (id == -1 && !chatActionBar.isActionModeShowed()) {
                    finishFragment();
                    return;
                }
                if (id == MENU_MARK_ALL_READ) {
                    showMarkAllReadDialog();
                    return;
                }
                if (id == MENU_SETTINGS) {
                    presentFragment(new FeedChannelsActivity());
                    return;
                }
                if (original != null) {
                    original.onItemClick(id);
                }
            }

            @Override
            public boolean canOpenMenu() {
                return original == null || original.canOpenMenu();
            }
        });
    }

    // region categories (exteraGram 13.1.1)

    private void setupCategoryTabs() {
        ChatActivity chatActivity = getChatActivity();
        ChatSearchTabs tabs = chatActivity != null ? chatActivity.hashtagSearchTabs : null;
        if (tabs == null) {
            return;
        }
        categoryTabs = new FeedCategoryTabsView(getContext(), this, currentAccount, chatActivity.getResourceProvider(), new FeedCategoryTabsView.Delegate() {
            @Override
            public void onCategorySelected(int categoryId) {
                switchCategoryAnimated(categoryId);
            }

            @Override
            public void onCreateCategory() {
                presentFragment(new FeedCategoryEditActivity(0, true));
            }

            @Override
            public void onEditCategory(FeedConfig.Category category) {
                presentFragment(new FeedCategoryEditActivity(category.getId(), false));
            }

            @Override
            public void onHideCategory(FeedConfig.Category category) {
                hideCategory(category);
            }

            @Override
            public void onDeleteCategory(FeedConfig.Category category) {
                FeedCategoryEditActivity.showDeleteDialog(FeedActivity.this, category, FeedActivity.this::onCategoryRemoved);
            }
        });
        categoryTabs.attachTo(tabs);
        lastCategoriesVersion = FeedConfig.getInstance(currentAccount).getCategoriesVersion();
        tabs.show(categoryTabs.hasCategories(), false);
    }

    /** Syncs the tabs with the config; returns true when the selected category changed (the feed was reloaded). */
    private boolean applyCategoryChanges() {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        if (categoryTabs != null && lastCategoriesVersion != feedConfig.getCategoriesVersion()) {
            lastCategoriesVersion = feedConfig.getCategoriesVersion();
            categoryTabs.update();
            ChatActivity chatActivity = getChatActivity();
            ChatSearchTabs tabs = chatActivity != null ? chatActivity.hashtagSearchTabs : null;
            if (tabs != null && tabs.isShown() != categoryTabs.hasCategories()) {
                tabs.show(categoryTabs.hasCategories());
            }
        }
        if (feedConfig.getSelectedCategoryId() == lastSelectedCategoryId) {
            return false;
        }
        onCategorySwitched();
        return true;
    }

    private void onCategorySwitched() {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        lastSelectedCategoryId = feedConfig.getSelectedCategoryId();
        lastConfigGeneration = feedConfig.getGeneration();
        if (categoryTabs != null) {
            lastCategoriesVersion = feedConfig.getCategoriesVersion();
            categoryTabs.update();
        }
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null) {
            if (chatActivity.getActionBar() != null && chatActivity.getActionBar().isActionModeShowed()) {
                chatActivity.clearSelectionMode();
            }
            // StaticGram: one timeline (exteraGram keeps a cached timeline per category), so the feed reloads
            chatActivity.reloadFeed();
        }
        updateFeedSubtitle();
    }

    private void hideCategory(FeedConfig.Category category) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        boolean wasSelected = feedConfig.getSelectedCategoryId() == category.getId();
        if (!feedConfig.setCategoryHidden(category.getId(), true)) {
            return;
        }
        FeedController.getInstance(currentAccount).notifyCategoriesChanged();
        onCategoryRemoved();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.caption_hide, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedCategoryHiddenInfo, category.getTitle())), LocaleController.getString(R.string.Undo), () -> {
            if (feedConfig.setCategoryHidden(category.getId(), false)) {
                FeedController.getInstance(currentAccount).notifyCategoriesChanged();
                applyCategoryChanges();
                if (wasSelected) {
                    switchCategoryAnimated(category.getId());
                }
            }
        }).show();
    }

    private void onCategoryRemoved() {
        int selected = FeedConfig.getInstance(currentAccount).getSelectedCategoryId();
        if (selected != lastSelectedCategoryId) {
            switchCategoryAnimated(selected);
        } else {
            applyCategoryChanges();
        }
    }

    private void switchCategoryAnimated(int categoryId) {
        if (categoryTabs != null) {
            categoryTabs.setSelectedCategory(categoryId);
        }
        View listView = getFeedListView();
        if (listView == null || !viewportFullyVisible) {
            pendingCategoryId = -1;
            FeedController.getInstance(currentAccount).selectCategory(categoryId);
            applyCategoryChanges();
            return;
        }
        boolean waiting = pendingCategoryId != -1;
        pendingCategoryId = categoryId;
        if (waiting) {
            return;
        }
        if (categoryTransitionAnimator != null) {
            categoryTransitionAnimator.cancel();
        }
        float fromAlpha = listView.getAlpha();
        float fromX = listView.getTranslationX();
        float toX = -getTransitionDirection(lastSelectedCategoryId, categoryId) * AndroidUtilities.dp(24);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            setFeedContentTransform(listView, AndroidUtilities.lerp(fromAlpha, 0f, t), AndroidUtilities.lerp(fromX, toX, t));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (categoryTransitionAnimator == animation) {
                    categoryTransitionAnimator = null;
                    applyPendingCategory();
                }
            }
        });
        animator.setDuration((long) (fromAlpha * 120));
        animator.setInterpolator(CubicBezierInterpolator.EASE_IN);
        categoryTransitionAnimator = animator;
        animator.start();
    }

    private void applyPendingCategory() {
        int categoryId = pendingCategoryId;
        int previous = lastSelectedCategoryId;
        pendingCategoryId = -1;
        if (categoryId != -1) {
            FeedController.getInstance(currentAccount).selectCategory(categoryId);
        }
        if (!applyCategoryChanges() && categoryTabs != null) {
            categoryTabs.update();
        }
        View listView = getFeedListView();
        if (listView == null) {
            return;
        }
        float fromX = lastSelectedCategoryId == previous ? listView.getTranslationX() : getTransitionDirection(previous, lastSelectedCategoryId) * AndroidUtilities.dp(24);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            setFeedContentTransform(listView, t, fromX * (1f - t));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (categoryTransitionAnimator == animation) {
                    categoryTransitionAnimator = null;
                }
            }
        });
        animator.setDuration(280);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        categoryTransitionAnimator = animator;
        animator.start();
    }

    private void cancelCategoryTransition() {
        pendingCategoryId = -1;
        ValueAnimator animator = categoryTransitionAnimator;
        if (animator != null) {
            categoryTransitionAnimator = null;
            animator.cancel();
        }
        View listView = getFeedListView();
        if (listView != null) {
            setFeedContentTransform(listView, 1f, 0f);
        }
    }

    private void setFeedContentTransform(View listView, float alpha, float translationX) {
        listView.setAlpha(alpha);
        listView.setTranslationX(translationX);
        ChatActivity chatActivity = getChatActivity();
        View emptyView = chatActivity != null ? chatActivity.getFeedEmptyView() : null;
        if (emptyView != null) {
            emptyView.setAlpha(alpha);
            emptyView.setTranslationX(translationX);
        }
    }

    /** The feed posts are shown in the search results list of the embedded ChatActivity. */
    private View getFeedListView() {
        ChatActivity chatActivity = getChatActivity();
        return chatActivity != null ? chatActivity.getFeedListView() : null;
    }

    private int getTransitionDirection(int fromId, int toId) {
        return getCategoryOrder(toId) >= getCategoryOrder(fromId) ? 1 : -1;
    }

    private int getCategoryOrder(int categoryId) {
        List<FeedConfig.Category> categories = FeedConfig.getInstance(currentAccount).getCategories();
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).getId() == categoryId) {
                return i + 1;
            }
        }
        return 0;
    }

    // endregion

    private void showMarkAllReadDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.FeedMarkAllRead));
        builder.setMessage(LocaleController.getString(R.string.FeedMarkAllReadConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.MarkAsRead), (dialog, which) -> {
            markAllRead();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.FeedMarkAllReadDone)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    public void markAllRead() {
        ChatActivity chatActivity = getChatActivity();
        if (chatActivity != null) {
            chatActivity.markFeedAsRead();
        } else {
            FeedController.getInstance(currentAccount).markAllRead();
        }
    }

    private void setupChatTitle() {
        ChatActivity chatActivity = getChatActivity();
        ChatAvatarContainer avatarContainer = chatActivity != null ? chatActivity.avatarContainer : null;
        if (avatarContainer == null) {
            return;
        }
        avatarContainer.setTitle(LocaleController.getString(R.string.Feed));
        if (hasMainTabs && avatarContainer.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            // the feed tab has no back button, so do not keep the space reserved for it
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) avatarContainer.getLayoutParams();
            lp.leftMargin = AndroidUtilities.dp(8);
            avatarContainer.setLayoutParams(lp);
        }
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(getUserConfig().getClientUserId());
        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
        avatarDrawable.setCustomIcon(getContext() != null ? getContext().getResources().getDrawable(R.drawable.ic_feed).mutate() : null);
        avatarContainer.getAvatarImageView().setImage(null, null, avatarDrawable, null);
        updateFeedSubtitle();
    }

    private void updateFeedSubtitle() {
        FeedController controller = FeedController.getInstance(currentAccount);
        setFeedSubtitle(controller.getIncludedChannelCount());
        controller.loadChannels((channels, includedCount, failed, configGeneration) -> setFeedSubtitle(failed ? controller.getIncludedChannelCount() : includedCount));
    }

    private void setFeedSubtitle(int count) {
        lastSubtitleCount = count;
        ChatActivity chatActivity = getChatActivity();
        ChatAvatarContainer avatarContainer = chatActivity != null ? chatActivity.avatarContainer : null;
        if (avatarContainer == null) {
            return;
        }
        avatarContainer.setSubtitle(count < 0 ? LocaleController.getString(R.string.Loading) : LocaleController.formatPluralString("Channels", count));
        View subtitle = avatarContainer.getSubtitleTextView();
        if (subtitle != null) {
            subtitle.setVisibility(View.VISIBLE);
        }
    }
}
