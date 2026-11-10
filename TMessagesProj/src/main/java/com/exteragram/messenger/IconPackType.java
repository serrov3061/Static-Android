package com.exteragram.messenger;

public enum IconPackType {
    DEFAULT,
    SOLAR,
    REMIX;

    public static java.util.List<IconPackType> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private IconPackType() {
    }

}
