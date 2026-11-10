package com.exteragram.messenger.utils.ui;

import android.content.Context;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * exteraGram AccountsUiHelper (AccountsUiHelper.kt), the part used by the navigation drawer:
 * activated accounts ordered by login time, switching and adding an account.
 * The account popup builder (menu()) of exteraGram is not ported: our MainTabsActivity keeps its own selector.
 * StaticGram: account limits follow UserConfig.MAX_ACCOUNT_COUNT / MAX_ACCOUNT_DEFAULT_COUNT (exteraGram has 16).
 */
public final class AccountsUiHelper {

    private AccountsUiHelper() {
    }

    public static List<Integer> activated() {
        return activated(null);
    }

    public static List<Integer> activated(IntPredicate filter) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && (filter == null || filter.test(a))) {
                result.add(a);
            }
        }
        result.sort((a, b) -> Integer.compare(UserConfig.getInstance(a).loginTime, UserConfig.getInstance(b).loginTime));
        return result;
    }

    public static void switchTo(int account) {
        if (LaunchActivity.instance != null) {
            LaunchActivity.instance.switchToAccount(account, true);
        }
    }

    public static boolean hasFreeSlot() {
        return UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT;
    }

    public static Integer freeSlotWithinLimit() {
        int freeAccounts = 0;
        Integer availableAccount = null;
        for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                freeAccounts++;
                if (availableAccount == null) {
                    availableAccount = a;
                }
            }
        }
        if (!UserConfig.hasPremiumOnAccounts()) {
            freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);
        }
        return freeAccounts > 0 ? availableAccount : null;
    }

    public static void add() {
        add(null);
    }

    public static void add(BaseFragment fragment) {
        if (fragment == null) {
            fragment = LaunchActivity.getSafeLastFragment();
        }
        Integer slot = freeSlotWithinLimit();
        if (slot == null) {
            Context context = fragment != null ? fragment.getContext() : null;
            if (!UserConfig.hasPremiumOnAccounts() && fragment != null && context != null) {
                fragment.showDialog(new LimitReachedBottomSheet(fragment, context, LimitReachedBottomSheet.TYPE_ACCOUNTS, fragment.getCurrentAccount(), null));
            }
            return;
        }
        if (fragment != null) {
            fragment.presentFragment(new LoginActivity(slot));
        } else if (LaunchActivity.instance != null) {
            LaunchActivity.instance.presentFragment(new LoginActivity(slot));
        }
    }
}
