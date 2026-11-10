package com.exteragram.messenger.ai.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ai.data.Message;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

@SuppressLint("ViewConstructor")
public class HistoryMessageCell extends FrameLayout {
    private boolean needDivider;
    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView textView;
    private final TextView titleView;

    public HistoryMessageCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 22, 10, 22, 11));
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.LEFT);
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        textView = new TextView(context);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setGravity(Gravity.LEFT);
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(AndroidUtilities.dp(22), getMeasuredHeight() - 1, getMeasuredWidth() - AndroidUtilities.dp(22), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    public void set(Message message, CharSequence content, boolean expanded, boolean divider) {
        needDivider = divider;
        textView.setMaxLines(expanded ? Integer.MAX_VALUE : 6);
        textView.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
        boolean assistant = "assistant".equals(message.role());
        titleView.setText(LocaleController.getString(assistant ? R.string.AIAssistant : R.string.FromYou));
        titleView.setTextColor(Theme.getColor(assistant ? Theme.key_windowBackgroundWhiteGrayText2 : Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        textView.setText(Emoji.replaceEmoji(content == null ? "" : content, textView.getPaint().getFontMetricsInt(), false));
    }

    public static class Factory extends UItem.UItemFactory<HistoryMessageCell> {
        static {
            setup(new Factory());
        }

        @Override
        public HistoryMessageCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new HistoryMessageCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof HistoryMessageCell && item.object instanceof Message) {
                ((HistoryMessageCell) view).set((Message) item.object, item.text, item.checked, divider);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            if (a.object instanceof Message && b.object instanceof Message) {
                return a.checked == b.checked && TextUtils.equals(((Message) a.object).role(), ((Message) b.object).role()) && TextUtils.equals(a.text, b.text);
            }
            return false;
        }

        public static UItem asHistoryCell(int id, Message message, CharSequence content, boolean expanded) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = message;
            item.text = content;
            item.checked = expanded;
            return item;
        }
    }
}
