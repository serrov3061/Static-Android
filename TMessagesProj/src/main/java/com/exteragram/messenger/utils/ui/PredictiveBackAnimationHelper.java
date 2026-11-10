package com.exteragram.messenger.utils.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ViewPagerActivity;

import java.util.List;

/**
 * exteraGram 13.1.1 PredictiveBackAnimationHelper.kt: the Android 14+ predictive back animation of a fragment.
 * The closing screen shrinks to 85% towards the gesture edge, follows the finger vertically and rounds its
 * corners; the screen below enters from the left, scaled; a scrim dims it. After release the closing screen
 * slides away with the Material "emphasized" curve and fades out. Also the route background drawn behind both
 * screens of the spring / AOSP routes.
 */
public final class PredictiveBackAnimationHelper {

    private final RectF startClosingRect = new RectF();
    private final RectF targetClosingRect = new RectF();
    private final RectF currentClosingRect = new RectF();
    private final RectF startEnteringRect = new RectF();
    private final RectF targetEnteringRect = new RectF();
    private final RectF currentEnteringRect = new RectF();
    private final RectF commitStartClosingRect = new RectF();
    private final RectF commitTargetClosingRect = new RectF();
    private final RectF commitStartEnteringRect = new RectF();
    private final RectF commitTargetEnteringRect = new RectF();
    private final Interpolator gestureInterpolator = new PathInterpolator(0.1f, 0.1f, 0.0f, 1.0f);
    private final Interpolator postCommitInterpolator = createEmphasizedInterpolator();
    private final Interpolator verticalMoveInterpolator = new DecelerateInterpolator();
    private float progress;
    private float interpolatedProgress;
    private float closingAlpha = 1.0f;
    private float scrimAlphaMultiplier = 1.0f;
    private float initialTouchY;
    private float startCornerRadius;
    private float targetCornerRadius;
    private float currentCornerRadius;

    /** Gesture progress scaled by the "predictive back intensity" setting (0..2). */
    public static float applyIntensity(float progress) {
        return Utilities.clamp01(progress * Utilities.clamp(com.exteragram.messenger.ExteraConfig.getPredictiveBackIntensity(), 2.0f, 0.0f));
    }

    private static Interpolator createEmphasizedInterpolator() {
        Path path = new Path();
        path.moveTo(0f, 0f);
        path.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f);
        path.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f);
        return new PathInterpolator(path);
    }

    private static void interpolate(RectF out, RectF from, RectF to, float t) {
        out.set(
            AndroidUtilities.lerp(from.left, to.left, t),
            AndroidUtilities.lerp(from.top, to.top, t),
            AndroidUtilities.lerp(from.right, to.right, t),
            AndroidUtilities.lerp(from.bottom, to.bottom, t)
        );
    }

    private static void scaleCentered(RectF rect, float scale) {
        float cx = rect.centerX();
        float cy = rect.centerY();
        float hw = rect.width() * scale / 2f;
        float hh = rect.height() * scale / 2f;
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /**
     * @param width        closing screen width
     * @param height       layout height
     * @param touchY       gesture start Y
     * @param fromLeft     gesture started on the left edge (the screen moves to the right)
     * @param cornerRadius screen corner radius at rest
     */
    public void start(int width, int height, float touchY, boolean fromLeft, float cornerRadius) {
        initialTouchY = touchY;
        progress = 0f;
        interpolatedProgress = 0f;
        closingAlpha = 1.0f;
        scrimAlphaMultiplier = 1.0f;
        startCornerRadius = cornerRadius;
        targetCornerRadius = Math.max(cornerRadius, AndroidUtilities.dp(40));
        currentCornerRadius = cornerRadius;
        startClosingRect.set(0, 0, Math.max(1, width), Math.max(1, height));
        targetClosingRect.set(startClosingRect);
        scaleCentered(targetClosingRect, 0.85f);
        if (fromLeft) {
            targetClosingRect.offset(startClosingRect.right - targetClosingRect.right - AndroidUtilities.dp(8), 0);
        }
        currentClosingRect.set(startClosingRect);
        startEnteringRect.set(startClosingRect);
        scaleCentered(startEnteringRect, Utilities.clamp((startClosingRect.height() - cornerRadius * 2f) / startClosingRect.height(), 0.95f, 0.85f));
        startEnteringRect.offset(-Math.max(startEnteringRect.width() * 0.14999998f, AndroidUtilities.dp(96)), 0);
        targetEnteringRect.set(startEnteringRect);
        scaleCentered(targetEnteringRect, 0.85f);
        currentEnteringRect.set(startEnteringRect);
    }

    public void update(float progress, float touchY) {
        this.progress = Utilities.clamp01(progress);
        interpolatedProgress = gestureInterpolator.getInterpolation(this.progress);
        closingAlpha = 1.0f;
        scrimAlphaMultiplier = 1.0f;
        interpolate(currentClosingRect, startClosingRect, targetClosingRect, interpolatedProgress);
        float yOffset = getYOffset(currentClosingRect.height(), touchY);
        currentClosingRect.offset(0, yOffset);
        interpolate(currentEnteringRect, startEnteringRect, targetEnteringRect, interpolatedProgress);
        currentEnteringRect.offset(0, yOffset);
        currentCornerRadius = AndroidUtilities.lerp(startCornerRadius, targetCornerRadius, interpolatedProgress);
    }

    private float getYOffset(float currentHeight, float touchY) {
        float height = startClosingRect.height();
        float dy = touchY - initialTouchY;
        float sign = dy < 0 ? -1f : 1f;
        float half = height / 2f;
        float fraction = Math.min(half, Math.abs(dy)) / half;
        return sign * verticalMoveInterpolator.getInterpolation(fraction) * Math.max(0f, (height - currentHeight) / 2f - AndroidUtilities.dp(8));
    }

    /** Freezes the current rects as the start of the post-commit animation. */
    public void prepareCommit() {
        commitStartClosingRect.set(currentClosingRect);
        commitStartEnteringRect.set(currentEnteringRect);
        commitTargetEnteringRect.set(startClosingRect);
        commitTargetClosingRect.set(startClosingRect);
        commitTargetClosingRect.offset(currentClosingRect.left + AndroidUtilities.dp(96), 0);
    }

    public void updateCommitProgress(float progress) {
        progress = Utilities.clamp01(progress);
        float t = postCommitInterpolator.getInterpolation(progress);
        closingAlpha = Math.max(1.0f - 5.0f * progress, 0f);
        scrimAlphaMultiplier = 1.0f - progress;
        interpolate(currentClosingRect, commitStartClosingRect, commitTargetClosingRect, t);
        interpolate(currentEnteringRect, commitStartEnteringRect, commitTargetEnteringRect, t);
        currentCornerRadius = AndroidUtilities.lerp(targetCornerRadius, startCornerRadius, t);
    }

    public void reset() {
        startClosingRect.setEmpty();
        targetClosingRect.setEmpty();
        currentClosingRect.setEmpty();
        startEnteringRect.setEmpty();
        targetEnteringRect.setEmpty();
        currentEnteringRect.setEmpty();
        commitStartClosingRect.setEmpty();
        commitTargetClosingRect.setEmpty();
        commitStartEnteringRect.setEmpty();
        commitTargetEnteringRect.setEmpty();
        progress = 0f;
        interpolatedProgress = 0f;
        closingAlpha = 1.0f;
        scrimAlphaMultiplier = 1.0f;
        initialTouchY = 0f;
        startCornerRadius = 0f;
        targetCornerRadius = 0f;
        currentCornerRadius = 0f;
    }

    public float getProgress() {
        return progress;
    }

    /** Inner translation used by the fragments' slide callbacks while the gesture is in progress. */
    public float getSlideDistance() {
        return interpolatedProgress * AndroidUtilities.dp(336);
    }

    public int getPostCommitDuration() {
        return 375;
    }

    public void getClosingRect(RectF out) {
        out.set(currentClosingRect);
    }

    public void getEnteringRect(RectF out) {
        out.set(currentEnteringRect);
    }

    public float getClosingScale() {
        float w = startClosingRect.width();
        return w > 0 ? currentClosingRect.width() / w : 1f;
    }

    public float getEnteringScale() {
        float w = startClosingRect.width();
        return w > 0 ? currentEnteringRect.width() / w : 1f;
    }

    public float getCornerRadius() {
        return currentCornerRadius;
    }

    public float getClosingAlpha() {
        return closingAlpha;
    }

    public void setScrimAlphaMultiplier(float multiplier) {
        scrimAlphaMultiplier = Utilities.clamp01(multiplier);
    }

    public int getScrimAlpha(boolean dark) {
        return (int) ((dark ? 0.8f : 0.2f) * 255f * scrimAlphaMultiplier);
    }

    /** exteraGram 13.1.1: the predictive back animation is used on Android 14+ unless its intensity is 0. */
    public static boolean isEnabled() {
        return android.os.Build.VERSION.SDK_INT >= 34 && com.exteragram.messenger.ExteraConfig.getPredictiveBackIntensity() > 0f;
    }

    public static Drawable getTransitionBackground(List<BaseFragment> stack, BaseFragment fragment) {
        if (stack != null && stack.size() > 1) {
            fragment = stack.get(stack.size() - 2);
        }
        while (fragment instanceof ViewPagerActivity) {
            BaseFragment visible = ((ViewPagerActivity) fragment).getCurrentVisibleFragment();
            if (visible == null || visible == fragment) {
                break;
            }
            fragment = visible;
        }
        if (fragment instanceof ProfileActivity) {
            return new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray, fragment.getResourceProvider()));
        }
        View view = fragment != null ? fragment.fragmentView : null;
        Drawable background = view != null ? view.getBackground() : null;
        if (background instanceof ColorDrawable && Color.alpha(((ColorDrawable) background).getColor()) == 0) {
            background = null;
        }
        if (background != null) {
            return background;
        }
        return new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray, fragment != null ? fragment.getResourceProvider() : null));
    }

    public static void drawTransitionBackground(Canvas canvas, Drawable drawable, int width, int height, Rect tmp) {
        if (drawable == null) {
            return;
        }
        drawable.copyBounds(tmp);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        drawable.setBounds(tmp);
    }
}
