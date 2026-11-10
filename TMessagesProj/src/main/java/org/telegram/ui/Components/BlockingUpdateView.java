package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * The mandatory update screen (help.appUpdate with can_not_skip). It covers the whole app and
 * cannot be dismissed; the content (progress, retry, browser fallback) is {@link SgAppUpdateView}.
 */
public class BlockingUpdateView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final SgAppUpdateView updateView;
    private TLRPC.TL_help_appUpdate appUpdate;
    private int accountNum;
    private int pressCount;
    private int checkRequestId;

    public BlockingUpdateView(final Context context) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        updateView = new SgAppUpdateView(context, true, false, null);
        addView(updateView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            updateView.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        updateView.setPadding(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight);

        // hidden escape hatch kept from upstream: ten taps on the header icon hide the screen
        updateView.setOnHeaderClick(() -> {
            if (++pressCount >= 10) {
                setVisibility(GONE);
                SharedConfig.pendingAppUpdate = null;
                SharedConfig.saveConfig();
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // forced: nothing under this screen may receive touches
        return true;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == GONE) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
            if (checkRequestId != 0) {
                ConnectionsManager.getInstance(accountNum).cancelRequest(checkRequestId, false);
                checkRequestId = 0;
            }
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
    }

    private void hide() {
        setVisibility(GONE);
    }

    public void show(int account, TLRPC.TL_help_appUpdate update, boolean check) {
        pressCount = 0;
        appUpdate = update;
        accountNum = account;
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        updateView.setAppUpdate(account, update);
        if (check && ApplicationLoader.isStandaloneBuild() && checkRequestId == 0) {
            recheck();
        }
    }

    /**
     * Asks the server again whether this mandatory update still stands: the release may have been
     * replaced by a newer one, made optional, or withdrawn (help.noAppUpdate). Upstream ignored
     * noAppUpdate, which left users on a mandatory screen for a release that no longer exists.
     */
    private void recheck() {
        TLRPC.TL_help_getAppUpdate req = new TLRPC.TL_help_getAppUpdate();
        try {
            req.source = ApplicationLoader.applicationContext.getPackageManager().getInstallerPackageName(ApplicationLoader.applicationContext.getPackageName());
        } catch (Exception ignore) {

        }
        if (req.source == null) {
            req.source = "";
        }
        final int account = accountNum;
        checkRequestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            checkRequestId = 0;
            if (getVisibility() != VISIBLE) {
                return;
            }
            if (response instanceof TLRPC.TL_help_appUpdate) {
                final TLRPC.TL_help_appUpdate res = (TLRPC.TL_help_appUpdate) response;
                if (!res.can_not_skip) {
                    FileLog.d("sg_update: mandatory update " + (appUpdate != null ? appUpdate.version : null) + " is optional now");
                    hide();
                    SharedConfig.pendingAppUpdate = null;
                    SharedConfig.setNewAppVersionAvailable(res);
                    SharedConfig.saveConfig();
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                } else if (appUpdate == null || res.id != appUpdate.id || !TextUtils.equals(res.version, appUpdate.version)) {
                    if (SharedConfig.setNewAppVersionAvailable(res)) {
                        show(account, res, false);
                    } else {
                        // the server's mandatory release is not newer than this build: the shown one is stale
                        hide();
                        SharedConfig.pendingAppUpdate = null;
                        SharedConfig.saveConfig();
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                }
            } else if (response instanceof TLRPC.TL_help_noAppUpdate) {
                FileLog.d("sg_update: mandatory update " + (appUpdate != null ? appUpdate.version : null) + " withdrawn");
                hide();
                SharedConfig.pendingAppUpdate = null;
                SharedConfig.saveConfig();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            }
        }));
    }
}
