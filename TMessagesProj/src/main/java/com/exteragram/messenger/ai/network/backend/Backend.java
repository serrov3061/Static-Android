package com.exteragram.messenger.ai.network.backend;

public interface Backend {
    /** Runs the request synchronously on the caller (background) thread. */
    void execute(BackendRequest request, BackendSink sink) throws Exception;

    int getHistoryCharsLimit();

    int getHistoryMessagesLimit();

    int getMaxOutputTokens();

    float getMaxTemperature();

    boolean supportsImages();
}
