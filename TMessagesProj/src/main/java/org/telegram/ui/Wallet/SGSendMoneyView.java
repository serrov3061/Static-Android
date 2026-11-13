package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.utils.network.ExchangeRates;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stars.StarsIntroActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Sends GRAM (gramwallet.send) or Stars (sgcard.sendStars) to one user, laid out 1:1 like
 * Telegram 13.1.1's ChatAttachAlertGramLayout: the full-width recipient plate (WalletRecipientView:
 * avatar, name, the wallet address in two lines with placeholders while it resolves), the big
 * centred amount (WalletSendEnterAmount.AmountInputView: 3D diamond, digit animations; the golden
 * star for Stars), the "≈ 0,00 ₽ ⇅" chip that switches crypto/fiat entry, "Tap to set the amount"
 * that turns into the insufficient-funds error once an amount is being entered, and at the
 * bottom the balance with the send button appearing only with an amount.
 * StaticGram: a Stars | GRAM switch on top while the entry point did not fix the currency, and
 * the comment typed in place in the 13.1.1 comment bubble; it travels with the transfer. One request_key
 * is kept per entered transfer and reused on retries. With no user it is «На адрес»: GRAM to a
 * wallet address or name.ton (gramwallet.sendToAddress) typed in place of the recipient plate.
 */
@SuppressLint("ViewConstructor")
public class SGSendMoneyView extends LinearLayout {

    public interface Delegate {
        /** @param sinceDate server time when the transfer was sent; its chat message is not older */
        void onSent(int kind, long amount, boolean replayed, int sinceDate);

        void onError(String text);

        default void onOpenRecipient() {
        }
    }

    public static final int STARS_COLOR = 0xFFF1A12A;
    private static final int TAB_STARS = 0, TAB_GRAM = 1;
    private static final float PREVIEW_FRACTION_SCALE = 12.0f / 14.0f;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final TLRPC.User recipient;
    private int kind;
    private final SGSegmentedTabs tabs;
    private final WalletRecipientView recipientView;
    private final WalletSendEnterAmount.AmountInputView amountInput;
    private final EditTextBoldCursor amountEdit;
    private final AnimatedTextView currencyView;
    private final FrameLayout centerBottomContainer;
    private final LinearLayout centerBottom;
    private final TextView tapToSetAmount;
    private final EditTextBoldCursor commentEdit;
    private final TextView insufficientFundsView;
    private final FrameLayout bottomContainer;
    private final TextView balanceView;
    private final ButtonWithCounterView sendButton;
    private final CharSequence exchangeIcon;
    private Delegate delegate;
    private String recipientAddress;
    // StaticGram «На адрес»: no user, a wallet address or name.ton typed in place of the plate
    private final boolean addressMode;
    private LinearLayout addressBox;
    private EditTextBoldCursor addressEdit;
    private TextView addressAction;
    private TextView addressStatus;
    private SGTonDns.FieldResolver addressResolver;
    private String addressDomainFor;
    private String requestKey, requestSignature;
    private boolean sending;
    private boolean inFiat;
    private boolean centerBottomVisible;
    private boolean sendButtonVisible = true;
    private boolean insufficientShown = true;
    private int shakeDp = 6;
    private long balance = -1;

    // Fiat entry: the wallet's currency setting, else the device's (RUB for Russian), else USD
    private String fiatCode = "USD";
    private BigDecimal usdPerFiat = BigDecimal.ONE;

    public SGSendMoneyView(Context context, int currentAccount, TLRPC.User recipient, int kind, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        this.recipient = recipient;
        this.addressMode = recipient == null;
        this.kind = addressMode ? TL_sgcard.KIND_GRAM : kind;
        kind = this.kind;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(0, dp(4), 0, 0);

        // StaticGram: Stars | GRAM switch on top, only while the currency is not preselected.
        tabs = new SGSegmentedTabs(context,
                StarsIntroActivity.replaceStarsWithPlain("⭐ " + getString(R.string.SgCardCurrencyStars), .85f),
                gramText(getString(R.string.SgCardCurrencyGram)));
        tabs.setColors(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        tabs.setSelected(kind == TL_sgcard.KIND_STARS ? TAB_STARS : TAB_GRAM, false);
        tabs.setOnSelected(i -> setKind(i == TAB_STARS ? TL_sgcard.KIND_STARS : TL_sgcard.KIND_GRAM));
        addView(tabs, LayoutHelper.createLinear(220, 38, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

        // 13.1.1 WalletRecipientView, as ChatAttachAlertGramLayout adds it
        recipientView = new WalletRecipientView(context, resourcesProvider,
                () -> {
                    if (delegate != null) delegate.onOpenRecipient();
                },
                () -> BulletinFactory.global()
                        .createSimpleBulletin(R.raw.copy, getString(R.string.WalletAddressCopiedBulletin))
                        .show());
        recipientView.setRecipient(recipient, null);
        addView(recipientView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 64, Gravity.CENTER_HORIZONTAL, 6, 0, 6, 0));
        if (addressMode) {
            recipientView.setVisibility(GONE);
            tabs.setVisibility(GONE);
            createAddressBox(context);
        }

        // Amount: the 3D diamond (or the star), the digits and the accent unit, centred as a whole
        amountInput = new WalletSendEnterAmount.AmountInputView(context, resourcesProvider);
        amountInput.setOnClickListener(v -> requestAmountKeyboard());
        amountEdit = amountInput.getEditText();
        amountEdit.setFilters(new InputFilter[]{this::filterAmount});
        amountEdit.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        amountEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && SGSendMoneyView.this.sendButton.isEnabled()) {
                SGSendMoneyView.this.sendButton.performClick();
                return true;
            }
            return false;
        });
        amountEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateAmount(true);
            }
        });
        amountEdit.setOnFocusChangeListener((v, focused) -> updateCenterBottom(true));
        addView(amountInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64, Gravity.CENTER_HORIZONTAL, 16, addressMode ? 36 : 76, 16, 0));

        exchangeIcon = new SpannableString("⇅");
        final ColoredImageSpan exchangeSpan = new ColoredImageSpan(R.drawable.wallet_currency_exchange);
        exchangeSpan.setAlpha(.72f);
        ((SpannableString) exchangeIcon).setSpan(exchangeSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // "≈ 0,00 ₽ ⇅": always shown, switches the entry between the currency and fiat
        currencyView = new AnimatedTextView(context, false, true, true, true, true);
        currencyView.setAnimationProperties(.35f, 0, 320, 3.5f, CubicBezierInterpolator.EASE_OUT_QUINT);
        currencyView.setScaleProperty(.35f);
        currencyView.setTextColor(Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider)));
        currencyView.setTextSize(dp(14));
        currencyView.setTypeface(AndroidUtilities.getTypeface("fonts/gram.ttf"));
        currencyView.setGravity(Gravity.CENTER);
        currencyView.setPadding(dp(10), 0, dp(10), 0);
        currencyView.setAllowCancel(true);
        currencyView.setSizeableBackground(Theme.createRoundRectDrawable(dp(14),
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider), .08f)));
        currencyView.setOnClickListener(v -> switchInputMode());
        ScaleStateListAnimator.apply(currencyView);
        addView(currencyView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        // "Tap to set the amount", replaced by the insufficient-funds error while entering
        centerBottomContainer = new FrameLayout(context);
        centerBottomContainer.setClipChildren(false);
        addView(centerBottomContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        centerBottom = new LinearLayout(context);
        centerBottom.setOrientation(VERTICAL);
        centerBottomContainer.addView(centerBottom, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        tapToSetAmount = new TextView(context);
        tapToSetAmount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tapToSetAmount.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        tapToSetAmount.setPadding(dp(10), 0, dp(10), 0);
        tapToSetAmount.setGravity(Gravity.CENTER);
        tapToSetAmount.setText(getString(R.string.SgMoneyTapToSetAmount));
        tapToSetAmount.setOnClickListener(v -> requestAmountKeyboard());
        ScaleStateListAnimator.apply(tapToSetAmount);
        centerBottomContainer.addView(tapToSetAmount, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        insufficientFundsView = new TextView(context);
        insufficientFundsView.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        insufficientFundsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        insufficientFundsView.setGravity(Gravity.CENTER);
        insufficientFundsView.setTypeface(AndroidUtilities.bold());
        insufficientFundsView.setPadding(dp(10), 0, dp(10), 0);
        insufficientFundsView.setText(getString(R.string.WalletInsufficientFunds));
        centerBottom.addView(insufficientFundsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
        showInsufficient(false, false);

        // StaticGram: the comment is typed in place, always visible, in the 13.1.1 comment bubble.
        commentEdit = new EditTextBoldCursor(context);
        final MessageDrawable commentBackground = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, false);
        commentBackground.shadow = false;
        commentBackground.paint.setStyle(Paint.Style.STROKE);
        commentBackground.paint.setStrokeWidth(Math.max(1, dpf2(.5f)));
        commentBackground.paint.setColor(Theme.getColor(Theme.key_wallet_out_commentBorder, resourcesProvider));
        commentBackground.overrideColor = Theme.getColor(Theme.key_wallet_out_commentBorder, resourcesProvider);
        commentEdit.setBackground(commentBackground);
        commentEdit.setPadding(dp(15), dp(7), dp(19), dp(8));
        commentEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        commentEdit.setTypeface(AndroidUtilities.bold());
        commentEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        commentEdit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        commentEdit.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        commentEdit.setCursorSize(dp(17));
        commentEdit.setCursorWidth(1.5f);
        commentEdit.setGravity(Gravity.CENTER);
        commentEdit.setMinWidth(dp(160));
        commentEdit.setMaxWidth(AndroidUtilities.displaySize.x - dp(64));
        commentEdit.setHint(getString(R.string.SgCardCommentHint));
        commentEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SGCardsController.MAX_COMMENT)});
        commentEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        commentEdit.setMaxLines(4);
        commentEdit.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        commentEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                requestSignature = null;
            }
        });
        addView(commentEdit, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 12, 32, 0));

        centerBottom.setVisibility(INVISIBLE);
        centerBottom.setAlpha(0f);
        centerBottom.setScaleX(.8f);
        centerBottom.setScaleY(.8f);
        centerBottomVisible = false;

        // Bottom: the balance, and the send button that shows up with an amount
        bottomContainer = new FrameLayout(context);
        bottomContainer.setPadding(dp(16), 0, dp(16), dp(12));
        bottomContainer.setClipChildren(false);
        bottomContainer.setClipToPadding(false);
        balanceView = new TextView(context);
        balanceView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        balanceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        balanceView.setGravity(Gravity.CENTER);
        bottomContainer.addView(balanceView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, Gravity.TOP | Gravity.FILL_HORIZONTAL));
        sendButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        sendButton.setEnabled(false);
        sendButton.setOnClickListener(v -> send());
        bottomContainer.addView(sendButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL));
        addView(bottomContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 92, Gravity.CENTER_HORIZONTAL, 0, 32, 0, 0));
        updateSendButtonVisibility(false, false);

        loadFiatRates();
        if (!addressMode) loadRecipientAddress();
        setKind(kind, true);
    }

    /**
     * «На адрес»: the recipient input of 13.1's ChatAttachAlertGramLayout / WalletRecipientView —
     * a rounded field «Адрес или имя.ton» with «Вставить» (× once typed) and a live preview under
     * it: «→ UQ…» for a name.ton (tondns.resolve), the primary domain of a pasted address, or
     * «Неверный адрес».
     */
    private void createAddressBox(Context context) {
        final int gray2 = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        addressBox = new LinearLayout(context);
        addressBox.setOrientation(VERTICAL);
        final LinearLayout field = new LinearLayout(context);
        field.setOrientation(HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(16), 0, dp(6), 0);
        field.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(gray2, .09f)));
        final TextView to = new TextView(context);
        to.setText(getString(R.string.SgMoneyAddressTo));
        to.setTextColor(gray2);
        to.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.addView(to, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        addressEdit = new EditTextBoldCursor(context);
        addressEdit.setBackground(null);
        addressEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        addressEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        addressEdit.setHintTextColor(gray2);
        addressEdit.setHint(getString(R.string.SgMoneyAddressHint));
        addressEdit.setCursorColor(accent);
        addressEdit.setCursorSize(dp(18));
        addressEdit.setCursorWidth(1.5f);
        addressEdit.setSingleLine(true);
        addressEdit.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addressEdit.setPadding(0, dp(14), 0, dp(14));
        addressEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        addressEdit.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        addressEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                requestAmountKeyboard();
                return true;
            }
            return false;
        });
        field.addView(addressEdit, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        addressAction = new TextView(context);
        addressAction.setTextColor(accent);
        addressAction.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        addressAction.setTypeface(AndroidUtilities.bold());
        addressAction.setGravity(Gravity.CENTER);
        addressAction.setPadding(dp(10), 0, dp(10), 0);
        addressAction.setBackground(Theme.createRadSelectorDrawable(0, Theme.multAlpha(accent, .12f), 12, 12));
        addressAction.setOnClickListener(v -> {
            if (addressEdit.length() > 0) {
                addressEdit.setText("");
                addressEdit.requestFocus();
                AndroidUtilities.showKeyboard(addressEdit);
                return;
            }
            final CharSequence clip = clipboardText();
            if (!TextUtils.isEmpty(clip)) {
                addressEdit.setText(clip.toString().trim());
                addressEdit.setSelection(addressEdit.length());
                requestAmountKeyboard();
            }
        });
        ScaleStateListAnimator.apply(addressAction);
        field.addView(addressAction, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, Gravity.CENTER_VERTICAL));
        addressBox.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addressStatus = new TextView(context);
        addressStatus.setTextColor(gray2);
        addressStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        addressStatus.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        addressStatus.setSingleLine(true);
        addressStatus.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addressStatus.setGravity(Gravity.CENTER);
        addressBox.addView(addressStatus, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20, 16, 6, 16, 0));
        addView(addressBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // name.ton → tondns.resolve (debounced) into addressStatus; raw addresses are checked here
        addressResolver = SGTonDns.attachResolver(currentAccount, addressEdit, addressStatus);
        addressEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                requestSignature = null;
                bindAddress();
                // the resolver answers later: refresh the button and the preview colour then
                AndroidUtilities.runOnUIThread(SGSendMoneyView.this::bindAddressLater, 700);
                AndroidUtilities.runOnUIThread(SGSendMoneyView.this::bindAddressLater, 2000);
            }
        });
        bindAddress();
    }

    private CharSequence clipboardText() {
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return null;
            return cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
        } catch (Exception e) {
            return null;
        }
    }

    private void bindAddressLater() {
        if (addressEdit == null || !isAttachedToWindow()) return;
        bindAddress();
    }

    /** The address «На адрес» sends to: the resolved one for name.ton; null while unknown or invalid. */
    private String addressTarget() {
        if (addressEdit == null) return null;
        final String target = addressResolver != null ? addressResolver.target(addressEdit.getText().toString()) : addressEdit.getText().toString().trim();
        return SGChainWallet.looksLikeAddress(target) ? target.trim() : null;
    }

    private void bindAddress() {
        if (addressEdit == null) return;
        final String text = addressEdit.getText().toString().trim();
        addressAction.setText(getString(text.isEmpty() ? R.string.SgNftPaste : R.string.SgMoneyAddressClear));
        final int gray2 = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
        if (SGTonDns.looksLikeDomain(text)) {
            // attachResolver owns the text («Ищем домен…» / «→ UQ…» / «Домен не найден»)
            final boolean notFound = addressResolver != null && !addressResolver.loading && addressResolver.domain != null && addressResolver.address == null;
            addressStatus.setTextColor(notFound ? Theme.getColor(Theme.key_text_RedBold, resourcesProvider) : gray2);
        } else if (text.isEmpty()) {
            addressStatus.setVisibility(VISIBLE);
            addressStatus.setTextColor(gray2);
            addressStatus.setTypeface(null);
            addressStatus.setText(getString(R.string.SgMoneyAddressExplain));
        } else if (SGChainWallet.looksLikeAddress(text)) {
            addressStatus.setVisibility(VISIBLE);
            addressStatus.setTextColor(gray2);
            final String cached = SGTonDns.getInstance(currentAccount).getCachedDomain(text);
            addressStatus.setText(cached != null ? "\u2190 " + cached : getString(R.string.SgMoneyAddressValid));
            if (cached == null && !TextUtils.equals(addressDomainFor, text)) {
                addressDomainFor = text;
                SGTonDns.getInstance(currentAccount).getDomain(text, domain -> {
                    if (domain != null && addressEdit != null && TextUtils.equals(addressEdit.getText().toString().trim(), text)) {
                        addressStatus.setText("\u2190 " + domain);
                    }
                });
            }
        } else {
            addressStatus.setVisibility(VISIBLE);
            addressStatus.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
            addressStatus.setText(getString(text.length() < 48 && !text.contains(".") ? R.string.SgMoneyAddressTyping : R.string.SgNftInvalidAddress));
            if (text.length() < 48 && !text.contains(".")) addressStatus.setTextColor(gray2);
        }
        addressStatus.setTypeface(SGTonDns.looksLikeDomain(text) || SGChainWallet.looksLikeAddress(text)
                ? AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO) : null);
        if (sendButton != null && amountEdit != null) updateButton();
    }

    /** «На адрес» mode: no user, GRAM to a wallet address or name.ton. */
    public boolean isAddressMode() {
        return addressMode;
    }

    /** The address (or name.ton as typed) the last transfer went to, for the bulletin. */
    public String getAddressLabel() {
        if (addressEdit == null) return "";
        final String text = addressEdit.getText().toString().trim();
        if (SGTonDns.looksLikeDomain(text)) return SGTonDns.fullName(SGTonDns.normalize(text));
        return text.length() > 14 ? text.substring(0, 6) + "\u2026" + text.substring(text.length() - 6) : text;
    }

    public void requestAddressKeyboard() {
        if (addressEdit == null) return;
        addressEdit.requestFocus();
        AndroidUtilities.showKeyboard(addressEdit);
    }

    /** Prefills «На адрес» with a wallet address or name.ton (e.g. «Send» again from a transaction). */
    public void setAddress(String address) {
        if (addressEdit == null || TextUtils.isEmpty(address)) return;
        addressEdit.setText(address.trim());
        addressEdit.setSelection(addressEdit.length());
    }

    public boolean hasAddressText() {
        return addressEdit != null && addressEdit.length() > 0;
    }

    /** The entry point already chose the currency (a GRAM or Stars card): no switch, the currency is fixed. */
    public void lockKind() {
        tabs.setVisibility(GONE);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public WalletSendEnterAmount.AmountInputView getAmountInput() {
        return amountInput;
    }

    /** The flat currency icon (GRAM sign or star) for the drawn flight (SGSendTransition). */
    public View getCurrencyIcon() {
        return amountInput.getFlatIconView();
    }

    public EditTextBoldCursor getAmountEdit() {
        return amountEdit;
    }

    public EditTextBoldCursor getCommentEdit() {
        return commentEdit;
    }

    public int getKind() {
        return kind;
    }

    /** 13.1.1 setSendTransitionProgress: the header fades while the money flies away. */
    public void setSendTransitionProgress(float progress) {
        recipientView.setAlpha(1f - progress);
        if (addressBox != null) addressBox.setAlpha(1f - progress);
        tabs.setAlpha(1f - progress);
    }

    public void requestAmountKeyboard() {
        amountEdit.requestFocus();
        amountEdit.setSelection(amountEdit.length());
        AndroidUtilities.showKeyboard(amountEdit);
    }

    private CharSequence gramText(CharSequence text) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final SpannableString icon = new SpannableString("G");
        final ColoredImageSpan span = new ColoredImageSpan(R.drawable.wallet_gram_small);
        span.setSize(dp(16));
        icon.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb.append(icon).append(" ").append(text);
    }

    /** 13.1.1 loadRecipientAddress: placeholders until wallet.getUserAddresses answers. */
    private void loadRecipientAddress() {
        GramWallet.getInstance(currentAccount).resolveWallet(recipient, true, (a, error) -> {
            if (a != null && a.user_id == recipient.id && !TextUtils.isEmpty(a.address)) {
                recipientAddress = a.address;
                recipientView.setRecipient(recipient, recipientAddress);
                final String forAddress = recipientAddress;
                SGTonDns.getInstance(currentAccount).getDomain(recipientAddress, domain -> {
                    if (domain != null && TextUtils.equals(forAddress, recipientAddress)) recipientView.setDomain(domain);
                });
            }
        });
    }

    public void setKind(int k) {
        setKind(k, false);
    }

    private void setKind(int k, boolean force) {
        if (kind == k && !force) return;
        kind = k;
        final int tab = k == TL_sgcard.KIND_STARS ? TAB_STARS : TAB_GRAM;
        if (tabs.getSelected() != tab) tabs.setSelected(tab, !force);
        amountInput.setStarsMode(k == TL_sgcard.KIND_STARS);
        requestSignature = null;
        updateInputModeViews();
        if (!force) {
            // Keep a value the user typed; drop what the new currency cannot hold.
            String text = amountEdit.getText().toString();
            if (k == TL_sgcard.KIND_STARS && !inFiat) {
                final int dot = Math.max(text.indexOf('.'), text.indexOf(','));
                if (dot >= 0) text = text.substring(0, dot);
                if (text.length() > 7) text = text.substring(0, 7);
            }
            if (!TextUtils.equals(text, amountEdit.getText())) {
                amountInput.setAmountText(text);
                amountEdit.setSelection(amountEdit.length());
            }
        }
        updateBalance();
        updateAmount(!force);
    }

    // ---- rates ----

    private void loadFiatRates() {
        applyRates(ExchangeRates.getCached());
        ExchangeRates.fetch(state -> AndroidUtilities.runOnUIThread(() -> {
            if (applyRates(state)) {
                updateInputModeViews();
                updateAmount(true);
            }
        }));
    }

    /** The fiat currency as in 13.1.1 (the wallet's currency setting), else the device's, else by language. */
    private static String preferredFiat(int account) {
        try {
            final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("gram_wallet", Context.MODE_PRIVATE);
            final String set = prefs.getString("currency", null);
            if (!TextUtils.isEmpty(set) && ExchangeRates.isSupportedCurrency(set)) return set;
        } catch (Exception ignore) {
        }
        final String local = ExchangeRates.getLocalCurrency(account);
        if (local != null) return local;
        final Locale l = LocaleController.getInstance().getCurrentLocale();
        final String lang = l == null ? "" : l.getLanguage();
        switch (lang) {
            case "ru": return "RUB";
            case "uk": return "UAH";
            case "be": return "BYN";
            case "kk": return "KZT";
            case "uz": return "UZS";
            default: return "USD";
        }
    }

    private boolean applyRates(ExchangeRates.State state) {
        final String wanted = preferredFiat(currentAccount);
        BigDecimal usd = null;
        // Server rates (wallet.getCurrencyRates), when the server has them: fiat per USD.
        final CurrenciesController currencies = GramWallet.getInstance(currentAccount).currencies;
        if (TextUtils.equals(currencies.getCurrency(), wanted) && !"USD".equals(wanted)) {
            final double perUsd = currencies.getCurrencyRate();
            if (perUsd > 0 && !Double.isNaN(perUsd) && !Double.isInfinite(perUsd)) {
                usd = BigDecimal.ONE.divide(BigDecimal.valueOf(perUsd), 16, RoundingMode.HALF_UP);
            }
        }
        if (usd == null && state != null) usd = state.getUsdRate(wanted);
        if ("USD".equals(wanted)) usd = BigDecimal.ONE;
        final String code = usd == null || usd.signum() <= 0 ? "USD" : wanted;
        if (usd == null || usd.signum() <= 0) usd = BigDecimal.ONE;
        if (inFiat && !TextUtils.equals(code, fiatCode)) return false; // never change units under the user's fingers
        final boolean changed = !TextUtils.equals(code, fiatCode) || usd.compareTo(usdPerFiat) != 0;
        fiatCode = code;
        usdPerFiat = usd;
        return changed;
    }

    /** USD per GRAM or per Star. */
    private double usdPerUnit(int k) {
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        if (k == TL_sgcard.KIND_GRAM) return mc.config.tonUsdRate.get();
        return mc.starsUsdSellRate1000 / 100000.0;
    }

    /** Fiat per one GRAM or one Star, 0 when unknown. */
    private BigDecimal fiatPerUnit() {
        final double usd = usdPerUnit(kind);
        if (usd <= 0 || Double.isNaN(usd) || Double.isInfinite(usd) || usdPerFiat.signum() <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(usd).divide(usdPerFiat, 12, RoundingMode.HALF_UP);
    }

    private Locale locale() {
        final Locale l = LocaleController.getInstance().getCurrentLocale();
        return l != null ? l : Locale.getDefault();
    }

    private Currency fiatCurrency() {
        try {
            return Currency.getInstance(fiatCode);
        } catch (Exception e) {
            return Currency.getInstance("USD");
        }
    }

    private int fiatFractionDigits() {
        final int d = fiatCurrency().getDefaultFractionDigits();
        return d < 0 ? 2 : d;
    }

    private String fiatSymbol() {
        try {
            return fiatCurrency().getSymbol(locale());
        } catch (Exception e) {
            return fiatCode;
        }
    }

    private boolean fiatSymbolLeft() {
        try {
            final NumberFormat nf = NumberFormat.getCurrencyInstance(locale());
            nf.setCurrency(fiatCurrency());
            final String one = nf.format(1);
            return !one.isEmpty() && !Character.isDigit(one.charAt(0));
        } catch (Exception e) {
            return "USD".equals(fiatCode);
        }
    }

    private String formatFiat(BigDecimal value) {
        try {
            final NumberFormat nf = NumberFormat.getCurrencyInstance(locale());
            nf.setCurrency(fiatCurrency());
            nf.setMinimumFractionDigits(fiatFractionDigits());
            nf.setMaximumFractionDigits(fiatFractionDigits());
            return nf.format(value);
        } catch (Exception e) {
            return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + fiatCode;
        }
    }

    // ---- amount ----

    private void updateInputModeViews() {
        final boolean stars = kind == TL_sgcard.KIND_STARS;
        final int accent = stars ? STARS_COLOR : Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        amountInput.setAllowDecimal(!stars || inFiat);
        if (!inFiat) {
            amountInput.setCurrency(false, stars ? getString(R.string.SgCardCurrencyStars) : getString(R.string.GramCurrency),
                    null, false, false, ",", ".", accent);
        } else {
            final boolean left = fiatSymbolLeft();
            final String symbol = fiatSymbol();
            final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale());
            final String decimal = String.valueOf(symbols.getDecimalSeparator());
            final String grouping = Character.isSpaceChar(symbols.getGroupingSeparator()) ? " " : String.valueOf(symbols.getGroupingSeparator());
            amountInput.setCurrency(true, left ? fiatCode : symbol, symbol, left, false, grouping, decimal, accent);
        }
    }

    private BigDecimal enteredValue() {
        final String text = amountEdit.getText().toString().replace(',', '.').trim();
        if (text.isEmpty() || ".".equals(text)) return BigDecimal.ZERO;
        try {
            final BigDecimal v = new BigDecimal(text);
            return v.signum() < 0 ? BigDecimal.ZERO : v;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /** Entered amount in nanoton (GRAM) or Stars; -1 when not valid. */
    public long getAmount() {
        BigDecimal units = enteredValue();
        if (inFiat) {
            final BigDecimal rate = fiatPerUnit();
            if (rate.signum() <= 0) return -1;
            units = units.divide(rate, 9, RoundingMode.DOWN);
        }
        if (units.signum() <= 0) return -1;
        try {
            if (kind == TL_sgcard.KIND_GRAM) {
                final long nano = units.movePointRight(9).setScale(0, RoundingMode.DOWN).longValueExact();
                return nano > 0 ? nano : -1;
            }
            final long stars = units.setScale(0, RoundingMode.DOWN).longValueExact();
            return stars >= 1 && stars <= SGCardsController.MAX_STARS ? stars : -1;
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    private int maxFractionDigits() {
        if (inFiat) return fiatFractionDigits();
        return kind == TL_sgcard.KIND_GRAM ? 9 : 0;
    }

    /** 13.1.1 amount filter: one separator, a fraction no longer than the unit allows, no "00". */
    private CharSequence filterAmount(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        final String value = dest.subSequence(0, dstart).toString() + source.subSequence(start, end) + dest.subSequence(dend, dest.length());
        boolean hasDecimal = false;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '.' || c == ',') {
                if (hasDecimal) return dest.subSequence(dstart, dend);
                hasDecimal = true;
            } else if (c < '0' || c > '9') {
                return dest.subSequence(dstart, dend);
            }
        }
        final int fraction = maxFractionDigits();
        final int decimalIndex = Math.max(value.indexOf('.'), value.indexOf(','));
        if (decimalIndex >= 0 && (fraction == 0 || value.length() - decimalIndex - 1 > fraction)) {
            return dest.subSequence(dstart, dend);
        }
        final int integerDigits = decimalIndex >= 0 ? decimalIndex : value.length();
        if (integerDigits > (kind == TL_sgcard.KIND_STARS && !inFiat ? 7 : 12)) return dest.subSequence(dstart, dend);
        if (start == end) return null;
        if (dstart == 0 && (value.startsWith(".") || value.startsWith(","))) return "0" + source.subSequence(start, end);
        return value.startsWith("00") ? dest.subSequence(dstart, dend) : null;
    }

    /** 13.1.1 updateAmount. */
    private void updateAmount(boolean animated) {
        final long amount = getAmount();
        final boolean hasAmount = !TextUtils.isEmpty(amountEdit.getText());
        currencyView.setText(chipText(amount), animated);
        showInsufficient(hasAmount && insufficient(), animated);
        updateButton();
        updateSendButtonVisibility(hasAmount, animated);
        updateCenterBottom(animated);
    }

    private CharSequence chipText(long amount) {
        final SpannableStringBuilder sb = new SpannableStringBuilder("≈ ");
        final long a = Math.max(0, amount);
        if (inFiat) {
            if (kind == TL_sgcard.KIND_GRAM) {
                final SpannableString icon = new SpannableString("G");
                final ColoredImageSpan span = new ColoredImageSpan(R.drawable.wallet_gram_small);
                span.recolorDrawable = false;
                icon.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(icon).append(" ");
                final int from = sb.length();
                sb.append(GramWallet.formatGrams(a));
                setFractionSpan(sb, from, '.');
            } else {
                sb.append(StarsIntroActivity.replaceStarsWithPlain("⭐", .8f)).append(" ").append(WalletCardComponent.formatStars(a));
            }
        } else {
            final BigDecimal units = kind == TL_sgcard.KIND_GRAM ? BigDecimal.valueOf(a).movePointLeft(9) : BigDecimal.valueOf(a);
            final int from = sb.length();
            sb.append(formatFiat(units.multiply(fiatPerUnit())));
            setFractionSpan(sb, from, DecimalFormatSymbols.getInstance(locale()).getDecimalSeparator());
        }
        return sb.append(" ").append(exchangeIcon);
    }

    private static void setFractionSpan(SpannableStringBuilder text, int start, char decimalSeparator) {
        final int end = text.length();
        for (int i = start + 1; i + 1 < end; i++) {
            if (text.charAt(i) != decimalSeparator || !Character.isDigit(text.charAt(i - 1)) || !Character.isDigit(text.charAt(i + 1))) continue;
            int fractionEnd = i + 1;
            while (fractionEnd < end && Character.isDigit(text.charAt(fractionEnd))) fractionEnd++;
            text.setSpan(new RelativeSizeSpan(PREVIEW_FRACTION_SCALE), i + 1, fractionEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
    }

    /** 13.1.1 switchInputMode: the entered amount turns into its fiat value and back. */
    private void switchInputMode() {
        final BigDecimal rate = fiatPerUnit();
        if (!inFiat && rate.signum() <= 0) return;
        final long amount = getAmount();
        inFiat = !inFiat;
        updateInputModeViews();
        String value = "";
        if (amount > 0) {
            if (inFiat) {
                final BigDecimal units = kind == TL_sgcard.KIND_GRAM ? BigDecimal.valueOf(amount).movePointLeft(9) : BigDecimal.valueOf(amount);
                value = units.multiply(rate).setScale(fiatFractionDigits(), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
            } else if (kind == TL_sgcard.KIND_GRAM) {
                value = BigDecimal.valueOf(amount).movePointLeft(9).stripTrailingZeros().toPlainString();
            } else {
                value = String.valueOf(amount);
            }
        }
        amountInput.setAmountText(value);
        amountEdit.setSelection(amountEdit.length());
        updateAmount(true);
    }

    /** 13.1.1 updateCenterBottom: "Tap to set the amount" until an amount is being entered. */
    private void updateCenterBottom(boolean animated) {
        final boolean visible = getAmount() > 0 || amountEdit.isFocused();
        if (visible == centerBottomVisible && animated) return;
        centerBottomVisible = visible;
        centerBottom.animate().cancel();
        tapToSetAmount.animate().cancel();
        if (!animated) {
            centerBottom.setVisibility(visible ? VISIBLE : INVISIBLE);
            centerBottom.setAlpha(visible ? 1f : 0f);
            centerBottom.setScaleX(visible ? 1f : .8f);
            centerBottom.setScaleY(visible ? 1f : .8f);
            tapToSetAmount.setVisibility(!visible ? VISIBLE : INVISIBLE);
            tapToSetAmount.setAlpha(!visible ? 1f : 0f);
            tapToSetAmount.setScaleX(!visible ? 1f : .8f);
            tapToSetAmount.setScaleY(!visible ? 1f : .8f);
            return;
        }
        centerBottom.setVisibility(VISIBLE);
        tapToSetAmount.setVisibility(VISIBLE);
        centerBottom.animate().alpha(visible ? 1f : 0f).scaleX(visible ? 1f : .8f).scaleY(visible ? 1f : .8f)
                .withEndAction(() -> centerBottom.setVisibility(visible ? VISIBLE : INVISIBLE))
                .setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        tapToSetAmount.animate().alpha(!visible ? 1f : 0f).scaleX(!visible ? 1f : .8f).scaleY(!visible ? 1f : .8f)
                .withEndAction(() -> tapToSetAmount.setVisibility(!visible ? VISIBLE : INVISIBLE))
                .setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    /** 13.1.1 showError(insufficientFunds). */
    private void showInsufficient(boolean show, boolean animated) {
        if (insufficientShown == show && animated) return;
        insufficientShown = show;
        insufficientFundsView.animate().cancel();
        if (!animated) {
            insufficientFundsView.setAlpha(show ? 1f : 0f);
            insufficientFundsView.setVisibility(show ? VISIBLE : GONE);
            return;
        }
        insufficientFundsView.setVisibility(VISIBLE);
        insufficientFundsView.animate().alpha(show ? 1f : 0f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> insufficientFundsView.setVisibility(show ? VISIBLE : GONE)).start();
    }

    /** 13.1.1 updateSendButtonVisibility: no button until an amount is typed; the balance moves down instead. */
    private void updateSendButtonVisibility(boolean visible, boolean animated) {
        if (animated && sendButtonVisible == visible) return;
        sendButtonVisible = visible;
        sendButton.animate().cancel();
        balanceView.animate().cancel();
        final float alpha = visible ? 1f : 0f;
        final float scale = visible ? 1f : .8f;
        final float textTranslationY = visible ? 0 : dp(60);
        if (!animated) {
            sendButton.setVisibility(visible ? VISIBLE : INVISIBLE);
            sendButton.setAlpha(alpha);
            sendButton.setScaleX(scale);
            sendButton.setScaleY(scale);
            balanceView.setTranslationY(textTranslationY);
            return;
        }
        sendButton.setVisibility(VISIBLE);
        sendButton.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> {
                    if (!sendButtonVisible) sendButton.setVisibility(INVISIBLE);
                }).start();
        balanceView.animate().translationY(textTranslationY).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    // ---- balance and sending ----

    public void updateBalance() {
        if (kind == TL_sgcard.KIND_GRAM) {
            final SGCardsController c = SGCardsController.getInstance(currentAccount);
            setBalance(c.getGramBalance());
            c.loadGramBalance(b -> {
                if (kind == TL_sgcard.KIND_GRAM) setBalance(b);
            });
        } else {
            // StaticGram: getBalance(loaded) actually requests the balance when it is not loaded yet
            // (balanceAvailable() alone left "Loading..." forever); starBalanceUpdated refreshes it too.
            final StarsController s = StarsController.getInstance(currentAccount);
            final TL_stars.StarsAmount b = s.getBalance(() -> {
                if (kind == TL_sgcard.KIND_STARS) updateStarsBalance();
            });
            setBalance(s.balanceAvailable() && b != null ? b.amount : -1);
        }
    }

    private void updateStarsBalance() {
        final StarsController s = StarsController.getInstance(currentAccount);
        if (s.balanceAvailable()) setBalance(s.getBalance().amount);
    }

    private final NotificationCenter.NotificationCenterDelegate starsBalanceObserver = (id, account, args) -> onStarsBalanceUpdated(id, account);

    private void onStarsBalanceUpdated(int id, int account) {
        if (id == NotificationCenter.starBalanceUpdated && account == currentAccount && kind == TL_sgcard.KIND_STARS) {
            updateStarsBalance();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(starsBalanceObserver, NotificationCenter.starBalanceUpdated);
        if (kind == TL_sgcard.KIND_STARS && balance < 0) updateBalance();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(starsBalanceObserver, NotificationCenter.starBalanceUpdated);
    }

    private void setBalance(long value) {
        balance = value;
        if (value < 0) {
            balanceView.setText(getString(R.string.Loading));
        } else if (kind == TL_sgcard.KIND_GRAM) {
            // 13.1.1: "Balance: 0.1049 GRAM" (formatGramsBalance, no grouping)
            balanceView.setText(LocaleController.formatSpannable(R.string.WalletBalanceAmount, GramWallet.formatGramsBalanceWithUnit(value, true)));
        } else {
            // "Balance: 5,558 Stars"
            balanceView.setText(LocaleController.formatString(R.string.WalletBalanceAmount, WalletCardComponent.formatStars(value) + " Stars"));
        }
        if (!TextUtils.isEmpty(amountEdit.getText())) showInsufficient(insufficient(), true);
        updateButton();
    }

    private boolean insufficient() {
        final long amount = getAmount();
        return balance >= 0 && amount > balance;
    }

    private void updateButton() {
        final long amount = getAmount();
        CharSequence text;
        if (kind == TL_sgcard.KIND_GRAM) {
            text = amount <= 0 ? getString(R.string.WalletSendGrams)
                    : LocaleController.formatSpannable(R.string.WalletSendAmount, GramWallet.formatGramsWithUnit(amount));
        } else if (amount <= 0) {
            text = getString(R.string.SgCardSendStarsButton);
        } else {
            // monochrome star tinted with the button text color (the big star next to the amount stays golden)
            text = StarsIntroActivity.replaceStars(formatString(R.string.SgCardSendStars, WalletCardComponent.formatStars(amount)), .85f);
        }
        sendButton.setText(text, true);
        sendButton.setEnabled(amount > 0 && !insufficient() && !sending && (!addressMode || addressTarget() != null));
    }

    private void send() {
        final long amount = getAmount();
        if (sending) return;
        if (amount <= 0) {
            AndroidUtilities.shakeViewSpring(amountEdit, shakeDp = -shakeDp);
            return;
        }
        if (insufficient()) {
            AndroidUtilities.shakeViewSpring(insufficientFundsView, shakeDp = -shakeDp);
            return;
        }
        // The server refuses GRAM sends below 0.01 (they would read "0 GRAM" in the chat card).
        if (kind == TL_sgcard.KIND_GRAM && amount < SGCardsController.MIN_GRAM_SEND_NANO) {
            AndroidUtilities.shakeViewSpring(amountEdit, shakeDp = -shakeDp);
            if (delegate != null) delegate.onError(LocaleController.getString(R.string.SgCardErrorGramMin));
            return;
        }
        final String target = addressMode ? addressTarget() : null;
        if (addressMode && target == null) {
            AndroidUtilities.shakeViewSpring(addressBox, shakeDp = -shakeDp);
            return;
        }
        final String text = commentEdit.getText().toString().trim();
        // Same transfer as the failed attempt: reuse its key, so a lost answer never pays twice.
        final String signature = kind + ":" + amount + ":" + text + (target != null ? ":" + target : "");
        if (requestKey == null || !signature.equals(requestSignature)) {
            requestKey = SGCardsController.newRequestKey();
            requestSignature = signature;
        }
        sending = true;
        sendButton.setLoading(true);
        final int sentKind = kind;
        final int sinceDate = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        final SGCardsController c = SGCardsController.getInstance(currentAccount);
        if (addressMode) {
            c.sendGramToAddress(target, amount, text, requestKey, (res, err) -> done(sentKind, amount, sinceDate, res != null, res != null && res.replayed, err));
        } else if (kind == TL_sgcard.KIND_GRAM) {
            c.sendGram(recipient.id, amount, text, requestKey, (res, err) -> done(sentKind, amount, sinceDate, res != null, res != null && res.replayed, err));
        } else {
            // sgcard.sendStars carries the comment (sgcard.messageActionStarsTransfer.comment)
            c.sendStars(recipient.id, amount, text, requestKey, (res, err) -> done(sentKind, amount, sinceDate, res != null, res != null && res.replayed, err));
        }
    }

    private void done(int sentKind, long amount, int sinceDate, boolean ok, boolean replayed, TLRPC.TL_error err) {
        sending = false;
        if (ok) {
            // The spinner stays until the sheet flies away.
            requestKey = null;
            requestSignature = null;
            sendButton.setEnabled(false);
            if (sentKind == TL_sgcard.KIND_STARS) StarsController.getInstance(currentAccount).invalidateBalance();
            if (delegate != null) delegate.onSent(sentKind, amount, replayed, sinceDate);
        } else {
            sendButton.setLoading(false);
            updateButton();
            if (delegate != null) delegate.onError(SGCardsController.errorText(err));
        }
    }

    /** "5 GRAM" / "⭐ 100" for bulletins. */
    public static CharSequence formatAmount(int kind, long amount) {
        if (kind == TL_sgcard.KIND_GRAM) return GramWallet.formatGramsWithUnit(amount);
        return StarsIntroActivity.replaceStarsWithPlain("⭐" + WalletCardComponent.formatStars(amount), .85f);
    }
}
