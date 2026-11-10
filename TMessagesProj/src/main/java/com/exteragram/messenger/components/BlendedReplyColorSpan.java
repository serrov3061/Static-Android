package com.exteragram.messenger.components;

import android.text.TextPaint;
import android.text.style.CharacterStyle;

public final class BlendedReplyColorSpan extends CharacterStyle {
    @Override
    public void updateDrawState(TextPaint tp) {
        // exteraGram calls blendedReplyColor$default(0f, 1, null): mask bit 1 set -> default strength 0.75
        tp.setColor(BlendedReplySpans.blendedReplyColor());
    }
}
