package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.HashMap;
import java.util.Map;

public class ChatActivityEnterViewAnimatedIconView extends RLottieImageView {
    private State currentState;
    private TransitState animatingState;
    private final int sizeDp;
    /** Cherrygram port: iOS-style input uses separate mic/camera lottie (voice_and_video_cg*). */
    private final boolean iosStyle;

    private boolean formatMode;
    private Drawable formatDrawable;
    private final AnimatedFloat formatProgress = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);

    private final Map<TransitState, RLottieDrawable> stateMap = new HashMap<TransitState, RLottieDrawable>() {
        @Nullable
        @Override
        public RLottieDrawable get(@Nullable Object key) {
            RLottieDrawable obj = super.get(key);
            if (obj == null) {
                TransitState state = (TransitState) key;
                int res = state.getResource(iosStyle);
                RLottieDrawable rLottieDrawable = new RLottieDrawable(res, AndroidUtilities.dp(sizeDp), AndroidUtilities.dp(sizeDp));
                put(state, rLottieDrawable);
                return rLottieDrawable;
            }
            return obj;
        }
    };

    public ChatActivityEnterViewAnimatedIconView(Context context) {
        this(context, 32);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, int sizeDp) {
        this(context, sizeDp, false);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, int sizeDp, boolean iosStyle) {
        super(context);
        this.sizeDp = sizeDp;
        this.iosStyle = iosStyle;
    }

    public void setState(State state, boolean animate) {
        if (animate && state == currentState) {
            return;
        }
        State fromState = currentState;
        currentState = state;
        // exteraGram: with an icon pack the Lottie icons cannot be replaced, so draw the (pack-mapped) static drawables instead
        if (!com.exteragram.messenger.icons.IconManager.INSTANCE.isBasePackOnly(com.exteragram.messenger.IconPackType.DEFAULT)) {
            int staticRes = 0;
            switch (state) {
                case SMILE: staticRes = R.drawable.input_smile; break;
                case KEYBOARD: staticRes = R.drawable.input_keyboard; break;
                case STICKER: staticRes = R.drawable.msg_sticker; break;
                case GIF: staticRes = R.drawable.msg_gif; break;
            }
            if (staticRes != 0) {
                animatingState = null;
                android.graphics.drawable.Drawable next = androidx.core.content.ContextCompat.getDrawable(getContext(), staticRes);
                if (next == null) {
                    setImageResource(staticRes);
                    return;
                }
                next = next.mutate();
                android.graphics.drawable.Drawable cur = getDrawable();
                if (!(cur instanceof PackSwapDrawable)) {
                    PackSwapDrawable first = new PackSwapDrawable();
                    first.set(next);
                    setImageDrawable(first);
                    return;
                }
                PackSwapDrawable swap = (PackSwapDrawable) cur;
                if (animate && fromState != null && fromState != state) {
                    swap.swapTo(next);
                } else {
                    swap.set(next);
                }
                return;
            }
        }
        if (!animate || fromState == null || getState(fromState, currentState) == null) {
            RLottieDrawable drawable = stateMap.get(getAnyState(currentState));
            drawable.stop();

            drawable.setProgress(state == State.VOICE && !iosStyle ? 0.5f : 0, false);
            setAnimation(drawable);
        } else {
            TransitState transitState = getState(fromState, currentState);
            if (transitState == animatingState) {
                return;
            }

            animatingState = transitState;
            RLottieDrawable drawable = stateMap.get(transitState);
            drawable.stop();
            if (transitState == TransitState.VIDEO_TO_VOICE && !iosStyle) {
                drawable.setCustomEndFrame(30);
                drawable.setProgress(0, false);
            } else if (transitState == TransitState.VOICE_TO_VIDEO && !iosStyle) {
                drawable.setCustomEndFrame(60);
                drawable.setProgress(0.5f, false);
            } else {
                drawable.setProgress(0, false);
            }
            drawable.setAutoRepeat(0);
            drawable.setOnAnimationEndListener(() -> animatingState = null);
            setAnimation(drawable);
            AndroidUtilities.runOnUIThread(drawable::start);
        }

        switch (state) {
            case VOICE:
                setContentDescription(LocaleController.getString(R.string.AccDescrVoiceMessage));
                break;
            case VIDEO:
                setContentDescription(LocaleController.getString(R.string.AccDescrVideoMessage));
                break;
        }
    }

    /** Sequential swap: old icon shrinks to a point, then the new one grows out of it. Drawn inside the drawable so the view background/ripple is untouched. */
    private static class PackSwapDrawable extends android.graphics.drawable.Drawable {
        private android.graphics.drawable.Drawable shown, pending;
        private float scale = 1f;
        private android.animation.ValueAnimator animator;
        private android.graphics.ColorFilter colorFilter;

        void set(android.graphics.drawable.Drawable d) {
            cancel();
            shown = d;
            pending = null;
            scale = 1f;
            prepare(d);
            invalidateSelf();
        }

        void swapTo(android.graphics.drawable.Drawable next) {
            prepare(next);
            if (shown == null) {
                set(next);
                return;
            }
            pending = next;
            if (animator != null && shrinking) {
                return; // already shrinking, the newest icon will grow afterwards
            }
            cancel();
            startShrink();
        }

        private boolean shrinking;

        private void startShrink() {
            shrinking = true;
            final android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(scale, 0f);
            animator = va;
            va.setDuration(Math.max(1, (long) (100 * scale)));
            va.setInterpolator(CubicBezierInterpolator.EASE_IN);
            va.addUpdateListener(a -> {
                scale = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean canceled;
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    canceled = true;
                }
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (canceled || animator != va) return;
                    shown = pending != null ? pending : shown;
                    pending = null;
                    scale = 0f;
                    startGrow();
                }
            });
            va.start();
        }

        private void startGrow() {
            shrinking = false;
            final android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator = va;
            va.setDuration(140);
            va.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            va.addUpdateListener(a -> {
                scale = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean canceled;
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    canceled = true;
                }
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (canceled || animator != va) return;
                    animator = null;
                    scale = 1f;
                    invalidateSelf();
                }
            });
            va.start();
        }

        private void cancel() {
            if (animator != null) {
                android.animation.ValueAnimator a = animator;
                animator = null;
                a.cancel();
            }
        }

        private void prepare(android.graphics.drawable.Drawable d) {
            d.setColorFilter(colorFilter);
            d.setBounds(getBounds());
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            if (shown == null || scale <= 0f) return;
            android.graphics.Rect b = getBounds();
            shown.setBounds(b);
            shown.setAlpha((int) (255 * Math.max(0f, Math.min(1f, scale))));
            canvas.save();
            canvas.scale(scale, scale, b.centerX(), b.centerY());
            shown.draw(canvas);
            canvas.restore();
            shown.setAlpha(255);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            if (shown != null) shown.setBounds(bounds);
            if (pending != null) pending.setBounds(bounds);
        }

        @Override
        public int getIntrinsicWidth() {
            return shown != null ? shown.getIntrinsicWidth() : -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return shown != null ? shown.getIntrinsicHeight() : -1;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter cf) {
            colorFilter = cf;
            if (shown != null) shown.setColorFilter(cf);
            if (pending != null) pending.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public void setFormatMode(boolean format, boolean animated) {
        if (formatMode == format) {
            return;
        }
        formatMode = format;
        if (!animated) {
            formatProgress.set(format, true);
        }
        invalidate();
    }

    public boolean isFormatMode() {
        return formatMode;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float p = formatProgress.set(formatMode);
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        if (p < 1) {
            final boolean layer = p > 0;
            if (layer) {
                canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (0xFF * (1f - p)), Canvas.ALL_SAVE_FLAG);
            } else {
                canvas.save();
            }
            final float s = 0.4f + 0.6f * (1f - p);
            canvas.scale(s, s, cx, cy);
            super.onDraw(canvas);
            canvas.restore();
        }
        if (p > 0) {
            if (formatDrawable == null) {
                formatDrawable = getContext().getResources().getDrawable(R.drawable.msg_edit).mutate();
            }
            final int w = formatDrawable.getIntrinsicWidth() / 2, h = formatDrawable.getIntrinsicHeight() / 2;
            formatDrawable.setBounds((int) cx - w, (int) cy - h, (int) cx + w, (int) cy + h);
            formatDrawable.setColorFilter(getColorFilter());
            formatDrawable.setAlpha((int) (0xFF * p));
            canvas.save();
            final float s = 0.4f + 0.6f * p;
            canvas.scale(s, s, cx, cy);
            formatDrawable.draw(canvas);
            canvas.restore();
        }
    }

    public State getCurrentState() {
        return currentState;
    }

    private TransitState getAnyState(State from) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from) {
                return transitState;
            }
        }
        return null;
    }

    private TransitState getState(State from, State to) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from && transitState.secondState == to) {
                return transitState;
            }
        }
        return null;
    }

    private enum TransitState {
        VOICE_TO_VIDEO(State.VOICE, State.VIDEO, R.raw.voice_and_video),
        STICKER_TO_KEYBOARD(State.STICKER, State.KEYBOARD, R.raw.sticker_to_keyboard),
        SMILE_TO_KEYBOARD(State.SMILE, State.KEYBOARD, R.raw.smile_to_keyboard),
        VIDEO_TO_VOICE(State.VIDEO, State.VOICE, R.raw.voice_and_video),
        KEYBOARD_TO_STICKER(State.KEYBOARD, State.STICKER, R.raw.keyboard_to_sticker),
        KEYBOARD_TO_GIF(State.KEYBOARD, State.GIF, R.raw.keyboard_to_gif),
        KEYBOARD_TO_SMILE(State.KEYBOARD, State.SMILE, R.raw.keyboard_to_smile),
        GIF_TO_KEYBOARD(State.GIF, State.KEYBOARD, R.raw.gif_to_keyboard),
        GIF_TO_SMILE(State.GIF, State.SMILE, R.raw.gif_to_smile),
        SMILE_TO_GIF(State.SMILE, State.GIF, R.raw.smile_to_gif),
        SMILE_TO_STICKER(State.SMILE, State.STICKER, R.raw.smile_to_sticker),
        STICKER_TO_SMILE(State.STICKER, State.SMILE, R.raw.sticker_to_smile);

        final State firstState, secondState;
        final int resource;

        TransitState(State firstState, State secondState, int resource) {
            this.firstState = firstState;
            this.secondState = secondState;
            this.resource = resource;
        }

        /** Cherrygram: iOS-style mic/camera transition */
        int getResource(boolean iosStyle) {
            if (iosStyle && this == VOICE_TO_VIDEO) {
                return R.raw.voice_and_video_cg;
            }
            if (iosStyle && this == VIDEO_TO_VOICE) {
                return R.raw.voice_and_video_cg_2;
            }
            return resource;
        }
    }

    public enum State {
        VOICE,
        VIDEO,
        STICKER,
        KEYBOARD,
        SMILE,
        GIF
    }
}
