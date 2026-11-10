package com.exteragram.messenger.components.supporter;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;

/**
 * exteraGram 13.1.1 SupporterArrowEngraving: the badge number ("#N") engraved on the back of the 3D arrow
 * (Icon3D type 5, shaders/fragment_extera_arrow.glsl uniforms "engraving" / "u_Engraving").
 */
public final class SupporterArrowEngraving {

    private int enabledHandle;
    private volatile Bitmap pending;
    private boolean ready;
    private int samplerHandle;
    private int texture;

    public static Bitmap createMask(String text) {
        Bitmap bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(59.392f);
        paint.setLetterSpacing(0.12f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setMaskFilter(new BlurMaskFilter(1.2288f, BlurMaskFilter.Blur.NORMAL));
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.rotate(19.49f, 509.5424f, 288.0512f);
        canvas.drawText(text, 509.5424f, 288.0512f - ((fm.ascent + fm.descent) / 2.0f), paint);
        return bitmap;
    }

    public void init(int program) {
        enabledHandle = GLES20.glGetUniformLocation(program, "engraving");
        samplerHandle = GLES20.glGetUniformLocation(program, "u_Engraving");
    }

    public void set(Bitmap bitmap) {
        pending = bitmap;
    }

    public void apply() {
        Bitmap bitmap = pending;
        if (bitmap != null) {
            pending = null;
            if (texture == 0) {
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                texture = textures[0];
            }
            int[] active = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, active, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glUniform1i(samplerHandle, 3);
            GLES20.glActiveTexture(active[0]);
            ready = true;
        }
        GLES20.glUniform1f(enabledHandle, ready ? 1.0f : 0.0f);
    }

    public void destroy() {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            texture = 0;
        }
        ready = false;
    }
}
