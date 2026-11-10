package org.telegram.ui.Components;

import android.app.Dialog;
import android.os.SystemClock;
import android.view.View;

import org.telegram.ui.ActionBar.BottomSheet;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * StaticGram: "only one sheet at a time" guard for screens where several taps in the same moment
 * (two fingers, spam taps, a tap while a long-press menu is popping up) used to open several
 * sheets / menus on top of each other (profile gifts list, Stars screen buttons).
 *
 * Usage (main thread only):
 * <pre>
 *     if (!SheetOpenGuard.tryOpen()) return;   // something is open or is being opened
 *     SheetOpenGuard.track(sheet);             // after sheet.show()
 * </pre>
 * A tap is refused while a tracked dialog (with window focus) / menu is still showing or within {@link #DEBOUNCE_MS}
 * after the previous accepted tap (covers the gap between the tap and the window really being
 * shown, and sheets opened asynchronously). Nothing has to be reset by hand: a tracked dialog
 * releases the guard as soon as it is dismissed (isShowing() == false), so existing
 * OnDismissListeners of the sheets are left untouched.
 */
public final class SheetOpenGuard {

    public static final long DEBOUNCE_MS = 600;

    private static long lastOpenTime;
    private static final ArrayList<WeakReference<Dialog>> dialogs = new ArrayList<>();
    private static final ArrayList<WeakReference<ItemOptions>> menus = new ArrayList<>();

    private SheetOpenGuard() {
    }

    public static boolean isBusy() {
        if (SystemClock.elapsedRealtime() - lastOpenTime < DEBOUNCE_MS) {
            return true;
        }
        boolean busy = false;
        for (int i = dialogs.size() - 1; i >= 0; --i) {
            final Dialog d = dialogs.get(i).get();
            if (d instanceof BottomSheet && ((BottomSheet) d).attachedFragment != null) {
                // attached sheets live inside the activity window, not in a Dialog window
                final BottomSheet sheet = (BottomSheet) d;
                final View container = sheet.getContainerView();
                if (sheet.isDismissed() || container == null || !container.isAttachedToWindow()) {
                    dialogs.remove(i);
                } else {
                    busy = true;
                }
            } else if (d == null || !d.isShowing()) {
                dialogs.remove(i);
            } else if (hasFocus(d)) {
                // a sheet left showing under a newer screen/window (e.g. a profile opened
                // from it) does not block taps there
                busy = true;
            }
        }
        for (int i = menus.size() - 1; i >= 0; --i) {
            final ItemOptions o = menus.get(i).get();
            if (o == null || !o.isShown()) {
                menus.remove(i);
            } else {
                busy = true;
            }
        }
        return busy;
    }

    private static boolean hasFocus(Dialog d) {
        try {
            return d.getWindow() == null || d.getWindow().getDecorView().hasWindowFocus();
        } catch (Exception e) {
            return true;
        }
    }

    /** @return true when the caller may open its sheet now (and marks the guard as taken). */
    public static boolean tryOpen() {
        if (isBusy()) {
            return false;
        }
        lastOpenTime = SystemClock.elapsedRealtime();
        return true;
    }

    /** Keep the guard taken while this dialog is showing. Call after show(). */
    public static <T extends Dialog> T track(T dialog) {
        if (dialog != null) {
            dialogs.add(new WeakReference<>(dialog));
            lastOpenTime = SystemClock.elapsedRealtime();
        }
        return dialog;
    }

    /** Keep the guard taken while this popup menu is showing. Call after show(). */
    public static ItemOptions track(ItemOptions menu) {
        if (menu != null) {
            menus.add(new WeakReference<>(menu));
            lastOpenTime = SystemClock.elapsedRealtime();
        }
        return menu;
    }
}
