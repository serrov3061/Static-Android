package com.exteragram.messenger.ai.network.backend;

public interface BackendSink {
    boolean isActive();

    void onCancellable(Runnable cancel);

    void onChunk(String text);

    void onComplete(String text);

    void onError(int code, String message);

    void onThinking();
}
