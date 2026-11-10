package com.exteragram.messenger.utils.ui;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * exteraGram AOSP-style window transition (WindowTransitionAnimationHelper.kt): both screens slide by 96dp
 * with the Material "emphasized" curve while the entering one fades in / the leaving one fades out.
 * The strip a screen uncovers while sliding is filled by stretching its 1px leading edge ({@link WindowEdgeExtension},
 * Android 10+, hardware canvas), as AOSP does.
 */
public abstract class WindowTransitionAnimationHelper {

    public static float getDuration() {
        float scale;
        try {
            scale = Settings.Global.getFloat(ApplicationLoader.applicationContext.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f);
        } catch (Exception e) {
            scale = 1.0f;
        }
        return Math.max(1.0f, scale * 450.0f);
    }

    public static float getSlideDistance() {
        return AndroidUtilities.dp(96.0f);
    }

    /**
     * @param view  the screen on top after the transition (containerView)
     * @param view2 the other screen (containerViewBack)
     * @param open  true when opening a new screen
     */
    public static void apply(View view, View view2, boolean open, float progress) {
        float p = Utilities.clamp01(progress);
        float distance = AndroidUtilities.dp(96.0f);
        float offset = Math.round(CubicBezierInterpolator.Emphasized.getInterpolation(p) * distance);
        if (view != null) {
            view.setTranslationX(open ? distance - offset : offset - distance);
            view.setAlpha(open ? fade(p, 50.0f, 83.0f) : 1.0f);
        }
        if (view2 != null) {
            view2.setTranslationX(open ? -offset : offset);
            view2.setAlpha(open ? 1.0f : 1.0f - fade(p, 35.0f, 83.0f));
        }
    }

    private static float fade(float progress, float delayMs, float durationMs) {
        return Utilities.clamp01(((progress * 450.0f) - delayMs) / durationMs);
    }

    /** exteraGram 13.1.1: a 1px column of a screen's leading edge, stretched over the strip it uncovers. */
    @RequiresApi(29)
    public static final class WindowEdgeExtension {
        private final RenderNode node = new RenderNode("windowEdgeExtension");
        private boolean captured;

        /** @param leftEdge capture the left column (the screen moves right), otherwise the right one */
        public boolean capture(View view, boolean leftEdge) {
            final int width = view.getWidth();
            final int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            node.setPosition(0, 0, 1, height);
            node.setPivotX(0);
            node.setPivotY(0);
            final RecordingCanvas canvas = node.beginRecording(1, height);
            try {
                canvas.translate(leftEdge ? 0 : -(width - 1), 0);
                view.draw(canvas);
            } finally {
                node.endRecording();
            }
            node.setUseCompositingLayer(true, null);
            captured = true;
            return true;
        }

        public boolean isCaptured() {
            return captured;
        }

        public void draw(Canvas canvas, float x, float width, float alpha) {
            if (!captured || width <= 0 || alpha <= 0 || !canvas.isHardwareAccelerated()) {
                return;
            }
            node.setScaleX(width / 1.0f);
            node.setTranslationX(x);
            node.setAlpha(alpha);
            canvas.drawRenderNode(node);
        }

        public void release() {
            if (!captured) {
                return;
            }
            captured = false;
            node.setUseCompositingLayer(false, null);
            node.discardDisplayList();
        }
    }
}
