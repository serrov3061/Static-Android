package org.telegram.ui.Wallet;

import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_wallet;

/**
 * StaticGram: GRAM receipts open the upstream wallet transaction sheet
 * ({@link WalletActivity#openTransactionSheet}) instead of the Stars one. On gramsrv the wallet
 * transaction list is often empty ({@link GramWallet#findTransaction} returns null), so the
 * transaction is built from the data the message action / money history row already carries.
 */
public final class SgGramReceipt {

    /** gramsrv deposit/transfer ids of old messageActionGiftTon look like "d:...". */
    public static final String DEPOSIT_PREFIX = "d:";

    private SgGramReceipt() {
    }

    public static boolean isGramGiftTon(TLRPC.MessageAction action) {
        return action instanceof TLRPC.TL_messageActionGiftTon
                && ((TLRPC.TL_messageActionGiftTon) action).transaction_id != null
                && ((TLRPC.TL_messageActionGiftTon) action).transaction_id.startsWith(DEPOSIT_PREFIX);
    }

    private static TL_wallet.WalletTransactionPeer peerOf(long dialogId, String address) {
        final TL_wallet.WalletTransactionPeer peer;
        if (dialogId < 0) {
            peer = new TL_wallet.walletTransactionPeerChannel();
            peer.channel_id = -dialogId;
        } else if (dialogId > 0) {
            peer = new TL_wallet.walletTransactionPeerUser();
            peer.user_id = dialogId;
        } else {
            peer = new TL_wallet.walletTransactionPeerAddress();
        }
        peer.address = address == null ? "" : address;
        return peer;
    }

    /** Old-style GRAM transfer message (messageActionGiftTon with a "d:" transaction id). */
    public static TL_wallet.walletTransaction fromGiftTon(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || !(msg.messageOwner.action instanceof TLRPC.TL_messageActionGiftTon)) {
            return null;
        }
        final TLRPC.TL_messageActionGiftTon action = (TLRPC.TL_messageActionGiftTon) msg.messageOwner.action;
        final TL_wallet.walletTransaction t = new TL_wallet.walletTransaction();
        t.id = action.transaction_id;
        t.incoming = !msg.isOutOwner();
        t.amount = Math.abs(action.cryptoAmount != 0 ? action.cryptoAmount : action.amount);
        t.date = msg.messageOwner.date;
        t.peer = peerOf(msg.getDialogId(), null);
        t.preview = true;
        return t;
    }

    /** gramsrv title of the ledger rows written by a user-to-user Stars transfer (sgcard.sendStars). */
    public static final String STARS_TRANSFER_TITLE = "Stars gift";
    /** gramsrv (07.11+) title of a Stars transfer row, sent with gift unset. */
    public static final String STARS_TRANSFER_DISPLAY_TITLE = "Перевод";

    /**
     * Stars transfer row of the money history: gift ledger row against a user without a star gift
     * (gramsrv writes it for sgcard.sendStars with title "Stars gift" and the comment, or "N Stars",
     * as description). Returns the transaction for {@link WalletActivity#openStarsTransferSheet}.
     */
    public static TL_wallet.walletTransaction fromStarsTransferTransaction(TL_stars.StarsTransaction st) {
        if (st == null || st.amount == null || st.amount instanceof TL_stars.TL_starsTonAmount
                || st.stargift != null
                // gramsrv projects every transfer (old rows too) as a non-gift row titled "Перевод";
                // a "Stars gift" gift row is a purchased Stars gift, not a transfer.
                || st.gift || !STARS_TRANSFER_DISPLAY_TITLE.equals(st.title)
                || !(st.peer instanceof TL_stars.TL_starsTransactionPeer) || st.peer.peer == null) {
            return null;
        }
        final long dialogId = DialogObject.getPeerDialogId(st.peer.peer);
        if (dialogId <= 0) {
            return null;
        }
        final TL_wallet.walletTransaction t = new TL_wallet.walletTransaction();
        t.id = st.id;
        t.incoming = st.amount.amount > 0;
        t.amount = Math.abs(st.amount.amount);
        t.date = st.date;
        t.pending = st.pending;
        t.failed = st.failed;
        t.peer = peerOf(dialogId, null);
        if (!TextUtils.isEmpty(st.description) && !st.description.equals(t.amount + " Stars")) {
            t.comment = st.description;
        }
        return t;
    }

    /** GRAM row of the money history (StarsTransaction with a TON amount). */
    public static TL_wallet.walletTransaction fromStarsTransaction(TL_stars.StarsTransaction st) {
        if (st == null || !(st.amount instanceof TL_stars.TL_starsTonAmount)) {
            return null;
        }
        final TL_wallet.walletTransaction t = new TL_wallet.walletTransaction();
        t.id = st.id;
        t.incoming = st.amount.amount > 0;
        t.amount = Math.abs(st.amount.amount);
        t.date = st.date;
        t.pending = st.pending;
        t.failed = st.failed;
        long dialogId = 0;
        if (st.peer instanceof TL_stars.TL_starsTransactionPeer && st.peer.peer != null) {
            dialogId = DialogObject.getPeerDialogId(st.peer.peer);
        }
        t.peer = peerOf(dialogId, null);
        if (!TextUtils.isEmpty(st.description)) {
            t.comment = st.description;
        }
        t.preview = true;
        return t;
    }
}
