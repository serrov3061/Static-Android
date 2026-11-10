package com.exteragram.messenger.notifications;

import java.util.ArrayList;
import java.util.List;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

// exteraGram: per-account notifications ("show notifications from all accounts" -> choose accounts)
public final class AccountNotifications {

    public static final AccountNotifications INSTANCE = new AccountNotifications();

    private AccountNotifications() {
    }

    public boolean isEnabled(int account) {
        return MessagesController.getGlobalNotificationsSettings().getBoolean(key(account), true);
    }

    public void setEnabled(int account, boolean enabled) {
        MessagesController.getGlobalNotificationsSettings().edit().putBoolean(key(account), enabled).apply();
        apply(account);
    }

    // Telegram: the current account always, others only with "All accounts" (+ the per-account switch, default on)
    public static boolean shouldShow(int account) {
        if (account != UserConfig.selectedAccount) {
            return SharedConfig.showNotificationsForAllAccounts && INSTANCE.isEnabled(account);
        }
        return true;
    }

    public static void applyAll() {
        for (int account : getAccounts()) {
            INSTANCE.apply(account);
        }
    }

    public static List<Integer> getAccounts() {
        ArrayList<Integer> result = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                result.add(a);
            }
        }
        return result;
    }

    public static int getEnabledCount() {
        int count = 0;
        for (int account : getAccounts()) {
            if (account == UserConfig.selectedAccount || INSTANCE.isEnabled(account)) {
                count++;
            }
        }
        return count;
    }

    private void apply(int account) {
        NotificationsController controller = NotificationsController.getInstance(account);
        if (shouldShow(account)) {
            controller.showNotifications();
        } else {
            controller.hideNotifications();
        }
    }

    private String key(int account) {
        return "accountNotifications_" + UserConfig.getInstance(account).clientUserId;
    }
}
