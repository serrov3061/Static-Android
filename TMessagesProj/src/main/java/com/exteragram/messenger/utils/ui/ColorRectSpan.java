package com.exteragram.messenger.utils.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import java.util.Iterator;
import kotlin.jvm.internal.ArrayIteratorKt;
import kotlin.math.MathKt;
import org.telegram.messenger.AndroidUtilities;

public final class ColorRectSpan extends ReplacementSpan {
    private static final Paint colorPaint = new Paint(1);
    private static final int offset = AndroidUtilities.dp(2.0f);
    private final int color;

    public ColorRectSpan(int i) {
        this.color = i;
    }

    @Override // android.text.style.ReplacementSpan
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return MathKt.roundToInt(paint.measureText(text, start, end) + offset + ((int) paint.getTextSize()));
    }

    @Override // android.text.style.ReplacementSpan
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        android.text.TextPaint textPaint = (android.text.TextPaint) paint;
        if (text instanceof Spanned) {
            Iterator it = ArrayIteratorKt.iterator((CharacterStyle[]) ((Spanned) text).getSpans(start, end, CharacterStyle.class));
            while (it.hasNext()) {
                CharacterStyle characterStyle = (CharacterStyle) it.next();
                if (characterStyle != this) {
                    characterStyle.updateDrawState(textPaint);
                }
            }
        }
        canvas.drawText(text, start, end, x, y, textPaint);
        float textSize = textPaint.getTextSize() * 0.9f;
        float fMeasureText = textPaint.measureText(text, start, end);
        float f = (bottom + top) / 2.0f;
        float f2 = textSize / 2.0f;
        float f3 = x + fMeasureText + offset;
        float f4 = f - f2;
        float f5 = f3 + textSize;
        float f6 = f + f2;
        float f7 = textSize * 0.285f;
        Paint paint2 = colorPaint;
        paint2.setColor(this.color);
        canvas.drawRoundRect(f3, f4, f5, f6, f7, f7, paint2);
    }
}
