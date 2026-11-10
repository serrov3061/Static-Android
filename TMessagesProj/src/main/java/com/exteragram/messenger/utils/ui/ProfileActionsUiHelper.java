package com.exteragram.messenger.utils.ui;

import android.graphics.Paint;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.Text;

/**
 * exteraGram 13.1.1: geometry of the Material 3 profile action buttons
 * (a 56dp pill per action with a label below it, gated by "Material Design 3 → Profile Buttons").
 */
public abstract class ProfileActionsUiHelper {

    private static final int MATERIAL3_BAND_HEIGHT_DP = 102;

    public static boolean isMaterial3Style() {
        return ExteraConfig.getNewProfileActionsStyle();
    }

    /** Height of the actions band under the profile header. */
    public static int getBandHeight() {
        return AndroidUtilities.dp(isMaterial3Style() ? MATERIAL3_BAND_HEIGHT_DP : 74);
    }

    public static float getSidePadding() {
        return AndroidUtilities.dpf2(12);
    }

    public static float getContainerGap(float availableWidth, int count) {
        final float gap = AndroidUtilities.dpf2(12);
        if (count < 2) {
            return gap;
        }
        return Math.max(AndroidUtilities.dpf2(8), Math.min(gap, (availableWidth - AndroidUtilities.dpf2(64) * count) / (count - 1)));
    }

    public static float getContainerHeight() {
        return AndroidUtilities.dpf2(56);
    }

    public static float getCornerRadius(float pressProgress) {
        return Math.max(0, AndroidUtilities.lerp(getContainerHeight() / 2f, AndroidUtilities.dpf2(12), pressProgress));
    }

    public static int getIconSize() {
        return AndroidUtilities.dp(24);
    }

    public static int getStateLayerColor(int color) {
        return ColorUtils.setAlphaComponent(color, Math.round(255 * 0.1f));
    }

    public static float getLabelAlpha(float fraction) {
        return Utilities.clamp01((fraction - 0.6f) / 0.4f);
    }

    public static Text createLabel(CharSequence text) {
        return new Text(text, 14, AndroidUtilities.bold());
    }

    public static float getLabelTopOffset(Text text) {
        final Paint.FontMetricsInt fm = text.getFontMetricsInt();
        return AndroidUtilities.dpf2(6) + fm.ascent - fm.top;
    }

    /** Fits the label into {@code width}: scales it down to 80% and ellipsizes the rest, returns the scale. */
    public static float fitLabel(Text text, CharSequence title, float width) {
        text.setText(title);
        final float maxWidth = width - AndroidUtilities.dpf2(8);
        final float currentWidth = text.getCurrentWidth();
        if (currentWidth <= maxWidth || maxWidth <= 0) {
            return 1f;
        }
        final float scale = maxWidth / currentWidth;
        if (scale >= 0.8f) {
            return scale;
        }
        text.setText(TextUtils.ellipsize(title, text.paint, maxWidth / 0.8f, TextUtils.TruncateAt.END));
        return 0.8f;
    }

    public static <K> SpringAnimation createPressSpring(K object, FloatPropertyCompat<K> property) {
        return new SpringAnimation(object, property)
            .setMinimumVisibleChange(0.001f)
            .setSpring(new SpringForce().setDampingRatio(0.6f).setStiffness(800f));
    }
}
