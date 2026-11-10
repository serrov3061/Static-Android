package com.exteragram.messenger.translator.core;

import com.exteragram.messenger.translator.TranslatorUtils;

import java.util.Locale;
import java.util.Set;

public abstract class BaseTranslator {

    public abstract String getDisplayName();

    /** Empty set means "any language". */
    public abstract Set<String> getSupportedLanguages();

    public abstract void translate(String text, String fromLang, String toLang, TranslatorUtils.TranslateCallback callback);

    public final boolean isLanguageSupported(String lang) {
        Set<String> supported = getSupportedLanguages();
        if (supported == null || supported.isEmpty()) {
            return true;
        }
        String primary = TranslatorUtils.primaryLanguageOf(lang);
        if (primary == null) {
            return false;
        }
        for (String code : supported) {
            String lower = code.toLowerCase(Locale.US);
            int dash = lower.indexOf('-');
            if ((dash >= 0 ? lower.substring(0, dash) : lower).equals(primary)) {
                return true;
            }
        }
        return false;
    }
}
