package com.exteragram.messenger.appicons.ui.components;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.exteragram.messenger.appicons.AppIcon;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** Grid tile of the launcher icon picker (exteraGram 13.1.1): icon preview with a check badge and the title below. */
@SuppressLint("ViewConstructor")
public class AppIconCell extends LinearLayout implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout previewContainer;
    private final AppIconPreviewView previewView;
    private final CheckBox2 checkBox;
    private final TextView titleView;
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private AppIcon icon;
    private float selection;
    private ValueAnimator selectionAnimator;

    public AppIconCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setClipChildren(false);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(12));

        previewContainer = new FrameLayout(context);
        previewContainer.setClipChildren(false);
        previewView = new AppIconPreviewView(context, resourcesProvider);
        previewContainer.addView(previewView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // the check badge border takes the selection tint so it blends with the selected tile
        checkBox = new CheckBox2(context, 21, key -> key == Theme.key_windowBackgroundWhite ? getSelectionColor() : Theme.getColor(key, resourcesProvider));
        checkBox.setColor(Theme.key_featuredStickers_addButton, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
        checkBox.setDrawUnchecked(false);
        checkBox.setDrawBackgroundAsArc(4);
        checkBox.setProgressDelegate(progress -> previewView.setPreviewScale(1f - progress * 0.08f));
        previewContainer.addView(checkBox, LayoutHelper.createFrame(24, 24, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 1, 1));
        addView(previewContainer, LayoutHelper.createLinear(68, 68, Gravity.CENTER_HORIZONTAL));

        titleView = new TextView(context);
        titleView.setMaxLines(2);
        titleView.setGravity(Gravity.CENTER);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTypeface(AndroidUtilities.regular());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        titleView.setLineSpacing(0, 0.95f);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        ScaleStateListAnimator.apply(this, 0.05f, 1.2f);
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.max(AndroidUtilities.dp(40), Math.min(AndroidUtilities.dp(68), MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight()));
        LayoutParams lp = (LayoutParams) previewContainer.getLayoutParams();
        if (lp.width != size) {
            lp.width = size;
            lp.height = size;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int getSelectionColor() {
        return ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), selection * 0.07f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (selection <= 0) {
            return;
        }
        selectionPaint.setColor(getSelectionColor());
        RectF rect = AndroidUtilities.rectTmp;
        rect.set(AndroidUtilities.dp(2), AndroidUtilities.dp(2), getWidth() - AndroidUtilities.dp(2), getHeight() - AndroidUtilities.dp(2));
        canvas.drawRoundRect(rect, AndroidUtilities.dp(16), AndroidUtilities.dp(16), selectionPaint);
    }

    public void set(AppIcon appIcon, boolean checked, boolean animated) {
        icon = appIcon;
        previewView.setIcon(appIcon);
        titleView.setText(appIcon.getTitle());
        setSelected(checked, animated);
    }

    public AppIcon getIcon() {
        return icon;
    }

    private void setSelected(boolean checked, boolean animated) {
        checkBox.setChecked(checked, animated);
        float target = checked ? 1f : 0f;
        if (selectionAnimator != null) {
            selectionAnimator.cancel();
            selectionAnimator = null;
        }
        if (!animated) {
            selection = target;
            invalidate();
            return;
        }
        if (selection == target) {
            return;
        }
        selectionAnimator = ValueAnimator.ofFloat(selection, target).setDuration(220);
        selectionAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        selectionAnimator.addUpdateListener(a -> {
            selection = (float) a.getAnimatedValue();
            invalidate();
        });
        selectionAnimator.start();
    }

    public static class Factory extends UItem.UItemFactory<AppIconCell> {
        static {
            setup(new Factory());
        }

        @Override
        public AppIconCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new AppIconCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof AppIconCell && item.object instanceof AppIcon) {
                AppIconCell cell = (AppIconCell) view;
                AppIcon appIcon = (AppIcon) item.object;
                cell.set(appIcon, item.checked, cell.getIcon() == appIcon);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.object == b.object;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.object == b.object && a.checked == b.checked;
        }

        public static UItem asAppIcon(AppIcon icon, boolean checked) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = icon;
            item.checked = checked;
            item.spanCount = 1;
            item.transparent = true;
            return item;
        }
    }
}
