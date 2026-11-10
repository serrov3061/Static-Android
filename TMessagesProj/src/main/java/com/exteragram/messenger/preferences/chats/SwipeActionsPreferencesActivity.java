package com.exteragram.messenger.preferences.chats;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.chats.components.SwipeActionsCell;
import com.exteragram.messenger.utils.chats.SwipeAction;
import java.util.ArrayList;
import java.util.List;
import kotlin.collections.CollectionsKt;
import kotlin.enums.EnumEntries;
import kotlin.enums.EnumEntriesKt;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public final class SwipeActionsPreferencesActivity extends BasePreferencesActivity {
    private SwipeActionsCell previewCell;
    private Drawable reorderIcon;

    public static final /* synthetic */ class EntriesMappings {
        public static final /* synthetic */ EnumEntries<SwipeAction> entries$0 = EnumEntriesKt.enumEntries(SwipeAction.values());
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public String getTitle() {
        return LocaleController.getString(R.string.SwipeActions);
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity, org.telegram.ui.ActionBar.BaseFragment
    public View createView(Context context) {
        this.previewCell = new SwipeActionsCell(context);
        View viewCreateView = super.createView(context);
        getListView$TMessagesProj().allowReorder(true);
        getListView$TMessagesProj().listenReorder(new Utilities.Callback2() { // from class: com.exteragram.messenger.preferences.chats.SwipeActionsPreferencesActivity$$ExternalSyntheticLambda0
            @Override // org.telegram.messenger.Utilities.Callback2
            public final void run(Object obj, Object obj2) {
                SwipeActionsPreferencesActivity.this.onReorder(((Integer) obj).intValue(), (ArrayList) obj2);
            }
        });
        return viewCreateView;
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Drawable drawable = this.reorderIcon;
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(getContext(), R.drawable.list_reorder);
        }
        this.reorderIcon = drawable;
        SwipeActionsCell swipeActionsCell = this.previewCell;
        if (swipeActionsCell == null) {
            swipeActionsCell = null;
        }
        items.add(UItem.asCustom(1, swipeActionsCell));
        items.add(UItem.asShadow(LocaleController.getString(R.string.SwipeActionsInfo)));
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(LocaleController.getString(R.string.SwipeActionsBehavior)));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, 2, (CharSequence) LocaleController.getString(R.string.SwipeActionsLoop), ExteraConfig.getSwipeActionsLoop(), (CharSequence) null, 0, false, "swipeActionsLoop", false, 0, 440, (Object) null));
        items.add(BasePreferencesActivity.uCheck$TMessagesProj$default((BasePreferencesActivity) this, 3, (CharSequence) LocaleController.getString(R.string.SwipeActionsReversed), ExteraConfig.getSwipeActionsReversed(), (CharSequence) null, 0, false, "swipeActionsReversed", false, 0, 440, (Object) null));
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(LocaleController.getString(R.string.SwipeActionsBehaviorInfo)));
        addSection(items, adapter, LocaleController.getString(R.string.SwipeActionsEnabled), SwipeAction.enabled());
        items.add(UItem.asShadow(LocaleController.getString(R.string.SwipeActionsOrderInfo)));
        List<SwipeAction> listDisabled = SwipeAction.disabled();
        if (listDisabled.isEmpty()) {
            return;
        }
        addSection(items, adapter, LocaleController.getString(R.string.SwipeActionsDisabled), listDisabled);
        items.add(UItem.asShadow(null));
    }

    private final UniversalAdapter addSection(ArrayList<UItem> items, UniversalAdapter adapter, String header, List<? extends SwipeAction> actions) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(header));
        adapter.reorderSectionStart();
        List<? extends SwipeAction> list = actions;
        ArrayList arrayList = new ArrayList(CollectionsKt.collectionSizeOrDefault(list, 10));
        for (SwipeAction swipeAction : list) {
            UItem uItemUButton$TMessagesProj$default = BasePreferencesActivity.uButton$TMessagesProj$default((BasePreferencesActivity) this, swipeAction.ordinal() + 100, (CharSequence) LocaleController.getString(swipeAction.titleRes), swipeAction.iconRes, (CharSequence) null, (CharSequence) null, (Boolean) null, false, (String) null, false, false, (Boolean) null, 1976, (Object) null);
            uItemUButton$TMessagesProj$default.object2 = this.reorderIcon;
            arrayList.add(uItemUButton$TMessagesProj$default);
        }
        items.addAll(arrayList);
        adapter.reorderSectionEnd();
        adapter.whiteSectionEnd();
        return adapter;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void onReorder(int sectionId, ArrayList<UItem> uItems) {
        if (sectionId != 0) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        ArrayList arrayList2 = new ArrayList();
        int size = uItems.size();
        int i = 0;
        while (i < size) {
            UItem uItem = uItems.get(i);
            i++;
            SwipeAction swipeActionFromId = fromId(uItem.id);
            if (swipeActionFromId != null) {
                arrayList2.add(swipeActionFromId);
            }
        }
        arrayList.addAll(arrayList2);
        SwipeAction.setEnabled(arrayList);
        refresh();
    }

    @Override // com.exteragram.messenger.preferences.BasePreferencesActivity
    public void onClick(UItem item, View view, int position, float x, float y) {
        int i = item.id;
        if (i == 2) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.SwipeActionsPreferencesActivity$$ExternalSyntheticLambda1
                @Override // androidx.core.util.Consumer
                public final void accept(Object obj) {
                    ExteraConfig.setSwipeActionsLoop(((Boolean) obj).booleanValue());
                }
            });
            SwipeActionsCell swipeActionsCell = this.previewCell;
            if (swipeActionsCell == null) {
                swipeActionsCell = null;
            }
            swipeActionsCell.updateActions();
            return;
        }
        if (i == 3) {
            toggleBooleanSettingAndRefresh$TMessagesProj(item, new Consumer() { // from class: com.exteragram.messenger.preferences.chats.SwipeActionsPreferencesActivity$$ExternalSyntheticLambda2
                @Override // androidx.core.util.Consumer
                public final void accept(Object obj) {
                    ExteraConfig.setSwipeActionsReversed(((Boolean) obj).booleanValue());
                }
            });
            return;
        }
        SwipeAction swipeActionFromId = fromId(i);
        if (swipeActionFromId == null) {
            return;
        }
        swipeActionFromId.setEnabled(!swipeActionFromId.isEnabled());
        refresh();
    }

    private final void refresh() {
        SwipeActionsCell swipeActionsCell = this.previewCell;
        if (swipeActionsCell == null) {
            swipeActionsCell = null;
        }
        swipeActionsCell.updateActions();
        UniversalAdapter universalAdapter = getListView$TMessagesProj().adapter;
        if (universalAdapter != null) {
            universalAdapter.update(true);
        }
    }

    private final SwipeAction fromId(int id) {
        int i = id - 100;
        EnumEntries<SwipeAction> enumEntries = EntriesMappings.entries$0;
        if (i < 0 || i >= enumEntries.size()) {
            return null;
        }
        return enumEntries.get(i);
    }
}
