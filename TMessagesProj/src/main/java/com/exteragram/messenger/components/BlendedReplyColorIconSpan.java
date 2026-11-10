package com.exteragram.messenger.components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import org.telegram.ui.Components.ColoredImageSpan;

public final class BlendedReplyColorIconSpan extends ColoredImageSpan {
    public BlendedReplyColorIconSpan(Drawable drawable) {
        super(drawable);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        setOverrideColor(BlendedReplySpans.blendedReplyColor());
        super.draw(canvas, text, start, end, x, top, y, bottom, paint);
    }
}
