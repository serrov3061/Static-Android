package org.telegram.ui.Wallet;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.graphics.Bitmap;
import android.os.Bundle;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry points shared by the cards screen, the profile, the card sheet and links. */
public final class SGCards {

    private SGCards() {
    }

    /** sg://card?id=&lt;user id&gt;&amp;kind=&lt;0|1&gt; */
    public static String cardLink(long userId, int kind) {
        return "sg://card?id=" + userId + "&kind=" + kind;
    }

    private static final Pattern CARD_LINK = Pattern.compile("^sg:(?://)?card\\?(.*)$");

    /** {user id, kind} of an sg://card link, or null for any other link. */
    public static long[] parseCardLink(String url) {
        if (url == null) return null;
        final Matcher m = CARD_LINK.matcher(url);
        if (!m.matches()) return null;
        long id = 0, kind = 0;
        for (String part : m.group(1).split("&")) {
            final int eq = part.indexOf('=');
            if (eq <= 0) continue;
            final String key = part.substring(0, eq), value = part.substring(eq + 1);
            try {
                if ("id".equals(key)) id = Long.parseLong(value);
                else if ("kind".equals(key)) kind = Long.parseLong(value) == 1 ? 1 : 0;
            } catch (NumberFormatException ignore) {
            }
        }
        return id > 0 ? new long[]{id, kind} : null;
    }

    /** Opens the card sheet of a user from an sg://card link (the user is fetched when unknown). */
    public static void openFromLink(BaseFragment fragment, long userId, int kind) {
        if (fragment == null) return;
        final int account = fragment.getCurrentAccount();
        final MessagesController controller = MessagesController.getInstance(account);
        if (controller.getUser(userId) != null) {
            SGCardSheet.show(fragment, null, userId, kind);
            return;
        }
        final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        final TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = userId;
        req.id.add(inputUser);
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            final java.util.ArrayList<TLRPC.User> users = new java.util.ArrayList<>();
            if (res instanceof org.telegram.tgnet.Vector) {
                for (Object o : ((org.telegram.tgnet.Vector) res).objects) {
                    if (o instanceof TLRPC.User && !(o instanceof TLRPC.TL_userEmpty) && ((TLRPC.User) o).id == userId) {
                        users.add((TLRPC.User) o);
                    }
                }
            }
            if (users.isEmpty()) {
                BulletinFactory.global().createErrorBulletin(getString(R.string.SgProfileIdNotFound)).show();
                return;
            }
            controller.putUsers(users, false);
            SGCardSheet.show(fragment, null, userId, kind);
        }));
    }

    public static void openBot(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        Browser.openUrl(fragment.getParentActivity(), SGCardsController.BOT_URL);
    }

    /**
     * Picks the recipient in the gift-picker sheet ("Отправить подарок" look: search, yourself,
     * frequent contacts, contacts) and opens the amount sheet for them.
     */
    public static void pickRecipientAndSend(BaseFragment fragment, int kind) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        final int account = fragment.getCurrentAccount();
        final org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet sheet = new org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet(
                fragment.getParentActivity(), account, 0, null,
                org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet.TYPE_SG_MONEY, true, fragment.getResourceProvider());
        sheet.setTitle(getString(kind == TL_sgcard.KIND_STARS ? R.string.SgCardSendStarsButton : R.string.SgCardSendGramButton));
        if (kind == TL_sgcard.KIND_GRAM) sheet.addAddressOption();
        sheet.setOnUserSelector(did -> {
            if (did != null && did == org.telegram.ui.Components.Premium.boosts.UserSelectorBottomSheet.OPTION_ADDRESS) {
                // «На адрес»: GRAM to a wallet address or name.ton
                sheet.dismiss();
                AndroidUtilities.runOnUIThread(() -> SGSendMoneySheet.showToAddress(fragment), 200);
                return;
            }
            final TLRPC.User user = DialogObject.isUserDialog(did) ? MessagesController.getInstance(account).getUser(did) : null;
            if (user == null || UserObject.isUserSelf(user) || user.bot || UserObject.isDeleted(user)) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.SgCardErrorRecipient)).show();
                return;
            }
            sheet.dismiss();
            AndroidUtilities.runOnUIThread(() -> SGSendMoneySheet.show(fragment, user, kind), 200);
        });
        if (!AndroidUtilities.isTablet() && !AndroidUtilities.hasDialogOnTop(fragment)) {
            sheet.makeAttached(fragment);
        }
        fragment.showDialog(sheet);
    }

    /**
     * "Send GRAM" from the upstream 13.1.1 wallet entry points (transaction sheet, profile menu,
     * profile wallet card). StaticGram has no self-custody wallet engine (no local key,
     * toncenter.performApiRequest is not served): its WalletSendEnterAmount shows the empty
     * wallet.getState balance and a network fee that never loads. GRAM is sent off-chain from
     * the «Деньги» balance (gramwallet.send / sendToAddress) instead.
     */
    public static void sendGram(BaseFragment fragment, TLRPC.User user, String address, String domain) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        if (user != null) {
            if (UserObject.isUserSelf(user) || user.bot || UserObject.isDeleted(user)) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(user.bot ? R.string.SgCardErrorBot : R.string.SgCardErrorRecipient)).show();
                return;
            }
            SGSendMoneySheet.show(fragment, user, TL_sgcard.KIND_GRAM);
            return;
        }
        final String target = !android.text.TextUtils.isEmpty(domain) && !"null".equals(domain) ? domain : address;
        SGSendMoneySheet.showToAddress(fragment, target);
    }

    /** Saves the rendered card and lets the user pick chats to send it to as a photo with the card link. */
    public static void shareCard(BaseFragment fragment, TLRPC.User owner, int kind, Bitmap image) {
        if (fragment == null || owner == null || image == null) {
            if (fragment != null) BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.SgCardErrorUnknown)).show();
            return;
        }
        final int account = fragment.getCurrentAccount();
        final File file;
        try {
            final File dir = new File(AndroidUtilities.getCacheDir(), "sgcards");
            dir.mkdirs();
            file = new File(dir, "card_" + owner.id + "_" + kind + "_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                image.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.SgCardErrorUnknown)).show();
            return;
        }
        final String caption = formatString(kind == TL_sgcard.KIND_STARS ? R.string.SgCardShareCaptionStars : R.string.SgCardShareCaptionGram,
                UserObject.getUserName(owner)) + "\n" + cardLink(owner.id, kind);
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;
            final AccountInstance instance = AccountInstance.getInstance(account);
            for (int i = 0; i < dids.size(); i++) {
                final long did = dids.get(i).dialogId;
                SendMessagesHelper.prepareSendingPhoto(instance, file.getAbsolutePath(), null, did, null, null, null,
                        caption, null, null, null, 0, null, notify, scheduleDate, 0, null);
                if (message != null && message.length() > 0) {
                    SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, notify, scheduleDate, scheduleRepeatPeriod, null, false));
                }
            }
            f.finishFragment();
            AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createSimpleBulletin(R.raw.forward, getString(R.string.SgCardShared)).show(), 300);
            return true;
        });
        fragment.presentFragment(picker);
    }

    public static boolean isSelf(int account, long userId) {
        return UserConfig.getInstance(account).getClientUserId() == userId;
    }

    static Utilities.Callback<Object> noop() {
        return o -> { };
    }
}
