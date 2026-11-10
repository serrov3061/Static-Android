package com.exteragram.messenger.utils.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.utils.chats.FolderRoles;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GroupCreateUserCell;
import org.telegram.ui.LaunchActivity;

import java.util.Objects;

/**
 * exteraGram: UI bits of folder roles (picker rows, chips, avatar icons).
 */
public final class FolderRolesUiHelper {

    private static Drawable ownerIcon;
    private static Drawable adminIcon;
    private static int iconKey;

    private FolderRolesUiHelper() {
    }

    public static Drawable getAvatarIcon(boolean owner) {
        Context context = LaunchActivity.instance;
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        int color = Theme.getColor(Theme.key_avatar_text);
        int key = Objects.hash(color, System.identityHashCode(context.getResources()), IconManager.INSTANCE.getIcon(R.drawable.filled_owner), IconManager.INSTANCE.getIcon(R.drawable.filled_admins));
        if (ownerIcon == null || adminIcon == null || iconKey != key) {
            Drawable ownerDrawable = ContextCompat.getDrawable(context, R.drawable.filled_owner);
            ownerIcon = ownerDrawable != null ? ownerDrawable.mutate() : null;
            Drawable admin = ContextCompat.getDrawable(context, R.drawable.filled_admins);
            adminIcon = admin != null ? admin.mutate() : null;
            PorterDuffColorFilter filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            if (ownerIcon != null) {
                ownerIcon.setColorFilter(filter);
            }
            if (adminIcon != null) {
                adminIcon.setColorFilter(filter);
            }
            iconKey = key;
        }
        return owner ? ownerIcon : adminIcon;
    }

    public static String getName(String chatType) {
        return LocaleController.getString(FolderRoles.modeOf(chatType) == FolderRoles.MODE_OWNER ? R.string.ChannelCreator : R.string.FolderRolesAdmin);
    }

    public static String getChipText(int mode) {
        return LocaleController.getString(mode == FolderRoles.MODE_OWNER ? R.string.FolderRolesOwnerOnly : R.string.FolderRolesAdminOnly);
    }

    public static String getPickerHeader() {
        return LocaleController.getString(R.string.FolderRolesHeader);
    }

    public static void bindPickerRow(GroupCreateUserCell cell, int row, int pickerFlags) {
        int mode = row == 0 ? FolderRoles.MODE_OWNER : FolderRoles.MODE_ADMIN;
        String chatType = FolderRoles.chatTypeOf(mode);
        cell.setAlpha(FolderRoles.supportsFlags(pickerFlags) ? 1f : .5f);
        cell.setObject(chatType, getName(chatType), null);
        cell.setChecked(FolderRoles.fromPickerFlags(pickerFlags) == mode, false);
        cell.setCheckBoxEnabled(true);
    }
}
