package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

/**
 * Round translucent "glass" plate behind an icon: the surface color with a soft drop shadow, turning
 * into a light veil when the icon is light (so a white icon reads as a frosted pill on dark backgrounds).
 */
public class LiquidGlassButtonBackground extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int iconColor = 0xFFFFFFFF;
    private int surfaceColor = 0xFFFFFFFF;
    private int alpha = 255;

    private float veilFactor() {
        return Math.max(0f, Math.min(1f, ((float) ColorUtils.calculateLuminance(iconColor | 0xFF000000) - 0.25f) / 0.5f));
    }

    public void setColors(int iconColor, int surfaceColor) {
        if (this.iconColor == iconColor && this.surfaceColor == surfaceColor) {
            return;
        }
        this.iconColor = iconColor;
        this.surfaceColor = surfaceColor;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty() || alpha <= 0) {
            return;
        }
        final float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        final float veil = veilFactor();
        rect.set(bounds);
        final int surface = ColorUtils.blendARGB(ColorUtils.setAlphaComponent(surfaceColor, 224), ColorUtils.setAlphaComponent(0xFFFFFFFF, 46), veil);
        final int color = ColorUtils.setAlphaComponent(surface, (int) (Color.alpha(surface) * alpha / 255f));
        if (veil < 1f) {
            final float shadow = AndroidUtilities.dpf2(10f);
            shadowPaint.setColor(color);
            shadowPaint.setShadowLayer(shadow, 0, shadow / 5f, ColorUtils.setAlphaComponent(0xFF000000, (int) ((1f - veil) * 24f * alpha / 255f)));
            canvas.drawRoundRect(rect, radius, radius, shadowPaint);
        }
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
