package com.exteragram.messenger.icons.ui.picker;

import android.app.Activity;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * exteraGram IconObserver: while an icon pack is being edited (ExteraConfig.editingIconPackId), remembers which
 * drawables every fragment requested (ExteraResources.getDrawableForDensity) so the floating icon picker lists the
 * icons of the visible screens.
 */
public final class IconObserver {

    public static final IconObserver INSTANCE = new IconObserver();

    private final WeakHashMap<BaseFragment, Set<Integer>> iconSources = new WeakHashMap<>();

    private IconObserver() {
    }

    public void log(int resId) {
        if (ExteraConfig.getEditingIconPackId() == null) {
            return;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return;
        }
        Activity activity = fragment.getParentActivity();
        if (activity instanceof LaunchActivity && !IconPickerController.isActive()) {
            // StaticGram: drawables are also requested off the main thread and during layout; attach the picker later
            LaunchActivity launchActivity = (LaunchActivity) activity;
            AndroidUtilities.runOnUIThread(() -> IconPickerController.setActive(launchActivity, true));
        }
        synchronized (iconSources) {
            Set<Integer> set = iconSources.get(fragment);
            if (set == null) {
                set = new HashSet<>();
                iconSources.put(fragment, set);
            }
            set.add(resId);
        }
    }

    public void removeSource(BaseFragment owner) {
        synchronized (iconSources) {
            iconSources.remove(owner);
        }
    }

    public Set<Integer> getUsedIcons() {
        List<BaseFragment> visible = LaunchActivity.getVisibleFragments();
        HashSet<Integer> result = new HashSet<>();
        synchronized (iconSources) {
            for (BaseFragment fragment : visible) {
                Set<Integer> set = iconSources.get(fragment);
                if (set != null) {
                    result.addAll(set);
                }
            }
        }
        return result;
    }

    public void clear() {
        synchronized (iconSources) {
            iconSources.clear();
        }
    }
}
