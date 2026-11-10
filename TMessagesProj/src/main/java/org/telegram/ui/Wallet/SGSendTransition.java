package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The 13.1.1 "money thrown into the chat" transition (WalletChatSendTransition /
 * WalletSendTransition) for StaticGram transfers: the currency icon of the amount form flies
 * out of the sheet along the same curve, spinning, with the WalletSendParticles burst and trail,
 * while the sheet drops away with the same exit curve. It lands in the chat (above the input)
 * with a burst, or, outside a chat, rises and ends with the celebration sparks. The particles
 * that are still alive move to the activity window, so they outlive the sheet.
 */
public final class SGSendTransition {

    private static final long DURATION = 600;

    private final ViewGroup root;
    private final View outgoing;
    private final View source;
    private final BaseFragment fragment;
    private final Runnable completion;
    private final boolean inChat;
    private final WalletSendParticles particles;
    private final PathInterpolator exitCurve = new PathInterpolator(.3f, 0f, .8f, .15f);
    private final RectF from = new RectF(), to = new RectF();
    private final FrameLayout overlay;
    private final float sourceAlpha;
    private final float outgoingTranslation;
    private float x, y, size, spin, iconAlpha = 1;
    private boolean finished;
    private ValueAnimator animator;

    /**
     * @param root       full-window container of the sheet (BottomSheet.getContainer())
     * @param outgoing   the sheet itself; it slides down while the icon flies
     * @param source     the currency icon of the amount form
     * @param completion dismisses the sheet; runs when the icon has landed
     */
    public static void start(ViewGroup root, View outgoing, View source, BaseFragment fragment, int kind, Runnable completion) {
        if (root == null || source == null || !root.isAttachedToWindow() || !source.isAttachedToWindow()
                || source.getWidth() <= 0 || fragment == null || fragment.getParentActivity() == null) {
            completion.run();
            return;
        }
        try {
            new SGSendTransition(root, outgoing, source, fragment, kind, completion);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            completion.run();
        }
    }

    private SGSendTransition(ViewGroup root, View outgoing, View source, BaseFragment fragment, int kind, Runnable completion) {
        this.root = root;
        this.outgoing = outgoing;
        this.source = source;
        this.fragment = fragment;
        this.completion = completion;
        inChat = fragment instanceof ChatActivity && ((ChatActivity) fragment).getChatListView() != null;
        particles = new WalletSendParticles(kind == TL_sgcard.KIND_STARS ? WalletSendParticles.STARS_COLORS : WalletSendParticles.COLORS);
        sourceAlpha = source.getAlpha();
        outgoingTranslation = outgoing == null ? 0 : outgoing.getTranslationY();

        final int[] rootLoc = new int[2], loc = new int[2];
        root.getLocationOnScreen(rootLoc);
        source.getLocationOnScreen(loc);
        from.set(loc[0] - rootLoc[0], loc[1] - rootLoc[1], loc[0] - rootLoc[0] + source.getWidth(), loc[1] - rootLoc[1] + source.getHeight());
        computeTarget(rootLoc);

        overlay = new FrameLayout(root.getContext()) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                particles.draw(canvas);
                drawIcon(canvas);
                super.dispatchDraw(canvas);
                if (particles.isActive() || !finished) postInvalidateOnAnimation();
            }
        };
        overlay.setWillNotDraw(false);
        overlay.setClipChildren(false);
        overlay.setClickable(true);
        root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        x = from.centerX();
        y = from.centerY();
        size = 1;
        source.setAlpha(0f);
        AndroidUtilities.hideKeyboard(source);
        particles.burst(x, y);
        root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);

        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(DURATION);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> frame((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finish();
            }
        });
        animator.start();
    }

    /** Where the money lands: just above the chat input, or high over the screen outside a chat. */
    private void computeTarget(int[] rootLoc) {
        final float side = dp(inChat ? 34 : 56);
        float cx, cy;
        if (inChat) {
            final View list = ((ChatActivity) fragment).getChatListView();
            final int[] loc = new int[2];
            list.getLocationOnScreen(loc);
            cx = loc[0] + list.getWidth() / 2f;
            cy = loc[1] + list.getHeight() - dp(44);
        } else {
            cx = root.getWidth() / 2f + rootLoc[0];
            cy = rootLoc[1] + root.getHeight() * .3f;
        }
        cx -= rootLoc[0];
        cy -= rootLoc[1];
        to.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2);
    }

    private void frame(float p) {
        if (finished) return;
        final float exit = exitCurve.getInterpolation(Math.min(1f, p * 2));
        if (outgoing != null) outgoing.setTranslationY(outgoingTranslation + outgoing.getHeight() * exit);
        final float q = 1f - p;
        final float controlX = from.centerX() + (to.centerX() - from.centerX()) * .35f;
        final float controlY = Math.min(from.centerY(), to.centerY()) - dp(150);
        x = q * q * from.centerX() + 2 * q * p * controlX + p * p * to.centerX();
        y = q * q * from.centerY() + 2 * q * p * controlY + p * p * to.centerY();
        final float fromSide = Math.max(from.width(), from.height());
        size = AndroidUtilities.lerp(1f, to.width() / Math.max(1, fromSide), p);
        // The 13.1.1 diamond turns 540 degrees on its way; here it spins around the vertical axis.
        spin = 540f * CubicBezierInterpolator.EASE_OUT.getInterpolation(p);
        if (!inChat) iconAlpha = 1f - Math.max(0, (p - .85f) / .15f);
        if (p < .92f) particles.trail(x, y);
        overlay.invalidate();
    }

    private void drawIcon(Canvas canvas) {
        if (finished || iconAlpha <= 0) return;
        final int w = source.getWidth(), h = source.getHeight();
        if (w <= 0 || h <= 0) return;
        final float flip = (float) Math.cos(Math.toRadians(spin));
        final int save = canvas.saveLayerAlpha(x - w * size, y - h * size, x + w * size, y + h * size, (int) (255 * iconAlpha));
        canvas.translate(x, y);
        final float sx = Math.abs(flip) < .08f ? (flip < 0 ? -.08f : .08f) : flip;
        canvas.scale(size * sx, size);
        canvas.translate(-w / 2f, -h / 2f);
        source.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void finish() {
        if (finished) return;
        finished = true;
        if (inChat) particles.burst(to.centerX(), to.centerY());
        else particles.celebrate(to.centerX(), to.centerY());
        root.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        source.setAlpha(sourceAlpha);
        overlay.setClickable(false);
        // Keep the landing sparks alive after the sheet window goes away.
        ViewGroup particleRoot = root;
        final Activity activity = fragment.getParentActivity();
        if (activity != null && activity.getWindow() != null && activity.getWindow().getDecorView() instanceof ViewGroup) {
            final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (decor.isAttachedToWindow() && decor != root.getRootView()) {
                final int[] a = new int[2], b = new int[2];
                root.getLocationOnScreen(a);
                decor.getLocationOnScreen(b);
                root.removeView(overlay);
                overlay.setTranslationX(a[0] - b[0]);
                overlay.setTranslationY(a[1] - b[1]);
                decor.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                particleRoot = decor;
            }
        }
        overlay.invalidate();
        completion.run();
        final ViewGroup cleanupRoot = particleRoot;
        AndroidUtilities.runOnUIThread(() -> cleanupRoot.removeView(overlay), 1500);
    }
}
