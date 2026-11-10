package org.telegram.ui.Wallet;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ChatActivity;

/**
 * Finds the chat message of a GRAM transfer just sent with gramwallet.send, so that Telegram
 * 13.1.1's WalletChatSendTransition can fly the diamond onto it. gramwallet.send answers with
 * the balance only, so the outgoing messageActionGramTransfer of that amount, not older than the
 * request, is waited for in the open chat; the transition matches it by localMessageId.
 */
public final class SGSendLanding {

    private static final long TIMEOUT_MS = 2500;
    private static final long POLL_MS = 50;

    private SGSendLanding() {
    }

    /** Calls {@code done} with the landing transaction, or with null when the message did not show up in time. */
    public static void await(ChatActivity chat, long amountNano, int sinceDate, Utilities.Callback<TL_wallet.walletTransaction> done) {
        final long started = SystemClock.elapsedRealtime();
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            final TL_wallet.walletTransaction tx = find(chat, amountNano, sinceDate);
            final View view = chat.getFragmentView();
            if (tx != null && view != null && view.isAttachedToWindow() && view.getWidth() > 0) {
                done.run(tx);
            } else if (SystemClock.elapsedRealtime() - started > TIMEOUT_MS || chat.isFinished || chat.isRemovingFromStack()) {
                done.run(null);
            } else {
                AndroidUtilities.runOnUIThread(poll[0], POLL_MS);
            }
        };
        poll[0].run();
    }

    private static TL_wallet.walletTransaction find(ChatActivity chat, long amountNano, int sinceDate) {
        if (chat == null || chat.messages == null) return null;
        for (int i = 0; i < chat.messages.size(); i++) {
            final MessageObject m = chat.messages.get(i);
            if (m == null || m.messageOwner == null || !m.isOutOwner()
                    || !(m.messageOwner.action instanceof TLRPC.TL_messageActionGramTransfer)) continue;
            final TLRPC.TL_messageActionGramTransfer action = (TLRPC.TL_messageActionGramTransfer) m.messageOwner.action;
            if (action.amount != amountNano || m.messageOwner.date < sinceDate - 30) continue;
            final TL_wallet.walletTransaction tx = new TL_wallet.walletTransaction();
            tx.localMessageId = m.getId();
            tx.id = action.transaction_id;
            tx.amount = amountNano;
            tx.comment = action.comment;
            return tx;
        }
        return null;
    }

    /**
     * 13.1.1 WalletChatSendTransition for a sheet in its own window (the money screen's send sheet):
     * {@code root} is the sheet's full-window container, {@code outgoing} the sheet panel.
     */
    public static WalletChatSendTransition start(ViewGroup root, View outgoing, WalletDiamondView source, ChatActivity chat,
                                                 TL_wallet.walletTransaction tx, Runnable completion) {
        return new WalletChatSendTransition(root, outgoing, source, chat, tx, completion);
    }
}
