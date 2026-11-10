package com.exteragram.messenger.feed.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.feed.FeedConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Objects;

/**
 * exteraGram 13.1.1: a feed category row in feed settings: emoji icon, title, eye button (show / hide the tab)
 * and a reorder handle. StaticGram: our TextCell has no "icon + value drawable" mode, so the reorder handle
 * is a separate view laid out by this cell.
 */
@SuppressLint("ViewConstructor")
public class FeedCategoryCell extends TextCell {

    private final Theme.ResourcesProvider resourcesProvider;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable iconDrawable;
    private final Drawable placeholderDrawable;
    private final Drawable shownDrawable;
    private final Drawable hiddenDrawable;
    private final ImageView visibilityView;
    private final ImageView reorderView;
    private int boundCategoryId;
    private float hiddenProgress;
    private ValueAnimator visibilityAnimator;

    public FeedCategoryCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        iconDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(imageView, AndroidUtilities.dp(24));
        iconDrawable.setCurrentAccount(currentAccount);
        placeholderDrawable = tinted(context, R.drawable.msg_folders);
        shownDrawable = tinted(context, R.drawable.msg_view_file);
        hiddenDrawable = tinted(context, R.drawable.menu_hide_gift);

        visibilityView = new ImageView(context);
        visibilityView.setScaleType(ImageView.ScaleType.CENTER);
        visibilityView.setImageDrawable(new LayerDrawable(new Drawable[]{shownDrawable, hiddenDrawable}));
        visibilityView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        setHiddenProgress(0);
        addView(visibilityView);

        reorderView = new ImageView(context);
        reorderView.setScaleType(ImageView.ScaleType.CENTER);
        reorderView.setImageDrawable(tinted(context, R.drawable.list_reorder));
        addView(reorderView);
    }

    private Drawable tinted(Context context, int resId) {
        Drawable drawable = ContextCompat.getDrawable(context, resId).mutate();
        drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        return drawable;
    }

    public void set(FeedConfig.Category category, boolean divider, View.OnClickListener onVisibilityClick) {
        boolean animated = boundCategoryId == category.getId();
        boundCategoryId = category.getId();
        if (category.getEmojiId() != 0) {
            iconDrawable.set(category.getEmojiId(), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, false);
            iconDrawable.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        } else {
            iconDrawable.set(placeholderDrawable, false);
            iconDrawable.setColor(null);
        }
        setTextAndIcon(category.getTitle(), iconDrawable, divider);

        boolean hidden = category.getIsHidden();
        float target = hidden ? 1f : 0f;
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
            visibilityAnimator = null;
        }
        if (animated && hiddenProgress != target) {
            visibilityAnimator = ValueAnimator.ofFloat(hiddenProgress, target);
            visibilityAnimator.addUpdateListener(a -> setHiddenProgress((float) a.getAnimatedValue()));
            visibilityAnimator.setDuration(200);
            visibilityAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            visibilityAnimator.start();
        } else {
            setHiddenProgress(target);
        }
        visibilityView.setContentDescription(LocaleController.getString(hidden ? R.string.FeedCategoryShow : R.string.FeedCategoryHide));
        visibilityView.setOnClickListener(onVisibilityClick);

        float alpha = hidden ? 0.5f : 1f;
        View[] views = {textView, imageView, visibilityView};
        for (View view : views) {
            view.animate().cancel();
            if (animated) {
                view.animate().alpha(alpha).setDuration(200).start();
            } else {
                view.setAlpha(alpha);
            }
        }
    }

    private void setHiddenProgress(float progress) {
        hiddenProgress = progress;
        shownDrawable.setAlpha((int) ((1f - progress) * 255));
        hiddenDrawable.setAlpha((int) (progress * 255));
        visibilityView.invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int size = MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(40), MeasureSpec.EXACTLY);
        visibilityView.measure(size, size);
        reorderView.measure(size, size);
        int textWidth = Math.max(0, getMeasuredWidth() - AndroidUtilities.dp(offsetFromImage) - AndroidUtilities.dp(104));
        textView.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(20), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = right - left;
        int size = visibilityView.getMeasuredWidth();
        int y = (bottom - top - size) / 2;
        int reorderX = AndroidUtilities.dp(10);
        int visibilityX = AndroidUtilities.dp(50);
        if (!LocaleController.isRTL) {
            reorderX = width - reorderX - size;
            visibilityX = width - visibilityX - size;
        }
        reorderView.layout(reorderX, y, reorderX + size, y + size);
        visibilityView.layout(visibilityX, y, visibilityX + size, y + size);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        iconDrawable.attach();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        iconDrawable.detach();
    }

    public static class Factory extends UItem.UItemFactory<FeedCategoryCell> {
        static {
            setup(new Factory());
        }

        @Override
        public FeedCategoryCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new FeedCategoryCell(context, currentAccount, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof FeedCategoryCell && item.object instanceof FeedConfig.Category) {
                ((FeedCategoryCell) view).set((FeedConfig.Category) item.object, divider, item.clickCallback);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return Objects.equals(a.object, b.object);
        }

        public static UItem of(int id, FeedConfig.Category category, View.OnClickListener onVisibilityClick) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = category;
            item.clickCallback = onVisibilityClick;
            return item;
        }
    }
}
