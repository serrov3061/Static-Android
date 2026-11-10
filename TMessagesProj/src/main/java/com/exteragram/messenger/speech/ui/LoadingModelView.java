package com.exteragram.messenger.speech.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickerImageView;

public class LoadingModelView extends FrameLayout {

    private final StickerImageView imageView;
    private final AnimatedTextView percentsTextView;
    private final ProgressView progressView;
    private final TextView title;
    private final TextView subtitle;

    public LoadingModelView(Context context) {
        super(context);

        imageView = new StickerImageView(context, UserConfig.selectedAccount);
        imageView.getImageReceiver().setAutoRepeat(1);
        imageView.setStickerPackName("UtyaDuck");
        imageView.setStickerNum(16);
        addView(imageView, LayoutHelper.createFrame(150, 150, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP, 0, 16, 0, 0));

        percentsTextView = new AnimatedTextView(context, false, true, true);
        percentsTextView.setAnimationProperties(0.35f, 0, 120, CubicBezierInterpolator.EASE_OUT);
        percentsTextView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        percentsTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        percentsTextView.setTextSize(AndroidUtilities.dp(24));
        percentsTextView.setTypeface(AndroidUtilities.bold());
        addView(percentsTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP, 0, 176, 0, 0));

        progressView = new ProgressView(context);
        addView(progressView, LayoutHelper.createFrame(240, 5, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP, 0, 226, 0, 0));

        title = new TextView(context);
        title.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(LocaleController.getString(R.string.DownloadingModel));
        addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP, 0, 261, 0, 0));

        subtitle = new TextView(context);
        subtitle.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setText(LocaleController.getString(R.string.DownloadingModelInfo));
        addView(subtitle, LayoutHelper.createFrame(240, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP, 0, 289, 0, 0));

        setProgress(0f);
    }

    public void setProgress(float progress) {
        int percent = (int) Math.ceil(MathUtils.clamp(progress, 0f, 1f) * 100f);
        percentsTextView.cancelAnimation();
        percentsTextView.setText(percent == 100 ? LocaleController.getString(R.string.ModelUnzipping) : String.format("%s%%", percent), !LocaleController.isRTL);
        progressView.setProgress(progress);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(350), MeasureSpec.EXACTLY));
    }

    public static class ProgressView extends View {
        private final Paint in = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint out = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedFloat progressT = new AnimatedFloat(this, 350, CubicBezierInterpolator.EASE_OUT);
        private float progress;

        public ProgressView(Context context) {
            super(context);
            in.setColor(Theme.getColor(Theme.key_switchTrackChecked));
            out.setColor(Theme.multAlpha(Theme.getColor(Theme.key_switchTrackChecked), 0.2f));
        }

        public void setProgress(float progress) {
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RectF rect = AndroidUtilities.rectTmp;
            rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            canvas.drawRoundRect(rect, AndroidUtilities.dp(3), AndroidUtilities.dp(3), out);
            rect.set(0, 0, getMeasuredWidth() * progressT.set(progress), getMeasuredHeight());
            canvas.drawRoundRect(rect, AndroidUtilities.dp(3), AndroidUtilities.dp(3), in);
        }
    }
}
