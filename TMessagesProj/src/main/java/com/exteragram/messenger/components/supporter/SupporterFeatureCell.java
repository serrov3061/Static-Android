package com.exteragram.messenger.components.supporter;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;

/** exteraGram 13.1.1 SupporterFeatureCell: a perk row (icon, title, subtitle, optional chevron action). */
public class SupporterFeatureCell extends FrameLayout {

    private final FrameLayout content;
    private final ImageView nextIcon;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinkSpanDrawable.LinksTextView subtitleView;

    public SupporterFeatureCell(Context context, int iconRes, CharSequence title, CharSequence subtitle, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        boolean rtl = LocaleController.isRTL;
        int gravity = rtl ? Gravity.RIGHT : Gravity.LEFT;

        content = new FrameLayout(context);
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setImageResource(iconRes);
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        content.addView(imageView, LayoutHelper.createFrame(24, 24, gravity | Gravity.TOP, rtl ? 0 : 21, 10, rtl ? 21 : 0, 0));

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(gravity);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        subtitleView.setText(subtitle);
        subtitleView.setTypeface(AndroidUtilities.regular());
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
        subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        subtitleView.setGravity(gravity);
        textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        content.addView(textLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity | Gravity.TOP, rtl ? 48 : 64, 8, rtl ? 64 : 48, 9));

        nextIcon = new ImageView(context);
        nextIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        nextIcon.setImageResource(R.drawable.msg_arrowright);
        nextIcon.setColorFilter(Theme.getColor(Theme.key_switchTrack, resourcesProvider));
        nextIcon.setScaleX(rtl ? -1.0f : 1.0f);
        nextIcon.setVisibility(View.GONE);
        content.addView(nextIcon, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 18, 0, 18, 0));
    }

    public void setSubtitle(CharSequence subtitle) {
        subtitleView.setText(subtitle);
    }

    public void setAction(Runnable action) {
        nextIcon.setVisibility(action != null ? View.VISIBLE : View.GONE);
        content.setBackground(action != null ? Theme.getSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), false) : null);
        if (action != null) {
            content.setOnClickListener(v -> action.run());
        } else {
            content.setOnClickListener(null);
            content.setClickable(false);
        }
    }
}
