package com.exteragram.messenger.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * exteraGram port: static (non-Lottie) icon used by the chat input panel when an icon pack is active.
 * Two stacked ImageViews are cross-faded on state change.
 */
@SuppressLint("ViewConstructor")
public class ChatActivityEnterViewStaticIconView extends FrameLayout {

    public enum State {
        VOICE(R.drawable.input_mic_pressed),
        VIDEO(R.drawable.input_video_pressed),
        STICKER(R.drawable.msg_sticker),
        KEYBOARD(R.drawable.input_keyboard),
        SMILE(R.drawable.input_smile),
        GIF(R.drawable.msg_gif);

        final int resource;

        State(int resource) {
            this.resource = resource;
        }
    }

    private final ImageView[] buttonViews = new ImageView[2];
    private State currentState;
    private AnimatorSet buttonsAnimation;
    private final int sizeDp;

    public ChatActivityEnterViewStaticIconView(Context context, ChatActivityEnterView parentView) {
        this(context, parentView, 32);
    }

    public ChatActivityEnterViewStaticIconView(Context context, ChatActivityEnterView parentView, int sizeDp) {
        super(context);
        setWillNotDraw(false);
        this.sizeDp = sizeDp;
        for (int a = 0; a < 2; a++) {
            buttonViews[a] = new ImageView(context);
            buttonViews[a].setColorFilter(new PorterDuffColorFilter(parentView.getThemedColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.MULTIPLY));
            buttonViews[a].setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            addView(buttonViews[a], LayoutHelper.createFrame(sizeDp, sizeDp, android.view.Gravity.CENTER));
        }
        buttonViews[0].setVisibility(VISIBLE);
        buttonViews[1].setVisibility(GONE);
        buttonViews[1].setScaleX(0.1f);
        buttonViews[1].setScaleY(0.1f);
    }

    public State getCurrentState() {
        return currentState;
    }

    public void setColorFilter(ColorFilter filter) {
        buttonViews[0].setColorFilter(filter);
        buttonViews[1].setColorFilter(filter);
    }

    public void setState(State state, boolean animated) {
        if (animated && state == currentState) {
            return;
        }
        State prev = currentState;
        currentState = state;
        if (animated && prev != null) {
            if (buttonsAnimation != null) {
                buttonsAnimation.cancel();
            }
            buttonViews[1].setVisibility(VISIBLE);
            buttonViews[1].setImageResource(currentState.resource);
            buttonViews[0].setAlpha(1f);
            buttonViews[0].setScaleX(1f);
            buttonViews[0].setScaleY(1f);
            buttonViews[1].setAlpha(0f);
            buttonViews[1].setScaleX(0.1f);
            buttonViews[1].setScaleY(0.1f);
            buttonsAnimation = new AnimatorSet();
            buttonsAnimation.playTogether(
                    ObjectAnimator.ofFloat(buttonViews[0], View.SCALE_X, 0.1f),
                    ObjectAnimator.ofFloat(buttonViews[0], View.SCALE_Y, 0.1f),
                    ObjectAnimator.ofFloat(buttonViews[0], View.ALPHA, 0f),
                    ObjectAnimator.ofFloat(buttonViews[1], View.SCALE_X, 1f),
                    ObjectAnimator.ofFloat(buttonViews[1], View.SCALE_Y, 1f),
                    ObjectAnimator.ofFloat(buttonViews[1], View.ALPHA, 1f)
            );
            buttonsAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation.equals(buttonsAnimation)) {
                        buttonsAnimation = null;
                        ImageView tmp = buttonViews[1];
                        buttonViews[1] = buttonViews[0];
                        buttonViews[0] = tmp;
                        buttonViews[1].setVisibility(INVISIBLE);
                        buttonViews[1].setAlpha(0f);
                        buttonViews[1].setScaleX(0.1f);
                        buttonViews[1].setScaleY(0.1f);
                    }
                }
            });
            buttonsAnimation.setDuration(200);
            buttonsAnimation.start();
        } else {
            if (buttonsAnimation != null) {
                buttonsAnimation.cancel();
                buttonsAnimation = null;
            }
            buttonViews[0].setImageResource(currentState.resource);
            buttonViews[0].setAlpha(1f);
            buttonViews[0].setScaleX(1f);
            buttonViews[0].setScaleY(1f);
            buttonViews[0].setVisibility(VISIBLE);
            buttonViews[1].setVisibility(GONE);
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
}
