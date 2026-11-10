package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.core.content.ContextCompat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;

public final class PluginTranslateButton extends View {
    public static final int STATE_LOADING = 1;
    public static final int STATE_RESET = 2;
    public static final int STATE_TRANSLATE = 0;
    private Drawable icon;
    private final AnimatedFloat loadingProgress;
    private Drawable previousIcon;
    private final CircularProgressDrawable progressDrawable;
    private final Drawable resetIcon;
    private int state;
    private final AnimatedFloat switchProgress;
    private final Drawable translateIcon;

    public PluginTranslateButton(Context context, int i) {
        super(context);
        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.msg_translate);
        if (drawable == null) {
            throw new IllegalArgumentException("Required value was null.");
        }
        Drawable drawableMutate = drawable.mutate();
        this.translateIcon = drawableMutate;
        Drawable drawable2 = ContextCompat.getDrawable(context, R.drawable.photo_undo2);
        if (drawable2 == null) {
            throw new IllegalArgumentException("Required value was null.");
        }
        Drawable drawableMutate2 = drawable2.mutate();
        this.resetIcon = drawableMutate2;
        this.progressDrawable = new CircularProgressDrawable(i);
        CubicBezierInterpolator cubicBezierInterpolator = CubicBezierInterpolator.EASE_OUT_QUINT;
        this.loadingProgress = new AnimatedFloat(this, 0L, 320L, cubicBezierInterpolator);
        this.switchProgress = new AnimatedFloat(1.0f, this, 0L, 250L, cubicBezierInterpolator);
        this.icon = drawableMutate;
        PorterDuffColorFilter porterDuffColorFilter = new PorterDuffColorFilter(i, PorterDuff.Mode.SRC_IN);
        drawableMutate.setColorFilter(porterDuffColorFilter);
        drawableMutate2.setColorFilter(porterDuffColorFilter);
        updateContentDescription();
    }

    public final int getState() {
        return this.state;
    }

    public final void setState(int state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        if (state != 1) {
            Drawable drawable = state == 2 ? this.resetIcon : this.translateIcon;
            Drawable drawable2 = this.icon;
            if (drawable != drawable2) {
                this.previousIcon = drawable2;
                this.icon = drawable;
                this.switchProgress.set(0.0f, true);
            }
        }
        updateContentDescription();
        invalidate();
    }

    private final void updateContentDescription() {
        setContentDescription(LocaleController.getString(this.state == 2 ? R.string.ShowOriginalButton : R.string.TranslateMessage));
    }

    @Override
    public void onDraw(Canvas canvas) {
        float f = this.loadingProgress.set(this.state == 1 ? 1.0f : 0.0f);
        int iDp = AndroidUtilities.dp(10.0f);
        if (f < 1.0f) {
            float f2 = this.switchProgress.set(1.0f);
            float f3 = 1.0f - f;
            float fLerp = AndroidUtilities.lerp(1.0f, 0.6f, f);
            int iSave = canvas.save();
            canvas.translate(0.0f, (-iDp) * f);
            try {
                Drawable drawable = this.previousIcon;
                if (drawable != null) {
                    if (f2 < 1.0f) {
                        drawIcon(canvas, drawable, (1.0f - f2) * f3, AndroidUtilities.lerp(1.0f, 0.4f, f2) * fLerp);
                    } else {
                        this.previousIcon = null;
                    }
                }
                drawIcon(canvas, this.icon, f3 * f2, fLerp * AndroidUtilities.lerp(0.4f, 1.0f, f2));
                canvas.restoreToCount(iSave);
            } catch (Throwable th) {
                canvas.restoreToCount(iSave);
                throw th;
            }
        }
        if (f > 0.0f) {
            int i = (int) ((1.0f - f) * iDp);
            this.progressDrawable.setBounds(0, i, getWidth(), getHeight() + i);
            this.progressDrawable.setAlpha((int) (255.0f * f));
            this.progressDrawable.draw(canvas);
            invalidate();
        }
    }

    private final void drawIcon(Canvas canvas, Drawable drawable, float alpha, float scale) {
        if (alpha <= 0.0f) {
            return;
        }
        int width = (getWidth() - drawable.getIntrinsicWidth()) / 2;
        int height = (getHeight() - drawable.getIntrinsicHeight()) / 2;
        drawable.setBounds(width, height, drawable.getIntrinsicWidth() + width, drawable.getIntrinsicHeight() + height);
        drawable.setAlpha((int) (255.0f * alpha));
        float width2 = getWidth() / 2.0f;
        float height2 = getHeight() / 2.0f;
        int iSave = canvas.save();
        canvas.scale(scale, scale, width2, height2);
        try {
            drawable.draw(canvas);
        } finally {
            canvas.restoreToCount(iSave);
        }
    }
}
