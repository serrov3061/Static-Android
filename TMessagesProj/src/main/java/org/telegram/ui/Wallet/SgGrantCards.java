package org.telegram.ui.Wallet;

import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * StaticGram: every way Stars or GRAM reach a user is drawn as the wallet card of
 * {@link WalletActionLayout} (golden Stars card / GRAM card), not only the user-to-user transfers
 * (sgcard.messageActionStarsTransfer, messageActionGramTransfer).
 *
 * <ul>
 *   <li>messageActionGiftStars (777000 gifts: partner/admin grants, bot withdrawals; Stars bought
 *       for a friend) and messageActionPrizeStars (giveaway prizes) -> golden Stars card;</li>
 *   <li>messageActionGiftTon (777000 GRAM top-ups/admin grants, bot GRAM withdrawals, legacy
 *       "d:" transfers) -> GRAM card.</li>
 * </ul>
 *
 * The card layout only knows the two transfer actions, so a grant is projected onto a synthetic
 * transfer action (amount, transaction id; no comment/address: the TL grant actions carry none).
 * See docs/stars-gram-cards.md.
 */
public final class SgGrantCards {

    /**
     * gramsrv marks a captioned grant that it sends as a transfer action
     * (sgcard.messageActionStarsTransfer / messageActionGramTransfer, the only actions with a
     * text: the caption is their comment) with this transaction id prefix
     * (tlprofile.GrantTransferIDPrefix). Clients without the transfer feature get the plain
     * messageActionGiftStars / messageActionGiftTon card instead.
     */
    public static final String GRANT_TRANSFER_ID_PREFIX = "sggrant:";

    private SgGrantCards() {
    }

    public static boolean isGrantTransferId(String transactionId) {
        return transactionId != null && transactionId.startsWith(GRANT_TRANSFER_ID_PREFIX);
    }

    /** The ledger id without the grant marker (receipts show this one). */
    public static String stripGrantTransferId(String transactionId) {
        return isGrantTransferId(transactionId) ? transactionId.substring(GRANT_TRANSFER_ID_PREFIX.length()) : transactionId;
    }

    /** A captioned grant delivered as a transfer action (see {@link #GRANT_TRANSFER_ID_PREFIX}). */
    public static boolean isGrantTransfer(TLRPC.MessageAction action) {
        if (action instanceof TLRPC.TL_sgcard_messageActionStarsTransfer) {
            return isGrantTransferId(((TLRPC.TL_sgcard_messageActionStarsTransfer) action).transaction_id);
        }
        if (action instanceof TLRPC.TL_messageActionGramTransfer) {
            return isGrantTransferId(((TLRPC.TL_messageActionGramTransfer) action).transaction_id);
        }
        return false;
    }

    public static boolean isStarsGrant(TLRPC.MessageAction action) {
        if (action instanceof TLRPC.TL_messageActionGiftStars) {
            return ((TLRPC.TL_messageActionGiftStars) action).stars > 0;
        }
        if (action instanceof TLRPC.TL_messageActionPrizeStars) {
            return ((TLRPC.TL_messageActionPrizeStars) action).stars > 0;
        }
        return false;
    }

    public static boolean isGramGrant(TLRPC.MessageAction action) {
        return action instanceof TLRPC.TL_messageActionGiftTon && gramNanoton(action) > 0;
    }

    public static boolean isGrant(TLRPC.MessageAction action) {
        return isStarsGrant(action) || isGramGrant(action);
    }

    public static boolean isGrant(MessageObject msg) {
        return msg != null && msg.messageOwner != null && isGrant(msg.messageOwner.action);
    }

    public static long stars(TLRPC.MessageAction action) {
        if (action instanceof TLRPC.TL_messageActionGiftStars) {
            return Math.abs(((TLRPC.TL_messageActionGiftStars) action).stars);
        }
        if (action instanceof TLRPC.TL_messageActionPrizeStars) {
            return Math.abs(((TLRPC.TL_messageActionPrizeStars) action).stars);
        }
        return 0;
    }

    /** GRAM amount of messageActionGiftTon in nanoton: crypto_amount (gramsrv puts the fiat pair in amount). */
    public static long gramNanoton(TLRPC.MessageAction action) {
        if (!(action instanceof TLRPC.TL_messageActionGiftTon)) return 0;
        if (action.cryptoAmount != 0) return Math.abs(action.cryptoAmount);
        // Without a fiat snapshot gramsrv sends currency "TON" and the nanoton in amount.
        if (action.currency != null && ("TON".equalsIgnoreCase(action.currency) || "GRAM".equalsIgnoreCase(action.currency))) {
            return Math.abs(action.amount);
        }
        return 0;
    }

    private static String transactionId(TLRPC.MessageAction action) {
        String id = null;
        if (action instanceof TLRPC.TL_messageActionGiftStars) {
            id = ((TLRPC.TL_messageActionGiftStars) action).transaction_id;
        } else if (action instanceof TLRPC.TL_messageActionPrizeStars) {
            id = ((TLRPC.TL_messageActionPrizeStars) action).transaction_id;
        } else if (action instanceof TLRPC.TL_messageActionGiftTon) {
            id = ((TLRPC.TL_messageActionGiftTon) action).transaction_id;
        }
        return TextUtils.isEmpty(id) ? "0" : id;
    }

    /** The golden-card projection of a Stars grant (null when the message is not one). */
    public static TLRPC.TL_sgcard_messageActionStarsTransfer toStarsTransfer(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || !isStarsGrant(msg.messageOwner.action)) return null;
        final TLRPC.TL_sgcard_messageActionStarsTransfer t = new TLRPC.TL_sgcard_messageActionStarsTransfer();
        t.outgoing = msg.isOutOwner();
        t.stars = stars(msg.messageOwner.action);
        t.transaction_id = transactionId(msg.messageOwner.action);
        return t;
    }

    /** The GRAM-card projection of a GRAM grant (null when the message is not one). */
    public static TLRPC.TL_messageActionGramTransfer toGramTransfer(MessageObject msg) {
        if (msg == null || msg.messageOwner == null || !isGramGrant(msg.messageOwner.action)) return null;
        final TLRPC.TL_messageActionGramTransfer t = new TLRPC.TL_messageActionGramTransfer();
        t.amount = gramNanoton(msg.messageOwner.action);
        t.peer_address = "";
        t.transaction_id = transactionId(msg.messageOwner.action);
        return t;
    }

    /**
     * Name printed on the card: the counterpart of the chat (the sender of an incoming grant, the
     * recipient of an outgoing one), the organizer for a giveaway prize. Service senders (777000)
     * keep their own name ("StaticGram") instead of "Unknown user": a grant comes from them.
     */
    public static String cardName(int currentAccount, MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return null;
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        if (msg.messageOwner.action instanceof TLRPC.TL_messageActionPrizeStars) {
            final TLRPC.Peer boost = ((TLRPC.TL_messageActionPrizeStars) msg.messageOwner.action).boost_peer;
            if (boost != null) {
                final String name = DialogObject.getName(mc.getUserOrChat(DialogObject.getPeerDialogId(boost)));
                if (!TextUtils.isEmpty(name)) return name;
            }
        }
        final long dialogId = msg.messageOwner.peer_id == null ? 0 : DialogObject.getPeerDialogId(msg.messageOwner.peer_id);
        if (dialogId == 0) return null;
        final TLRPC.User user = dialogId > 0 ? mc.getUser(dialogId) : null;
        if (user != null && UserObject.isService(user.id) && TextUtils.isEmpty(UserObject.getUserName(user))) {
            return "StaticGram";
        }
        return DialogObject.getName(mc.getUserOrChat(dialogId));
    }
}
