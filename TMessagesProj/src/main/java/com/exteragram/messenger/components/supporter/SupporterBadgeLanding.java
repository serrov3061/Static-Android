package com.exteragram.messenger.components.supporter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

/**
 * exteraGram 13.1.1 SupporterBadgeLanding: after «Numbered Badge» on the welcome sheet opens the own profile,
 * the arrow snapshot flies from the sheet header into the badge next to the profile name.
 * prepare() snapshots the header before the sheet is dismissed; ProfileActivity.onTransitionAnimationStart calls start().
 */
public abstract class SupporterBadgeLanding {

    private static Pending pending;

    public static final class Pending {
        final Bitmap bitmap;
        final float contentSize;
        final WeakReference<View> cover;
        final float screenX;
        final float screenY;
        final long time = SystemClock.elapsedRealtime();
        final long userId;

        public Pending(long userId, Bitmap bitmap, float screenX, float screenY, float contentSize, View cover) {
            this.userId = userId;
            this.bitmap = bitmap;
            this.screenX = screenX;
            this.screenY = screenY;
            this.contentSize = contentSize;
            this.cover = new WeakReference<>(cover);
        }
    }

    public static boolean prepare(long userId, View view, float contentScale) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        Bitmap bitmap = view instanceof TextureView ? ((TextureView) view).getBitmap() : null;
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        clear();
        pending = new Pending(userId, bitmap, location[0] + view.getWidth() / 2.0f, location[1] + view.getHeight() / 2.0f, view.getHeight() * contentScale, view.getRootView());
        return true;
    }

    private static void clear() {
        if (pending != null) {
            pending.bitmap.recycle();
            pending = null;
        }
    }

    public static void start(BaseFragment fragment, long userId, Supplier<SupporterEnterFrom> target, SupporterBadgeHint hint) {
        final Pending from = pending;
        if (from == null || from.userId != userId) {
            return;
        }
        pending = null;
        Activity activity = fragment.getParentActivity();
        if (activity == null || SystemClock.elapsedRealtime() - from.time > 2000) {
            from.bitmap.recycle();
            return;
        }
        final View decorView = activity.getWindow().getDecorView();
        final View cover = from.cover.get();
        final Flight flight = new Flight(from, target);
        final View.OnAttachStateChangeListener coverListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                flight.moveTo(decorView);
            }
        };
        // fly over the closing sheet window while it is still there, then over the activity
        if (cover != null && cover != decorView && cover.isAttachedToWindow()) {
            flight.moveTo(cover);
            cover.addOnAttachStateChangeListener(coverListener);
        } else {
            flight.moveTo(decorView);
        }
        if (hint != null) {
            hint.hold();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        animator.addUpdateListener(a -> {
            flight.progress = (float) a.getAnimatedValue();
            flight.invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cover != null) {
                    cover.removeOnAttachStateChangeListener(coverListener);
                }
                flight.moveTo(null);
                flight.land();
                from.bitmap.recycle();
                try {
                    decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Exception ignore) {
                }
                if (hint != null) {
                    hint.release();
                }
            }
        });
        animator.setDuration(800L);
        animator.setInterpolator(null);
        animator.start();
    }

    /** The flying snapshot, drawn in a view overlay; crossfades into the target badge drawable. */
    public static final class Flight extends Drawable {

        private final Pending from;
        private View host;
        private SupporterEnterFrom lastTarget;
        float progress;
        private final Supplier<SupporterEnterFrom> target;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] location = new int[2];
        private final RectF rect = new RectF();
        private final Rect savedBounds = new Rect();

        public Flight(Pending from, Supplier<SupporterEnterFrom> target) {
            this.from = from;
            this.target = target;
        }

        public void moveTo(View view) {
            if (host == view) {
                return;
            }
            if (host != null) {
                host.getOverlay().remove(this);
            }
            host = view;
            if (view != null) {
                setBounds(0, 0, view.getWidth(), view.getHeight());
                host.getOverlay().add(this);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (host == null) {
                return;
            }
            SupporterEnterFrom to = target != null ? target.get() : null;
            if (to != null) {
                lastTarget = to;
            }
            host.getLocationOnScreen(location);
            float fromX = from.screenX - location[0];
            float fromY = from.screenY - location[1];
            Drawable drawable = lastTarget != null ? lastTarget.drawable : null;
            float toX;
            float toY;
            float toSize;
            if (drawable != null) {
                toX = lastTarget.screenX - location[0];
                toY = lastTarget.screenY - location[1];
                toSize = lastTarget.scale * drawable.getIntrinsicWidth();
            } else {
                toX = fromX;
                toY = AndroidUtilities.dp(120);
                toSize = AndroidUtilities.dp(24);
            }
            float t = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(progress);
            float x = AndroidUtilities.lerp(fromX, toX, CubicBezierInterpolator.EASE_BOTH.getInterpolation(progress));
            float y = AndroidUtilities.lerp(fromY, toY, t);
            float size = AndroidUtilities.lerp(from.contentSize, toSize, t);
            float crossfade = Utilities.clamp((progress - 0.35f) / 0.45f, 1.0f, 0.0f);
            if (crossfade < 1.0f) {
                float s = size / from.contentSize;
                float hw = from.bitmap.getWidth() * s / 2.0f;
                float hh = from.bitmap.getHeight() * s / 2.0f;
                rect.set(x - hw, y - hh, x + hw, y + hh);
                bitmapPaint.setAlpha((int) ((1.0f - crossfade) * 255.0f));
                canvas.drawBitmap(from.bitmap, null, rect, bitmapPaint);
            }
            if (drawable != null) {
                if (crossfade > 0.0f) {
                    int w = drawable.getIntrinsicWidth();
                    int h = drawable.getIntrinsicHeight();
                    canvas.save();
                    float s = size / w;
                    canvas.scale(s, s, x, y);
                    drawable.copyBounds(savedBounds);
                    drawable.setBounds((int) x - w / 2, (int) y - h / 2, (int) x + w / 2, (int) y + h / 2);
                    drawable.setAlpha((int) (crossfade * 255.0f));
                    drawable.draw(canvas);
                    drawable.setBounds(savedBounds);
                    canvas.restore();
                }
                drawable.setAlpha(0);
            }
        }

        public void land() {
            if (lastTarget != null) {
                lastTarget.drawable.setAlpha(255);
                lastTarget.invalidate();
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
