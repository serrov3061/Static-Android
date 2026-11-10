package com.exteragram.messenger;

public enum GlassOutlineStyle {
    GLARE,
    SOLID,
    HIDDEN;

    public static java.util.List<GlassOutlineStyle> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private GlassOutlineStyle() {
    }

}
