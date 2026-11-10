package com.exteragram.messenger.components;

import android.graphics.Color;

import org.telegram.ui.ActionBar.Theme;

/** exteraGram BlendedReplySpansKt (port): reply text color blended with the reply name hue. */
public final class BlendedReplySpans {
    private static final float[] baseHsv = new float[3];
    private static final float[] nameHsv = new float[3];

    private BlendedReplySpans() {
    }

    public static int blendedReplyColor() {
        return blendedReplyColor(0.75f);
    }

    public static int blendedReplyColor(float strength) {
        int color = Theme.chat_replyTextPaint.getColor();
        int nameColor = Theme.chat_replyNamePaint.getColor();
        synchronized (baseHsv) {
            Color.colorToHSV(color, baseHsv);
            Color.colorToHSV(nameColor, nameHsv);
            if (baseHsv[2] < 0.35f) {
                baseHsv[2] = 0.5f;
            }
            baseHsv[0] = nameHsv[0];
            baseHsv[1] = Math.max(0f, Math.min(1f, nameHsv[1] * strength));
            return Color.HSVToColor(Color.alpha(color), baseHsv);
        }
    }
}
