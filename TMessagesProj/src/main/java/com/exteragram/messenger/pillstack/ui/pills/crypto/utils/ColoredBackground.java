package com.exteragram.messenger.pillstack.ui.pills.crypto.utils;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** exteraGram: gradient pill background with a subtle top/bottom stroke in dark themes. */
public class ColoredBackground extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ColoredBackground() {
        this(0xFF1BA4ED, 0xFF1488E1);
    }

    public ColoredBackground(int colorTop, int colorBottom) {
        paint.setShader(new LinearGradient(0, 0, 0, AndroidUtilities.dp(28), new int[]{colorTop, colorBottom}, new float[]{0.0f, 1.0f}, Shader.TileMode.CLAMP));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(AndroidUtilities.dp(1));
        strokePaint.setShader(new LinearGradient(0, 0, 0, AndroidUtilities.dp(28), new int[]{0x4DFFFFFF, 0, 0x1AFFFFFF}, new float[]{0.0f, 0.5f, 1.0f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float radius = AndroidUtilities.dp(14);
        RectF rect = AndroidUtilities.rectTmp;
        rect.set(bounds);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (!Theme.isCurrentThemeDark() || Theme.isCurrentThemeMonet()) {
            return;
        }
        float stroke = AndroidUtilities.dp(1);
        strokePaint.setStrokeWidth(stroke);
        rect.inset(stroke / 2.0f, stroke / 2.0f);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
