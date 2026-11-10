package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_tondns;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * StaticGram «Домены .ton» (from «Деньги»), a Fragment-like search screen in Telegram 13.1 style:
 * the live domain card on top (it follows what is typed), the big rounded «имя [.ton]» field, a
 * state chip that crossfades between «Проверяем…», «Доступен · 💎 40», «Занят», «Уже ваш» and
 * «Недопустимое имя» (live tondns.checkDomain), the «Купить за 💎 N» button, the confirmation
 * sheet with the card, price table and fireworks on success, and «Мои домены» with card
 * thumbnails, status and the primary badge; rows animate in and out (LayoutTransition).
 */
public class SGDomainsActivity extends BaseFragment {

    private static final long CHECK_DELAY_MS = 300;
    private static final int STATE_NONE = 0, STATE_CHECKING = 1, STATE_AVAILABLE = 2, STATE_TAKEN = 3, STATE_YOURS = 4, STATE_ERROR = 5, STATE_FOR_SALE = 6;

    private SGTonDns dns;
    private EditTextBoldCursor searchEdit;
    private ImageView clearButton;
    private SGDomainCardDrawable heroCard;
    private View heroView;
    private AnimatedTextView stateChip;
    private GradientDrawable stateChipBackground;
    private ValueAnimator stateColorAnimator;
    private int stateColor;
    private int shownState = -1;
    private ButtonWithCounterView buyButton;
    private boolean buyShown = true;
    private LinearLayout myList;
    private LinearLayout myEmptyView;
    private TextView myEmptyTitle;
    private TextView myEmptyText;
    private RLottieImageView myEmptySticker;
    private final HashMap<String, View> myRows = new HashMap<>();
    private final HashMap<String, String> myRowKeys = new HashMap<>();

    private int checkReqId = -1;
    private String checkingName;
    private TL_tondns.TL_domainCheck lastCheck;
    /** The listing of the searched name when it is taken and for sale (checkDomainEx). */
    private TL_tondns.TL_listing lastListing;

    // «Продаются»: the showcase (tondns.getListings)
    private LinearLayout listingsList;
    private TextView listingsEmpty, listingsMore;
    private final TextView[] sortChips = new TextView[4];
    private static final String[] SORTS = {"date", "price_asc", "price_desc", "name"};
    private String listingsSort = "date";
    private final ArrayList<TL_tondns.TL_listing> listings = new ArrayList<>();
    private int listingsCount;
    private boolean listingsLoading;
    private String myListingsKey = "";
    private TLRPC.TL_error lastError;
    private final Runnable checkRunnable = this::runCheck;
    private final Runnable listener = () -> {
        bindMyDomains();
        // the own listings changed (listed, re-priced, removed): the showcase follows
        final StringBuilder key = new StringBuilder();
        for (TL_tondns.TL_domain d : dns.myDomains) {
            final TL_tondns.TL_listing l = dns.getMyListing(d.name);
            if (l != null) key.append(l.name).append(':').append(l.price).append(';');
        }
        if (!key.toString().equals(myListingsKey)) {
            myListingsKey = key.toString();
            if (listingsList != null) loadListings(true);
        }
    };

    @Override
    public boolean onFragmentCreate() {
        dns = SGTonDns.getInstance(currentAccount);
        dns.subscribe(listener);
        dns.loadMyDomains(err -> dns.pollMints());
        dns.loadMyListings(null);
        SGCardsController.getInstance(currentAccount).loadGramBalance(null);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (dns != null) {
            dns.unsubscribe(listener);
            dns.cancel(checkReqId);
            dns.stopPolling();
        }
        if (stateColorAnimator != null) stateColorAnimator.cancel();
        AndroidUtilities.cancelRunOnUIThread(checkRunnable);
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray)) > .721f;
    }

    /**
     * «G 40»: the GRAM sign and the amount. The sign is the one of «Деньги» and the send screen
     * (wallet_gram_small, not the TON diamond), recoloured with the text, so in a button it takes
     * the button's text colour.
     */
    static CharSequence gramAmount(long nano, int iconRes) {
        return gramAmount(nano, null, true);
    }

    /**
     * «G 40» with the GRAM custom emoji (SGGramIconSpan; wallet_gram_small until it loads), animated
     * in host; tint paints it in the text colour (the button text colour in «Купить за»).
     */
    static CharSequence gramAmount(long nano, View host, boolean tint) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final SpannableString icon = new SpannableString("G");
        icon.setSpan(SGGramIconSpan.of(host, AndroidUtilities.dp(18), tint), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb.append(icon).append(" ").append(GramWallet.formatGrams(nano));
    }

    private static CharSequence gramAmountLegacy(long nano, int iconRes) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final SpannableString icon = new SpannableString("G");
        final ColoredImageSpan span = new ColoredImageSpan(R.drawable.wallet_gram_small);
        span.setSize(AndroidUtilities.dp(16));
        icon.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb.append(icon).append(" ").append(GramWallet.formatGrams(nano));
    }

    private static LayoutTransition smoothTransition() {
        final LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.setDuration(260);
        transition.setInterpolator(LayoutTransition.CHANGING, CubicBezierInterpolator.EASE_OUT_QUINT);
        transition.setInterpolator(LayoutTransition.CHANGE_APPEARING, CubicBezierInterpolator.EASE_OUT_QUINT);
        transition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, CubicBezierInterpolator.EASE_OUT_QUINT);
        return transition;
    }

    @Override
    public View createView(Context context) {
        final int gray = getThemedColor(Theme.key_windowBackgroundGray);
        final int white = getThemedColor(Theme.key_windowBackgroundWhite);
        final int black = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayText = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SgDnsTitle));
        actionBar.setBackgroundColor(gray);
        actionBar.setTitleColor(black);
        actionBar.setItemsColor(black, false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        final ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(gray);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        fragmentView = scrollView;
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(4), dp(16), dp(24));
        layout.setLayoutTransition(smoothTransition());
        scrollView.addView(layout, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // the live card: what is typed, as the server will render it
        heroCard = new SGDomainCardDrawable(getString(R.string.SgDnsSearchHint)).setRadiusFraction(.11f);
        heroView = new View(context);
        heroView.setBackground(heroCard);
        layout.addView(heroView, LayoutHelper.createLinear(124, 124, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        final TextView intro = new TextView(context);
        intro.setText(getString(R.string.SgDnsIntro));
        intro.setTextColor(grayText);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        intro.setGravity(Gravity.CENTER);
        intro.setLineSpacing(dp(2), 1f);
        layout.addView(intro, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 18));

        // «имя» [.ton] (×)
        final LinearLayout searchBox = new LinearLayout(context);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(16), 0, dp(8), 0);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(16), white));
        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(grayText, PorterDuff.Mode.SRC_IN));
        searchBox.addView(searchIcon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        searchEdit = new EditTextBoldCursor(context);
        searchEdit.setBackground(null);
        searchEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        searchEdit.setTextColor(black);
        searchEdit.setHintTextColor(grayText);
        searchEdit.setHint(getString(R.string.SgDnsSearchHint));
        searchEdit.setCursorColor(getThemedColor(Theme.key_featuredStickers_addButton));
        searchEdit.setCursorSize(dp(20));
        searchEdit.setCursorWidth(1.5f);
        searchEdit.setSingleLine(true);
        searchEdit.setPadding(0, dp(16), 0, dp(16));
        searchEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        searchEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchEdit.setFilters(new InputFilter[]{SGDomainsActivity::filterName});
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                final String name = currentName();
                heroCard.setName(TextUtils.isEmpty(name) ? getString(R.string.SgDnsSearchHint) : name);
                showClear(!TextUtils.isEmpty(name));
                scheduleCheck();
            }
        });
        searchEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                AndroidUtilities.cancelRunOnUIThread(checkRunnable);
                runCheck();
                return true;
            }
            return false;
        });
        searchBox.addView(searchEdit, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        final TextView suffix = new TextView(context);
        suffix.setText(SGTonDns.SUFFIX);
        suffix.setTextColor(grayText);
        suffix.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        suffix.setTypeface(AndroidUtilities.bold());
        searchBox.addView(suffix, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));
        clearButton = new ImageView(context);
        clearButton.setImageResource(R.drawable.msg_clear_input);
        clearButton.setScaleType(ImageView.ScaleType.CENTER);
        clearButton.setColorFilter(new PorterDuffColorFilter(grayText, PorterDuff.Mode.SRC_IN));
        clearButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        clearButton.setOnClickListener(v -> {
            searchEdit.setText("");
            searchEdit.requestFocus();
        });
        clearButton.setAlpha(0f);
        clearButton.setScaleX(.5f);
        clearButton.setScaleY(.5f);
        clearButton.setVisibility(View.GONE);
        searchBox.addView(clearButton, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));
        layout.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // the live state chip
        stateChip = new AnimatedTextView(context, false, true, false);
        stateChip.setAnimationProperties(.4f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        stateChip.setTextSize(dp(14));
        stateChip.setTypeface(AndroidUtilities.bold());
        stateChip.setGravity(Gravity.CENTER);
        stateChip.setPadding(dp(12), 0, dp(12), 0);
        stateChipBackground = new GradientDrawable();
        stateChipBackground.setCornerRadius(dp(15));
        stateChip.setSizeableBackground(stateChipBackground);
        stateColor = grayText;
        applyStateColor(stateColor);
        layout.addView(stateChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_HORIZONTAL, 16, 10, 16, 0));

        buyButton = new ButtonWithCounterView(context, getResourceProvider()).setRound();
        buyButton.setOnClickListener(v -> {
            if (lastCheck != null && lastCheck.available) openBuySheet(lastCheck);
            else if (lastListing != null && shownState == STATE_FOR_SALE) openListedBuySheet(lastListing);
        });
        layout.addView(buyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 0));

        // «Мои домены»
        final TextView myHeader = new TextView(context);
        myHeader.setText(getString(R.string.SgDnsMyDomains));
        myHeader.setTextColor(grayText);
        myHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        myHeader.setTypeface(AndroidUtilities.bold());
        layout.addView(myHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 28, 16, 8));
        myList = new LinearLayout(context);
        myList.setOrientation(LinearLayout.VERTICAL);
        myList.setBackground(Theme.createRoundRectDrawable(dp(16), white));
        myList.setLayoutTransition(smoothTransition());
        layout.addView(myList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        createShowcase(context, layout, white, grayText, black);

        // empty state: the 13.1 utyan sticker
        myEmptyView = new LinearLayout(context);
        myEmptyView.setOrientation(LinearLayout.VERTICAL);
        myEmptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        myEmptyView.setPadding(dp(24), dp(20), dp(24), dp(22));
        myEmptySticker = new RLottieImageView(context);
        myEmptySticker.setAutoRepeat(false);
        myEmptySticker.setAnimation(R.raw.utyan_empty, 100, 100);
        myEmptySticker.setOnClickListener(v -> {
            myEmptySticker.setProgress(0);
            myEmptySticker.playAnimation();
        });
        myEmptyView.addView(myEmptySticker, LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL));
        myEmptyTitle = new TextView(context);
        myEmptyTitle.setTextColor(black);
        myEmptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        myEmptyTitle.setTypeface(AndroidUtilities.bold());
        myEmptyTitle.setGravity(Gravity.CENTER);
        myEmptyView.addView(myEmptyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        myEmptyText = new TextView(context);
        myEmptyText.setTextColor(grayText);
        myEmptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        myEmptyText.setGravity(Gravity.CENTER);
        myEmptyView.addView(myEmptyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        bindResult(false);
        bindMyDomains();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // «Мои домены» are fresh after returning to the screen (mints, transfers done elsewhere)
        if (dns != null) {
            dns.loadMyDomains(err -> dns.pollMints());
            dns.loadMyListings(null);
        }
        if (listingsList != null && !listingsLoading) loadListings(true);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && myEmptySticker != null && myEmptySticker.isAttachedToWindow()) myEmptySticker.playAnimation();
    }

    /** a-z 0-9 _ only (upper case folds to lower case), at most 32 characters. */
    private static CharSequence filterName(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        final StringBuilder sb = new StringBuilder(end - start);
        boolean changed = false;
        for (int i = start; i < end; i++) {
            final char c = source.charAt(i);
            final char l = Character.toLowerCase(c);
            if (l >= 'a' && l <= 'z' || l >= '0' && l <= '9' || l == '_') {
                sb.append(l);
                if (l != c) changed = true;
            } else {
                changed = true;
            }
        }
        final int keep = SGTonDns.MAX_NAME - (dest.length() - (dend - dstart));
        if (keep <= 0) return "";
        if (sb.length() > keep) {
            sb.setLength(keep);
            changed = true;
        }
        return changed ? sb.toString() : null;
    }

    private String currentName() {
        return searchEdit == null ? "" : searchEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
    }

    private void showClear(boolean show) {
        if (clearButton == null) return;
        final boolean shown = clearButton.getVisibility() == View.VISIBLE && clearButton.getTag() == null;
        if (shown == show) return;
        clearButton.animate().cancel();
        if (show) {
            clearButton.setTag(null);
            clearButton.setVisibility(View.VISIBLE);
            clearButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        } else {
            clearButton.setTag(1);
            clearButton.animate().alpha(0f).scaleX(.5f).scaleY(.5f).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .withEndAction(() -> {
                        if (clearButton.getTag() != null) clearButton.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void scheduleCheck() {
        AndroidUtilities.cancelRunOnUIThread(checkRunnable);
        dns.cancel(checkReqId);
        checkReqId = -1;
        lastCheck = null;
        lastListing = null;
        lastError = null;
        checkingName = currentName();
        if (!TextUtils.isEmpty(checkingName)) {
            AndroidUtilities.runOnUIThread(checkRunnable, CHECK_DELAY_MS);
        } else {
            checkingName = null;
        }
        bindResult(true);
    }

    private void runCheck() {
        final String name = currentName();
        if (TextUtils.isEmpty(name)) return;
        dns.cancel(checkReqId);
        checkingName = name;
        lastCheck = null;
        lastListing = null;
        lastError = null;
        bindResult(true);
        // checkDomainEx: plus the listing when the name is taken and for sale (checkDomain on an older server)
        checkReqId = dns.checkDomainEx(name, (res, err) -> {
            if (!TextUtils.equals(name, currentName())) return;
            checkReqId = -1;
            checkingName = null;
            if (res != null) {
                final TL_tondns.TL_domainCheck check = new TL_tondns.TL_domainCheck();
                check.name = res.name;
                check.available = res.available;
                check.price = res.price;
                lastCheck = check;
                lastListing = res.listing;
            } else {
                lastCheck = null;
                lastListing = null;
            }
            lastError = res == null ? err : null;
            bindResult(true);
        });
    }

    /** 1 — the state is shown as a tinted pill, 0 — as plain gray text (the input rules hint). */
    private float stateChipFill = 1f;

    private void applyStateColor(int color) {
        stateChip.setTextColor(color);
        stateChipBackground.setColor(Theme.multAlpha(color, .12f * stateChipFill));
        stateChip.invalidate();
    }

    private void animateStateColor(int color, boolean animated) {
        if (stateColorAnimator != null) stateColorAnimator.cancel();
        if (!animated || color == stateColor) {
            stateColor = color;
            applyStateColor(color);
            return;
        }
        final int from = stateColor;
        stateColor = color;
        stateColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        stateColorAnimator.addUpdateListener(a -> applyStateColor((int) a.getAnimatedValue()));
        stateColorAnimator.setDuration(320);
        stateColorAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        stateColorAnimator.start();
    }

    private void showBuy(boolean show, boolean animated) {
        if (buyButton == null || buyShown == show) return;
        buyShown = show;
        buyButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void bindResult(boolean animated) {
        if (stateChip == null) return;
        final String name = currentName();
        int state;
        CharSequence text;
        int color;
        if (TextUtils.isEmpty(name)) {
            state = STATE_NONE;
            text = getString(R.string.SgDnsSearchEmpty);
            color = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        } else if (checkingName != null || lastCheck == null && lastError == null) {
            state = STATE_CHECKING;
            text = getString(R.string.SgDnsChecking);
            color = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        } else if (lastCheck != null && lastCheck.available) {
            state = STATE_AVAILABLE;
            // just «Свободен»: the price is on the «Купить за» button right below
            final String available = getString(R.string.SgDnsAvailablePrice);
            final int dot = available.indexOf(" · ");
            text = dot > 0 ? available.substring(0, dot) : available.replace("%1$s", "").trim();
            color = getThemedColor(Theme.key_windowBackgroundWhiteGreenText2);
        } else if (lastCheck != null && lastListing != null && dns.findMine(lastCheck.name) == null
                && lastListing.seller_id != getUserConfig().getClientUserId()) {
            // taken, but its owner sells it: «Продаётся», buy at the listing price
            state = STATE_FOR_SALE;
            text = getString(R.string.SgDnsOnSale);
            color = getThemedColor(Theme.key_color_orange);
        } else if (lastCheck != null) {
            final boolean mine = dns.findMine(lastCheck.name) != null;
            state = mine ? STATE_YOURS : STATE_TAKEN;
            text = getString(mine ? R.string.SgDnsStateYours : R.string.SgDnsStateTaken);
            color = mine ? getThemedColor(Theme.key_featuredStickers_addButton) : getThemedColor(Theme.key_text_RedBold);
        } else {
            state = STATE_ERROR;
            final boolean invalid = lastError != null && "TONDNS_NAME_INVALID".equals(lastError.text);
            text = invalid ? getString(R.string.SgDnsStateInvalid) : SGTonDns.errorText(lastError);
            color = getThemedColor(Theme.key_text_RedBold);
        }
        // The «Latin letters, digits and _» rules are a plain gray line under the field, not a
        // full-width pill glued to the edges; the live states keep their tinted pill.
        stateChipFill = state == STATE_NONE ? 0f : 1f;
        stateChip.setTextSize(dp(state == STATE_NONE ? 13 : 14));
        stateChip.setText(text, animated && shownState != -1);
        animateStateColor(color, animated && shownState != -1);
        applyStateColor(stateColor);
        shownState = state;
        if (state == STATE_AVAILABLE) {
            buyButton.setText(LocaleController.formatSpannable(R.string.SgDnsBuyFor, gramAmount(lastCheck.price, buyButton, true)), animated && buyShown);
        }
        if (state == STATE_FOR_SALE) {
            buyButton.setText(LocaleController.formatSpannable(R.string.SgDnsBuyFor, gramAmount(lastListing.price, buyButton, true)), animated && buyShown);
        }
        showBuy(state == STATE_AVAILABLE || state == STATE_FOR_SALE, animated);
        if (state == STATE_AVAILABLE && animated) {
            heroView.animate().cancel();
            heroView.setScaleX(.94f);
            heroView.setScaleY(.94f);
            heroView.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
    }

    // ===== «Мои домены» =====

    private static String rowKey(TL_tondns.TL_domain d) {
        return d.name + "|" + d.minted + "|" + d.primary + "|" + d.purchased_at;
    }

    private String rowKeyWithSale(TL_tondns.TL_domain d) {
        final TL_tondns.TL_listing l = dns.getMyListing(d.name);
        return rowKey(d) + "|" + (l != null ? l.price : 0);
    }

    private void bindMyDomains() {
        if (myList == null || getContext() == null) return;
        final Context context = getContext();
        final ArrayList<TL_tondns.TL_domain> list = dns.myDomains;
        if (list.isEmpty()) {
            for (View row : myRows.values()) myList.removeView(row);
            myRows.clear();
            myRowKeys.clear();
            final boolean loading = !dns.isMyLoaded() && dns.getMyError() == null;
            final boolean failed = dns.getMyError() != null;
            myEmptySticker.setVisibility(loading ? View.GONE : View.VISIBLE);
            myEmptyTitle.setText(getString(loading ? R.string.Loading : failed ? R.string.SgDnsMyError : R.string.SgDnsMyEmpty));
            myEmptyText.setText(getString(failed ? R.string.SgNftRetry : R.string.SgDnsMyEmptyHint));
            myEmptyText.setVisibility(loading ? View.GONE : View.VISIBLE);
            myEmptyView.setOnClickListener(failed ? v -> dns.loadMyDomains(null) : null);
            if (myEmptyView.getParent() == null) {
                myList.addView(myEmptyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                if (!loading) myEmptySticker.playAnimation();
            }
        } else {
            if (myEmptyView.getParent() != null) myList.removeView(myEmptyView);
            // drop rows that are gone, rebuild changed ones in place, add new ones in order
            final HashMap<String, TL_tondns.TL_domain> byName = new HashMap<>();
            for (TL_tondns.TL_domain d : list) byName.put(d.name, d);
            for (String name : new ArrayList<>(myRows.keySet())) {
                if (!byName.containsKey(name)) {
                    myList.removeView(myRows.remove(name));
                    myRowKeys.remove(name);
                }
            }
            for (int i = 0; i < list.size(); i++) {
                final TL_tondns.TL_domain d = list.get(i);
                final String key = rowKeyWithSale(d) + "|" + (i < list.size() - 1);
                View row = myRows.get(d.name);
                if (row != null && key.equals(myRowKeys.get(d.name)) && myList.indexOfChild(row) == i) continue;
                if (row != null) {
                    myList.removeView(row);
                }
                row = createDomainRow(context, d, i < list.size() - 1);
                myRows.put(d.name, row);
                myRowKeys.put(d.name, key);
                myList.addView(row, Math.min(i, myList.getChildCount()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
            }
        }
        // a name that just got bought is no longer «available»
        if (lastCheck != null && lastCheck.available && dns.findMine(lastCheck.name) != null) {
            lastCheck.available = false;
            bindResult(true);
        }
    }

    private View createDomainRow(Context context, TL_tondns.TL_domain d, boolean divider) {
        final int black = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayText = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        final FrameLayout row = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (divider) {
                    canvas.drawRect(dp(72), getHeight() - Math.max(1, dp(.5f)), getWidth(), getHeight(), Theme.dividerPaint);
                }
            }
        };
        row.setWillNotDraw(false);
        row.setBackground(Theme.getSelectorDrawable(false));

        // the card thumbnail: the server render once minted, the vector card meanwhile
        final BackupImageView thumb = new BackupImageView(context);
        thumb.setRoundRadius(dp(10));
        final SGDomainCardDrawable local = new SGDomainCardDrawable(d.name).setRadiusFraction(.23f);
        if (d.minted) {
            thumb.setImage(SGTonDns.mediaUrl(d.name), "88_88", local);
        } else {
            thumb.setImageDrawable(local);
        }
        row.addView(thumb, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(context);
        title.setText(SGTonDns.fullName(d.name));
        title.setTextColor(black);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        if (d.primary) {
            final TextView badge = new TextView(context);
            badge.setText(getString(R.string.SgDnsPrimaryChip));
            badge.setTextColor(accent);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(7), 0, dp(7), 0);
            badge.setBackground(Theme.createRoundRectDrawable(dp(9), Theme.multAlpha(accent, .12f)));
            titleRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 18, 0f, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        }
        texts.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final TextView status = new TextView(context);
        final TL_tondns.TL_listing sale = dns.getMyListing(d.name);
        if (d.minted && sale != null) {
            status.setText(formatString(R.string.SgDnsSaleListedFor, GramWallet.formatGrams(sale.price)));
            status.setTextColor(getThemedColor(Theme.key_color_orange));
        } else if (d.minted) {
            status.setText(d.purchased_at > 0
                    ? formatString(R.string.SgDnsBoughtOn, LocaleController.formatDateChat(d.purchased_at, true))
                    : getString(R.string.SgDnsStatusReadyFull));
            status.setTextColor(grayText);
        } else {
            // «Минтится… 0:12»: a ticking timer since the purchase instead of an endless spinner
            status.setTextColor(accent);
            final Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                final int now = getConnectionsManager().getCurrentTime();
                final int secs = d.purchased_at > 0 ? Math.max(0, now - d.purchased_at) : 0;
                status.setText(d.purchased_at > 0
                        ? formatString(R.string.SgDnsMintingElapsed, String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60))
                        : getString(R.string.SgDnsStatusMintingFull));
                if (status.isAttachedToWindow()) AndroidUtilities.runOnUIThread(tick[0], 1000);
            };
            status.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    AndroidUtilities.cancelRunOnUIThread(tick[0]);
                    tick[0].run();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    AndroidUtilities.cancelRunOnUIThread(tick[0]);
                }
            });
            tick[0].run();
        }
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        status.setSingleLine(true);
        texts.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 44, 0));

        {
            // the minting row shows its timer in the status line (no endless spinner)
            final ImageView arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.attach_arrow_right);
            arrow.setColorFilter(new PorterDuffColorFilter(grayText, PorterDuff.Mode.SRC_IN));
            row.addView(arrow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        }
        row.setOnClickListener(v -> SGDomainSheet.open(getContext(), currentAccount, d, getResourceProvider()));
        return row;
    }

    // ===== «Продаются»: the resale showcase =====

    private void createShowcase(Context context, LinearLayout layout, int white, int grayText, int black) {
        final TextView header = new TextView(context);
        header.setText(getString(R.string.SgDnsForSale));
        header.setTextColor(grayText);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 28, 16, 8));

        final android.widget.HorizontalScrollView sortScroll = new android.widget.HorizontalScrollView(context);
        sortScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout sorts = new LinearLayout(context);
        sorts.setOrientation(LinearLayout.HORIZONTAL);
        final int[] labels = {R.string.SgDnsSortDate, R.string.SgDnsSortCheap, R.string.SgDnsSortExpensive, R.string.SgDnsSortName};
        for (int i = 0; i < 4; i++) {
            final TextView chip = new TextView(context);
            chip.setText(getString(labels[i]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            final String sort = SORTS[i];
            chip.setOnClickListener(v -> {
                if (sort.equals(listingsSort)) return;
                listingsSort = sort;
                bindSortChips();
                loadListings(true);
            });
            sortChips[i] = chip;
            sorts.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }
        sortScroll.addView(sorts);
        layout.addView(sortScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        bindSortChips();

        listingsList = new LinearLayout(context);
        listingsList.setOrientation(LinearLayout.VERTICAL);
        listingsList.setBackground(Theme.createRoundRectDrawable(dp(16), white));
        layout.addView(listingsList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listingsEmpty = new TextView(context);
        listingsEmpty.setTextColor(grayText);
        listingsEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        listingsEmpty.setGravity(Gravity.CENTER);
        listingsEmpty.setPadding(dp(16), dp(22), dp(16), dp(22));

        listingsMore = new TextView(context);
        listingsMore.setText(getString(R.string.SgDnsForSaleMore));
        listingsMore.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        listingsMore.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        listingsMore.setTypeface(AndroidUtilities.bold());
        listingsMore.setGravity(Gravity.CENTER);
        listingsMore.setPadding(0, dp(14), 0, dp(14));
        listingsMore.setBackground(Theme.getSelectorDrawable(false));
        listingsMore.setOnClickListener(v -> loadListings(false));

        loadListings(true);
    }

    private void bindSortChips() {
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        final int grayText = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        for (int i = 0; i < 4; i++) {
            final boolean on = SORTS[i].equals(listingsSort);
            sortChips[i].setTextColor(on ? accent : grayText);
            sortChips[i].setBackground(Theme.createRoundRectDrawable(dp(15), on ? Theme.multAlpha(accent, .14f) : getThemedColor(Theme.key_windowBackgroundWhite)));
        }
    }

    private void loadListings(boolean reset) {
        if (listingsList == null) return;
        if (listingsLoading && !reset) return;
        listingsLoading = true;
        final int offset = reset ? 0 : listings.size();
        final String sort = listingsSort;
        if (reset && listings.isEmpty()) bindListings();
        dns.getListings(offset, 30, sort, (res, err) -> {
            if (!sort.equals(listingsSort)) return;
            listingsLoading = false;
            if (res != null) {
                if (reset) listings.clear();
                listings.addAll(res.listings);
                listingsCount = res.count;
            } else if (reset) {
                listings.clear();
                listingsCount = 0;
            }
            bindListings();
        });
    }

    private void bindListings() {
        if (listingsList == null || getContext() == null) return;
        listingsList.removeAllViews();
        if (listings.isEmpty()) {
            listingsEmpty.setText(getString(listingsLoading ? R.string.Loading : R.string.SgDnsForSaleEmpty));
            listingsList.addView(listingsEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < listings.size(); i++) {
            final boolean divider = i < listings.size() - 1 || listings.size() < listingsCount;
            listingsList.addView(createListingRow(getContext(), listings.get(i), divider), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        }
        if (listings.size() < listingsCount) {
            listingsList.addView(listingsMore, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private View createListingRow(Context context, TL_tondns.TL_listing l, boolean divider) {
        final int black = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayText = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        final FrameLayout row = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (divider) {
                    canvas.drawRect(dp(72), getHeight() - Math.max(1, dp(.5f)), getWidth(), getHeight(), Theme.dividerPaint);
                }
            }
        };
        row.setWillNotDraw(false);
        row.setBackground(Theme.getSelectorDrawable(false));
        final String name = SGTonDns.fullName(l.name);
        final BackupImageView thumb = new BackupImageView(context);
        thumb.setRoundRadius(dp(10));
        thumb.setImage(SGTonDns.mediaUrl(name), "88_88", new SGDomainCardDrawable(name).setRadiusFraction(.23f));
        row.addView(thumb, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(name);
        title.setTextColor(black);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        final TextView seller = new TextView(context);
        final TLRPC.User user = getMessagesController().getUser(l.seller_id);
        seller.setText(user != null ? org.telegram.messenger.UserObject.getUserName(user) : "");
        seller.setTextColor(grayText);
        seller.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        seller.setSingleLine(true);
        seller.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(seller, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 110, 0));

        final TextView price = new TextView(context);
        price.setTextColor(black);
        price.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        price.setTypeface(AndroidUtilities.bold());
        price.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        price.setText(gramAmount(l.price, price, true));
        row.addView(price, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

        row.setOnClickListener(v -> {
            if (l.seller_id == getUserConfig().getClientUserId()) {
                final TL_tondns.TL_domain mine = dns.findMine(name);
                if (mine != null) SGDomainSheet.open(getContext(), currentAccount, mine, getResourceProvider());
                else SGDomainSaleSheet.open(getContext(), currentAccount, name, getResourceProvider(), null);
            } else {
                openListedBuySheet(l);
            }
        });
        return row;
    }

    /** Buying a listed domain: tondns.buyListed at the shown price (one request key per attempt, reused on retries). */
    private void openListedBuySheet(TL_tondns.TL_listing first) {
        final Context context = getContext();
        if (context == null || first == null) return;
        final TL_tondns.TL_listing[] listing = {first};
        final String name = SGTonDns.fullName(first.name);
        final String[] requestKey = {SGTonDns.newRequestKey()};
        final int backgroundColor = getThemedColor(Theme.key_windowBackgroundGray);
        final int black = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayText = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);

        final BottomSheet sheet = new BottomSheet(context, false, getResourceProvider());
        sheet.setBackgroundColor(backgroundColor);
        sheet.fixNavigationBar(backgroundColor);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(12), dp(8), dp(12), dp(12));
        layout.addView(SGCollectibleSheetUi.createHandle(context, getResourceProvider()), LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        final BackupImageView card = new BackupImageView(context);
        card.setRoundRadius(dp(16));
        card.setImage(SGTonDns.mediaUrl(name), "280_280", new SGDomainCardDrawable(name).setRadiusFraction(0));
        layout.addView(card, LayoutHelper.createLinear(140, 140, Gravity.CENTER_HORIZONTAL));

        final TextView title = new TextView(context);
        title.setText(name);
        title.setTextColor(black);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 16, 12, 0));
        final TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.SgDnsBuyListedText));
        subtitle.setTextColor(grayText);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 6, 16, 0));

        // Цена · Ваш баланс · Продавец
        final TableView table = new TableView(context, getResourceProvider());
        table.setBackground(Theme.createRoundRectDrawable(dp(10), getThemedColor(Theme.key_windowBackgroundWhite)));
        final TextView priceValue = tableValue(context);
        final TextView balanceValue = tableValue(context);
        table.addRow(getString(R.string.SgDnsPrice), priceValue);
        table.addRow(getString(R.string.SgDnsBalance), balanceValue);
        table.addRowUser(getString(R.string.SgDnsSeller), currentAccount, first.seller_id, null);
        layout.addView(table, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 14));

        final ButtonWithCounterView button = new ButtonWithCounterView(context, getResourceProvider()).setRound();
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        final Runnable bind = () -> {
            final long balance = SGCardsController.getInstance(currentAccount).getGramBalance();
            final long price = listing[0].price;
            priceValue.setText(gramAmount(price, priceValue, true));
            balanceValue.setText(balance < 0 ? getString(R.string.Loading) : gramAmount(balance, balanceValue, true));
            final boolean low = balance >= 0 && balance < price;
            balanceValue.setTextColor(low ? getThemedColor(Theme.key_text_RedBold) : black);
            button.setText(low ? getString(R.string.SgDnsLowBalance)
                    : LocaleController.formatSpannable(R.string.SgDnsBuyFor, gramAmount(price, button, true)), true);
            button.setEnabled(!low);
        };
        button.setOnClickListener(v -> {
            if (button.isLoading()) return;
            button.setLoading(true);
            dns.buyListed(listing[0], requestKey[0], (res, err) -> {
                button.setLoading(false);
                if (res != null) {
                    sheet.dismiss();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.SgDnsBoughtListed)).show();
                    if (lastListing != null && TextUtils.equals(SGTonDns.fullName(lastListing.name), name)) {
                        lastListing = null;
                        bindResult(true);
                    }
                    dns.loadMyDomains(null);
                    loadListings(true);
                    return;
                }
                if (err != null && "TONDNS_PRICE_CHANGED".equals(err.text)) {
                    // ask again and show the new price; the next tap is a new attempt
                    dns.checkDomainEx(name, (r, e) -> {
                        if (r != null && r.listing != null) {
                            listing[0] = r.listing;
                            requestKey[0] = SGTonDns.newRequestKey();
                            bind.run();
                            BulletinFactory.of(sheet.getContainer(), getResourceProvider()).createSimpleBulletin(R.raw.error,
                                    formatString(R.string.SgDnsPriceChangedNew, GramWallet.formatGrams(r.listing.price))).show();
                        } else {
                            sheet.dismiss();
                            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SgDnsSaleErrorNotListed)).show();
                            loadListings(true);
                        }
                    });
                    return;
                }
                if (err != null && "TONDNS_NOT_LISTED".equals(err.text)) {
                    sheet.dismiss();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SgDnsSaleErrorNotListed)).show();
                    loadListings(true);
                    return;
                }
                SGTonDns.showError(BulletinFactory.of(sheet.getContainer(), getResourceProvider()), err);
            });
        });
        bind.run();
        SGCardsController.getInstance(currentAccount).loadGramBalance(b -> bind.run());
        sheet.setCustomView(layout);
        sheet.show();
    }

    // ===== buying =====

    private void openBuySheet(TL_tondns.TL_domainCheck check) {
        final Context context = getContext();
        if (context == null) return;
        final String name = SGTonDns.fullName(check.name);
        final long[] price = {check.price};
        final String requestKey = SGTonDns.newRequestKey(); // one per confirm sheet, reused on retries
        final int backgroundColor = getThemedColor(Theme.key_windowBackgroundGray);
        final int black = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayText = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);

        final BottomSheet sheet = new BottomSheet(context, false, getResourceProvider());
        sheet.setBackgroundColor(backgroundColor);
        sheet.fixNavigationBar(backgroundColor);
        final LinearLayout layout = new LinearLayout(context);
        // The sheet is as tall as its content, at the bottom (the fireworks layer used to make it
        // full screen: it is MATCH_PARENT, so it now takes the content's height, not the screen's).
        final FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                layout.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST));
                final int h = layout.getMeasuredHeight();
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
                for (int i = 0; i < getChildCount(); i++) {
                    final View child = getChildAt(i);
                    if (child != layout) child.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                }
            }
        };
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(12), dp(8), dp(12), dp(12));
        layout.addView(SGCollectibleSheetUi.createHandle(context, getResourceProvider()), LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));
        layout.setLayoutTransition(smoothTransition());
        root.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final View card = new View(context);
        card.setBackground(new SGDomainCardDrawable(name).setRadiusFraction(.11f));
        layout.addView(card, LayoutHelper.createLinear(140, 140, Gravity.CENTER_HORIZONTAL));

        final TextView title = new TextView(context);
        title.setText(name);
        title.setTextColor(black);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 16, 12, 0));

        final TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.SgDnsBuyText));
        subtitle.setTextColor(grayText);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2), 1f);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 6, 16, 0));

        // Цена · Ваш баланс · Срок (the 13.1 TableView)
        final TableView table = new TableView(context, getResourceProvider());
        table.setBackground(Theme.createRoundRectDrawable(dp(10), getThemedColor(Theme.key_windowBackgroundWhite)));
        final TextView priceValue = tableValue(context);
        final TextView balanceValue = tableValue(context);
        table.addRow(getString(R.string.SgDnsPrice), priceValue);
        table.addRow(getString(R.string.SgDnsBalance), balanceValue);
        table.addRow(getString(R.string.SgDnsTerm), getString(R.string.SgDnsTermForever));
        layout.addView(table, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 14));

        // «Минтится…» / «Готово»
        final LinearLayout statusBox = new LinearLayout(context);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER);
        final RadialProgressView statusProgress = new RadialProgressView(context, getResourceProvider());
        statusProgress.setSize(dp(18));
        statusProgress.setProgressColor(getThemedColor(Theme.key_featuredStickers_addButton));
        statusBox.addView(statusProgress, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        final TextView statusText = new TextView(context);
        statusText.setTextColor(black);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusText.setTypeface(AndroidUtilities.bold());
        statusBox.addView(statusText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        statusBox.setVisibility(View.GONE);
        layout.addView(statusBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 6, 0, 12));

        final ButtonWithCounterView button = new ButtonWithCounterView(context, getResourceProvider()).setRound();
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        final ButtonWithCounterView secondButton = new ButtonWithCounterView(context, false, getResourceProvider()).setRound();
        secondButton.setVisibility(View.GONE);
        layout.addView(secondButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));

        final FireworksOverlay fireworks = new FireworksOverlay(context);
        root.addView(fireworks, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // 0 = confirm, 1 = minting, 2 = done
        final int[] stage = {0};
        final boolean[] celebrated = {false};
        final Runnable bind = () -> {
            final long balance = SGCardsController.getInstance(currentAccount).getGramBalance();
            priceValue.setText(gramAmount(price[0], priceValue, true));
            balanceValue.setText(balance < 0 ? getString(R.string.Loading) : gramAmount(balance, balanceValue, true));
            if (stage[0] == 0) {
                final boolean low = balance >= 0 && balance < price[0];
                balanceValue.setTextColor(low ? getThemedColor(Theme.key_text_RedBold) : black);
                button.setText(low ? getString(R.string.SgDnsLowBalance)
                        : LocaleController.formatSpannable(R.string.SgDnsBuyFor, gramAmount(price[0], button, true)), true);
                button.setEnabled(!low);
                return;
            }
            final TL_tondns.TL_domain mine = dns.findMine(name);
            if (stage[0] == 1 && mine != null && mine.minted) stage[0] = 2;
            if (!celebrated[0]) {
                // the upstream gift purchase success: fireworks and a bounce of the card
                celebrated[0] = true;
                fireworks.start(true);
                card.animate().cancel();
                card.setScaleX(.85f);
                card.setScaleY(.85f);
                card.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
            statusBox.setVisibility(View.VISIBLE);
            table.setVisibility(View.GONE);
            if (stage[0] == 1) {
                statusProgress.setVisibility(View.VISIBLE);
                statusText.setText(getString(R.string.SgDnsMinting));
                subtitle.setText(getString(R.string.SgDnsMintingText));
                button.setLoading(false);
                button.setEnabled(true);
                button.setText(getString(R.string.SgDnsMintingClose), true);
                secondButton.setVisibility(View.GONE);
            } else {
                statusProgress.setVisibility(View.GONE);
                statusText.setText(getString(R.string.SgDnsBought));
                subtitle.setText(formatString(R.string.SgDnsBoughtText, name));
                button.setLoading(false);
                button.setEnabled(true);
                button.setText(getString(mine != null && mine.primary ? R.string.SgDnsDone : R.string.SgDnsSetPrimary), true);
                secondButton.setVisibility(mine != null && mine.primary ? View.GONE : View.VISIBLE);
                secondButton.setText(getString(R.string.SgDnsDone), false);
            }
        };
        final Runnable dnsListener = bind;
        dns.subscribe(dnsListener);
        sheet.setOnDismissListener(di -> dns.unsubscribe(dnsListener));
        SGCardsController.getInstance(currentAccount).loadGramBalance(b -> bind.run());
        bind.run();

        button.setOnClickListener(v -> {
            if (button.isLoading()) return;
            if (stage[0] == 1) {
                sheet.dismiss();
                return;
            }
            if (stage[0] == 2) {
                final TL_tondns.TL_domain mine = dns.findMine(name);
                if (mine != null && mine.primary) {
                    sheet.dismiss();
                    return;
                }
                button.setLoading(true);
                dns.setPrimary(name, err -> {
                    button.setLoading(false);
                    if (err != null) {
                        SGTonDns.showError(BulletinFactory.of(sheet.container, getResourceProvider()), err);
                        return;
                    }
                    sheet.dismiss();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_pin, formatString(R.string.SgDnsPrimarySet, name)).show();
                });
                return;
            }
            button.setLoading(true);
            dns.buyDomain(SGTonDns.shortName(name), price[0], requestKey, (res, err) -> {
                if (res != null) {
                    stage[0] = res.domain != null && res.domain.minted ? 2 : 1;
                    SGCardsController.getInstance(currentAccount).loadGramBalance(null);
                    bind.run();
                    return;
                }
                button.setLoading(false);
                if (err != null && "TONDNS_PRICE_CHANGED".equals(err.text)) {
                    // show the live price before buying again
                    dns.checkDomain(SGTonDns.shortName(name), (c, e) -> {
                        if (c != null) {
                            price[0] = c.price;
                            if (!c.available) {
                                sheet.dismiss();
                                runCheck();
                                return;
                            }
                        }
                        bind.run();
                    });
                }
                SGTonDns.showError(BulletinFactory.of(sheet.container, getResourceProvider()), err);
                bind.run();
            });
        });
        secondButton.setOnClickListener(v -> sheet.dismiss());

        sheet.setCustomView(root);
        sheet.useBackgroundTopPadding = false;
        showDialog(sheet);
        sheet.fixNavigationBar(backgroundColor);
    }

    private TextView tableValue(Context context) {
        final TextView value = new TextView(context);
        value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        value.setTypeface(AndroidUtilities.bold());
        value.setSingleLine(true);
        return value;
    }
}
