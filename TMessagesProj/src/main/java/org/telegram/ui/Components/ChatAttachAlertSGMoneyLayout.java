package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;

import android.animation.AnimatorSet;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Wallet.SGSendMoneyView;
import org.telegram.ui.Wallet.SGSendLanding;
import org.telegram.ui.Wallet.SGSendTransition;
import org.telegram.ui.Wallet.WalletChatSendTransition;

/**
 * StaticGram "Деньги" tab of the attach menu (Telegram 13.1.1 ChatAttachAlertGramLayout):
 * sends GRAM (gramwallet.send) or Stars (sgcard.sendStars) to the private chat peer.
 */
public class ChatAttachAlertSGMoneyLayout extends ChatAttachAlert.AttachAlertLayout {

    private final RecyclerListView listView;
    private final SGSendMoneyView form;
    private final TLRPC.User recipient;
    private TL_wallet.walletTransaction queuedTransaction;
    private WalletChatSendTransition sendTransition;

    public static boolean isRecipient(int account, TLRPC.User user) {
        return user != null
                && !UserObject.isUserSelf(user)
                && !user.bot
                && !UserObject.isDeleted(user)
                && !UserObject.isReplyUser(user)
                && !UserObject.isService(user.id);
    }

    public ChatAttachAlertSGMoneyLayout(ChatAttachAlert alert, Context context, TLRPC.User recipient, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, resourcesProvider);
        this.recipient = recipient;
        form = new SGSendMoneyView(context, alert.currentAccount, recipient, TL_sgcard.KIND_GRAM, resourcesProvider);
        // The attach sheet's window is not focusable by default, so tapping the amount or the
        // comment never brought up the keyboard. Make it focusable on the first tap.
        final android.view.View.OnTouchListener focusOnTap = (v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                parentAlert.setFocusable(true);
                v.requestFocus();
                AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(v), 60);
            }
            return false;
        };
        form.getAmountEdit().setOnTouchListener(focusOnTap);
        form.getCommentEdit().setOnTouchListener(focusOnTap);
        form.setDelegate(new SGSendMoneyView.Delegate() {
            @Override
            public void onSent(int kind, long amount, boolean replayed, int sinceDate) {
                final ChatActivity chat = recipientChat();
                if (kind == TL_sgcard.KIND_GRAM && chat != null && form.getAmountInput().getDiamondView().canAnimateFlight()) {
                    // 13.1.1 WalletChatSendTransition: the diamond flies onto the new transfer in the chat
                    // while the sheet drops away (ChatAttachAlert.onCustomCloseAnimation).
                    final boolean[] handled = new boolean[1];
                    SGSendLanding.await(chat, amount, sinceDate, tx -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        try {
                            if (tx != null && !parentAlert.isDismissed()) {
                                queuedTransaction = tx;
                                parentAlert.dismiss(true);
                            } else {
                                flyDrawn(kind, amount);
                            }
                        } catch (Throwable e) {
                            FileLog.e(e);
                            closeAfterSend(kind, amount);
                        }
                    });
                    // The money is already sent: never leave the sheet spinning if the flight cannot start.
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!parentAlert.isDismissed()) {
                            handled[0] = true;
                            closeAfterSend(kind, amount);
                        }
                    }, 4000);
                    return;
                }
                flyDrawn(kind, amount);
            }

            @Override
            public void onOpenRecipient() {
                parentAlert.dismiss(true);
            }

            private void flyDrawn(int kind, long amount) {
                AndroidUtilities.hideKeyboard(form);
                // The money (GRAM sign or star) flies from the sheet into the chat.
                SGSendTransition.start(parentAlert.getContainer(), parentAlert.getSheetContainer(), form.getCurrencyIcon(),
                        parentAlert.baseFragment, kind, () -> {
                            parentAlert.dismiss(true);
                            showSentBulletin(kind, amount);
                        });
            }

            private void closeAfterSend(int kind, long amount) {
                queuedTransaction = null;
                AndroidUtilities.hideKeyboard(form);
                parentAlert.dismiss(true);
                showSentBulletin(kind, amount);
            }

            private void showSentBulletin(int kind, long amount) {
                final String raw = formatString(R.string.SgCardSentTo, "\u0001", UserObject.getFirstName(recipient));
                final SpannableStringBuilder sb = new SpannableStringBuilder(raw);
                final int at = raw.indexOf('\u0001');
                if (at >= 0) sb.replace(at, at + 1, SGSendMoneyView.formatAmount(kind, amount));
                if (parentAlert.baseFragment != null) {
                    BulletinFactory.of(parentAlert.baseFragment).createSimpleBulletin(kind == TL_sgcard.KIND_STARS ? R.raw.stars_send : R.raw.contact_check, sb).show();
                }
            }

            @Override
            public void onError(String text) {
                BulletinFactory.of(parentAlert.sizeNotifierFrameLayout, resourcesProvider).createErrorBulletin(text).show();
            }
        });

        listView = new RecyclerListView(context, resourcesProvider);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                form.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(form);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            }

            @Override
            public int getItemCount() {
                return 1;
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                parentAlert.updateLayout(ChatAttachAlertSGMoneyLayout.this, true, dy);
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private ChatActivity recipientChat() {
        if (!(parentAlert.baseFragment instanceof ChatActivity)) return null;
        final ChatActivity chat = (ChatActivity) parentAlert.baseFragment;
        if (chat.getCurrentAccount() != parentAlert.currentAccount || chat.getDialogId() != recipient.id
                || chat.getChatMode() != 0 || chat.getFragmentView() == null || !chat.getFragmentView().isAttachedToWindow()) return null;
        return chat;
    }

    /** A GRAM transfer is waiting for its 13.1.1 flight into the chat (the sheet keeps the keyboard until it starts). */
    public boolean hasQueuedSend() {
        return queuedTransaction != null;
    }

    public void setSendTransitionProgress(float progress) {
        form.setSendTransitionProgress(progress);
    }

    /** ChatAttachAlert.onCustomCloseAnimation: 13.1.1 WalletChatSendTransition, or null for the plain close. */
    public AnimatorSet createSendTransition(Runnable completion) {
        if (queuedTransaction == null || sendTransition != null) return null;
        final ChatActivity chat = recipientChat();
        if (chat == null || !form.getAmountInput().getDiamondView().canAnimateFlight()) return null;
        sendTransition = new WalletChatSendTransition(parentAlert, form.getAmountInput().getDiamondView(), chat, queuedTransaction, completion);
        return sendTransition.animator();
    }

    @Override
    public int getCurrentItemTop() {
        if (listView.getChildCount() <= 0) {
            return Integer.MAX_VALUE;
        }
        final View child = listView.getChildAt(0);
        final RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        final int top = child.getTop() - dp(8);
        int newOffset = top > 0 && holder != null && holder.getAdapterPosition() == 0 ? top : 0;
        return newOffset + dp(12);
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        parentAlert.getSheetContainer().invalidate();
    }

    @Override
    public int getFirstOffset() {
        return getListTopPadding() + dp(4);
    }

    @Override
    public int getListTopPadding() {
        return listView.getPaddingTop();
    }

    @Override
    public void onPreMeasure(int availableWidth, int availableHeight) {
        super.onPreMeasure(availableWidth, availableHeight);
        int paddingTop;
        if (parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > dp(20)) {
            paddingTop = dp(8);
        } else if (!AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            paddingTop = (int) (availableHeight / 3.5f);
        } else {
            paddingTop = (availableHeight / 5 * 2);
        }
        paddingTop -= dp(52);
        if (paddingTop < 0) paddingTop = 0;
        if (listView.getPaddingTop() != paddingTop) {
            listView.setPadding(0, paddingTop, 0, dp(64));
        }
    }

    @Override
    public void onShow(ChatAttachAlert.AttachAlertLayout previousLayout) {
        form.updateBalance();
        listView.scrollToPosition(0);
    }

    @Override
    public void scrollToTop() {
        listView.smoothScrollToPosition(0);
    }

    @Override
    public void onHide() {
        AndroidUtilities.hideKeyboard(form);
        parentAlert.setFocusable(false);
    }
}
