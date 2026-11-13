package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProfileMusicPulse;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.ProfileActivity;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Pinned collectible gifts floating around the profile avatar, each inside a glass bubble.
 *
 * <ul>
 * <li>The bubbles orbit the avatar on a tilted ellipse (the far half passes behind the avatar), can be spun
 *     with a horizontal swipe and flung; a long press or a drag picks a bubble up, it inflates and flies back
 *     on release.</li>
 * <li>A tap opens {@link ProfileGiftScene}: the bubble inflates in the middle of the screen with an "Open" button.</li>
 * <li>{@link #getAmbientView()} sits under the avatar and paints a soft glow in the gifts' colors; while music
 *     plays it fills the header with the backdrop of one gift and switches to the next gift on every beat
 *     ({@link ProfileMusicPulse}).</li>
 * <li>With liquid glass enabled every bubble also refracts the header behind it ({@link #getGiftGlassSource}).</li>
 * </ul>
 */
public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final long BEAT_GLOW_MS = 360;
    private static final float HELD_GIFT_SCALE = 3.55f;

    private final int currentAccount;
    private final long dialogId;
    private final View avatarContainer;
    private final ProfileActivity.AvatarImageView avatarImage;
    private final Theme.ResourcesProvider resourcesProvider;
    private final View ambientView;

    private boolean active = true;
    private boolean animateScene;
    // bubbles off in settings: gifts sit around the avatar the standard way (no orbit, glass, drag or ambient)
    private final boolean classic = !org.telegram.ui.music.MusicPlayerConfig.isProfileGiftBubbles();

    private final RectF avatarOcclusion = new RectF();
    private final Path avatarOcclusionPath = new Path();
    private float avatarOcclusionRadius;

    private final long orbitStart = SystemClock.uptimeMillis();
    private double spinOffset;
    private float spinVelocity;
    private long spinReleasedAt;
    private boolean spinning;
    private float lastSpinX;

    private final Paint ambientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix ambientMatrix = new Matrix();
    private RadialGradient ambientPalette, previousPalette;
    private int paletteColor, paletteEdge;
    private final AnimatedFloat paletteProgress = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    private MessageObject musicTrack;
    private long musicFrameAt;
    private float musicBass, musicKick, musicOpacity;
    private long lastBeat, beatStartedAt;
    private float beatStrength;
    private long musicGiftId, previousMusicGiftId, backdropChangedAt;

    private GlassSourceRenderNode giftGlassSource;

    // touch: press, hold-and-drag, spin
    private Gift pressedGift;
    private boolean giftGesture;
    private float touchDownX, touchDownY;
    private VelocityTracker giftVelocity;
    private final Runnable holdGift = () -> {
        if (active && pressedGift != null) {
            beginGrab(touchDownX, touchDownY);
        }
    };
    private long draggedGiftID;
    private float grabOffsetX, grabOffsetY;
    private float dragX, dragY;
    private long grabStarted;
    private float grabOriginX, grabOriginY, grabOriginScale, grabOriginTilt;
    private ImageReceiver heldArtwork, heldArtworkReceiver;
    private long returnStarted;
    private float returnVelocityX, returnVelocityY;
    private float releaseScale, releaseStretch, releaseTilt;

    private long sceneGiftID;

    private static final float HELD_MARGIN_DP = 6;
    // a held bubble inflates as it is pulled away from the avatar: fully at this distance from the avatar's edge
    private static final float HELD_GROW_DISTANCE_DP = 110;

    private float contentBottom = -1;
    // where the held bubble was really drawn (after fitting it into the header)
    private float heldDrawnX, heldDrawnY, heldDrawnScale, heldDrawnStretch;
    private boolean heldDrawn;

    private final android.util.LongSparseArray<ProfileGiftGlass> glassCache = new android.util.LongSparseArray<>();
    private boolean frameInvalidatePending;

    public ProfileGiftsView(Context context, int currentAccount, long dialogId, @NonNull View avatarContainer, ProfileActivity.AvatarImageView avatarImage, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;

        this.avatarContainer = avatarContainer;
        this.avatarImage = avatarImage;

        this.resourcesProvider = resourcesProvider;

        ambientView = new View(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                drawBehindAvatar(canvas);
            }
        };
        ambientView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The glow/backdrop layer; ProfileActivity puts it under the avatar. */
    public View getAmbientView() {
        return ambientView;
    }

    public int getGiftAccount() {
        return currentAccount;
    }

    /** Top of the profile buttons (bubbles never go below it), -1 when there are none. */
    public void setContentBottom(float y) {
        if (Math.abs(contentBottom - y) > 0.5f) {
            contentBottom = y;
            invalidate();
        }
    }

    private float headerLimit() {
        return contentBottom >= 0 ? Math.min(contentBottom, expandY) : expandY;
    }

    /** Below the status bar and the toolbar: a held bubble never goes over them. */
    private float headerTop() {
        return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
    }

    /** Bubbles with the same backdrop share one glass (paints, paths, shaders). */
    ProfileGiftGlass obtainGlass(int color, int edgeColor) {
        final long key = ((long) color << 32) | (edgeColor & 0xFFFFFFFFL);
        ProfileGiftGlass glass = glassCache.get(key);
        if (glass == null) {
            glass = new ProfileGiftGlass(color, edgeColor);
            glassCache.put(key, glass);
        }
        return glass;
    }

    public void setActive(boolean f) {
        this.active = f;
        if (!f) {
            finishGesture();
            clearGrab();
        }
        invalidate();
    }

    public float expandProgress, collapseProgress;
    public boolean isOpening;

    public void setExpandProgress(float progress) {
        if (this.expandProgress != progress) {
            this.expandProgress = progress;
            invalidate();
        }
    }

    public void setCollapseProgress(float progress, boolean isOpening) {
        this.isOpening = isOpening;
        float newProgress = Utilities.clamp01((progress - 0.3f) / 0.7f);
        if (this.collapseProgress != newProgress) {
            this.collapseProgress = newProgress;
            invalidate();
        }
    }

    private float actionBarProgress;

    public void setActionBarActionMode(float progress) {
        actionBarProgress = progress;
        invalidate();
    }

    private float left, right, cy;
    private float expandY, maxExpandY;
    private final AnimatedFloat rightAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setBounds(float left, float right, float cy, boolean animated, int maxExpandY) {
        boolean changed = Math.abs(left - this.left) > 0.1f || Math.abs(right - this.right) > 0.1f || Math.abs(cy - this.cy) > 0.1f;
        this.left = left;
        this.right = right;
        if (!animated) {
            this.rightAnimated.set(this.right, true);
        }
        this.cy = cy;
        this.maxExpandY = maxExpandY + cy;
        if (changed) {
            invalidate();
        }
    }

    public void setExpandCoords(float y) {
        this.expandY = y;
        invalidate();
    }

    private float progressToInsets = 1f;

    public void setProgressToStoriesInsets(float progressToInsets) {
        if (this.progressToInsets == progressToInsets) {
            return;
        }
        this.progressToInsets = progressToInsets;
        invalidate();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (frameInvalidatePending) {
            // the next orbit frame: the backdrop under the avatar did not change
            frameInvalidatePending = false;
            return;
        }
        if (ambientView != null) {
            ambientView.invalidate();
        }
    }

    private void invalidateFrame() {
        frameInvalidatePending = true;
        postInvalidateOnAnimation();
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (ambientView != null) {
            ambientView.setAlpha(alpha);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starUserGiftsLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        ProfileMusicPulse.attach();

        for (Gift gift : gifts) {
            if (gift.emojiDrawable != null) {
                gift.emojiDrawable.addView(this);
            }
            if (gift.pattern != null) {
                gift.pattern.attach();
            }
        }

        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        finishGesture();
        clearGrab();
        super.onDetachedFromWindow();

        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        ProfileMusicPulse.detach();

        for (Gift gift : gifts) {
            if (gift.emojiDrawable != null) {
                gift.emojiDrawable.removeView(this);
            }
            if (gift.pattern != null) {
                gift.pattern.detach();
            }
        }
        clearExitingGifts();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded) {
            if ((long) args[0] == dialogId) {
                update();
            }
        } else {
            // music started / paused: the backdrop starts or stops pulsing
            invalidate();
        }
    }

    public final class Gift {

        public final long id;
        public final TLRPC.Document document;
        public final long documentId;
        public final int color;
        public final int edgeColor;
        public final String slug;
        private final TLRPC.Document symbol;
        private final int patternColor;
        private final int glassPatternColor;
        private final PorterDuffColorFilter glassPatternTint;
        private StarsReactionsSheet.Particles particles;

        public int position = -1;

        public Gift(TL_stars.TL_starGiftUnique gift) {
            id = gift.id;
            document = gift.getDocument();
            documentId = document == null ? 0 : document.id;
            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            final int fallback = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            color = backdrop == null ? fallback : backdrop.center_color | 0xFF000000;
            edgeColor = backdrop == null ? fallback : backdrop.edge_color | 0xFF000000;
            final TL_stars.starGiftAttributePattern patternAttribute = findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class);
            symbol = patternAttribute == null ? null : patternAttribute.document;
            patternColor = backdrop == null ? fallback : backdrop.pattern_color | 0xFF000000;
            glassPatternColor = ColorUtils.blendARGB(patternColor, 0xFFFFFFFF, 0.74f);
            glassPatternTint = symbol == null ? null : new PorterDuffColorFilter(glassPatternColor, PorterDuff.Mode.SRC_IN);
            slug = gift.slug;
            initParticles();
        }

        public Gift(TLRPC.TL_emojiStatusCollectible status) {
            id = status.collectible_id;
            document = null;
            documentId = status.document_id;
            color = status.center_color | 0xFF000000;
            edgeColor = status.edge_color | 0xFF000000;
            symbol = null;
            patternColor = edgeColor;
            glassPatternColor = 0xFFFFFFFF;
            glassPatternTint = null;
            slug = status.slug;
            initParticles();
        }

        private void initParticles() {
            particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 6);
            final float gsz = dp(36);
            particles.bounds.set(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f);
        }

        float getDrawnScale() {
            return drawnScale;
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id && b.documentId == documentId && b.color == color && b.edgeColor == edgeColor;
        }

        public RadialGradient gradient;
        public final Matrix gradientMatrix = new Matrix();
        public Paint gradientPaint;
        public AnimatedEmojiDrawable emojiDrawable;
        public AnimatedFloat animatedFloat;
        private AnimatedFloat positionX, positionY;
        private boolean laidOut;
        private boolean behindAvatar;
        private float drawnScale = 1.0f;
        private float drawnTilt;
        private long exitStart;
        private ProfileGiftGlass glass;
        private BlurredBackgroundDrawable lens;
        private AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable pattern;
        private RadialGradient musicGradient, musicHalo, musicRipple;

        public final RectF bounds = new RectF();
        public final ButtonBounce bounce = new ButtonBounce(ProfileGiftsView.this);

        public void copy(Gift b) {
            emojiDrawable = b.emojiDrawable;
            animatedFloat = b.animatedFloat;
            positionX = b.positionX;
            positionY = b.positionY;
            laidOut = b.laidOut;
            particles = b.particles;
            drawnScale = b.drawnScale;
            drawnTilt = b.drawnTilt;
            if (color == b.color && edgeColor == b.edgeColor) {
                glass = b.glass;
                musicGradient = b.musicGradient;
                musicHalo = b.musicHalo;
                musicRipple = b.musicRipple;
            }
            lens = b.lens;
            pattern = b.pattern;
            if (pattern != null && (symbol == null || b.symbol == null || symbol.id != b.symbol.id || patternColor != b.patternColor)) {
                pattern.detach();
                pattern = null;
            }
            bounds.set(b.bounds);
            position = b.position;
        }

        public void draw(
                Canvas canvas,
                float cx, float cy,
                float ascale, float rotate,
                float alpha,
                float gradientAlpha
        ) {
            draw(canvas, cx, cy, ascale, rotate, alpha, gradientAlpha, 0.0f);
        }

        public void draw(
                Canvas canvas,
                float cx, float cy,
                float ascale, float rotate,
                float alpha,
                float gradientAlpha,
                float stretch
        ) {
            final float gsz = dp(45);
            bounds.set(cx - gsz / 2, cy - gsz / 2, cx + gsz / 2, cy + gsz / 2);
            if (alpha <= 0.0f) return;
            if (classic) {
                drawClassic(canvas, cx, cy, ascale, rotate, alpha, gradientAlpha);
                return;
            }
            final boolean held = id == draggedGiftID;
            final float scale = held ? ascale : bounce.getScale(0.1f) * ascale;
            drawnScale = scale;
            drawnTilt = rotate;
            final float radius = dp(20);
            if (glass == null) {
                glass = obtainGlass(color, edgeColor);
            }
            final float wobble = animateScene ? Math.max(-1.0f, Math.min(1.0f, rotate / 10.0f)) : 0.0f;
            final float pulse = giftMusicPulse();

            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            glass.drawGlow(canvas, radius * scale, alpha, wobble, pulse, stretch);
            canvas.restore();

            drawBackdropLens(canvas, cx, cy, radius * scale, alpha, wobble, stretch, rotate);

            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            canvas.scale(scale, scale);
            if (animateScene && LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
                particles.process();
                particles.draw(canvas, color, alpha);
            }
            if (gradientPaint != null) {
                gradientPaint.setAlpha((int) (0xFF * alpha * gradientAlpha));
                canvas.drawRect(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f, gradientPaint);
            }
            glass.draw(canvas, radius, alpha, wobble, pulse, stretch, false);
            drawGlassPattern(this, canvas, glass, radius, wobble, stretch, (stretch * 0.6f + 0.32f) * alpha);
            if (held && heldArtwork != null && heldArtwork.getBitmap() != null) {
                final float s = dp(14);
                heldArtwork.setImageCoords(-s, -s, s * 2.0f, s * 2.0f);
                heldArtwork.setAlpha(alpha);
                heldArtwork.draw(canvas);
            } else if (emojiDrawable != null) {
                int sz = dp(26);
                if (held) {
                    sz = Math.min(sz, Math.round(dp(54) / scale));
                }
                emojiDrawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
                emojiDrawable.setAlpha(Math.round(alpha * 255.0f));
                emojiDrawable.draw(canvas);
            }
            glass.draw(canvas, radius, alpha, wobble, pulse, stretch, true);
            canvas.restore();
        }

        private void drawClassic(Canvas canvas, float cx, float cy, float ascale, float rotate, float alpha, float gradientAlpha) {
            final float gsz = dp(45);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            final float scale = ascale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            particles.process();
            particles.draw(canvas, color, alpha);
            if (gradientPaint != null) {
                gradientPaint.setAlpha((int) (0xFF * alpha * gradientAlpha));
                canvas.drawRect(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f, gradientPaint);
            }
            if (emojiDrawable != null) {
                final int sz = dp(24);
                emojiDrawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
                emojiDrawable.setAlpha(Math.round(alpha * 255.0f));
                emojiDrawable.draw(canvas);
            }
            canvas.restore();
        }

        /** Liquid glass refraction of the header under the bubble (Android 12+, liquid glass enabled). */
        private void drawBackdropLens(Canvas canvas, float cx, float cy, float radius, float alpha, float wobble, float stretch, float rotate) {
            if (radius < 1.0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) {
                return;
            }
            if (giftGlassSource == null || giftGlassSource.inRecording() || !LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
                return;
            }
            if (lens == null) {
                lens = giftGlassSource.createDrawable();
                lens.setLiquidGlassEffectAllowed();
            }
            final int w = Math.round(glass.widthScale(wobble, stretch) * radius);
            final int h = Math.round(glass.heightScale(wobble, stretch) * radius);
            lens.setBounds(-w, -h, w, h);
            lens.setRadius(Math.min(w, h));
            lens.setSourceOffset(cx, cy);
            lens.setIntensity(0.43f * stretch + 0.52f);
            lens.setAlpha(Math.round((87.0f * stretch + 158.0f) * alpha));
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            glass.clip(canvas, radius, wobble, stretch);
            lens.draw(canvas);
            canvas.restore();
        }
    }

    private StarsController.GiftsList list;

    public final ArrayList<Gift> oldGifts = new ArrayList<>();
    public final ArrayList<Gift> gifts = new ArrayList<>();
    private final ArrayList<Gift> exitingGifts = new ArrayList<>();
    public final HashSet<Long> giftIds = new HashSet<>();
    public int maxCount;

    private static final int[] ORBIT_POSITIONS = { 1, 4, 2, 5, 0, 3 };

    public void update() {
        if (!MessagesController.getInstance(currentAccount).enableGiftsInProfile) {
            return;
        }

        maxCount = Math.min(ORBIT_POSITIONS.length, MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit);
        oldGifts.clear();
        oldGifts.addAll(gifts);
        gifts.clear();
        giftIds.clear();

        final TLRPC.EmojiStatus emojiStatus;
        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            emojiStatus = user == null ? null : user.emoji_status;
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            emojiStatus = chat == null ? null : chat.emoji_status;
        }
        if (emojiStatus instanceof TLRPC.TL_emojiStatusCollectible) {
            giftIds.add(((TLRPC.TL_emojiStatusCollectible) emojiStatus).collectible_id);
        }
        list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);
        if (list != null) {
            for (int i = 0; i < list.gifts.size() && gifts.size() < maxCount; i++) {
                final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                if (!savedGift.unsaved && savedGift.pinned_to_top && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                    final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                    if (gift.documentId != 0 && !giftIds.contains(gift.id)) {
                        gifts.add(gift);
                        giftIds.add(gift.id);
                    }
                }
            }
        }

        if (draggedGiftID != 0 && !giftIds.contains(draggedGiftID)) {
            finishGesture();
            clearGrab();
        }

        boolean changed = false;
        if (gifts.size() != oldGifts.size()) {
            changed = true;
        } else for (int i = 0; i < gifts.size(); i++) {
            if (!gifts.get(i).equals(oldGifts.get(i))) {
                changed = true;
                break;
            }
        }

        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            Gift oldGift = null;
            for (int j = 0; j < oldGifts.size(); ++j) {
                if (oldGifts.get(j).id == g.id && oldGifts.get(j).documentId == g.documentId) {
                    oldGift = oldGifts.get(j);
                    break;
                }
            }
            if (oldGift == null) {
                for (int j = 0; j < exitingGifts.size(); ++j) {
                    final Gift e = exitingGifts.get(j);
                    if (e.id == g.id && e.documentId == g.documentId) {
                        oldGift = exitingGifts.remove(j);
                        break;
                    }
                }
            }

            if (oldGift != null) {
                g.copy(oldGift);
            } else {
                if (g.document != null) {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.document);
                } else {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.documentId);
                }
                g.animatedFloat = new AnimatedFloat(this, 0, 320, null);
                g.animatedFloat.force(ProfileGiftScene.animationsEnabled() ? 0.0f : 1.0f);
                g.positionX = new AnimatedFloat(this, 0, 310, CubicBezierInterpolator.EASE_OUT_QUINT);
                g.positionY = new AnimatedFloat(this, 0, 310, CubicBezierInterpolator.EASE_OUT_QUINT);
                if (isAttachedToWindow() && g.emojiDrawable != null) {
                    g.emojiDrawable.addView(this);
                }
            }
            if (oldGift != null && oldGift.color == g.color) {
                g.gradient = oldGift.gradient;
                g.gradientPaint = oldGift.gradientPaint;
            } else {
                g.gradient = new RadialGradient(0, 0, dp(22.5f), g.color, Theme.multAlpha(g.color, 0.0f), Shader.TileMode.CLAMP);
                g.gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                g.gradientPaint.setShader(g.gradient);
            }
        }

        for (int i = 0; i < oldGifts.size(); i++) {
            final Gift g = oldGifts.get(i);
            Gift newGift = null;
            for (int j = 0; j < gifts.size(); ++j) {
                if (gifts.get(j).id == g.id && gifts.get(j).documentId == g.documentId) {
                    newGift = gifts.get(j);
                    break;
                }
            }
            if (newGift != null) {
                continue;
            }
            if (isAttachedToWindow() && active && isShown() && !g.bounds.isEmpty() && ProfileGiftScene.animationsEnabled()) {
                // fades out on its place instead of disappearing
                if (exitingGifts.size() == 6) {
                    releaseGift(exitingGifts.remove(0));
                }
                g.exitStart = SystemClock.uptimeMillis();
                exitingGifts.add(g);
            } else {
                releaseGift(g);
            }
        }

        for (int i = 0; i < gifts.size(); ++i) {
            gifts.get(i).position = ORBIT_POSITIONS[i];
        }

        updateAmbientPalette();
        if (changed)
            invalidate();
    }

    private boolean drawableInUse(AnimatedEmojiDrawable drawable, Gift except) {
        for (int i = 0; i < gifts.size(); ++i) {
            final Gift o = gifts.get(i);
            if (o != except && o.emojiDrawable == drawable) return true;
        }
        for (int i = 0; i < exitingGifts.size(); ++i) {
            final Gift o = exitingGifts.get(i);
            if (o != except && o.emojiDrawable == drawable) return true;
        }
        return false;
    }

    private void releaseGift(Gift g) {
        // AnimatedEmojiDrawable.make() caches by document: another bubble of the same model shares it,
        // removing this view would detach (and blank) that bubble's artwork too
        if (g.emojiDrawable != null && !drawableInUse(g.emojiDrawable, g)) {
            g.emojiDrawable.removeView(this);
        }
        if (g.pattern != null) {
            g.pattern.detach();
        }
    }

    private void clearExitingGifts() {
        while (!exitingGifts.isEmpty()) {
            releaseGift(exitingGifts.remove(exitingGifts.size() - 1));
        }
    }

    private void updateAmbientPalette() {
        int color = 0, edge = 0;
        for (int i = 0; i < gifts.size(); ++i) {
            final Gift g = gifts.get(i);
            color = i == 0 ? g.color : ColorUtils.blendARGB(color, g.color, 1.0f / (i + 1));
            edge = i == 0 ? g.edgeColor : ColorUtils.blendARGB(edge, g.edgeColor, 1.0f / (i + 1));
        }
        if (color == paletteColor && edge == paletteEdge) {
            return;
        }
        paletteColor = color;
        paletteEdge = edge;
        previousPalette = ambientPalette;
        ambientPalette = gifts.isEmpty() ? null : new RadialGradient(0, 0, 1.0f,
            new int[] { Theme.multAlpha(color, 0.33f), Theme.multAlpha(edge, 0.16f), 0 },
            new float[] { 0.0f, 0.54f, 1.0f }, Shader.TileMode.CLAMP);
        paletteProgress.force(ProfileGiftScene.animationsEnabled() ? 0.0f : 1.0f);
        invalidate();
        ambientView.invalidate();
    }

    private float headerAlpha() {
        return Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));
    }

    // region ambient backdrop

    private void drawBehindAvatar(Canvas canvas) {
        if (classic) {
            return;
        }
        if (!isShown() || expandProgress >= 1.0f || collapseProgress <= 0.0f || maxExpandY <= 0.0f) {
            return;
        }
        final float sz = dpf2(96);
        final float aw = Math.max(avatarContainer.getScaleX() * avatarContainer.getWidth(), sz);
        final float ah = Math.max(avatarContainer.getScaleY() * avatarContainer.getHeight(), sz);
        final float acx = aw / 2.0f + Math.min(avatarContainer.getX(), (getWidth() - sz) / 2.0f);
        final float acy = ah / 2.0f + Math.max(avatarContainer.getY(), (maxExpandY - sz) / 2.0f);
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), expandY);
        drawAmbient(canvas, acx, acy, Math.min(aw, ah) * 0.5f, (1.0f - actionBarProgress) * (1.0f - expandProgress) * headerAlpha(), SystemClock.uptimeMillis());
        canvas.restore();
    }

    private void drawAmbient(Canvas canvas, float cx, float cy, float radius, float alpha, long now) {
        final float p = paletteProgress.set(1.0f);
        if (p >= 1.0f) {
            previousPalette = null;
        }
        final float size = Math.max(dp(72), getWidth() * 0.56f);
        ambientMatrix.setScale(size, size);
        ambientMatrix.postTranslate(cx, cy);
        drawAmbientLayer(canvas, previousPalette, (1.0f - p) * alpha);
        drawAmbientLayer(canvas, ambientPalette, p * alpha);

        if (musicOpacity > 0.008f) {
            final float change = CubicBezierInterpolator.DEFAULT.getInterpolation(Utilities.clamp01((now - backdropChangedAt) / (float) BEAT_GLOW_MS));
            final float mix = previousMusicGiftId == musicGiftId ? 1.0f : change;
            final float beat = beatProgress(now);
            for (int i = 0; i < gifts.size(); ++i) {
                final Gift g = gifts.get(i);
                if (g.id == previousMusicGiftId && mix < 1.0f) {
                    drawMusicBackdrop(canvas, g, cx, cy, radius, (1.0f - mix) * (musicOpacity * alpha), beat);
                }
                if (g.id == musicGiftId) {
                    drawMusicBackdrop(canvas, g, cx, cy, radius, musicOpacity * alpha * mix, beat);
                }
            }
        }
    }

    private void drawAmbientLayer(Canvas canvas, RadialGradient shader, float alpha) {
        if (shader == null || alpha <= 0.0f) {
            return;
        }
        shader.setLocalMatrix(ambientMatrix);
        ambientPaint.setShader(shader);
        ambientPaint.setAlpha(Math.round(alpha * 255.0f));
        canvas.drawRect(0, 0, getWidth(), expandY, ambientPaint);
    }

    /** The gift's backdrop over the whole header, breathing with the bass, with a ripple on every kick. */
    private void drawMusicBackdrop(Canvas canvas, Gift gift, float cx, float cy, float radius, float alpha, float beat) {
        if (alpha <= 0.008f) {
            return;
        }
        if (gift.musicGradient == null) {
            final Shader.TileMode clamp = Shader.TileMode.CLAMP;
            gift.musicGradient = new RadialGradient(0, 0, 1.0f,
                new int[] { gift.color, gift.edgeColor, Theme.multAlpha(gift.edgeColor, 0.42f) },
                new float[] { 0.0f, 0.58f, 1.0f }, clamp);
            final int light = ColorUtils.blendARGB(gift.color, 0xFFFFFFFF, 0.3f);
            gift.musicHalo = new RadialGradient(0, 0, 1.0f,
                new int[] { 0, Theme.multAlpha(light, 0.76f), Theme.multAlpha(gift.color, 0.28f), 0 },
                new float[] { 0.35f, 0.55f, 0.75f, 1.0f }, clamp);
            gift.musicRipple = new RadialGradient(0, 0, 1.0f,
                new int[] { 0, Theme.multAlpha(gift.color, 0.38f), Theme.multAlpha(light, 0.85f), 0 },
                new float[] { 0.68f, 0.84f, 0.9f, 1.0f }, clamp);
        }
        final float fill = (musicBass * 0.21f + 0.76f) * Math.max(getWidth(), expandY);
        ambientMatrix.setScale(fill, fill * 0.83f);
        ambientMatrix.postTranslate(cx, cy);
        drawAmbientLayer(canvas, gift.musicGradient, (musicBass * 0.18f + 0.65f) * alpha);

        final float halo = (musicBass * 0.19f + 1.92f) * radius;
        ambientMatrix.setScale(halo, halo);
        ambientMatrix.postTranslate(cx, cy);
        drawAmbientLayer(canvas, gift.musicHalo, (musicKick * 0.28f + (musicBass * 0.42f + 0.24f)) * alpha);

        if (musicKick > 0.015f) {
            final float ripple = ((2.0f - beat) * beat * 0.88f + 1.16f) * radius;
            ambientMatrix.setScale(ripple, ripple);
            ambientMatrix.postTranslate(cx, cy);
            drawAmbientLayer(canvas, gift.musicRipple, 0.82f * alpha * musicKick);
        }

        if (gift.symbol == null) {
            return;
        }
        ensurePattern(gift);
        StarGiftPatterns.drawProfileAnimatedPattern(canvas, gift.pattern, getWidth(), maxExpandY, collapseProgress, avatarContainer, (musicBass * 0.17f + 0.83f) * alpha);
    }

    private void ensurePattern(Gift gift) {
        if (gift.pattern != null) {
            return;
        }
        gift.pattern = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(ambientView, dp(22), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC_WITH_THUMB);
        gift.pattern.setCurrentAccount(currentAccount);
        gift.pattern.setColor(gift.glassPatternColor);
        gift.pattern.set(gift.symbol, false);
        if (isAttachedToWindow()) {
            gift.pattern.attach();
        }
    }

    /** The gift's pattern symbol scattered inside the glass bubble. */
    public void drawGlassPattern(Gift gift, Canvas canvas, ProfileGiftGlass glass, float radius, float wobble, float stretch, float alpha) {
        if (gift.symbol == null || radius < 1.0f || alpha <= 0.0f) {
            return;
        }
        ensurePattern(gift);
        canvas.save();
        glass.clip(canvas, radius, wobble, stretch);
        final float w = glass.widthScale(wobble, stretch) * radius;
        final float h = glass.heightScale(wobble, stretch) * radius;
        ImageReceiver tinted = null;
        if (gift.pattern.getDrawable() instanceof AnimatedEmojiDrawable) {
            final AnimatedEmojiDrawable drawable = (AnimatedEmojiDrawable) gift.pattern.getDrawable();
            if (!drawable.canOverrideColor()) {
                tinted = drawable.getImageReceiver();
                if (tinted != null) {
                    tinted.setColorFilter(gift.glassPatternTint);
                }
            }
        }
        for (int i = 0; i < 8; ++i) {
            final double a = wobble * 0.19d + (i + 0.38d) * Math.PI / 4.0d;
            final float k = (i % 2) * 0.07f + 0.75f;
            final float x = (float) Math.cos(a) * w * k;
            final float y = (float) Math.sin(a) * h * k;
            final float m = i % 3;
            final float half = (m * 0.02f + 0.074f) * radius;
            gift.pattern.setBounds(Math.round(x - half), Math.round(y - half), Math.round(x + half), Math.round(y + half));
            gift.pattern.setAlpha(Math.round((m * 0.12f + 0.63f) * (255.0f * alpha)));
            gift.pattern.draw(canvas);
        }
        if (tinted != null) {
            tinted.setColorFilter(null);
        }
        canvas.restore();
    }

    // endregion

    // region music pulse

    private float beatProgress(long now) {
        if (beatStartedAt == 0) {
            return 1.0f;
        }
        return Utilities.clamp01((now - beatStartedAt) / (float) BEAT_GLOW_MS);
    }

    public float giftMusicPulse() {
        return Utilities.clamp01(musicKick * 0.75f + musicBass * 0.65f);
    }

    /** @return whether the music backdrop is (still) visible */
    private boolean updateMusicPulse(long now) {
        final MediaController mediaController = MediaController.getInstance();
        final MessageObject playing = mediaController.getPlayingMessageObject();
        final boolean playingMusic = animateScene && !classic && playing != null && playing.currentAccount == currentAccount && playing.isMusic() && !mediaController.isMessagePaused() && !gifts.isEmpty();
        final ProfileMusicPulse.Frame frame = ProfileMusicPulse.latest();
        if (playing != musicTrack) {
            musicTrack = playing;
            beatStartedAt = 0;
            lastBeat = 0;
            beatStrength = 0;
            musicGiftId = 0;
            previousMusicGiftId = 0;
        }
        final boolean fresh = playingMusic && now - frame.at < 140;
        final float bass = fresh ? frame.bass : 0.0f;
        final long dt = musicFrameAt == 0 ? 16 : Math.min(64, Math.max(0, now - musicFrameAt));
        musicFrameAt = now;
        musicBass = lerp(musicBass, bass, 1.0f - (float) Math.exp(-dt / (bass > musicBass ? 40.0d : 145.0d)));
        if (fresh && frame.beat != 0 && frame.beat != lastBeat) {
            lastBeat = frame.beat;
            beatStartedAt = now;
            beatStrength = frame.kick;
            if (now - backdropChangedAt >= BEAT_GLOW_MS || musicGiftId == 0) {
                // every beat switches the header to the next gift's backdrop
                previousMusicGiftId = musicGiftId;
                int index = -1;
                for (int i = 0; i < gifts.size(); ++i) {
                    if (gifts.get(i).id == musicGiftId) {
                        index = i;
                    }
                }
                musicGiftId = gifts.get((index + 1) % gifts.size()).id;
                backdropChangedAt = now;
            }
        }
        if (playingMusic && musicGiftId == 0) {
            musicGiftId = gifts.get(0).id;
        }
        if (!playingMusic) {
            beatStartedAt = 0;
        }
        final float fade = 1.0f - beatProgress(now);
        musicKick = beatStrength * fade * fade;
        musicOpacity = lerp(musicOpacity, playingMusic ? 1.0f : 0.0f, 1.0f - (float) Math.exp(-dt / (playingMusic ? 105.0d : 82.0d)));
        return playingMusic || musicOpacity > 0.008f;
    }

    // endregion

    // region liquid glass

    /**
     * Render node ProfileActivity records the header into (everything but the bubbles) so the bubbles can
     * refract it; null when the bubbles are not shown or liquid glass is off.
     */
    public GlassSourceRenderNode getGiftGlassSource(Canvas canvas) {
        if (classic || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated() || !active || gifts.isEmpty() || expandProgress >= 1.0f || collapseProgress <= 0.0f || !LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            return null;
        }
        if (giftGlassSource == null) {
            giftGlassSource = new GlassSourceRenderNode(null);
        }
        return giftGlassSource;
    }

    // endregion

    private void updateAvatarOcclusion(float x, float y, float w, float h) {
        avatarOcclusionPath.rewind();
        avatarOcclusion.setEmpty();
        if (!avatarImage.drawAvatar || !avatarImage.isShown() || w <= 0 || h <= 0) {
            return;
        }
        avatarOcclusion.set(x, y, x + w, y + h);
        avatarOcclusionRadius = Math.min(Math.min(w, h) * 0.5f, avatarContainer.getScaleX() * avatarImage.getRoundRadius()[0]);
        avatarOcclusionPath.addRoundRect(avatarOcclusion, avatarOcclusionRadius, avatarOcclusionRadius, Path.Direction.CW);
    }

    private boolean avatarCovers(float x, float y) {
        if (!avatarOcclusion.contains(x, y)) {
            return false;
        }
        final float r = avatarOcclusionRadius;
        final float dx = x - Math.max(avatarOcclusion.left + r, Math.min(avatarOcclusion.right - r, x));
        final float dy = y - Math.max(avatarOcclusion.top + r, Math.min(avatarOcclusion.bottom - r, y));
        return dy * dy + dx * dx <= r * r;
    }

    private double orbitSpin(long now) {
        if (spinReleasedAt == 0) {
            return spinOffset;
        }
        return (1.0d - Math.exp(-(now - spinReleasedAt) / 620.0d)) * (spinVelocity * 0.62d) + spinOffset;
    }

    private float grabProgress(long now) {
        return CubicBezierInterpolator.DEFAULT.getInterpolation(Utilities.clamp01((now - grabStarted) / 210.0f));
    }

    private final TimeInterpolator giftCollapseXInterpolator = new DecelerateInterpolator();
    private final TimeInterpolator giftCollapseYInterpolator = new LinearInterpolator();

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        animateScene = active && ProfileGiftScene.animationsEnabled() && isShown() && getWindowVisibility() == View.VISIBLE;
        if (!animateScene || expandProgress >= 1.0f || collapseProgress <= 0f) {
            clearExitingGifts();
        }
        if (expandProgress >= 1.0f || collapseProgress <= 0f || maxExpandY <= 0) return;

        final long now = SystemClock.uptimeMillis();
        final double t = animateScene ? (now - orbitStart) / 1000.0d : 0.0d;
        final boolean music = updateMusicPulse(now);
        if (music || previousPalette != null) {
            ambientView.postInvalidateOnAnimation();
        }

        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = (avatarContainer.getWidth()) * avatarContainer.getScaleX();
        final float realH = (avatarContainer.getHeight()) * avatarContainer.getScaleY();
        updateAvatarOcclusion(realX, realY, realW, realH);

        final float sz = dpf2(96);
        final float ax = Math.min(realX, (getWidth() - sz) / 2f);
        final float ay = Math.max(realY, (maxExpandY - sz) / 2f);
        final float aw = Math.max(realW, sz);
        final float ah = Math.max(realH, sz);

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), headerLimit());

        final float acx = ax + aw / 2.0f;
        final float acy = ay + ah / 2.0f;

        final float realCX = realX + realW / 2.0f;
        final float realCY = realY + realH / 2.0f;

        final float expandScale = expandY / maxExpandY;
        final float closedAlpha = headerAlpha();

        for (int i = exitingGifts.size() - 1; i >= 0; --i) {
            final Gift gift = exitingGifts.get(i);
            final float p = Utilities.clamp01((now - gift.exitStart) / 240.0f);
            if (p >= 1.0f) {
                releaseGift(exitingGifts.remove(i));
            } else {
                gift.draw(canvas, gift.bounds.centerX(), gift.bounds.centerY(), 1.0f - 0.25f * p, 0, (1.0f - p) * closedAlpha, 1.0f);
            }
        }

        // the held gift is drawn last, above everything and outside of the header clip
        Gift held = null;
        float heldX = 0, heldY = 0, heldScale = 1.0f, heldTilt = 0, heldAlpha = 1.0f, heldStretch = 0;

        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat.set(1.0f);
            float scale = lerp(0.5f, 1.0f, enter);
            final float alpha = (1.0f - actionBarProgress) * ((1.0f - expandProgress) * enter) * closedAlpha;

            float gx, gy;
            float delayValue;

            switch (gift.position) {
                case 0: // left
                    gx = acx / 2f - (dp(20) * expandScale);
                    gy = acy - dp(13);
                    delayValue = 1.6f;
                    break;
                case 1: // top left
                    gx = acx * 2f / 3f - (dp(6) * expandScale);
                    gy = ay - dp(4);
                    delayValue = 0;
                    break;
                case 2: // bottom left
                    gx = acx * 2f / 3f - (dp(12) * expandScale);
                    gy = ay + ah - dp(16);
                    delayValue = 0.9f;
                    break;
                case 3: // right
                    gx = acx * 1.5f + (dp(20) * expandScale);
                    gy = acy - dp(13);
                    delayValue = 1.6f;
                    break;
                case 4: // top right
                    gx = acx * 4f / 3f + (dp(12) * expandScale);
                    gy = ay - dp(4);
                    delayValue = 0.9f;
                    break;
                default: // bottom right
                    gx = acx * 4f / 3f + (dp(12) * expandScale);
                    gy = ay + ah - dp(16);
                    delayValue = 0;
                    break;
            }

            final float collapseProgressWithEnter;
            if (isOpening || enter >= 1f) {
                collapseProgressWithEnter = collapseProgress;
            } else {
                collapseProgressWithEnter = Math.min(enter, collapseProgress);
            }

            final float delayFraction = 0.2f;
            final float maxDelayFraction = 1.6f * delayFraction;
            final float intervalFraction = 1f - maxDelayFraction;

            float delay = delayValue * delayFraction;
            float collapse = collapseProgressWithEnter >= 1f - delay ? 1f
                    : Utilities.clamp01((collapseProgressWithEnter - maxDelayFraction + delay) / intervalFraction);

            if (classic || !gift.laidOut || !animateScene || collapse < 1f) {
                gift.positionX.force(gx);
                gift.positionY.force(gy);
                gift.laidOut = true;
            } else {
                gx = gift.positionX.set(gx);
                gy = gift.positionY.set(gy);
            }

            if (collapse < 1f) {
                gx = AndroidUtilities.lerp(realCX, gx, giftCollapseXInterpolator.getInterpolation(collapse));
                gy = AndroidUtilities.lerp(realCY, gy, giftCollapseYInterpolator.getInterpolation(collapse));
                scale = AndroidUtilities.lerp(scale / 2f, scale, collapse);
            }

            gift.behindAvatar = false;
            float tilt = 0.0f;
            if (animateScene && !classic) {
                // orbit on a tilted ellipse: the upper half is the far side, smaller and behind the avatar
                final double phase = (gift.id & 127) * 0.31d;
                final double wave = ((gift.id & 3) * 0.075d + 0.37d) * t + phase;
                final double angle = Math.sin(wave) * 0.28d + (0.34d * t + gift.position * Math.PI / 3.0d + orbitSpin(now));
                final double bob = ((gift.id & 3) * 0.12d + 1.1d) * t + phase;
                final float depth = (float) Math.sin(angle);
                gift.behindAvatar = depth < 0 && collapse > 0.8f;
                final float rx = ((float) Math.sin(wave + angle) * 0.08f + 0.85f) * Math.min(Math.max(0, acx - dp(33)), getWidth() * 0.32f);
                final float ry = dp((int) (gift.id & 3) * 5 + 31);
                final float ox = (float) Math.sin(2.0d * angle + phase) * dp(11) + ((float) Math.cos(angle) * rx + acx);
                final float oy = (float) Math.sin(bob) * dp(7) + (ry * depth + (acy - dp(4)));
                gx = lerp(gx, ox, collapse);
                gy = lerp(gy, oy, collapse);
                scale = (depth * 0.12f + 0.9f) * scale;
                tilt = (float) Math.sin(bob) * 9.0f;
            }

            if (gift.id == draggedGiftID) {
                gift.behindAvatar = false;
                final float back = returnStarted == 0 ? 0.0f : Utilities.clamp01((now - returnStarted) / 480.0f);
                if (back < 1.0f) {
                    final float e = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(back);
                    final float kick = 3.0f * back * (1.0f - back) * (1.0f - back);
                    final float g = grabProgress(now);
                    if (returnStarted == 0) {
                        heldX = lerp(grabOriginX, dragX, g);
                        heldY = lerp(grabOriginY, dragY, g);
                        // inflates only as it gets away from the avatar
                        final float away = (float) Math.hypot(heldX - realCX, heldY - realCY) - Math.min(realW, realH) / 2.0f;
                        final float grow = g * Utilities.clamp01(away / dp(HELD_GROW_DISTANCE_DP));
                        heldStretch = grow;
                        heldScale = lerp(grabOriginScale, HELD_GIFT_SCALE, grow);
                        heldTilt = lerp(grabOriginTilt, 0.0f, g);
                    } else {
                        // flies back on an arc, carrying a bit of the release velocity
                        heldX = returnVelocityX * kick + lerp(dragX, gx, e);
                        heldY = returnVelocityY * kick + lerp(dragY, gy, e) - (float) Math.sin(e * Math.PI) * dp(27);
                        heldScale = lerp(releaseScale, scale, e);
                        heldStretch = (1.0f - e) * releaseStretch;
                        heldTilt = lerp(releaseTilt, tilt, e);
                    }
                    held = gift;
                    heldAlpha = alpha;
                    continue;
                }
                clearGrab();
            }

            final float drawAlpha = gift.id == sceneGiftID ? 0.0f : alpha;
            if (gift.behindAvatar && !avatarOcclusion.isEmpty()) {
                canvas.save();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas.clipOutPath(avatarOcclusionPath);
                } else {
                    canvas.clipPath(avatarOcclusionPath, Region.Op.DIFFERENCE);
                }
                gift.draw(canvas, gx, gy, scale, tilt, drawAlpha, 1f);
                canvas.restore();
            } else {
                gift.draw(canvas, gx, gy, scale, tilt, drawAlpha, 1f);
            }
        }

        canvas.restore();

        heldDrawn = false;
        if (held != null) {
            // the held bubble (and its flight back) stays inside the visible header: below the toolbar,
            // above the buttons, inside the sides; where it has no room it inflates less (never below its orbit size)
            final float margin = dp(HELD_MARGIN_DP);
            final float radius = dp(20);
            final float heightBound = 1.06f + 0.48f * heldStretch;
            final float widthBound = 1.08f;
            final float top = Math.min(headerTop(), headerLimit());
            final float limit = headerLimit();
            final float maxHalfH = Math.max(0.0f, (limit - top - 2.0f * margin) / 2.0f);
            final float maxHalfW = getWidth() / 2.0f - margin;
            final float fitScale = Math.min(maxHalfH / (radius * heightBound), maxHalfW / (radius * widthBound));
            if (heldScale > fitScale) {
                heldScale = Math.max(fitScale, Math.min(heldScale, grabOriginScale));
            }
            final float halfH = radius * heldScale * heightBound;
            final float halfW = radius * heldScale * widthBound;
            heldX = Utilities.clamp(heldX, getWidth() - margin - halfW, margin + halfW);
            heldY = Utilities.clamp(heldY, limit - margin - halfH, top + margin + halfH);
            heldDrawnX = heldX;
            heldDrawnY = heldY;
            heldDrawnScale = heldScale;
            heldDrawnStretch = heldStretch;
            heldDrawn = true;
            canvas.save();
            // the bubble is already fitted above the buttons; only its glow halo may spill past the header bottom
            canvas.clipRect(0, 0, getWidth(), getHeight());
            held.draw(canvas, heldX, heldY, heldScale, heldTilt, heldAlpha, 1.0f, heldStretch);
            canvas.restore();
            if (returnStarted != 0 || now - grabStarted < 210) {
                invalidateFrame();
                return;
            }
        }
        if (animateScene && (music || !gifts.isEmpty() ? closedAlpha > 0.0f : !exitingGifts.isEmpty())) {
            invalidateFrame();
        }
    }

    public Gift getGiftUnder(float x, float y) {
        if (!active || expandProgress >= 1.0f || collapseProgress <= 0.0f || y < 0 || y > expandY) {
            return null;
        }
        Gift behind = null;
        for (int i = gifts.size() - 1; i >= 0; --i) {
            final Gift g = gifts.get(i);
            if (!g.bounds.contains(x, y) || g.id == sceneGiftID) {
                continue;
            }
            if (!g.behindAvatar) {
                return g;
            }
            if (behind == null) {
                behind = g;
            }
        }
        return behind;
    }

    private void beginGrab(float x, float y) {
        removeCallbacks(holdGift);
        pressedGift.bounce.setPressed(false);
        clearGrab();
        grabStarted = SystemClock.uptimeMillis();
        draggedGiftID = pressedGift.id;
        grabOriginX = pressedGift.bounds.centerX();
        grabOriginY = pressedGift.bounds.centerY();
        grabOriginScale = pressedGift.drawnScale;
        grabOriginTilt = pressedGift.drawnTilt;
        TLRPC.Document doc = pressedGift.document;
        if (doc == null && pressedGift.emojiDrawable != null) {
            doc = pressedGift.emojiDrawable.getDocument();
        }
        if (doc != null) {
            if (heldArtworkReceiver == null) {
                heldArtworkReceiver = new ImageReceiver(this);
            }
            heldArtwork = heldArtworkReceiver;
            heldArtwork.setCurrentAccount(currentAccount);
            heldArtwork.setAspectFit(true);
            heldArtwork.setAllowStartAnimation(animateScene);
            heldArtwork.setAllowDecodeSingleFrame(true);
            heldArtwork.onAttachedToWindow();
            heldArtwork.setImage(ImageLocation.getForDocument(doc), "256_256_nolimit", null, null, doc, 1);
        }
        dragX = x + grabOffsetX;
        dragY = y + grabOffsetY;
        invalidate();
    }

    private void startReturn(boolean fling) {
        returnStarted = SystemClock.uptimeMillis();
        releaseStretch = grabProgress(returnStarted);
        releaseScale = lerp(grabOriginScale, HELD_GIFT_SCALE, releaseStretch);
        releaseTilt = lerp(grabOriginTilt, 0.0f, releaseStretch);
        dragX = lerp(grabOriginX, dragX, releaseStretch);
        dragY = lerp(grabOriginY, dragY, releaseStretch);
        if (heldDrawn) {
            // it may have been fitted into the header: fly back from where it was seen
            releaseStretch = heldDrawnStretch;
            releaseScale = heldDrawnScale;
            dragX = heldDrawnX;
            dragY = heldDrawnY;
        }
        if (fling && giftVelocity != null) {
            giftVelocity.computeCurrentVelocity(1000, dp(2600));
            final float vx = giftVelocity.getXVelocity();
            final float vy = giftVelocity.getYVelocity();
            returnVelocityX = vx * 0.085f;
            returnVelocityY = 0.085f * vy;
            if (animateScene && Math.abs(vx) > Math.max(dp(340), Math.abs(vy) * 1.4f)) {
                // a sideways throw also spins the whole orbit
                spinOffset = orbitSpin(returnStarted) % (2.0d * Math.PI);
                spinVelocity = vx / dp(160);
                spinReleasedAt = returnStarted;
            }
        }
        invalidate();
    }

    private void clearGrab() {
        returnStarted = 0;
        draggedGiftID = 0;
        returnVelocityY = 0.0f;
        returnVelocityX = 0.0f;
        if (heldArtwork != null) {
            heldArtwork.onDetachedFromWindow();
            heldArtwork.clearImage();
            heldArtwork = null;
        }
    }

    private void finishGesture() {
        removeCallbacks(holdGift);
        if (pressedGift != null) {
            pressedGift.bounce.setPressed(false);
            pressedGift = null;
        }
        if (giftVelocity != null) {
            giftVelocity.recycle();
            giftVelocity = null;
        }
        if (giftGesture && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        giftGesture = false;
        spinning = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            finishGesture();
            pressedGift = getGiftUnder(event.getX(), event.getY());
            if (pressedGift == null) {
                // no bubble under the finger: a swipe in the orbit band (beside the avatar) spins the gifts
                if (classic || !animateScene || gifts.isEmpty() || avatarOcclusion.isEmpty() || avatarCovers(event.getX(), event.getY()) || Math.abs(event.getY() - avatarOcclusion.centerY()) > dp(68)) {
                    return false;
                }
            }
            giftGesture = true;
            touchDownX = event.getX();
            touchDownY = event.getY();
            giftVelocity = VelocityTracker.obtain();
            if (pressedGift != null) {
                grabOffsetX = pressedGift.bounds.centerX() - touchDownX;
                grabOffsetY = pressedGift.bounds.centerY() - touchDownY;
                pressedGift.bounce.setPressed(true);
                if (!classic) {
                    postDelayed(holdGift, ViewConfiguration.getLongPressTimeout());
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
        }
        if (!giftGesture) {
            return false;
        }
        if (giftVelocity != null) {
            giftVelocity.addMovement(event);
        }
        final Gift pressed = pressedGift;
        final boolean holding = pressed != null && draggedGiftID == pressed.id && returnStarted == 0;
        if (action == MotionEvent.ACTION_MOVE && pressed != null) {
            if (holding) {
                dragX = event.getX() + grabOffsetX;
                dragY = event.getY() + grabOffsetY;
                invalidate();
            } else if (Math.hypot(event.getX() - touchDownX, event.getY() - touchDownY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                if (classic) {
                    finishGesture();
                    return false;
                }
                beginGrab(event.getX(), event.getY());
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            final float dx = event.getX() - touchDownX;
            if (!spinning && Math.abs(dx) > ViewConfiguration.get(getContext()).getScaledTouchSlop() && Math.abs(dx) > Math.abs(event.getY() - touchDownY)) {
                spinning = true;
                spinOffset = orbitSpin(SystemClock.uptimeMillis()) % (2.0d * Math.PI);
                spinReleasedAt = 0;
                lastSpinX = touchDownX;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (spinning) {
                spinOffset += (event.getX() - lastSpinX) / dp(68);
                lastSpinX = event.getX();
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            final Gift clicked = action == MotionEvent.ACTION_UP && !holding ? pressed : null;
            if (holding) {
                startReturn(action == MotionEvent.ACTION_UP);
            }
            if (spinning && action == MotionEvent.ACTION_UP && giftVelocity != null) {
                giftVelocity.computeCurrentVelocity(1000, dp(1400));
                spinVelocity = giftVelocity.getXVelocity() / dp(68);
                spinReleasedAt = SystemClock.uptimeMillis();
            }
            finishGesture();
            if (clicked != null) {
                onGiftClick(clicked);
            }
        }
        return true;
    }

    public void onGiftClick(Gift gift) {
        if (classic) {
            openGiftLink(getContext(), gift);
            return;
        }
        if (sceneGiftID != 0) {
            return;
        }
        sceneGiftID = gift.id;
        final boolean shown = ProfileGiftScene.show(this, gift, () -> {
            sceneGiftID = 0;
            invalidate();
        });
        if (!shown) {
            sceneGiftID = 0;
        }
        invalidate();
        if (!shown) {
            openGiftLink(getContext(), gift);
        }
    }

    static void openGiftLink(Context context, Gift gift) {
        if (TextUtils.isEmpty(gift.slug)) {
            return;
        }
        Browser.openUrl(context, "https://staticgram.top/nft/" + Uri.encode(gift.slug));
    }
}
