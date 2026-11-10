package com.exteragram.messenger.plugins.ui.components;

import android.text.SpannableStringBuilder;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.components.CheckBoxRow;
import com.exteragram.messenger.plugins.PythonPluginsEngine;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.IOException;

/**
 * exteraGram plugins: offer to install a newer Python plugin SDK.
 * exteraGram builds this on its app-update sheet; StaticGram has no such sheet, so it is a plain alert with the same actions.
 */
public final class PythonSdkUpdateAlert {

    private final PythonPluginsEngine.Updater.Companion.PythonSdkUpdateInfo update;
    private boolean enableAutoUpdate;

    private PythonSdkUpdateAlert(PythonPluginsEngine.Updater.Companion.PythonSdkUpdateInfo update) {
        this.update = update;
    }

    public static AlertDialog create(BaseFragment fragment, PythonPluginsEngine.Updater.Companion.PythonSdkUpdateInfo update) {
        PythonSdkUpdateAlert alert = new PythonSdkUpdateAlert(update);
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        android.content.Context context = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.PluginsPySdkUpdate));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView versionView = new TextView(context);
        versionView.setTextSize(1, 14);
        versionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        versionView.setText(update.version + ("beta".equals(update.getChannel()) ? " (beta)" : ""));
        layout.addView(versionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        if (update.text != null && update.text.trim().length() > 0) {
            TextView changelogView = new TextView(context);
            changelogView.setTextSize(1, 15);
            changelogView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            SpannableStringBuilder changelog = new SpannableStringBuilder(update.text.trim());
            try {
                MessageObject.addEntitiesToText(changelog, update.entities, false, false, false, false);
            } catch (Exception e) {
                FileLog.e(e);
            }
            changelogView.setText(changelog);
            layout.addView(changelogView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 12, 24, 0));
        }

        CheckBoxRow autoUpdateRow = new CheckBoxRow(context, LocaleController.getString(R.string.EnableAutoUpdate), false, resourcesProvider);
        autoUpdateRow.setOnCheckedChange(checked -> {
            alert.enableAutoUpdate = checked;
            return kotlin.Unit.INSTANCE;
        });
        layout.addView(autoUpdateRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 0));
        builder.setView(layout);

        builder.setPositiveButton(LocaleController.getString(R.string.AppUpdateNow), (dialog, which) -> alert.onDone());
        if (!update.can_not_skip) {
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        }
        AlertDialog dialog = builder.create();
        if (update.can_not_skip) {
            dialog.setCanCancel(false);
            dialog.setCancelable(false);
        }
        return dialog;
    }

    private void onDone() {
        if (enableAutoUpdate) {
            PythonPluginsEngine.Updater.Companion.enableAutoUpdate();
        }
        try {
            PythonPluginsEngine.Updater.Companion.savePythonSdkArchive(update, true);
        } catch (IOException e) {
            TLRPC.Message message = update.getMessage();
            FileLog.e("Failed to load python-plugins-sdk file (" + update.getChannel() + ", message id = " + (message != null ? message.id : null) + ")", e);
        }
    }
}
