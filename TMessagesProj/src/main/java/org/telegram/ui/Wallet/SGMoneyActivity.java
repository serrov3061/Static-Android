package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Stars.StarGiftSheet;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stars.StarsIntroActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.TON.TONIntroActivity;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Деньги": Telegram 13.1.1's wallet screen (WalletActivity) for StaticGram. The earnings row,
 * the money card, one "Send" pill and the Transactions / Collectibles switch. StaticGram has two
 * cards, GRAM and Stars: they page left / right, and the balance, the button colour and the
 * list below follow the selected card.
 */
public class SGMoneyActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int TAB_TRANSACTIONS = 0, TAB_COLLECTIBLES = 1;
    private static final int[] KINDS = {TL_sgcard.KIND_GRAM, TL_sgcard.KIND_STARS};
    private static final int MENU_SCAN = 1, MENU_MORE = 2;

    private UniversalRecyclerView listView;
    private FrameLayout earnedContainer;
    private TextView earnedText;
    private SGMoneyCardsPager pager;
    private final SGCardView[] cards = new SGCardView[2];
    private LinearLayout buttonContainer;
    private ButtonWithCounterView sendButton;
    private FrameLayout tabsContainer;
    private SGSegmentedTabs tabs;
    /** A copy of the tabs pinned under the bar once the list row scrolls past it (13.1.1 history tabs stick). */
    private FrameLayout stickyTabsContainer;
    private SGSegmentedTabs stickyTabs;
    private ActionBarMenuItem moreItem;
    private int page;
    private float pagePosition;
    private int tab = TAB_TRANSACTIONS;
    /** «Коллекции»: only what is on chain, the NFTs held by the custodial wallet (wallet.getNfts). */
    private SGChainWallet chain;
    private final Runnable chainListener = () -> {
        if (listView != null && tab == TAB_COLLECTIBLES) listView.adapter.update(true);
    };
    private LinearLayout nftEmptyView;
    private TextView nftEmptyText;
    private ButtonWithCounterView nftEmptyButton;
    private final int[] buttonColors = new int[2];
    /** «Домены .ton» row under the send pill, with the primary domain when there is one. */
    private FrameLayout domainsContainer;
    private TextView domainsText;
    private final Runnable dnsListener = this::updateDomainsRow;

    // 13.1.1 WalletActivity header collapse: scrolling up, the centred card tilts back, shrinks and
    // slides under the bar while its balance flies into the action bar in place of the title.
    private FrameLayout actionBarBalance;
    private View actionBarBalanceAnchor;
    private FrameLayout balanceOverlay;
    private WalletCardComponent balanceCard; // the card whose balance is in the overlay now
    private boolean balanceInOverlay;
    private final Rect balanceCardBounds = new Rect();
    private final Matrix balanceFaceTransform = new Matrix();
    private final Matrix balanceDrawTransform = new Matrix();
    private final Matrix nextBalanceDrawTransform = new Matrix();
    private final float[] balanceLocalCorners = new float[8];
    private final float[] balanceProjectedCorners = new float[8];
    private final float[] balanceTransitionCorners = new float[8];
    private final int[] balanceOverlayLocation = new int[2];
    private final int[] balanceTargetLocation = new int[2];
    private float collapse;
    private boolean userScrolled;
    /** Direction of the last scroll step: > 0 — towards the list (collapsing), < 0 — back to the card. */
    private int lastScrollDirection;
    /** The overlay is taken down while the screen slides away or is paused. */
    private boolean balanceOverlaySuspended;

    public SGMoneyActivity() {
        this(-1);
    }

    /** Opens on the card of that kind (TL_sgcard.KIND_GRAM / KIND_STARS); -1 — the default one. */
    public SGMoneyActivity(int initialKind) {
        for (int i = 0; i < KINDS.length; i++) {
            if (KINDS[i] == initialKind) {
                page = i;
                pagePosition = i;
            }
        }
    }

    private long selfId() {
        return getUserConfig().getClientUserId();
    }

    private static int kindOf(int page) {
        return KINDS[Math.max(0, Math.min(1, page))];
    }

    @Override
    public boolean onFragmentCreate() {
        final NotificationCenter nc = getNotificationCenter();
        nc.addObserver(this, NotificationCenter.sgCardsUpdated);
        nc.addObserver(this, NotificationCenter.sgCollectiblesChanged);
        nc.addObserver(this, NotificationCenter.starBalanceUpdated);
        nc.addObserver(this, NotificationCenter.starTransactionsLoaded);
        nc.addObserver(this, NotificationCenter.starUserGiftsLoaded);
        for (boolean ton : new boolean[]{true, false}) {
            final StarsController c = StarsController.getInstance(currentAccount, ton);
            c.getBalance();
            c.invalidateTransactions(false);
            c.loadTransactions(StarsController.ALL_TRANSACTIONS);
        }
        chain = SGChainWallet.getInstance(currentAccount);
        chain.subscribe(chainListener);
        chain.invalidate();
        SGTonDns.getInstance(currentAccount).subscribe(dnsListener);
        SGTonDns.getInstance(currentAccount).loadMyDomains(null);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        final NotificationCenter nc = getNotificationCenter();
        nc.removeObserver(this, NotificationCenter.sgCardsUpdated);
        nc.removeObserver(this, NotificationCenter.sgCollectiblesChanged);
        nc.removeObserver(this, NotificationCenter.starBalanceUpdated);
        nc.removeObserver(this, NotificationCenter.starTransactionsLoaded);
        nc.removeObserver(this, NotificationCenter.starUserGiftsLoaded);
        if (chain != null) chain.unsubscribe(chainListener);
        SGTonDns.getInstance(currentAccount).unsubscribe(dnsListener);
        detachBalanceOverlay();
        for (SGCardView v : cards) if (v != null) v.dispose();
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray)) > .721f;
    }

    @Override
    public boolean canBeginSlide() {
        return (pager == null || !pager.isDragging()) && super.canBeginSlide();
    }

    @Override
    public View createView(Context context) {
        final int gray = getThemedColor(Theme.key_windowBackgroundGray);
        final int black = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SgMoneyTitle));
        actionBar.setBackgroundColor(gray);
        actionBar.setTitleColor(black);
        actionBar.setItemsColor(black, false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SCAN) {
                    openScanner();
                } else if (id == MENU_MORE) {
                    showMenu();
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SCAN, R.drawable.scan_qr).setContentDescription(getString(R.string.SgMoneyScanQr));
        moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other);
        moreItem.setContentDescription(getString(R.string.AccDescrMoreOptions));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(gray);
        fragmentView = root;
        createBalanceOverlay(context);

        // "You also have 💎 N in Gram Earnings ›" (13.1.1 createWalletNoticeRow)
        earnedContainer = new FrameLayout(context);
        earnedContainer.setPadding(dp(16), dp(4), dp(16), dp(4));
        final FrameLayout earnedRow = new FrameLayout(context);
        earnedRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        earnedRow.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_windowBackgroundWhite),
                Theme.blendOver(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)), 22, 22));
        earnedText = new TextView(context);
        earnedText.setTextColor(black);
        earnedText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        earnedText.setTypeface(AndroidUtilities.bold());
        earnedText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        earnedRow.addView(earnedText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 42, 0));
        final ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.attach_arrow_right);
        arrow.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.SRC_IN));
        earnedRow.addView(arrow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        earnedRow.setOnClickListener(v -> presentFragment(new TONIntroActivity()));
        ScaleStateListAnimator.apply(earnedRow, .02f, 1.2f);
        earnedContainer.addView(earnedRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // «Домены .ton ›» (same row style as the earnings notice)
        domainsContainer = new FrameLayout(context);
        domainsContainer.setPadding(dp(16), 0, dp(16), dp(10));
        final FrameLayout domainsRow = new FrameLayout(context);
        domainsRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        domainsRow.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_windowBackgroundWhite),
                Theme.blendOver(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)), 22, 22));
        domainsText = new TextView(context);
        domainsText.setTextColor(black);
        domainsText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        domainsText.setTypeface(AndroidUtilities.bold());
        domainsText.setSingleLine(true);
        domainsText.setEllipsize(TextUtils.TruncateAt.END);
        domainsText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        domainsRow.addView(domainsText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 42, 0));
        final ImageView domainsArrow = new ImageView(context);
        domainsArrow.setImageResource(R.drawable.attach_arrow_right);
        domainsArrow.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.SRC_IN));
        domainsRow.addView(domainsArrow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        domainsRow.setOnClickListener(v -> presentFragment(new SGDomainsActivity()));
        ScaleStateListAnimator.apply(domainsRow, .02f, 1.2f);
        domainsContainer.addView(domainsRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateDomainsRow();

        // The two cards
        for (int i = 0; i < 2; i++) {
            final SGCardView view = new SGCardView(context, currentAccount, false);
            final int index = i;
            view.card.setCardOnClickListener(v -> onCardTap(index));
            view.card.setOnStickerStyleChanged(this::updateButtonColors);
            cards[i] = view;
        }
        pager = new SGMoneyCardsPager(context, cards[0], cards[1]);
        pager.setListener(new SGMoneyCardsPager.Listener() {
            @Override
            public void onScroll(float position) {
                pagePosition = position;
                applyButtonColor();
            }

            @Override
            public void onPageSelected(int p) {
                page = p;
                updateCollapse();
                sendButton.setText(getString(kindOf(p) == TL_sgcard.KIND_STARS ? R.string.SgCardSendStarsButton : R.string.SgCardSendGramButton), true);
                if (listView != null) {
                    listView.adapter.update(true);
                    loadMore();
                }
            }
        });

        // One "Send" pill: 13.1.1 has "Add Funds" next to it, StaticGram tops up elsewhere.
        buttonContainer = new LinearLayout(context);
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setPadding(dp(16), dp(4), dp(16), dp(12));
        sendButton = new ButtonWithCounterView(context, getResourceProvider()).setRound();
        sendButton.setText(getString(R.string.SgCardSendGramButton), false);
        sendButton.setOnClickListener(v -> SGCards.pickRecipientAndSend(this, kindOf(page)));
        buttonContainer.addView(sendButton, LayoutHelper.createLinear(0, 48, 1f));
        if (page != 0) {
            // opened on a given card (sg://money/stars): start there without the page animation
            final int initial = page;
            page = 0;
            pager.setPage(initial, false);
        }

        // Transactions | Collectibles (13.1.1 history tabs, bubble style)
        tabsContainer = new FrameLayout(context);
        tabs = new SGSegmentedTabs(context, getString(R.string.SgMoneyTransactions), getString(R.string.SgMoneyCollectibles));
        tabs.setColors(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector),
                black, getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        tabs.setOnSelected(this::selectTab);
        tabsContainer.addView(tabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 16, 0, 16, 0));
        tabsContainer.setPadding(0, 0, 0, dp(6));

        // «В кошельке пока нет NFT»: a hint and a button to the profile gifts (or a retry after an error)
        nftEmptyView = new LinearLayout(context);
        nftEmptyView.setOrientation(LinearLayout.VERTICAL);
        nftEmptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        nftEmptyView.setPadding(dp(32), dp(28), dp(32), dp(20));
        // the 13.1 empty-state sticker
        final org.telegram.ui.Components.RLottieImageView nftEmptySticker = new org.telegram.ui.Components.RLottieImageView(context);
        nftEmptySticker.setAutoRepeat(false);
        nftEmptySticker.setAnimation(R.raw.utyan_empty, 100, 100);
        nftEmptySticker.setOnClickListener(v -> {
            nftEmptySticker.setProgress(0);
            nftEmptySticker.playAnimation();
        });
        nftEmptyView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                nftEmptySticker.setProgress(0);
                nftEmptySticker.playAnimation();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
        nftEmptyView.addView(nftEmptySticker, LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
        nftEmptyText = new TextView(context);
        nftEmptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        nftEmptyText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        nftEmptyText.setGravity(Gravity.CENTER);
        nftEmptyView.addView(nftEmptyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        nftEmptyButton = new ButtonWithCounterView(context, getResourceProvider()).setRound();
        nftEmptyView.addView(nftEmptyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        listView = new UniversalRecyclerView(context, currentAccount, classGuid, false, this::fillItems, this::onClick, null,
                getResourceProvider(), 3, LinearLayoutManager.VERTICAL);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        // Collectible tiles lie on the screen background (no section card): 12dp from the edges, 8dp apart.
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                if (!(view instanceof CollectibleCell || view instanceof WalletGiftCell)) return;
                if (!(view.getLayoutParams() instanceof androidx.recyclerview.widget.GridLayoutManager.LayoutParams)) return;
                final androidx.recyclerview.widget.GridLayoutManager.LayoutParams lp = (androidx.recyclerview.widget.GridLayoutManager.LayoutParams) view.getLayoutParams();
                if (lp.getSpanSize() != 1) return;
                final int columns = 3, column = lp.getSpanIndex();
                final float edge = dp(12), gap = dp(8);
                final float loss = (2 * edge + (columns - 1) * gap) / columns;
                final float left = edge + column * (gap - loss);
                outRect.left = Math.round(left);
                outRect.right = Math.round(loss - left);
                outRect.bottom = (int) gap;
            }
        });
        listView.setClipChildren(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(24));
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDurations(350);
        listView.setItemAnimator(itemAnimator);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolled = true;
                    if (balanceOverlaySuspended) {
                        balanceOverlaySuspended = false;
                        attachBalanceOverlay();
                    }
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && userScrolled) {
                    userScrolled = false;
                    snapHeader();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(1) || isLoadingVisible()) loadMore();
                if (dy != 0 && recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) lastScrollDirection = dy > 0 ? 1 : -1;
                updateCollapse();
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        stickyTabsContainer = new FrameLayout(context);
        stickyTabsContainer.setBackgroundColor(gray);
        stickyTabsContainer.setPadding(0, 0, 0, dp(6));
        stickyTabs = new SGSegmentedTabs(context, getString(R.string.SgMoneyTransactions), getString(R.string.SgMoneyCollectibles));
        stickyTabs.setColors(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector),
                black, getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        stickyTabs.setOnSelected(this::selectTab);
        stickyTabsContainer.addView(stickyTabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 16, 0, 16, 0));
        stickyTabsContainer.setVisibility(View.GONE);
        root.addView(stickyTabsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 46, Gravity.TOP));

        bindCards();
        updateEarned();
        listView.adapter.update(false);
        SGCardsController.getInstance(currentAccount).load(selfId(), true, r -> bindCards());
        SGCardsController.getInstance(currentAccount).loadGramBalance(b -> bindCards());
        actionBar.post(this::attachBalanceOverlay);
        listView.post(this::updateCollapse);
        return fragmentView;
    }

    // ===== header collapse (13.1.1 WalletActivity.updateBalancePosition) =====

    private void createBalanceOverlay(Context context) {
        actionBarBalance = new FrameLayout(context);
        actionBarBalance.setClipChildren(false);
        actionBarBalance.setClipToPadding(false);
        actionBar.addView(actionBarBalance, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 64, 0, 96, 0));
        actionBarBalanceAnchor = new View(context);
        actionBarBalance.addView(actionBarBalanceAnchor, LayoutHelper.createFrame(1, 1, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        balanceOverlay = new FrameLayout(context) {
            private final ViewTreeObserver.OnPreDrawListener updateProjection = () -> {
                // The card tilts with the gyroscope: keep the flying balance glued to its face.
                if (balanceInOverlay) updateBalanceOverlayTransform();
                return true;
            };

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                getViewTreeObserver().addOnPreDrawListener(updateProjection);
            }

            @Override
            protected void onDetachedFromWindow() {
                getViewTreeObserver().removeOnPreDrawListener(updateProjection);
                super.onDetachedFromWindow();
            }

            @Override
            protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (!balanceInOverlay || balanceCard == null || child != balanceCard.getBalanceLayout()) {
                    return super.drawChild(canvas, child, drawingTime);
                }
                final int save = canvas.save();
                canvas.concat(balanceDrawTransform);
                final boolean result = super.drawChild(canvas, child, drawingTime);
                canvas.restoreToCount(save);
                return result;
            }
        };
        balanceOverlay.setClipChildren(false);
        balanceOverlay.setClipToPadding(false);
    }

    /**
     * The flying balance is drawn in the navigation layout's overlay container: above the action
     * bar and the page, with nothing on the way to clip it (inside the action bar it was cut off
     * and the balance just vanished from the card). Taken down while the screen is paused or slides.
     */
    private void attachBalanceOverlay() {
        if (balanceOverlay == null || actionBar == null || balanceOverlaySuspended) return;
        ViewGroup host = getParentLayout() != null ? getParentLayout().getOverlayContainerView() : null;
        if (host == null && actionBar.getParent() instanceof ViewGroup) host = (ViewGroup) actionBar.getParent();
        if (host == null) return;
        if (balanceOverlay.getParent() != host) {
            AndroidUtilities.removeFromParent(balanceOverlay);
            host.addView(balanceOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        balanceOverlay.bringToFront();
        balanceOverlay.post(this::updateCollapse);
    }

    private void detachBalanceOverlay() {
        returnBalanceToCard();
        if (balanceOverlay != null) AndroidUtilities.removeFromParent(balanceOverlay);
    }

    @Override
    public void onPause() {
        super.onPause();
        balanceOverlaySuspended = true;
        detachBalanceOverlay();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        balanceOverlaySuspended = true;
        detachBalanceOverlay();
    }

    /** The list row holding the cards pager, or null while it is scrolled away. */
    private View pagerRow() {
        View v = pager;
        while (v != null && v.getParent() != listView) {
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return v;
    }

    private int pagerRowPosition() {
        return earnedContainer != null && earnedContainer.getTag() != null ? 1 : 0;
    }

    private void selectTab(int i) {
        tab = i;
        if (tabs.getSelected() != i) tabs.setSelected(i, true);
        if (stickyTabs.getSelected() != i) stickyTabs.setSelected(i, true);
        listView.adapter.update(true);
        loadMore();
    }

    /** The tabs row reached the top of the list: its pinned copy takes over until it comes back down. */
    private void updateStickyTabs() {
        if (stickyTabsContainer == null || tabsContainer == null) return;
        View row = tabsContainer;
        while (row != null && row.getParent() != listView) {
            row = row.getParent() instanceof View ? (View) row.getParent() : null;
        }
        final boolean stick = row != null ? row.getTop() < listView.getPaddingTop()
                : listView.layoutManager.findFirstVisibleItemPosition() > pagerRowPosition() + 3;
        stickyTabsContainer.setVisibility(stick ? View.VISIBLE : View.GONE);
    }

    private void updateCollapse() {
        if (listView == null || pager == null || actionBar == null) return;
        updateStickyTabs();
        final View row = pagerRow();
        float progress;
        if (row == null) {
            progress = listView.layoutManager.findFirstVisibleItemPosition() > pagerRowPosition() ? 1f : 0f;
        } else {
            final float scroll = listView.getPaddingTop() - row.getTop();
            progress = Utilities.clamp(scroll / (float) Math.max(1, row.getHeight()), 1f, 0f);
        }
        collapse = progress;

        // The card falls back into the screen, shrinks from its top and lags behind the list.
        final float tilt = Math.max(0, 90f * (float) Math.sqrt(progress) - 10 + 10 * progress);
        final float gyroscope = 1f - (float) Math.pow(progress, 0.33f);
        for (SGCardView v : cards) {
            if (v == null) continue;
            v.card.setAdditionalTilt(tilt);
            v.card.setUseGyroscope(gyroscope);
        }
        final float scale = AndroidUtilities.lerp(1f, .8f, progress);
        pager.setPivotX(pager.getWidth() * .5f);
        pager.setPivotY(0);
        pager.setScaleX(scale);
        pager.setScaleY(scale);
        pager.setTranslationY(row != null ? Math.max(0, row.getHeight() / 2f + dp(24)) * progress : 0);
        pager.setAlpha(1f - Utilities.clamp01(AndroidUtilities.ilerp(progress, .75f, 1f)));

        // The balance of the centred card flies into the bar.
        final WalletCardComponent card = cards[page] != null ? cards[page].card : null;
        if (balanceCard != null && balanceCard != card) returnBalanceToCard();
        if (card != null) {
            setBalanceInOverlay(card, progress > 0f && balanceOverlay.getParent() != null && !balanceOverlaySuspended);
            card.setBalanceCollapseColors(progress, getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            if (balanceInOverlay) updateBalanceOverlayTransform();
        }

        // The title is gone before the flying balance reaches it (no «Деньги» showing through it).
        final float titleAlpha = 1f - Utilities.clamp01(progress / .4f);
        final float titleTranslationY = -dp(8) * (1f - titleAlpha);
        if (actionBar.getTitleTextView() != null) {
            actionBar.getTitleTextView().setAlpha(titleAlpha);
            actionBar.getTitleTextView().setTranslationY(titleTranslationY);
        }
    }

    private void setBalanceInOverlay(WalletCardComponent card, boolean inOverlay) {
        if (balanceInOverlay == inOverlay) return;
        if (!inOverlay) {
            returnBalanceToCard();
            return;
        }
        final android.widget.LinearLayout layout = card.getBalanceLayout();
        if (layout.getWidth() == 0 || layout.getHeight() == 0) return;
        balanceCardBounds.set(layout.getLeft(), layout.getTop(), layout.getRight(), layout.getBottom());
        AndroidUtilities.removeFromParent(layout);
        balanceCard = card;
        balanceInOverlay = true;
        balanceOverlay.addView(layout, new FrameLayout.LayoutParams(balanceCardBounds.width(), balanceCardBounds.height(), Gravity.LEFT | Gravity.TOP));
        layout.layout(0, 0, balanceCardBounds.width(), balanceCardBounds.height());
        updateBalanceOverlayTransform();
        balanceOverlay.invalidate();
    }

    private void returnBalanceToCard() {
        if (balanceCard == null) {
            balanceInOverlay = false;
            return;
        }
        final WalletCardComponent card = balanceCard;
        balanceCard = null;
        balanceInOverlay = false;
        card.attachBalanceToCard();
        card.getBalanceLayout().layout(balanceCardBounds.left, balanceCardBounds.top, balanceCardBounds.right, balanceCardBounds.bottom);
        card.setBalanceCollapseColors(0, getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        balanceOverlay.invalidate();
    }

    /** 13.1.1 updateBalanceOverlayTransform: the balance goes from the (tilted) card face to the bar. */
    private void updateBalanceOverlayTransform() {
        if (balanceCard == null || balanceCardBounds.isEmpty() || !actionBarBalanceAnchor.isAttachedToWindow()) return;
        final boolean projected = balanceCard.getFrontFaceTransform(balanceOverlay, balanceFaceTransform);
        // Scrolled away, the card is detached: fully collapsed, the bar end alone is enough.
        if (!projected && collapse < 1f) return;
        final float width = balanceCardBounds.width();
        final float height = balanceCardBounds.height();
        for (int i = 0; i < 4; i++) {
            final float x = i == 1 || i == 2 ? width : 0f;
            final float y = i >= 2 ? height : 0f;
            balanceLocalCorners[i * 2] = x;
            balanceLocalCorners[i * 2 + 1] = y;
            balanceProjectedCorners[i * 2] = balanceCardBounds.left + x;
            balanceProjectedCorners[i * 2 + 1] = balanceCardBounds.top + y;
        }
        if (projected) balanceFaceTransform.mapPoints(balanceProjectedCorners);
        balanceOverlay.getLocationInWindow(balanceOverlayLocation);
        actionBarBalanceAnchor.getLocationInWindow(balanceTargetLocation);
        final float targetScale = 18f / 23f;
        balanceCard.setBalanceTransition(collapse, actionBarBalance.getWidth() / targetScale);
        final float targetLeft = balanceTargetLocation[0] - balanceOverlayLocation[0];
        final float targetTop = balanceTargetLocation[1] - balanceOverlayLocation[1] - height * targetScale * .5f;
        for (int i = 0; i < 4; i++) {
            balanceTransitionCorners[i * 2] = AndroidUtilities.lerp(balanceProjectedCorners[i * 2],
                    targetLeft + balanceLocalCorners[i * 2] * targetScale, collapse);
            balanceTransitionCorners[i * 2 + 1] = AndroidUtilities.lerp(balanceProjectedCorners[i * 2 + 1],
                    targetTop + balanceLocalCorners[i * 2 + 1] * targetScale, collapse);
        }
        if (nextBalanceDrawTransform.setPolyToPoly(balanceLocalCorners, 0, balanceTransitionCorners, 0, 4)
                && !balanceDrawTransform.equals(nextBalanceDrawTransform)) {
            balanceDrawTransform.set(nextBalanceDrawTransform);
            balanceOverlay.invalidate();
        }
        if (projected) balanceCard.setBalanceDiamondTransform(balanceFaceTransform, balanceDrawTransform);
        final float subtitleScale = AndroidUtilities.lerp(1f, (12f / 14f) / targetScale, collapse);
        final View usd = balanceCard.getUsdBalanceView();
        usd.setPivotX(0);
        usd.setPivotY(0);
        usd.setScaleX(subtitleScale);
        usd.setScaleY(subtitleScale);
    }

    /** Released half way, the header settles fully open or fully collapsed (13.1.1 WalletHeaderSnapHelper). */
    private void snapHeader() {
        if (listView == null || collapse <= 0f || collapse >= 1f) return;
        final View row = pagerRow();
        if (row == null) return;
        final int dy;
        // Finish the way the finger went (a short push up collapses, a short pull down expands);
        // only an undecided release goes to the nearer end.
        final boolean expand = lastScrollDirection != 0 ? lastScrollDirection < 0 : collapse < .5f;
        if (expand) {
            final View first = listView.layoutManager.findViewByPosition(0);
            dy = (first != null ? first.getTop() : row.getTop()) - listView.getPaddingTop();
        } else {
            dy = row.getBottom() - listView.getPaddingTop();
        }
        if (dy != 0) listView.smoothScrollBy(0, dy, CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (fragmentView == null) return;
        SGCardsController.getInstance(currentAccount).load(selfId());
        SGCardsController.getInstance(currentAccount).loadGramBalance(b -> bindCards());
        // NFTs minted / returned / sent while away show up without re-entering the app
        if (chain != null && !chain.isLoading()) chain.invalidate();
        StarsController.getInstance(currentAccount).invalidateProfileGifts(selfId());
        SGTonDns.getInstance(currentAccount).loadMyDomains(null);
        balanceOverlaySuspended = false;
        if (actionBar != null) actionBar.post(this::attachBalanceOverlay);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (listView == null) return;
        if (id == NotificationCenter.sgCollectiblesChanged) {
            listView.adapter.update(true);
            updateDomainsRow();
        } else if (id == NotificationCenter.sgCardsUpdated) {
            if (args.length > 0 && args[0] instanceof Long && (Long) args[0] == selfId()) bindCards();
        } else if (id == NotificationCenter.starBalanceUpdated) {
            bindCards();
            updateEarned();
        } else if (id == NotificationCenter.starTransactionsLoaded) {
            if (tab == TAB_TRANSACTIONS) listView.adapter.update(true);
        } else if (id == NotificationCenter.starUserGiftsLoaded) {
            // a gift moved between the profile and the wallet: the wallet list follows
            if (tab == TAB_COLLECTIBLES && chain != null && !chain.isLoading()) chain.invalidate();
        }
    }

    // ===== cards =====

    private TL_sgcard.TL_card cardData(int kind) {
        final TL_sgcard.TL_cards cached = SGCardsController.getInstance(currentAccount).getCached(selfId());
        TL_sgcard.TL_card c = cached != null ? cached.get(kind) : null;
        if (c == null) {
            c = new TL_sgcard.TL_card();
            c.kind = kind;
        }
        return c;
    }

    private long balance(int kind, TL_sgcard.TL_card c) {
        if (kind == TL_sgcard.KIND_GRAM) {
            final long g = SGCardsController.getInstance(currentAccount).getGramBalance();
            return g >= 0 ? g : c.balance;
        }
        final StarsController s = StarsController.getInstance(currentAccount);
        return s.balanceAvailable() ? s.getBalance().amount : c.balance;
    }

    private void bindCards() {
        if (pager == null) return;
        final TLRPC.User self = getUserConfig().getCurrentUser();
        for (int i = 0; i < 2; i++) {
            final int kind = kindOf(i);
            final SGCardView view = cards[i];
            final TL_sgcard.TL_card c = cardData(kind);
            final long b = balance(kind, c);
            if (view.getData() != c) {
                c.balance = b;
                view.bind(self, c);
            } else if (c.balance != b) {
                view.setBalance(b);
            }
        }
        updateButtonColors();
        if (listView != null) listView.post(this::updateCollapse);
    }

    /** Filled, high-contrast "Send" pill: GRAM uses the theme accent (as official 13.1.1), Stars are gold. */
    private void updateButtonColors() {
        for (int i = 0; i < 2; i++) {
            buttonColors[i] = kindOf(i) == TL_sgcard.KIND_STARS ? 0xFFF2A60D : getThemedColor(Theme.key_featuredStickers_addButton);
        }
        applyButtonColor();
    }

    private void applyButtonColor() {
        if (sendButton == null) return;
        final float t = Math.max(0, Math.min(1, pagePosition));
        final int color = androidx.core.graphics.ColorUtils.blendARGB(buttonColors[0], buttonColors[1], t);
        sendButton.setColor(color);
        final boolean starsNear = kindOf(t < .5f ? 0 : 1) == TL_sgcard.KIND_STARS;
        sendButton.setTextColor(starsNear ? 0xFF1A1200 : getThemedColor(Theme.key_featuredStickers_buttonText));
    }

    /**
     * A tap on the side card brings it to the middle; on the middle card the QR corner
     * (right part of the face) opens the QR, the rest of the face opens that card's editor.
     */
    private void onCardTap(int index) {
        if (pager.isDragging()) return;
        if (index != pager.getSelected()) {
            pager.setPage(index, true);
            return;
        }
        final View card = cards[index];
        final float left = card.getLeft() + card.getTranslationX();
        final float rel = card.getWidth() > 0 ? (pager.getLastDownX() - left) / card.getWidth() : 0;
        if (rel > .72f) openQr(kindOf(index));
        else openEditor(kindOf(index));
    }

    private void updateEarned() {
        if (earnedText == null) return;
        final StarsController ton = StarsController.getTonInstance(currentAccount);
        final long earned = ton.balanceAvailable() ? ton.getBalance().amount : 0;
        final boolean show = earned > 0;
        if (show) {
            final SpannableStringBuilder text = new SpannableStringBuilder();
            text.append('￼');
            final ColoredImageSpan icon = new ColoredImageSpan(R.drawable.mini_gram_16);
            icon.recolorDrawable = false;
            text.setSpan(icon, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(" ");
            final int amountStart = text.length();
            text.append(WalletCardComponent.formatGrams(earned));
            text.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_featuredStickers_addButton)), amountStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            earnedText.setText(LocaleController.formatSpannable(R.string.SgMoneyEarnedBalance, text));
        }
        final boolean was = earnedContainer.getTag() != null;
        earnedContainer.setTag(show ? 1 : null);
        if (was != show && listView != null) {
            // Shown while the page is at its top: the row goes above the card and scrolls with the
            // list, instead of being inserted off screen and popping in on the next scroll.
            final boolean atTop = !listView.canScrollVertically(-1);
            listView.adapter.update(!atTop);
            if (atTop) listView.scrollToPosition(0);
        }
    }

    /** «Домены .ton», or «Домены .ton · justi.ton» with the primary domain in the accent colour. */
    private void updateDomainsRow() {
        if (domainsText == null) return;
        final SpannableStringBuilder text = new SpannableStringBuilder(getString(R.string.SgDnsMoneyRow));
        final String primary = SGTonDns.getInstance(currentAccount).getPrimary();
        if (!TextUtils.isEmpty(primary)) {
            text.append("  ");
            final int start = text.length();
            text.append(primary);
            text.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_featuredStickers_addButton)), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        domainsText.setText(text);
    }

    // ===== list =====

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (earnedContainer == null) return;
        if (earnedContainer.getTag() != null) items.add(UItem.asCustomShadow(earnedContainer));
        items.add(UItem.asCustomShadow(pager, true));
        items.add(UItem.asCustomShadow(buttonContainer));
        items.add(UItem.asCustomShadow(domainsContainer));
        items.add(UItem.asCustomShadow(tabsContainer));
        final int kind = kindOf(page);
        if (tab == TAB_TRANSACTIONS) {
            final StarsController c = StarsController.getInstance(currentAccount, kind == TL_sgcard.KIND_GRAM);
            final ArrayList<TL_stars.StarsTransaction> list = c.transactions[StarsController.ALL_TRANSACTIONS];
            final boolean loading = !c.didFullyLoadTransactions(StarsController.ALL_TRANSACTIONS);
            if (!list.isEmpty() || loading) {
                adapter.whiteSectionStart();
                for (TL_stars.StarsTransaction t : list) {
                    items.add(StarsIntroActivity.StarsTransactionView.Factory.asTransaction(t, false));
                }
                if (loading) {
                    for (int i = 0; i < 3; i++) items.add(UItem.asFlicker(-100 - kind * 10 - i, FlickerLoadingView.DIALOG_CELL_TYPE));
                }
                adapter.whiteSectionEnd();
            } else {
                items.add(UItem.asCenterShadow(getString(R.string.SgMoneyNoTransactions)));
            }
        } else {
            int count = 0;
            if (chain != null) {
                for (org.telegram.tgnet.tl.TL_wallet.nftItem nft : chain.nfts) {
                    if (SGChainWallet.isGift(nft)) {
                        // the unique gift preview, as the 13.1.1 wallet collectibles grid
                        items.add(WalletGiftCell.Factory.of(nft, null));
                    } else {
                        // usernames / numbers: the server's media.png card
                        items.add(CollectibleCell.Factory.of(nft).setSpanCount(1));
                    }
                    count++;
                }
                if (chain.isLoading() || !chain.isLoaded() && chain.getError() == null || chain.isLoaded() && !chain.isEndReached() && chain.getError() == null) {
                    final int left = 3 - count % 3;
                    for (int i = 0; i < (left == 0 ? 3 : left); i++) {
                        items.add(UItem.asFlicker(-200 - i, FlickerLoadingView.STAR_GIFT).setSpanCount(1));
                    }
                    count++;
                }
            }
            if (count == 0 && nftEmptyView != null) {
                final boolean failed = chain != null && chain.getError() != null;
                nftEmptyText.setText(getString(failed ? R.string.SgNftLoadError : R.string.SgNftEmpty));
                nftEmptyButton.setText(getString(failed ? R.string.SgNftRetry : R.string.SgNftEmptyButton), false);
                nftEmptyButton.setOnClickListener(v -> {
                    if (failed) {
                        chain.invalidate();
                    } else {
                        openProfileGifts();
                    }
                });
                items.add(UItem.asCustomShadow(nftEmptyView));
            }
        }
    }

    private void openProfileGifts() {
        final android.os.Bundle args = new android.os.Bundle();
        args.putLong("user_id", selfId());
        args.putBoolean("my_profile", true);
        args.putBoolean("open_gifts", true);
        presentFragment(new org.telegram.ui.ProfileActivity(args));
    }

    private boolean isLoadingVisible() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            if (listView.getChildAt(i) instanceof FlickerLoadingView) return true;
        }
        return false;
    }

    private void loadMore() {
        if (tab == TAB_TRANSACTIONS) {
            StarsController.getInstance(currentAccount, kindOf(page) == TL_sgcard.KIND_GRAM).loadTransactions(StarsController.ALL_TRANSACTIONS);
        } else if (chain != null && !chain.isEndReached() && chain.getError() == null) {
            chain.load();
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof TL_stars.StarsTransaction && ((TL_stars.StarsTransaction) item.object).amount instanceof TL_stars.TL_starsTonAmount) {
            // StaticGram: GRAM rows open the upstream wallet receipt, not the Stars one
            final org.telegram.tgnet.tl.TL_wallet.walletTransaction transaction = SgGramReceipt.fromStarsTransaction((TL_stars.StarsTransaction) item.object);
            if (transaction != null) {
                WalletActivity.openTransactionSheet(getContext(), currentAccount, transaction, null, null, null, null, getResourceProvider());
            }
        } else if (item.object instanceof TL_stars.StarsTransaction
                && SgGramReceipt.fromStarsTransferTransaction((TL_stars.StarsTransaction) item.object) != null) {
            // StaticGram: Stars transfer rows open the 13.1 receipt in Stars mode, like the chat card
            WalletActivity.openStarsTransferSheet(getContext(), currentAccount,
                    SgGramReceipt.fromStarsTransferTransaction((TL_stars.StarsTransaction) item.object), null, getResourceProvider());
        } else if (item.object instanceof TL_stars.StarsTransaction) {
            StarsIntroActivity.showTransactionSheet(getContext(), false, 0, currentAccount, (TL_stars.StarsTransaction) item.object, getResourceProvider());
        } else if (item.object instanceof org.telegram.tgnet.tl.TL_wallet.nftItem) {
            final org.telegram.tgnet.tl.TL_wallet.nftItem nft = (org.telegram.tgnet.tl.TL_wallet.nftItem) item.object;
            if (SGChainWallet.isGift(nft)) {
                // the upstream gift sheet in wallet mode: «Передать», «Вернуть в профиль»
                if (!org.telegram.ui.Components.SheetOpenGuard.tryOpen()) return;
                final TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) nft.gift;
                final StarGiftSheet sheet = new StarGiftSheet(getContext(), currentAccount, 0, getResourceProvider())
                        .setWalletCollectible(nft);
                sheet.set(gift.slug, gift, null);
                sheet.show();
                org.telegram.ui.Components.SheetOpenGuard.track(sheet);
            } else {
                SGNftSheet.open(getContext(), currentAccount, nft, getResourceProvider());
            }
        }
    }

    // ===== actions =====

    private void showMenu() {
        final int kind = kindOf(page);
        ItemOptions.makeOptions(this, moreItem)
                .add(R.drawable.msg_edit, getString(R.string.SgMoneyEditCard), this::openEditor)
                .add(R.drawable.msg_share, getString(R.string.SgMoneyShareCard), () -> {
                    final android.graphics.Bitmap image = cards[page].renderImage();
                    SGCards.shareCard(this, getUserConfig().getCurrentUser(), kind, image);
                })
                .add(R.drawable.msg_qrcode, getString(R.string.SgMoneyShowQr), () -> openQr(kind))
                .add(R.drawable.settings_gram_24, getString(R.string.SgMoneyGramEarnings), () -> presentFragment(new TONIntroActivity()))
                .add(R.drawable.settings_stars, getString(R.string.TelegramStars), () -> presentFragment(new StarsIntroActivity()))
                .setDrawScrim(false)
                .translate(0, -dp(48))
                .show();
    }

    private void openEditor() {
        openEditor(kindOf(page));
    }

    private void openEditor(int kind) {
        if (SGCardsController.getInstance(currentAccount).isPurchased(selfId())) {
            presentFragment(new SGCardEditorActivity(kind));
        } else {
            presentFragment(new SGCardsActivity());
        }
    }

    /** The card's QR: the sg://card link that opens it with the send button for others. */
    private void openQr(int kind) {
        final TLRPC.User self = getUserConfig().getCurrentUser();
        if (self == null || getParentActivity() == null) return;
        showDialog(new QRCodeBottomSheet(getParentActivity(),
                getString(kind == TL_sgcard.KIND_STARS ? R.string.SgCardStarsTitle : R.string.SgCardGramTitle),
                SGCards.cardLink(self.id, kind),
                getString(kind == TL_sgcard.KIND_STARS ? R.string.SgMoneyQrHelpStars : R.string.SgMoneyQrHelpGram),
                true, getResourceProvider()));
    }

    private void openScanner() {
        if (getParentActivity() == null) return;
        if (Build.VERSION.SDK_INT >= 23 && getParentActivity().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.CAMERA}, 34);
            return;
        }
        CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                AndroidUtilities.runOnUIThread(() -> onScanned(text), 300);
            }
        });
    }

    private void onScanned(String text) {
        if (TextUtils.isEmpty(text)) return;
        final long[] card = SGCards.parseCardLink(text.trim());
        if (card != null) {
            SGCards.openFromLink(this, card[0], (int) card[1]);
        } else if (text.startsWith("http") || text.startsWith("tg:") || text.startsWith("sg:") || text.startsWith("ton:")) {
            Browser.openUrl(getParentActivity(), text.trim());
        } else {
            AndroidUtilities.addToClipboard(text);
            org.telegram.ui.Components.BulletinFactory.of(this).createCopyBulletin(getString(R.string.SgMoneyQrCopied)).show();
        }
    }
}
