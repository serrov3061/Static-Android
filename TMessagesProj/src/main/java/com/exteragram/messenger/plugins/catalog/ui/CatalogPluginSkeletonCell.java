package com.exteragram.messenger.plugins.catalog.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** Loading placeholder of a catalog cell (exteraGram 13.1.1 catalog/ui/CatalogPluginSkeletonCell). */
@SuppressLint("ViewConstructor")
public final class CatalogPluginSkeletonCell extends FlickerLoadingView {

    private static final float HEIGHT = 91.0f;
    private static final float[] TITLE_WIDTHS = {120.0f, 88.0f, 144.0f};

    private final Theme.ResourcesProvider resourcesProvider;
    private final RectF rect = new RectF();
    private boolean needDivider;
    private int variant;

    public CatalogPluginSkeletonCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        setIsSingleCell(true);
    }

    public void set(int variant, boolean divider) {
        this.variant = variant;
        this.needDivider = divider;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(HEIGHT) + (needDivider ? 1 : 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updateColors();
        updateGradient();
        Paint paint = getPaint();
        int maxRight = getMeasuredWidth() - AndroidUtilities.dp(12.0f);
        drawRect(canvas, 12.0f, 12.0f, AndroidUtilities.dp(56.0f), 56.0f, CatalogPluginCell.getIconRadius(), paint);
        drawRect(canvas, 68.0f, 18.0f, Math.min(AndroidUtilities.dp(TITLE_WIDTHS[variant % TITLE_WIDTHS.length] + 68.0f), maxRight), 26.0f, 4.0f, paint);
        drawRect(canvas, 68.0f, 44.0f, Math.min(AndroidUtilities.dp(268.0f), maxRight), 52.0f, 4.0f, paint);
        drawRect(canvas, 68.0f, 68.0f, Math.min(AndroidUtilities.dp(156.0f), maxRight), 76.0f, 4.0f, paint);
        if (needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(68.0f), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(68.0f) : 0), getMeasuredHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
        }
        invalidate();
    }

    private void drawRect(Canvas canvas, float left, float top, float right, float bottom, float radius, Paint paint) {
        rect.set(AndroidUtilities.dp(left), AndroidUtilities.dp(top), right, AndroidUtilities.dp(bottom));
        if (LocaleController.isRTL) {
            rect.set(getMeasuredWidth() - rect.right, rect.top, getMeasuredWidth() - rect.left, rect.bottom);
        }
        canvas.drawRoundRect(rect, AndroidUtilities.dp(radius), AndroidUtilities.dp(radius), paint);
    }

    public static final class Factory extends UItem.UItemFactory<CatalogPluginSkeletonCell> {

        static {
            setup(new Factory());
        }

        public static UItem of(int id, int variant) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.intValue = variant;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public CatalogPluginSkeletonCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new CatalogPluginSkeletonCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof CatalogPluginSkeletonCell) {
                ((CatalogPluginSkeletonCell) view).set(item.intValue, divider);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.intValue == b.intValue;
        }
    }
}
