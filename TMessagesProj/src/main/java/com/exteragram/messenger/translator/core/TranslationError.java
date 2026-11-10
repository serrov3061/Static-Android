package com.exteragram.messenger.translator.core;

/** Port of exteraGram's sealed TranslationError (Kotlin) to plain Java. */
public abstract class TranslationError {

    private TranslationError() {
    }

    public final boolean isRetryable() {
        return (this instanceof RateLimited) || (this instanceof Transient);
    }

    public static final class RateLimited extends TranslationError {
        private final long retryAfterMs;

        public RateLimited(long retryAfterMs) {
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof RateLimited && retryAfterMs == ((RateLimited) other).retryAfterMs);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(retryAfterMs);
        }

        @Override
        public String toString() {
            return "RateLimited(retryAfterMs=" + retryAfterMs + ")";
        }
    }

    public static final class Transient extends TranslationError {
        public static final Transient INSTANCE = new Transient();

        private Transient() {
        }

        @Override
        public String toString() {
            return "Transient";
        }
    }

    public static final class LanguageUnsupported extends TranslationError {
        public static final LanguageUnsupported INSTANCE = new LanguageUnsupported();

        private LanguageUnsupported() {
        }

        @Override
        public String toString() {
            return "LanguageUnsupported";
        }
    }

    public static final class Fatal extends TranslationError {
        public static final Fatal INSTANCE = new Fatal();

        private Fatal() {
        }

        @Override
        public String toString() {
            return "Fatal";
        }
    }

    public static final class Cancelled extends TranslationError {
        public static final Cancelled INSTANCE = new Cancelled();

        private Cancelled() {
        }

        @Override
        public String toString() {
            return "Cancelled";
        }
    }
}
