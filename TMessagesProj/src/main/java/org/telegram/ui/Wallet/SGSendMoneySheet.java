package org.telegram.ui.Wallet;

import static org.telegram.messenger.LocaleController.formatString;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Sends GRAM or Stars to one user from the money screen or a card: Telegram 13.1.1's GRAM send
 * screen (SGSendMoneyView) in a sheet. The currency is fixed by the entry point, so there is no
 * Stars | GRAM switch. After sending, the recipient's chat opens under the sheet and, for GRAM,
 * the diamond flies onto the new transfer (13.1.1 WalletChatSendTransition).
 */
public class SGSendMoneySheet extends BottomSheet {

    private final FrameLayout bulletinContainer;
    private final SGSendMoneyView form;
    private final BaseFragment fragment;
    private final TLRPC.User user;
    private boolean flying;

    public static void show(BaseFragment fragment, TLRPC.User user, int kind) {
        if (fragment == null || fragment.getParentActivity() == null || user == null) return;
        final SGSendMoneySheet sheet = new SGSendMoneySheet(fragment, user, kind);
        // showDialog() silently refuses while the chat picker is still closing
        // (its exit animation), so the sheet is shown on its own.
        if (fragment.showDialog(sheet) == null) {
            sheet.show();
        }
    }

    /** «На адрес»: GRAM to a wallet address or name.ton, no recipient chat. */
    public static void showToAddress(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        final SGSendMoneySheet sheet = new SGSendMoneySheet(fragment, null, TL_sgcard.KIND_GRAM);
        if (fragment.showDialog(sheet) == null) {
            sheet.show();
        }
    }

    public SGSendMoneySheet(BaseFragment fragment, TLRPC.User user, int kind) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.user = user;
        final Context context = fragment.getParentActivity();
        currentAccount = fragment.getCurrentAccount();
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        setApplyBottomPadding(false);

        bulletinContainer = new FrameLayout(context);
        form = new SGSendMoneyView(context, currentAccount, user, kind, resourcesProvider);
        form.lockKind();
        form.setDelegate(new SGSendMoneyView.Delegate() {
            @Override
            public void onSent(int k, long amount, boolean replayed, int sinceDate) {
                SGSendMoneySheet.this.onSent(k, amount, sinceDate);
            }

            @Override
            public void onError(String text) {
                BulletinFactory.of(bulletinContainer, resourcesProvider).createErrorBulletin(text).show();
            }

            @Override
            public void onOpenRecipient() {
                if (user == null) return;
                dismiss();
                if (!isRecipientChat(fragment)) fragment.presentFragment(ChatActivity.of(user.id));
            }
        });
        bulletinContainer.addView(form, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP, 0, 12, 0, 0));
        setCustomView(bulletinContainer);
        AndroidUtilities.runOnUIThread(user == null ? form::requestAddressKeyboard : form::requestAmountKeyboard, 300);
    }

    private boolean isRecipientChat(BaseFragment f) {
        return user != null && f instanceof ChatActivity && ((ChatActivity) f).getDialogId() == user.id && ((ChatActivity) f).getChatMode() == 0;
    }

    @Override
    public void dismiss() {
        // The flight dismisses the sheet itself when the money has landed.
        if (flying) return;
        super.dismiss();
    }

    private void finishFlight() {
        flying = false;
        // The flight puts the panel back in place when it ends; it has already left the screen.
        if (containerView != null) containerView.setAlpha(0f);
        dismiss();
    }

    private void onSent(int k, long amount, int sinceDate) {
        AndroidUtilities.hideKeyboard(form);
        if (user == null) {
            // «На адрес»: no chat to land in; the upstream «sent» bulletin over the money screen
            final String to = form.getAddressLabel();
            dismiss();
            AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createSimpleBulletin(R.raw.forward,
                    AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SgMoneySentToAddress,
                            GramWallet.formatGramsWithUnit(amount), to))).show(), 250);
            return;
        }
        // 13.1.1: after sending from the wallet the recipient's chat opens with the new transfer.
        final ChatActivity chat;
        if (isRecipientChat(fragment)) {
            chat = (ChatActivity) fragment;
        } else if (fragment.getParentLayout() != null) {
            final Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            chat = new ChatActivity(args);
            // The sheet stays above the new chat until the money lands there.
            if (fragment.visibleDialog == this) fragment.visibleDialog = null;
            flying = true;
            if (!fragment.presentFragment(chat)) {
                flying = false;
                dismiss();
                return;
            }
        } else {
            dismiss();
            return;
        }
        flying = true;
        // The money is already sent: never leave the sheet up if the flight cannot finish.
        AndroidUtilities.runOnUIThread(() -> {
            if (isShowing()) {
                flying = false;
                super.dismiss();
                showSentBulletin(chat, k, amount);
            }
        }, 5000);
        if (k == TL_sgcard.KIND_GRAM && form.getAmountInput().getDiamondView().canAnimateFlight()) {
            SGSendLanding.await(chat, amount, sinceDate, tx -> {
                if (tx != null && isShowing() && getContainer() != null && getContainer().isAttachedToWindow()) {
                    SGSendLanding.start(getContainer(), containerView, form.getAmountInput().getDiamondView(), chat, tx, this::finishFlight);
                } else {
                    flyDrawn(k, amount, chat);
                }
            });
        } else {
            // Stars: no diamond in the transfer card, the star itself flies into the chat.
            AndroidUtilities.runOnUIThread(() -> flyDrawn(k, amount, chat), 250);
        }
    }

    private void flyDrawn(int k, long amount, ChatActivity chat) {
        if (!isShowing()) {
            flying = false;
            showSentBulletin(chat, k, amount);
            return;
        }
        SGSendTransition.start(getContainer(), containerView, form.getCurrencyIcon(), chat, k, () -> {
            finishFlight();
            showSentBulletin(chat, k, amount);
        });
    }

    private void showSentBulletin(BaseFragment target, int k, long amount) {
        final String raw = formatString(R.string.SgCardSentTo, "\u0001", UserObject.getFirstName(user));
        final SpannableStringBuilder sb = new SpannableStringBuilder(raw);
        final int at = raw.indexOf('\u0001');
        if (at >= 0) sb.replace(at, at + 1, SGSendMoneyView.formatAmount(k, amount));
        BulletinFactory.of(target).createSimpleBulletin(k == TL_sgcard.KIND_STARS ? R.raw.stars_send : R.raw.contact_check, sb).show();
    }
}
