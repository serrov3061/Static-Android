package com.exteragram.messenger;

public enum TabCounterMode {
    ALL,
    UNMUTED,
    HIDDEN;

    public static java.util.List<TabCounterMode> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private TabCounterMode() {
    }

}
