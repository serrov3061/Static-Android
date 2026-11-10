package com.exteragram.messenger.icons.ui.picker;

import android.annotation.SuppressLint;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.ui.LaunchActivity;

/**
 * exteraGram IconPickerController: shows the floating icon picker ({@link IconPickerView}) over LaunchActivity while
 * an icon pack is being edited, and removes it when editing ends.
 */
public abstract class IconPickerController {

    @SuppressLint("StaticFieldLeak")
    private static IconPickerView pickerView;
    private static boolean isInitializing;

    public static boolean isActive() {
        return pickerView != null;
    }

    public static void onDestroy() {
        IconPickerView view = pickerView;
        if (view != null) {
            view.saveConfig();
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
        pickerView = null;
    }

    public static boolean onBackPressed(boolean invoked) {
        return pickerView != null && pickerView.onBackPressed(invoked);
    }

    public static void setActive(LaunchActivity activity, boolean active) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (active == (pickerView != null)) {
            return;
        }
        if (active) {
            showPicker(activity);
        } else {
            hidePicker(activity);
        }
    }

    private static void showPicker(LaunchActivity activity) {
        if (ExteraConfig.getEditingIconPackId() == null || pickerView != null || isInitializing) {
            return;
        }
        isInitializing = true;
        try {
            pickerView = new IconPickerView(activity);
            FrameLayout container = activity.getMainContainerFrameLayout();
            if (container != null) {
                container.addView(pickerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                pickerView.showFab();
            }
        } finally {
            isInitializing = false;
        }
    }

    private static void hidePicker(LaunchActivity activity) {
        if (pickerView == null) {
            return;
        }
        IconObserver.INSTANCE.clear();
        IconPickerView view = pickerView;
        pickerView = null;
        view.saveConfig();
        view.dismiss(() -> {
            FrameLayout container = activity.getMainContainerFrameLayout();
            if (container != null) {
                container.removeView(view);
            } else if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        });
    }
}
