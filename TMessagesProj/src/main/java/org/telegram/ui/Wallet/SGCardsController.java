package org.telegram.ui.Wallet;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_gramwallet;
import org.telegram.tgnet.tl.TL_sgcard;

import java.security.SecureRandom;
import java.util.ArrayList;

/**
 * StaticGram wallet cards (sgcard.*) and money transfers (gramwallet.send, sgcard.sendStars).
 * Cards are cached per user in memory; {@link NotificationCenter#sgCardsUpdated} is posted
 * with the user id whenever a user's cards change.
 */
public class SGCardsController {

    public static final String BOT_URL = "https://t.me/StaticGramApp?start=wcard";
    public static final long NANO = 1_000_000_000L;
    public static final long MAX_STARS = 1_000_000L;
    public static final int MAX_COMMENT = 256;

    private static final long RELOAD_INTERVAL_MS = 30_000;

    private static final SGCardsController[] instances = new SGCardsController[UserConfig.MAX_ACCOUNT_COUNT];

    public static SGCardsController getInstance(int account) {
        SGCardsController instance = instances[account];
        if (instance == null) {
            synchronized (SGCardsController.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new SGCardsController(account);
                }
            }
        }
        return instance;
    }

    private final int currentAccount;
    private final LongSparseArray<TL_sgcard.TL_cards> cards = new LongSparseArray<>();
    private final LongSparseArray<Long> lastLoad = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<Utilities.Callback<TL_sgcard.TL_cards>>> loading = new LongSparseArray<>();
    private long gramBalance = -1;

    private SGCardsController(int account) {
        currentAccount = account;
    }

    private long selfId() {
        return UserConfig.getInstance(currentAccount).getClientUserId();
    }

    public boolean isSelf(long userId) {
        return userId == selfId();
    }

    /** Cached cards of the user, or null when never loaded. */
    public TL_sgcard.TL_cards getCached(long userId) {
        return cards.get(userId);
    }

    /** Whether the user has cards to show (since 07.11 everyone has the two default cards). */
    public boolean hasCards(long userId) {
        final TL_sgcard.TL_cards c = cards.get(userId);
        return c != null && !c.cards.isEmpty();
    }

    /** Whether the cached answer says the user bought card customization. */
    public boolean isPurchased(long userId) {
        final TL_sgcard.TL_cards c = cards.get(userId);
        return c != null && c.purchased && !c.cards.isEmpty();
    }

    public void load(long userId) {
        load(userId, false, null);
    }

    /** Loads cards of the user (cached answer is returned at once when fresh and not forced). */
    public void load(long userId, boolean force, Utilities.Callback<TL_sgcard.TL_cards> done) {
        final TL_sgcard.TL_cards cached = cards.get(userId);
        final Long last = lastLoad.get(userId);
        if (!force && cached != null && last != null && SystemClock.elapsedRealtime() - last < RELOAD_INTERVAL_MS) {
            if (done != null) done.run(cached);
            return;
        }
        ArrayList<Utilities.Callback<TL_sgcard.TL_cards>> waiting = loading.get(userId);
        if (waiting != null) {
            if (done != null) waiting.add(done);
            return;
        }
        final TLRPC.InputUser inputUser = isSelf(userId) ? new TLRPC.TL_inputUserSelf() : MessagesController.getInstance(currentAccount).getInputUser(userId);
        if (inputUser == null || inputUser instanceof TLRPC.TL_inputUserEmpty) {
            if (done != null) done.run(cached);
            return;
        }
        waiting = new ArrayList<>();
        if (done != null) waiting.add(done);
        loading.put(userId, waiting);
        final TL_sgcard.TL_getCards req = new TL_sgcard.TL_getCards();
        req.user_id = inputUser;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            final ArrayList<Utilities.Callback<TL_sgcard.TL_cards>> callbacks = loading.get(userId);
            loading.remove(userId);
            TL_sgcard.TL_cards result = cards.get(userId);
            if (res != null) {
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                cards.put(userId, res);
                lastLoad.put(userId, SystemClock.elapsedRealtime());
                result = res;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.sgCardsUpdated, userId);
            }
            if (callbacks != null) {
                for (Utilities.Callback<TL_sgcard.TL_cards> cb : callbacks) cb.run(result);
            }
        });
    }

    /** sgcard.saveCard for the own card of kind; the cache is updated from the answer. */
    public void save(int kind, String backgroundSlug, ArrayList<TL_sgcard.TL_inputSticker> stickers, Utilities.Callback2<TL_sgcard.TL_card, TLRPC.TL_error> done) {
        final TL_sgcard.TL_saveCard req = new TL_sgcard.TL_saveCard();
        req.kind = kind;
        req.background_slug = TextUtils.isEmpty(backgroundSlug) ? null : backgroundSlug;
        req.stickers = stickers;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                final long self = selfId();
                final TL_sgcard.TL_cards c = cards.get(self);
                if (c != null) {
                    for (int i = 0; i < c.cards.size(); i++) {
                        if (c.cards.get(i).kind == res.kind) {
                            c.cards.set(i, res);
                        }
                    }
                }
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.sgCardsUpdated, self);
            }
            if (done != null) done.run(res, err);
        });
    }

    /** A new idempotency key for one user action (sgcard.sendStars / gramwallet.send). */
    public static String newRequestKey() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        final StringBuilder sb = new StringBuilder("sg");
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public void sendStars(long userId, long amount, String comment, String requestKey, Utilities.Callback2<TL_sgcard.TL_starsResult, TLRPC.TL_error> done) {
        final TL_sgcard.TL_sendStars req = new TL_sgcard.TL_sendStars();
        req.user_id = MessagesController.getInstance(currentAccount).getInputUser(userId);
        req.amount = amount;
        req.comment = comment == null ? "" : comment.trim();
        req.request_key = requestKey;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) refreshBalances(userId);
            if (done != null) done.run(res, err);
        });
    }

    /** gramwallet.send: amount in nanoton; the server posts a GRAM gift card into the chat. */
    public void sendGram(long userId, long amountNano, String comment, String requestKey, Utilities.Callback2<TL_gramwallet.TL_result, TLRPC.TL_error> done) {
        final TL_gramwallet.TL_send req = new TL_gramwallet.TL_send();
        req.user_id = userId;
        req.amount = amountNano;
        req.comment = comment == null ? "" : comment.trim();
        req.request_key = requestKey;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                refreshBalances(userId);
            }
            if (done != null) done.run(res, err);
        });
    }

    /** «На адрес»: GRAM to a wallet address (gramwallet.sendToAddress), the same result as {@link #sendGram}. */
    public void sendGramToAddress(String address, long amountNano, String comment, String requestKey, Utilities.Callback2<TL_gramwallet.TL_result, TLRPC.TL_error> done) {
        final TL_gramwallet.TL_sendToAddress req = new TL_gramwallet.TL_sendToAddress();
        req.address = address == null ? "" : address.trim();
        req.amount = amountNano;
        req.comment = comment == null ? "" : comment.trim();
        req.request_key = requestKey;
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                refreshBalances(selfId());
            }
            if (done != null) done.run(res, err);
        });
    }

    /** Last known spendable GRAM ("Деньги") balance in nanoton, -1 when unknown. */
    public long getGramBalance() {
        return gramBalance;
    }

    public void loadGramBalance(Utilities.Callback<Long> done) {
        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(new TL_gramwallet.TL_getBalance(), AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) gramBalance = res.wallet + res.gram; // send tops the wallet up from GRAM
            if (done != null) done.run(gramBalance);
        });
    }

    private void refreshBalances(long peerId) {
        final long self = selfId();
        if (cards.get(self) != null) load(self, true, null);
        if (peerId != self && cards.get(peerId) != null) load(peerId, true, null);
    }

    /** Smallest GRAM send the server accepts (0.01 GRAM), in nanoton. */
    public static final long MIN_GRAM_SEND_NANO = 10_000_000L;

    /** Human readable text for an sgcard / gramwallet error. */
    public static String errorText(TLRPC.TL_error error) {
        if (error == null || error.text == null) return LocaleController.getString(R.string.SgCardErrorUnknown);
        final String priced = transferCommentPriceText(error.text);
        if (priced != null) return priced;
        switch (error.text) {
            case "MONEY_TRANSFER_FORBIDDEN":
                return LocaleController.getString(R.string.SgMoneyTransferForbidden);
            case "BALANCE_TOO_LOW":
                return LocaleController.getString(R.string.SgCardErrorBalance);
            case "WALLET_CARD_NOT_PURCHASED":
                return LocaleController.getString(R.string.SgCardErrorNotPurchased);
            case "WALLET_CARD_STICKERS_TOO_MUCH":
                return LocaleController.formatString(R.string.SgCardErrorTooMany, TL_sgcard.MAX_STICKERS);
            case "WALLET_CARD_STICKER_ANIMATED":
                return LocaleController.getString(R.string.SgCardErrorAnimated);
            case "WALLET_CARD_GIFT_INVALID":
                return LocaleController.getString(R.string.SgCardErrorGift);
            case "USER_GIFT_UNAVAILABLE":
            case "PEER_ID_INVALID":
            case "USER_ID_INVALID":
                return LocaleController.getString(R.string.SgCardErrorRecipient);
            case "USER_IS_BOT":
                return LocaleController.getString(R.string.SgCardErrorBot);
            case "STARS_AMOUNT_INVALID":
                return LocaleController.formatString(R.string.SgCardErrorStarsAmount, MAX_STARS);
            case "WALLET_CARD_COMMENT_TOO_LONG":
                return LocaleController.formatString(R.string.SgCardErrorComment, MAX_COMMENT);
            case "INPUT_REQUEST_INVALID":
                return LocaleController.getString(R.string.SgCardErrorAmount);
            case "GRAM_AMOUNT_TOO_SMALL":
                return LocaleController.getString(R.string.SgCardErrorGramMin);
            case "GRAM_EXTERNAL_ADDRESS_UNSUPPORTED":
                return LocaleController.getString(R.string.SgMoneyExternalUnsupported);
            case "ADDRESS_INVALID":
            case "WALLET_ADDRESS_INVALID":
            case "GRAM_ADDRESS_INVALID":
            case "TONDNS_ADDRESS_INVALID":
                return LocaleController.getString(R.string.SgNftInvalidAddress);
            default:
                return LocaleController.formatString(R.string.SgCardErrorCode, error.text);
        }
    }

    /**
     * STARS_TRANSFER_COMMENT_PRICE_&lt;stars&gt; / GRAM_TRANSFER_COMMENT_PRICE_&lt;nanoton&gt;: the
     * recipient charges for messages, so a transfer with a comment must be worth at least the
     * paid-message price. Null for any other error.
     */
    public static String transferCommentPriceText(String text) {
        if (text == null) return null;
        try {
            if (text.startsWith("STARS_TRANSFER_COMMENT_PRICE_")) {
                final long stars = Long.parseLong(text.substring("STARS_TRANSFER_COMMENT_PRICE_".length()));
                return LocaleController.formatString(R.string.SgTransferCommentPriceStars, WalletCardComponent.formatStars(stars));
            }
            if (text.startsWith("GRAM_TRANSFER_COMMENT_PRICE_")) {
                long nano = Long.parseLong(text.substring("GRAM_TRANSFER_COMMENT_PRICE_".length()));
                nano = (nano + 9_999_999L) / 10_000_000L * 10_000_000L; // shown with 2 decimals: never round below the minimum
                return LocaleController.formatString(R.string.SgTransferCommentPriceGram, WalletCardComponent.formatGrams(nano));
            }
        } catch (NumberFormatException ignore) {
        }
        return null;
    }

    /** Parses a decimal GRAM amount ("1.5", "1,5") into nanoton; -1 when invalid. */
    public static long parseGram(String text) {
        if (TextUtils.isEmpty(text)) return -1;
        try {
            final java.math.BigDecimal v = new java.math.BigDecimal(text.trim().replace(',', '.').replace(" ", ""));
            if (v.signum() <= 0 || v.scale() > 9) return -1;
            return v.movePointRight(9).longValueExact();
        } catch (Exception e) {
            return -1;
        }
    }
}
