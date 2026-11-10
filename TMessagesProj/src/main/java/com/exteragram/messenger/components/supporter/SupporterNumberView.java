package com.exteragram.messenger.components.supporter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * exteraGram 13.1.1 SupporterNumberView: the badge number ("#N") rolling in digit by digit on the welcome
 * sheet (slot-machine style, a haptic tick per landed digit, onLanded after the last one).
 */
public class SupporterNumberView extends View {

    private static final long DIGIT_DELAY = 140;
    private static final float ROLL_DURATION = 900.0f;

    private ValueAnimator animator;
    private int color1;
    private int color2;
    private int digitCount;
    private float digitWidth;
    private int[] digits = new int[0];
    private int landedCount;
    private Runnable onLanded;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int shaderWidth = -1;
    private String text = "";
    private float time = -1.0f;

    public SupporterNumberView(Context context) {
        super(context);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(AndroidUtilities.dp(34));
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 9; i++) {
            digitWidth = Math.max(digitWidth, paint.measureText(String.valueOf(i)));
        }
    }

    public void setNumber(String number) {
        text = number;
        digits = new int[number.length()];
        digitCount = 0;
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            digits[i] = Character.isDigit(c) ? Character.digit(c, 10) : -1;
            if (digits[i] >= 0) {
                digitCount++;
            }
        }
        setContentDescription(number);
        invalidate();
    }

    public void setColors(int color1, int color2) {
        this.color1 = color1;
        this.color2 = color2;
        shaderWidth = -1;
        invalidate();
    }

    public void start(Runnable onLanded) {
        this.onLanded = onLanded;
        if (animator != null) {
            animator.cancel();
        }
        long duration = Math.max(0, digitCount - 1) * DIGIT_DELAY + 1140;
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            time = duration;
            landedCount = digitCount;
            invalidate();
            if (onLanded != null) {
                onLanded.run();
            }
            return;
        }
        time = 0.0f;
        landedCount = 0;
        animator = ValueAnimator.ofFloat(0.0f, duration);
        animator.setDuration(duration);
        animator.setInterpolator(null);
        animator.addUpdateListener(a -> {
            time = (float) a.getAnimatedValue();
            checkLanded();
            invalidate();
        });
        animator.start();
    }

    private void checkLanded() {
        int landed = time < ROLL_DURATION ? 0 : Math.min(digitCount, ((int) ((time - ROLL_DURATION) / DIGIT_DELAY)) + 1);
        while (landedCount < landed) {
            landedCount++;
            if (landedCount < digitCount) {
                try {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Exception ignore) {
                }
            } else if (onLanded != null) {
                onLanded.run();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY));
    }

    private float charWidth(int i) {
        return digits[i] >= 0 ? digitWidth : paint.measureText(text, i, i + 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (time < 0.0f || text.isEmpty()) {
            return;
        }
        float totalWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            totalWidth += charWidth(i);
        }
        if (shaderWidth != (int) totalWidth) {
            shaderWidth = (int) totalWidth;
            float left = (getWidth() - totalWidth) / 2.0f;
            paint.setShader(color1 == color2 ? null : new LinearGradient(left, 0, left + totalWidth, 0, color1, color2, Shader.TileMode.CLAMP));
            paint.setColor(color1);
        }
        float lineHeight = paint.getTextSize() * 1.1f;
        float baseline = (getHeight() / 2.0f) - ((paint.descent() + paint.ascent()) / 2.0f);
        float alpha = Utilities.clamp(time / 200.0f, 1.0f, 0.0f);
        canvas.save();
        float half = 0.6f * lineHeight;
        canvas.clipRect(0, (getHeight() / 2.0f) - half, getWidth(), (getHeight() / 2.0f) + half);
        int digitIndex = 0;
        float x = (getWidth() - totalWidth) / 2.0f;
        for (int i = 0; i < text.length(); i++) {
            float w = charWidth(i);
            float cx = x + w / 2.0f;
            if (digits[i] < 0) {
                paint.setAlpha((int) (255.0f * alpha));
                canvas.drawText(text, i, i + 1, cx, baseline, paint);
            } else {
                float t = (time - digitIndex * DIGIT_DELAY) / ROLL_DURATION;
                float roll = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Utilities.clamp(t, 1.0f, 0.0f)) * (digits[i] + 20);
                int whole = (int) Math.floor(roll);
                float frac = roll - whole;
                float bounce = (float) Math.sin(Utilities.clamp(((t - 1.0f) * ROLL_DURATION) / 240.0f, 1.0f, 0.0f) * Math.PI) * 0.14f + 1.0f;
                canvas.save();
                canvas.scale(bounce, bounce, cx, getHeight() / 2.0f);
                drawDigit(canvas, whole % 10, cx, baseline - frac * lineHeight, frac, alpha);
                if (frac > 0) {
                    float rest = 1.0f - frac;
                    drawDigit(canvas, (whole + 1) % 10, cx, baseline + rest * lineHeight, rest, alpha);
                }
                canvas.restore();
                digitIndex++;
            }
            x += w;
        }
        canvas.restore();
    }

    private void drawDigit(Canvas canvas, int digit, float x, float y, float fade, float alpha) {
        paint.setAlpha((int) (alpha * 255.0f * (1.0f - fade)));
        canvas.drawText(String.valueOf(digit), x, y, paint);
    }
}
