package com.exteragram.messenger.utils.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ItemOptions;

/**
 * exteraGram: "filter by type" button of the in-chat search panel.
 */
public final class ChatSearchFilterUiHelper {

    private static final class Type {
        final int iconResId;
        final int titleResId;
        final TLRPC.MessagesFilter filter;
        final boolean groupsOnly;

        Type(int iconResId, int titleResId, TLRPC.MessagesFilter filter, boolean groupsOnly) {
            this.iconResId = iconResId;
            this.titleResId = titleResId;
            this.filter = filter;
            this.groupsOnly = groupsOnly;
        }
    }

    private static final Type[] TYPES = {
            new Type(R.drawable.msg_photos, R.string.SearchFilterPhotos, new TLRPC.TL_inputMessagesFilterPhotos(), false),
            new Type(R.drawable.msg_videocall, R.string.SearchFilterVideos, new TLRPC.TL_inputMessagesFilterVideo(), false),
            new Type(R.drawable.msg_sendfile, R.string.SharedFilesTab2, new TLRPC.TL_inputMessagesFilterDocument(), false),
            new Type(R.drawable.msg_link, R.string.SharedLinksTab2, new TLRPC.TL_inputMessagesFilterUrl(), false),
            new Type(R.drawable.msg_voice_headphones, R.string.SharedMusicTab2, new TLRPC.TL_inputMessagesFilterMusic(), false),
            new Type(R.drawable.input_mic, R.string.SearchFilterVoice, new TLRPC.TL_inputMessagesFilterVoice(), false),
            new Type(R.drawable.input_video, R.string.SearchFilterRound, new TLRPC.TL_inputMessagesFilterRoundVideo(), false),
            new Type(R.drawable.msg_gif, R.string.SharedGIFsTab2, new TLRPC.TL_inputMessagesFilterGif(), false),
            new Type(R.drawable.menu_poll_notify, R.string.SharedPollTab, new TLRPC.TL_inputMessagesFilterPoll(), false),
            new Type(R.drawable.msg_location, R.string.SearchFilterLocations, new TLRPC.TL_inputMessagesFilterGeo(), false),
            new Type(R.drawable.msg_pin, R.string.SearchFilterPinned, new TLRPC.TL_inputMessagesFilterPinned(), false),
            new Type(R.drawable.msg_mention, R.string.SearchFilterMentions, new TLRPC.TL_inputMessagesFilterMyMentions(), true),
    };

    private final ChatActivity chatActivity;
    private ImageView button;
    private Runnable onChanged;
    private ActionBarMenuItem searchItem;
    private FiltersView.MediaFilterData selected;

    public ChatSearchFilterUiHelper(ChatActivity chatActivity) {
        this.chatActivity = chatActivity;
    }

    public ImageView createButton(Context context, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem searchItem, Runnable onChanged) {
        this.searchItem = searchItem;
        this.onChanged = onChanged;
        this.selected = null;
        button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(R.drawable.msg_filter);
        button.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_searchPanelIcons, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_actionBarActionModeDefaultSelector, resourcesProvider), 1));
        button.setContentDescription(LocaleController.getString(R.string.SearchFilterByType));
        button.setOnClickListener(v -> showMenu());
        return button;
    }

    public static int getButtonLeftMargin(View... previous) {
        float margin = 0;
        for (View view : previous) {
            if (view != null && view.getVisibility() != View.GONE) {
                margin += 45.33f;
            }
        }
        return AndroidUtilities.dp(margin);
    }

    public static int getCountTextLeftMargin(View... buttons) {
        int count = 0;
        for (View view : buttons) {
            if (view != null && view.getVisibility() != View.GONE) {
                count++;
            }
        }
        return AndroidUtilities.dp(count == 0 ? 18 : 14.67f + count * 45.33f);
    }

    public TLRPC.MessagesFilter getFilter() {
        return selected != null ? selected.filter : null;
    }

    public boolean onFilterCleared(FiltersView.MediaFilterData data) {
        if (data == null || data != selected) {
            return false;
        }
        selected = null;
        return true;
    }

    public void clear() {
        if (selected != null) {
            searchItem.removeSearchFilter(selected);
            selected = null;
        }
    }

    private void showMenu() {
        TLRPC.Chat chat = chatActivity.getCurrentChat();
        boolean isGroup = chat != null && (!ChatObject.isChannel(chat) || chat.megagroup);
        int[] location = new int[2];
        button.getLocationInWindow(location);
        int buttonY = location[1];
        ActionBar actionBar = chatActivity.getActionBar();
        actionBar.getLocationInWindow(location);
        ItemOptions options = ItemOptions.makeOptions(chatActivity, button)
                .setGravity(0)
                .forceTop(true)
                .followScrimView()
                .setMaxHeight(buttonY - location[1] - actionBar.getMeasuredHeight())
                .setScrimViewBackground(Theme.createCircleDrawable(AndroidUtilities.dp(40), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, chatActivity.getResourceProvider())));
        for (Type type : TYPES) {
            if (type.groupsOnly && !isGroup) {
                continue;
            }
            options.addChecked(selected != null && selected.filter == type.filter, getMenuIcon(type.iconResId), LocaleController.getString(type.titleResId), () -> select(type));
        }
        options.show();
    }

    private Drawable getMenuIcon(int iconResId) {
        LayerDrawable drawable = new LayerDrawable(new Drawable[]{ContextCompat.getDrawable(chatActivity.getContext(), iconResId).mutate()});
        drawable.setLayerSize(0, AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        return drawable;
    }

    private void select(Type type) {
        boolean wasSelected = selected != null && selected.filter == type.filter;
        if (selected != null) {
            searchItem.removeSearchFilter(selected);
            selected = null;
        }
        if (!wasSelected) {
            selected = new FiltersView.MediaFilterData(type.iconResId, type.titleResId, type.filter, 0);
            selected.iconSize = 18;
            searchItem.addSearchFilter(selected);
        }
        AndroidUtilities.hideKeyboard(searchItem.getSearchField());
        onChanged.run();
    }
}
