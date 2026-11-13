package org.telegram.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;

/**
 * Player progress: the played part waves while the track plays (flat when paused, dragging or in power saving),
 * the rest is a dim line with the buffered part a bit brighter, and a tall rounded thumb grows while dragging.
 */
public class MusicWaveSeekBar extends View {

    public interface Delegate {
        void onSeek(float progress, boolean done);
    }

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint restPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bufferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final int touchSlop;

    private Delegate delegate;
    private float progress;
    private float bufferedProgress;
    private float dragProgress;
    private boolean dragging;
    private boolean maybeDrag;
    private float downX, downY;
    private boolean playing;
    private float amplitude;
    private float phase;
    private float thumbScale = 1f;
    private long lastFrame;

    public MusicWaveSeekBar(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        playedPaint.setStyle(Paint.Style.STROKE);
        playedPaint.setStrokeCap(Paint.Cap.ROUND);
        playedPaint.setStrokeWidth(AndroidUtilities.dp(3));
        restPaint.setStyle(Paint.Style.STROKE);
        restPaint.setStrokeCap(Paint.Cap.ROUND);
        restPaint.setStrokeWidth(AndroidUtilities.dp(3));
        bufferPaint.set(restPaint);
        setColor(0xFFFFFFFF);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setColor(int color) {
        playedPaint.setColor(color);
        thumbPaint.setColor(color);
        restPaint.setColor(ColorUtils.setAlphaComponent(color, 64));
        bufferPaint.setColor(ColorUtils.setAlphaComponent(color, 102));
        invalidate();
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            invalidate();
        }
    }

    public void setProgress(float progress) {
        if (dragging) {
            return;
        }
        final float value = clamp(progress);
        if (this.progress != value) {
            this.progress = value;
            invalidate();
        }
    }

    public void setBufferedProgress(float progress) {
        final float value = clamp(progress);
        if (bufferedProgress != value) {
            bufferedProgress = value;
            invalidate();
        }
    }

    public float getProgress() {
        return dragging ? dragProgress : progress;
    }

    public boolean isDragging() {
        return dragging;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float left() {
        return getPaddingLeft() + AndroidUtilities.dp(4);
    }

    private float right() {
        return getWidth() - getPaddingRight() - AndroidUtilities.dp(4);
    }

    private float progressAt(float x) {
        final float l = left(), r = right();
        return r > l ? clamp((x - l) / (r - l)) : 0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0f : Math.min(48, now - lastFrame) / 1000f;
        lastFrame = now;
        final float targetAmplitude = playing && !dragging && !LiteMode.isPowerSaverApplied() ? 1f : 0f;
        amplitude += Math.min(1f, 8f * dt) * (targetAmplitude - amplitude);
        if (Math.abs(targetAmplitude - amplitude) < 0.005f) {
            amplitude = targetAmplitude;
        }
        thumbScale += Math.min(1f, 14f * dt) * ((dragging ? 1.6f : 1f) - thumbScale);
        if (amplitude > 0) {
            phase += dt * 6.2831855f * 0.9f;
        }

        final float l = left(), r = right();
        final float cy = getHeight() / 2f;
        final float x = l + getProgress() * (r - l);
        final float gap = AndroidUtilities.dp(6);
        final float restStart = x + gap;
        if (restStart < r) {
            canvas.drawLine(restStart, cy, r, cy, restPaint);
            final float buffered = l + (r - l) * bufferedProgress;
            if (buffered > restStart) {
                canvas.drawLine(restStart, cy, Math.min(buffered, r), cy, bufferPaint);
            }
        }
        final float playedEnd = Math.max(l, x - gap);
        if (playedEnd > l) {
            final float height = AndroidUtilities.dp(3.5f) * amplitude;
            if (height < 0.5f) {
                canvas.drawLine(l, cy, playedEnd, cy, playedPaint);
            } else {
                final float wavelength = AndroidUtilities.dp(26);
                final float k = 6.2831855f / wavelength;
                final float step = AndroidUtilities.dp(2);
                wave.rewind();
                boolean first = true;
                for (float px = l; px <= playedEnd + 0.5f; px += step) {
                    final float sx = Math.min(px, playedEnd);
                    final float edge = Math.min(1f, Math.min(sx - l, playedEnd - sx) / (wavelength * 0.5f));
                    final float sy = (float) Math.sin(sx * k - phase) * height * edge + cy;
                    if (first) {
                        wave.moveTo(sx, sy);
                        first = false;
                    } else {
                        wave.lineTo(sx, sy);
                    }
                }
                canvas.drawPath(wave, playedPaint);
            }
        }
        final float thumbW = AndroidUtilities.dp(4) * thumbScale / 2f;
        final float thumbH = AndroidUtilities.dp(16) * thumbScale / 2f;
        canvas.drawRoundRect(x - thumbW, cy - thumbH, x + thumbW, cy + thumbH, thumbW, thumbW, thumbPaint);

        if (amplitude <= 0 && (thumbScale <= 1.001f || dragging) && (!dragging || thumbScale >= 1.599f)) {
            lastFrame = 0;
            return;
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            maybeDrag = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (maybeDrag && !dragging && Math.abs(event.getX() - downX) > touchSlop) {
                dragging = true;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
            if (dragging) {
                dragProgress = progressAt(event.getX());
                if (delegate != null) {
                    delegate.onSeek(dragProgress, false);
                }
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && !dragging && maybeDrag) {
                dragProgress = progressAt(event.getX());
                dragging = true;
            }
            if (dragging) {
                dragging = false;
                progress = dragProgress;
                if (action == MotionEvent.ACTION_UP && delegate != null) {
                    delegate.onSeek(dragProgress, true);
                }
            }
            maybeDrag = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
