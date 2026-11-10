package com.exteragram.messenger.translator.core;

import java.util.List;

public abstract class ProviderResponse {

    private ProviderResponse() {
    }

    public static final class Success extends ProviderResponse {
        private final List<String> texts;

        public Success(List<String> texts) {
            this.texts = texts;
        }

        public List<String> getTexts() {
            return texts;
        }
    }

    public static final class Failure extends ProviderResponse {
        private final TranslationError error;

        public Failure(TranslationError error) {
            this.error = error;
        }

        public TranslationError getError() {
            return error;
        }
    }
}
