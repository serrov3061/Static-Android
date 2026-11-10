package com.exteragram.messenger;

public enum AvatarCornerType {
    DEFAULT,
    FORUM,
    COMMUNITY;

    public static java.util.List<AvatarCornerType> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private AvatarCornerType() {
    }

}
