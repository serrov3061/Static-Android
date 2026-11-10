package com.exteragram.messenger;

public enum DividerStyle {
    HIDDEN,
    LINE,
    SEGMENTS;

    public static java.util.List<DividerStyle> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private DividerStyle() {
    }

}
