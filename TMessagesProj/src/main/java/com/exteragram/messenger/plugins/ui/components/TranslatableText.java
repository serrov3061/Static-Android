package com.exteragram.messenger.plugins.ui.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.text.Layout;
import android.widget.TextView;
import com.exteragram.messenger.plugins.PluginsConstants;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Intrinsics;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.LoadingDrawable;

final class TranslatableText {
    private final Function1<? super String, ? extends CharSequence> format;
    private String language;
    private boolean loading;
    private final LoadingDrawable loadingDrawable;
    private Layout loadingLayout;
    private final LinkPath loadingPath;
    private int oldHeight;
    private Layout oldLayout;
    private final Function0<Unit> onTransitionUpdate;
    private final String original;
    private String shownText;
    private ValueAnimator transitionAnimator;
    private float transitionProgress;
    private String translation;
    private TextView view;

    public TranslatableText(String str, Function1<? super String, ? extends CharSequence> function1, Function0<Unit> function0) {
        this.original = str;
        this.format = function1;
        this.onTransitionUpdate = function0;
        this.shownText = str;
        LinkPath linkPath = new LinkPath(true);
        this.loadingPath = linkPath;
        LoadingDrawable loadingDrawable = new LoadingDrawable();
        loadingDrawable.usePath(linkPath);
        loadingDrawable.setAppearByGradient(true);
        loadingDrawable.setRadiiDp(4.0f);
        this.loadingDrawable = loadingDrawable;
        this.transitionProgress = 1.0f;
    }

    public final String getOriginal() {
        return this.original;
    }

    public final TextView getView() {
        return this.view;
    }

    public final void setView(TextView textView) {
        this.view = textView;
    }

    public final String getLanguage() {
        return this.language;
    }

    public final void setLanguage(String str) {
        this.language = str;
    }

    public final String getTranslation() {
        return this.translation;
    }

    public final void setTranslation(String str) {
        this.translation = str;
    }

    public final void startLoading() {
        this.loading = true;
        this.loadingDrawable.reset();
        this.loadingDrawable.resetDisappear();
        TextView textView = this.view;
        if (textView != null) {
            textView.invalidate();
        }
    }

    public final void stopLoading() {
        this.loadingDrawable.disappear();
        TextView textView = this.view;
        if (textView != null) {
            textView.invalidate();
        }
    }

    public final void show(boolean translated) {
        String str;
        final TextView textView = this.view;
        if (textView == null) {
            return;
        }
        if (!translated || (str = this.translation) == null) {
            str = this.original;
        }
        if (Intrinsics.areEqual(str, this.shownText)) {
            return;
        }
        this.shownText = str;
        ValueAnimator valueAnimator = this.transitionAnimator;
        if (valueAnimator != null) {
            valueAnimator.cancel();
        }
        this.oldLayout = textView.getLayout();
        this.oldHeight = textView.getHeight();
        textView.setText(this.format.invoke(str));
        this.transitionProgress = 0.0f;
        ValueAnimator valueAnimatorOfFloat = ValueAnimator.ofFloat(0.0f, 1.0f);
        valueAnimatorOfFloat.setDuration(350L);
        valueAnimatorOfFloat.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        valueAnimatorOfFloat.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public final void onAnimationUpdate(ValueAnimator valueAnimator2) {
                TranslatableText.$r8$lambda$JRgU_sneSrKGk0HLUPOQr7eYGE4(TranslatableText.this, textView, valueAnimator2);
            }
        });
        valueAnimatorOfFloat.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                TranslatableText.this.oldLayout = null;
                TranslatableText.this.transitionProgress = 1.0f;
                TranslatableText.this.transitionAnimator = null;
                textView.requestLayout();
                textView.invalidate();
            }
        });
        valueAnimatorOfFloat.start();
        this.transitionAnimator = valueAnimatorOfFloat;
    }

    public static void $r8$lambda$JRgU_sneSrKGk0HLUPOQr7eYGE4(TranslatableText translatableText, TextView textView, ValueAnimator valueAnimator) {
        translatableText.transitionProgress = ((Float) valueAnimator.getAnimatedValue()).floatValue();
        textView.requestLayout();
        textView.invalidate();
        translatableText.onTransitionUpdate.invoke();
    }

    public final int getHeight(int measuredHeight) {
        return this.oldLayout == null ? measuredHeight : AndroidUtilities.lerp(this.oldHeight, measuredHeight, this.transitionProgress);
    }

    public final int beforeDraw(Canvas canvas) {
        Layout layout;
        TextView textView = this.view;
        if (textView == null || (layout = this.oldLayout) == null) {
            return -1;
        }
        canvas.save();
        canvas.clipRect(0, 0, textView.getWidth(), textView.getHeight());
        canvas.translate(textView.getTotalPaddingLeft(), textView.getTotalPaddingTop());
        canvas.saveLayerAlpha(0.0f, 0.0f, layout.getWidth(), layout.getHeight(), (int) ((1.0f - this.transitionProgress) * 255.0f));
        layout.draw(canvas);
        canvas.restore();
        canvas.restore();
        return canvas.saveLayerAlpha(0.0f, 0.0f, textView.getWidth(), textView.getHeight(), (int) (255.0f * this.transitionProgress));
    }

    public final void afterDraw(Canvas canvas, int saveCount) {
        if (saveCount >= 0) {
            canvas.restoreToCount(saveCount);
        }
        drawLoading(canvas);
    }

    private final void drawLoading(Canvas canvas) {
        TextView textView = this.view;
        if (textView != null && this.loading) {
            if (this.loadingDrawable.isDisappeared()) {
                this.loading = false;
                return;
            }
            Layout layout = textView.getLayout();
            if (layout == null) {
                return;
            }
            if (layout != this.loadingLayout) {
                this.loadingLayout = layout;
                this.loadingPath.rewind();
                this.loadingPath.setCurrentLayout(layout, 0, textView.getTotalPaddingLeft(), textView.getTotalPaddingTop());
                layout.getSelectionPath(0, layout.getText().length(), this.loadingPath);
                this.loadingDrawable.updateBounds();
            }
            int currentTextColor = textView.getCurrentTextColor();
            this.loadingDrawable.setColors(Theme.multAlpha(currentTextColor, 0.05f), Theme.multAlpha(currentTextColor, 0.15f), Theme.multAlpha(currentTextColor, 0.1f), Theme.multAlpha(currentTextColor, 0.3f));
            this.loadingDrawable.draw(canvas);
            textView.invalidate();
        }
    }
}
