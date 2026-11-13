package org.telegram.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LiquidGlassButtonBackground;
import org.telegram.ui.Components.LiquidPressEffect;

/**
 * Round player button: an icon (or short text, like «1x») on a glass plate. The plate can be always shown,
 * shown only while the button is active/pressed, or replaced by a solid fill (the big play button).
 */
public class MusicGlassButton extends View {

    private final LiquidGlassButtonBackground glass = new LiquidGlassButtonBackground();
    private final LiquidPressEffect press;
    private final RectF rect = new RectF();
    private final RectF moved = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private Drawable icon;
    private int iconSize;
    private int iconColor = 0xFFFFFFFF;
    private PorterDuffColorFilter iconFilter;
    private int iconFilterColor;
    private String text;
    private int fillColor;
    private boolean glassShown = true;
    private int glassWidth;
    private int glassHeight;
    private boolean active;
    private float activeProgress;

    public MusicGlassButton(Context context) {
        super(context);
        press = new LiquidPressEffect(this);
        press.setUnclipParents(1);
        textPaint.setTextSize(AndroidUtilities.dp(14));
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextAlign(Paint.Align.CENTER);
        iconSize = AndroidUtilities.dp(24);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (isEnabled()) {
            press.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    public Drawable getIcon() {
        return icon;
    }

    public void setIcon(int resId) {
        setIcon(ContextCompat.getDrawable(getContext(), resId));
    }

    public void setIcon(Drawable drawable) {
        icon = drawable != null ? drawable.mutate() : null;
        iconFilter = null;
        invalidate();
    }

    public void setIconSize(int size) {
        iconSize = size;
        invalidate();
    }

    public void setIconColor(int color) {
        iconColor = color;
        invalidate();
    }

    public void setText(String text) {
        this.text = text;
        invalidate();
    }

    public void setFillColor(int color) {
        fillColor = color;
        invalidate();
    }

    public void setGlassShown(boolean shown) {
        glassShown = shown;
        invalidate();
    }

    public void setGlassSize(int width, int height) {
        glassWidth = width;
        glassHeight = height;
        invalidate();
    }

    public void setActive(boolean active, boolean animated) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (!animated) {
            activeProgress = active ? 1f : 0f;
        }
        invalidate();
    }

    public boolean isActiveState() {
        return active;
    }

    public void invalidateGlass() {
        invalidate();
    }

    private static float radiusOf(RectF r) {
        return Math.min(r.width(), r.height()) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float target = active ? 1f : 0f;
        if (activeProgress != target) {
            activeProgress = AndroidUtilities.lerp(activeProgress, target, 0.25f);
            if (Math.abs(activeProgress - target) < 0.01f) {
                activeProgress = target;
            } else {
                invalidate();
            }
        }
        final int w = glassWidth > 0 ? Math.min(glassWidth, getWidth()) : getWidth();
        final int h = glassHeight > 0 ? Math.min(glassHeight, getHeight()) : getHeight();
        rect.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        final boolean pressed = press.isActive();
        final float plate = glassShown ? 1f : Math.max(activeProgress, press.getPressProgress());

        if (fillColor != 0) {
            final RectF r = pressed ? moved : rect;
            if (pressed) {
                press.mapRect(rect.left, rect.top, rect.right, rect.bottom, moved);
            }
            fillPaint.setColor(fillColor);
            final float radius = radiusOf(r);
            canvas.drawRoundRect(r, radius, radius, fillPaint);
            if (pressed) {
                canvas.save();
                canvas.translate(r.left, r.top);
                press.drawGlow(canvas, r.width(), r.height(), radius);
                canvas.restore();
            }
        } else if (plate > 0) {
            final RectF r = pressed ? moved : rect;
            if (pressed) {
                press.mapRect(rect.left, rect.top, rect.right, rect.bottom, moved);
            }
            glass.setColors(iconColor, 0xFF000000);
            glass.setAlpha((int) (plate * 255));
            glass.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
            glass.draw(canvas);
            if (pressed) {
                canvas.save();
                canvas.translate(r.left, r.top);
                press.drawGlow(canvas, r.width(), r.height(), radiusOf(r));
                canvas.restore();
            }
        }

        canvas.save();
        canvas.translate(rect.left, rect.top);
        press.transform(canvas, rect.width(), rect.height());
        canvas.translate(-rect.left, -rect.top);
        final float cx = rect.centerX();
        final float cy = rect.centerY();
        final int contentAlpha = isEnabled() ? 255 : 110;
        if (icon != null) {
            final float half = iconSize / 2f;
            icon.setBounds((int) (cx - half), (int) (cy - half), (int) (cx + half), (int) (cy + half));
            if (iconFilter == null || iconFilterColor != iconColor) {
                iconFilterColor = iconColor;
                iconFilter = new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
                icon.setColorFilter(iconFilter);
            }
            icon.setAlpha(contentAlpha);
            icon.draw(canvas);
        }
        if (text != null) {
            textPaint.setColor(iconColor);
            textPaint.setAlpha(contentAlpha);
            canvas.drawText(text, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
        }
        canvas.restore();
        if (pressed) {
            invalidate();
        }
    }
}
