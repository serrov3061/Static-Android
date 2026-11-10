/*
 * This is the source code of Telegram for Android v. 2.0.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;

import com.exteragram.messenger.ExteraConfig;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.voip.CellFlickerDrawable;

// exteraGram: Material 3 (wavy) linear progress when "New loading style" is on, legacy line otherwise
public class LineProgressView extends LinearProgressIndicator {

    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_WAVY = 1;

    private long lastUpdateTime;
    private float currentProgress;
    private float animationProgressStart;
    private long currentProgressTime;
    private float animatedProgressValue;
    private float animatedAlphaValue = 1.0f;

    private int backColor;
    private int progressColor;
    public int type;

    // exteraGram uses BaseProgressIndicator.setHideAfterMaxProgress (material 1.14); emulated here for material 1.13
    private boolean hideAfterMaxProgress;
    private final Runnable hideRunnable = this::hide;

    public void setHideAfterMaxProgress(boolean value) {
        hideAfterMaxProgress = value;
    }

    private static DecelerateInterpolator decelerateInterpolator;
    private static Paint progressPaint;

    private final RectF rect = new RectF();

    CellFlickerDrawable cellFlickerDrawable;

    public LineProgressView(Context context) {
        this(context, null);
    }

    public LineProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, com.google.android.material.R.attr.linearProgressIndicatorStyle);
    }

    public LineProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        if (decelerateInterpolator == null) {
            decelerateInterpolator = new DecelerateInterpolator();
            progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(AndroidUtilities.dp(2));
        }
        init();
    }

    private void init() {
        setMax(1000);
        setTrackThickness(AndroidUtilities.dp(2));
        setTrackCornerRadius(AndroidUtilities.dp(1));
        setTrackStopIndicatorSize(AndroidUtilities.dp(2));
        setIndicatorTrackGapSize(AndroidUtilities.dp(2));
        setTrackCornerRadiusFraction(0.5f);
        setIndeterminate(false);
        setProgressType(TYPE_DEFAULT);
    }

    public void setProgressType(int value) {
        if (!ExteraConfig.getNewLoadingStyle()) {
            type = TYPE_DEFAULT;
            return;
        }
        if (type == value) {
            return;
        }
        type = value;
        if (value == TYPE_DEFAULT) {
            setWaveSpeed(0);
            setWavelength(0);
            setWaveAmplitude(0);
        } else if (value == TYPE_WAVY) {
            setWavelengthDeterminate(AndroidUtilities.dp(40));
            setWaveAmplitude(AndroidUtilities.dp(3));
            updateWaveSpeed(isAttachedToWindow());
            // exteraGram (material 1.14) also sets waveAmplitudeRampProgressMin(0.05f); not available in material 1.13
        }
    }

    private void updateWaveSpeed(boolean attached) {
        setWaveSpeed(type == TYPE_WAVY && attached ? AndroidUtilities.dp(15) : 0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateWaveSpeed(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateWaveSpeed(false);
    }

    private void updateLegacyAnimation() {
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;

        if (animatedProgressValue != 1 && animatedProgressValue != currentProgress) {
            float progressDiff = currentProgress - animationProgressStart;
            if (progressDiff > 0) {
                currentProgressTime += dt;
                if (currentProgressTime >= 300) {
                    animatedProgressValue = currentProgress;
                    animationProgressStart = currentProgress;
                    currentProgressTime = 0;
                } else {
                    animatedProgressValue = animationProgressStart + progressDiff * decelerateInterpolator.getInterpolation(currentProgressTime / 300.0f);
                }
            }
            invalidate();
        }
        if (animatedProgressValue >= 1 && animatedProgressValue == 1 && animatedAlphaValue != 0) {
            animatedAlphaValue -= dt / 200.0f;
            if (animatedAlphaValue <= 0) {
                animatedAlphaValue = 0.0f;
            }
            invalidate();
        }
    }

    public void setProgressColor(int color) {
        progressColor = color;
        if (ExteraConfig.getNewLoadingStyle()) {
            setIndicatorColor(color);
        }
    }

    public void setBackColor(int color) {
        backColor = color;
        if (ExteraConfig.getNewLoadingStyle()) {
            setTrackColor(color);
        }
    }

    public void setProgress(float value, boolean animated) {
        currentProgress = value;
        if (ExteraConfig.getNewLoadingStyle()) {
            removeCallbacks(hideRunnable);
            setProgressCompat((int) (value * getMax()), animated);
            if (hideAfterMaxProgress && value >= 1f) {
                postDelayed(hideRunnable, animated ? 300 : 0);
            }
            return;
        }
        if (!animated) {
            animatedProgressValue = value;
            animationProgressStart = value;
        } else {
            animationProgressStart = animatedProgressValue;
        }
        if (value != 1) {
            animatedAlphaValue = 1;
        }
        currentProgressTime = 0;

        lastUpdateTime = System.currentTimeMillis();
        invalidate();
    }

    public float getCurrentProgress() {
        return currentProgress;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        if (ExteraConfig.getNewLoadingStyle()) {
            super.onDraw(canvas);
            return;
        }
        if (backColor != 0 && animatedProgressValue != 1) {
            progressPaint.setColor(backColor);
            progressPaint.setAlpha((int) (255 * animatedAlphaValue));
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, getHeight() / 2f, getHeight() / 2f, progressPaint);
        }

        progressPaint.setColor(progressColor);
        progressPaint.setAlpha((int) (255 * animatedAlphaValue));
        rect.set(0, 0, getWidth() * animatedProgressValue, getHeight());
        canvas.drawRoundRect(rect, getHeight() / 2f, getHeight() / 2f, progressPaint);

        if (animatedAlphaValue > 0) {
            if (cellFlickerDrawable == null) {
                cellFlickerDrawable = new CellFlickerDrawable(160, 0);
                cellFlickerDrawable.drawFrame = false;
                cellFlickerDrawable.animationSpeedScale = 0.8f;
                cellFlickerDrawable.repeatProgress = 1.2f;
            }
            cellFlickerDrawable.setParentWidth(getMeasuredWidth());
            cellFlickerDrawable.draw(canvas, rect, getHeight() / 2f, null);
            invalidate();
        }

        updateLegacyAnimation();
    }
}
