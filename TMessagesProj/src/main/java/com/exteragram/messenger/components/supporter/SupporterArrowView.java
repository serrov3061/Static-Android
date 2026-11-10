package com.exteragram.messenger.components.supporter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.StarParticlesView;

/**
 * exteraGram 13.1.1 SupporterArrowView: the rotating 3D arrow (Icon3D type 5) over star particles, tinted from
 * the accent colour; the badge number can be engraved on it. setBadge() swaps the arrow for a custom-emoji badge.
 */
public class SupporterArrowView extends FrameLayout {

    private BadgeIconView badgeView;
    public final int color1;
    public final int color2;
    private boolean iconHidden;
    public final GLIconTextureView iconView;
    private final Theme.ResourcesProvider resourcesProvider;

    public SupporterArrowView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClipChildren(false);
        int performance = SharedConfig.getDevicePerformanceClass();
        StarParticlesView particles = new StarParticlesView(context, performance == SharedConfig.PERFORMANCE_CLASS_HIGH ? 90 : performance == SharedConfig.PERFORMANCE_CLASS_AVERAGE ? 50 : 25) {
            @Override
            protected void configure() {
                super.configure();
                drawable.useGradient = false;
                drawable.useBlur = false;
                drawable.forceMaxAlpha = true;
                drawable.checkBounds = true;
                drawable.colorKey = Theme.key_featuredStickers_addButton;
                drawable.resourcesProvider = resourcesProvider;
                drawable.init();
            }
        };
        addView(particles, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 190, Gravity.CENTER));

        iconView = new GLIconTextureView(context, GLIconRenderer.DIALOG_STYLE, Icon3D.TYPE_EXTERA_ARROW) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                setPaused(false);
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                setPaused(true);
            }
        };
        int color = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        Bitmap background = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888);
        new Canvas(background).drawColor(ColorUtils.blendARGB(color, Theme.getColor(Theme.key_dialogBackground, resourcesProvider), 0.5f));
        iconView.setBackgroundBitmap(background);
        iconView.mRenderer.resourcesProvider = resourcesProvider;
        color1 = shiftColor(color, -10.0f, 0.75f, 1.15f);
        color2 = shiftColor(color, 18.0f, 0.6f, 1.1f);
        iconView.mRenderer.setColors(color1, color2);
        iconView.setStarParticlesView(particles);
        addView(iconView, LayoutHelper.createFrame(160, 160, Gravity.CENTER));
    }

    public void setBadge(int account, long documentId) {
        if (badgeView == null) {
            removeView(iconView);
            badgeView = new BadgeIconView(getContext());
            addView(badgeView, LayoutHelper.createFrame(130, 130, Gravity.CENTER));
        }
        badgeView.set(account, documentId, Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
    }

    public void setNumber(String number) {
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap mask = SupporterArrowEngraving.createMask(number);
            AndroidUtilities.runOnUIThread(() -> iconView.mRenderer.setEngraving(mask));
        });
    }

    public View getIconView() {
        return badgeView != null ? badgeView : iconView;
    }

    public boolean hasBadge() {
        return badgeView != null;
    }

    public void setIconHidden(boolean hidden) {
        if (iconHidden != hidden) {
            iconHidden = hidden;
            invalidate();
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == getIconView() && iconHidden) {
            return true;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    private static int shiftColor(int color, float hueShift, float saturation, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = ((hsv[0] + hueShift) + 360.0f) % 360.0f;
        hsv[1] = Math.min(1.0f, hsv[1] * saturation);
        hsv[2] = Math.min(1.0f, hsv[2] * value);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /** A custom-emoji badge in place of the arrow, tinted with the accent colour when the emoji allows it. */
    public static class BadgeIconView extends View {

        private PorterDuffColorFilter colorFilter;
        private AnimatedEmojiDrawable drawable;

        public BadgeIconView(Context context) {
            super(context);
        }

        public void set(int account, long documentId, int color) {
            if (drawable != null && isAttachedToWindow()) {
                drawable.removeView(this);
            }
            drawable = AnimatedEmojiDrawable.make(account, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_LARGE_140, documentId);
            colorFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            if (isAttachedToWindow()) {
                drawable.addView(this);
            }
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (drawable != null) {
                drawable.addView(this);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (drawable != null) {
                drawable.removeView(this);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawable != null) {
                drawable.setBounds(0, 0, getWidth(), getHeight());
                boolean tint = drawable.canOverrideColor();
                if (tint) {
                    drawable.setColorFilter(colorFilter);
                }
                drawable.draw(canvas);
                if (tint) {
                    drawable.setColorFilter(null);
                }
            }
        }
    }
}
