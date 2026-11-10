package com.exteragram.messenger.config;

/** Bottom navigation bar mode: 0 = default, 1 = hidden, 2 = floating. Persisted as "bottomNavigationBarMode". */
public final class BottomNavigationBar {

    private static int mode;

    private BottomNavigationBar() {
    }

    public static int getMode() {
        mode = Math.max(0, Math.min(2, mode));
        return mode;
    }

    public static void setMode(int value) {
        mode = value;
        getMode();
    }

    public static boolean hidden() {
        return getMode() == 1;
    }

    public static boolean visible() {
        return getMode() != 1;
    }

    public static boolean floating() {
        return getMode() == 2;
    }
}
