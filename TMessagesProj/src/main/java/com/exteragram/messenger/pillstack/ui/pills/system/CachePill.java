package com.exteragram.messenger.pillstack.ui.pills.system;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.core.PillType;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CacheControlActivity;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** exteraGram: cache size pill with a device storage ring; opens Storage Usage. */
@SuppressLint("ViewConstructor")
public class CachePill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private static final AtomicLong lastKnownCacheSize = new AtomicLong(-1);
    private static float lastKnownProgress = -1.0f;
    private final AtomicBoolean calculating = new AtomicBoolean(false);
    private final ImageView iconView;
    private final LinearLayout layout;
    private final StorageProgressDrawable progressDrawable;
    private final AnimatedTextView textView;

    @Override
    public long getRefreshInterval() {
        return 180000L;
    }

    public CachePill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48.0f));
        layout.setPadding(AndroidUtilities.dp(6.0f), 0, AndroidUtilities.dp(8.0f), 0);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        progressDrawable = new StorageProgressDrawable(iconView);
        iconView.setImageDrawable(progressDrawable);
        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13.0f));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);
        if (lastKnownCacheSize.get() != -1 && !isRefreshDue()) {
            setData(lastKnownCacheSize.get(), lastKnownProgress, false);
        } else {
            iconView.setVisibility(GONE);
            textView.setVisibility(GONE);
        }
    }

    @Override
    public int getPillId() {
        return PillType.CACHE.getId();
    }

    @Override
    public void onUpdateData(boolean force) {
        boolean unknown = lastKnownCacheSize.get() == -1;
        if ((force || unknown || isRefreshDue()) && calculating.compareAndSet(false, true)) {
            if (force || unknown) {
                CacheControlActivity.resetCalculatedTotalSIze();
            }
            startLoading();
            ImageLoader.getInstance().checkMediaPaths(() -> CacheControlActivity.calculateTotalSize(size -> {
                lastKnownCacheSize.set(size);
                CacheControlActivity.getDeviceTotalSize((total, free) -> {
                    float progress = total > 0 ? (total - free) / (float) total : 0.0f;
                    lastKnownProgress = progress;
                    calculating.set(false);
                    setData(size, progress, true);
                });
            }));
        }
    }

    private void setData(long size, float progress, boolean animated) {
        stopLoading();
        String text = AndroidUtilities.formatFileSize(size);
        if (animated && (textView.getText() == null || !TextUtils.equals(textView.getText(), text) || textView.getVisibility() == GONE)) {
            animateSizeChange();
        }
        textView.setText(text, animated);
        progressDrawable.setProgress(progress, animated);
        iconView.setVisibility(VISIBLE);
        textView.setVisibility(VISIBLE);
        markDataUpdated();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pillStackSettingsChanged && PillStackConfig.shouldUpdatePill(args, getPillId())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public void onPillClicked() {
        openCacheSettings();
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
            .add(R.drawable.msg2_data, LocaleController.getString(R.string.StorageUsage), this::openCacheSettings)
            .addGap()
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
            .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), () -> fragment.presentFragment(new PillStackPreferencesActivity()))
            .setDrawScrim(false)
            .setDimAlpha(0)
            .show();
        return true;
    }

    private void openCacheSettings() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new CacheControlActivity());
        }
    }

    @Override
    public void updateColors() {
        int color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14.0f), Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f), Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        progressDrawable.setColor(color);
        updateLoadingColors();
    }

    public static class StorageProgressDrawable extends Drawable {
        private final AnimatedFloat animatedProgress;
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private final RectF rectF = new RectF();

        public StorageProgressDrawable(View view) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            animatedProgress = new AnimatedFloat(view, 650L, CubicBezierInterpolator.EASE_OUT_QUINT);
        }

        public void setProgress(float value, boolean animated) {
            progress = Math.max(0.05f, Math.min(value, 1.0f));
            if (!animated) {
                animatedProgress.force(progress);
            }
            invalidateSelf();
        }

        public void setColor(int color) {
            this.color = color;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            int width = getBounds().width();
            int height = getBounds().height();
            float size = Math.min(width, height) - AndroidUtilities.dp(2.0f);
            float left = (width - size) / 2.0f;
            float top = (height - size) / 2.0f;
            rectF.set(left, top, left + size, top + size);
            float p = animatedProgress.set(progress);
            paint.setStrokeWidth(AndroidUtilities.dp(2.0f));
            paint.setColor(color);
            paint.setAlpha(50);
            canvas.drawCircle(width / 2.0f, height / 2.0f, size / 2.0f, paint);
            paint.setAlpha(255);
            canvas.drawArc(rectF, -90.0f, p * 360.0f, false, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
