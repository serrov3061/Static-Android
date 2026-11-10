package com.exteragram.messenger.icons.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * Row of an icon pack (exteraGram IconPackCell): animated preview (IconPackPreviewView), name, author link and a radio
 * button for built-in packs or a reorder handle for custom ones.
 */
@SuppressLint("ViewConstructor")
public class IconPackCell extends FrameLayout {

    private final IconPackPreviewView iconView;
    private final TextView title;
    private final EffectsTextView subtitle;
    private final RadioButton radioButton;
    private final ImageView handle;
    private boolean needDivider;

    @SuppressLint("ClickableViewAccessibility")
    public IconPackCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setWillNotDraw(false);
        iconView = new IconPackPreviewView(context);
        addView(iconView, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 76, 0, 60, 0));

        title = new TextView(context);
        title.setGravity(Gravity.LEFT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setSingleLine(true);
        title.setMaxLines(1);
        title.setIncludeFontPadding(false);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        title.setTypeface(AndroidUtilities.regular());
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitle = new EffectsTextView(context);
        subtitle.setGravity(Gravity.LEFT);
        subtitle.setClickable(true);
        subtitle.setTypeface(AndroidUtilities.regular());
        subtitle.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        subtitle.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setSingleLine(true);
        subtitle.setMaxLines(1);
        subtitle.setIncludeFontPadding(false);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        radioButton = new RadioButton(context);
        radioButton.setSize(AndroidUtilities.dp(20));
        radioButton.setColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider), Theme.getColor(Theme.key_radioBackgroundChecked, resourcesProvider));
        addView(radioButton, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));

        handle = new ImageView(context);
        handle.setImageResource(R.drawable.list_reorder);
        handle.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        handle.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        handle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(handle, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
    }

    public ImageView getHandle() {
        return handle;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(AndroidUtilities.dp(76), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    public void set(IconPack pack, boolean divider, boolean checked, boolean reordering) {
        needDivider = divider;
        title.setText(pack.getName());
        subtitle.setText(LocaleUtils.fullyFormatText(pack.getAuthor()));
        iconView.setIconPack(pack);
        if (pack.isBase()) {
            handle.setVisibility(GONE);
            radioButton.setVisibility(VISIBLE);
            radioButton.setChecked(checked, true);
        } else {
            handle.setVisibility(reordering ? VISIBLE : GONE);
            radioButton.setVisibility(GONE);
        }
    }

    public static class Factory extends UItem.UItemFactory<IconPackCell> {
        static {
            setup(new Factory());
        }

        @Override
        public IconPackCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new IconPackCell(context, resourcesProvider);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (!(view instanceof IconPackCell)) {
                return;
            }
            IconPackCell cell = (IconPackCell) view;
            cell.set((IconPack) item.object, divider, item.checked, item.reordering);
            cell.getHandle().setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN && listView != null) {
                    listView.startDrag(listView.getChildViewHolder(view));
                }
                return false;
            });
        }

        public static UItem asIconPackCell(IconPack pack) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = pack;
            return item;
        }
    }
}
