package com.exteragram.messenger.appicons;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LauncherIconController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * exteraGram launcher icon switcher (AppIconController.java). It shares the DefaultIcon alias with Telegram's
 * LauncherIconController; switching here also disables Telegram's alternative icons and vice versa
 * (LauncherIconController.setIcon calls {@link #disableExtraIcons}).
 */
public abstract class AppIconController {

    private static List<AppIcon> allIcons;
    private static AppIcon selectedIcon;

    public static List<AppIcon> getAllIcons() {
        if (allIcons == null) {
            allIcons = Collections.unmodifiableList(Arrays.asList(GeneratedAppIcons.create()));
        }
        return allIcons;
    }

    public static List<AppIcon> getAvailableIcons() {
        ArrayList<AppIcon> list = new ArrayList<>();
        for (AppIcon icon : getAllIcons()) {
            if (icon.isAvailable()) {
                list.add(icon);
            }
        }
        return list;
    }

    public static AppIcon findById(String id) {
        for (AppIcon icon : getAllIcons()) {
            if (icon.id.equals(id)) {
                return icon;
            }
        }
        return null;
    }

    public static AppIcon getDefaultIcon() {
        AppIcon icon = findById("default");
        return icon != null ? icon : getAllIcons().get(0);
    }

    public static boolean isEnabled(AppIcon icon) {
        Context context = ApplicationLoader.applicationContext;
        int state = context.getPackageManager().getComponentEnabledSetting(icon.getComponentName(context));
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon.isDefault());
    }

    /** Selected exteraGram icon, or null when one of Telegram's alternative icons is active. */
    public static AppIcon getSelectedIcon() {
        if (selectedIcon == null) {
            for (AppIcon icon : getAllIcons()) {
                if (!icon.isDefault() && isEnabled(icon)) {
                    selectedIcon = icon;
                    break;
                }
            }
            if (selectedIcon == null) {
                selectedIcon = getDefaultIcon();
            }
        }
        return selectedIcon;
    }

    /** True when a non-default exteraGram icon is enabled (used by LauncherIconController.tryFixLauncherIconIfNeeded). */
    public static boolean hasEnabledExtraIcon() {
        for (AppIcon icon : getAllIcons()) {
            if (!icon.isDefault() && isEnabled(icon)) {
                return true;
            }
        }
        return false;
    }

    public static void setIcon(AppIcon icon) {
        selectedIcon = icon;
        List<AppIcon> icons = getAllIcons();
        Utilities.globalQueue.postRunnable(() -> applyIcon(icon, icons));
    }

    private static void applyIcon(AppIcon icon, List<AppIcon> icons) {
        try {
            Context context = ApplicationLoader.applicationContext;
            PackageManager pm = context.getPackageManager();
            ComponentName target = icon.getComponentName(context);
            pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (AppIcon other : icons) {
                if (other != icon) {
                    pm.setComponentEnabledSetting(other.getComponentName(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                }
            }
            for (LauncherIconController.LauncherIcon launcherIcon : LauncherIconController.LauncherIcon.values()) {
                ComponentName name = launcherIcon.getComponentName(context);
                if (!name.equals(target)) {
                    pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Called when Telegram's own picker switches the icon: turn off the exteraGram-only aliases. */
    public static void disableExtraIcons(PackageManager pm, Context context) {
        for (AppIcon icon : getAllIcons()) {
            if (!icon.isDefault()) {
                pm.setComponentEnabledSetting(icon.getComponentName(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
        AndroidUtilities.runOnUIThread(() -> selectedIcon = null);
    }
}
