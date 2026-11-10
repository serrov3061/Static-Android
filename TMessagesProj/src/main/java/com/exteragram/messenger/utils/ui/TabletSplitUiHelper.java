package com.exteragram.messenger.utils.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * exteraGram 13.1.1: resizable tablet split. A 12dp gutter with a drag handle sits between the left (dialogs)
 * and the right (chat) pane; dragging it changes the left pane width (320dp .. 60% of the width, keeping at
 * least 360dp for the right pane), snapping to the default 35%. The fraction is stored in
 * {@link ExteraConfig#getTabletLeftFraction()}.
 */
public final class TabletSplitUiHelper {

    public interface Callback {
        boolean canResize();

        void onResized();
    }

    private static final float DEFAULT_FRACTION = 0.35f;

    private final ViewGroup container;
    private final Callback callback;
    private final HandleView handleView;
    private final int touchSlop;

    private int insetLeft;
    private int contentWidth;
    private int leftWidth;
    private int defaultWidth;
    private int startWidth;
    private int rawWidth;
    private int dragWidth;
    private float startX;
    private float startY;
    private boolean tracking;
    private boolean dragging;
    private boolean snapped;
    private float snapProgress;
    private ValueAnimator snapAnimator;
    private PointerIcon resizeIcon;

    public TabletSplitUiHelper(ViewGroup container, Callback callback) {
        this.container = container;
        this.callback = callback;
        this.handleView = new HandleView(container.getContext());
        this.touchSlop = ViewConfiguration.get(container.getContext()).getScaledTouchSlop();
    }

    private static int getGutterWidth() {
        return AndroidUtilities.dp(12);
    }

    private static int getLeftWidth(int contentWidth, float fraction) {
        int min = AndroidUtilities.dp(320);
        int max = Math.max(min, Math.min((int) (contentWidth * 0.6f), contentWidth - getGutterWidth() - AndroidUtilities.dp(360)));
        return Utilities.clamp(Math.round(contentWidth * fraction), max, min);
    }

    /** Left inset + left pane + gutter: where the right pane starts. */
    public static int getRightPaneStart(int fullWidth, int insetLeft, int insetRight) {
        return insetLeft + getLeftWidth(fullWidth - insetLeft - insetRight, getFraction()) + getGutterWidth();
    }

    private static float getFraction() {
        float fraction;
        try {
            fraction = ExteraConfig.getTabletLeftFraction();
        } catch (Throwable e) {
            fraction = DEFAULT_FRACTION;
        }
        if (Float.isNaN(fraction) || fraction <= 0f || fraction >= 1f) {
            fraction = DEFAULT_FRACTION;
        }
        return fraction;
    }

    public View getHandleView() {
        return handleView;
    }

    /** Left inset + left pane width. */
    public int getLeftPaneWidth() {
        return leftWidth;
    }

    public int getRightPaneStart() {
        return leftWidth + getGutterWidth();
    }

    public void update(int width, int insetLeft, int insetRight) {
        this.insetLeft = insetLeft;
        this.contentWidth = width - insetLeft - insetRight;
        this.leftWidth = insetLeft + (dragging ? dragWidth : getLeftWidth(contentWidth, getFraction()));
    }

    public void measureHandle(int height) {
        handleView.measure(
            View.MeasureSpec.makeMeasureSpec(getGutterWidth() + AndroidUtilities.dp(16) * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        );
    }

    public void layoutHandle() {
        int left = leftWidth - AndroidUtilities.dp(16);
        handleView.layout(left, 0, left + handleView.getMeasuredWidth(), handleView.getMeasuredHeight());
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        return handleTouch(event, true);
    }

    public boolean onTouchEvent(MotionEvent event) {
        return handleTouch(event, false);
    }

    public PointerIcon onResolvePointerIcon(MotionEvent event) {
        if (Build.VERSION.SDK_INT < 24) {
            return null;
        }
        if (!dragging && !isOverHandle(event.getX())) {
            return null;
        }
        if (resizeIcon == null) {
            resizeIcon = PointerIcon.getSystemIcon(container.getContext(), PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW);
        }
        return resizeIcon;
    }

    private boolean isOverHandle(float x) {
        return handleView.getVisibility() == View.VISIBLE && contentWidth > 0
            && Math.abs(x - leftWidth - getGutterWidth() / 2f) <= AndroidUtilities.dp(14)
            && callback != null && callback.canResize();
    }

    private boolean handleTouch(MotionEvent event, boolean intercept) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                tracking = isOverHandle(event.getX());
                startX = event.getX();
                startY = event.getY();
                return tracking && !intercept;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) {
                    return false;
                }
                if (!dragging) {
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                        tracking = false;
                        return false;
                    }
                    if (Math.abs(dx) <= touchSlop) {
                        return !intercept;
                    }
                    startDrag(event.getX());
                }
                updateDrag(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasDragging = dragging;
                tracking = false;
                if (wasDragging) {
                    finishDrag();
                }
                return wasDragging;
        }
        return false;
    }

    private void startDrag(float x) {
        dragging = true;
        startX = x;
        startWidth = rawWidth = dragWidth = leftWidth - insetLeft;
        defaultWidth = getLeftWidth(contentWidth, DEFAULT_FRACTION);
        snapped = startWidth == defaultWidth;
        snapProgress = snapped ? 1f : 0f;
        ViewParent parent = container.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        handleView.invalidate();
    }

    private void updateDrag(float x) {
        if (contentWidth <= 0) {
            return;
        }
        rawWidth = getLeftWidth(contentWidth, (startWidth + x - startX) / contentWidth);
        int distance = Math.abs(rawWidth - defaultWidth);
        boolean snap = distance < AndroidUtilities.dp(snapped ? 32 : 12);
        if (snap != snapped) {
            snapped = snap;
            if (snap) {
                AndroidUtilities.vibrateCursor(handleView);
            }
            animateSnap(snap);
        }
        applyDragWidth();
    }

    private void animateSnap(boolean snap) {
        if (snapAnimator != null) {
            snapAnimator.cancel();
        }
        snapAnimator = ValueAnimator.ofFloat(snapProgress, snap ? 1f : 0f);
        snapAnimator.addUpdateListener(animation -> {
            snapProgress = (float) animation.getAnimatedValue();
            applyDragWidth();
        });
        snapAnimator.setDuration(220);
        snapAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        snapAnimator.start();
    }

    private void applyDragWidth() {
        if (!dragging) {
            return;
        }
        int width = AndroidUtilities.lerp(rawWidth, defaultWidth, snapProgress);
        if (width != dragWidth) {
            dragWidth = width;
            container.requestLayout();
        }
    }

    private void finishDrag() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
        if (snapped) {
            dragWidth = defaultWidth;
        }
        dragging = false;
        boolean changed = dragWidth != startWidth;
        if (changed && contentWidth > 0) {
            ExteraConfig.setTabletLeftFraction(snapped ? DEFAULT_FRACTION : dragWidth / (float) contentWidth);
        }
        container.requestLayout();
        handleView.invalidate();
        if (changed && callback != null) {
            callback.onResized();
        }
    }

    private final class HandleView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path gutterPath = new Path();
        private final Path cornerPath = new Path();
        private final Path circlePath = new Path();
        private final AnimatedFloat activeProgress = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);

        HandleView(Context context) {
            super(context);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float r = AndroidUtilities.dp(16);
            float right = w - r;
            gutterPath.rewind();
            gutterPath.addRect(r, 0, right, h, Path.Direction.CW);
            // the gutter background continues into rounded inner corners of both panes
            addCorner(r, 0, r - r, r);
            addCorner(r, h, r - r, h - r);
            addCorner(right, 0, right + r, r);
            addCorner(right, h, right + r, h - r);
        }

        private void addCorner(float x1, float y1, float cx, float cy) {
            float r = AndroidUtilities.dp(16);
            cornerPath.rewind();
            cornerPath.addRect(Math.min(x1, cx), Math.min(y1, cy), Math.max(x1, cx), Math.max(y1, cy), Path.Direction.CW);
            circlePath.rewind();
            circlePath.addCircle(cx, cy, r, Path.Direction.CW);
            cornerPath.op(circlePath, Path.Op.DIFFERENCE);
            gutterPath.op(cornerPath, Path.Op.UNION);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float active = activeProgress.set(dragging);
            float handleWidth = AndroidUtilities.lerp(AndroidUtilities.dpf2(4), AndroidUtilities.dpf2(6), active);
            float handleHeight = AndroidUtilities.lerp(AndroidUtilities.dpf2(36), AndroidUtilities.dpf2(72), active);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            canvas.drawPath(gutterPath, paint);
            paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), AndroidUtilities.lerp(0.5f, 1f, active)));
            RectF rect = AndroidUtilities.rectTmp;
            rect.set(cx - handleWidth / 2f, cy - handleHeight / 2f, cx + handleWidth / 2f, cy + handleHeight / 2f);
            canvas.drawRoundRect(rect, handleWidth / 2f, handleWidth / 2f, paint);
        }
    }
}
