package com.exteragram.messenger.translator.providers;

import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.translator.core.BaseTranslator;

import java.util.Collections;
import java.util.Set;

/** Built-in Telegram translation: messages.translateText on our own server. */
public final class TelegramTranslator extends BaseTranslator {

    private static volatile TelegramTranslator instance;

    public static TelegramTranslator getInstance() {
        TelegramTranslator local = instance;
        if (local == null) {
            synchronized (TelegramTranslator.class) {
                local = instance;
                if (local == null) {
                    instance = local = new TelegramTranslator();
                }
            }
        }
        return local;
    }

    private TelegramTranslator() {
    }

    @Override
    public String getDisplayName() {
        return "Telegram";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Collections.emptySet();
    }

    @Override
    public void translate(String text, String fromLang, String toLang, TranslatorUtils.TranslateCallback callback) {
        TranslatorUtils.translateWithDefault(text, null, 0, toLang, null, callback);
    }
}
