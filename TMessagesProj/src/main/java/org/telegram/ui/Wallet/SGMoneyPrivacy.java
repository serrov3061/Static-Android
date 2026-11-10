package org.telegram.ui.Wallet;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/**
 * «Кто может отправлять мне деньги» (Stars and GRAM transfers): the server-side privacy rule
 * set behind sgcard.getMoneyPrivacy / sgcard.setMoneyPrivacy. Three modes like the other
 * privacy rows; per-user exceptions set elsewhere are kept when the mode changes.
 */
public final class SGMoneyPrivacy {

    public static final int MODE_EVERYBODY = 0;
    public static final int MODE_CONTACTS = 1;
    public static final int MODE_NOBODY = 2;

    private static final ArrayList<TLRPC.PrivacyRule>[] rules = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] loading = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private SGMoneyPrivacy() {
    }

    /** Loads the rules (once per screen open); done runs on the UI thread when they arrive. */
    public static void load(int account, Runnable done) {
        if (loading[account]) return;
        loading[account] = true;
        ConnectionsManager.getInstance(account).sendRequestTyped(new TL_sgcard.TL_getMoneyPrivacy(), AndroidUtilities::runOnUIThread, (res, err) -> {
            loading[account] = false;
            if (res != null) {
                apply(account, res);
                if (done != null) done.run();
            }
        });
    }

    private static void apply(int account, TL_account.privacyRules res) {
        MessagesController.getInstance(account).putUsers(res.users, false);
        MessagesController.getInstance(account).putChats(res.chats, false);
        rules[account] = res.rules;
    }

    public static boolean isLoaded(int account) {
        return rules[account] != null;
    }

    public static int getMode(int account) {
        final ArrayList<TLRPC.PrivacyRule> list = rules[account];
        if (list == null) return MODE_EVERYBODY;
        boolean disallowAll = false, allowContacts = false, allowAll = false;
        for (TLRPC.PrivacyRule rule : list) {
            if (rule instanceof TLRPC.TL_privacyValueDisallowAll) disallowAll = true;
            else if (rule instanceof TLRPC.TL_privacyValueAllowContacts) allowContacts = true;
            else if (rule instanceof TLRPC.TL_privacyValueAllowAll) allowAll = true;
        }
        if (allowAll || !disallowAll && !allowContacts) return MODE_EVERYBODY;
        return allowContacts ? MODE_CONTACTS : MODE_NOBODY;
    }

    private static int exceptions(int account, boolean allow) {
        final ArrayList<TLRPC.PrivacyRule> list = rules[account];
        if (list == null) return 0;
        int count = 0;
        for (TLRPC.PrivacyRule rule : list) {
            if (allow && rule instanceof TLRPC.TL_privacyValueAllowUsers) count += ((TLRPC.TL_privacyValueAllowUsers) rule).users.size();
            if (!allow && rule instanceof TLRPC.TL_privacyValueDisallowUsers) count += ((TLRPC.TL_privacyValueDisallowUsers) rule).users.size();
        }
        return count;
    }

    private static String modeName(int mode) {
        switch (mode) {
            case MODE_CONTACTS:
                return LocaleController.getString(R.string.LastSeenContacts);
            case MODE_NOBODY:
                return LocaleController.getString(R.string.LastSeenNobody);
            default:
                return LocaleController.getString(R.string.LastSeenEverybody);
        }
    }

    /** Row value: "Мои контакты (-2, +1)" like the other privacy rows. */
    public static String valueText(int account) {
        final int mode = getMode(account);
        final int minus = mode != MODE_NOBODY ? exceptions(account, false) : 0;
        final int plus = mode != MODE_EVERYBODY ? exceptions(account, true) : 0;
        final String name = modeName(mode);
        if (minus > 0 && plus > 0) return name + " (-" + minus + ", +" + plus + ")";
        if (minus > 0) return name + " (-" + minus + ")";
        if (plus > 0) return name + " (+" + plus + ")";
        return name;
    }

    /** Single-choice picker; changed runs after the server saved the new rules. */
    public static void showPicker(BaseFragment fragment, Runnable changed) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        final int account = fragment.getCurrentAccount();
        final String[] options = {modeName(MODE_EVERYBODY), modeName(MODE_CONTACTS), modeName(MODE_NOBODY)};
        fragment.showDialog(AlertsCreator.createSingleChoiceDialog(fragment.getParentActivity(), options,
                LocaleController.getString(R.string.SgPrivacyMoney), getMode(account), (dialog, which) -> save(fragment, which, changed)));
    }

    private static void save(BaseFragment fragment, int mode, Runnable changed) {
        final int account = fragment.getCurrentAccount();
        final MessagesController controller = MessagesController.getInstance(account);
        final TL_sgcard.TL_setMoneyPrivacy req = new TL_sgcard.TL_setMoneyPrivacy();
        final ArrayList<TLRPC.PrivacyRule> current = rules[account];
        if (current != null) {
            for (TLRPC.PrivacyRule rule : current) {
                if (mode != MODE_EVERYBODY && rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                    final TLRPC.TL_inputPrivacyValueAllowUsers in = new TLRPC.TL_inputPrivacyValueAllowUsers();
                    for (Long id : ((TLRPC.TL_privacyValueAllowUsers) rule).users) {
                        final TLRPC.InputUser user = controller.getInputUser(id);
                        if (user != null && !(user instanceof TLRPC.TL_inputUserEmpty)) in.users.add(user);
                    }
                    if (!in.users.isEmpty()) req.rules.add(in);
                } else if (mode != MODE_NOBODY && rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                    final TLRPC.TL_inputPrivacyValueDisallowUsers in = new TLRPC.TL_inputPrivacyValueDisallowUsers();
                    for (Long id : ((TLRPC.TL_privacyValueDisallowUsers) rule).users) {
                        final TLRPC.InputUser user = controller.getInputUser(id);
                        if (user != null && !(user instanceof TLRPC.TL_inputUserEmpty)) in.users.add(user);
                    }
                    if (!in.users.isEmpty()) req.rules.add(in);
                }
            }
        }
        if (mode == MODE_EVERYBODY) {
            req.rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
        } else {
            if (mode == MODE_CONTACTS) req.rules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
            req.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        }
        ConnectionsManager.getInstance(account).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            if (res != null) {
                apply(account, res);
                if (changed != null) changed.run();
            } else {
                BulletinFactory.of(fragment).createErrorBulletin(SGCardsController.errorText(err)).show();
            }
        });
    }
}
