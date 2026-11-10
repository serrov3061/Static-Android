package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Editor of the own GRAM or Stars card: the background is any own unique gift, stickers are
 * own unique gifts and static stickers (recent, favourite, installed packs; no TGS / video).
 * Stickers are moved, scaled and rotated right on the card (13.1.1 WalletGiftStickers look),
 * up to {@link TL_sgcard#MAX_STICKERS}. Saved with sgcard.saveCard.
 */
public class SGCardEditorActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int TAB_BACKGROUND = 0, TAB_GIFTS = 1, TAB_STICKERS = 2;
    private static final int DONE = 1;
    private static final int VIEW_GIFT = 0, VIEW_STICKER = 1, VIEW_NONE = 2, VIEW_EMPTY = 3, VIEW_HEADER = 4;

    private final int kind;
    private TL_sgcard.TL_card initial;
    private SGCardView cardView;
    private ViewPagerFixed pager;
    private final RecyclerListView[] lists = new RecyclerListView[3];
    private final Adapter[] adapters = new Adapter[3];
    /** Gifts shown by GiftSheet cells as bare saved gifts (no sender), like 13.1.1 WalletGiftCell. */
    private final HashMap<String, TL_stars.SavedStarGift> savedGifts = new HashMap<>();
    private LinearLayout selectionRow;
    private TextView hintView;
    private ButtonWithCounterView saveButton;

    private final ArrayList<TL_stars.StarGift> gifts = new ArrayList<>();
    private boolean giftsLoading, giftsEnd;
    private String giftsOffset;
    private final ArrayList<TLRPC.Document> stickers = new ArrayList<>();
    /** The stickers tab grid: a String is a section header (favourites, recents, a pack), a Document a sticker. */
    private final ArrayList<Object> stickerItems = new ArrayList<>();
    private boolean dirty, saving;

    public SGCardEditorActivity(int kind) {
        this.kind = kind;
    }

    @Override
    public boolean onFragmentCreate() {
        final TL_sgcard.TL_cards c = SGCardsController.getInstance(currentAccount).getCached(getUserConfig().getClientUserId());
        initial = c == null ? null : c.get(kind);
        if (initial == null) {
            initial = new TL_sgcard.TL_card();
            initial.kind = kind;
        }
        loadGifts();
        // The whole sticker library, like the sticker picker: favourites, recents, every installed
        // pack and custom emoji pack. Requested once here; the replies refill the grid below.
        final MediaDataController mdc = MediaDataController.getInstance(currentAccount);
        mdc.checkStickers(MediaDataController.TYPE_IMAGE);
        mdc.checkStickers(MediaDataController.TYPE_EMOJIPACKS);
        mdc.loadRecents(MediaDataController.TYPE_FAVE, false, true, false);
        mdc.loadRecents(MediaDataController.TYPE_IMAGE, false, true, false);
        loadStickers();
        getNotificationCenter().addObserver(this, NotificationCenter.stickersDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.recentDocumentsDidLoad);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.stickersDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.recentDocumentsDidLoad);
        if (cardView != null) cardView.dispose();
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.stickersDidLoad || id == NotificationCenter.recentDocumentsDidLoad) {
            // Sticker packs and recents arrive after the editor opens: refill the stickers grid.
            final ArrayList<Object> before = new ArrayList<>(stickerItems);
            loadStickers();
            if (!before.equals(stickerItems)) notifyAdapter();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(kind == TL_sgcard.KIND_STARS ? R.string.SgCardEditStars : R.string.SgCardEditGram));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) finishFragment();
                } else if (id == DONE) {
                    save();
                }
            }
        });
        actionBar.createMenu().addItem(DONE, R.drawable.ic_ab_done);

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setClipChildren(false);
        root.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final FrameLayout cardContainer = new FrameLayout(context);
        cardContainer.setClipChildren(false);
        cardView = new SGCardView(context, currentAccount, true);
        cardView.bind(getUserConfig().getCurrentUser(), copy(initial));
        cardView.card.setUseGyroscope(.25f);
        cardView.stickers.setListener(new SGCardStickers.Listener() {
            @Override
            public void onChanged() {
                dirty = true;
                refreshCells();
                updateSelectionRow();
            }

            @Override
            public void onSelectionChanged(boolean selected) {
                updateSelectionRow();
            }
        });
        cardView.stickers.setCellSource(new SGCardStickers.CellSource() {
            @Override
            public View cellFor(TL_stars.StarGift gift, TLRPC.Document document) {
                return findCell(gift, document);
            }

            @Override
            public float artShare(View cell) {
                final View image = cell instanceof GiftCellView ? ((GiftCellView) cell).imageView : cell instanceof StickerCell ? ((StickerCell) cell).image : null;
                if (image == null || cell.getWidth() <= 0 || image.getWidth() <= 0) return .77f;
                return image.getWidth() / (float) cell.getWidth()
                        * WalletStickerOutline.SIZE / (WalletStickerOutline.SIZE - 2f * WalletStickerOutline.PADDING);
            }

            @Override
            public ImageReceiver imageOf(View cell) {
                if (cell instanceof GiftCellView) return ((GiftCellView) cell).imageView.getImageReceiver();
                if (cell instanceof StickerCell) return ((StickerCell) cell).image.getImageReceiver();
                return null;
            }

            @Override
            public void onCellsChanged() {
                refreshCells();
            }
        });
        cardContainer.addView(cardView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 4, 4, 4, 0));
        layout.addView(cardContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // The hint gets its own row below the card, sized to its text: a fixed 44dp row made a
        // long (translated) hint run over the card's balance and holder lines. It never overlaps
        // the card face, and the card keeps a gap for its tilt and landing squash.
        final FrameLayout under = new FrameLayout(context);
        under.setMinimumHeight(dp(40));
        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hintView.setGravity(Gravity.CENTER);
        hintView.setLineSpacing(dp(1), 1f);
        hintView.setPadding(0, dp(2), 0, dp(4));
        hintView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        hintView.setText(formatString(R.string.SgCardEditorHint, TL_sgcard.MAX_STICKERS));
        under.addView(hintView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));
        selectionRow = new LinearLayout(context);
        selectionRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionRow.setGravity(Gravity.CENTER);
        selectionRow.addView(actionChip(context, getString(R.string.SgCardStickerToFront), v -> cardView.stickers.bringSelectedToFront()), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        selectionRow.addView(actionChip(context, getString(R.string.SgCardStickerRemove), v -> cardView.stickers.removeSelected()), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        selectionRow.setVisibility(View.GONE);
        under.addView(selectionRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.CENTER));
        layout.addView(under, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        // 13.1.1 WalletActivity history: bubble tabs over a ViewPagerFixed of 3-column grids.
        for (int i = 0; i < 3; i++) createPage(context, i);
        pager = new ViewPagerFixed(context, getResourceProvider()) {
            @Override
            protected void onTabPageSelected(int position) {
                if (position != TAB_STICKERS) pager.post(SGCardEditorActivity.this::loadGifts);
            }

            @Override
            protected int tabMarginDp() {
                return 12;
            }
        };
        pager.setAdapter(new ViewPagerFixed.Adapter() {
            @Override
            public int getItemCount() {
                return 3;
            }

            @Override
            public CharSequence getItemTitle(int position) {
                return getString(position == TAB_BACKGROUND ? R.string.SgCardTabBackground : position == TAB_GIFTS ? R.string.SgCardTabGifts : R.string.SgCardTabStickers);
            }

            @Override
            public int getItemViewType(int position) {
                return position;
            }

            @Override
            public View createView(int viewType) {
                final View page = lists[viewType];
                if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
                return page;
            }

            @Override
            public void bindView(View view, int position, int viewType) {
            }
        });
        final ViewPagerFixed.TabsView tabs = pager.createTabsView(true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE);
        tabs.setColors(Theme.key_windowBackgroundWhiteGrayText2,
                Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText2,
                Theme.key_listSelector, Theme.key_windowBackgroundWhite);
        tabs.setBackground(Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhite)));
        layout.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 0, 16, 4));
        layout.addView(pager, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        saveButton = new ButtonWithCounterView(context, getResourceProvider()).setRound();
        saveButton.setText(getString(R.string.SgCardSave), false);
        saveButton.setOnClickListener(v -> save());
        layout.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 0, 12, 8));

        // Stickers fly between the grid and the card above everything (13.1.1 board overlay).
        root.addView(cardView.stickers.createOverlay(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return fragmentView;
    }

    private void createPage(Context context, int page) {
        final RecyclerListView list = new RecyclerListView(context);
        final GridLayoutManager lm = new GridLayoutManager(context, 3);
        final Adapter adapter = new Adapter(page);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final int type = adapter.getItemViewType(position);
                return type == VIEW_EMPTY || type == VIEW_HEADER ? 3 : 1;
            }
        });
        list.setLayoutManager(lm);
        list.setAdapter(adapter);
        list.setPadding(dp(9), dp(4), dp(9), dp(12));
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setSelectorType(9);
        list.setSelectorDrawableColor(0);
        list.setItemAnimator(null);
        list.setOnItemClickListener((view, position) -> onItemClick(page, view, position));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (page != TAB_STICKERS && lm.findLastVisibleItemPosition() > adapter.getItemCount() - 8) {
                    AndroidUtilities.runOnUIThread(SGCardEditorActivity.this::loadGifts);
                }
            }
        });
        lists[page] = list;
        adapters[page] = adapter;
    }

    private int currentTab() {
        return pager == null ? TAB_BACKGROUND : pager.getCurrentPosition();
    }

    /** The visible cell of a gift or sticker on the current page (where it flies from / back to). */
    private View findCell(TL_stars.StarGift gift, TLRPC.Document document) {
        final RecyclerListView list = lists[currentTab()];
        if (list == null) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            final View child = list.getChildAt(i);
            if (gift != null && child instanceof GiftCellView) {
                final TL_stars.StarGift g = ((GiftCellView) child).gift;
                if (g != null && g.slug != null && g.slug.equals(gift.slug)) return child;
            } else if (document != null && child instanceof StickerCell) {
                final TLRPC.Document d = ((StickerCell) child).document;
                if (d != null && d.id == document.id) return child;
            }
        }
        return null;
    }

    /** Updates the "on card" / selected state of the visible cells in place, animated. */
    private void refreshCells() {
        for (int page = 0; page < 3; page++) {
            final RecyclerListView list = lists[page];
            if (list == null) continue;
            for (int i = 0; i < list.getChildCount(); i++) {
                final View child = list.getChildAt(i);
                if (child instanceof GiftCellView) ((GiftCellView) child).updateState(true);
                else if (child instanceof StickerCell) ((StickerCell) child).updateState(true);
                else if (child instanceof NoneCell) ((NoneCell) child).updateState(true);
            }
        }
    }

    private View actionChip(Context context, CharSequence text, View.OnClickListener click) {
        final TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        chip.setBackground(Theme.createRoundRectDrawable(dp(17), Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .12f)));
        chip.setText(text);
        chip.setOnClickListener(click);
        ScaleStateListAnimator.apply(chip, .05f, 1.2f);
        return chip;
    }

    private void updateSelectionRow() {
        final boolean sel = cardView != null && cardView.stickers.hasSelection();
        selectionRow.setVisibility(sel ? View.VISIBLE : View.GONE);
        // INVISIBLE keeps the row height, so the card and the picker don't jump on selection.
        hintView.setVisibility(sel ? View.INVISIBLE : View.VISIBLE);
    }

    /** notifyDataSetChanged is illegal while a list computes a layout or scrolls
     *  (sticker callbacks and network replies can land then): defer to the next frame. */
    private void notifyAdapter() {
        for (int i = 0; i < 3; i++) {
            final RecyclerListView list = lists[i];
            if (list == null || adapters[i] == null) continue;
            if (list.isComputingLayout()) {
                list.post(this::notifyAdapter);
                return;
            }
        }
        for (Adapter a : adapters) if (a != null) a.notifyDataSetChanged();
    }

    private static TL_sgcard.TL_card copy(TL_sgcard.TL_card c) {
        final TL_sgcard.TL_card r = new TL_sgcard.TL_card();
        r.kind = c.kind;
        r.background = c.background;
        r.balance = c.balance;
        r.stickers = new ArrayList<>(c.stickers);
        return r;
    }

    // ---------------------------------------------------------------- data

    private void loadGifts() {
        if (giftsLoading || giftsEnd) return;
        giftsLoading = true;
        final TL_stars.getSavedStarGifts req = new TL_stars.getSavedStarGifts();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.exclude_unlimited = true;
        req.exclude_unupgradable = true;
        req.exclude_upgradable = true;
        req.offset = giftsOffset == null ? "" : giftsOffset;
        req.limit = 60;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            giftsLoading = false;
            if (res == null) {
                giftsEnd = true;
                return;
            }
            MessagesController.getInstance(currentAccount).putUsers(res.users, false);
            MessagesController.getInstance(currentAccount).putChats(res.chats, false);
            final HashSet<String> seen = new HashSet<>();
            for (TL_stars.StarGift g : gifts) seen.add(g.slug);
            for (TL_stars.SavedStarGift sg : res.gifts) {
                if (sg.gift instanceof TL_stars.TL_starGiftUnique && sg.gift.slug != null && !sg.gift.burned && seen.add(sg.gift.slug)) {
                    gifts.add(sg.gift);
                }
            }
            giftsOffset = res.next_offset;
            giftsEnd = res.next_offset == null || res.next_offset.isEmpty() || res.gifts.isEmpty();
            notifyAdapter();
        });
    }

    private void loadStickers() {
        final MediaDataController mdc = MediaDataController.getInstance(currentAccount);
        final HashSet<Long> seen = new HashSet<>();
        stickers.clear();
        stickerItems.clear();
        // Favourites and recents may repeat pack stickers; the packs themselves are shown whole.
        addStickers(getString(R.string.FavoriteStickers), mdc.getRecentStickers(MediaDataController.TYPE_FAVE), seen);
        addStickers(getString(R.string.RecentStickers), mdc.getRecentStickers(MediaDataController.TYPE_IMAGE), seen);
        addSets(mdc.getStickerSets(MediaDataController.TYPE_IMAGE));
        addSets(mdc.getStickerSets(MediaDataController.TYPE_EMOJIPACKS));
    }

    private void addSets(ArrayList<TLRPC.TL_messages_stickerSet> sets) {
        if (sets == null) return;
        for (TLRPC.TL_messages_stickerSet set : sets) {
            if (set == null || set.set == null || set.set.archived) continue;
            addStickers(set.set.title, set.documents, new HashSet<>());
        }
    }

    /** One section: the header and the static stickers of the list (the server takes only those). */
    private void addStickers(String title, ArrayList<TLRPC.Document> list, HashSet<Long> seen) {
        if (list == null) return;
        boolean header = false;
        for (TLRPC.Document d : list) {
            if (!SGCardStickers.isStaticSticker(d) || !seen.add(d.id)) continue;
            if (!header) {
                stickerItems.add(title == null ? "" : title);
                header = true;
            }
            stickerItems.add(d);
            stickers.add(d);
        }
    }

    // ---------------------------------------------------------------- actions

    private void onItemClick(int page, View view, int position) {
        final Object item = adapters[page].getItem(position);
        final SGCardStickers s = cardView.stickers;
        if (page == TAB_BACKGROUND) {
            if (view instanceof NoneCell) {
                s.setBackgroundGift(null, true);
            } else if (item instanceof TL_stars.StarGift) {
                final TL_stars.StarGift g = (TL_stars.StarGift) item;
                final TL_stars.StarGift cur = s.getBackgroundGift();
                s.setBackgroundGift(cur != null && g.slug.equals(cur.slug) ? null : g, true);
            }
            dirty = true;
            refreshCells();
            return;
        }
        if (item instanceof TL_stars.StarGift) {
            final TL_stars.StarGift g = (TL_stars.StarGift) item;
            if (s.contains(g, null)) s.remove(g, null);
            else if (!s.add(g, null)) tooMany(view);
        } else if (item instanceof TLRPC.Document) {
            final TLRPC.Document d = (TLRPC.Document) item;
            if (s.contains(null, d)) s.remove(null, d);
            else if (!s.add(null, d)) tooMany(view);
        }
        refreshCells();
    }

    private void tooMany(View cell) {
        // 13.1.1 toss() with no room: the cell wiggles.
        if (cell != null) {
            cell.animate().rotation(3).setDuration(90).withEndAction(() -> cell.animate().rotation(0).setDuration(120).start()).start();
        }
        tooMany();
    }

    private void tooMany() {
        BulletinFactory.of(this).createErrorBulletin(formatString(R.string.SgCardErrorTooMany, TL_sgcard.MAX_STICKERS)).show();
    }

    private void save() {
        if (saving) return;
        saving = true;
        saveButton.setLoading(true);
        final TL_stars.StarGift bg = cardView.stickers.getBackgroundGift();
        SGCardsController.getInstance(currentAccount).save(kind, bg == null ? null : bg.slug, cardView.stickers.toInput(), (res, err) -> {
            saving = false;
            saveButton.setLoading(false);
            if (res != null) {
                dirty = false;
                finishFragment();
                return;
            }
            if (err != null && "WALLET_CARD_NOT_PURCHASED".equals(err.text)) {
                BulletinFactory.of(this).createErrorBulletin(SGCardsController.errorText(err)).show();
                return;
            }
            BulletinFactory.of(this).createErrorBulletin(SGCardsController.errorText(err)).show();
        });
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (dirty && !saving) {
            if (invoked) {
                new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle(getString(R.string.SgCardUnsavedTitle))
                        .setMessage(getString(R.string.SgCardUnsavedText))
                        .setPositiveButton(getString(R.string.SgCardSave), (d, w) -> save())
                        .setNegativeButton(getString(R.string.SgCardDiscard), (d, w) -> {
                            dirty = false;
                            finishFragment();
                        })
                        .show();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ---------------------------------------------------------------- grid

    private class Adapter extends RecyclerListView.SelectionAdapter {
        final int page;

        Adapter(int page) {
            this.page = page;
        }

        Object getItem(int position) {
            if (page == TAB_STICKERS) return position < stickerItems.size() ? stickerItems.get(position) : null;
            if (page == TAB_BACKGROUND) {
                if (position == 0) return null;
                position--;
            }
            return position < gifts.size() ? gifts.get(position) : null;
        }

        @Override
        public int getItemCount() {
            if (page == TAB_STICKERS) return Math.max(1, stickerItems.size());
            if (page == TAB_BACKGROUND) return 1 + Math.max(gifts.isEmpty() ? 1 : 0, gifts.size());
            return Math.max(1, gifts.size());
        }

        @Override
        public int getItemViewType(int position) {
            if (page == TAB_STICKERS) {
                if (stickerItems.isEmpty()) return VIEW_EMPTY;
                return stickerItems.get(position) instanceof String ? VIEW_HEADER : VIEW_STICKER;
            }
            if (page == TAB_BACKGROUND) {
                if (position == 0) return VIEW_NONE;
                return gifts.isEmpty() ? VIEW_EMPTY : VIEW_GIFT;
            }
            return gifts.isEmpty() ? VIEW_EMPTY : VIEW_GIFT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_EMPTY && holder.getItemViewType() != VIEW_HEADER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == VIEW_EMPTY) {
                final TextView empty = new TextView(parent.getContext());
                empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(24), dp(24), dp(24), dp(24));
                empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                view = empty;
            } else if (viewType == VIEW_HEADER) {
                final TextView header = new TextView(parent.getContext());
                header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                header.setTypeface(AndroidUtilities.bold());
                header.setSingleLine(true);
                header.setEllipsize(android.text.TextUtils.TruncateAt.END);
                header.setPadding(dp(8), dp(14), dp(8), dp(6));
                header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                view = header;
            } else if (viewType == VIEW_STICKER) {
                view = new StickerCell(parent.getContext());
            } else if (viewType == VIEW_NONE) {
                view = new NoneCell(parent.getContext());
            } else {
                view = new GiftCellView(parent.getContext(), page);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final int type = holder.getItemViewType();
            if (type == VIEW_EMPTY) {
                final TextView empty = (TextView) holder.itemView;
                if (page == TAB_STICKERS) empty.setText(getString(R.string.SgCardNoStaticStickers));
                else empty.setText(getString(giftsLoading ? R.string.Loading : R.string.SgCardNoGifts));
                return;
            }
            final Object item = getItem(position);
            if (type == VIEW_HEADER) {
                ((TextView) holder.itemView).setText(item instanceof String ? (String) item : "");
                return;
            }
            if (type == VIEW_NONE) {
                ((NoneCell) holder.itemView).updateState(false);
            } else if (type == VIEW_STICKER && item instanceof TLRPC.Document) {
                ((StickerCell) holder.itemView).set((TLRPC.Document) item);
            } else if (type == VIEW_GIFT && item instanceof TL_stars.StarGift) {
                ((GiftCellView) holder.itemView).set((TL_stars.StarGift) item);
            }
        }
    }

    private static boolean sameGift(TL_stars.StarGift a, TL_stars.StarGift b) {
        return a != null && b != null && a.slug != null && a.slug.equals(b.slug);
    }

    /**
     * 13.1.1 WalletGiftCell: the press bounce is drawn by the cell rather than set as scaleX/Y,
     * because the board owns the view's scale while a gift flies home into it.
     */
    private static final class DrawnScale {
        final View view;
        float scale = 1;
        boolean wasPressed;
        ValueAnimator animator;

        DrawnScale(View view) {
            this.view = view;
        }

        void pressed(boolean pressed) {
            if (pressed == wasPressed) return;
            wasPressed = pressed;
            animate(pressed ? .96f : 1, pressed ? 80 : 350, pressed ? null : new OvershootInterpolator(1.5f));
        }

        void animate(float to, long duration, TimeInterpolator interpolator) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(scale, to);
            animator.addUpdateListener(a -> {
                scale = (float) a.getAnimatedValue();
                view.invalidate();
            });
            if (interpolator != null) animator.setInterpolator(interpolator);
            animator.setDuration(duration);
            animator.start();
        }

        void reset() {
            if (animator != null) animator.cancel();
            scale = 1;
        }
    }

    /** A unique gift in the grid: 13.1.1 WalletGiftCell (GiftSheet.GiftCell with the wallet sticker state). */
    @SuppressLint("ViewConstructor")
    private class GiftCellView extends GiftSheet.GiftCell {
        /** A square tile, like every cell of this grid. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int size = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }

        final int page;
        TL_stars.StarGift gift;
        private final DrawnScale drawn = new DrawnScale(this);

        GiftCellView(Context context, int page) {
            super(context, currentAccount, getResourceProvider());
            this.page = page;
            setStateListAnimator(null);
            setWillNotDraw(false);
            // The selected background gift gets an accent ring (white is lost on light cells).
            cardBackground.selectedColorKey = Theme.key_featuredStickers_addButton;
        }

        void set(TL_stars.StarGift gift) {
            final boolean same = sameGift(this.gift, gift);
            this.gift = gift;
            if (!same) {
                animate().cancel();
                setScaleX(1);
                setScaleY(1);
                setRotation(0);
                drawn.reset();
            }
            TL_stars.SavedStarGift saved = savedGifts.get(gift.slug);
            if (saved == null) {
                saved = new TL_stars.SavedStarGift();
                saved.gift = gift;
                savedGifts.put(gift.slug, saved);
            }
            resetWalletStickerState();
            setStarsGift(saved, true, false);
            updateState(false);
        }

        void updateState(boolean animated) {
            if (gift == null || cardView == null) return;
            final SGCardStickers s = cardView.stickers;
            if (page == TAB_BACKGROUND) {
                setSelected(sameGift(s.getBackgroundGift(), gift), animated);
            } else {
                final boolean out = s.isOut(gift, null);
                setWalletStickerState(out ? 1 : 0, out, animated);
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (drawn != null) drawn.pressed(isPressed());
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (drawn == null || drawn.scale == 1) {
                super.draw(canvas);
                return;
            }
            canvas.save();
            canvas.scale(drawn.scale, drawn.scale, getWidth() / 2f, getHeight() / 2f);
            super.draw(canvas);
            canvas.restore();
        }
    }

    /** "No background" in the background grid, a plain gift-sized card. */
    @SuppressLint("ViewConstructor")
    private class NoneCell extends FrameLayout {
        /** A square tile, like every cell of this grid. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int size = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }

        private final GiftSheet.CardBackground background;
        private final DrawnScale drawn = new DrawnScale(this);

        NoneCell(Context context) {
            super(context);
            setWillNotDraw(false);
            final FrameLayout card = new FrameLayout(context);
            card.setBackground(background = new GiftSheet.CardBackground(card, getResourceProvider(), true));
            background.selectedColorKey = Theme.key_featuredStickers_addButton;
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTypeface(AndroidUtilities.bold());
            label.setGravity(Gravity.CENTER);
            label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            label.setText(getString(R.string.SgCardNoBackground));
            card.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 104, Gravity.CENTER));
        }

        void updateState(boolean animated) {
            background.setSelected(cardView != null && cardView.stickers.getBackgroundGift() == null, animated);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (drawn != null) drawn.pressed(isPressed());
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.save();
            if (drawn != null) canvas.scale(drawn.scale, drawn.scale, getWidth() / 2f, getHeight() / 2f);
            super.draw(canvas);
            canvas.restore();
        }
    }

    /** A static sticker in the grid, on a gift-sized card; a silhouette with an "on card" ribbon while on the card. */
    @SuppressLint("ViewConstructor")
    private class StickerCell extends FrameLayout {
        /** A square tile, like every cell of this grid. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int size = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        }

        final BackupImageView image;
        private final GiftSheet.Ribbon ribbon;
        private final DrawnScale drawn = new DrawnScale(this);
        TLRPC.Document document;
        private int state = -1;

        StickerCell(Context context) {
            super(context);
            setWillNotDraw(false);
            // StaticGram: the sticker lies on the screen background, no card plate under it
            final FrameLayout card = new FrameLayout(context);
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            image = new BackupImageView(context);
            image.getImageReceiver().setAutoRepeat(0);
            card.addView(image, LayoutHelper.createFrame(80, 80, Gravity.CENTER, 0, 12, 0, 12));
            ribbon = new GiftSheet.Ribbon(context);
            ribbon.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            ribbon.setText(getString(R.string.SgCardGiftOnCard), true);
            addView(ribbon, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP, 0, 2, 1, 0));
        }

        void set(TLRPC.Document document) {
            if (this.document == null || document.id != this.document.id) {
                animate().cancel();
                setScaleX(1);
                setScaleY(1);
                setRotation(0);
                drawn.reset();
                state = -1;
            }
            this.document = document;
            image.setImage(ImageLocation.getForDocument(document), "80_80", "webp", null, document);
            updateState(false);
        }

        void updateState(boolean animated) {
            if (document == null || cardView == null) return;
            final int out = cardView.stickers.isOut(null, document) ? 1 : 0;
            if (state == out) return;
            state = out;
            // The same silhouette as GiftCell.setWalletStickerState.
            final int color = 0xFF555555;
            final float k = 1 - out;
            image.setColorFilter(out == 0 ? null : new ColorMatrixColorFilter(new float[]{
                    k, 0, 0, 0, Color.red(color) * out,
                    0, k, 0, 0, Color.green(color) * out,
                    0, 0, k, 0, Color.blue(color) * out,
                    0, 0, 0, 1 - out * (1 - 110f / 255f), 0
            }));
            image.invalidate();
            ribbon.animate().cancel();
            if (animated) {
                ribbon.setPivotX(dp(48));
                ribbon.setPivotY(0);
                ribbon.animate().alpha(out).scaleX(.6f + .4f * out).scaleY(.6f + .4f * out).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            } else {
                ribbon.setAlpha(out);
                ribbon.setScaleX(.6f + .4f * out);
                ribbon.setScaleY(.6f + .4f * out);
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (drawn != null) drawn.pressed(isPressed());
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.save();
            if (drawn != null) canvas.scale(drawn.scale, drawn.scale, getWidth() / 2f, getHeight() / 2f);
            super.draw(canvas);
            canvas.restore();
        }
    }
}
