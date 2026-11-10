package com.exteragram.messenger.appicons;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import com.exteragram.messenger.utils.AppUtils;

import org.telegram.messenger.LocaleController;

/**
 * Launcher icon of exteraGram's icon picker (AppIcon.java), each backed by an activity-alias in
 * TMessagesProj/src/main/AndroidManifest.xml (org.telegram.messenger.*Icon).
 */
public final class AppIcon {

    public final String id;
    public final String component;
    public final String availability;
    public final int titleRes;
    public final String title;
    public final int descriptionRes;
    public final String description;
    /** Accent color of the picker header; 0 = derived from the icon (AppIconPreviewLoader.getAccent). */
    public final int color;
    public final String authorUsername;
    public final String authorName;
    public final int background;
    public final boolean backgroundIsColor;
    public final int foreground;
    public final int monochrome;
    private ComponentName componentName;

    private AppIcon(String id, String component, String availability, int titleRes, String title, int descriptionRes, String description, int color, String authorUsername, String authorName, int background, boolean backgroundIsColor, int foreground, int monochrome) {
        this.id = id;
        this.component = component;
        this.availability = availability;
        this.titleRes = titleRes;
        this.title = title;
        this.descriptionRes = descriptionRes;
        this.description = description;
        this.color = color;
        this.authorUsername = authorUsername;
        this.authorName = authorName;
        this.background = background;
        this.backgroundIsColor = backgroundIsColor;
        this.foreground = foreground;
        this.monochrome = monochrome;
    }

    public static AppIcon of(String id, String component, String availability, int titleRes, String title, int descriptionRes, String description, int color, String authorUsername, String authorName, int background, boolean backgroundIsColor, int foreground, int monochrome) {
        return new AppIcon(id, component, availability, titleRes, title, descriptionRes, description, color, authorUsername, authorName, background, backgroundIsColor, foreground, monochrome);
    }

    public ComponentName getComponentName(Context context) {
        if (componentName == null) {
            componentName = new ComponentName(context.getPackageName(), component);
        }
        return componentName;
    }

    public boolean isDefault() {
        return "default".equals(id);
    }

    public boolean isMonet() {
        return "monet".equals(availability);
    }

    public boolean isAvailable() {
        if ("winter".equals(availability)) {
            return AppUtils.isWinter();
        }
        if (!isMonet()) {
            return true;
        }
        // exteraGram limits Monet icons to API 31-32; StaticGram allows every Android 12+ (system_accent colors)
        return Build.VERSION.SDK_INT >= 31;
    }

    public int getBackground() {
        return background;
    }

    public boolean isBackgroundColor() {
        return backgroundIsColor;
    }

    public int getForeground() {
        return foreground;
    }

    public CharSequence getTitle() {
        return titleRes != 0 ? LocaleController.getString(titleRes) : title;
    }

    public CharSequence getDescription() {
        if (descriptionRes != 0) {
            return LocaleController.getString(descriptionRes);
        }
        return description;
    }

    /** Author credit shown in the picker header; null for the app's own icons. */
    public String getAuthor() {
        if (authorName != null) {
            return authorName;
        }
        if (authorUsername == null || "exteraGram".equalsIgnoreCase(authorUsername)) {
            return null;
        }
        return "@" + authorUsername;
    }
}
