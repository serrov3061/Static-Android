/*
 * This is the source code of Telegram for Android v. 2.0.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.vectordrawable.graphics.drawable.Animatable2Compat;

import com.exteragram.messenger.ExteraConfig;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.DeterminateDrawable;
import com.google.android.material.progressindicator.IndeterminateDrawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

public class RadialProgress2 implements Drawable.Callback {

    // exteraGram: Material 3 progress styles (0 - legacy, 1 - M3 circular, 2 - M3 wavy)
    public static final int STYLE_DEFAULT = 0;
    public static final int STYLE_M3 = 1;
    public static final int STYLE_M3_WAVY = 2;

    private static final Animatable2Compat.AnimationCallback indeterminateHideCallback = new Animatable2Compat.AnimationCallback() {
        @Override
        public void onAnimationEnd(Drawable drawable) {
            AndroidUtilities.runOnUIThread(() -> cancelIndeterminateAnimator(drawable));
        }
    };

    protected int style;
    private boolean needDrawBackground = true;
    private boolean invertColors;
    private int lastMainProgressLevel = -1;
    private int lastMiniProgressLevel = -1;
    private int indicatorColor = -1;
    private int indicatorPressedColor = -1;
    private int trackColor = -1;
    private int trackPressedColor = -1;
    private int waveAmplitude;
    private CircularProgressIndicatorSpec progressSpec;
    private CircularProgressIndicatorSpec miniProgressSpec;
    private DeterminateDrawable<CircularProgressIndicatorSpec> progressDrawable;
    private IndeterminateDrawable<CircularProgressIndicatorSpec> indeterminateDrawable;
    private DeterminateDrawable<CircularProgressIndicatorSpec> miniProgressDrawable;
    private IndeterminateDrawable<CircularProgressIndicatorSpec> miniIndeterminateDrawable;

    public float drawScale = 1f;

    public RectF progressRect = new RectF();
    private View parent;

    private boolean previousCheckDrawable;

    private boolean drawMiniIcon;
    private int progressColor = 0xffffffff;
    private Paint miniProgressBackgroundPaint;

    private Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint circleMiniPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public MediaActionDrawable mediaActionDrawable;
    private MediaActionDrawable miniMediaActionDrawable;
    private float miniIconScale = 1.0f;
    private int circleColor;
    private int circlePressedColor;
    public int iconColor;
    private int iconPressedColor;
    private int circleColorKey = -1;
    private int circleCrossfadeColorKey = -1;
    private float circleCrossfadeColorProgress;
    private float circleCheckProgress = 1.0f;
    private int circlePressedColorKey = -1;
    public int iconColorKey = -1;
    private int iconPressedColorKey = -1;
    public ImageReceiver overlayImageView;
    private int circleRadius;
    private boolean isPressed;
    private boolean isPressedMini;
    public float overrideCircleAlpha = 1f;

    private int backgroundStroke;

    private boolean drawBackground = true;

    private Bitmap miniDrawBitmap;
    private Canvas miniDrawCanvas;

    private float overrideAlpha = 1.0f;
    private Theme.ResourcesProvider resourcesProvider;
    private int maxIconSize;
    private float overlayImageAlpha = 1f;

    public float iconScale = 1f;

    public RadialProgress2(View parentView) {
        this(parentView, null);
    }

    public RadialProgress2(View parentView, Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        miniProgressBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        parent = parentView;

        overlayImageView = new ImageReceiver(parentView);
        overlayImageView.setInvalidateAll(true);

        mediaActionDrawable = new MediaActionDrawable();

        miniMediaActionDrawable = new MediaActionDrawable();
        miniMediaActionDrawable.setMini(true);
        miniMediaActionDrawable.setIcon(MediaActionDrawable.ICON_NONE, false);

        circleRadius = AndroidUtilities.dp(22);
        overlayImageView.setRoundRadius(circleRadius);

        overlayPaint.setColor(0x64000000);

        if (parentView != null) {
            mediaActionDrawable.setDelegate(parentView::invalidate);
            miniMediaActionDrawable.setDelegate(parentView::invalidate);
        }
    }

    public void setParent(View parent) {
        this.parent = parent;
        overlayImageView.setParentView(parent);
        mediaActionDrawable.setDelegate(parent::invalidate);
        miniMediaActionDrawable.setDelegate(parent::invalidate);
    }

    private static boolean iconIsCancel(int icon) {
        return icon == MediaActionDrawable.ICON_CANCEL || icon == MediaActionDrawable.ICON_CANCEL_FILL || icon == MediaActionDrawable.ICON_CANCEL_NOPROFRESS || icon == MediaActionDrawable.ICON_CANCEL_PERCENT;
    }

    @Keep
    public void setStyle(int value) {
        if (!ExteraConfig.getNewLoadingStyle() && value != STYLE_DEFAULT) {
            value = STYLE_DEFAULT;
        }
        if (style == value) {
            return;
        }
        style = value;
        if (value == STYLE_DEFAULT) {
            mediaActionDrawable.drawProgressCircle = true;
            miniMediaActionDrawable.drawProgressCircle = true;
            stopMdcDrawable(progressDrawable, true);
            stopMdcDrawable(indeterminateDrawable, true);
            stopMdcDrawable(miniProgressDrawable, true);
            stopMdcDrawable(miniIndeterminateDrawable, true);
            progressDrawable = null;
            indeterminateDrawable = null;
            miniProgressDrawable = null;
            miniIndeterminateDrawable = null;
            progressSpec = null;
            miniProgressSpec = null;
        } else if (value == STYLE_M3 || value == STYLE_M3_WAVY) {
            mediaActionDrawable.drawProgressCircle = false;
            miniMediaActionDrawable.drawProgressCircle = false;
            initMdcDrawables(parent.getContext());
            if (lastMainProgressLevel >= 0) {
                progressDrawable.setLevel(lastMainProgressLevel);
            }
            if (lastMiniProgressLevel >= 0) {
                miniProgressDrawable.setLevel(lastMiniProgressLevel);
            }
            setWavy(value == STYLE_M3_WAVY);
        }
    }

    @Keep
    public int getStyle() {
        return style;
    }

    public boolean isMaterial3Style() {
        return style == STYLE_M3 || style == STYLE_M3_WAVY;
    }

    @Keep
    public void setSpecValues(int indicatorSize, int trackThickness, int trackCornerRadius, int indicatorTrackGapSize) {
        if (isMaterial3Style()) {
            applySpecValues(progressSpec, indicatorSize, trackThickness, trackCornerRadius, indicatorTrackGapSize);
        }
    }

    @Keep
    public void setMiniSpecValues(int indicatorSize, int trackThickness, int trackCornerRadius, int indicatorTrackGapSize) {
        if (isMaterial3Style()) {
            applySpecValues(miniProgressSpec, indicatorSize, trackThickness, trackCornerRadius, indicatorTrackGapSize);
        }
    }

    private static void applySpecValues(CircularProgressIndicatorSpec spec, int indicatorSize, int trackThickness, int trackCornerRadius, int indicatorTrackGapSize) {
        spec.indicatorSize = indicatorSize;
        spec.trackThickness = trackThickness;
        spec.trackCornerRadius = trackCornerRadius;
        spec.useRelativeTrackCornerRadius = true;
        spec.trackCornerRadiusFraction = 0.5f;
        spec.indicatorTrackGapSize = indicatorTrackGapSize;
    }

    private void initMdcDrawables(Context context) {
        progressSpec = new CircularProgressIndicatorSpec(context, null);
        miniProgressSpec = new CircularProgressIndicatorSpec(context, null);
        progressSpec.hideAnimationBehavior = 1; // BaseProgressIndicator.HIDE_OUTWARD
        progressSpec.showAnimationBehavior = 2; // BaseProgressIndicator.SHOW_INWARD
        miniProgressSpec.hideAnimationBehavior = 1; // BaseProgressIndicator.HIDE_OUTWARD
        miniProgressSpec.showAnimationBehavior = 2; // BaseProgressIndicator.SHOW_INWARD
        setSpecValues(circleRadius * 2, AndroidUtilities.dp(3), AndroidUtilities.dp(2), AndroidUtilities.dp(4));
        setMiniSpecValues(AndroidUtilities.dp(24), AndroidUtilities.dp(3), AndroidUtilities.dp(2), AndroidUtilities.dp(2));

        progressDrawable = DeterminateDrawable.createCircularDrawable(context, progressSpec);
        progressDrawable.setCallback(this);
        progressDrawable.setVisible(false, false, false);

        indeterminateDrawable = IndeterminateDrawable.createCircularDrawable(context, progressSpec);
        indeterminateDrawable.setCallback(this);
        indeterminateDrawable.setVisible(false, false, false);
        indeterminateDrawable.registerAnimationCallback(indeterminateHideCallback);

        miniProgressDrawable = DeterminateDrawable.createCircularDrawable(context, miniProgressSpec);
        miniProgressDrawable.setCallback(this);
        miniProgressDrawable.setVisible(false, false, false);

        miniIndeterminateDrawable = IndeterminateDrawable.createCircularDrawable(context, miniProgressSpec);
        miniIndeterminateDrawable.setCallback(this);
        miniIndeterminateDrawable.setVisible(false, false, false);
        miniIndeterminateDrawable.registerAnimationCallback(indeterminateHideCallback);

        updateM3Colors();
    }

    private void updateM3Colors() {
        if (!isMaterial3Style()) {
            return;
        }
        if (progressSpec != null) {
            int iconColor;
            int circleColor;
            if (isPressed) {
                iconColor = iconPressedColorKey >= 0 ? getThemedColor(iconPressedColorKey) : this.iconPressedColor;
                circleColor = circlePressedColorKey >= 0 ? getThemedColor(circlePressedColorKey) : this.circlePressedColor;
            } else {
                iconColor = iconColorKey >= 0 ? getThemedColor(iconColorKey) : this.iconColor;
                circleColor = circleColorKey >= 0 ? getThemedColor(circleColorKey) : this.circleColor;
            }
            if (overlayImageView.hasBitmapImage()) {
                float alpha = overlayImageView.getCurrentAlpha();
                if (alpha >= 1.0f) {
                    iconColor = 0xffffffff;
                } else {
                    int r = Color.red(iconColor);
                    int g = Color.green(iconColor);
                    int b = Color.blue(iconColor);
                    int a = Color.alpha(iconColor);
                    iconColor = Color.argb(a + (int) ((0xff - a) * alpha), r + (int) ((0xff - r) * alpha), g + (int) ((0xff - g) * alpha), b + (int) ((0xff - b) * alpha));
                }
            }
            if (isPressed && indicatorPressedColor != -1) {
                iconColor = indicatorPressedColor;
            } else if (indicatorColor != -1) {
                iconColor = indicatorColor;
            }
            final boolean backgroundDrawn = (!overlayImageView.hasBitmapImage() || overlayImageView.getCurrentAlpha() < 1.0f) && drawBackground && needDrawBackground;
            final int indicator = !invertColors || backgroundDrawn ? iconColor : circleColor;
            int track;
            if (isPressed && trackPressedColor != -1) {
                track = trackPressedColor;
            } else if (trackColor != -1) {
                track = trackColor;
            } else {
                track = ColorUtils.setAlphaComponent(indicator, getTrackAlpha());
            }
            int[] colors = progressSpec.indicatorColors;
            if (colors.length == 0 || colors[0] != indicator) {
                progressSpec.indicatorColors = new int[]{indicator};
                PorterDuffColorFilter filter = new PorterDuffColorFilter(indicator, PorterDuff.Mode.SRC_IN);
                if (indeterminateDrawable != null) {
                    indeterminateDrawable.setColorFilter(filter);
                }
                if (progressDrawable != null) {
                    progressDrawable.setColorFilter(filter);
                }
            }
            if (progressSpec.trackColor != track) {
                progressSpec.trackColor = track;
            }
        }
        if (miniProgressSpec != null) {
            int indicator;
            if (isPressedMini && circleCrossfadeColorKey < 0) {
                indicator = iconPressedColorKey >= 0 ? getThemedColor(iconPressedColorKey) : this.iconPressedColor;
            } else {
                indicator = iconColorKey >= 0 ? getThemedColor(iconColorKey) : this.iconColor;
            }
            if (isPressedMini && indicatorPressedColor != -1) {
                indicator = indicatorPressedColor;
            } else if (indicatorColor != -1) {
                indicator = indicatorColor;
            }
            int track;
            if (isPressedMini && trackPressedColor != -1) {
                track = trackPressedColor;
            } else if (trackColor != -1) {
                track = trackColor;
            } else {
                track = ColorUtils.setAlphaComponent(indicator, getTrackAlpha());
            }
            int[] colors = miniProgressSpec.indicatorColors;
            if (colors.length == 0 || colors[0] != indicator) {
                miniProgressSpec.indicatorColors = new int[]{indicator};
            }
            if (miniProgressSpec.trackColor != track) {
                miniProgressSpec.trackColor = track;
            }
        }
    }

    private int getTrackAlpha() {
        return (int) ((Theme.isCurrentThemeDark() ? 0.325f : 0.275f) * 255);
    }

    @Keep
    public void setWavy(boolean wavy) {
        if (style != STYLE_M3_WAVY) {
            return;
        }
        if (wavy) {
            miniProgressSpec.indicatorInset = 0;
            progressSpec.indicatorInset = 0;
            setWavyValues(AndroidUtilities.dp(15), AndroidUtilities.dp(1.6f), AndroidUtilities.dp(5), 0.05f);
        } else {
            progressSpec.indicatorInset = AndroidUtilities.dp(4);
            miniProgressSpec.indicatorInset = AndroidUtilities.dp(4);
            setWavyValues(0, 0, 0, 1.0f);
        }
    }

    public void setWavyValues(int wavelength, int amplitude, int speed, float rampProgressMin) {
        if (style != STYLE_M3_WAVY) {
            return;
        }
        progressSpec.wavelengthDeterminate = wavelength;
        progressSpec.wavelengthIndeterminate = wavelength;
        waveAmplitude = amplitude;
        progressSpec.waveAmplitude = amplitude;
        progressSpec.waveSpeed = speed;
        // exteraGram (material 1.14) also sets waveAmplitudeRampProgressMin; not available in material 1.13
        invalidateParent();
    }

    public void setNeedDrawBackground(boolean value) {
        needDrawBackground = value;
    }

    @Keep
    public boolean isNeedDrawBackground() {
        return needDrawBackground;
    }

    public void setInvertColors(boolean value) {
        invertColors = value;
    }

    @Keep
    public boolean isInvertColors() {
        return invertColors;
    }

    @Keep
    public void setM3Colors(int indicator, int indicatorPressed, int track, int trackPressed) {
        indicatorColor = indicator;
        indicatorPressedColor = indicatorPressed;
        trackColor = track;
        trackPressedColor = trackPressed;
    }

    @Keep
    public void setM3Colors(int indicator, int track) {
        setM3Colors(indicator, indicator, track, track);
    }

    public void setResourcesProvider(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
    }

    @Keep
    public void setAsMini() {
        mediaActionDrawable.setMini(true);
    }

    @Keep
    public void setCircleRadius(int value) {
        circleRadius = value;
        overlayImageView.setRoundRadius(circleRadius);
        if (isMaterial3Style()) {
            progressSpec.indicatorSize = circleRadius * 2;
        }
    }

    public void setBackgroundStroke(int value) {
        backgroundStroke = value;
        circlePaint.setStrokeWidth(value);
        circlePaint.setStyle(Paint.Style.STROKE);
        invalidateParent();
    }

    public int getRadius() {
        return circleRadius;
    }

    public void setBackgroundDrawable(MessageDrawable drawable) {
        mediaActionDrawable.setBackgroundDrawable(drawable);
        miniMediaActionDrawable.setBackgroundDrawable(drawable);
    }

    @Keep
    public void setBackgroundGradientDrawable(LinearGradient drawable) {
        mediaActionDrawable.setBackgroundGradientDrawable(drawable);
        miniMediaActionDrawable.setBackgroundGradientDrawable(drawable);
    }

    public void setImageOverlay(TLRPC.PhotoSize image, TLRPC.Document document, Object parentObject) {
        final String filter = String.format(Locale.US, "%d_%d", circleRadius * 2, circleRadius * 2);
        overlayImageView.setImage(ImageLocation.getForDocument(image, document), String.format(Locale.US, "%d_%d", circleRadius * 2, circleRadius * 2), null, null, parentObject, 1);
    }

    public void setImageOverlay(TLRPC.PhotoSize image, TLRPC.PhotoSize thumb, TLRPC.Document document, Object parentObject) {
        final String filter = String.format(Locale.US, "%d_%d", circleRadius * 2, circleRadius * 2);
        overlayImageView.setImage(image == null ? null : ImageLocation.getForDocument(image, document), filter, thumb == null ? null : ImageLocation.getForDocument(thumb, document), filter, null, 0, null, parentObject, 1);
    }

    public void setImageOverlay(String url) {
        overlayImageView.setImage(url, url != null ? String.format(Locale.US, "%d_%d", circleRadius * 2, circleRadius * 2) : null, null, null, -1);
    }

    public void setImageOverlay(Bitmap bitmap) {
        overlayImageView.setImageBitmap(bitmap);
    }

    public void onAttachedToWindow() {
        overlayImageView.onAttachedToWindow();
    }

    public void onDetachedFromWindow() {
        stopMdcDrawable(progressDrawable, false);
        stopMdcDrawable(indeterminateDrawable, false);
        stopMdcDrawable(miniProgressDrawable, false);
        stopMdcDrawable(miniIndeterminateDrawable, false);
        overlayImageView.onDetachedFromWindow();
    }

    public void setColorKeys(int circle, int circlePressed, int icon, int iconPressed) {
        circleColorKey = circle;
        circlePressedColorKey = circlePressed;
        iconColorKey = icon;
        iconPressedColorKey = iconPressed;
    }

    @Keep
    public void setColors(int circle, int circlePressed, int icon, int iconPressed) {
        circleColor = circle;
        circlePressedColor = circlePressed;
        iconColor = icon;
        iconPressedColor = iconPressed;
        circleColorKey = -1;
        circlePressedColorKey = -1;
        iconColorKey = -1;
        iconPressedColorKey = -1;
    }

    public void setCircleCrossfadeColor(int color, float progress, float checkProgress) {
        circleCrossfadeColorKey = color;
        circleCrossfadeColorProgress = progress;
        circleCheckProgress = checkProgress;
        miniIconScale = 1.0f;
        if (color >= 0) {
            initMiniIcons();
        }
    }

    public void setDrawBackground(boolean value) {
        drawBackground = value;
    }

    public void setProgressRect(int left, int top, int right, int bottom) {
        progressRect.set(left, top, right, bottom);
    }

    public void setProgressRect(float left, float top, float right, float bottom) {
        progressRect.set(left, top, right, bottom);
    }

    public RectF getProgressRect() {
        return progressRect;
    }

    public void setProgressColor(int color) {
        progressColor = color;
    }

    public void setMiniProgressBackgroundColor(int color) {
        miniProgressBackgroundPaint.setColor(color);
    }

    public void setProgress(float value, boolean animated) {
        setProgress(value, animated, false);
    }

    public void setProgress(float value, boolean animated, boolean force) {
        if (!force && isMaterial3Style() && value == 0 && getProgress() > 0) {
            return;
        }
        if (!force && value == getProgress()) {
            return;
        }
        final int level = (int) (value * 10000);
        if (drawMiniIcon) {
            miniMediaActionDrawable.setProgress(value, animated);
            if (isMaterial3Style()) {
                miniProgressDrawable.setLevel(level);
                updateM3Colors();
                validateVisibleDrawables(value, getMiniIcon(), miniProgressDrawable, miniIndeterminateDrawable, animated);
            }
            lastMiniProgressLevel = level;
        } else {
            mediaActionDrawable.setProgress(value, animated);
            if (isMaterial3Style()) {
                progressDrawable.setLevel(level);
                updateM3Colors();
                validateVisibleDrawables(value, getIcon(), progressDrawable, indeterminateDrawable, animated);
            }
            lastMainProgressLevel = level;
        }
    }

    private void validateVisibleDrawables(float progress, int icon, DeterminateDrawable<CircularProgressIndicatorSpec> determinate, IndeterminateDrawable<CircularProgressIndicatorSpec> indeterminate, boolean animated) {
        if (progress >= 1.0f || !iconIsCancel(icon) && (progress <= 0.04f || icon != MediaActionDrawable.ICON_DOWNLOAD)) {
            if (determinate.isVisible()) {
                determinate.setVisible(false, false, animated);
            }
            if (indeterminate.isVisible()) {
                indeterminate.setVisible(false, false, animated);
            }
        } else if (progress <= 0.04f) {
            if (!indeterminate.isVisible() && parent != null && parent.isAttachedToWindow()) {
                indeterminate.setVisible(true, true, true);
            }
            if (determinate.isVisible()) {
                determinate.setVisible(false, false, animated);
            }
        } else {
            if (indeterminate.isVisible()) {
                indeterminate.setVisible(false, false, false);
            }
            if (!determinate.isVisible()) {
                determinate.setVisible(true, false, false);
            }
        }
    }

    private void validateVisibleDrawables(int icon, int currentIcon, float progress, DeterminateDrawable<CircularProgressIndicatorSpec> determinate, IndeterminateDrawable<CircularProgressIndicatorSpec> indeterminate, boolean animated) {
        if (iconIsCancel(icon) || progress > 0.04f && progress < 1.0f && iconIsCancel(currentIcon) && icon == MediaActionDrawable.ICON_DOWNLOAD) {
            final boolean showIndeterminate = progress <= 0.04f;
            if (determinate.isVisible() == showIndeterminate) {
                determinate.setVisible(!showIndeterminate, false, false);
            }
            if (indeterminate.isVisible() != showIndeterminate) {
                if (!showIndeterminate || parent != null && parent.isAttachedToWindow()) {
                    indeterminate.setVisible(showIndeterminate, true, showIndeterminate);
                }
            }
        } else {
            if (determinate.isVisible()) {
                determinate.setVisible(false, false, animated);
            }
            if (indeterminate.isVisible()) {
                indeterminate.setVisible(false, false, animated);
            }
        }
    }

    public float getProgress() {
        return drawMiniIcon ? miniMediaActionDrawable.getProgress() : mediaActionDrawable.getProgress();
    }

    private void invalidateParent() {
        int offset = AndroidUtilities.dp(2);
        parent.invalidate((int) progressRect.left - offset, (int) progressRect.top - offset, (int) progressRect.right + offset * 2, (int) progressRect.bottom + offset * 2);
    }

    public int getIcon() {
        return mediaActionDrawable.getCurrentIcon();
    }

    public int getMiniIcon() {
        return miniMediaActionDrawable.getCurrentIcon();
    }

    @Keep
    public void setIcon(int icon, boolean ifSame, boolean animated) {
        final boolean same = icon == mediaActionDrawable.getCurrentIcon();
        if (ifSame && same) {
            return;
        }
        if (isMaterial3Style() && mediaActionDrawable.getProgress() > 0.999f && iconIsCancel(icon)) {
            setProgress(0, false, true);
        }
        if (isMaterial3Style() && !same) {
            updateM3Colors();
            validateVisibleDrawables(icon, mediaActionDrawable.getCurrentIcon(), getProgress(), progressDrawable, indeterminateDrawable, animated);
        }
        mediaActionDrawable.setIcon(icon, animated);
        if (parent != null) {
            if (!animated) {
                parent.invalidate();
            } else {
                invalidateParent();
            }
        }
    }

    public void setMiniIconScale(float scale) {
        miniIconScale = scale;
    }

    public void setMiniIcon(int icon, boolean ifSame, boolean animated) {
        if (icon != MediaActionDrawable.ICON_DOWNLOAD && icon != MediaActionDrawable.ICON_CANCEL && icon != MediaActionDrawable.ICON_NONE) {
            return;
        }
        final boolean same = icon == miniMediaActionDrawable.getCurrentIcon();
        if (ifSame && same) {
            return;
        }
        if (drawMiniIcon && isMaterial3Style() && !same) {
            updateM3Colors();
            validateVisibleDrawables(icon, miniMediaActionDrawable.getCurrentIcon(), getProgress(), miniProgressDrawable, miniIndeterminateDrawable, animated);
        }
        miniMediaActionDrawable.setIcon(icon, animated);
        drawMiniIcon = icon != MediaActionDrawable.ICON_NONE || miniMediaActionDrawable.getTransitionProgress() < 1.0f;
        if (drawMiniIcon) {
            initMiniIcons();
        }
        if (!animated) {
            parent.invalidate();
        } else {
            invalidateParent();
        }
    }

    public void initMiniIcons() {
        if (miniDrawBitmap == null) {
            try {
                miniDrawBitmap = Bitmap.createBitmap(AndroidUtilities.dp(48), AndroidUtilities.dp(48), Bitmap.Config.ARGB_8888);
                miniDrawCanvas = new Canvas(miniDrawBitmap);
            } catch (Throwable ignore) {

            }
        }
    }

    public boolean swapIcon(int icon) {
        if (mediaActionDrawable.setIcon(icon, false)) {
            return true;
        }
        return false;
    }

    public void setPressed(boolean value, boolean mini) {
        if (mini) {
            isPressedMini = value;
        } else {
            isPressed = value;
        }
        updateM3Colors();
        invalidateParent();
    }

    public void setOverrideAlpha(float alpha) {
        overrideAlpha = alpha;
    }

    public float getOverrideAlpha() {
        return overrideAlpha;
    }

    public float getWholeAlpha() {
        final int currentIcon = mediaActionDrawable.getCurrentIcon();
        final int prevIcon = mediaActionDrawable.getPreviousIcon();

        if (backgroundStroke != 0) {
            if (currentIcon == MediaActionDrawable.ICON_CANCEL) {
                return 1.0f - mediaActionDrawable.getTransitionProgress();
            } else if (prevIcon == MediaActionDrawable.ICON_CANCEL) {
                return mediaActionDrawable.getTransitionProgress();
            } else {
                return 1.0f;
            }
        } else if ((currentIcon == MediaActionDrawable.ICON_CANCEL || currentIcon == MediaActionDrawable.ICON_CHECK || currentIcon == MediaActionDrawable.ICON_EMPTY || currentIcon == MediaActionDrawable.ICON_GIF || currentIcon == MediaActionDrawable.ICON_PLAY) && prevIcon == MediaActionDrawable.ICON_NONE) {
            return mediaActionDrawable.getTransitionProgress();
        } else {
            return currentIcon != MediaActionDrawable.ICON_NONE ? 1.0f : 1.0f - mediaActionDrawable.getTransitionProgress();
        }
    }

    @Keep
    public void draw(Canvas canvas) {
        if (mediaActionDrawable.getCurrentIcon() == MediaActionDrawable.ICON_NONE && mediaActionDrawable.getTransitionProgress() >= 1.0f || progressRect.isEmpty()) {
            return;
        }

        final boolean scaled = drawScale != 1f;
        if (scaled) {
            canvas.save();
            canvas.scale(drawScale, drawScale, progressRect.centerX(), progressRect.centerY());
        }

        int currentIcon = mediaActionDrawable.getCurrentIcon();
        final int previousIcon = mediaActionDrawable.getPreviousIcon();
        final float wholeAlpha = getWholeAlpha();

        if (isPressedMini && circleCrossfadeColorKey < 0) {
            if (iconPressedColorKey >= 0) {
                miniMediaActionDrawable.setColor(getThemedColor(iconPressedColorKey));
            } else {
                miniMediaActionDrawable.setColor(iconPressedColor);
            }
            if (circlePressedColorKey >= 0) {
                circleMiniPaint.setColor(getThemedColor(circlePressedColorKey));
            } else {
                circleMiniPaint.setColor(circlePressedColor);
            }
        } else {
            if (iconColorKey >= 0) {
                miniMediaActionDrawable.setColor(getThemedColor(iconColorKey));
            } else {
                miniMediaActionDrawable.setColor(iconColor);
            }
            if (circleColorKey >= 0) {
                if (circleCrossfadeColorKey >= 0) {
                    circleMiniPaint.setColor(AndroidUtilities.getOffsetColor(getThemedColor(circleColorKey), getThemedColor(circleCrossfadeColorKey), circleCrossfadeColorProgress, circleCheckProgress));
                } else {
                    circleMiniPaint.setColor(getThemedColor(circleColorKey));
                }
            } else {
                circleMiniPaint.setColor(circleColor);
            }
        }

        int color;
        if (isPressed) {
            if (iconPressedColorKey >= 0) {
                mediaActionDrawable.setColor(color = getThemedColor(iconPressedColorKey));
                mediaActionDrawable.setBackColor(getThemedColor(circlePressedColorKey));
            } else {
                mediaActionDrawable.setColor(color = iconPressedColor);
                mediaActionDrawable.setBackColor(circlePressedColor);
            }
            if (circlePressedColorKey >= 0) {
                circlePaint.setColor(getThemedColor(circlePressedColorKey));
            } else {
                circlePaint.setColor(circlePressedColor);
            }
        } else {
            if (iconColorKey >= 0) {
                mediaActionDrawable.setColor(color = getThemedColor(iconColorKey));
                mediaActionDrawable.setBackColor(getThemedColor(circleColorKey));
            } else {
                mediaActionDrawable.setColor(color = iconColor);
                mediaActionDrawable.setBackColor(circleColor);
            }
            if (circleColorKey >= 0) {
                circlePaint.setColor(getThemedColor(circleColorKey));
            } else {
                circlePaint.setColor(circleColor);
            }
        }
        if ((drawMiniIcon || circleCrossfadeColorKey >= 0) && miniDrawCanvas != null) {
            miniDrawBitmap.eraseColor(0);
        }

        int originalAlpha = circlePaint.getAlpha();
        circlePaint.setAlpha((int) (originalAlpha * wholeAlpha * overrideAlpha * overrideCircleAlpha));
        originalAlpha = circleMiniPaint.getAlpha();
        circleMiniPaint.setAlpha((int) (originalAlpha * wholeAlpha * overrideAlpha));

        boolean drawCircle = true;
        float scale = 1f;
        int centerX;
        int centerY;
        if ((drawMiniIcon || circleCrossfadeColorKey >= 0) && miniDrawCanvas != null) {
            centerX = (int) Math.ceil(progressRect.width() / 2);
            centerY = (int) Math.ceil(progressRect.height() / 2);
        } else {
            centerX = (int) progressRect.centerX();
            centerY = (int) progressRect.centerY();
        }

        if (overlayImageView.hasBitmapImage()) {
            float alpha = overlayImageView.getCurrentAlpha();
            overlayPaint.setAlpha((int) (0x64 * alpha * wholeAlpha * overrideAlpha));
            int c;
            if (alpha >= 1.0f) {
                drawCircle = false;
                c = 0xffffffff;
            } else {
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int a = Color.alpha(color);

                int rD = (int) ((0xff - r) * alpha);
                int gD = (int) ((0xff - g) * alpha);
                int bD = (int) ((0xff - b) * alpha);
                int aD = (int) ((0xff - a) * alpha);
                c = Color.argb(a + aD, r + rD, g + gD, b + bD);
            }
            mediaActionDrawable.setColor(c);

            overlayImageView.setImageCoords(centerX - circleRadius, centerY - circleRadius, circleRadius * 2, circleRadius * 2);
        }

        int restore = Integer.MIN_VALUE;
        if (miniDrawCanvas != null && circleCrossfadeColorKey >= 0 && circleCheckProgress != 1.0f) {
            restore = miniDrawCanvas.save();
            float scaleMini = 1.0f - 0.1f * (1.0f - circleCheckProgress);
            miniDrawCanvas.scale(scaleMini, scaleMini, centerX, centerY);
        }
        final boolean drawBackgroundCircle = drawCircle && drawBackground && (!isMaterial3Style() || needDrawBackground);
        if (drawBackgroundCircle) {
            if ((drawMiniIcon || circleCrossfadeColorKey >= 0) && miniDrawCanvas != null) {
                miniDrawCanvas.drawCircle(centerX, centerY, circleRadius, circlePaint);
            } else {
                if (currentIcon != MediaActionDrawable.ICON_NONE || wholeAlpha != 0) {
                    if (backgroundStroke != 0) {
                        canvas.drawCircle(centerX, centerY, (circleRadius - AndroidUtilities.dp(3.5f)), circlePaint);
                    } else {
                        canvas.drawCircle(centerX, centerY, circleRadius, circlePaint);
                    }
                }
            }
        }
        if (overlayImageView.hasBitmapImage()) {
            overlayImageView.setAlpha(wholeAlpha * overrideAlpha * overlayImageAlpha);

            if ((drawMiniIcon || circleCrossfadeColorKey >= 0) && miniDrawCanvas != null) {
                overlayImageView.draw(miniDrawCanvas);
                miniDrawCanvas.drawCircle(centerX, centerY, circleRadius, overlayPaint);
            } else {
                overlayImageView.draw(canvas);
                canvas.drawCircle(centerX, centerY, circleRadius, overlayPaint);
            }
        }
        int iconSize = circleRadius;
        if (maxIconSize > 0 && iconSize > maxIconSize) {
            iconSize = maxIconSize;
        }
        if (iconScale != 1f) {
            canvas.save();
            canvas.scale(iconScale, iconScale, centerX, centerY);
        }
        mediaActionDrawable.setBounds(centerX - iconSize, centerY - iconSize, centerX + iconSize, centerY + iconSize);
        mediaActionDrawable.setHasOverlayImage(overlayImageView.hasBitmapImage());

        // exteraGram: Material 3 progress indicator
        final Canvas progressCanvas = (drawMiniIcon || circleCrossfadeColorKey >= 0) && miniDrawCanvas != null ? miniDrawCanvas : canvas;
        final float progress = getProgress();
        final int miniIcon = miniMediaActionDrawable.getCurrentIcon();
        boolean drawM3Progress = false;
        if (ExteraConfig.getNewLoadingStyle() && isMaterial3Style()) {
            if (!drawMiniIcon && iconIsCancel(currentIcon) || drawMiniIcon && iconIsCancel(miniIcon)) {
                drawM3Progress = true;
            } else if (progress > 0.04f && progress < 1.0f && (drawMiniIcon ? miniIcon == MediaActionDrawable.ICON_DOWNLOAD : currentIcon == MediaActionDrawable.ICON_DOWNLOAD)) {
                drawM3Progress = true;
            }
        }
        if (drawM3Progress && !drawMiniIcon) {
            boolean indeterminateVisible = indeterminateDrawable != null && indeterminateDrawable.isVisible();
            updateM3Colors();
            if (style == STYLE_M3_WAVY) {
                progressSpec.waveAmplitude = drawBackgroundCircle ? 0 : (int) (waveAmplitude * getWaveScale(currentIcon, previousIcon));
            }
            final int progressAlpha = (int) (wholeAlpha * 255 * overrideAlpha);
            if (progress <= 0.04f && !indeterminateVisible && parent != null && parent.isAttachedToWindow()) {
                indeterminateDrawable.setVisible(true, true, true);
                indeterminateVisible = true;
            }
            final Drawable drawable = indeterminateVisible ? indeterminateDrawable : progressDrawable;
            drawable.setBounds(centerX - iconSize, centerY - iconSize, centerX + iconSize, centerY + iconSize);
            drawable.setAlpha(progressAlpha);
            drawable.draw(progressCanvas);
        }
        if ((drawMiniIcon || circleCrossfadeColorKey >= 0)) {
            if (miniDrawCanvas != null) {
                mediaActionDrawable.draw(miniDrawCanvas);
            } else {
                mediaActionDrawable.draw(canvas);
            }
        } else {
            mediaActionDrawable.setOverrideAlpha(overrideAlpha);
            mediaActionDrawable.draw(canvas);
        }
        if (restore != Integer.MIN_VALUE && miniDrawCanvas != null) {
            miniDrawCanvas.restoreToCount(restore);
        }

        if ((drawMiniIcon || circleCrossfadeColorKey >= 0)) {
            int offset;
            int size;
            float cx;
            float cy;
            if (Math.abs(progressRect.width() - AndroidUtilities.dp(44)) < AndroidUtilities.density) {
                offset = 0;
                size = 20;
                cx = progressRect.centerX() + AndroidUtilities.dp(16 + offset);
                cy = progressRect.centerY() + AndroidUtilities.dp(16 + offset);
            } else {
                offset = 2;
                size = 22;
                cx = progressRect.centerX() + AndroidUtilities.dp(18);
                cy = progressRect.centerY() + AndroidUtilities.dp(18);
            }
            int halfSize = size / 2;

            float alpha;
            if (drawMiniIcon) {
                alpha = miniMediaActionDrawable.getCurrentIcon() != MediaActionDrawable.ICON_NONE ? 1.0f : 1.0f - miniMediaActionDrawable.getTransitionProgress();
                if (alpha == 0.0f) {
                    drawMiniIcon = false;
                }
            } else {
                alpha = 1.0f;
            }

            if (miniDrawCanvas != null) {
                miniDrawCanvas.drawCircle(AndroidUtilities.dp(18 + size + offset), AndroidUtilities.dp(18 + size + offset), AndroidUtilities.dp(halfSize + 1) * alpha * miniIconScale, Theme.checkboxSquare_eraserPaint);
            } else {
                miniProgressBackgroundPaint.setColor(progressColor);
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(12), miniProgressBackgroundPaint);
            }

            if (miniDrawCanvas != null) {
                canvas.drawBitmap(miniDrawBitmap, (int) progressRect.left, (int) progressRect.top, null);
            }

            restore = Integer.MIN_VALUE;
            if (miniIconScale < 1.0f) {
                restore = canvas.save();
                canvas.scale(miniIconScale, miniIconScale, cx, cy);
            }

            canvas.drawCircle(cx, cy, AndroidUtilities.dp(halfSize) * alpha + AndroidUtilities.dp(1) * (1.0f - circleCheckProgress), circleMiniPaint);
            if (drawMiniIcon) {
                miniMediaActionDrawable.setBounds((int) (cx - AndroidUtilities.dp(halfSize) * alpha), (int) (cy - AndroidUtilities.dp(halfSize) * alpha), (int) (cx + AndroidUtilities.dp(halfSize) * alpha), (int) (cy + AndroidUtilities.dp(halfSize) * alpha));
                if (drawM3Progress) {
                    updateM3Colors();
                    final float r = AndroidUtilities.dp(12) * alpha;
                    final Drawable drawable = progress <= 0.04f ? miniIndeterminateDrawable : miniProgressDrawable;
                    drawable.setBounds((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
                    drawable.setAlpha((int) (alpha * 255));
                    drawable.draw(canvas);
                }
                miniMediaActionDrawable.draw(canvas);
            }
            if (restore != Integer.MIN_VALUE) {
                canvas.restoreToCount(restore);
            }
        }
        if (iconScale != 1f) {
            canvas.restore();
        }
        if (scaled) {
            canvas.restore();
        }
    }

    private float getWaveScale(int currentIcon, int previousIcon) {
        final float current = iconIsCancel(currentIcon) ? 1f : 0f;
        final float previous = iconIsCancel(previousIcon) ? 1f : 0f;
        return previous + (current - previous) * mediaActionDrawable.getTransitionProgress();
    }

    public int getCircleColorKey() {
        return circleColorKey;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void setMaxIconSize(int maxSize) {
        this.maxIconSize = maxSize;
    }

    public void setOverlayImageAlpha(float overlayImageAlpha) {
        this.overlayImageAlpha = overlayImageAlpha;
    }

    public float getTransitionProgress() {
        return drawMiniIcon ? miniMediaActionDrawable.getTransitionProgress() : mediaActionDrawable.getTransitionProgress();
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateParent();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        if (parent != null) {
            parent.scheduleDrawable(who, what, when);
        }
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        if (parent != null) {
            parent.unscheduleDrawable(who, what);
        }
    }

    private static void stopMdcDrawable(Drawable drawable, boolean clearCallback) {
        if (drawable == null) {
            return;
        }
        if (drawable instanceof IndeterminateDrawable) {
            ((IndeterminateDrawable<?>) drawable).stop();
            cancelIndeterminateAnimator(drawable);
        } else if (drawable instanceof Animatable) {
            ((Animatable) drawable).stop();
        } else {
            drawable.setVisible(false, false);
        }
        if (clearCallback) {
            drawable.setCallback(null);
        }
    }

    private static void cancelIndeterminateAnimator(Drawable drawable) {
        IndeterminateDrawable<?> indeterminate = (IndeterminateDrawable<?>) drawable;
        if (indeterminate.isVisible() || indeterminate.isRunning()) {
            return;
        }
        indeterminate.setVisible(true, false, false);
        indeterminate.setVisible(false, false, false);
    }
}
