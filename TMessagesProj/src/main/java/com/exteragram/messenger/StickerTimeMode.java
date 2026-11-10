package com.exteragram.messenger;

public enum StickerTimeMode {
    DEFAULT,
    SIDE,
    HIDDEN;

    public static java.util.List<StickerTimeMode> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private StickerTimeMode() {
    }

}
