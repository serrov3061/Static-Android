package com.exteragram.messenger.translator.core;

/** Per-provider batching and rate limits (values copied from exteraGram). */
public final class ProviderLimits {

    public static final ProviderLimits GOOGLE = new ProviderLimits(20, 5000, 8000, 2, 0, 4, 1000, 30000);
    public static final ProviderLimits YANDEX = new ProviderLimits(20, 5000, 5000, 2, 0, 4, 1000, 30000);
    public static final ProviderLimits MICROSOFT = new ProviderLimits(20, 5000, 10000, 4, 0, 4, 1000, 30000);

    private final int maxTextsPerRequest;
    private final int maxCharsPerText;
    private final int maxCharsPerRequest;
    private final int maxConcurrent;
    private final long minIntervalMs;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public ProviderLimits(int maxTextsPerRequest, int maxCharsPerText, int maxCharsPerRequest, int maxConcurrent, long minIntervalMs, int maxAttempts, long baseBackoffMs, long maxBackoffMs) {
        this.maxTextsPerRequest = maxTextsPerRequest;
        this.maxCharsPerText = maxCharsPerText;
        this.maxCharsPerRequest = maxCharsPerRequest;
        this.maxConcurrent = maxConcurrent;
        this.minIntervalMs = minIntervalMs;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    public int getMaxTextsPerRequest() {
        return maxTextsPerRequest;
    }

    public int getMaxCharsPerText() {
        return maxCharsPerText;
    }

    public int getMaxCharsPerRequest() {
        return maxCharsPerRequest;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public long getMinIntervalMs() {
        return minIntervalMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getBaseBackoffMs() {
        return baseBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }
}
