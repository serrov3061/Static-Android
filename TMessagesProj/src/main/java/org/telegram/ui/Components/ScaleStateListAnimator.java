package org.telegram.ui.Components;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import java.util.function.Consumer;

public class ScaleStateListAnimator {

    public static void apply(View view) {
        apply(view, .1f, 1.5f);
    }

    public static void apply(View view, float scale, float tension) {
        apply(view, scale, tension, null, null);
    }

    /**
     * exteraGram API: same as {@link #apply(View, float, float)} plus progress callbacks
     * (0..1 while pressing, 1..0 while releasing). Used by exteraGram plugins / dex modules.
     */
    public static void apply(View view, float scale, float tension, Consumer<Float> onPressed, Consumer<Float> onReleased) {
        if (view == null) {
            return;
        }

        AnimatorSet pressedAnimator = new AnimatorSet();
        pressedAnimator.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f - scale),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f - scale)
        );
        pressedAnimator.setDuration(80);

        AnimatorSet defaultAnimator = new AnimatorSet();
        defaultAnimator.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f)
        );
        defaultAnimator.setInterpolator(new OvershootInterpolator(tension));
        defaultAnimator.setDuration(350);

        StateListAnimator scaleStateListAnimator = new StateListAnimator();

        if (onPressed != null) {
            ValueAnimator pressedProgress = ValueAnimator.ofFloat(0f, 1f);
            pressedProgress.setDuration(80);
            pressedProgress.addUpdateListener(a -> onPressed.accept((Float) a.getAnimatedValue()));
            AnimatorSet pressedSet = new AnimatorSet();
            pressedSet.playTogether(pressedAnimator, pressedProgress);
            scaleStateListAnimator.addState(new int[]{android.R.attr.state_pressed}, pressedSet);
        } else {
            scaleStateListAnimator.addState(new int[]{android.R.attr.state_pressed}, pressedAnimator);
        }
        if (onReleased != null) {
            ValueAnimator releasedProgress = ValueAnimator.ofFloat(1f, 0f);
            releasedProgress.setDuration(350);
            releasedProgress.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            releasedProgress.addUpdateListener(a -> onReleased.accept((Float) a.getAnimatedValue()));
            AnimatorSet releasedSet = new AnimatorSet();
            releasedSet.playTogether(defaultAnimator, releasedProgress);
            scaleStateListAnimator.addState(new int[0], releasedSet);
        } else {
            scaleStateListAnimator.addState(new int[0], defaultAnimator);
        }

        view.setStateListAnimator(scaleStateListAnimator);
    }

    public static void reset(View view) {
        view.setStateListAnimator(null);
    }

}
