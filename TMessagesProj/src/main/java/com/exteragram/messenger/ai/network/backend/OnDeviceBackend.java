package com.exteragram.messenger.ai.network.backend;

/** On-device backend (Gemini Nano) is not available in StaticGram, see {@link OnDeviceAvailability}. */
public class OnDeviceBackend implements Backend {

    @Override
    public void execute(BackendRequest request, BackendSink sink) {
        sink.onError(-1, "On-device AI is not available");
    }

    @Override
    public int getHistoryCharsLimit() {
        return 4000;
    }

    @Override
    public int getHistoryMessagesLimit() {
        return 8;
    }

    @Override
    public int getMaxOutputTokens() {
        return 256;
    }

    @Override
    public float getMaxTemperature() {
        return 1.0f;
    }

    @Override
    public boolean supportsImages() {
        return false;
    }
}
