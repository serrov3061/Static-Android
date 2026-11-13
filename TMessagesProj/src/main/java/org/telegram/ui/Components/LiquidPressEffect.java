package org.telegram.ui.Components;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.ScrollView;

import androidx.core.widget.NestedScrollView;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;

import java.util.ArrayList;

/**
 * Press feedback of the glass buttons: the content follows the finger a little (rubber band offset),
 * swells while pressed and gets a soft highlight around the touch point. Springs, no allocations per frame.
 * With the power saver on the press is shown without the springy motion.
 */
public class LiquidPressEffect {

    private static final float DAMPING = 0.5f;
    private static final float STIFFNESS = 300f;
    private static final ArrayList<LiquidPressEffect> touching = new ArrayList<>();

    private final View view;
    private final FloatValueHolder press = new FloatValueHolder(0f);
    private final FloatValueHolder offsetX = new FloatValueHolder(0f);
    private final FloatValueHolder offsetY = new FloatValueHolder(0f);
    private final SpringAnimation pressAnimation;
    private final SpringAnimation offsetXAnimation;
    private final SpringAnimation offsetYAnimation;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient spotShader;
    private final Matrix spotMatrix = new Matrix();
    private final Path clip = new Path();

    private final ArrayList<ViewGroup> unclipped = new ArrayList<>();
    private final ArrayList<ViewGroup> unclippedPadding = new ArrayList<>();
    private int unclipLevels;

    private boolean touched;
    private float startX, startY;
    private float touchX, touchY;
    private float curTx, curTy;
    private float curScaleX = 1f, curScaleY = 1f;

    public LiquidPressEffect(View view) {
        this.view = view;
        pressAnimation = spring(press, 0.001f);
        offsetXAnimation = spring(offsetX, DAMPING);
        offsetYAnimation = spring(offsetY, DAMPING);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                cancelPress();
            }
        });
        if (Build.VERSION.SDK_INT >= 29) {
            glowPaint.setBlendMode(BlendMode.PLUS);
            spotPaint.setBlendMode(BlendMode.PLUS);
        } else {
            glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            spotPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }
        glowPaint.setColor(0xFFFFFFFF);
        spotPaint.setColor(0xFFFFFFFF);
        spotShader = new RadialGradient(0, 0, 1f, new int[] { 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF }, new float[] { 0f, DAMPING, 1f }, Shader.TileMode.CLAMP);
        spotPaint.setShader(spotShader);
    }

    private SpringAnimation spring(FloatValueHolder holder, float minimumVisibleChange) {
        final SpringAnimation animation = new SpringAnimation(holder);
        final SpringForce force = new SpringForce();
        force.setDampingRatio(DAMPING);
        force.setStiffness(STIFFNESS);
        animation.setSpring(force);
        animation.setMinimumVisibleChange(minimumVisibleChange);
        animation.addUpdateListener((a, value, velocity) -> view.invalidate());
        animation.addEndListener((a, canceled, value, velocity) -> restoreClipping());
        return animation;
    }

    private static boolean animated() {
        return !LiteMode.isPowerSaverApplied();
    }

    public void setUnclipParents(int levels) {
        unclipLevels = levels;
    }

    public static void releaseStale(View root) {
        for (int i = touching.size() - 1; i >= 0; --i) {
            final LiquidPressEffect effect = touching.get(i);
            if (!effect.view.isAttachedToWindow() || effect.view.getRootView() == root) {
                effect.cancelPress();
            }
        }
    }

    public void cancelPress() {
        if (touched) {
            onTouch(MotionEvent.ACTION_CANCEL, touchX, touchY);
        }
    }

    public void onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN || (view.isEnabled() && view.isClickable())) {
            onTouch(event.getActionMasked(), event.getX(), event.getY());
        }
    }

    public void onTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (!touched) {
                touched = true;
                touching.add(this);
            }
        } else {
            if (!touched) {
                return;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touched = false;
                touching.remove(this);
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            unclipParents();
            touchX = startX = x;
            touchY = startY = y;
            offsetXAnimation.cancel();
            offsetYAnimation.cancel();
            offsetX.setValue(0f);
            offsetY.setValue(0f);
            if (animated()) {
                pressAnimation.animateToFinalPosition(1f);
            } else {
                pressAnimation.cancel();
                press.setValue(1f);
                view.invalidate();
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            touchX = x;
            touchY = y;
            if (animated()) {
                offsetXAnimation.cancel();
                offsetYAnimation.cancel();
                offsetX.setValue(touchX - startX);
                offsetY.setValue(touchY - startY);
                view.invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (animated()) {
                pressAnimation.animateToFinalPosition(0f);
                offsetXAnimation.animateToFinalPosition(0f);
                offsetYAnimation.animateToFinalPosition(0f);
            } else {
                pressAnimation.cancel();
                offsetXAnimation.cancel();
                offsetYAnimation.cancel();
                press.setValue(0f);
                offsetX.setValue(0f);
                offsetY.setValue(0f);
                view.invalidate();
                restoreClipping();
            }
        }
    }

    private boolean compute(float width, float height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        final float p = press.getValue();
        final float ox = offsetX.getValue();
        final float oy = offsetY.getValue();
        if (p == 0 && ox == 0 && oy == 0) {
            return false;
        }
        final float swell = AndroidUtilities.dpf2(4f) * p;
        final float min = Math.min(width, height);
        curTx = (float) Math.tanh(ox * 0.05f / min) * min;
        curTy = (float) Math.tanh(oy * 0.05f / min) * min;
        final float stretch = AndroidUtilities.dpf2(4f);
        final float max = Math.max(width, height);
        final double angle = Math.atan2(oy, ox);
        curScaleX = 1f + swell / width + Math.min(1f, Math.abs((float) Math.cos(angle) * ox / max)) * (stretch / width);
        curScaleY = 1f + swell / height + Math.min(1f, Math.abs((float) Math.sin(angle) * oy / max)) * (stretch / height);
        return true;
    }

    public boolean isActive() {
        return press.getValue() != 0 || offsetX.getValue() != 0 || offsetY.getValue() != 0;
    }

    public float getPressProgress() {
        return press.getValue();
    }

    public void mapRect(float left, float top, float right, float bottom, RectF out) {
        out.set(left, top, right, bottom);
        final float w = right - left;
        final float h = bottom - top;
        if (compute(w, h)) {
            final float cx = (left + right) / 2f + curTx;
            final float cy = (top + bottom) / 2f + curTy;
            final float hw = w * curScaleX / 2f;
            final float hh = h * curScaleY / 2f;
            out.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }
    }

    public void transform(Canvas canvas, float width, float height) {
        if (compute(width, height)) {
            canvas.translate(curTx, curTy);
            canvas.scale(curScaleX, curScaleY, width / 2f, height / 2f);
        }
    }

    public void drawGlow(Canvas canvas, float width, float height, float radius) {
        final float p = getPressProgress();
        if (p <= 0 || width <= 0 || height <= 0) {
            return;
        }
        final float alpha = Math.min(p, 1f);
        canvas.save();
        clip.rewind();
        clip.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        glowPaint.setAlpha((int) (alpha * 20.4f));
        canvas.drawRect(0, 0, width, height, glowPaint);
        final float spotRadius = Math.min(width, height) * 1.5f;
        final float cx = Math.max(0, Math.min(width, touchX));
        final float cy = Math.max(0, Math.min(height, touchY));
        spotMatrix.setScale(spotRadius, spotRadius);
        spotMatrix.postTranslate(cx, cy);
        spotShader.setLocalMatrix(spotMatrix);
        spotPaint.setAlpha((int) (alpha * 38.25f));
        canvas.drawRect(0, 0, width, height, spotPaint);
        canvas.restore();
    }

    private void unclipParents() {
        if (unclipLevels <= 0 || !unclipped.isEmpty() || !unclippedPadding.isEmpty()) {
            return;
        }
        ViewParent parent = view.getParent();
        for (int i = 0; i < unclipLevels && parent instanceof ViewGroup; ++i) {
            final ViewGroup group = (ViewGroup) parent;
            if (group.getClipChildren()) {
                group.setClipChildren(false);
                unclipped.add(group);
            }
            final boolean scrolling = group instanceof RecyclerView || group instanceof AbsListView || group instanceof ScrollView || group instanceof NestedScrollView;
            if ((group.getPaddingLeft() != 0 || group.getPaddingTop() != 0 || group.getPaddingRight() != 0 || group.getPaddingBottom() != 0) && !scrolling && group.getClipToPadding()) {
                group.setClipToPadding(false);
                unclippedPadding.add(group);
            }
            parent = group.getParent();
        }
    }

    private void restoreClipping() {
        if (unclipped.isEmpty() && unclippedPadding.isEmpty()) {
            return;
        }
        if (pressAnimation.isRunning() || offsetXAnimation.isRunning() || offsetYAnimation.isRunning()) {
            return;
        }
        if (isActive() && getPressProgress() > 0.01f) {
            return;
        }
        for (int i = 0; i < unclipped.size(); ++i) {
            final ViewGroup group = unclipped.get(i);
            group.setClipChildren(true);
            group.invalidate();
        }
        unclipped.clear();
        for (int i = 0; i < unclippedPadding.size(); ++i) {
            final ViewGroup group = unclippedPadding.get(i);
            group.setClipToPadding(true);
            group.invalidate();
        }
        unclippedPadding.clear();
    }
}
