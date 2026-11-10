package com.exteragram.messenger.pillstack.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.List;

/** exteraGram: vertical stack of pills in the dialogs search field; swipe up/down switches pills. */
public class PillStackView extends FrameLayout {

    private ValueAnimator currentAnimator;
    private int currentIndex;
    private float currentSwipeProgress;
    private boolean isSwiping;
    private boolean isSwipingUp;
    private boolean longClickPerformed;
    private final Runnable longPressRunnable;
    private boolean maybeClick;
    private final List<BasePill> pills = new ArrayList<>();
    private boolean stackOnScreen = true;
    private float startX;
    private float startY;
    private final float touchSlop;
    private float visibilityFactor = -1.0f;

    public PillStackView(Context context) {
        super(context);
        longPressRunnable = () -> {
            if (!maybeClick || isSwiping || pills.isEmpty()) {
                return;
            }
            longClickPerformed = pills.get(currentIndex).onPillLongClicked();
            if (longClickPerformed) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                maybeClick = false;
                for (int i = 0; i < pills.size(); i++) {
                    pills.get(i).setPressed(false);
                }
            }
        };
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
    }

    public void addPill(BasePill pill) {
        pills.add(pill);
        addView(pill);
        if (pills.size() - 1 != currentIndex) {
            pill.setAlpha(0.0f);
            pill.setScaleX(0.8f);
            pill.setScaleY(0.8f);
            pill.setVisibility(GONE);
        } else {
            pill.setVisibility(VISIBLE);
            pill.onPillSelected();
        }
        pill.onStackVisibilityChanged(stackOnScreen);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (stackOnScreen == isVisible) {
            return;
        }
        stackOnScreen = isVisible;
        for (BasePill pill : pills) {
            pill.onStackVisibilityChanged(isVisible);
        }
    }

    public int getPillsCount() {
        return pills.size();
    }

    public void setCurrentIndex(int index) {
        if (index < 0 || index >= pills.size() || index == currentIndex) {
            return;
        }
        BasePill old = pills.get(currentIndex);
        old.setVisibility(GONE);
        old.onPillUnselected();
        currentIndex = index;
        BasePill pill = pills.get(index);
        pill.setVisibility(VISIBLE);
        pill.setAlpha(1.0f);
        pill.setScaleX(1.0f);
        pill.setScaleY(1.0f);
        pill.setTranslationY(0.0f);
        pill.onPillSelected();
        requestLayout();
    }

    public void clearPills() {
        if (!pills.isEmpty() && currentIndex < pills.size()) {
            pills.get(currentIndex).onPillUnselected();
        }
        pills.clear();
        removeAllViews();
        currentIndex = 0;
    }

    public void setVisibilityFactor(float factor) {
        if (visibilityFactor == factor) {
            return;
        }
        visibilityFactor = factor;
        if (factor > 0.01f) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
            setAlpha(factor);
            setScaleX(AndroidUtilities.lerp(0.6f, 1.0f, factor));
            setScaleY(AndroidUtilities.lerp(0.6f, 1.0f, factor));
            return;
        }
        setVisibility(GONE);
    }

    public void updateColors() {
        for (BasePill pill : pills) {
            pill.updateColors();
        }
    }

    private void beginSwipe(MotionEvent event) {
        isSwiping = true;
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        startY = event.getRawY() - (isSwipingUp ? -(currentSwipeProgress * getHeight()) : getHeight() * currentSwipeProgress);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (pills.isEmpty()) {
            return super.onInterceptTouchEvent(event);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = event.getRawX();
            startY = event.getRawY();
            isSwiping = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - startX;
            float dy = event.getRawY() - startY;
            if (Math.abs(dy) > touchSlop && pills.size() > 1) {
                beginSwipe(event);
                return true;
            }
        }
        return super.onInterceptTouchEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (pills.isEmpty()) {
            return super.onTouchEvent(event);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = event.getRawX();
            startY = event.getRawY();
            isSwiping = false;
            maybeClick = true;
            longClickPerformed = false;
            removeCallbacks(longPressRunnable);
            postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
            BasePill pill = pills.get(currentIndex);
            pill.setPressed(true);
            pill.drawableHotspotChanged(event.getX() - pill.getLeft(), event.getY() - pill.getTop());
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (longClickPerformed) {
                return true;
            }
            float dx = event.getRawX() - startX;
            float dy = event.getRawY() - startY;
            if (Math.abs(dy) > touchSlop || Math.abs(dx) > touchSlop) {
                maybeClick = false;
                removeCallbacks(longPressRunnable);
            }
            if (isSwiping) {
                handleSwipeProgress(dy);
                return true;
            }
            if (Math.abs(dy) > touchSlop && pills.size() > 1) {
                beginSwipe(event);
                return true;
            }
            return super.onTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPressRunnable);
            if (isSwiping) {
                finishSwipe(event.getRawY() - startY);
                isSwiping = false;
            } else if (maybeClick && !longClickPerformed && action == MotionEvent.ACTION_UP) {
                pills.get(currentIndex).onPillClicked();
            }
            for (int i = 0; i < pills.size(); i++) {
                pills.get(i).setPressed(false);
            }
            maybeClick = false;
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleSwipeProgress(float dy) {
        int height;
        if (pills.size() <= 1 || (height = getHeight()) <= 0) {
            return;
        }
        isSwipingUp = dy < 0.0f;
        float progress = Math.abs(dy) / height;
        int next = isSwipingUp ? currentIndex + 1 : currentIndex - 1;
        if (!PillStackConfig.getInfiniteScrolling() && (next >= pills.size() || next < 0)) {
            currentSwipeProgress = progress;
        } else {
            currentSwipeProgress = Math.min(progress, 1.0f);
        }
        applyProgress(currentSwipeProgress, isSwipingUp);
    }

    private void applyProgress(float progress, boolean up) {
        BasePill current = pills.get(currentIndex);
        int next = up ? currentIndex + 1 : currentIndex - 1;
        if (PillStackConfig.getInfiniteScrolling()) {
            if (next >= pills.size()) {
                next = 0;
            }
            if (next < 0) {
                next = pills.size() - 1;
            }
        }
        for (int i = 0; i < pills.size(); i++) {
            if (i != currentIndex && i != next && pills.get(i).getVisibility() != GONE) {
                pills.get(i).setVisibility(GONE);
            }
        }
        if (!PillStackConfig.getInfiniteScrolling() && (next >= pills.size() || next < 0)) {
            float translation = getHeight() * ((float) (1.0d - (1.0d / ((progress * 0.18f) + 1.0d))));
            current.setTranslationY(up ? -translation : translation);
            current.setAlpha(1.0f);
            return;
        }
        float p = Math.min(progress, 1.0f);
        BasePill nextPill = pills.get(next);
        if (nextPill.getVisibility() != VISIBLE) {
            nextPill.setVisibility(VISIBLE);
        }
        float translation = getHeight() * p;
        current.setTranslationY(up ? -translation : translation);
        current.setAlpha(1.0f - p);
        float scaleOut = 1.0f - 0.2f * p;
        current.setScaleX(scaleOut);
        current.setScaleY(scaleOut);
        float scaleIn = 0.2f * p + 0.8f;
        nextPill.setScaleX(scaleIn);
        nextPill.setScaleY(scaleIn);
        nextPill.setAlpha(p);
        float from = up ? getHeight() : -getHeight();
        nextPill.setTranslationY(from - p * from);
    }

    private void finishSwipe(float dy) {
        int height = getHeight();
        if (height <= 0) {
            cancelSwipe(isSwipingUp);
            return;
        }
        boolean canMove = true;
        if (!PillStackConfig.getInfiniteScrolling()) {
            int next = isSwipingUp ? currentIndex + 1 : currentIndex - 1;
            if (next >= pills.size() || next < 0) {
                canMove = false;
            }
        }
        if (Math.abs(dy) > height * 0.25f && canMove) {
            animateToNextPill(isSwipingUp);
        } else {
            cancelSwipe(isSwipingUp);
        }
    }

    private void animateToNextPill(final boolean up) {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        currentAnimator = ValueAnimator.ofFloat(currentSwipeProgress, 1.0f);
        currentAnimator.setDuration(250L);
        currentAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        currentAnimator.addUpdateListener(a -> applyProgress((Float) a.getAnimatedValue(), up));
        currentAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) {
                    return;
                }
                BasePill old = pills.get(currentIndex);
                old.setVisibility(GONE);
                old.setPressed(false);
                old.setScaleX(1.0f);
                old.setScaleY(1.0f);
                old.onPillUnselected();
                currentIndex = up ? currentIndex + 1 : currentIndex - 1;
                if (PillStackConfig.getInfiniteScrolling()) {
                    if (currentIndex >= pills.size()) {
                        currentIndex = 0;
                    }
                    if (currentIndex < 0) {
                        currentIndex = pills.size() - 1;
                    }
                }
                for (int i = 0; i < pills.size(); i++) {
                    if (i != currentIndex) {
                        pills.get(i).setVisibility(GONE);
                    }
                }
                BasePill pill = pills.get(currentIndex);
                pill.setVisibility(VISIBLE);
                pill.setScaleX(1.0f);
                pill.setScaleY(1.0f);
                pill.setTranslationY(0.0f);
                pill.setAlpha(1.0f);
                pill.onPillSelected();
                currentSwipeProgress = 0.0f;
                PillStackConfig.saveLastActivePillId(pill.getPillId());
            }
        });
        currentAnimator.start();
    }

    private void cancelSwipe(final boolean up) {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        currentAnimator = ValueAnimator.ofFloat(currentSwipeProgress, 0.0f);
        currentAnimator.setDuration(200L);
        currentAnimator.addUpdateListener(a -> applyProgress((Float) a.getAnimatedValue(), up));
        currentAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) {
                    return;
                }
                for (int i = 0; i < pills.size(); i++) {
                    if (i != currentIndex) {
                        BasePill pill = pills.get(i);
                        pill.setVisibility(GONE);
                        pill.setPressed(false);
                        pill.setScaleX(1.0f);
                        pill.setScaleY(1.0f);
                    }
                }
                BasePill pill = pills.get(currentIndex);
                pill.setTranslationY(0.0f);
                pill.setAlpha(1.0f);
                pill.setScaleX(1.0f);
                pill.setScaleY(1.0f);
                currentSwipeProgress = 0.0f;
            }
        });
        currentAnimator.start();
    }
}
