package com.exteragram.messenger.icons;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import com.exteragram.messenger.icons.ui.picker.IconObserver;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Resources wrapper of exteraGram: drawables go through {@link IconManager} (icon packs).
 * LaunchActivity.getResources() always serves it (exteraGram LaunchActivity.smali:55685); the application
 * context serves it while an icon pack replaces something.
 */
public final class ExteraResources extends Resources {

    private final Resources mResources;
    private final AssetManager sourceAssets;

    @SuppressWarnings("deprecation")
    public ExteraResources(Resources resources) {
        super(resources.getAssets(), resources.getDisplayMetrics(), resources.getConfiguration());
        this.mResources = resources;
        this.sourceAssets = resources.getAssets();
        IconManager.INSTANCE.initialize(false);
    }

    public AssetManager getSourceAssets() {
        return sourceAssets;
    }

    @Override
    public Drawable getDrawableForDensity(int id, int density, Resources.Theme theme) throws Resources.NotFoundException {
        IconObserver.INSTANCE.log(id); // exteraGram: icons shown while a pack is being edited (icon picker)
        IconManager iconManager = IconManager.INSTANCE;
        Drawable drawable = iconManager.getDrawable(id, density, theme, this);
        if (drawable != null) {
            return drawable;
        }
        try {
            int replaced = iconManager.getIcon(id);
            if (BuildVars.LOGS_ENABLED && replaced != id && loggedReplacements.add(replaced)) { // once per replacement
                FileLog.d("ExteraResources: " + safeName(id) + " -> " + safeName(replaced));
            }
            id = replaced;
        } catch (Throwable th) {
            FileLog.e(th);
        }
        return mResources.getDrawableForDensity(id, density, theme);
    }

    private static final java.util.Set<Integer> loggedReplacements = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private String safeName(int id) {
        try {
            return mResources.getResourceEntryName(id);
        } catch (Throwable ignore) {
            return Integer.toHexString(id);
        }
    }

    public Drawable getOriginalDrawable(int id) {
        try {
            return mResources.getDrawable(id, null);
        } catch (Resources.NotFoundException unused) {
            return null;
        }
    }
}
