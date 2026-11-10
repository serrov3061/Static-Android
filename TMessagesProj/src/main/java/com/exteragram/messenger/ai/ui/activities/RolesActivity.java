package com.exteragram.messenger.ai.ui.activities;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.ui.components.RoleCell;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AIEditorAlert;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

/** Built-in and custom AI roles (port of exteraGram's RolesActivity). */
public final class RolesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.rolesUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.rolesUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.rolesUpdated && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.createMenu().addItem(0, R.drawable.msg_add);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 0) {
                    showRoleAlert(null);
                }
            }
        });
        return view;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Roles);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.RolesInfo), "exteraStaticPlaceholders", "🎭"));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Suggestions)));
        for (final Role role : AiController.getInstance().getSuggestedRoles()) {
            items.add(RoleCell.Factory.asRoleCell(role, v -> selectRole(role)));
        }
        items.add(UItem.asShadow(null));
        List<Role> roles = AiController.getInstance().getRoles();
        if (roles.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.Roles)));
        for (final Role role : roles) {
            items.add(RoleCell.Factory.asRoleCell(role, v -> selectRole(role)));
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        Role role = item.object instanceof Role ? (Role) item.object : null;
        if (role == null) {
            return;
        }
        if (role.isSuggestion()) {
            showRolePreview(role);
        } else {
            showRoleAlert(role);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final Role role = item.object instanceof Role ? (Role) item.object : null;
        if (role == null || role.isSuggestion()) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> showRoleAlert(role));
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
            if (AndroidUtilities.addToClipboard(role.getName() + "\n" + role.getPrompt())) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
        });
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDeleteRole(role));
        options.setScrimViewBackground(listView.getClipBackground(view));
        options.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        options.show();
        return true;
    }

    private void confirmDeleteRole(final Role role) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.Delete))
                .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteRoleInfo, role.getName())))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> deleteRole(role))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteRole(Role role) {
        boolean selected = role.isSelected();
        if (AiController.getInstance().removeRole(role)) {
            if (selected) {
                AiConfig.setSelectedAiRole(AiController.getInstance().getSuggestedRoles().get(0));
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void selectRole(Role role) {
        if (role.isSelected()) {
            return;
        }
        AiConfig.setSelectedAiRole(role);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void showRoleAlert(final Role role) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        String name = role != null && role.getName() != null ? role.getName() : "";
        String prompt = role != null && role.getPrompt() != null ? role.getPrompt() : "";
        new AIEditorAlert.CreateAiStyleAlert(activity, getResourceProvider()).setLocalStyle(name, prompt, role != null ? role.getEmojiId() : 0L, role != null && !role.isSuggestion(), 64, 1024, (newName, newPrompt, emojiId) -> {
            Role newRole = new Role(newName, newPrompt).setEmojiId(emojiId != null ? emojiId : 0L);
            boolean wasSelected = role != null && role.isSelected();
            boolean ok;
            if (role != null && !role.isSuggestion()) {
                ok = AiController.getInstance().updateRole(role, newRole);
            } else {
                ok = AiController.getInstance().addRole(newRole);
            }
            if (ok) {
                if (wasSelected) {
                    AiConfig.setSelectedAiRole(newRole);
                }
                getNotificationCenter().postNotificationName(NotificationCenter.rolesUpdated);
            }
            return ok;
        }).show();
    }

    private void showRolePreview(Role role) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        new AIEditorAlert.CreateAiStyleAlert(activity, getResourceProvider()).setLocalStylePreview(role.getName(), role.getPrompt(), role.getEmojiId(), 64, 1024).show();
    }
}
