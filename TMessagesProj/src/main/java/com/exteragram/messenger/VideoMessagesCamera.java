package com.exteragram.messenger;

public enum VideoMessagesCamera {
    FRONT,
    REAR,
    ASK;

    public static java.util.List<VideoMessagesCamera> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private VideoMessagesCamera() {
    }

}
