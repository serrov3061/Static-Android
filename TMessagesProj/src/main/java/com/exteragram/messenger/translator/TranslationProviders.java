package com.exteragram.messenger.translator;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.translator.core.BaseTranslator;
import com.exteragram.messenger.translator.providers.GoogleTranslator;
import com.exteragram.messenger.translator.providers.MicrosoftTranslator;
import com.exteragram.messenger.translator.providers.TelegramTranslator;
import com.exteragram.messenger.translator.providers.YandexTranslator;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Translation providers. The order of {@link #ALL} is the index stored in ExteraConfig.translationProvider
 * (0 Telegram, 1 Google, 2 Yandex, 3 Microsoft) and must never change: backups depend on it.
 */
public final class TranslationProviders {

    public static final List<BaseTranslator> ALL = Collections.unmodifiableList(Arrays.asList(
        TelegramTranslator.getInstance(),
        GoogleTranslator.getInstance(),
        YandexTranslator.getInstance(),
        MicrosoftTranslator.getInstance()
    ));

    private TranslationProviders() {
    }

    public static int getLastIndex() {
        return ALL.size() - 1;
    }

    public static CharSequence[] names() {
        CharSequence[] names = new CharSequence[ALL.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ALL.get(i).getDisplayName();
        }
        return names;
    }

    /** Out-of-range index falls back to Google, exactly like exteraGram. */
    public static BaseTranslator current() {
        int index = ExteraConfig.getTranslationProvider();
        return (index < 0 || index >= ALL.size()) ? ALL.get(1) : ALL.get(index);
    }

    public static boolean isTelegram() {
        return current() instanceof TelegramTranslator;
    }

    public static boolean isAlternative() {
        return !isTelegram();
    }

    /** Chat translation is available with Premium, or for free with an alternative provider (exteraGram behaviour). */
    public static boolean isChatTranslationUnlocked(int currentAccount, long dialogId) {
        if (isChatTranslationUnlocked(currentAccount)) {
            return true;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return chat != null && chat.autotranslation;
    }

    public static boolean isChatTranslationUnlocked(int currentAccount) {
        return isAlternative() || UserConfig.getInstance(currentAccount).isPremium();
    }
}
