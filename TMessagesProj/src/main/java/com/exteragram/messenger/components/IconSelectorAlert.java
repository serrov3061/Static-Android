package com.exteragram.messenger.components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;

import com.exteragram.messenger.utils.ui.FolderIcons;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

// exteraGram: popup grid with the folder icons (FolderIcons.folderIcons) to pick a folder emoticon
public abstract class IconSelectorAlert {

    public interface OnIconSelectedListener {
        void onIconSelected(String emoticon);
    }

    public static void show(BaseFragment fragment, View view, String selectedEmoticon, OnIconSelectedListener listener) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider);
        final int grayIconColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider);
        final int selectorColor = Theme.getColor(Theme.key_listSelector, resourcesProvider);

        View anchor = (View) view.getParent();
        final ItemOptions options = ItemOptions.makeOptions(fragment, anchor)
            .setGravity(Gravity.LEFT)
            .translate(view.getX() - dp(13), 0);
        if (anchor.getParent() instanceof RecyclerListView) {
            options.setScrimViewBackground(((RecyclerListView) anchor.getParent()).getClipBackground(anchor));
        }

        GridLayout grid = new GridLayout(context);
        int columns = 6;
        while (columns > 1 && dp(columns * 50 + 40) > AndroidUtilities.displaySize.x) {
            columns--;
        }
        grid.setColumnCount(columns);

        for (String emoticon : FolderIcons.folderIcons.keySet()) {
            final boolean selected = emoticon.equals(selectedEmoticon);
            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_XY);
            final int padding = dp(6);
            icon.setPadding(padding, padding, padding, padding);
            icon.setImageResource(FolderIcons.getTabIcon(emoticon));
            icon.setColorFilter(new PorterDuffColorFilter(selected ? textColor : grayIconColor, PorterDuff.Mode.MULTIPLY));
            icon.setBackground(Theme.createRadSelectorDrawable(
                selected ? Theme.multAlpha(textColor, 0.15686275f) : 0, selectorColor, 7, 7));
            icon.setOnClickListener(v -> {
                options.dismiss();
                if (!selected) {
                    listener.onIconSelected(emoticon);
                }
            });
            grid.addView(icon, LayoutHelper.createFrame(48, 48f, Gravity.CENTER, 1f, 1f, 1f, 1f));
        }

        options.addView(grid, LayoutHelper.createLinear(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4f, 4f, 4f, 4f));
        options.show();
    }
}
