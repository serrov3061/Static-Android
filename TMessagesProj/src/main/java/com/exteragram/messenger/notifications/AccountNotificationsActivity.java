package com.exteragram.messenger.notifications;

import android.view.View;

import com.exteragram.messenger.preferences.BasePreferencesActivity;

import java.util.ArrayList;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

// exteraGram: choose which accounts show notifications
public final class AccountNotificationsActivity extends BasePreferencesActivity {

    private float shiftDp = -3.0f;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.AccountNotifications);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ShowNotificationsFor)));
        for (int account : AccountNotifications.getAccounts()) {
            items.add(UItem.asUserCheckbox(account + 1000, UserConfig.getInstance(account).getCurrentUser())
                    .setChecked(account == UserConfig.selectedAccount || AccountNotifications.INSTANCE.isEnabled(account)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.AccountNotificationsInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.viewType != UniversalAdapter.VIEW_TYPE_USER_CHECKBOX) {
            return;
        }
        final int account = item.id - 1000;
        if (account == UserConfig.selectedAccount) {
            shiftDp = -shiftDp;
            AndroidUtilities.shakeViewSpring(view, shiftDp);
            BotWebViewVibrationEffect.APP_ERROR.vibrate();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.AccountNotificationsCurrent)).show();
            return;
        }
        toggleBooleanSettingAndRefresh$TMessagesProj(item, enabled -> AccountNotifications.INSTANCE.setEnabled(account, enabled));
    }
}
