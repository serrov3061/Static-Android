package com.exteragram.messenger.security;

import android.app.Activity;
import android.text.TextUtils;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_payments;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * exteraGram 13.1.1 "Confirm sensitive actions", StaticGram Java implementation.
 * <p>
 * exteraGram enforces this inside its own libtmessages (closed native code). Here the check sits in
 * {@code ConnectionsManager.sendRequest}: a protected request (Stars/payments, gifts, account security,
 * chat ownership, wallet) that is started by a plugin (Python executing on the call stack, see
 * {@link #originOf}) or rewritten by a plugin's pre_request_hook is held until the user allows it.
 * Requests the user starts from the client UI are never held.
 */
public final class SensitiveRequestGuard {

    public static final int NORMAL = 0;
    public static final int PLUGIN = 1;
    public static final int APPROVED = 2;

    /** constructor -> category string (0 = no category line). */
    private static final SparseIntArray CATEGORIES = new SparseIntArray();
    /** Protected only when they spend Stars (paid messages / paid search). */
    private static final SparseIntArray PAID_ONLY = new SparseIntArray();

    static {
        put(TL_stars.transferStarGift.constructor, R.string.ProfileGifts);
        put(TL_stars.updateStarGiftPrice.constructor, R.string.ProfileGifts);
        put(TL_stars.convertStarGift.constructor, R.string.ProfileGifts);
        put(TL_stars.upgradeStarGift.constructor, R.string.ProfileGifts);
        put(TL_stars.getStarGiftWithdrawalUrl.constructor, R.string.ProfileGifts);
        put(TL_stars.withdrawStarGift.constructor, R.string.ProfileGifts);
        put(TL_stars.deleteStarGift.constructor, R.string.ProfileGifts);
        put(TL_stars.craftStarGift.constructor, R.string.ProfileGifts);
        put(TL_payments.TL_sendStarGiftOffer.constructor, R.string.ProfileGifts);
        put(TL_stars.TL_payments_sendStarsForm.constructor, R.string.SensitiveRequestCategoryPayments);
        put(TLRPC.TL_payments_sendPaymentForm.constructor, R.string.SensitiveRequestCategoryPayments);
        put(TLRPC.TL_messages_sendPaidReaction.constructor, R.string.SensitiveRequestCategoryPayments);
        put(TL_stars.TL_fulfillStarsSubscription.constructor, R.string.SensitiveRequestCategoryPayments);
        put(TL_stars.TL_changeStarsSubscription.constructor, R.string.SensitiveRequestCategoryPayments);
        put(TLRPC.TL_payments_getStarsRevenueWithdrawalUrl.constructor, R.string.SensitiveRequestCategoryPayments);
        put(TLRPC.TL_auth_acceptLoginToken.constructor, R.string.SecurityTitle);
        put(TLRPC.TL_auth_exportAuthorization.constructor, R.string.SecurityTitle);
        put(TLRPC.TL_auth_resetAuthorizations.constructor, R.string.SecurityTitle);
        put(TL_account.resetAuthorization.constructor, R.string.SecurityTitle);
        put(TL_account.resetWebAuthorization.constructor, R.string.SecurityTitle);
        put(TL_account.resetWebAuthorizations.constructor, R.string.SecurityTitle);
        put(TL_account.deleteAccount.constructor, R.string.SecurityTitle);
        put(TL_account.updatePasswordSettings.constructor, R.string.SecurityTitle);
        put(TL_account.resetPassword.constructor, R.string.SecurityTitle);
        put(TL_account.changePhone.constructor, R.string.SecurityTitle);
        put(TL_account.updateConnectedBot.constructor, R.string.SecurityTitle);
        put(TL_account.acceptAuthorization.constructor, R.string.SecurityTitle);
        put(TL_account.updateUsername.constructor, R.string.SecurityTitle);
        put(TL_account.toggleUsername.constructor, R.string.SecurityTitle);
        put(TL_account.setAuthorizationTTL.constructor, R.string.SecurityTitle);
        put(TLRPC.TL_channels_editCreator.constructor, R.string.SensitiveRequestCategoryChatManagement);
        put(TLRPC.TL_channels_editAdmin.constructor, R.string.SensitiveRequestCategoryChatManagement);
        put(TLRPC.TL_channels_deleteChannel.constructor, R.string.SensitiveRequestCategoryChatManagement);
        put(TLRPC.TL_channels_updateUsername.constructor, R.string.SensitiveRequestCategoryChatManagement);
        put(TLRPC.TL_channels_toggleUsername.constructor, R.string.SensitiveRequestCategoryChatManagement);
        put(TL_wallet.sendTransfer.constructor, R.string.WalletTitle);
        put(TL_wallet.exportSecretPhrase.constructor, R.string.WalletTitle);
        put(TL_wallet.replaceWallet.constructor, R.string.WalletTitle);
        put(TL_wallet.enableBackup.constructor, R.string.WalletTitle);
        put(TL_wallet.disableBackup.constructor, R.string.WalletTitle);
        put(TL_wallet.tonConnectSubmitConnectResult.constructor, R.string.WalletTitle);
        put(TL_wallet.tonConnectSubmitResponse.constructor, R.string.WalletTitle);
        PAID_ONLY.put(TLRPC.TL_messages_sendMessage.constructor, R.string.SensitiveRequestCategoryPayments);
        PAID_ONLY.put(TLRPC.TL_messages_sendMedia.constructor, R.string.SensitiveRequestCategoryPayments);
        PAID_ONLY.put(TLRPC.TL_messages_forwardMessages.constructor, R.string.SensitiveRequestCategoryPayments);
        PAID_ONLY.put(TLRPC.TL_messages_sendMultiMedia.constructor, R.string.SensitiveRequestCategoryPayments);
        PAID_ONLY.put(TLRPC.TL_messages_sendInlineBotResult.constructor, R.string.SensitiveRequestCategoryPayments);
        PAID_ONLY.put(TLRPC.TL_channels_searchPosts.constructor, R.string.SensitiveRequestCategoryPayments);
        PAID_ONLY.put(TL_phone.sendGroupCallMessage.constructor, R.string.SensitiveRequestCategoryPayments);
    }

    private static void put(int constructor, int category) {
        CATEGORIES.put(constructor, category);
    }

    private SensitiveRequestGuard() {
    }

    private static int constructorOf(TLObject object) {
        try {
            return object.getClass().getField("constructor").getInt(null);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static long paidStars(TLObject object) {
        if (object instanceof TLRPC.TL_messages_sendMessage) return ((TLRPC.TL_messages_sendMessage) object).allow_paid_stars;
        if (object instanceof TLRPC.TL_messages_sendMedia) return ((TLRPC.TL_messages_sendMedia) object).allow_paid_stars;
        if (object instanceof TLRPC.TL_messages_forwardMessages) return ((TLRPC.TL_messages_forwardMessages) object).allow_paid_stars;
        if (object instanceof TLRPC.TL_messages_sendMultiMedia) return ((TLRPC.TL_messages_sendMultiMedia) object).allow_paid_stars;
        if (object instanceof TLRPC.TL_messages_sendInlineBotResult) return ((TLRPC.TL_messages_sendInlineBotResult) object).allow_paid_stars;
        if (object instanceof TLRPC.TL_channels_searchPosts) return ((TLRPC.TL_channels_searchPosts) object).allow_paid_stars;
        if (object instanceof TL_phone.sendGroupCallMessage) return ((TL_phone.sendGroupCallMessage) object).allow_paid_stars;
        return 0;
    }

    public static boolean isProtected(TLObject object) {
        if (object == null) return false;
        final int constructor = constructorOf(object);
        if (constructor == 0) return false;
        if (CATEGORIES.indexOfKey(constructor) >= 0) return true;
        return PAID_ONLY.indexOfKey(constructor) >= 0 && paidStars(object) > 0;
    }

    /**
     * Called on the caller's thread: whether a protected request is started by plugin code.
     * <p>
     * exteraGram makes this decision in its closed native tgnet, so the rule here is our own:
     * <ul>
     * <li>a {@code com.chaquo.python.*} frame means Python is executing up the stack right now (a plugin
     * hook callback, replace_hooked_method, a PyRunnable / dynamic_proxy posted to a queue, a plugin
     * menu/button callback) and this request is sent from inside it;</li>
     * <li>a thread started by Python ({@code threading}) that calls Java directly has no Java frames
     * below the call, so its outermost frame is ConnectionsManager itself
     * (client_utils.send_request from such a thread).</li>
     * </ul>
     * Xposed machinery frames (de.robv / org.lsposed / LSPHooker_) are deliberately NOT a signal: they are
     * on the stack of every hooked method, also when a plugin only observes it, so the user's own
     * wallet.sendTransfer or gift transfer through a hooked UI method would be held for confirmation.
     * Requests a pre_request_hook replaces or mutates are caught separately, see {@link #snapshot}.
     */
    public static int originOf(TLObject object) {
        if (!SensitiveRequestSwitch.isActive() || !isProtected(object)) {
            return NORMAL;
        }
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            if (element.getClassName().startsWith("com.chaquo.python.")) {
                return PLUGIN;
            }
        }
        if (stack.length > 0 && CONNECTIONS_MANAGER.equals(stack[stack.length - 1].getClassName())) {
            return PLUGIN;
        }
        return NORMAL;
    }

    private static final String CONNECTIONS_MANAGER = "org.telegram.tgnet.ConnectionsManager";

    /**
     * Serialized form of a protected request before the plugins' pre_request_hook runs, so a hook that
     * edits the request in place (not only one that returns a new object) is treated as plugin-made.
     * Paid-only kinds (sendMessage...) are captured even without a price, so a hook that adds
     * allow_paid_stars is caught too. Null when confirmations are off or the kind is never protected.
     */
    public static byte[] snapshot(TLObject object) {
        if (object == null || !SensitiveRequestSwitch.isActive()) {
            return null;
        }
        final int constructor = constructorOf(object);
        if (constructor == 0 || CATEGORIES.indexOfKey(constructor) < 0 && PAID_ONLY.indexOfKey(constructor) < 0) {
            return null;
        }
        return serialize(object);
    }

    /** Whether the pre_request_hook replaced the request or changed its contents. */
    public static boolean rewrittenByHook(TLObject original, TLObject hooked, byte[] snapshot) {
        if (hooked == null) return false;
        if (hooked != original) return true;
        if (snapshot == null) return false;
        final byte[] now = serialize(hooked);
        return now == null || !java.util.Arrays.equals(snapshot, now);
    }

    private static byte[] serialize(TLObject object) {
        SerializedData data = null;
        try {
            data = new SerializedData(object.getObjectSize());
            object.serializeToStream(data);
            return data.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            if (data != null) data.cleanup();
        }
    }

    public static boolean needsConfirmation(TLObject object, boolean fromPlugin) {
        return fromPlugin && SensitiveRequestSwitch.isActive() && isProtected(object);
    }

    /** What the confirmation sheet shows for a request: (key, value) pairs, texts the values index, the TL method. */
    static final class Details {
        long[] values = new long[0];
        String[] texts = new String[0];
        String method;
    }

    /** exteraGram: requests of these kinds asked at the same time are confirmed by one sheet (amounts summed). */
    private static final java.util.HashSet<Integer> MERGEABLE = new java.util.HashSet<>(java.util.Arrays.asList(
            TLRPC.TL_messages_sendPaidReaction.constructor,
            TLRPC.TL_messages_sendMessage.constructor,
            TLRPC.TL_messages_sendMedia.constructor,
            TLRPC.TL_messages_forwardMessages.constructor,
            TLRPC.TL_messages_sendMultiMedia.constructor,
            TLRPC.TL_messages_sendInlineBotResult.constructor));

    private static SensitiveRequestSheet currentSheet;

    private static long peerId(int account, TLRPC.InputPeer peer) {
        if (peer == null) return 0;
        if (peer instanceof TLRPC.TL_inputPeerSelf) return org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        return org.telegram.messenger.DialogObject.getPeerDialogId(peer);
    }

    private static long peerId(int account, TLRPC.InputUser user) {
        if (user == null) return 0;
        if (user instanceof TLRPC.TL_inputUserSelf) return org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        return user.user_id;
    }

    private static void savedGift(java.util.ArrayList<Long> out, TL_stars.InputSavedStarGift gift) {
        if (gift instanceof TL_stars.TL_inputSavedStarGiftUser) {
            out.add((long) SensitiveRequestSheet.GIFT_MESSAGE); out.add((long) ((TL_stars.TL_inputSavedStarGiftUser) gift).msg_id);
        } else if (gift instanceof TL_stars.TL_inputSavedStarGiftChat) {
            out.add((long) SensitiveRequestSheet.SAVED_GIFT); out.add(((TL_stars.TL_inputSavedStarGiftChat) gift).saved_id);
        }
    }

    private static TLRPC.InputPeer paidPeer(TLObject object) {
        if (object instanceof TLRPC.TL_messages_sendMessage) return ((TLRPC.TL_messages_sendMessage) object).peer;
        if (object instanceof TLRPC.TL_messages_sendMedia) return ((TLRPC.TL_messages_sendMedia) object).peer;
        if (object instanceof TLRPC.TL_messages_forwardMessages) return ((TLRPC.TL_messages_forwardMessages) object).to_peer;
        if (object instanceof TLRPC.TL_messages_sendMultiMedia) return ((TLRPC.TL_messages_sendMultiMedia) object).peer;
        if (object instanceof TLRPC.TL_messages_sendInlineBotResult) return ((TLRPC.TL_messages_sendInlineBotResult) object).peer;
        return null;
    }

    /** "TL_payments_sendStarsForm" -> "payments.sendStarsForm", "TL_account.deleteAccount" style names too. */
    static String methodName(TLObject object) {
        final Class<?> cls = object.getClass();
        String name = cls.getSimpleName();
        final Class<?> outer = cls.getEnclosingClass();
        if (name.startsWith("TL_")) name = name.substring(3);
        final int us = name.indexOf('_');
        if (us > 0) {
            return name.substring(0, us) + "." + name.substring(us + 1);
        }
        if (outer != null) {
            String ns = outer.getSimpleName();
            if (ns.startsWith("TL_")) ns = ns.substring(3);
            if ("stars".equals(ns)) ns = "payments";
            return ns + "." + name;
        }
        return name;
    }

    static Details details(int account, TLObject object) {
        final Details d = new Details();
        d.method = methodName(object);
        final java.util.ArrayList<Long> v = new java.util.ArrayList<>();
        final java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        TLRPC.InputInvoice invoice = null;
        long formId = 0;
        boolean form = false;
        if (object instanceof TL_stars.TL_payments_sendStarsForm) {
            final TL_stars.TL_payments_sendStarsForm r = (TL_stars.TL_payments_sendStarsForm) object;
            form = true; formId = r.form_id; invoice = r.invoice;
        } else if (object instanceof TLRPC.TL_payments_sendPaymentForm) {
            final TLRPC.TL_payments_sendPaymentForm r = (TLRPC.TL_payments_sendPaymentForm) object;
            form = true; formId = r.form_id; invoice = r.invoice;
        } else if (object instanceof TLRPC.TL_messages_sendPaidReaction) {
            final TLRPC.TL_messages_sendPaidReaction r = (TLRPC.TL_messages_sendPaidReaction) object;
            v.add((long) SensitiveRequestSheet.CHAT); v.add(peerId(account, r.peer));
            v.add((long) SensitiveRequestSheet.STARS); v.add((long) r.count);
        } else if (object instanceof TL_stars.transferStarGift) {
            final TL_stars.transferStarGift r = (TL_stars.transferStarGift) object;
            v.add((long) SensitiveRequestSheet.RECIPIENT); v.add(peerId(account, r.to_id));
            savedGift(v, r.stargift);
        } else if (object instanceof TLRPC.TL_channels_editCreator) {
            final TLRPC.TL_channels_editCreator r = (TLRPC.TL_channels_editCreator) object;
            v.add((long) SensitiveRequestSheet.RECIPIENT); v.add(peerId(account, r.user_id));
        } else if (paidStars(object) > 0) {
            final TLRPC.InputPeer peer = paidPeer(object);
            if (peer != null) { v.add((long) SensitiveRequestSheet.CHAT); v.add(peerId(account, peer)); }
            v.add((long) SensitiveRequestSheet.STARS); v.add(paidStars(object));
        }
        if (form) {
            // the remembered amount is used only for the same form id AND the same invoice (exteraGram)
            final String hash = SensitivePaymentForms.invoiceHash(invoice);
            if (formId != 0 && hash != null) {
                v.add((long) SensitiveRequestSheet.FORM_ID); v.add(formId);
                v.add((long) SensitiveRequestSheet.INVOICE); v.add((long) texts.size()); texts.add(hash);
            }
        }
        if (invoice instanceof TLRPC.TL_inputInvoiceStarGift) {
            final TLRPC.TL_inputInvoiceStarGift i = (TLRPC.TL_inputInvoiceStarGift) invoice;
            v.add((long) SensitiveRequestSheet.RECIPIENT); v.add(peerId(account, i.peer));
            v.add((long) SensitiveRequestSheet.GIFT_ID); v.add(i.gift_id);
            if (i.hide_name) { v.add((long) SensitiveRequestSheet.ANONYMOUS); v.add(1L); }
            if (i.include_upgrade) { v.add((long) SensitiveRequestSheet.UPGRADE); v.add(1L); }
            if (i.message != null && !TextUtils.isEmpty(i.message.text)) {
                v.add((long) SensitiveRequestSheet.GIFT_TEXT); v.add((long) texts.size()); texts.add(i.message.text);
            }
        } else if (invoice instanceof TLRPC.TL_inputInvoiceStarGiftTransfer) {
            final TLRPC.TL_inputInvoiceStarGiftTransfer i = (TLRPC.TL_inputInvoiceStarGiftTransfer) invoice;
            savedGift(v, i.stargift);
            v.add((long) SensitiveRequestSheet.RECIPIENT); v.add(peerId(account, i.to_id));
        } else if (invoice instanceof TLRPC.TL_inputInvoiceStarGiftUpgrade) {
            savedGift(v, ((TLRPC.TL_inputInvoiceStarGiftUpgrade) invoice).stargift);
        } else if (invoice instanceof TLRPC.TL_inputInvoiceStarGiftResale) {
            final TLRPC.TL_inputInvoiceStarGiftResale i = (TLRPC.TL_inputInvoiceStarGiftResale) invoice;
            v.add((long) SensitiveRequestSheet.RECIPIENT); v.add(peerId(account, i.to_id));
            if (!TextUtils.isEmpty(i.slug)) { v.add((long) SensitiveRequestSheet.GIFT_TEXT); v.add((long) texts.size()); texts.add(i.slug); }
        }
        d.values = new long[v.size()];
        for (int i = 0; i < v.size(); i++) d.values[i] = v.get(i);
        d.texts = texts.toArray(new String[0]);
        return d;
    }

    /**
     * Holds the request until the user answers; {@code result} runs exactly once (false when there is no
     * activity to ask in, the sheet is dismissed or Decline is tapped). A request of a mergeable kind that
     * arrives while a sheet for the same account, method and texts is open joins that sheet instead of
     * opening a second one (exteraGram SensitiveRequestConfirmation.request).
     */
    public static void confirm(int account, TLObject object, Utilities.Callback<Boolean> result) {
        final AtomicBoolean answered = new AtomicBoolean();
        final Utilities.Callback<Boolean> once = allowed -> {
            if (answered.compareAndSet(false, true)) {
                result.run(allowed);
            }
        };
        final Details details = details(account, object);
        final int constructor = constructorOf(object);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final BaseFragment fragment = LaunchActivity.getLastFragment();
                final Activity activity = fragment != null ? fragment.getParentActivity() : null;
                if (activity == null || activity.isFinishing()) {
                    once.run(false);
                    return;
                }
                final Utilities.Callback<Boolean> callback = allowed -> {
                    if (currentSheet != null && currentSheet.isAnswered()) {
                        currentSheet = null;
                    }
                    once.run(allowed);
                };
                if (currentSheet != null && MERGEABLE.contains(constructor)
                        && currentSheet.merge(account, constructor, details.values, details.texts, callback)) {
                    return;
                }
                final SensitiveRequestSheet sheet = new SensitiveRequestSheet(activity, fragment.getResourceProvider(), account, constructor,
                        getCategoryText(constructor), details.method, details.values, details.texts, callback);
                currentSheet = sheet;
                sheet.show();
            } catch (Exception e) {
                FileLog.e(e);
                once.run(false);
            }
        });
    }

    private static String getCategoryText(int constructor) {
        int categoryRes = CATEGORIES.get(constructor, 0);
        if (categoryRes == 0) categoryRes = PAID_ONLY.get(constructor, 0);
        return categoryRes != 0 ? LocaleController.getString(categoryRes) : null;
    }

    /** exteraGram: a dialog opened over the confirmation must not cover it. */
    public static void onDialogShown(android.app.Dialog dialog) {
        final SensitiveRequestSheet sheet = currentSheet;
        if (sheet != null && sheet != dialog) {
            sheet.bringToFront();
        }
    }

    public static boolean isConfirmationDialog(Object dialog) {
        return dialog != null && dialog == currentSheet;
    }
}
