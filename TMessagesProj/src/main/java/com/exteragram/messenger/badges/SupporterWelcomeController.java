package com.exteragram.messenger.badges;

import android.content.SharedPreferences;
import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.components.SupporterWelcomeSheet;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * exteraGram 13.1.1 SupporterWelcomeController: shows the «Thank You for Your Support!» sheet once per account
 * after it becomes a supporter (status SUPPORTER / DEVELOPER in the badges list), when the app is idle on that
 * account (no dialog, sheet, passcode or photo viewer; retried every 5 s otherwise).
 * StaticGram: exteraGram also remembers "shown" in the API bot's cloud storage (BotCloudStorage key
 * "supporter_welcome", value = badge number) so it is not repeated on other devices; we have no bot cloud
 * storage, so only the local "supporterWelcomeShown" set is used.
 */
public final class SupporterWelcomeController implements NotificationCenter.NotificationCenterDelegate {

    public static final SupporterWelcomeController INSTANCE = new SupporterWelcomeController();

    private static final String SHOWN_KEY = "supporterWelcomeShown";

    private static final HashSet<Long> ready = new HashSet<>();
    private static boolean retryScheduled;
    private static boolean started;
    private static final Runnable retryRunnable = () -> {
        retryScheduled = false;
        tryShow();
    };

    private SupporterWelcomeController() {
    }

    /** Called on the UI thread after the badge list was loaded or synced. */
    public static void check() {
        if (TextUtils.isEmpty(BadgesConfig.getApiUrl())) {
            return;
        }
        INSTANCE.start();
        Set<Long> shown = readSet(SHOWN_KEY);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig userConfig = UserConfig.getInstance(a);
            if (!userConfig.isClientActivated()) {
                continue;
            }
            long id = userConfig.clientUserId;
            if (BadgesController.INSTANCE.isSupporter(id) && !shown.contains(id)) {
                ready.add(id);
            }
        }
        tryShow();
    }

    public static void tryShow() {
        if (ready.isEmpty()) {
            return;
        }
        int account = UserConfig.selectedAccount;
        long id = UserConfig.getInstance(account).clientUserId;
        if (!ready.contains(id)) {
            return;
        }
        if (!BadgesController.INSTANCE.isSupporter(id)) {
            ready.remove(id);
            return;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (ApplicationLoader.mainInterfacePaused || SharedConfig.isWaitingForPasscodeEnter || fragment == null || fragment.getParentActivity() == null
                || fragment.getCurrentAccount() != account || fragment.visibleDialog != null || fragment.hasShownSheet()
                || PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            INSTANCE.scheduleRetry();
            return;
        }
        ready.remove(id);
        if (SupporterWelcomeSheet.show(fragment, SupporterWelcomeSheet.MODE_WELCOME) != null) {
            addToSet(SHOWN_KEY, id);
        }
    }

    private void start() {
        if (started) {
            return;
        }
        started = true;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(new ForegroundDetector.Listener() {
                @Override
                public void onBecameForeground() {
                    INSTANCE.scheduleRetry();
                }

                @Override
                public void onBecameBackground() {
                }
            });
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (retryScheduled || ready.isEmpty()) {
            return;
        }
        retryScheduled = true;
        AndroidUtilities.runOnUIThread(retryRunnable, 5000L);
    }

    private static Set<Long> readSet(String key) {
        Set<String> set = ExteraConfig.getPreferences().getStringSet(key, null);
        if (set == null) {
            return Collections.emptySet();
        }
        HashSet<Long> result = new HashSet<>();
        for (String s : set) {
            try {
                result.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private static void addToSet(String key, long userId) {
        Set<String> set = ExteraConfig.getPreferences().getStringSet(key, null);
        HashSet<String> copy = set == null ? new HashSet<>() : new HashSet<>(set);
        if (copy.add(String.valueOf(userId))) {
            SharedPreferences.Editor editor = ExteraConfig.getPreferences().edit();
            editor.putStringSet(key, copy);
            editor.apply();
        }
    }
}
