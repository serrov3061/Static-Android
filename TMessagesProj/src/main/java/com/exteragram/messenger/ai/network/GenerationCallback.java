package com.exteragram.messenger.ai.network;

/** Callbacks of {@link Client#getResponse}; always invoked on the UI thread. */
public interface GenerationCallback {
    /** Streaming: the whole text received so far (not a delta). */
    void onChunk(String text);

    void onError(int code, String message);

    void onResponse(String text);

    default void onThinking() {
    }
}
