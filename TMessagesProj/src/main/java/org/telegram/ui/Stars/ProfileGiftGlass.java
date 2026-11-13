package org.telegram.ui.Stars;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * The glass bubble a collectible gift floats in around the profile avatar: an outer glow, a tinted body,
 * a rim, a sheen and a caustic highlight. Everything is drawn in a unit space (radius 1) and scaled to the
 * bubble radius; {@code wobble} squashes the bubble while it tilts and {@code stretch} while it is held.
 */
final class ProfileGiftGlass {

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caustic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeReflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private final Path reflection = new Path();

    public ProfileGiftGlass(int color, int edgeColor) {
        final int light = ColorUtils.blendARGB(color, 0xFFFFFFFF, 0.18f);
        final Shader.TileMode clamp = Shader.TileMode.CLAMP;

        glow.setShader(new RadialGradient(0, 0, 1.62f,
            new int[] { Theme.multAlpha(light, 0.8f), Theme.multAlpha(light, 0.74f), Theme.multAlpha(color, 0.46f), Theme.multAlpha(edgeColor, 0.15f), 0 },
            new float[] { 0.0f, 0.54f, 0.68f, 0.84f, 1.0f }, clamp));
        outerGlow.setShader(new RadialGradient(0, 0, 2.25f,
            new int[] { Theme.multAlpha(color, 0.38f), Theme.multAlpha(color, 0.29f), Theme.multAlpha(edgeColor, 0.12f), 0 },
            new float[] { 0.0f, 0.47f, 0.72f, 1.0f }, clamp));
        body.setShader(new RadialGradient(-0.3f, -0.39f, 1.65f,
            new int[] { 0x72FFFFFF, Theme.multAlpha(light, 0.29f), Theme.multAlpha(color, 0.36f), Theme.multAlpha(edgeColor, 0.82f) },
            new float[] { 0.0f, 0.31f, 0.68f, 1.0f }, clamp));

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(0.025f);
        rim.setShader(new LinearGradient(-0.72f, -0.78f, 0.78f, 0.86f,
            new int[] { 0xF2FFFFFF, Theme.multAlpha(light, 0.65f), 0x2AFFFFFF, 0xC4FFFFFF },
            new float[] { 0.0f, 0.29f, 0.63f, 1.0f }, clamp));
        sheen.setShader(new RadialGradient(-0.42f, -0.65f, 0.7f,
            new int[] { 0xB8FFFFFF, 0x52FFFFFF, 0 },
            new float[] { 0.0f, 0.25f, 1.0f }, clamp));
        caustic.setShader(new RadialGradient(0.36f, 0.87f, 0.84f,
            new int[] { 0xA5FFFFFF, Theme.multAlpha(light, 0.53f), 0 },
            new float[] { 0.0f, 0.39f, 1.0f }, clamp));

        // a slightly irregular circle, so the bubble reads as liquid glass and not as a disc
        contour.moveTo(-0.96f, -0.19f);
        contour.cubicTo(-0.91f, -0.75f, -0.55f, -1.03f, -0.03f, -0.99f);
        contour.cubicTo(0.48f, -1.04f, 1.02f, -0.65f, 1.01f, -0.08f);
        contour.cubicTo(1.07f, 0.54f, 0.65f, 0.98f, 0.12f, 0.99f);
        contour.cubicTo(-0.53f, 1.04f, -1.06f, 0.49f, -0.96f, -0.19f);
        contour.close();

        reflection.moveTo(-0.83f, -0.38f);
        reflection.cubicTo(-0.69f, -0.73f, -0.4f, -0.94f, -0.03f, -0.91f);
        reflection.cubicTo(-0.36f, -0.81f, -0.64f, -0.65f, -0.83f, -0.38f);
        reflection.close();
        edgeReflection.setShader(new LinearGradient(-0.78f, -0.4f, -0.12f, -0.96f,
            new int[] { 0x00FFFFFF, 0xD4FFFFFF, 0x22FFFFFF }, null, clamp));
    }

    public float widthScale(float wobble, float stretch) {
        return 1.0f + wobble * 0.08f - stretch * 0.12f;
    }

    public float heightScale(float wobble, float stretch) {
        return stretch * 0.48f + (1.0f - wobble * 0.06f);
    }

    /** Clips the canvas to the bubble contour (the canvas must be translated to the bubble center). */
    public void clip(Canvas canvas, float radius, float wobble, float stretch) {
        final float sx = widthScale(wobble, stretch) * radius;
        final float sy = radius * heightScale(wobble, stretch);
        canvas.scale(sx, sy);
        canvas.clipPath(contour);
        canvas.scale(1.0f / sx, 1.0f / sy);
    }

    public void drawGlow(Canvas canvas, float radius, float alpha, float wobble, float pulse, float stretch) {
        canvas.save();
        canvas.scale(widthScale(wobble, stretch) * radius, radius * heightScale(wobble, stretch));
        final float a = alpha * 255.0f;
        outerGlow.setAlpha(Math.round((0.62f * pulse + 0.38f) * a));
        canvas.drawCircle(0, 0, 2.25f, outerGlow);
        glow.setAlpha(Math.round((pulse * 0.21f + 0.79f) * a));
        canvas.drawCircle(0, 0, 1.62f, glow);
        canvas.restore();
    }

    public void draw(Canvas canvas, float radius, float alpha, float wobble, float pulse, boolean highlight) {
        draw(canvas, radius, alpha, wobble, pulse, 0.0f, highlight);
    }

    /**
     * {@code highlight == false} draws the body (under the gift artwork),
     * {@code highlight == true} the sheen, caustic, reflection and rim (over it).
     */
    public void draw(Canvas canvas, float radius, float alpha, float wobble, float pulse, float stretch, boolean highlight) {
        canvas.save();
        canvas.scale(widthScale(wobble, stretch) * radius, radius * heightScale(wobble, stretch));
        if (highlight) {
            canvas.save();
            canvas.clipPath(contour);
            final float a = 255.0f * alpha;
            sheen.setAlpha(Math.round(a));
            caustic.setAlpha(Math.round((pulse * 0.38f + 0.62f) * a));
            canvas.save();
            canvas.rotate((stretch * 12.0f + 11.0f) * wobble);
            canvas.drawPaint(sheen);
            canvas.drawPaint(caustic);
            edgeReflection.setAlpha(Math.round(alpha * 220.0f));
            canvas.drawPath(reflection, edgeReflection);
            canvas.restore();
            canvas.restore();
            rim.setAlpha(Math.round(a));
            canvas.drawPath(contour, rim);
        } else {
            body.setAlpha(Math.round(alpha * 255.0f));
            canvas.drawPath(contour, body);
        }
        canvas.restore();
    }
}
