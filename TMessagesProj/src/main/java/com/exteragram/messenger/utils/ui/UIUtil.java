package com.exteragram.messenger.utils.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import androidx.core.graphics.ColorUtils;
import com.exteragram.messenger.ExteraConfig;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import kotlin.Pair;
import kotlin.TuplesKt;
import kotlin.ranges.RangesKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ScaleStateListAnimator;

public final class UIUtil {
    public static final UIUtil INSTANCE = new UIUtil();
    private static final WeakHashMap<View, View.OnLayoutChangeListener> scaleAnimatorRelayoutListeners = new WeakHashMap<>();
    private static final float[] nowPlayingPattern = {-5.5f, 20.0f, 20.0f, 0.35f, -5.5f, -20.0f, 20.0f, 0.35f, -36.0f, -42.0f, 22.0f, 0.375f, -36.0f, 0.0f, 25.0f, 0.425f, -36.0f, 42.0f, 22.0f, 0.375f, -70.0f, 22.0f, 23.0f, 0.35f, -70.0f, -22.0f, 23.0f, 0.35f, -99.0f, 46.0f, 21.0f, 0.275f, -99.0f, 0.0f, 22.0f, 0.325f, -99.0f, -46.0f, 21.0f, 0.275f, -128.0f, -23.0f, 20.0f, 0.225f, -128.0f, 23.0f, 20.0f, 0.225f};

    private UIUtil() {
    }

    public static final GradientDrawable createBottomFade(int color) {
        return INSTANCE.createFade(color, GradientDrawable.Orientation.TOP_BOTTOM);
    }

    public static final GradientDrawable createTopFade(int color) {
        return INSTANCE.createFade(color, GradientDrawable.Orientation.BOTTOM_TOP);
    }

    private final GradientDrawable createFade(int color, GradientDrawable.Orientation orientation) {
        return new GradientDrawable(orientation, new int[]{ColorUtils.setAlphaComponent(color, 0), ColorUtils.setAlphaComponent(color, 60), color});
    }

    public static final void applyScaleStateListAnimator(final View view, final float selectorRad, final boolean top, final boolean bottom, final int basePadding, final float scale, final float tension) {
        // stage 2: exteraGram scale + ripple-mask animator (needs ScaleStateListAnimator.apply(view, scale, tension, Consumer, Consumer))
    }

    /* renamed from: $r8$lambda$ERjZRGym-Kx6PQpLiaKZuzWZ1SI, reason: not valid java name */
    

    public static final Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmapCreateBitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmapCreateBitmap;
    }

    

    public static final float getFabSquareCornerRadiusDp(int size) {
        return (float) Math.ceil((size * 16) / 56.0f);
    }

    public static final Drawable createFabBackground(int size, int color, int pressedColor) {
        Pair pair;
        if (size == 40) {
            int i = Theme.key_windowBackgroundWhite;
            pair = TuplesKt.to(Integer.valueOf(ColorUtils.blendARGB(Theme.getColor(i), -1, 0.1f)), Integer.valueOf(Theme.blendOver(Theme.getColor(i), Theme.getColor(Theme.key_listSelector))));
        } else {
            pair = TuplesKt.to(Integer.valueOf(color), Integer.valueOf(pressedColor));
        }
        return Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(ExteraConfig.getSquareFab() ? getFabSquareCornerRadiusDp(size) : size / 2.0f), ((Number) pair.component1()).intValue(), ((Number) pair.component2()).intValue());
    }

    public static /* synthetic */ int adjustHsl$default(UIUtil uIUtil, int i, float f, float f2, int i2, Object obj) {
        if ((i2 & 4) != 0) {
            f2 = -1.0f;
        }
        return uIUtil.adjustHsl(i, f, f2);
    }

    public final int adjustHsl(int color, float luminance, float saturation) {
        float[] fArr = new float[3];
        ColorUtils.colorToHSL(color, fArr);
        if (saturation > 0.0f) {
            fArr[1] = RangesKt.coerceAtMost(fArr[1] * saturation, 1.0f);
        }
        fArr[2] = RangesKt.coerceAtMost(fArr[2] * luminance, 1.0f);
        return ColorUtils.HSLToColor(fArr);
    }

    public final void drawNowPlayingPattern(Canvas canvas, Drawable pattern, float w, float h, float alpha) {
        if (alpha <= 0.0f) {
            return;
        }
        int i = 0;
        while (true) {
            float[] fArr = nowPlayingPattern;
            if (i >= fArr.length) {
                return;
            }
            float f = fArr[i];
            float f2 = fArr[i + 1];
            float f3 = fArr[i + 2];
            float f4 = fArr[i + 3];
            float f5 = h / 2.0f;
            pattern.setBounds((int) ((AndroidUtilities.dpf2(f) + w) - (AndroidUtilities.dpf2(f3) / 2.0f)), (int) ((AndroidUtilities.dpf2(f2) + f5) - (AndroidUtilities.dpf2(f3) / 2.0f)), (int) (AndroidUtilities.dpf2(f) + w + (AndroidUtilities.dpf2(f3) / 2.0f)), (int) (f5 + AndroidUtilities.dpf2(f2) + (AndroidUtilities.dpf2(f3) / 2.0f)));
            pattern.setAlpha((int) (255.0f * alpha * f4));
            pattern.draw(canvas);
            i += 4;
        }
    }
}
