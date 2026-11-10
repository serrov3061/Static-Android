package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.exteragram.messenger.utils.ui.MonetUtils;
import com.exteragram.messenger.utils.ui.ProfileActionsUiHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("ViewConstructor")
public class ProfileActionsView extends View {

    private final List<Action> actions = new ArrayList<>();
    private final Paint paint = new Paint();
    private final Paint shaderPaint = new Paint();
    private final Paint containerPaint = new Paint(Paint.ANTI_ALIAS_FLAG); // exteraGram: M3 containers
    // exteraGram 13.1.1: Material 3 profile buttons (pill containers with labels below, "Profile Buttons" in MD3 settings)
    private final boolean material3;
    private final Theme.ResourcesProvider resourcesProvider;
    private int textColor = -1;

    private static final FloatPropertyCompat<Action> PRESS_PROGRESS = new FloatPropertyCompat<Action>("pressProgress") {
        @Override
        public float getValue(Action action) {
            return action.pressProgress;
        }

        @Override
        public void setValue(Action action, float value) {
            action.setPressProgress(value);
        }
    };
    private final Path pathTmp = new Path(); // exteraGram: segmented divider style
    private final float[] radii = new float[8];
    private float parentExpanded;

    public boolean isAnimatingCallAction = false;
    public boolean isOpeningLayout = true;
    public float clipHeight = -1;
    private final Path clipAvatarPath = new Path();
    private final Path clipPath = new Path();
    private ProfileActivity.AvatarImageView avatarView;
    private float renderNodeScale;
    private float renderNodeTranslateY;
    private RenderNode renderNode;

    private int activeCount = 0;
    private final int targetHeight;
    private boolean ignoreRect = false;
    private float currentHeight = 0;

    private OnActionClickListener onActionClickListener = null;

    private final Set<Integer> allAvailableActions = new HashSet<>();

    public int mode = MODE_MY_PROFILE;
    public static final int MODE_USER = 0;
    public static final int MODE_CHANNEL = 1;
    public static final int MODE_BOT = 2;
    public static final int MODE_GROUP = 3;
    public static final int MODE_FORUM = 4;
    public static final int MODE_TOPIC = 5;
    public static final int MODE_MY_PROFILE = 6;

    public static final int KEY_MESSAGE = 0;
    public static final int KEY_NOTIFICATION = 1;
    public static final int KEY_DISCUSS = 2;
    public static final int KEY_GIFT = 3;
    public static final int KEY_SHARE = 4;
    public static final int KEY_CALL = 5;
    public static final int KEY_VIDEO = 6;
    public static final int KEY_JOIN = 7;
    public static final int KEY_REPORT = 8;
    public static final int KEY_LEAVE = 9;
    public static final int KEY_VOICE_CHAT = 10;
    public static final int KEY_STREAM = 11;
    public static final int KEY_STORY = 12;
    public static final int KEY_STOP = 13;
    public static final int KEY_SET_PHOTO = 14;
    public static final int KEY_EDIT_USERNAME = 15;
    public static final int KEY_EDIT_INFO = 16;
    public static final int KEY_SETTINGS = 17;
    public static final int KEY_OPEN_CHANNEL = 18; // exteraGram: linked channel of a discussion group

    private boolean isApplying;
    private boolean isNotificationsEnabled;

    private Action callAction = null;
    private Action firstAction, lastAction;
    final float xpadding;
    final float ypadding;
    final float top;
    final float textPadding;

    private int color = 0;
    private boolean hasColorById;
    private RadialGradient radialGradient;
    private final Matrix matrix = new Matrix();

    public boolean myProfile;

    public ProfileActionsView(Context context, int targetHeight) {
        this(context, targetHeight, null);
    }

    public ProfileActionsView(Context context, int targetHeight, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        material3 = ProfileActionsUiHelper.isMaterial3Style();

        paint.setColor(Color.BLACK);
        paint.setAlpha(40);
        shaderPaint.setAntiAlias(material3);

        xpadding = material3 ? ProfileActionsUiHelper.getSidePadding() : dpf2(14);
        ypadding = dpf2(12);
        top = dpf2(8);
        textPadding = dpf2(4);

        this.targetHeight = (int) (targetHeight - ypadding - top);

        setBackgroundColor(0);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void drawingBlur(boolean drawing) {
        if (ignoreRect != drawing || renderNode != null) {
            ignoreRect = drawing;
            renderNode = null;
            avatarView = null;
            invalidate();
        }
    }

    public void drawingBlur(RenderNode renderNode, ProfileActivity.AvatarImageView avatarView, float scale, float dy) {
        this.ignoreRect = false;
        this.renderNode = renderNode;
        this.avatarView = avatarView;
        this.renderNodeScale = scale;
        this.renderNodeTranslateY = dy;
        invalidate();
    }

    public void setOnActionClickListener(OnActionClickListener onActionClickListener) {
        this.onActionClickListener = onActionClickListener;
    }

    public void setParentExpanded(float expanded) {
        if (parentExpanded != expanded) {
            parentExpanded = expanded;
            checkPaints();
            invalidate();
        }
    }

    public void setActionsColor(int color, boolean hasColorById) {
        setActionsColor(color, -1, hasColorById);
    }

    public void setActionsColor(int color, int textColor, boolean hasColorById) {
        if (radialGradient == null || this.color != color || this.textColor != textColor || this.hasColorById != hasColorById) {
            this.color = color;
            this.textColor = textColor;
            this.hasColorById = hasColorById;
            createColorShader();
            checkPaints();
        }
    }

    private boolean isButtonColorLight() {
        return AndroidUtilities.computePerceivedBrightness(color) > 0.72f;
    }

    private void checkPaints() {

    }

    private void createColorShader() {
        if (color == 0) return;
        if (!hasColorById) {
            paint.setColor(color);
//            paint.setAlpha(40);
            return;
        }
        int w = getMeasuredWidth();
        if (w <= 0) return;

        float betweenPadding = getGap();
        float width = (w - betweenPadding * Math.max(0, activeCount - 1) - xpadding * 2f) / Math.max(1, activeCount);

        this.radialGradient = new RadialGradient(
                width / 2f,
                (material3 ? ProfileActionsUiHelper.getContainerHeight() : targetHeight) / 2f,
                hasColorById ? width * 0.65f : 1f,
                Theme.multAlpha(color, 0.8f),
                color,
                Shader.TileMode.CLAMP
        );
        shaderPaint.setShader(radialGradient);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.makeMeasureSpec((int) (targetHeight + top + ypadding), MeasureSpec.EXACTLY)
        );
    }

    public void updatePosition(float y, float newHeight) {
        currentHeight = newHeight;
        setTranslationY(y);
        invalidate();
    }

    // exteraGram: with DividerStyle.SEGMENTS the buttons form one joined segmented group
    private boolean isSegmentedMode() {
        return !material3 && com.exteragram.messenger.ExteraConfig.getDividerStyle() == com.exteragram.messenger.DividerStyle.SEGMENTS;
    }

    private void setRadii(boolean first, boolean last, float outer, float inner) {
        radii[0] = radii[1] = first ? outer : inner;
        radii[2] = radii[3] = last ? outer : inner;
        radii[4] = radii[5] = last ? outer : inner;
        radii[6] = radii[7] = first ? outer : inner;
    }

    private void updateClipPath(Action action, float r, float extra, Path path) {
        path.rewind();
        if (material3) {
            AndroidUtilities.rectTmp.set(action.containerInView);
            AndroidUtilities.rectTmp.inset(-extra, -extra);
            final float radius = action.containerRadiusInView + extra;
            path.addRoundRect(AndroidUtilities.rectTmp, radius, radius, Path.Direction.CCW);
            return;
        }
        AndroidUtilities.rectTmp.set(action.rect);
        AndroidUtilities.rectTmp.inset(
            action.rect.width() / 2.0f * (1.0f - action.getScale()),
            action.rect.height() / 2.0f * (1.0f - action.getScale())
        );
        AndroidUtilities.rectTmp.inset(-extra, -extra);
        if (isSegmentedMode()) {
            final float inner = getInnerRoundRadius();
            final boolean first = action.isDeleting ? action.wasFirst : action == firstAction;
            final boolean last = action.isDeleting ? action.wasLast : action == lastAction;
            setRadii(first, last, r, inner);
            path.addRoundRect(AndroidUtilities.rectTmp, radii, Path.Direction.CCW);
        } else {
            path.addRoundRect(AndroidUtilities.rectTmp, r, r, Path.Direction.CCW);
        }
    }

    private float getGap() {
        if (material3) {
            return ProfileActionsUiHelper.getContainerGap(getMeasuredWidth() - xpadding * 2f, activeCount);
        }
        return isSegmentedMode() ? dp(2) : xpadding / 2f;
    }

    private float getItemWidth() {
        int w = getMeasuredWidth();
        float betweenPadding = getGap();
        return (w - betweenPadding * (activeCount - 1) - xpadding * 2f) / activeCount;
    }

    private void drawGradient(Canvas canvas, Action action, float alpha) {
        if (radialGradient == null) return;
        int wasAlpha2 = shaderPaint.getAlpha();
        shaderPaint.setAlpha((int) (action.getAlpha() * alpha * wasAlpha2));
        matrix.setTranslate(AndroidUtilities.rectTmp.left, AndroidUtilities.rectTmp.top);
        radialGradient.setLocalMatrix(matrix);
        if (isSegmentedMode()) {
            canvas.drawRect(AndroidUtilities.rectTmp, shaderPaint);
        } else {
            final float r = getRoundRadius();
            canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, shaderPaint);
        }
        shaderPaint.setAlpha(wasAlpha2);
    }

    /** The buttons' glass (upstream 13.2), one drawable per drawn button in order; without it they are filled with their color. */
    @Nullable
    private org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory glassFactory;
    private final ArrayList<org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable> glass = new ArrayList<>();
    private int glassUsed;

    /** The buttons on glass, see {@link ProfileButtonsGlass#actionsFactory}. exteraGram segmented / Material 3 styles keep their own fill. */
    public void setGlassFactory(@Nullable org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory) {
        glassFactory = factory;
        glass.clear();
        glassUsed = 0;
        invalidate();
    }

    private void drawGlass(Canvas canvas, RectF rect, float radius, float alpha) {
        if (glassUsed == glass.size()) {
            glass.add(glassFactory.create(this, ProfileButtonsGlass.NO_COLORS).setRadius(radius));
        }
        final org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable drawable = glass.get(glassUsed++);
        drawable.setBounds((int) rect.left, (int) rect.top, (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom));
        drawable.setAlpha((int) (0xFF * alpha));
        drawable.draw(canvas);
    }

    /** The glass of the buttons that are not drawn any more is out of the capture. */
    private void hideUnusedGlass() {
        for (int i = glassUsed; i < glass.size(); i++) {
            final org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable drawable = glass.get(i);
            if (!drawable.getBounds().isEmpty()) {
                drawable.setBounds(0, 0, 0, 0);
            }
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        glassUsed = 0;
        onDrawInternal(canvas);
        hideUnusedGlass();
    }

    private void onDrawInternal(@NonNull Canvas canvas) {
        if (clipHeight >= 0f) {
            final float bottom = clipHeight - getY();
            if (bottom <= 0) {
                return;
            }
            canvas.clipRect(0f, 0f, getMeasuredWidth(), bottom);
        }

        float height = Math.max(0f, currentHeight - ypadding - top);

        if (height <= 0f) {
            return;
        }

        final float betweenPadding = getGap();
        final float width = getItemWidth();
        float left = xpadding;
        float r = getRoundRadius();

        if (renderNode != null) {
            clipPath.rewind();
        }

        Action newFirstAction = null, newLastAction = null;
        int c = actions.size();
        for (int i = 0; i < c; i++) {
            Action action = actions.get(i);
            if (action.isDeleted) continue;

            if (!action.isDeleting) {
                action.rect.set(left, top, left + width, top + height);
                left += width + betweenPadding;

                if (newFirstAction == null) {
                    newFirstAction = action;
                }
                newLastAction = action;
            }

            action.updatePosition();
        }
        firstAction = newFirstAction;
        lastAction = newLastAction;

        if (material3) {
            drawMaterial3(canvas, height);
            return;
        }

        if (renderNode != null) {
            for (int i = 0; i < c; i++) {
                Action action = actions.get(i);
                if (action.isDeleted) continue;
                updateClipPath(action, r, 1f, pathTmp);
                clipPath.addPath(pathTmp);
            }
        }

        float fraction = Utilities.clamp01(height / targetHeight);
        float alphaFraction1 = Utilities.clamp01((fraction - 0.2f) / 0.8f);
        if (alphaFraction1 <= 0f) {
            return;
        }

        if (!ignoreRect) {
            for (int i = 0; i < c; i++) {
                Action action = actions.get(i);
                if (action.isDeleted) continue;
                if (glassFactory != null && !isSegmentedMode()) {
                    updateClipPath(action, r, 0f, pathTmp);
                    drawGlass(canvas, AndroidUtilities.rectTmp, r, action.getAlpha() * alphaFraction1);
                    continue;
                }
                int wasAlpha = paint.getAlpha();
                int newAlpha = (int) (action.getAlpha() * alphaFraction1 * wasAlpha);
                paint.setAlpha((int) (newAlpha * (radialGradient != null ? 0.1f : 1f)));

                updateClipPath(action, r, 0f, pathTmp);
                if (isSegmentedMode()) {
                    canvas.save();
                    canvas.clipPath(pathTmp);
                    canvas.drawRect(AndroidUtilities.rectTmp, paint);
                    drawGradient(canvas, action, alphaFraction1);
                    canvas.restore();
                } else {
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, paint);
                    drawGradient(canvas, action, alphaFraction1);
                }
                paint.setAlpha(wasAlpha);
            }
        }

        drawRenderNode(canvas);

        float alphaFraction2 = Utilities.clamp01((fraction - 0.4f) / 0.6f);
        if (alphaFraction2 > 0f) {
            for (int i = 0; i < c; i++) {
                drawAction(canvas, actions.get(i), fraction, alphaFraction2);
            }
        }
    }

    // exteraGram 13.1.1: Material 3 profile buttons
    private void drawMaterial3(Canvas canvas, float height) {
        final float fraction = Utilities.clamp01(height / targetHeight);
        final float labelWidth = getItemWidth() + getGap();
        final int c = actions.size();
        for (int i = 0; i < c; i++) {
            final Action action = actions.get(i);
            if (!action.isDeleted) {
                action.updateMaterial3Layout(fraction);
            }
        }
        if (renderNode != null) {
            for (int i = 0; i < c; i++) {
                final Action action = actions.get(i);
                if (action.isDeleted) continue;
                updateClipPath(action, 0, 1f, pathTmp);
                clipPath.addPath(pathTmp);
            }
        }
        final float alphaFraction1 = Utilities.clamp01((fraction - 0.2f) / 0.8f);
        if (alphaFraction1 <= 0f) {
            return;
        }
        final boolean monet = !hasColorById && Theme.isCurrentThemeMonet(resourcesProvider);
        final float filledIconProgress = getFilledIconProgress(monet || isButtonColorLight());
        final boolean dark = Theme.isCurrentThemeDark();
        final int contentColor = ColorUtils.blendARGB(monet ? MonetUtils.getOnSecondaryContainerColor(dark) : Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider), Color.WHITE, filledIconProgress);
        final int labelColor = hasColorById ? Color.WHITE : ColorUtils.blendARGB(Theme.getColor(Theme.key_profile_title, resourcesProvider), Color.WHITE, parentExpanded);
        final int containerColor = monet ? ColorUtils.blendARGB(MonetUtils.getSecondaryContainerColor(dark), paint.getColor(), filledIconProgress) : paint.getColor();
        final boolean gradient = !monet && radialGradient != null;

        if (!ignoreRect) {
            final int stateLayerColor = ProfileActionsUiHelper.getStateLayerColor(contentColor);
            for (int i = 0; i < c; i++) {
                final Action action = actions.get(i);
                if (action.isDeleted) continue;
                final float alpha = action.getAlpha() * alphaFraction1;
                final float r = action.containerRadius;
                canvas.save();
                canvas.concat(action.layoutMatrix);
                containerPaint.setColor(containerColor);
                containerPaint.setAlpha((int) (Color.alpha(containerColor) * alpha * (gradient ? 0.1f : 1f)));
                canvas.drawRoundRect(action.container, r, r, containerPaint);
                if (gradient) {
                    final int wasAlpha = shaderPaint.getAlpha();
                    shaderPaint.setAlpha((int) (alpha * wasAlpha));
                    matrix.setTranslate(action.container.left, action.container.top);
                    radialGradient.setLocalMatrix(matrix);
                    canvas.drawRoundRect(action.container, r, r, shaderPaint);
                    shaderPaint.setAlpha(wasAlpha);
                }
                action.updateRippleColor(stateLayerColor);
                pathTmp.rewind();
                pathTmp.addRoundRect(action.container, r, r, Path.Direction.CCW);
                canvas.clipPath(pathTmp);
                action.rippleDrawable.setBounds((int) action.container.left, (int) action.container.top, (int) Math.ceil(action.container.right), (int) Math.ceil(action.container.bottom));
                action.rippleDrawable.draw(canvas);
                canvas.restore();
            }
        }

        drawRenderNode(canvas);

        final float alphaFraction2 = Utilities.clamp01((fraction - 0.4f) / 0.6f);
        if (alphaFraction2 <= 0f) {
            return;
        }
        final float labelAlpha = ProfileActionsUiHelper.getLabelAlpha(fraction);
        updateColorFilter(contentColor);
        for (int i = 0; i < c; i++) {
            final Action action = actions.get(i);
            if (action.isDeleted) continue;
            final float actionAlpha = action.getAlpha();
            canvas.save();
            canvas.concat(action.layoutMatrix);
            updateBounds(action);
            if (labelAlpha > 0f) {
                action.fitLabel(labelWidth);
                final float cx = action.container.centerX();
                final float ty = action.container.bottom + action.labelTopOffset;
                canvas.save();
                canvas.scale(action.textScale, action.textScale, cx, ty);
                action.text.draw(canvas, cx - action.text.getWidth() / 2f, ty + action.text.getHeight() / 2f, labelColor, labelAlpha * actionAlpha);
                canvas.restore();
            }
            final float iconAlpha = alphaFraction2 * actionAlpha;
            drawActionIcons(canvas, action, iconAlpha, filledIconProgress);
            canvas.restore();
            drawLoading(canvas, action, iconAlpha, contentColor);
        }
    }

    private float getFilledIconProgress(boolean light) {
        if (!light) {
            return 1f;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return 0f;
        }
        return MathUtils.clamp((parentExpanded - 0.75f) / 0.25f, 0, 1);
    }

    private void updateColorFilter(int color) {
        if (lastColorFilter == null || lastColorFilterColor != color) {
            lastColorFilterColor = color;
            lastColorFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    private void drawActionIcons(Canvas canvas, Action action, float alpha, float filledProgress) {
        if (action.iconTranslationY != 0) {
            canvas.translate(0, action.iconTranslationY);
        }
        if (action.iconScale != 1f) {
            canvas.scale(action.iconScale, action.iconScale, action.bounds.centerX(), action.bounds.centerY());
        }
        if (isAnimatingCallAction && action.key == KEY_CALL) {
            return;
        }
        final boolean basePack = com.exteragram.messenger.icons.IconManager.INSTANCE.isBasePackOnly(com.exteragram.messenger.IconPackType.DEFAULT);
        final float outlineAlpha = basePack ? (1f - filledProgress) * alpha : 0f;
        final float filledAlpha = basePack ? filledProgress * alpha : alpha;
        if (action.drawableAnimated != null) {
            if (action.key == KEY_NOTIFICATION) {
                drawActionDrawable(canvas, action.drawableOutline, outlineAlpha);
                drawActionDrawable(canvas, action.drawableAnimated, filledAlpha);
            } else {
                drawActionDrawable(canvas, action.drawableAnimated, alpha);
            }
        } else {
            drawActionDrawable(canvas, action.drawableOutline, outlineAlpha);
            drawActionDrawable(canvas, action.drawableFilled, filledAlpha);
        }
    }

    private void drawRenderNode(Canvas canvas) {
        if (renderNode == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !renderNode.hasDisplayList() || !canvas.isHardwareAccelerated()) {
            return;
        }

        canvas.save();
        if (avatarView != null) {
            View v = (View) avatarView.getParent();
            float vl = v.getX();
            float vt = v.getY() - getTranslationY();
            float vw = v.getWidth() * v.getScaleX();
            float vh = v.getHeight() * v.getScaleY();

            clipAvatarPath.rewind();
            clipAvatarPath.addRoundRect(
                    vl,
                    vt,
                    vl + vw,
                    vt + vh,
                    avatarView.getRoundRadiusForExpand() * v.getScaleX(),
                    avatarView.getRoundRadiusForExpand() * v.getScaleY(),
                    Path.Direction.CCW
            );
            canvas.clipPath(clipAvatarPath);
        }

        canvas.clipPath(clipPath);
        canvas.translate(0f, renderNodeTranslateY);
        canvas.scale(renderNodeScale, renderNodeScale);
        canvas.drawRenderNode(renderNode);

        canvas.restore();
    }

    public void stopLoading(int key) {
        stopLoading(find(key));
    }

    private void stopLoading(Action a) {
        if (a != null && a.isLoading) {
            a.isLoading = false;
            invalidate();
        }
    }

    private void updateBounds(Action action) {
        final float cx = action.rect.centerX();
        final float cy = action.rect.centerY();
        if (material3) {
            final int size = ProfileActionsUiHelper.getIconSize();
            final int l = (int) (cx - size / 2f);
            final int t = (int) (top + (ProfileActionsUiHelper.getContainerHeight() - size) / 2f);
            action.setBounds(l, t, l + size, t + size);
            return;
        }

        final int drawableSize = dp(24);
        final float drawableR = drawableSize * 0.5f;

        action.text.setMaxWidth(action.rect.width() - dp(2));
        action.textScale = action.text.getLineCount() >= 3 ? 0.75f : action.text.getLineCount() >= 2 ? 0.85f : 1.0f;
        final float drawableTop = Math.max(0, (targetHeight - action.text.getHeight() * action.textScale) / 3f + dpf2(1.33f));
        action.setBounds(
            (int) (cx - drawableR),
            (int) (drawableTop),
            (int) (cx + drawableR),
            (int) (drawableTop + drawableSize)
        );
    }

    private int lastColorFilterColor;
    private ColorFilter lastColorFilter;

    private void drawAction(Canvas canvas, Action action, float fraction, float alpha) {
        if (action == null || action.isDeleted) {
            return;
        }

        final boolean isButtonColorLight = isButtonColorLight();
        float useFilledWhiteIcon = !isButtonColorLight ? 1 : MathUtils.clamp((parentExpanded - 0.75f) / 0.25f, 0, 1);
        if (isButtonColorLight && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            useFilledWhiteIcon = 0f;
        }

        final int textColor = ColorUtils.blendARGB(Color.BLACK, Color.WHITE, useFilledWhiteIcon);
        if (lastColorFilter == null || lastColorFilterColor != textColor) {
            lastColorFilterColor = textColor;
            lastColorFilter = new PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN);
        }

        canvas.save();
        alpha *= action.getAlpha();
        final float cx = action.rect.centerX();
        final float cy = action.rect.centerY();
        fraction *= action.getScale();
        canvas.scale(fraction, fraction, cx, cy);
        canvas.clipRect(action.rect);

        updateBounds(action);

        final float textY = action.bounds.bottom + action.bounds.top - action.text.getHeight() * action.textScale / 2.0f - dp(4.66f);
        canvas.save();
        canvas.scale(action.textScale, action.textScale, cx, textY + action.text.getHeight() * action.textScale / 2.0f);
        action.text.draw(canvas, cx - action.text.getWidth() / 2f, textY, textColor, alpha);
        canvas.restore();

        if (action.iconTranslationY != 0) {
            canvas.translate(0, action.iconTranslationY);
        }

        if (action.iconScale != 1f) {
            canvas.scale(action.iconScale, action.iconScale, action.bounds.centerX(), action.bounds.centerY());
        }
        if (!isAnimatingCallAction || action.key != KEY_CALL) {
            final float outlineAlpha = (1f - useFilledWhiteIcon) * alpha;
            final float filledAlpha = useFilledWhiteIcon * alpha;
            if (action.drawableAnimated != null) {
                if (action.key == KEY_NOTIFICATION) {
                    drawActionDrawable(canvas, action.drawableOutline, outlineAlpha);
                    drawActionDrawable(canvas, action.drawableAnimated, filledAlpha);
                } else {
                    drawActionDrawable(canvas, action.drawableAnimated, alpha);
                }
            } else {
                drawActionDrawable(canvas, action.drawableOutline, outlineAlpha);
                drawActionDrawable(canvas, action.drawableFilled, filledAlpha);
            }
        }

        canvas.restore();
        drawLoading(canvas, action, alpha, Color.WHITE);
    }

    private void drawActionDrawable(Canvas canvas, Drawable drawable, float alpha) {
        if (drawable == null) {
            return;
        }

        final int a = (int) (alpha * 255);

        drawable.setColorFilter(lastColorFilter);
        drawable.setAlpha(a);
        drawable.draw(canvas);
    }

    private void drawLoading(Canvas canvas, Action action, float alpha, int color) {
        if (action.stopDelay > 0 && System.currentTimeMillis() > action.stopDelay + action.startTime) {
            action.isLoading = false;
        }

        if (action.isLoading) {
            if (action.loadingDrawable == null) {
                action.loadingDrawable = new LoadingDrawable();
                action.loadingDrawable.setCallback(this);
                action.loadingDrawable.setColors(
                        Theme.multAlpha(color, .1f),
                        Theme.multAlpha(color, .3f),
                        Theme.multAlpha(color, .35f),
                        Theme.multAlpha(color, .8f)
                );
                action.loadingDrawable.setAppearByGradient(true);
                action.loadingDrawable.strokePaint.setStrokeWidth(dpf2(1.25f));
            } else if (action.loadingDrawable.isDisappeared() || action.loadingDrawable.isDisappearing()) {
                action.loadingDrawable.reset();
                action.loadingDrawable.resetDisappear();
            }
        } else if (action.loadingDrawable != null && !action.loadingDrawable.isDisappearing() && !action.loadingDrawable.isDisappeared()) {
            action.loadingDrawable.disappear();
        }

        if (action.loadingDrawable != null) {
            action.loadingDrawable.setBounds(material3 ? action.containerInView : action.rect);
            if (material3) {
                action.loadingDrawable.setRadii(action.containerRadiusInView);
            } else if (isSegmentedMode()) {
                final float outer = getRoundRadius();
                final float inner = getInnerRoundRadius();
                final boolean first = action.isDeleting ? action.wasFirst : action == firstAction;
                final boolean last = action.isDeleting ? action.wasLast : action == lastAction;
                action.loadingDrawable.setRadii(first ? outer : inner, last ? outer : inner, last ? outer : inner, first ? outer : inner);
            } else {
                action.loadingDrawable.setRadii(getRoundRadius());
            }
            action.loadingDrawable.setAlpha((int) (0xFF * alpha));
            action.loadingDrawable.draw(canvas);
        }
    }

    public float getRoundRadius() {
        return dp(com.exteragram.messenger.ExteraConfig.getSectionRadiusDp()); // exteraGram
    }

    public float getInnerRoundRadius() {
        // exteraGram: inner corners of the segmented group (as in the original: min(dp(4), sectionRadiusDp))
        return Math.min(dp(4), com.exteragram.messenger.ExteraConfig.getSectionRadiusDp());
    }

    private Action hit = null;
    private float downX, downY;
    private long downTime;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (currentHeight < dp(8)) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();
        int eventAction = event.getAction();

        if (eventAction == MotionEvent.ACTION_DOWN) {
            hit = null;
            int c = actions.size();
            for (int i = 0; i < c; i++) {
                Action a = actions.get(i);
                if (!a.isDeleting && a.rect.contains(x, y)) {
                    hit = a;
                    downX = x;
                    downY = y;
                    downTime = System.currentTimeMillis();
                    hit.setPressed(true);
                    if (material3) {
                        hit.rippleDrawable.setHotspot(x, y);
                        hit.rippleDrawable.setState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled});
                    }
//                    try {
//                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
//                    } catch (Exception ignore) {}
                    break;
                }
            }
        } else if (eventAction == MotionEvent.ACTION_MOVE) {
            if (hit != null) {
                if (Math.abs(x - downX) > 20 || Math.abs(y - downY) > 20) {
                    hit.setPressed(false);
                    hit.rippleDrawable.setState(new int[0]);
                    hit = null;
                }
            }
        } else if (eventAction == MotionEvent.ACTION_UP || eventAction == MotionEvent.ACTION_CANCEL) {
            if (hit != null) {
                hit.setPressed(false);
                hit.rippleDrawable.setState(new int[0]);
                if (eventAction == MotionEvent.ACTION_UP && hit.rect.contains(x, y)) {
                    if (System.currentTimeMillis() - downTime > 250) {
                        try {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                        } catch (Exception ignore) {
                        }
                    }
                    if (hit.supportsLoading && !hit.isLoading) {
                        hit.isLoading = true;
                        invalidate();
                    }
                    if (hit.supportsAnimate != 0 && com.exteragram.messenger.icons.IconManager.INSTANCE.isBasePackOnly(com.exteragram.messenger.IconPackType.DEFAULT)) { // exteraGram: no Lottie over icon-pack icons
                        hit.updateDrawable(true, hit.supportsAnimate);
                    }
                    hit.startTime = System.currentTimeMillis();
                    final Action finalHit = hit;
                    if (onActionClickListener != null) {
                        if (finalHit.callDelay == 0) {
                            onActionClickListener.onClick(hit.key, hit.rect.left, hit.rect.top);
                        } else {
                            postDelayed(() -> {
                                onActionClickListener.onClick(finalHit.key, finalHit.rect.left, finalHit.rect.top);
                            }, finalHit.callDelay);
                        }
                    }
                }
                hit = null;
                return true;
            }
        }
        return hit != null;
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).rippleDrawable == who) {
                return true;
            }
        }
        return super.verifyDrawable(who) || who instanceof LoadingDrawable;
    }

    public void beginApplyingActions() {
        isApplying = true;
    }

    public void commitActions() {
        if (isApplying) {
            isApplying = false;
            applyVisibleActions();
        }
    }

    public void set(int key, boolean enabled) {
        boolean changed;
        if (enabled) {
            changed = allAvailableActions.add(key);
        } else {
            changed = allAvailableActions.remove(key);
        }
        if (changed) {
            applyVisibleActions();
        }
    }

    public void setNotifications(boolean enabled) {
        final boolean animated = isNotificationsEnabled != enabled;
        isNotificationsEnabled = enabled;
        Action notificationAction = find(KEY_NOTIFICATION);
        if (notificationAction != null) {
            updateNotification(notificationAction, animated);
            invalidate();
        } else {
            allAvailableActions.add(KEY_NOTIFICATION);
            applyVisibleActions();
        }
    }

    public void addCameraAction() {
        Action action = new Action(ActionButton.SET_PHOTO);
        action.key = KEY_SET_PHOTO;
        actions.add(action);
    }

    public void addEditInfo() {
        final Action action = new Action(ActionButton.EDIT_INFO);
        action.key = KEY_EDIT_INFO;
        actions.add(action);
    }

    public void addSettings() {
        final Action action = new Action(ActionButton.SETTINGS);
        action.key = KEY_SETTINGS;
        actions.add(action);
    }

    public void startAnimatedActions() {
        if (mode == MODE_MY_PROFILE) {
            int c = actions.size();
            for (int i = 0; i < c; i++) {
                Action a = actions.get(i);
                if (a.drawableAnimated != null) {
                    if (a.key == KEY_EDIT_USERNAME) {
                        a.drawableAnimated.setCurrentFrame(14);
                    } else {
                        a.drawableAnimated.setCurrentFrame(0);
                    }
                    a.drawableAnimated.start();
                }
            }
        }
    }

    public boolean supportsEditInfo() {
        return mode == MODE_MY_PROFILE;
    }

    public void startCameraAnimation() {
        Action camera = find(KEY_SET_PHOTO);
        if (camera != null && camera.drawableAnimated != null) {
            camera.drawableAnimated.start();
        }
    }

    public boolean canHaveJoinAction() {
        return mode == MODE_CHANNEL || mode == MODE_GROUP;
    }

    private void updateNotification(Action notificationAction, boolean animated) {
        if (animated && com.exteragram.messenger.icons.IconManager.INSTANCE.isBasePackOnly(com.exteragram.messenger.IconPackType.DEFAULT)) { // exteraGram
            if (isNotificationsEnabled) {
                notificationAction.setText(getString(ActionButton.NOTIFICATION_MUTE.title));
                notificationAction.updateDrawable(
                    R.raw.profile_unmuting,
                    ActionButton.NOTIFICATION_MUTE.filledIcon,
                    ActionButton.NOTIFICATION_MUTE.outlineIcon
                );
            } else {
                notificationAction.setText(getString(ActionButton.NOTIFICATION_UNMUTE.title));
                notificationAction.updateDrawable(
                    R.raw.profile_muting,
                    ActionButton.NOTIFICATION_UNMUTE.filledIcon,
                    ActionButton.NOTIFICATION_UNMUTE.outlineIcon
                );
            }
        } else {
            notificationAction.update(isNotificationsEnabled ? ActionButton.NOTIFICATION_MUTE : ActionButton.NOTIFICATION_UNMUTE);
        }
    }

    private void applyVisibleActions() {
        if (isApplying) return;
        if (mode == MODE_MY_PROFILE) {
            activeCount = actions.size();
            invalidate();
            return;
        }

        List<Action> out = new ArrayList<>();
        boolean join = hasJoin();

        switch (mode) {
            case MODE_USER:
                insertIfAvailable(out, KEY_MESSAGE);
                insertIfAvailable(out, KEY_CALL);
                insertIfAvailable(out, KEY_VIDEO);
                insertIfAvailable(out, KEY_NOTIFICATION);
                insertIfNotAvailable(out, KEY_GIFT, KEY_VIDEO);
                break;
            case MODE_TOPIC:
                insertIfAvailable(out, KEY_MESSAGE);
                insertIfAvailable(out, KEY_NOTIFICATION);
                break;
            case MODE_CHANNEL:
                if (join) {
                    insertIfAvailable(out, KEY_JOIN);
                } else {
                    insertIfAvailable(out, KEY_VOICE_CHAT);
                    insertIfNotAvailable(out, KEY_STREAM, KEY_VOICE_CHAT);
                }
                insertIfAvailable(out, KEY_NOTIFICATION);
                if (!join) {
                    insertIfAvailable(out, KEY_DISCUSS);
                    insertIfNotAvailable2(out, KEY_GIFT, KEY_DISCUSS, KEY_STORY);
                }
                insertIfNotAvailable(out, KEY_SHARE, KEY_STORY);
                if (join) {
                    out.add(getOrCreate(KEY_REPORT));
                } else {
                    insertIfAvailable(out, KEY_STORY);
                    insertIfNotAvailable(out, KEY_LEAVE, KEY_STORY);
                }
                break;
            case MODE_GROUP:
            case MODE_FORUM:
                if (join) {
                    insertIfAvailable(out, KEY_JOIN);
                } else {
                    insertIfAvailable(out, KEY_MESSAGE);
                }
                insertIfAvailable(out, KEY_NOTIFICATION);
                if (join) {
                    out.add(getOrCreate(KEY_REPORT));
                } else {
                    insertIfAvailable(out, KEY_OPEN_CHANNEL);
                    insertIfAvailable(out, KEY_VOICE_CHAT);
                    insertIfNotAvailable(out, KEY_STREAM, KEY_VOICE_CHAT);
                    insertIfAvailable(out, KEY_STORY);
                    insertIfNotAvailable(out, KEY_LEAVE, KEY_STORY);
                }
                break;
            case MODE_BOT:
                insertIfAvailable(out, KEY_MESSAGE);
                insertIfAvailable(out, KEY_NOTIFICATION);
                insertIfAvailable(out, KEY_SHARE);
                out.add(getOrCreate(KEY_STOP));
                break;
        }

        AndroidUtilities.runOnUIThread(() -> {
            int oldCount = activeCount;
            activeCount = out.size();

            if (oldCount != activeCount && radialGradient != null) {
                createColorShader();
            }

            int c = actions.size();
            for (int i = 0; i < c; i++) {
                Action a = actions.get(i);
                if (a.isDeleting && !a.isDeleted) {
                    out.add(a);
                } else if (find(out, a.key) == null) {
                    a.delete();
                    out.add(a);
                }
            }

            actions.clear();
            actions.addAll(out);
            invalidate();
        });
    }

    private void insertIfAvailable(List<Action> list, int key) {
        if (allAvailableActions.contains(key)) {
            list.add(getOrCreate(key));
        }
    }

    private void insertIfNotAvailable(List<Action> list, int key, int notAvailable) {
        if (allAvailableActions.contains(key) &&
                !allAvailableActions.contains(notAvailable)) {
            list.add(getOrCreate(key));
        }
    }

    private void insertIfNotAvailable2(List<Action> list, int key, int notAvailable1, int notAvailable2) {
        if (allAvailableActions.contains(key) &&
                !allAvailableActions.contains(notAvailable1) &&
                !allAvailableActions.contains(notAvailable2)) {
            list.add(getOrCreate(key));
        }
    }

    private boolean hasJoin() {
        return allAvailableActions.contains(KEY_JOIN) &&
                !allAvailableActions.contains(KEY_LEAVE);
    }

    private Action getOrCreate(int key) {
        Action newAction = find(key);
        if (newAction != null) {
            if (key == KEY_NOTIFICATION) {
                updateNotification(newAction, false);
            }
            return newAction;
        }

        switch (key) {
            case KEY_MESSAGE:
                newAction = new Action(ActionButton.MESSAGE);
                break;
            case KEY_DISCUSS:
                newAction = new Action(ActionButton.DISCUSS);
                break;
            case KEY_GIFT:
                newAction = new Action(ActionButton.GIFT);
                newAction.supportsLoading = true;
                newAction.stopDelay = 200;
                break;
            case KEY_SHARE:
                newAction = new Action(ActionButton.SHARE);
                break;
            case KEY_CALL:
                newAction = new Action(ActionButton.CALL);
                callAction = newAction;
                newAction.supportsLoading = true;
                newAction.stopDelay = 500;
                break;
            case KEY_VIDEO:
                newAction = new Action(ActionButton.VIDEO);
                newAction.supportsLoading = true;
                newAction.stopDelay = 500;
                break;
            case KEY_JOIN:
                newAction = new Action(ActionButton.JOIN);
                newAction.supportsLoading = true;
                newAction.callDelay = 300;
                break;
            case KEY_REPORT:
                newAction = new Action(ActionButton.REPORT);
                newAction.supportsLoading = true;
                newAction.stopDelay = 500;
                break;
            case KEY_LEAVE:
                newAction = new Action(ActionButton.LEAVE);
                newAction.supportsLoading = true;
                newAction.supportsAnimate = R.raw.profile_leave;
                newAction.stopDelay = 300;
                break;
            case KEY_VOICE_CHAT:
                newAction = new Action(ActionButton.VOICE_CHAT);
                newAction.supportsLoading = true;
                newAction.supportsAnimate = R.raw.profile_voicechat;
                newAction.stopDelay = 500;
                break;
            case KEY_STREAM:
                newAction = new Action(ActionButton.STREAM);
                newAction.supportsLoading = true;
                newAction.supportsAnimate = R.raw.profile_voicechat;
                newAction.stopDelay = 500;
                break;
            case KEY_STORY:
                newAction = new Action(ActionButton.STORY);
                break;
            case KEY_OPEN_CHANNEL:
                newAction = new Action(ActionButton.OPEN_CHANNEL);
                break;
            case KEY_STOP:
                newAction = new Action(ActionButton.STOP);
                newAction.supportsLoading = true;
                newAction.stopDelay = 300;
                break;
            case KEY_NOTIFICATION:
                newAction = new Action();
                updateNotification(newAction, false);
                break;
        }

        if (newAction != null) {
            newAction.key = key;
        }
        return newAction;
    }

    private Action find(int key) {
        return find(actions, key);
    }

    private Action find(List<Action> actions, int key) {
        int c = actions.size();
        for (int i = 0; i < c; i++) {
            Action a = actions.get(i);
            if (!a.isDeleting && a.key == key) {
                return a;
            }
        }
        return null;
    }

    public boolean hasCall() {
        return allAvailableActions.contains(KEY_CALL) && callAction != null;
    }

    private boolean callAnimationStateLoaded = false;
    private float callBackwardAnimateFromX = -1;
    private float callBackwardAnimateFromY = -1;

    public void applyCallTransition(
            View callView,
            boolean isOpen,
            float maxBottom,
            float fraction
    ) {
        if (callView == null || getMeasuredWidth() <= 0f) {
            return;
        }

        float callAnimateFromX = callView.getLeft();
        float callAnimateFromY = callView.getTop();
        if (isOpen) {
            int c = actions.size();
            final float betweenPadding = xpadding / 2f;
            final float width = getItemWidth();
            float left = xpadding;

            for (int i = 0; i < c; i++) {
                Action action = actions.get(i);
                if (action.isDeleted) continue;
                if (action.key == KEY_CALL) {
                    callAction.rect.set(left, top, left + width, top + targetHeight);
                    break;
                }
                left += width + betweenPadding;
            }

            updateBounds(callAction);

            float callAnimateEndY = maxBottom - targetHeight - ypadding - top
                    + callAction.bounds.centerY()
                    - callView.getMeasuredHeight() / 2f
                    - callAnimateFromY;
            float callAnimateEndX = callAction.bounds.centerX()
                    - callView.getMeasuredWidth() / 2f
                    - callAnimateFromX;

            callView.setTranslationX(AndroidUtilities.lerp(0f, callAnimateEndX, fraction));
            callView.setTranslationY(AndroidUtilities.lerp(0f, callAnimateEndY, fraction));
        } else {
            if (!callAnimationStateLoaded) {
                callAnimationStateLoaded = true;
                callBackwardAnimateFromY = getTranslationY()
                        + callAction.bounds.centerY()
                        - callView.getMeasuredHeight() / 2f
                        - callAnimateFromY;

                callBackwardAnimateFromX = callAction.bounds.centerX()
                        - callView.getMeasuredWidth() / 2f
                        - callAnimateFromX;
            }

            callView.setTranslationX(AndroidUtilities.lerp(0f, callBackwardAnimateFromX, fraction));
            callView.setTranslationY(AndroidUtilities.lerp(0f, callBackwardAnimateFromY, fraction));
        }

        if (callView.getVisibility() != View.VISIBLE) {
            callView.setVisibility(View.VISIBLE);
        }
    }

    public interface OnActionClickListener {
        void onClick(int key, float x, float y);
    }

    private class Action {
        int key;

        private final ButtonBounce bounce = new ButtonBounce(ProfileActionsView.this);
        final RectF prevRect = new RectF();
        final RectF rect = new RectF();

        private final AnimatedFloat positionFraction = new AnimatedFloat(ProfileActionsView.this, 0, 250, CubicBezierInterpolator.DEFAULT);
        private final RectF to = new RectF();
        private final RectF from = new RectF();

        private final Rect bounds = new Rect();
        private Drawable drawableFilled;
        private Drawable drawableOutline;
        private RLottieDrawable drawableAnimated;
        private Text text;
        private float textScale = 1.0f;
        private CharSequence title;

        // exteraGram: Material 3 container geometry
        private final RectF container = new RectF();
        private final RectF containerInView = new RectF();
        private final Matrix layoutMatrix = new Matrix();
        private float containerRadius;
        private float containerRadiusInView;
        private float labelTopOffset;
        private float labelFitWidth = -1;
        private float pressProgress;
        private SpringAnimation pressSpring;
        private int rippleColor;
        private final Drawable rippleDrawable = createRippleDrawable();

        private Drawable createRippleDrawable() {
            final Drawable drawable = Theme.createRadSelectorDrawable(0, 0x11ffffff, 0, 0);
            drawable.setCallback(ProfileActionsView.this);
            return drawable;
        }

        public void setPressed(boolean pressed) {
            bounce.setPressed(pressed);
            if (material3) {
                if (pressSpring == null) {
                    pressSpring = ProfileActionsUiHelper.createPressSpring(this, PRESS_PROGRESS);
                }
                pressSpring.animateToFinalPosition(pressed ? 1f : 0f);
            }
        }

        private void setPressProgress(float value) {
            pressProgress = value;
            invalidate();
        }

        public void updateMaterial3Layout(float fraction) {
            final float cx = rect.centerX();
            final float width = (isDeleting ? from : isOpening ? to : rect).width();
            container.set(cx - width / 2f, top, cx + width / 2f, top + ProfileActionsUiHelper.getContainerHeight());
            containerRadius = ProfileActionsUiHelper.getCornerRadius(pressProgress);
            final float scale = width > 0 && (isDeleting || isOpening) ? Utilities.clamp01(rect.width() / width) : 1f;
            layoutMatrix.setScale(scale, scale, cx, top + targetHeight / 2f);
            layoutMatrix.postScale(fraction, fraction, cx, top);
            layoutMatrix.mapRect(containerInView, container);
            containerRadiusInView = containerRadius * scale * fraction;
        }

        public void fitLabel(float width) {
            if (labelFitWidth != width) {
                labelFitWidth = width;
                textScale = ProfileActionsUiHelper.fitLabel(text, title, width);
                labelTopOffset = ProfileActionsUiHelper.getLabelTopOffset(text);
            }
        }

        public void updateRippleColor(int color) {
            if (rippleColor != color && rippleDrawable instanceof RippleDrawable) {
                rippleColor = color;
                ((RippleDrawable) rippleDrawable).setColor(ColorStateList.valueOf(color));
            }
        }

        public void setBounds(int l, int t, int r, int b) {
            bounds.set(l, t, r, b);
            checkBounds();
        }

        private void checkBounds() {
            if (drawableAnimated != null) {
                drawableAnimated.setBounds(bounds);
            }
            if (drawableFilled != null) {
                drawableFilled.setBounds(bounds);
            }
            if (drawableOutline != null) {
                drawableOutline.setBounds(bounds);
            }
        }

        public void setText(CharSequence cs) {
            this.title = cs;
            if (material3) {
                this.text = ProfileActionsUiHelper.createLabel(cs);
                this.labelFitWidth = -1;
            } else {
                this.text = new Text(cs, 11, AndroidUtilities.bold())
                    .multiline(3)
                    .align(Layout.Alignment.ALIGN_CENTER);
            }
        }

        boolean isOpening = false;
        boolean isDeleting = false;
        boolean isDeleted = false;
        boolean wasFirst, wasLast; // exteraGram: segment corners while deleting

        int iconTranslationY = 0;
        float iconScale = 1f;

        LoadingDrawable loadingDrawable;
        boolean isLoading;
        boolean supportsLoading;
        int supportsAnimate;
        int callDelay = 0;
        long startTime;
        int stopDelay;

        @Deprecated
        public Action() {
        }

        public Action(ActionButton button) {
            update(button);
        }

        public float getAlpha() {
            if (isDeleting) {
                return 1f - positionFraction.set(1f);
            } else if (isOpening) {
                return positionFraction.set(1f);
            } else {
                return 1f;
            }
        }

        public void delete() {
            if (loadingDrawable != null) {
                loadingDrawable.disappear();
                supportsLoading = false;
                isLoading = false;
            }
            isDeleting = true;
            wasFirst = this == firstAction;
            wasLast = this == lastAction;

            boolean isFirstItem = prevRect.left - 1 <= xpadding;
            boolean isLastItem = prevRect.right + 1 >= getMeasuredWidth() - xpadding;

            if (isFirstItem && isLastItem) {
                isFirstItem = isLastItem = false;
            }
            from.set(prevRect);
            to.set(prevRect);

            if (isFirstItem) {
                to.right = to.left;
            } else if (isLastItem) {
                to.left = to.right;
            } else if ((key == KEY_GIFT || key == KEY_DISCUSS) && mode == MODE_CHANNEL) {
                to.left = to.right;
            } else {
                to.left = to.right = to.centerX();
            }
            positionFraction.set(0f, true);
        }

        @Deprecated
        public void updateDrawable(boolean animated, int drawableRes) {
            if (animated) {
                updateDrawable(drawableRes, 0, 0);
            } else {
                updateDrawable(0, drawableRes, drawableRes);
            }
        }

        @Deprecated
        public void update(boolean animated, int drawableRes, int textRes) {
            updateDrawable(animated, drawableRes);
            setText(getString(textRes));
        }

        public void updatePosition() {
            if (isDeleting) {
                animatePosition();
                return;
            }

            if (isOpeningLayout) {
                isOpening = false;
                prevRect.set(rect);
                from.set(rect);
                to.set(rect);
                positionFraction.set(1f, true);
                return;
            }

            if (to.isEmpty()) {
                isOpening = true;
                to.set(rect);
                from.set(rect);

                boolean fromRight = rect.left - 1 <= xpadding;
                boolean fromLeft = rect.right + 1 >= getMeasuredWidth() - xpadding;

                if ((fromRight && fromLeft) ||
                        (firstAction != null && firstAction.key == key) ||
                        (lastAction != null && lastAction.key == key)) {
                    fromRight = fromLeft = false;
                }

                if ((key == KEY_CALL || key == KEY_VIDEO) && mode == MODE_USER) {
                    fromLeft = false;
                    fromRight = true;
                } else if ((key == KEY_GIFT || key == KEY_DISCUSS) && mode == MODE_CHANNEL) {
                    fromLeft = false;
                    fromRight = true;
                } else if (fromRight && firstAction != null && !firstAction.isDeleting) {
                    fromLeft = true;
                    fromRight = false;
                } else if (fromLeft && lastAction != null && !lastAction.isDeleting) {
                    fromLeft = false;
                    fromRight = true;
                }

                if (fromRight) {
                    from.left = from.right;
                } else if (fromLeft) {
                    from.right = from.left;
                } else {
                    from.left = from.right = to.centerX();
                }

                positionFraction.set(0f, true);
            }

            if (!rect.equals(to)) {
                from.set(prevRect);
                to.set(rect);
                positionFraction.set(0f, true);
            }

            animatePosition();
            prevRect.set(rect);
        }

        private void animatePosition() {
            float fraction = positionFraction.set(1f);
            if (fraction != 1f) {
                rect.left = AndroidUtilities.lerp(from.left, to.left, fraction);
                rect.right = AndroidUtilities.lerp(from.right, to.right, fraction);
            } else {
                isOpening = false;
                if (isDeleting) {
                    isDeleted = true;
                }
            }
        }

        public float getScale() {
            if (material3) {
                return 1f; // exteraGram: M3 buttons morph their corners instead of bouncing
            }
            return bounce.getScale(0.04f);
        }

        public void update(ActionButton button) {
            updateDrawable(0, button.filledIcon, button.outlineIcon);
            setText(getString(button.title));
        }

        @SuppressLint("UseCompatLoadingForDrawables")
        public void updateDrawable(@RawRes int animatedRes, @DrawableRes int filledRes, @DrawableRes int outlineRes) {
            if (animatedRes != 0) {
                RLottieDrawable drawable = new RLottieDrawable(animatedRes, dp(56), dp(56), false, null);
                drawable.setMasterParent(ProfileActionsView.this);
                drawable.start();
                drawableAnimated = drawable;
            } else {
                drawableAnimated = null;
            }
            drawableFilled = filledRes != 0 ? getResources().getDrawable(filledRes).mutate() : null;
            drawableOutline = outlineRes != 0 ? getResources().getDrawable(outlineRes).mutate() : null;

            checkBounds();
        }
    }

    public enum ActionButton {
        MESSAGE(R.string.ProfileActionsMessage, R.drawable.filled_profile_message_24, R.drawable.outline_profile_message_24),
        NOTIFICATION_MUTE(R.string.ProfileButtonMute, R.drawable.filled_profile_mute_24, R.drawable.outline_profile_mute_24),
        NOTIFICATION_UNMUTE(R.string.ProfileButtonUnmute, R.drawable.filled_profile_unmute_24, R.drawable.outline_profile_unmute_24),
        DISCUSS(R.string.ProfileActionsDiscuss, R.drawable.filled_profile_message_24, R.drawable.outline_profile_message_24),
        GIFT(R.string.ProfileActionsGift, R.drawable.gift, R.drawable.input_gift_s),
        SHARE(R.string.ProfileActionsShare, R.drawable.action_share, R.drawable.msg_share),
        CALL(R.string.ProfileActionsCall, R.drawable.filled_profile_call_24, R.drawable.outline_profile_call_24),
        VIDEO(R.string.ProfileActionsVideo, R.drawable.filled_profile_video_24, R.drawable.outline_profile_video_24),
        JOIN(R.string.ProfileActionsJoin, R.drawable.filled_profile_member_24, R.drawable.outline_profile_member_24),
        REPORT(R.string.ProfileActionsReport, R.drawable.report, R.drawable.msg_report),
        LEAVE(R.string.ProfileActionsLeave, R.drawable.leave, R.drawable.leave),
        VOICE_CHAT(R.string.ProfileActionsVoiceChat, R.drawable.live_stream, R.drawable.live_stream),
        STREAM(R.string.ProfileActionsLiveStream, R.drawable.live_stream, R.drawable.live_stream),
        STORY(R.string.Story, R.drawable.filled_profile_story, R.drawable.outline_profile_story),
        OPEN_CHANNEL(R.string.ProfileChannel, R.drawable.msg_channel_filled, R.drawable.msg_channel),
        STOP(R.string.ProfileActionsStop, R.drawable.filled_profile_stop_24, R.drawable.outline_profile_stop_24),
        SET_PHOTO(R.string.ProfileActionsEditPhoto2, R.drawable.filled_profile_photo, R.drawable.outline_profile_photo),
        EDIT_USERNAME(R.string.ProfileActionsEditUsername, R.drawable.filled_profile_edit_24, R.drawable.outline_profile_edit_24),
        EDIT_INFO(R.string.ProfileActionsEditInfo, R.drawable.filled_profile_edit_24, R.drawable.outline_profile_edit_24),
        SETTINGS(R.string.Settings, R.drawable.filled_profile_settings, R.drawable.outline_profile_settings),;

        final @StringRes int title;
        final @DrawableRes int filledIcon;
        final @DrawableRes int outlineIcon;

        ActionButton(@StringRes int title, @DrawableRes int filledIcon, @DrawableRes int outlineIcon) {
            this.title = title;
            this.filledIcon = filledIcon;
            this.outlineIcon = outlineIcon;
        }
    }

    private AccessibilityNodeProvider accessibilityNodeProvider;
    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (accessibilityNodeProvider == null) {
            accessibilityNodeProvider = new AccessibilityNodeProvider() {
                @Override
                public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                    int[] pos = {0, 0};
                    getLocationOnScreen(pos);
                    if (virtualViewId == HOST_VIEW_ID) {
                        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(ProfileActionsView.this);
                        onInitializeAccessibilityNodeInfo(info);
                        info.setEnabled(true);

                        for (int i = 0; i < actions.size(); ++i) {
                            info.addChild(ProfileActionsView.this, actions.get(i).key);
                        }

                        return info;
                    } else {
                        Action action = null;
                        for (int i = 0; i < actions.size(); ++i) {
                            if (actions.get(i).key == virtualViewId) {
                                action = actions.get(i);
                                break;
                            }
                        }
                        if (action == null) return null;
                        if (action.rect.isEmpty()) return null;

                        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
                        info.setSource(ProfileActionsView.this, virtualViewId);
                        info.setParent(ProfileActionsView.this);
                        info.setPackageName(getContext().getPackageName());

                        info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
                        info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                        info.setClickable(true);
                        info.setFocusable(true);
                        info.setEnabled(true);
                        info.setVisibleToUser(true);
                        info.setClassName(android.widget.Button.class.getName());

                        info.setText(action.title != null ? action.title : action.text.getText()); // exteraGram: the M3 label may be ellipsized

                        Rect parentBounds = new Rect(
                                (int) action.rect.left,
                                (int) action.rect.top,
                                (int) action.rect.right,
                                (int) action.rect.bottom
                        );
                        info.setBoundsInParent(parentBounds);
                        parentBounds.offset(pos[0], pos[1]);
                        info.setBoundsInScreen(parentBounds);

                        return info;
                    }
                }

                @Override
                public boolean performAction(int virtualViewId, int action, @Nullable Bundle arguments) {
                    if (virtualViewId == HOST_VIEW_ID) {
                        return performAccessibilityAction(action, arguments);
                    }

                    Action button = null;
                    for (int i = 0; i < actions.size(); ++i) {
                        if (actions.get(i).key == virtualViewId) {
                            button = actions.get(i);
                            break;
                        }
                    }
                    if (button == null) return false;

                    if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                        sendAccessibilityEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                        return true;
                    } else if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                        if (onActionClickListener != null) {
                            onActionClickListener.onClick(virtualViewId, 0, 0);
                        }
                        return true;
                    }

                    return false;
                }

                private void sendAccessibilityEventForVirtualView(int viewId, int eventType) {
                    sendAccessibilityEventForVirtualView(viewId, eventType, null);
                }

                private void sendAccessibilityEventForVirtualView(int viewId, int eventType, String text) {
                    AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
                    if (am.isTouchExplorationEnabled()) {
                        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
                        event.setPackageName(getContext().getPackageName());
                        event.setSource(ProfileActionsView.this, viewId);
                        if (text != null) {
                            event.getText().add(text);
                        }
                        if (getParent() != null) {
                            getParent().requestSendAccessibilityEvent(ProfileActionsView.this, event);
                        }
                    }
                }
            };
        }
        return accessibilityNodeProvider;
    }
}
