package com.exteragram.messenger.ai.ui.activities;

import android.app.Activity;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * List of the user's AI services (port of exteraGram's ServicesActivity).
 * The on-device (Gemini Nano) entry of exteraGram is never shown: on-device AI is unavailable in StaticGram.
 */
public final class ServicesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {
    private Drawable newServiceCircle;
    private CombinedDrawable newServiceDrawable;
    private Drawable newServicePlus;

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.servicesUpdated);
        AiController.getInstance().loadServices();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.servicesUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.servicesUpdated) {
            reloadServices();
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Services);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.ServicesInfo), "exteraStaticPlaceholders", "🔑"));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Services)));
        List<Service> services = AiController.getInstance().getAll();
        for (int i = 0; i < services.size(); i++) {
            Service service = services.get(i);
            UItem item = UItem.asRadio2(i + 100, service.isOnDevice() ? LocaleController.getString(R.string.AIOnDevice) : service.getModel(), service.isOnDevice() ? LocaleController.getString(R.string.AIOnDevice) : service.getUrl()).setChecked(service.isSelected());
            item.object = service;
            items.add(item);
        }
        UItem add = UItem.asButton(1, getNewServiceDrawable(), LocaleController.getString(R.string.NewService)).accent();
        add.pad = 61;
        items.add(add);
    }

    private CombinedDrawable getNewServiceDrawable() {
        if (newServiceDrawable == null) {
            newServiceCircle = getContext().getResources().getDrawable(R.drawable.poll_add_circle).mutate();
            newServicePlus = getContext().getResources().getDrawable(R.drawable.poll_add_plus).mutate();
            newServiceDrawable = new CombinedDrawable(newServiceCircle, newServicePlus) {
                {
                    translateX = AndroidUtilities.dp(2);
                }

                @Override
                public void setColorFilter(ColorFilter colorFilter) {
                }
            };
        }
        newServiceCircle.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_switchTrackChecked), PorterDuff.Mode.MULTIPLY));
        newServicePlus.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_checkboxCheck), PorterDuff.Mode.MULTIPLY));
        return newServiceDrawable;
    }

    private void reloadServices() {
        AiController.getInstance().loadServices();
        if (getContext() == null || fragmentView == null || listView == null || listView.adapter == null) {
            return;
        }
        listView.adapter.update(true);
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        Service service = item.object instanceof Service ? (Service) item.object : null;
        if (item.id < 100 || service == null) {
            if (item.id == 1) {
                presentFragment(new EditServiceActivity());
            }
            return;
        }
        if (service.isOnDevice() || service.isSelected()) {
            return;
        }
        AiConfig.setSelectedServices(service);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final Service service = item.object instanceof Service ? (Service) item.object : null;
        if (service == null || service.isOnDevice()) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> presentFragment(new EditServiceActivity(service)));
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
            // copies endpoint, model and the API key, as exteraGram does (for moving the service to another device)
            if (AndroidUtilities.addToClipboard(service.getUrl() + "\n" + service.getModel() + "\n" + service.getKey())) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        });
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDeleteService(service));
        options.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.show();
        return true;
    }

    private void confirmDeleteService(final Service service) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.Delete))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteServiceInfo, service.getShortModel())))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> deleteService(service))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteService(Service service) {
        boolean selected = service.isSelected();
        if (!AiController.getInstance().removeService(service)) {
            return;
        }
        if (selected) {
            AiConfig.clearSelectedService();
            if (!AiController.getInstance().isServicesEmpty()) {
                AiConfig.setSelectedServices(AiController.getInstance().getAll().get(0));
            }
        }
        getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
        if (fragmentView != null && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
