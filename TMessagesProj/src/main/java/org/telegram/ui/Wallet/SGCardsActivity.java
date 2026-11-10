package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * "Карточки": the own GRAM and Stars cards with edit / transfer / share actions, or a promo
 * with the purchase link when the feature is not bought. Reachable from Settings and the
 * own profile.
 */
public class SGCardsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private FrameLayout root;
    private RadialProgressView progress;
    private ScrollView scroll;
    private LinearLayout content;
    private final ArrayList<SGCardView> cardViews = new ArrayList<>();
    private int shownState = -1; // 0 promo, 1 cards

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.sgCardsUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starBalanceUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.sgCardsUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starBalanceUpdated);
        for (SGCardView v : cardViews) v.dispose();
        super.onFragmentDestroy();
    }

    private long selfId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SgCardsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        progress = new RadialProgressView(context, getResourceProvider());
        root.addView(progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        scroll = new ScrollView(context);
        scroll.setClipChildren(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;

        final SGCardsController c = SGCardsController.getInstance(currentAccount);
        update(c.getCached(selfId()));
        c.load(selfId(), true, this::update);
        StarsController.getInstance(currentAccount).getBalance();
        return fragmentView;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.sgCardsUpdated) {
            if (args.length > 0 && args[0] instanceof Long && (Long) args[0] == selfId()) {
                update(SGCardsController.getInstance(currentAccount).getCached(selfId()));
            }
        } else if (id == NotificationCenter.starBalanceUpdated) {
            SGCardsController.getInstance(currentAccount).load(selfId(), true, null);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (shownState == 1) SGCardsController.getInstance(currentAccount).load(selfId());
    }

    private void update(TL_sgcard.TL_cards cards) {
        if (content == null) return;
        if (cards == null) {
            progress.setVisibility(View.VISIBLE);
            scroll.setVisibility(View.GONE);
            return;
        }
        progress.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        final boolean purchased = cards.purchased && !cards.cards.isEmpty();
        final TLRPC.User self = getUserConfig().getCurrentUser();
        if (purchased && shownState == 1 && cardViews.size() == 2) {
            for (SGCardView v : cardViews) {
                final TL_sgcard.TL_card c = cards.get(v.getKind());
                if (c != null) v.bind(self, c);
            }
            return;
        }
        for (SGCardView v : cardViews) v.dispose();
        cardViews.clear();
        content.removeAllViews();
        shownState = purchased ? 1 : 0;
        if (!purchased) {
            buildPromo(self);
            return;
        }
        final TextView note = text(getString(R.string.SgCardsPublicInfo), 14, Theme.key_windowBackgroundWhiteGrayText, false);
        note.setGravity(Gravity.CENTER);
        content.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 4, 12, 4));
        for (int kind : new int[]{TL_sgcard.KIND_GRAM, TL_sgcard.KIND_STARS}) {
            TL_sgcard.TL_card c = cards.get(kind);
            if (c == null) {
                c = new TL_sgcard.TL_card();
                c.kind = kind;
            }
            content.addView(text(getString(kind == TL_sgcard.KIND_GRAM ? R.string.SgCardGramTitle : R.string.SgCardStarsTitle), 15, Theme.key_windowBackgroundWhiteBlackText, true),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 16, 12, 0));
            final SGCardView view = new SGCardView(getContext(), currentAccount, false);
            view.bind(self, c);
            final int k = kind;
            view.card.setCardOnClickListener(v -> presentFragment(new SGCardEditorActivity(k)));
            cardViews.add(view);
            content.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final LinearLayout actions = new LinearLayout(getContext());
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(action(R.drawable.msg_edit, getString(R.string.SgCardEdit), v -> presentFragment(new SGCardEditorActivity(k))), LayoutHelper.createLinear(0, 64, 1f, 0, 0, 4, 0));
            actions.addView(action(R.drawable.msg_send, getString(k == TL_sgcard.KIND_GRAM ? R.string.SgCardSendGramButton : R.string.SgCardSendStarsButton), v -> SGCards.pickRecipientAndSend(this, k)), LayoutHelper.createLinear(0, 64, 1f, 4, 0, 4, 0));
            actions.addView(action(R.drawable.msg_share, getString(R.string.SgCardShare), v -> {
                final Bitmap image = view.renderImage();
                SGCards.shareCard(this, self, k, image);
            }), LayoutHelper.createLinear(0, 64, 1f, 4, 0, 0, 0));
            content.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, 12, 0, 12, 0));
        }
    }

    private void buildPromo(TLRPC.User self) {
        final SGCardView preview = new SGCardView(getContext(), currentAccount, false);
        final TL_sgcard.TL_card demo = new TL_sgcard.TL_card();
        demo.kind = TL_sgcard.KIND_GRAM;
        preview.bind(self, demo);
        cardViews.add(preview);
        content.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        final TextView title = text(getString(R.string.SgCardsPromoTitle), 22, Theme.key_windowBackgroundWhiteBlackText, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 8));
        final TextView text = text(getString(R.string.SgCardsPromoText), 15, Theme.key_windowBackgroundWhiteGrayText, false);
        text.setGravity(Gravity.CENTER);
        content.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 20));

        final ButtonWithCounterView buy = new ButtonWithCounterView(getContext(), getResourceProvider()).setRound();
        buy.setText(getString(R.string.SgCardsPromoButton), false);
        buy.setOnClickListener(v -> SGCards.openBot(this));
        content.addView(buy, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 0));
    }

    private TextView text(CharSequence s, int size, int colorKey, boolean bold) {
        final TextView tv = new TextView(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        tv.setTextColor(getThemedColor(colorKey));
        if (bold) tv.setTypeface(AndroidUtilities.bold());
        tv.setText(s);
        return tv;
    }

    private View action(int icon, CharSequence label, View.OnClickListener click) {
        final LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_windowBackgroundWhite)));
        final ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setColorFilter(new android.graphics.PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_addButton), android.graphics.PorterDuff.Mode.SRC_IN));
        l.addView(iv, LayoutHelper.createLinear(24, 24, Gravity.CENTER_HORIZONTAL));
        final TextView tv = text(label, 12, Theme.key_featuredStickers_addButton, true);
        tv.setSingleLine();
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        l.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 0));
        l.setOnClickListener(click);
        ScaleStateListAnimator.apply(l, .04f, 1.2f);
        return l;
    }
}
