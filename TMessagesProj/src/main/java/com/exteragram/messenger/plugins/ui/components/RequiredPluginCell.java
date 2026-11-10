package com.exteragram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.exteragram.messenger.plugins.PluginsConstants;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProgressButton;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

@SuppressLint({"ViewConstructor"})
public final class RequiredPluginCell extends LinearLayout {
    private static final int BUTTON_GAP_DP = 12;
    private static final int BUTTON_HEIGHT_DP = 28;
    private static final int BUTTON_RIGHT_PADDING = 18;
    private static final int HEIGHT_DP = 64;
    private static final int LEFT_PADDING = 21;
    private static final float LINE_GAP = 4.0f;
    private final ProgressButton actionButton;
    private Utilities.Callback<RequiredPluginInfo> delegate;
    private RequiredPluginInfo info;
    private boolean needDivider;
    private final Theme.ResourcesProvider resourcesProvider;
    private final SimpleTextView subtitleView;
    private final SimpleTextView textView;

    public RequiredPluginCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(0);
        setGravity(16);
        setPadding(AndroidUtilities.dp(21.0f), 0, AndroidUtilities.dp(18.0f), 0);
        SimpleTextView simpleTextView = new SimpleTextView(context);
        simpleTextView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        simpleTextView.setTextSize(16);
        simpleTextView.setGravity(LocaleController.isRTL ? 5 : 3);
        simpleTextView.setImportantForAccessibility(2);
        this.textView = simpleTextView;
        SimpleTextView simpleTextView2 = new SimpleTextView(context);
        simpleTextView2.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        simpleTextView2.setTextSize(13);
        simpleTextView2.setGravity(LocaleController.isRTL ? 5 : 3);
        simpleTextView2.setImportantForAccessibility(2);
        this.subtitleView = simpleTextView2;
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(1);
        linearLayout.addView(simpleTextView, LayoutHelper.createLinear(-1, -2));
        linearLayout.addView(simpleTextView2, LayoutHelper.createLinear(-1, -2, 0.0f, LINE_GAP, 0.0f, 0.0f));
        ProgressButton progressButton = new ProgressButton(context);
        this.actionButton = progressButton;
        ScaleStateListAnimator.apply(progressButton, 0.05f, 1.5f);
        progressButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public final void onClick(View view) {
                RequiredPluginCell.$r8$lambda$uedXg2VNTw6uGtLndaaVv098w8A(RequiredPluginCell.this, view);
            }
        });
        ViewGroup.LayoutParams layoutParamsCreateLinear = LayoutHelper.createLinear(0, -2, 1.0f, 16);
        boolean z = LocaleController.isRTL;
        ViewGroup.LayoutParams layoutParamsCreateLinear2 = LayoutHelper.createLinear(-2, 28, 16, z ? 0.0f : 12.0f, 0.0f, z ? 12.0f : 0.0f, 0.0f);
        if (LocaleController.isRTL) {
            addView(progressButton, layoutParamsCreateLinear2);
            addView(linearLayout, layoutParamsCreateLinear);
        } else {
            addView(linearLayout, layoutParamsCreateLinear);
            addView(progressButton, layoutParamsCreateLinear2);
        }
    }

    public static void $r8$lambda$uedXg2VNTw6uGtLndaaVv098w8A(RequiredPluginCell requiredPluginCell, View view) {
        Utilities.Callback<RequiredPluginInfo> callback;
        RequiredPluginInfo requiredPluginInfo = requiredPluginCell.info;
        if (requiredPluginInfo == null || (callback = requiredPluginCell.delegate) == null) {
            return;
        }
        callback.run(requiredPluginInfo);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64.0f) + (this.needDivider ? 1 : 0), TLObject.FLAG_30));
    }

    @Override
    public void onDraw(Canvas canvas) {
        Paint paint;
        if (this.needDivider) {
            Theme.ResourcesProvider resourcesProvider = this.resourcesProvider;
            if (resourcesProvider == null || (paint = resourcesProvider.getPaint("paintDivider")) == null) {
                paint = Theme.dividerPaint;
            }
            canvas.drawLine(LocaleController.isRTL ? 0.0f : AndroidUtilities.dp(21.0f), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(21.0f) : 0), getMeasuredHeight() - 1, paint);
        }
    }

    public final void setNeedDivider(boolean divider) {
        this.needDivider = divider;
        setWillNotDraw(!divider);
        invalidate();
    }

    public final void set(RequiredPluginInfo info, Utilities.Callback<RequiredPluginInfo> delegate) {
        if (info == null) {
            return;
        }
        this.info = info;
        this.delegate = delegate;
        this.textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        this.textView.setText(info.title());
        this.subtitleView.setTextColor(getThemedColor(info.isSatisfied() ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_text_RedRegular));
        this.subtitleView.setText(info.subtitle());
        int themedColor = getThemedColor(Theme.key_featuredStickers_addButton);
        this.actionButton.setText(info.actionText());
        boolean zIsSatisfied = info.isSatisfied();
        ProgressButton progressButton = this.actionButton;
        if (zIsSatisfied) {
            progressButton.setTextColor(themedColor);
            this.actionButton.setBackground(null);
            this.actionButton.setClickable(false);
            this.actionButton.setFocusable(false);
        } else {
            progressButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            this.actionButton.setProgressColor(getThemedColor(Theme.key_featuredStickers_buttonProgress));
            this.actionButton.setBackground(Theme.AdaptiveRipple.createRect(themedColor, getThemedColor(Theme.key_featuredStickers_addButtonPressed), 14.0f));
            this.actionButton.setClickable(true);
            this.actionButton.setFocusable(true);
        }
        requestLayout();
    }

    public final void setLoading(boolean loading) {
        this.actionButton.setDrawProgress(loading, true);
    }

    private final int getThemedColor(int key) {
        return Theme.getColor(key, this.resourcesProvider);
    }

    public static final class Factory extends UItem.UItemFactory<RequiredPluginCell> {

            public static final Companion Companion = new Companion();

        public static final UItem asRequirement(RequiredPluginInfo requiredPluginInfo, Utilities.Callback<RequiredPluginInfo> callback) {
            return Companion.asRequirement(requiredPluginInfo, callback);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public RequiredPluginCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new RequiredPluginCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof RequiredPluginCell) {
                RequiredPluginCell requiredPluginCell = (RequiredPluginCell) view;
                Object obj = item.object;
                RequiredPluginInfo requiredPluginInfo = obj instanceof RequiredPluginInfo ? (RequiredPluginInfo) obj : null;
                Object obj2 = item.object2;
                requiredPluginCell.set(requiredPluginInfo, obj2 instanceof Utilities.Callback ? (Utilities.Callback) obj2 : null);
                requiredPluginCell.setNeedDivider(divider);
            }
        }

        @Override
        public boolean equals(UItem a2, UItem b2) {
            return a2.id == b2.id;
        }

        @Override
        public boolean contentsEquals(UItem a2, UItem b2) {
            return a2.id == b2.id && TextUtils.equals(a2.text, b2.text);
        }

        public static final class Companion {
            private Companion() {
            }

            public final UItem asRequirement(RequiredPluginInfo info, Utilities.Callback<RequiredPluginInfo> delegate) {
                UItem uItemOfFactory = UItem.ofFactory(Factory.class);
                uItemOfFactory.id = info.id.hashCode();
                uItemOfFactory.text = info.contentKey();
                uItemOfFactory.object = info;
                uItemOfFactory.object2 = delegate;
                return uItemOfFactory;
            }
        }

        static {
            UItem.UItemFactory.setup(new Factory());
        }
    }
}
