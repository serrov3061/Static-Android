package com.exteragram.messenger.pillstack.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.pillstack.core.PillRegistry;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** exteraGram: choose and reorder the pills shown in the dialogs search field. */
public final class PillStackPreferencesActivity extends BasePreferencesActivity {

    private static final int ID_INFINITE_SCROLLING = 1000;

    private Drawable reorderIcon;
    private ActionBarMenuItem resetItem;
    private final HashMap<Integer, ItemInfo> itemDetails = new HashMap<>();
    private int activeSectionId = -1;
    private int hiddenSectionId = -1;

    private static final class ItemInfo {
        final CharSequence name;
        final int iconRes;
        final int iconColorTop;
        final int iconColorBottom;

        ItemInfo(CharSequence name, int iconRes, int iconColorTop, int iconColorBottom) {
            this.name = name;
            this.iconRes = iconRes;
            this.iconColorTop = iconColorTop;
            this.iconColorBottom = iconColorBottom;
        }
    }

    @Override
    public void initializeOptionStrings() {
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            itemDetails.put(info.id(), new ItemInfo(info.name(), info.iconRes(), info.iconColorTop(), info.iconColorBottom()));
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.PillStackPills);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
        resetItem.setContentDescription(LocaleController.getString(R.string.Reset));
        resetItem.setOnClickListener(v -> resetToDefault());
        updateResetButtonVisibility(false);
        UniversalRecyclerView list = getListView$TMessagesProj();
        list.allowReorder(true);
        list.listenReorder(this::updateConfigFromReorder);
        return view;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (reorderIcon == null) {
            reorderIcon = ContextCompat.getDrawable(getContext(), R.drawable.list_reorder);
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default(this, ID_INFINITE_SCROLLING, LocaleController.getString(R.string.PillStackInfiniteScrolling), PillStackConfig.getInfiniteScrolling(), null, 0, false, "pillStackInfiniteScrolling", false, 0, 440, null));
        items.add(UItem.asShadow(null));
        activeSectionId = -1;
        hiddenSectionId = -1;
        if (!PillStackConfig.getActivePills().isEmpty()) {
            activeSectionId = addMenuSection(items, adapter, LocaleController.getString(R.string.PillStackActivePills), PillStackConfig.getActivePills());
            items.add(UItem.asShadow(LocaleController.getString(R.string.PillStackPillsSettingsInfo)));
        }
        if (PillStackConfig.getHiddenPills().isEmpty()) {
            return;
        }
        hiddenSectionId = addMenuSection(items, adapter, LocaleController.getString(R.string.PillStackHiddenPills), PillStackConfig.getHiddenPills());
        if (PillStackConfig.getActivePills().isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PillStackPillsSettingsInfo)));
        }
    }

    private int addMenuSection(ArrayList<UItem> items, UniversalAdapter adapter, String header, List<Integer> ids) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(header));
        int sectionId = adapter.reorderSectionStart();
        for (Integer id : ids) {
            ItemInfo info = itemDetails.get(id);
            if (info != null) {
                items.add(createMenuItem(id, info));
            }
        }
        adapter.reorderSectionEnd();
        adapter.whiteSectionEnd();
        return sectionId;
    }

    private UItem createMenuItem(int id, final ItemInfo info) {
        UItem item = BasePreferencesActivity.uButton$TMessagesProj$default(this, id, info.name, info.iconRes, null, null, null, false, null, false, false, null, 1976, null);
        item.object2 = reorderIcon;
        item.bind = view -> {
            if (view instanceof TextCell) {
                TextCell cell = (TextCell) view;
                cell.setColorfulIcon(info.iconColorTop, info.iconColorBottom, info.iconRes);
                cell.setImageLeft(21);
                cell.setOffsetFromImage(65);
            }
        };
        return item;
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id <= 0) {
            return;
        }
        if (id == ID_INFINITE_SCROLLING) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, PillStackConfig::setInfiniteScrolling);
            return;
        }
        if (PillStackConfig.getActivePills().contains(id)) {
            PillStackConfig.getActivePills().remove(Integer.valueOf(id));
            if (!PillStackConfig.getHiddenPills().contains(id)) {
                PillStackConfig.getHiddenPills().add(0, id);
            }
        } else if (PillStackConfig.getHiddenPills().contains(id)) {
            PillStackConfig.getHiddenPills().remove(Integer.valueOf(id));
            PillStackConfig.getActivePills().add(id);
        }
        saveAndNotify();
    }

    private void saveAndNotify() {
        PillStackConfig.savePillsLayout();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged);
        UniversalAdapter adapter = getListView$TMessagesProj().adapter;
        if (adapter != null) {
            adapter.update(true);
        }
        updateResetButtonVisibility(true);
    }

    private void updateResetButtonVisibility(boolean animated) {
        if (resetItem != null) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, !PillStackConfig.getActivePills().equals(PillStackConfig.getDefaultActivePills()), 0.5f, animated);
        }
    }

    private void resetToDefault() {
        PillStackConfig.getActivePills().clear();
        PillStackConfig.getActivePills().addAll(PillStackConfig.getDefaultActivePills());
        PillStackConfig.getHiddenPills().clear();
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            if (!PillStackConfig.getActivePills().contains(info.id())) {
                PillStackConfig.getHiddenPills().add(info.id());
            }
        }
        saveAndNotify();
    }

    private void updateConfigFromReorder(int sectionId, ArrayList<UItem> items) {
        ArrayList<Integer> ids = new ArrayList<>(items.size());
        for (UItem item : items) {
            ids.add(item.id);
        }
        if (sectionId == activeSectionId) {
            PillStackConfig.getActivePills().clear();
            PillStackConfig.getActivePills().addAll(ids);
        } else if (sectionId == hiddenSectionId) {
            PillStackConfig.getHiddenPills().clear();
            PillStackConfig.getHiddenPills().addAll(ids);
        }
        saveAndNotify();
    }
}
