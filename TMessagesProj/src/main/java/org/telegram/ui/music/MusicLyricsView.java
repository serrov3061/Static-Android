package org.telegram.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

/**
 * Lyrics page. Synced lyrics follow the playback: the current line is bright and slightly larger and is kept
 * at 30% of the height; tapping a line seeks to it. After a manual scroll the view waits 3.5s before following again.
 */
public class MusicLyricsView extends View {

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint();
    private final ArrayList<StaticLayout> layouts = new ArrayList<>();
    private float[] tops = new float[0];
    private float[] emphasis = new float[0];
    private final OverScroller scroller;
    private final GestureDetector gestures;

    private MusicLyrics lyrics;
    private Utilities.Callback<Long> onSeek;
    private int current = -1;
    private int layoutWidth;
    private float contentHeight;
    private float scrollY;
    private float targetScrollY;
    private boolean userScrolling;
    private long userScrolledAt;
    private long lastFrame;
    private int fadeHeight;

    public MusicLyricsView(Context context) {
        super(context);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(AndroidUtilities.dp(24));
        paint.setColor(0xFFFFFFFF);
        scroller = new OverScroller(context);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                userScrolling = true;
                userScrolledAt = SystemClock.elapsedRealtime();
                scrollY = clampScroll(scrollY + distanceY);
                targetScrollY = scrollY;
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                userScrolledAt = SystemClock.elapsedRealtime();
                scroller.fling(0, (int) scrollY, 0, (int) -velocityY, 0, 0, (int) minScroll(), (int) maxScroll());
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (lyrics != null && lyrics.synced && onSeek != null) {
                    final float y = scrollY + e.getY();
                    for (int i = 0; i < layouts.size(); ++i) {
                        final float top = tops[i];
                        if (y >= top - AndroidUtilities.dp(8) && y <= top + layouts.get(i).getHeight() + AndroidUtilities.dp(8)) {
                            userScrolledAt = 0;
                            onSeek.run(lyrics.lines.get(i).time);
                            return true;
                        }
                    }
                }
                return false;
            }
        });
    }

    public void setOnSeek(Utilities.Callback<Long> onSeek) {
        this.onSeek = onSeek;
    }

    public MusicLyrics getLyrics() {
        return lyrics;
    }

    public void setLyrics(MusicLyrics lyrics) {
        if (this.lyrics == lyrics) {
            return;
        }
        this.lyrics = lyrics;
        current = -1;
        scrollY = targetScrollY = 0;
        layoutWidth = 0;
        requestLayout();
        invalidate();
    }

    public void setTime(long timeMs) {
        if (lyrics == null || !lyrics.synced) {
            return;
        }
        final int line = lyrics.lineAt(timeMs);
        if (line == current) {
            return;
        }
        current = line;
        invalidate();
    }

    private float anchor() {
        return getHeight() * 0.3f;
    }

    private float minScroll() {
        if (lyrics == null || !lyrics.synced) {
            return 0;
        }
        return -anchor();
    }

    private float maxScroll() {
        return Math.max(minScroll(), contentHeight - (lyrics == null || !lyrics.synced ? getHeight() - AndroidUtilities.dp(40) : anchor()));
    }

    private float clampScroll(float value) {
        return Math.max(minScroll(), Math.min(maxScroll(), value));
    }

    private void buildLayouts(int width) {
        layouts.clear();
        if (lyrics == null || width <= 0) {
            contentHeight = 0;
            return;
        }
        layoutWidth = width;
        paint.setTextSize(AndroidUtilities.dp(lyrics.synced ? 25 : 20));
        final float gap = AndroidUtilities.dp(lyrics.synced ? 18 : 6);
        final int count = lyrics.lines.size();
        if (tops.length < count) {
            tops = new float[count];
        }
        float y = 0;
        for (int i = 0; i < count; ++i) {
            final MusicLyrics.Line line = lyrics.lines.get(i);
            final String text = line.text.isEmpty() ? (lyrics.synced ? "♪" : " ") : line.text;
            final StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.05f)
                .build();
            layouts.add(layout);
            tops[i] = y;
            y += layout.getHeight() + gap;
        }
        contentHeight = y;
        emphasis = new float[count];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width != layoutWidth) {
            buildLayouts(width);
        }
        if (layouts.isEmpty()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        final boolean smooth = !LiteMode.isPowerSaverApplied();

        boolean animating = false;
        if (scroller.computeScrollOffset()) {
            scrollY = targetScrollY = clampScroll(scroller.getCurrY());
            animating = true;
        } else if (lyrics.synced && !userScrolling && now - userScrolledAt > 3500) {
            targetScrollY = clampScroll(current >= 0 ? tops[current] - anchor() : minScroll());
            final float diff = targetScrollY - scrollY;
            if (smooth && Math.abs(diff) > 0.5f) {
                scrollY += Math.min(1f, 7f * dt) * diff;
                animating = true;
            } else {
                scrollY = targetScrollY;
            }
        }

        final int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.translate(getPaddingLeft(), -scrollY);
        for (int i = 0; i < layouts.size(); ++i) {
            final StaticLayout layout = layouts.get(i);
            final float top = tops[i];
            if (top + layout.getHeight() < scrollY || top > scrollY + getHeight()) {
                continue;
            }
            final float alpha, scale;
            if (lyrics.synced) {
                final float target = i == current ? 1f : 0f;
                if (smooth) {
                    emphasis[i] += Math.min(1f, dt * 10f) * (target - emphasis[i]);
                }
                if (!smooth || Math.abs(target - emphasis[i]) <= 0.01f) {
                    emphasis[i] = target;
                } else {
                    animating = true;
                }
                final int distance = current < 0 ? i + 1 : Math.abs(i - current);
                alpha = AndroidUtilities.lerp(distance <= 1 ? 0.42f : distance == 2 ? 0.32f : 0.24f, 1f, emphasis[i]);
                scale = AndroidUtilities.lerp(0.94f, 1f, emphasis[i]);
            } else {
                alpha = 0.92f;
                scale = 1f;
            }
            canvas.save();
            canvas.translate(0, top);
            canvas.scale(scale, scale, 0, layout.getHeight() / 2f);
            paint.setAlpha((int) (alpha * 255));
            layout.draw(canvas);
            canvas.restore();
        }
        canvas.translate(-getPaddingLeft(), scrollY);
        if (fadeHeight != getHeight()) {
            fadeHeight = getHeight();
            final float fade = AndroidUtilities.dp(36);
            fadePaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                new int[] { 0, 0xFF000000, 0xFF000000, 0 },
                new float[] { 0, fade / getHeight(), 1f - fade / getHeight(), 1f }, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), fadePaint);
        canvas.restoreToCount(save);

        if (!animating && (userScrolledAt == 0 || now - userScrolledAt > 3600)) {
            lastFrame = 0;
            return;
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final boolean handled = gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            userScrolling = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled || super.onTouchEvent(event);
    }
}
