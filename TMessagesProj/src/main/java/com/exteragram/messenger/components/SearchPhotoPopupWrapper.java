package com.exteragram.messenger.components;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Stories.DarkThemeResourceProvider;

// exteraGram: engine picker ("Search photo") shown from the PhotoViewer menu
public class SearchPhotoPopupWrapper {

    public final ActionBarPopupWindow.ActionBarPopupWindowLayout searchSwipeBackLayout;

    public SearchPhotoPopupWrapper(Context context, PopupSwipeBackLayout swipeBackLayout, Utilities.Callback<ReverseImageSearch.Provider> callback) {
        searchSwipeBackLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, null);
        searchSwipeBackLayout.setFitItems(true);

        ActionBarMenuSubItem back = ActionBarMenuItem.addItem(searchSwipeBackLayout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, null);
        back.setOnClickListener(v -> swipeBackLayout.closeForeground());
        back.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        back.setSelectorColor(0x0FFFFFFF);

        addSeparator(context);

        addEngine("Yandex", ReverseImageSearch.Provider.YANDEX, callback);
        addEngine("Google", ReverseImageSearch.Provider.GOOGLE, callback);
        addEngine("Bing", ReverseImageSearch.Provider.BING, callback);
        addEngine("TinEye", ReverseImageSearch.Provider.TINEYE, callback);

        addSeparator(context);

        TextView info = new LinkSpanDrawable.LinksTextView(context);
        info.setTag(R.id.fit_width_tag, Boolean.TRUE);
        info.setPadding(AndroidUtilities.dp(13), 0, AndroidUtilities.dp(13), AndroidUtilities.dp(8));
        info.setTextSize(1, 12f);
        info.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, new DarkThemeResourceProvider()));
        info.setMovementMethod(LinkMovementMethod.getInstance());
        info.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, new DarkThemeResourceProvider()));
        info.setText(LocaleController.getString(R.string.SearchPhotoInfo));
        searchSwipeBackLayout.addView(info, LayoutHelper.createLinear(-1, -2, 0f, 0, 0, 8, 0, 0));
    }

    private void addSeparator(Context context) {
        FrameLayout separator = new FrameLayout(context);
        separator.setMinimumWidth(AndroidUtilities.dp(196));
        separator.setBackgroundColor(0xFF181818);
        searchSwipeBackLayout.addView(separator);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) separator.getLayoutParams();
        if (LocaleController.isRTL) {
            params.gravity = Gravity.RIGHT;
        }
        params.width = -1;
        params.height = AndroidUtilities.dp(8);
        separator.setLayoutParams(params);
    }

    private void addEngine(String title, ReverseImageSearch.Provider provider, Utilities.Callback<ReverseImageSearch.Provider> callback) {
        ActionBarMenuSubItem item = ActionBarMenuItem.addItem(searchSwipeBackLayout, 0, title, false, null);
        item.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        item.setOnClickListener(v -> callback.run(provider));
        item.setSelectorColor(0x0FFFFFFF);
    }
}
