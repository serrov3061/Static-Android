package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

/**
 * StaticGram: launch animation. The starting window (staticgram_launch_bg, and the empty splash
 * icon on Android 12+) is a plain white/black screen; this overlay continues it on top of the
 * already created UI: a paper plane flies in and lands, the red gem springs up under it and the
 * plane becomes the cut-out (raw/staticgram_splash_{light,dark}, 1.5 s, baked from
 * logo-anim/v4/f.js). Then the overlay fades out while the logo zooms through. The UI below is
 * created and laid out, but the overlay swallows every touch until it is removed (before, taps
 * went through it: you could open a chat or press buttons during the animation). Playback starts
 * as soon as the overlay is attached, in the same frame as the first UI frame (it used to wait
 * for the first pre-draw plus a main-thread idle of up to 250 ms and only then begin decoding,
 * so the already black/white screen stayed empty for several hundred ms more). Skipped when system animations
 * are off. Shown once per process, also when the system restores the activity after killing the
 * idle process (that cold start used to get only the plain starting window).
 */
public class StaticGramLaunchSplash extends FrameLayout {

    private static final long EXIT_DURATION = 380;
    private static final long FALLBACK_TIMEOUT = 2600;
    // the gem takes 440 of the 1320 px lottie canvas (1080 + 120 on each side for the wing)
    private static final int GEM_DP = 157; // StaticGram: x1.4 (was 112)
    private static final int BOX_DP = GEM_DP * 1320 / 440;
    // frames are rendered at 3/4 of the box and scaled up: a quarter of the pixels to rasterize
    // while the app is still busy starting
    private static final int RENDER_DP = BOX_DP * 3 / 4;
    private static boolean shownInProcess;

    public static void attach(FrameLayout parent, boolean restored) {
        if (shownInProcess || parent == null) {
            return;
        }
        shownInProcess = true;
        try {
            final float scale = Settings.Global.getFloat(parent.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (scale <= 0f) {
                return;
            }
            parent.addView(new StaticGramLaunchSplash(parent.getContext()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private final RLottieImageView logo;
    private final int[] loc = new int[2];
    private boolean started, exiting;
    private final Runnable exitRunnable = this::exit;

    private StaticGramLaunchSplash(Context context) {
        super(context);
        final boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setBackgroundColor(night ? 0xFF000000 : 0xFFFFFFFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        // block the live UI below until the overlay is gone
        setClickable(true);
        logo = new RLottieImageView(context);
        logo.setAutoRepeat(false);
        logo.setAnimation(night ? R.raw.staticgram_splash_dark : R.raw.staticgram_splash_light, RENDER_DP, RENDER_DP);
        addView(logo, new FrameLayout.LayoutParams(dp(BOX_DP), dp(BOX_DP)));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // same place as the starting window: centered in the whole window
        final View root = getRootView();
        getLocationInWindow(loc);
        final float cx = (root != null ? root.getWidth() : getWidth()) / 2f - loc[0];
        final float cy = (root != null ? root.getHeight() : getHeight()) / 2f - loc[1];
        logo.setTranslationX(cx - logo.getLeft() - logo.getWidth() / 2f);
        logo.setTranslationY(cy - logo.getTop() - logo.getHeight() / 2f);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // start right away: the first frames are decoded in the background while the rest of
        // the activity start runs, so the plane is on screen with the first drawn frame
        start();
    }

    // swallow all touches (and hover/trackball) while the overlay is on screen
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        return true;
    }

    @Override
    public boolean dispatchTrackballEvent(android.view.MotionEvent event) {
        return true;
    }

    private void start() {
        if (started) return;
        started = true;
        final RLottieDrawable drawable = logo.getAnimatedDrawable();
        if (drawable == null) {
            exit();
            return;
        }
        drawable.setOnFinishCallback(exitRunnable, drawable.getFramesCount() - 1);
        logo.playAnimation();
        AndroidUtilities.runOnUIThread(exitRunnable, FALLBACK_TIMEOUT);
    }

    private void exit() {
        if (exiting) return;
        exiting = true;
        AndroidUtilities.cancelRunOnUIThread(exitRunnable);
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
            ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f),
            ObjectAnimator.ofFloat(logo, View.SCALE_X, 1f, 1.35f),
            ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1f, 1.35f)
        );
        set.setDuration(EXIT_DURATION);
        set.setInterpolator(CubicBezierInterpolator.EASE_IN);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                remove();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                remove();
            }
        });
        set.start();
    }

    private void remove() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(exitRunnable);
    }
}
