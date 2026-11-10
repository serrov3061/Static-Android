package com.exteragram.messenger.feed.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.feed.FeedChannelActions;
import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * exteraGram feed settings: bottom tab, unread counter, archived channels, categories (13.1.1) and the
 * list of channels shown / hidden in All, plus the "Wide Posts in Feed" switch.
 */
public final class FeedChannelsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_FEED_TAB = 1000;
    private static final int ID_WIDE_POSTS = 1001;
    private static final int ID_UNREAD_COUNTER = 1002;
    private static final int ID_ARCHIVE = 1003;
    private static final int ID_CREATE_CATEGORY = 1004;
    private static final int ID_CATEGORY_BASE = 2000;

    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_DESELECT_ALL = 2;

    private static final Comparator<TLRPC.Chat> BY_TITLE = (a, b) -> {
        String ta = a.title != null ? a.title.toLowerCase(Locale.ROOT) : "";
        String tb = b.title != null ? b.title.toLowerCase(Locale.ROOT) : "";
        return ta.compareTo(tb);
    };

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private ActionBarMenuItem otherItem;
    private String query;
    private boolean searching;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.FeedSettings);
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, FeedController.feedNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogDeleted);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, FeedController.feedNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogDeleted);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == FeedController.feedNeedReload) {
            reloadChannels();
        } else if (id == NotificationCenter.dialogDeleted) {
            removeChannel((Long) args[0]);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SELECT_ALL) {
                    setAllExcluded(false);
                } else if (id == MENU_DESELECT_ALL) {
                    setAllExcluded(true);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (otherItem != null) {
                    otherItem.setVisibility(View.GONE);
                }
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                query = null;
                if (otherItem != null) {
                    otherItem.setVisibility(View.VISIBLE);
                }
                updateList();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase(Locale.getDefault());
                updateList();
            }
        });
        searchItem.setSearchPaddingStart(7);
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        otherItem = menu.addItem(3, R.drawable.ic_ab_other);
        otherItem.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll));
        otherItem.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, LocaleController.getString(R.string.DeselectAll));
        // exteraGram 13.1.1: categories are reordered by dragging
        listView.allowReorder(true);
        listView.listenReorder((sectionId, sectionItems) -> {
            ArrayList<Integer> ids = new ArrayList<>();
            for (int i = 0; i < sectionItems.size(); i++) {
                Object object = sectionItems.get(i).object;
                if (object instanceof FeedConfig.Category) {
                    ids.add(((FeedConfig.Category) object).getId());
                }
            }
            FeedConfig.getInstance(currentAccount).reorderCategories(ids);
            FeedController.getInstance(currentAccount).notifyCategoriesChanged();
        });
        reloadChannels();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateList();
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!searching) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            actionBar.closeSearchField();
        }
        return false;
    }

    private void reloadChannels() {
        FeedController.getInstance(currentAccount).loadChannels(true, (list, includedCount, failed, configGeneration) -> {
            if (failed) {
                return;
            }
            channels.clear();
            for (int i = 0; i < list.size(); i++) {
                TLRPC.Chat chat = list.get(i);
                TLRPC.Chat actual = getMessagesController().getChat(chat.id);
                channels.add(actual != null ? actual : chat);
            }
            Collections.sort(channels, BY_TITLE);
            updateList();
        });
    }

    private void removeChannel(long dialogId) {
        boolean removed = false;
        for (int i = channels.size() - 1; i >= 0; i--) {
            if (-channels.get(i).id == dialogId) {
                channels.remove(i);
                removed = true;
            }
        }
        if (removed) {
            updateList();
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        String q = query;
        boolean noQuery = TextUtils.isEmpty(q);
        if (noQuery) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
            items.add(uCheck$TMessagesProj(ID_FEED_TAB, LocaleController.getString(R.string.FeedBottomTab), ExteraConfig.getShowFeedTab(), LocaleController.getString(R.string.FeedBottomTabInfo), 0, true, "feedBottomTab", true, 0));
            items.add(uCheck$TMessagesProj(ID_WIDE_POSTS, LocaleController.getString(R.string.WidePostsInFeed), ExteraConfig.getWidePostsInFeed(), null, 0, true, "feedWidePosts", true, 0));
            items.add(uCheck$TMessagesProj(ID_UNREAD_COUNTER, LocaleController.getString(R.string.FeedUnreadCounter), ExteraConfig.getShowFeedUnreadCounter(), null, 0, true, "feedUnreadCounter", true, 0));
            items.add(uCheck$TMessagesProj(ID_ARCHIVE, LocaleController.getString(R.string.FeedIncludeArchived), feedConfig.getIncludeArchived(), null, 0, true, "feedIncludeArchived", true, 0));
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedIncludeArchivedInfo)));
            addCategoriesSection(items, adapter);
        }
        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            if (noQuery || (chat.title != null && chat.title.toLowerCase(Locale.getDefault()).contains(q))) {
                boolean excluded = feedConfig.isExcluded(-chat.id);
                (excluded ? hidden : shown).add(UItem.asUserCheckbox(Long.hashCode(chat.id), chat).setChecked(!excluded));
            }
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedShownChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow());
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedHiddenChannels)));
            items.addAll(hidden);
        }
        if (noQuery && (!shown.isEmpty() || !hidden.isEmpty())) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedChannelsInfo)));
        }
    }

    private void addCategoriesSection(ArrayList<UItem> items, UniversalAdapter adapter) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(LocaleController.getString(R.string.FeedCategories)));
        adapter.reorderSectionStart();
        for (FeedConfig.Category category : FeedConfig.getInstance(currentAccount).getCategories()) {
            items.add(FeedCategoryCell.Factory.of(ID_CATEGORY_BASE + category.getId(), category, v -> toggleCategoryHidden(category.getId())));
        }
        adapter.reorderSectionEnd();
        items.add(uButton$TMessagesProj(ID_CREATE_CATEGORY, LocaleController.getString(R.string.FeedCategoryCreate), R.drawable.msg_add, null, null, null, false, null, false, true, null));
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(LocaleController.getString(R.string.FeedCategoriesInfo)));
    }

    private void toggleCategoryHidden(int categoryId) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        FeedConfig.Category category = feedConfig.getCategory(categoryId);
        if (category != null && feedConfig.setCategoryHidden(categoryId, !category.getIsHidden())) {
            FeedController.getInstance(currentAccount).notifyCategoriesChanged();
            updateList();
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof FeedConfig.Category) {
            presentFragment(new FeedCategoryEditActivity(((FeedConfig.Category) item.object).getId(), false));
            return;
        }
        if (item.id == ID_CREATE_CATEGORY) {
            presentFragment(new FeedCategoryEditActivity(0, false));
            return;
        }
        TLRPC.Chat chat = item.object instanceof TLRPC.Chat ? (TLRPC.Chat) item.object : null;
        if (chat != null) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, checked -> FeedConfig.getInstance(currentAccount).setExcluded(-chat.id, !checked));
            return;
        }
        if (item.id == ID_FEED_TAB) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setShowFeedTab);
            updateList();
            getNotificationCenter().postNotificationName(FeedController.feedTabVisibleToggled);
        } else if (item.id == ID_WIDE_POSTS) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setWidePostsInFeed);
        } else if (item.id == ID_UNREAD_COUNTER) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, ExteraConfig::setShowFeedUnreadCounter);
            getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, 0);
        } else if (item.id == ID_ARCHIVE) {
            FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
            feedConfig.setIncludeArchived(!feedConfig.getIncludeArchived());
            item.setChecked(feedConfig.getIncludeArchived());
            reloadChannels();
            updateList();
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        TLRPC.Chat chat = item.object instanceof TLRPC.Chat ? (TLRPC.Chat) item.object : null;
        if (chat == null) {
            return super.onLongClick(item, view, position, x, y);
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.add(R.drawable.msg_channel, LocaleController.getString(R.string.OpenChannel2), () -> presentFragment(ChatActivity.of(-chat.id)));
        options.addIf(FeedChannelActions.canLeave(chat), R.drawable.msg_leave, LocaleController.getString(R.string.LeaveChannelMenu), true, () -> FeedChannelActions.leaveChannel(this, chat, null, null));
        options.show();
        return true;
    }

    private void setAllExcluded(boolean excluded) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        if (excluded) {
            ArrayList<Long> ids = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                ids.add(-channels.get(i).id);
            }
            feedConfig.excludeAll(ids);
        } else {
            feedConfig.clearExcluded();
        }
        updateList();
    }
}
