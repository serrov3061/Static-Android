package com.exteragram.messenger.icons.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.icons.BaseIconPacks;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.ui.components.IconPackCell;
import com.exteragram.messenger.icons.ui.components.NewIconPackBottomSheet;
import com.exteragram.messenger.icons.ui.picker.IconPickerController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * exteraGram icon packs screen (IconPacksActivity.kt): the built-in packs (Telegram default, Solar, Remix), then the
 * enabled custom packs (reorderable, the topmost wins) and all other installed packs. A tap enables / disables a
 * custom pack, a long press offers Edit (enabled packs), Share (.icons archive) and Delete; the floating button
 * creates a new pack.
 */
public class IconPacksActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final String[] BASE_PACKS = {"base.default", "base.solar", "base.remix"};

    private FragmentFloatingButton floatingButton;
    private Runnable reorderRunnable;
    private boolean scrollUpdated;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.iconPackUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.iconPackUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.iconPackUpdated && fragmentView != null && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (floatingButton != null) {
            floatingButton.setTranslationY(-bottom);
        }
        super.onInsets(left, top, right, bottom);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        floatingButton = new FragmentFloatingButton(context, resourceProvider);
        floatingButton.imageView.setImageResource(R.drawable.msg_add);
        floatingButton.setContentDescription(LocaleController.getString(R.string.Add));
        floatingButton.setOnClickListener(v -> new NewIconPackBottomSheet(this, context).show());
        if (view instanceof FrameLayout) {
            ((FrameLayout) view).addView(floatingButton, FragmentFloatingButton.createDefaultLayoutParams());
        }
        listView.allowReorder(true);
        listView.setReorderHandleOnly(true);
        listView.listenReorder(this::updateConfigFromReorder);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy != 0 && scrollUpdated) {
                    floatingButton.setButtonVisible(dy < 0, true);
                }
                scrollUpdated = true;
            }
        });
        return view;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.IconPacks);
    }

    private static String getActiveBasePack() {
        for (String id : ExteraConfig.getIconPacksLayout()) {
            if (id.startsWith("base.")) {
                return id;
            }
        }
        return "base.default";
    }

    private static IconPack getPackById(String id) {
        return id.startsWith("base.") ? BaseIconPacks.INSTANCE.getBasePack(id) : IconManager.INSTANCE.findPackById(id);
    }

    private static int countCustomPacks(List<String> ids) {
        int count = 0;
        for (String id : ids) {
            if (!id.startsWith("base.") && getPackById(id) != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String active = getActiveBasePack();
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(LocaleController.getString(R.string.BasePacks)));
        for (String id : BASE_PACKS) {
            IconPack pack = getPackById(id);
            if (pack != null) {
                UItem item = IconPackCell.Factory.asIconPackCell(pack);
                item.checked = id.equals(active);
                items.add(item);
            }
        }
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(LocaleController.getString(R.string.BaseIconPackInfo)));

        int enabled = countCustomPacks(ExteraConfig.getIconPacksLayout());
        if (enabled > 0) {
            addCustomPacksSection(items, adapter, LocaleController.getString(R.string.EnabledPacks), ExteraConfig.getIconPacksLayout(), enabled > 1);
        }
        int hidden = countCustomPacks(ExteraConfig.getIconPacksHidden());
        if (hidden > 0) {
            if (enabled > 0) {
                items.add(UItem.asShadow(null));
            }
            addCustomPacksSection(items, adapter, LocaleController.getString(R.string.AllPacks), ExteraConfig.getIconPacksHidden(), hidden > 1);
        }
        if (enabled > 0 || hidden > 0) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.IconPacksHint)));
        }
    }

    private void addCustomPacksSection(ArrayList<UItem> items, UniversalAdapter adapter, String header, List<String> ids, boolean reorderable) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(header));
        if (reorderable) {
            adapter.reorderSectionStart();
        }
        for (String id : ids) {
            IconPack pack;
            if (!id.startsWith("base.") && (pack = getPackById(id)) != null) {
                items.add(IconPackCell.Factory.asIconPackCell(pack).setReordering(reorderable));
            }
        }
        if (reorderable) {
            adapter.reorderSectionEnd();
        }
        adapter.whiteSectionEnd();
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof IconPack)) {
            return;
        }
        String id = ((IconPack) item.object).getId();
        if (id.startsWith("base.")) {
            if (id.equals(getActiveBasePack())) {
                return;
            }
            ExteraConfig.getIconPacksLayout().removeIf(s -> s.startsWith("base."));
            ExteraConfig.getIconPacksLayout().add(id);
            ExteraConfig.getIconPacksHidden().remove(id);
            // StaticGram: keep the other base packs listed as hidden (ExteraConfig.loadConfig expects every base id)
            for (String base : BASE_PACKS) {
                if (!base.equals(id) && !ExteraConfig.getIconPacksHidden().contains(base)) {
                    ExteraConfig.getIconPacksHidden().add(base);
                }
            }
        } else if (ExteraConfig.getIconPacksLayout().contains(id)) {
            ExteraConfig.getIconPacksLayout().remove(id);
            if (!ExteraConfig.getIconPacksHidden().contains(id)) {
                ExteraConfig.getIconPacksHidden().add(0, id);
            }
        } else if (ExteraConfig.getIconPacksHidden().contains(id)) {
            ExteraConfig.getIconPacksHidden().remove(id);
            ExteraConfig.getIconPacksLayout().add(0, id);
        }
        ExteraConfig.saveIconPacksLayout();
        IconManager.INSTANCE.initialize(true);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof IconPack)) {
            return super.onLongClick(item, view, position, x, y);
        }
        IconPack pack = (IconPack) item.object;
        if (pack.isBase()) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.addIf(ExteraConfig.getIconPacksLayout().contains(pack.getId()), R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> openEditor(pack));
        options.add(R.drawable.msg_share, LocaleController.getString(R.string.ShareFile), () -> sharePack(pack));
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDeletePack(pack));
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.setDismissOnMoveOutside(true);
        options.show();
        return true;
    }

    private void openEditor(IconPack pack) {
        ExteraConfig.setEditingIconPackId(pack.getId());
        Activity activity = getParentActivity();
        if (activity instanceof LaunchActivity) {
            IconPickerController.setActive((LaunchActivity) activity, true);
        }
        presentFragment(new IconPacksEditorActivity(pack));
    }

    private void sharePack(IconPack pack) {
        Utilities.globalQueue.postRunnable(() -> {
            File file = IconManager.INSTANCE.bundlePackBlocking(pack.getId());
            if (file == null) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                Activity activity = getParentActivity();
                if (activity == null) {
                    return;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)));
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        });
    }

    private void confirmDeletePack(IconPack pack) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(LocaleController.getString(R.string.DeletePack))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeletePackInfo, pack.getName())))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, which) -> IconManager.INSTANCE.deletePack(pack.getId()))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void updateConfigFromReorder(int sectionId, ArrayList<UItem> items) {
        ArrayList<String> ids = new ArrayList<>();
        for (UItem item : items) {
            if (item.object instanceof IconPack) {
                ids.add(((IconPack) item.object).getId());
            }
        }
        boolean enabledSection = countCustomPacks(ExteraConfig.getIconPacksLayout()) > 1 && sectionId == 0;
        if (enabledSection) {
            String base = null;
            for (String id : ExteraConfig.getIconPacksLayout()) {
                if (id.startsWith("base.")) {
                    base = id;
                    break;
                }
            }
            ExteraConfig.getIconPacksLayout().clear();
            ExteraConfig.getIconPacksLayout().addAll(ids);
            if (base != null) {
                ExteraConfig.getIconPacksLayout().add(base);
            }
        } else {
            // hidden custom packs; keep the hidden base ids (StaticGram stores them in the same list)
            ArrayList<String> bases = new ArrayList<>();
            for (String id : ExteraConfig.getIconPacksHidden()) {
                if (id.startsWith("base.")) {
                    bases.add(id);
                }
            }
            ExteraConfig.getIconPacksHidden().clear();
            ExteraConfig.getIconPacksHidden().addAll(ids);
            ExteraConfig.getIconPacksHidden().addAll(bases);
        }
        ExteraConfig.saveIconPacksLayout();
        if (enabledSection) {
            if (reorderRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(reorderRunnable);
            }
            reorderRunnable = () -> IconManager.INSTANCE.initialize(true);
            AndroidUtilities.runOnUIThread(reorderRunnable, 500);
        }
        if (fragmentView != null && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
