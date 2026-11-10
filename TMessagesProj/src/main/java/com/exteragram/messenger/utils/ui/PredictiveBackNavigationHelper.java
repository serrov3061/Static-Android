package com.exteragram.messenger.utils.ui;

import android.view.View;
import android.view.ViewTreeObserver;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.RequiresApi;

import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.GroupCallPip;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.ViewPagerActivity;

import java.util.function.BooleanSupplier;

/**
 * exteraGram 13.1.1: keeps the app back callback registered only while the app has something to handle on
 * back. On the idle root chat list it is unregistered, so Android 13+ plays the system back-to-home
 * (predictive back) animation. Re-checked before every frame and on window focus.
 */
@RequiresApi(33)
public final class PredictiveBackNavigationHelper implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnWindowFocusChangeListener {

    private static final Class<?>[] BACK_HANDLER_CLASSES = {
        LaunchActivity.class, ActionBarLayout.class, BaseFragment.class, ViewPagerActivity.class, MainTabsActivity.class, DialogsActivity.class
    };

    private final LaunchActivity activity;
    private final View decorView;
    private final OnBackInvokedCallback callback;
    private final BooleanSupplier canUseSystemBack;
    private boolean registered;
    private boolean detached;
    private boolean gestureInProgress;

    /** @param blockSystemBack true while the app must keep the back callback (e.g. switching accounts) */
    public PredictiveBackNavigationHelper(LaunchActivity activity, OnBackInvokedCallback callback, BooleanSupplier blockSystemBack) {
        this.activity = activity;
        this.decorView = activity.getWindow().getDecorView();
        this.callback = callback;
        this.canUseSystemBack = () -> !blockSystemBack.getAsBoolean() && canUseSystemBack(activity);
        decorView.getViewTreeObserver().addOnPreDrawListener(this);
        decorView.getViewTreeObserver().addOnWindowFocusChangeListener(this);
        update();
    }

    /** Requests a re-check on the next frame. */
    public static void invalidate() {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity != null && activity.getWindow() != null) {
            activity.getWindow().getDecorView().invalidate();
        }
    }

    private static boolean hasFragments(INavigationLayout layout) {
        return layout != null && !layout.getFragmentStack().isEmpty();
    }

    private static boolean canUseSystemBack(LaunchActivity activity) {
        final INavigationLayout layout = activity.getActionBarLayout();
        if (!(layout instanceof ActionBarLayout) || !((ActionBarLayout) layout).canUseSystemBack()) {
            return false;
        }
        if (hasFragments(activity.getLayersActionBarLayout()) || hasFragments(activity.getRightActionBarLayout())) {
            return false;
        }
        if (PluginsController.getInstance().hasXposedHook("onBackPressed", BACK_HANDLER_CLASSES)) {
            return false;
        }
        if (GroupCallPip.onBackPressed(false) || !activity.onBackPressed(false)) {
            return false;
        }
        final BaseFragment lastFragment = layout.getLastFragment();
        if (lastFragment instanceof MainTabsActivity) {
            return ((MainTabsActivity) lastFragment).canUseSystemBack();
        }
        return lastFragment instanceof DialogsActivity && ((DialogsActivity) lastFragment).canUseSystemBack();
    }

    private void update() {
        if (detached || gestureInProgress || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        boolean systemBack;
        try {
            systemBack = canUseSystemBack.getAsBoolean();
        } catch (Throwable e) {
            FileLog.e(e);
            systemBack = false;
        }
        final boolean register = !systemBack;
        if (registered == register) {
            return;
        }
        try {
            if (register) {
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            } else {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
            }
            registered = register;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void setGestureInProgress(boolean inProgress) {
        gestureInProgress = inProgress;
        if (!inProgress) {
            decorView.invalidate();
        }
    }

    @Override
    public boolean onPreDraw() {
        update();
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            update();
        }
    }

    public void detach() {
        detached = true;
        final ViewTreeObserver observer = decorView.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(this);
            observer.removeOnWindowFocusChangeListener(this);
        }
        if (registered) {
            try {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            registered = false;
        }
    }
}
