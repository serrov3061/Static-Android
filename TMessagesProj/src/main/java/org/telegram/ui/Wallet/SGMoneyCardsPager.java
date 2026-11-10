package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The money screen's cards, GRAM and Stars, paged left / right. The selected card sits in the
 * middle, the other one peeks from the edge, smaller and dimmed; both follow the finger and
 * settle like a pager. Vertical drags are handed to the list, horizontal ones never reach
 * the card's own tilt.
 */
@SuppressLint("ViewConstructor")
public class SGMoneyCardsPager extends FrameLayout {

    public interface Listener {
        void onScroll(float position);

        void onPageSelected(int page);
    }

    private static final int SIDE_DP = 18, PEEK_DP = 22;
    private static final float SCALE = .1f, ALPHA = .45f;

    private final View[] pages;
    private float position;
    private int selected;
    private ValueAnimator animator;
    private final int touchSlop, minFling;
    private VelocityTracker velocityTracker;
    private float downX, downY, startPosition, dragOriginX;
    private boolean dragging, released;
    private float lastDownX;
    private Listener listener;

    public SGMoneyCardsPager(Context context, View... pages) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        this.pages = pages;
        for (View p : pages) addView(p, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        final ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFling = dp(350);
        updatePageMotion();
    }

    /** Only the centred card follows the device tilt; the peeking one keeps still (and cached). */
    private void updatePageMotion() {
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] instanceof SGCardView) ((SGCardView) pages[i]).card.setMotionEnabled(i == selected);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getSelected() {
        return selected;
    }

    public float getPosition() {
        return position;
    }

    /** Where the last touch started, in this view's coordinates. */
    public float getLastDownX() {
        return lastDownX;
    }

    public boolean isDragging() {
        return dragging;
    }

    private int pageWidth(int width) {
        return Math.max(1, width - dp(SIDE_DP * 2));
    }

    /** Distance between card centres: the neighbour's (scaled) face starts PEEK_DP from the edge. */
    private float spacing() {
        final int w = getWidth();
        final float face = pageWidth(w) - dp(24);
        return w / 2f - dp(PEEK_DP) + (1f - SCALE) * face / 2f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int pw = pageWidth(w);
        int h = 0;
        for (View p : pages) {
            p.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            h = Math.max(h, p.getMeasuredHeight());
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int w = right - left;
        for (View p : pages) {
            final int l = (w - p.getMeasuredWidth()) / 2;
            p.layout(l, 0, l + p.getMeasuredWidth(), p.getMeasuredHeight());
        }
        applyTransforms();
    }

    private void applyTransforms() {
        final float spacing = spacing();
        for (int i = 0; i < pages.length; i++) {
            final View p = pages[i];
            final float d = i - position;
            final float ad = Math.min(1f, Math.abs(d));
            p.setTranslationX(d * spacing);
            final float scale = 1f - SCALE * ad;
            p.setScaleX(scale);
            p.setScaleY(scale);
            p.setAlpha(1f - ALPHA * ad);
            // A dimmed card is drawn through an offscreen buffer anyway; for the still side card
            // make it a kept hardware layer, so moving / scaling / fading it reuses the pixels
            // instead of replaying the whole card (material, stickers, text) every frame.
            final int layer = i != selected && ad > 0f ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (p.getLayerType() != layer) p.setLayerType(layer, null);
        }
    }

    private void setPosition(float p) {
        position = p;
        final int sel = Math.max(0, Math.min(pages.length - 1, Math.round(p)));
        final boolean changed = sel != selected;
        selected = sel;
        if (changed) updatePageMotion();
        applyTransforms();
        if (listener != null) listener.onScroll(Math.max(0, Math.min(pages.length - 1, p)));
        if (changed && listener != null) listener.onPageSelected(sel);
    }

    public void setPage(int page, boolean animated) {
        page = Math.max(0, Math.min(pages.length - 1, page));
        if (animator != null) animator.cancel();
        if (!animated || getWidth() <= 0) {
            setPosition(page);
            return;
        }
        animator = ValueAnimator.ofFloat(position, page);
        animator.setDuration(Math.max(220, (long) (420 * Math.min(1f, Math.abs(page - position)))));
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> setPosition((float) a.getAnimatedValue()));
        animator.start();
    }

    private static float rubber(float over) {
        return over * .25f / (1f + over);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);
        final float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastDownX = x;
                dragging = false;
                released = false;
                if (animator != null) animator.cancel();
                startPosition = position;
                // Keep the swipe-back gesture and the list away until the direction is known.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && !released) {
                    final float dx = x - downX, dy = y - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        dragOriginX = x;
                        final MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        // The card answers that CANCEL with requestDisallowInterceptTouchEvent(false), which
                        // re-armed the list (it stole diagonal swipes) and the swipe-back (a swipe from Stars
                        // to GRAM closed the screen). Take the gesture back for the rest of the drag.
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                        released = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                if (dragging) {
                    float p = startPosition - (x - dragOriginX) / Math.max(1f, spacing());
                    final float max = pages.length - 1;
                    if (p < 0) p = -rubber(-p);
                    else if (p > max) p = max + rubber(p - max);
                    setPosition(p);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    velocityTracker.computeCurrentVelocity(1000);
                    final float vx = velocityTracker.getXVelocity();
                    int target = Math.round(position);
                    if (ev.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vx) > minFling) {
                        target = vx < 0 ? (int) Math.ceil(position) : (int) Math.floor(position);
                    }
                    setPage(target, true);
                    dragging = false;
                    recycleTracker();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                if (position != Math.round(position)) setPage(Math.round(position), true);
                recycleTracker();
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Lets ActionBarLayout.findScrollingChild keep the swipe-back off while a card is to the left. */
    @Override
    public boolean canScrollHorizontally(int direction) {
        return direction < 0 ? position > 0.01f : position < pages.length - 1 - 0.01f;
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
}
