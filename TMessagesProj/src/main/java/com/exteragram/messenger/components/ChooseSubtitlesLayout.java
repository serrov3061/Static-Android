package com.exteragram.messenger.components;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.PopupSwipeBackLayout;

// exteraGram: subtitles submenu of the PhotoViewer "more" popup
public class ChooseSubtitlesLayout {

    public interface Callback {
        void onChooseSubtitles();

        void onDisableSubtitles();
    }

    public final ActionBarPopupWindow.ActionBarPopupWindowLayout layout;
    private final ActionBarMenuSubItem disableItem;

    public ChooseSubtitlesLayout(Context context, PopupSwipeBackLayout swipeBackLayout, Callback callback) {
        layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, null);
        layout.setFitItems(true);

        ActionBarMenuSubItem backItem = ActionBarMenuItem.addItem(layout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, null);
        backItem.setOnClickListener(v -> swipeBackLayout.closeForeground());
        backItem.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        backItem.setSelectorColor(0x0FFFFFFF);

        FrameLayout separator = new FrameLayout(context);
        separator.setMinimumWidth(AndroidUtilities.dp(196));
        separator.setBackgroundColor(0xFF181818);
        layout.addView(separator);
        LinearLayout.LayoutParams separatorParams = (LinearLayout.LayoutParams) separator.getLayoutParams();
        separatorParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        separatorParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
        separatorParams.height = AndroidUtilities.dp(8);
        separator.setLayoutParams(separatorParams);

        ActionBarMenuSubItem chooseItem = ActionBarMenuItem.addItem(layout, R.drawable.msg_folders, LocaleController.getString(R.string.ChooseSubtitles), false, null);
        chooseItem.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        chooseItem.setSelectorColor(0x0FFFFFFF);
        chooseItem.setOnClickListener(v -> callback.onChooseSubtitles());

        disableItem = ActionBarMenuItem.addItem(layout, R.drawable.msg_cancel, LocaleController.getString(R.string.DisableSubtitles), false, null);
        disableItem.setColors(0xFFFAFAFA, 0xFFFAFAFA);
        disableItem.setSelectorColor(0x0FFFFFFF);
        disableItem.setOnClickListener(v -> callback.onDisableSubtitles());
    }

    public void update(boolean hasSubtitles) {
        disableItem.setVisibility(hasSubtitles ? View.VISIBLE : View.GONE);
    }
}
