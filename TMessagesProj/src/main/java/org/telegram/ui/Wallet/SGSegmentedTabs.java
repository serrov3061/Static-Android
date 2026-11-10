package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Two-segment switch like 13.1.1's bubble tabs (ViewPagerFixed SELECTOR_TYPE_BUBBLE_STYLE):
 * a rounded track, a pill that slides under the selected title and title colours that follow it.
 */
@SuppressLint("ViewConstructor")
public class SGSegmentedTabs extends View {

    private final CharSequence[] titles;
    private final StaticLayout[] layouts;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int activeColor, inactiveColor;
    private int selected;
    private float progress;
    private ValueAnimator animator;
    private Utilities.Callback<Integer> onSelected;
    private int lastWidth;

    public SGSegmentedTabs(Context context, CharSequence... titles) {
        super(context);
        this.titles = titles;
        this.layouts = new StaticLayout[titles.length];
        textPaint.setTextSize(dp(14));
        textPaint.setTypeface(AndroidUtilities.bold());
    }

    public void setColors(int track, int pill, int active, int inactive) {
        trackPaint.setColor(track);
        pillPaint.setColor(pill);
        activeColor = active;
        inactiveColor = inactive;
        invalidate();
    }

    public void setOnSelected(Utilities.Callback<Integer> listener) {
        onSelected = listener;
    }

    public int getSelected() {
        return selected;
    }

    public void setSelected(int index, boolean animated) {
        if (index < 0 || index >= titles.length) return;
        selected = index;
        if (animator != null) animator.cancel();
        if (!animated) {
            progress = index;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, index);
        animator.setDuration(320);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || titles.length == 0) return;
        final int pad = dp(3);
        final float segment = (w - pad * 2f) / titles.length;
        if (lastWidth != w) {
            lastWidth = w;
            for (int i = 0; i < titles.length; i++) {
                final int avail = Math.max(1, (int) segment - dp(12));
                final CharSequence t = TextUtils.ellipsize(titles[i], textPaint, avail, TextUtils.TruncateAt.END);
                layouts[i] = new StaticLayout(t, textPaint, avail, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
            }
        }
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint);
        final float left = pad + segment * progress;
        rect.set(left, pad, left + segment, h - pad);
        final float r = (h - pad * 2) / 2f;
        canvas.drawRoundRect(rect, r, r, pillPaint);
        for (int i = 0; i < titles.length; i++) {
            final StaticLayout l = layouts[i];
            if (l == null) continue;
            final float t = Math.max(0f, 1f - Math.abs(progress - i));
            textPaint.setColor(androidx.core.graphics.ColorUtils.blendARGB(inactiveColor, activeColor, t));
            canvas.save();
            canvas.translate(pad + segment * i + (segment - l.getWidth()) / 2f, (h - l.getHeight()) / 2f);
            l.draw(canvas);
            canvas.restore();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() == MotionEvent.ACTION_UP && getWidth() > 0) {
            final int index = Math.max(0, Math.min(titles.length - 1, (int) (event.getX() / getWidth() * titles.length)));
            if (index != selected) {
                setSelected(index, true);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (onSelected != null) onSelected.run(index);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
}
