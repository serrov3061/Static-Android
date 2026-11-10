package com.exteragram.messenger;

public enum TabIconsMode {
    ICONS_AND_TITLES,
    TITLES_ONLY,
    ICONS_ONLY;

    public static java.util.List<TabIconsMode> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private TabIconsMode() {
    }

}
