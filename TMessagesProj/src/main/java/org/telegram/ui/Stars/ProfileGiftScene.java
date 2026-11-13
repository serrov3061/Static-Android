package org.telegram.ui.Stars;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Full-screen scene shown after a tap on a gift bubble around the profile avatar: the bubble flies from its
 * place to the middle of the screen and inflates, the gift is shown large inside it over a dimmed profile,
 * with an "Open" button at the bottom. Tap on the bubble tilts it, drag or tap outside closes the scene.
 */
final class ProfileGiftScene extends View {

    private final ProfileGiftsView source;
    private final ProfileGiftsView.Gift gift;
    private final ProfileGiftGlass glass;
    private final ImageReceiver document = new ImageReceiver(this);
    private final Paint particle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] screen = new int[2];
    private final long start = SystemClock.uptimeMillis();
    private final boolean animate;

    private Dialog dialog;
    private View details;
    private ValueAnimator animator;
    private float progress;
    private boolean closing;

    private float centerX, centerY, radius;
    // the part of the window not covered by the status / navigation bars
    private float visibleTop, visibleBottom;
    private static final float BUTTON_GAP_DP = 24;
    private float downX, downY;
    private float offsetX, offsetY;
    private boolean inside, dragged;
    private float tapTilt;

    public static boolean animationsEnabled() {
        if (!LiteMode.isEnabled(LiteMode.FLAG_CHAT_BACKGROUND)) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return ValueAnimator.areAnimatorsEnabled();
    }

    private ProfileGiftScene(Context context, ProfileGiftsView source, ProfileGiftsView.Gift gift) {
        super(context);
        this.source = source;
        this.gift = gift;
        this.animate = animationsEnabled();
        this.glass = source.obtainGlass(gift.color, gift.edgeColor);
        final TLRPC.Document doc = gift.document != null ? gift.document : gift.emojiDrawable.getDocument();
        if (doc != null) {
            document.setCurrentAccount(source.getGiftAccount());
            document.setAspectFit(true);
            document.setAllowStartAnimation(animate);
            document.setImage(ImageLocation.getForDocument(doc), "256_256_nolimit", null, null, doc, 1);
        }
        setFocusable(true);
        setClickable(true);
    }

    public static boolean show(ProfileGiftsView source, ProfileGiftsView.Gift gift, Runnable onDismiss) {
        if (gift.emojiDrawable == null || gift.documentId == 0 || !source.isAttachedToWindow()) {
            return false;
        }
        final Context context = source.getContext();
        final ProfileGiftScene scene = new ProfileGiftScene(context, source, gift);
        final Dialog dialog = new Dialog(context, R.style.TransparentDialogNoAnimation) {
            @Override
            public void onBackPressed() {
                scene.close();
            }
        };
        scene.dialog = dialog;
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout container = new FrameLayout(context);
        container.addView(scene, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!TextUtils.isEmpty(gift.slug)) {
            final TextView button = new TextView(context);
            button.setGravity(Gravity.CENTER);
            button.setText(LocaleController.getString(R.string.Open));
            button.setTextSize(16);
            button.setTextColor(0xFFFFFFFF);
            button.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
            button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), 0x402B3955));
            button.setOnClickListener(v -> {
                dialog.dismiss();
                ProfileGiftsView.openGiftLink(context, gift);
            });
            // placed right under the bubble by the scene (see layoutDetails)
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            button.setAlpha(0.0f);
            container.addView(button, lp);
            scene.details = button;
        }
        container.setFitsSystemWindows(false);
        container.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        dialog.setContentView(container);
        dialog.setCanceledOnTouchOutside(false);

        final Window window = dialog.getWindow();
        if (window == null) {
            return false;
        }
        // a window over the whole screen (status and navigation bars too): the dim is even everywhere,
        // the bubble is centered in the visible part of the screen, whatever profile and scroll it came from
        AndroidUtilities.enableEdgeToEdge(window);
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        final WindowManager.LayoutParams params = window.getAttributes();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.dimAmount = 0;
        AndroidUtilities.applyEdgeToEdgeLayoutParams(params);
        window.setAttributes(params);
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException e) {
            return false;
        }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scene.transition(1.0f, null);
        return true;
    }

    private void close() {
        if (closing) {
            return;
        }
        closing = true;
        transition(0.0f, dialog::dismiss);
    }

    private void transition(float to, Runnable end) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
        if (!animate) {
            progress = to;
            invalidate();
            if (end != null) {
                end.run();
            }
            return;
        }
        animator = ValueAnimator.ofFloat(progress, to);
        animator.setDuration(to == 1.0f ? 420 : 300);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            if (details != null) {
                details.setAlpha(progress);
            }
            invalidate();
        });
        if (end != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    end.run();
                }
            });
        }
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        document.onAttachedToWindow();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int insetTop = AndroidUtilities.statusBarHeight;
        int insetBottom = AndroidUtilities.navigationBarHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final android.view.WindowInsets insets = getRootWindowInsets();
            if (insets != null) {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
            }
        }
        visibleTop = insetTop;
        visibleBottom = Math.max(insetTop + 1, getHeight() - insetBottom);
        layoutDetails();
    }

    private float targetRadius() {
        return Math.min(getWidth() * 0.34f, (visibleBottom - visibleTop) * 0.22f);
    }

    private float targetCenterY() {
        return (visibleTop + visibleBottom) / 2.0f;
    }

    /** The "Open" button right under the inflated bubble. */
    private void layoutDetails() {
        if (details == null || getHeight() == 0) {
            return;
        }
        final float bubbleBottom = targetCenterY() + targetRadius() * glass.heightScale(-1.0f, 0.0f);
        final float y = Math.min(bubbleBottom + AndroidUtilities.dp(BUTTON_GAP_DP), visibleBottom - AndroidUtilities.dp(16 + 44));
        // the bubble can be dragged around (letting go closes the scene): the button stays right under it
        details.setTranslationX(offsetX);
        details.setTranslationY(y + offsetY);
    }

    @Override
    protected void onDetachedFromWindow() {
        document.onDetachedFromWindow();
        document.clearImage();
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    /** Sparkles orbiting the big bubble; {@code front} selects the half that passes in front of the gift. */
    private void drawParticles(Canvas canvas, float t, boolean front) {
        if (!animate) {
            return;
        }
        for (int i = 0; i < 14; ++i) {
            final double a = 2.39996d * i + (0.032d * i + 0.65d) * t;
            final float depth = (float) Math.sin(a);
            if ((depth >= 0) != front) {
                continue;
            }
            final float x = ((i % 3) * 0.07f + 0.74f) * ((float) Math.cos(a) * radius);
            final float y = (float) Math.sin(i * 0.52d + a) * radius * 0.62f;
            particle.setColor(0xFFFFFFFF);
            particle.setAlpha((int) ((Math.abs(depth) * 135.0f + 75.0f) * progress));
            canvas.drawCircle(x, y, AndroidUtilities.dp(i % 4 == 0 ? 2.0f : 1.0f), particle);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0.0f || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        getLocationOnScreen(screen);
        final int sx = screen[0], sy = screen[1];
        source.getLocationOnScreen(screen);
        final float fromX = gift.bounds.centerX() + screen[0] - sx;
        final float fromY = gift.bounds.centerY() + screen[1] - sy;
        radius = AndroidUtilities.lerp(AndroidUtilities.dp(20) * gift.getDrawnScale(), targetRadius(), progress);
        centerX = AndroidUtilities.lerp(fromX, getWidth() * 0.5f + offsetX, progress);
        centerY = AndroidUtilities.lerp(fromY, targetCenterY() + offsetY, progress);
        final float t = animate ? (SystemClock.uptimeMillis() - start) / 1000.0f : 0.0f;

        canvas.drawColor(Color.argb(Math.round(progress * 150.0f), 9, 12, 24));
        canvas.save();
        canvas.translate(centerX, centerY);
        final float wobble = (float) Math.sin(1.6f * t);
        final float pulse = source.giftMusicPulse();
        glass.drawGlow(canvas, radius, progress, wobble, pulse, 0.0f);
        glass.draw(canvas, radius, progress, wobble, pulse, false);
        source.drawGlassPattern(gift, canvas, glass, radius, wobble, 0.0f, progress);
        drawParticles(canvas, t, false);

        canvas.save();
        canvas.rotate((float) Math.sin(1.45f * t) * 8.0f + tapTilt);
        final float s = (float) Math.sin(t * 2.0f) * 0.035f + 1.0f;
        canvas.scale(s, s);
        final float half = radius * 0.7f;
        if (document.getBitmap() != null) {
            document.setAlpha(progress);
            document.setImageCoords(-half, -half, half * 2.0f, half * 2.0f);
            document.draw(canvas);
        } else {
            final int sz = Math.min(AndroidUtilities.dp(15), (int) half);
            gift.emojiDrawable.setAlpha(Math.round(progress * 255.0f));
            gift.emojiDrawable.setBounds(-sz, -sz, sz, sz);
            gift.emojiDrawable.draw(canvas);
        }
        canvas.restore();

        drawParticles(canvas, t, true);
        glass.draw(canvas, radius, progress, wobble, pulse, true);
        canvas.restore();

        if (animate && !closing && isShown() && getWindowVisibility() == View.VISIBLE) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (closing) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                inside = Math.hypot(downX - centerX, downY - centerY) <= radius;
                dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (inside) {
                    final float dx = event.getX() - downX;
                    final float dy = event.getY() - downY;
                    dragged |= Math.abs(dx) + Math.abs(dy) > AndroidUtilities.dp(5);
                    offsetX = dx * 0.88f;
                    offsetY = dy * 0.88f;
                    layoutDetails();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (inside && !dragged) {
                    tapTilt = tapTilt > 0.0f ? -12.0f : 12.0f;
                    invalidate();
                    performClick();
                } else {
                    close();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragged) {
                    close();
                }
                return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
