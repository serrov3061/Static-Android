package com.exteragram.messenger;

public enum TransitionAnimation {
    DEFAULT,
    AOSP,
    SPRING;

    public static java.util.List<TransitionAnimation> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private TransitionAnimation() {
    }

}
