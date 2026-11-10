package com.exteragram.messenger.icons.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.core.content.res.ResourcesCompat;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.icons.ExteraResources;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.ui.components.NewIconPackBottomSheet;
import com.exteragram.messenger.icons.ui.picker.IconPickerController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * exteraGram icon pack editor (IconPacksEditorActivity.kt): every replaceable drawable with its replacement in the
 * pack, a search field, a filter (all / replaced / not replaced) and "Edit info" / "Save and exit" actions.
 * A tap opens the replace-icon sheet.
 */
public class IconPacksEditorActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final ArrayList<UItem> cachedIconItems = new ArrayList<>();
    private static boolean isIconsLoaded;
    private static boolean isLoading;

    private IconPack iconPack;
    private int iconFilter;
    private ActionBarMenuItem otherItem;
    private ArrayList<UItem> packItems;
    private IconPack packItemsFor;
    private String[] packItemLowerNames = new String[0];
    private String query;
    private Runnable searchRunnable;
    private boolean searching;
    private final ActionBarMenuSubItem[] filterItems = new ActionBarMenuSubItem[3];

    public IconPacksEditorActivity(IconPack iconPack) {
        this.iconPack = iconPack;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
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
                updateAdapter();
            }

            @Override
            public void onTextChanged(EditText editText) {
                if (searchRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                }
                searchRunnable = () -> {
                    query = editText.getText().toString();
                    updateAdapter();
                };
                AndroidUtilities.runOnUIThread(searchRunnable, 200);
            }
        }).setSearchPaddingStart(7);
        otherItem = menu.addItem(3, R.drawable.ic_ab_other);
        otherItem.addSwipeBackItem(R.drawable.msg_select, null, LocaleController.getString(R.string.IconPickerFilter), createFilterLayout(context));
        otherItem.addSubItem(1, R.drawable.msg_edit, LocaleController.getString(R.string.Edit));
        if (ExteraConfig.getEditingIconPackId() != null) {
            ActionBarMenuSubItem save = otherItem.addSubItem(2, R.drawable.ic_ab_done, LocaleController.getString(R.string.IconPickerSaveAndExit));
            save.setColors(getThemedColor(Theme.key_featuredStickers_addButtonPressed), getThemedColor(Theme.key_featuredStickers_addButtonPressed));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    new NewIconPackBottomSheet(IconPacksEditorActivity.this, context, iconPack).show();
                } else if (id == 2) {
                    ExteraConfig.setEditingIconPackId(null);
                    Activity activity = getParentActivity();
                    if (activity instanceof LaunchActivity) {
                        IconPickerController.setActive((LaunchActivity) activity, false);
                    }
                    finishFragment();
                }
            }
        });
        return view;
    }

    private void updateAdapter() {
        if (fragmentView != null && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private ActionBarPopupWindow.ActionBarPopupWindowLayout createFilterLayout(Context context) {
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, resourceProvider);
        layout.setFitItems(true);
        ActionBarMenuSubItem back = ActionBarMenuItem.addItem(layout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, resourceProvider);
        back.setOnClickListener(v -> {
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout = otherItem != null ? otherItem.getPopupLayout() : null;
            PopupSwipeBackLayout swipeBack = popupLayout != null ? popupLayout.getSwipeBack() : null;
            if (swipeBack != null) {
                swipeBack.closeForeground();
            }
        });
        View gap = ActionBarMenuItem.addGap(0, layout);
        gap.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        gap.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator));
        createFilterItem(layout, LocaleController.getString(R.string.IconPickerAllIcons), 0);
        createFilterItem(layout, LocaleController.getString(R.string.IconPickerReplacedIcons), 1);
        createFilterItem(layout, LocaleController.getString(R.string.IconPickerNotReplacedIcons), 2);
        updateFilterChecks();
        return layout;
    }

    private void createFilterItem(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, String text, int filter) {
        ActionBarMenuSubItem item = new ActionBarMenuSubItem(layout.getContext(), true, false, false, resourceProvider);
        item.setTextAndIcon(text, 0);
        item.setMinimumWidth(AndroidUtilities.dp(196));
        layout.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        item.setOnClickListener(v -> setIconFilter(filter));
        filterItems[filter] = item;
    }

    private void setIconFilter(int filter) {
        if (iconFilter == filter) {
            return;
        }
        iconFilter = filter;
        updateFilterChecks();
        updateAdapter();
    }

    private void updateFilterChecks() {
        for (int i = 0; i < filterItems.length; i++) {
            if (filterItems[i] != null) {
                filterItems[i].setChecked(iconFilter == i);
            }
        }
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.iconPackUpdated);
        loadIconsAsync();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.iconPackUpdated);
        super.onFragmentDestroy();
    }

    private void loadIconsAsync() {
        if ((isIconsLoaded && !cachedIconItems.isEmpty()) || isLoading) {
            return;
        }
        isLoading = true;
        Utilities.globalQueue.postRunnable(() -> {
            HashMap<String, Integer> icons = new HashMap<>(IconManager.INSTANCE.getSystemIcons());
            if (icons.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    isLoading = false;
                    IconManager.INSTANCE.initialize(true);
                });
                return;
            }
            ArrayList<UItem> items = new ArrayList<>(1500);
            for (Map.Entry<String, Integer> entry : icons.entrySet()) {
                items.add(EditorIconCell.Factory.asIcon(entry.getValue(), entry.getKey(), null));
            }
            Collections.sort(items, (a, b) -> a.text.toString().compareTo(b.text.toString()));
            AndroidUtilities.runOnUIThread(() -> {
                cachedIconItems.clear();
                cachedIconItems.addAll(items);
                isIconsLoaded = true;
                isLoading = false;
                packItems = null;
                updateAdapter();
            });
        });
    }

    @Override
    public String getTitle() {
        return iconPack != null && iconPack.getName() != null ? iconPack.getName() : LocaleController.getString(R.string.NewIconPack);
    }

    private ArrayList<UItem> getPackItems() {
        if (packItems != null && java.util.Objects.equals(packItemsFor, iconPack)) {
            return packItems;
        }
        ArrayList<UItem> items = new ArrayList<>(cachedIconItems.size());
        String[] names = new String[cachedIconItems.size()];
        for (int i = 0; i < cachedIconItems.size(); i++) {
            UItem item = cachedIconItems.get(i);
            items.add(EditorIconCell.Factory.asIcon(item.id, item.text, iconPack));
            names[i] = item.text != null ? item.text.toString().toLowerCase(Locale.getDefault()) : "";
        }
        packItems = items;
        packItemLowerNames = names;
        packItemsFor = iconPack;
        return items;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (getContext() == null || !isIconsLoaded) {
            return;
        }
        ArrayList<UItem> all = getPackItems();
        String lowerQuery = !searching || query == null || query.isEmpty() ? null : query.toLowerCase(Locale.getDefault());
        Map<String, String> icons = iconPack != null ? iconPack.getIcons() : null;
        for (int i = 0; i < all.size(); i++) {
            if (lowerQuery != null && !packItemLowerNames[i].contains(lowerQuery)) {
                continue;
            }
            UItem item = all.get(i);
            if (iconFilter != 0) {
                String name = item.text != null ? item.text.toString() : null;
                boolean replaced = name != null && icons != null && icons.containsKey(name);
                if ((iconFilter == 1 && !replaced) || (iconFilter == 2 && replaced)) {
                    continue;
                }
            }
            items.add(item);
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        IconManager.INSTANCE.showReplaceAlert(getContext(), item.id, iconPack);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.iconPackUpdated || getContext() == null) {
            return;
        }
        if (!isIconsLoaded) {
            loadIconsAsync();
        }
        if (iconPack != null) {
            IconPack updated = IconManager.INSTANCE.findPackById(iconPack.getId());
            if (updated == null) {
                finishFragment();
                return;
            }
            iconPack = updated;
            if (actionBar != null) {
                actionBar.setTitle(getTitle());
            }
        }
        Parcelable state = fragmentView != null && listView != null && listView.getLayoutManager() != null ? listView.getLayoutManager().onSaveInstanceState() : null;
        updateAdapter();
        if (state != null) {
            listView.getLayoutManager().onRestoreInstanceState(state);
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

    /** Row of the editor and of the icon picker: original icon, name and the pack's replacement on the right. */
    @SuppressLint("ViewConstructor")
    public static final class EditorIconCell extends TextCell {

        private int boundResId;

        public EditorIconCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context, resourcesProvider);
        }

        public static final class Factory extends UItem.UItemFactory<EditorIconCell> {
            static {
                setup(new Factory());
            }

            public static UItem asIcon(int id, CharSequence text, IconPack pack) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = text;
                item.object = pack;
                return item;
            }

            @Override
            public EditorIconCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new EditorIconCell(context, resourcesProvider);
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.id != b.id || !TextUtils.equals(a.text, b.text)) {
                    return false;
                }
                IconPack pa = a.object instanceof IconPack ? (IconPack) a.object : null;
                IconPack pb = b.object instanceof IconPack ? (IconPack) b.object : null;
                if (pa == pb) {
                    return true;
                }
                if (pa == null || pb == null) {
                    return false;
                }
                String name = a.text.toString();
                return java.util.Objects.equals(pa.getIcons().get(name), pb.getIcons().get(name));
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                EditorIconCell cell = (EditorIconCell) view;
                cell.reset();
                cell.boundResId = item.id;
                Resources resources = cell.getContext().getResources();
                Drawable original;
                try {
                    original = resources instanceof ExteraResources ? ((ExteraResources) resources).getOriginalDrawable(item.id) : ResourcesCompat.getDrawable(resources, item.id, null);
                } catch (Exception e) {
                    original = null;
                }
                IconPack pack = item.object instanceof IconPack ? (IconPack) item.object : null;
                Drawable replacement = pack != null ? IconManager.INSTANCE.getCachedPackIconDrawable(pack, item.id) : IconManager.INSTANCE.getCachedDrawable(item.id);
                cell.setTextAndIconAndValueDrawable(item.text, original != null ? original.mutate() : null, replacement, divider);
                cell.setIsIcon(true);
                cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                cell.setOffsetFromImage(68);
                if (replacement == null) {
                    int resId = item.id;
                    Utilities.Callback<Drawable> callback = drawable -> {
                        if (cell.boundResId == resId) {
                            cell.getValueImageView().setImageDrawable(drawable);
                        }
                    };
                    if (pack != null) {
                        IconManager.INSTANCE.requestPackIconDrawable(pack, resId, callback);
                    } else {
                        IconManager.INSTANCE.requestDrawable(resId, callback);
                    }
                }
            }
        }
    }
}
