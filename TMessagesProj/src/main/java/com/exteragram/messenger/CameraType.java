package com.exteragram.messenger;

public enum CameraType {
    CAMERA_1,
    CAMERA_2,
    CAMERA_X;

    public static java.util.List<CameraType> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private CameraType() {
    }

}
