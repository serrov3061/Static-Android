package com.exteragram.messenger.utils.ui;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import me.vkryl.android.animator.FactorAnimator;

/**
 * exteraGram 13.1.1: spring-driven visibility of the bottom tabs (and of offsets that follow them). Reports
 * changes through {@link FactorAnimator.Target} like BoolAnimator; an optional faster critically damped
 * spring drives the alpha.
 */
public final class TabsVisibilityAnimator {

    private final int id;
    private final FactorAnimator.Target target;
    private final FloatValueHolder positionHolder;
    private final SpringAnimation positionSpring;
    private final FloatValueHolder alphaHolder;
    private final SpringAnimation alphaSpring;
    private boolean value;
    private float floatValue;
    private float alphaValue;

    public TabsVisibilityAnimator(int id, FactorAnimator.Target target, boolean value, boolean separateAlpha) {
        this.id = id;
        this.target = target;
        this.value = value;
        float f = value ? 1.0f : 0.0f;
        this.floatValue = f;
        this.alphaValue = f;
        positionHolder = new FloatValueHolder(f);
        positionSpring = new SpringAnimation(positionHolder).setMinimumVisibleChange(0.001f).setSpring(new SpringForce(f));
        positionSpring.addUpdateListener((animation, v, velocity) -> {
            if (floatValue != v) {
                floatValue = v;
                notifyChanged();
            }
        });
        positionSpring.addEndListener((animation, canceled, v, velocity) -> {
            if (!canceled) {
                target.onFactorChangeFinished(id, v, null);
            }
        });
        if (separateAlpha) {
            alphaHolder = new FloatValueHolder(f);
            alphaSpring = new SpringAnimation(alphaHolder).setMinimumVisibleChange(0.001f)
                .setSpring(new SpringForce(f).setStiffness(1600.0f).setDampingRatio(1.0f));
            alphaSpring.addUpdateListener((animation, v, velocity) -> {
                if (alphaValue != v) {
                    alphaValue = v;
                    notifyChanged();
                }
            });
        } else {
            alphaHolder = null;
            alphaSpring = null;
        }
    }

    public void setValue(boolean value, boolean animated) {
        if (this.value == value && animated) {
            return;
        }
        this.value = value;
        float f = value ? 1.0f : 0.0f;
        if (animated) {
            boolean m3 = MainTabsUiHelper.isMaterial3NavigationBar();
            positionSpring.getSpring().setStiffness(m3 ? 700.0f : 380.0f).setDampingRatio(m3 ? 0.9f : 0.8f);
            positionSpring.animateToFinalPosition(f);
            if (alphaSpring != null) {
                alphaSpring.animateToFinalPosition(f);
            }
            return;
        }
        boolean changed = floatValue != f || alphaValue != f;
        snapTo(f);
        if (changed) {
            notifyChanged();
            target.onFactorChangeFinished(id, f, null);
        }
    }

    public void changeValueSilently(boolean value) {
        this.value = value;
    }

    public void changeValueSilently(float value) {
        snapTo(value);
    }

    public boolean getValue() {
        return value;
    }

    public float getFloatValue() {
        return floatValue;
    }

    public float getAlphaValue() {
        return alphaValue;
    }

    private void snapTo(float f) {
        positionSpring.cancel();
        positionHolder.setValue(f);
        floatValue = f;
        if (alphaSpring != null && alphaHolder != null) {
            alphaSpring.cancel();
            alphaHolder.setValue(f);
        }
        alphaValue = f;
    }

    private void notifyChanged() {
        target.onFactorChanged(id, floatValue, -1.0f, null);
    }
}
