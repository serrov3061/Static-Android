package com.exteragram.messenger.translator;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.widget.EditText;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.translator.core.BaseTranslator;
import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.TranslationError;
import com.exteragram.messenger.translator.providers.GoogleTranslator;
import com.exteragram.messenger.translator.providers.TelegramTranslator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Port of exteraGram's TranslatorUtils (target languages, provider routing, translate-before-send helpers). */
public abstract class TranslatorUtils {

    private static final String[] DEVICE_MODELS = {"Galaxy S6", "Galaxy S7", "Galaxy S8", "Galaxy S9", "Galaxy S10", "Galaxy S21", "Pixel 3", "Pixel 4", "Pixel 5", "OnePlus 6", "OnePlus 7", "OnePlus 8", "OnePlus 9", "Xperia XZ", "Xperia XZ2", "Xperia XZ3", "Xperia 1", "Xperia 5", "Xperia 10", "Xperia L4"};
    private static final String[] CHROME_VERSIONS = {"111.0.5563.57", "94.0.4606.81", "80.0.3987.119", "69.0.3497.100", "92.0.4515.159", "71.0.3578.99"};

    private static final LinkedHashMap<String, String> detectedLanguages = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 200;
        }
    };

    public interface TranslateCallback {
        void onFailed();

        default void onReqId(int reqId) {
        }

        default void onSuccess(String text) {
        }

        default void onSuccess(TLObject response, TLRPC.TL_error error) {
        }

        default void onSuccess(TLRPC.TL_textWithEntities text) {
        }

        default void onFailed(TranslationError error) {
            onFailed();
        }
    }

    // region language codes

    private static List<TranslateController.Language> getLanguages() {
        return new ArrayList<>(TranslateController.getLanguages());
    }

    public static String normalizeLanguageCode(String code) {
        if (TextUtils.isEmpty(code)) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.US).replace('_', '-');
        return "nb".equals(normalized) ? "no" : normalized;
    }

    public static String primaryLanguageOf(String code) {
        String normalized = normalizeLanguageCode(code);
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }
        int dash = normalized.indexOf('-');
        return dash >= 0 ? normalized.substring(0, dash) : normalized;
    }

    public static boolean isTargetLanguageFollowApp() {
        return "app".equalsIgnoreCase(ExteraConfig.getTargetLang());
    }

    /** Language is either the translation target or in the "do not translate" list. */
    public static boolean isRestrictedLanguage(String code) {
        if (TextUtils.isEmpty(code)) {
            return false;
        }
        String primary = primaryLanguageOf(code);
        if (TextUtils.isEmpty(primary)) {
            return false;
        }
        if (TextUtils.equals(primary, primaryLanguageOf(getResolvedTargetLanguageCode()))) {
            return true;
        }
        for (String restricted : RestrictedLanguagesSelectActivity.getRestrictedLanguages()) {
            if (TextUtils.equals(primary, primaryLanguageOf(restricted))) {
                return true;
            }
        }
        return false;
    }

    private static TranslateController.Language createLanguageItem(String code) {
        String normalized = normalizeLanguageCode(code);
        TranslateController.Language language = new TranslateController.Language();
        language.code = normalized;
        Locale current = LocaleController.getInstance().getCurrentLocale();
        if (current == null) {
            current = Locale.getDefault();
        }
        Locale locale = Locale.forLanguageTag(normalized == null ? "" : normalized);
        String displayName = locale.getDisplayName(current);
        String ownDisplayName = locale.getDisplayName(locale);
        if (TextUtils.isEmpty(displayName)) {
            displayName = TranslateAlert2.capitalFirst(TranslateAlert2.languageName(normalized));
        }
        if (TextUtils.isEmpty(ownDisplayName)) {
            ownDisplayName = TranslateAlert2.capitalFirst(TranslateAlert2.systemLanguageName(normalized, true));
        }
        if (TextUtils.isEmpty(displayName)) {
            displayName = normalized == null ? "" : normalized.toUpperCase(Locale.US);
        }
        if (TextUtils.isEmpty(ownDisplayName)) {
            ownDisplayName = displayName;
        }
        language.displayName = TranslateAlert2.capitalFirst(displayName);
        language.ownDisplayName = TranslateAlert2.capitalFirst(ownDisplayName);
        language.q = (language.displayName + " " + language.ownDisplayName).toLowerCase(Locale.US);
        return language;
    }

    /** Languages the current provider can translate into. */
    public static ArrayList<TranslateController.Language> getCurrentTargetLanguages() {
        BaseTranslator translator = getCurrentTranslator();
        Set<String> supported = translator.getSupportedLanguages();
        if (supported == null || supported.isEmpty()) {
            ArrayList<TranslateController.Language> list = new ArrayList<>(TranslateController.getLanguages());
            if (translator == TelegramTranslator.getInstance()) {
                list.removeIf(l -> l == null || TextUtils.isEmpty(l.code) || l.code.contains("-") || l.code.contains("_"));
            }
            return list;
        }
        HashSet<String> codes = new HashSet<>();
        for (String code : supported) {
            String normalized = normalizeLanguageCode(code);
            if (!TextUtils.isEmpty(normalized)) {
                codes.add(normalized);
            }
        }
        ArrayList<TranslateController.Language> list = new ArrayList<>();
        for (String code : codes) {
            list.add(createLanguageItem(code));
        }
        list.sort((a, b) -> (a.displayName == null ? "" : a.displayName).compareToIgnoreCase(b.displayName == null ? "" : b.displayName));
        return list;
    }

    private static String getResolvedAppLanguageCode() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        String language = normalizeLanguageCode(locale.getLanguage());
        String country = locale.getCountry();
        if (!TextUtils.isEmpty(language) && !TextUtils.isEmpty(country)) {
            String withCountry = normalizeLanguageCode(language + "-" + country);
            if (isTargetLanguageSupportedForCurrentProvider(withCountry)) {
                return withCountry;
            }
        }
        if (!isTargetLanguageSupportedForCurrentProvider(language)) {
            String fallback = normalizeLanguageCode(LocaleController.getString(R.string.LanguageCode));
            if (isTargetLanguageSupportedForCurrentProvider(fallback)) {
                return fallback;
            }
        }
        return language;
    }

    public static String getResolvedTargetLanguageCode(String code) {
        if ("app".equalsIgnoreCase(code)) {
            return getResolvedAppLanguageCode();
        }
        String normalized = normalizeLanguageCode(code);
        return TextUtils.isEmpty(normalized) ? getResolvedAppLanguageCode() : normalized;
    }

    public static String getResolvedTargetLanguageCode() {
        return getResolvedTargetLanguageCode(ExteraConfig.getTargetLang());
    }

    public static CharSequence[] getTargetLanguageTitles() {
        List<TranslateController.Language> languages = getCurrentTargetLanguages();
        CharSequence[] titles = new CharSequence[languages.size() + 1];
        titles[0] = LocaleController.getString(R.string.TranslationTargetApp);
        for (int i = 0; i < languages.size(); i++) {
            TranslateController.Language language = languages.get(i);
            titles[i + 1] = language.displayName + (language.ownDisplayName == null ? "" : " – " + language.ownDisplayName);
        }
        return titles;
    }

    public static int getTargetLanguageIndexByCode(String code) {
        if ("app".equalsIgnoreCase(code)) {
            return 0;
        }
        List<TranslateController.Language> languages = getCurrentTargetLanguages();
        String resolved = getResolvedTargetLanguageCode(code);
        for (int i = 0; i < languages.size(); i++) {
            if (TextUtils.equals(languages.get(i).code, resolved)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static String getTargetLanguageCodeByIndex(int index) {
        if (index == 0) {
            return "app";
        }
        List<TranslateController.Language> languages = getCurrentTargetLanguages();
        int i = index - 1;
        if (i < 0 || i >= languages.size()) {
            return null;
        }
        return languages.get(i).code;
    }

    public static String getTargetLanguageTitle() {
        if (isTargetLanguageFollowApp()) {
            return LocaleController.getString(R.string.TranslationTargetApp);
        }
        return getLanguageTitleSystem(getResolvedTargetLanguageCode());
    }

    public static void setTargetLanguage(String code) {
        String normalized = "app".equalsIgnoreCase(code) ? "app" : normalizeLanguageCode(code);
        String value = TextUtils.isEmpty(normalized) ? "app" : normalized;
        ExteraConfig.setTargetLang(value);
        if (MessagesController.getGlobalMainSettings().getBoolean("translate_button_restricted_languages_changed", false)) {
            return;
        }
        // "Do not translate" was never customized: recompute its default from the new target language.
        MessagesController.getGlobalMainSettings().edit().remove("translate_button_restricted_languages").apply();
        RestrictedLanguagesSelectActivity.invalidateRestrictedLanguages();
        RestrictedLanguagesSelectActivity.checkRestrictedLanguages(false);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            try {
                if (!UserConfig.getInstance(i).isClientActivated()) {
                    continue;
                }
                MessagesController.getInstance(i).getTranslateController().checkRestrictedLanguagesUpdate();
            } catch (Exception ignore) {
            }
        }
    }

    public static boolean isTargetLanguageSupportedForCurrentProvider(String code) {
        if ("app".equalsIgnoreCase(code)) {
            return true;
        }
        String normalized = normalizeLanguageCode(code);
        if (TextUtils.isEmpty(normalized)) {
            return false;
        }
        BaseTranslator translator = getCurrentTranslator();
        Set<String> supported = translator.getSupportedLanguages();
        if (supported == null || supported.isEmpty()) {
            return !(translator == TelegramTranslator.getInstance() && normalized.contains("-"));
        }
        for (String s : supported) {
            if (TextUtils.equals(normalizeLanguageCode(s), normalized)) {
                return true;
            }
        }
        return false;
    }

    public static void ensureTargetLanguageCompatibleWithProvider() {
        if (!isTargetLanguageSupportedForCurrentProvider(ExteraConfig.getTargetLang())) {
            setTargetLanguage("app");
        }
    }

    public static String getLanguageTitleSystem(String code) {
        if ("none".equals(code)) {
            return LocaleController.getString(R.string.None);
        }
        for (TranslateController.Language language : getLanguages()) {
            if (TextUtils.equals(language.code, code)) {
                return language.displayName;
            }
        }
        String normalized = normalizeLanguageCode(code);
        return TextUtils.isEmpty(normalized) ? "" : normalized.toUpperCase(Locale.US);
    }

    /**
     * "Title - own name" options for the codes with ONE language-list build
     * (TranslateController.getLanguages() localizes and sorts the whole list on every call).
     */
    public static CharSequence[] getLanguageOptions(List<String> codes) {
        final List<TranslateController.Language> languages = getLanguages();
        final CharSequence[] result = new CharSequence[codes.size()];
        for (int i = 0; i < result.length; i++) {
            final String code = codes.get(i);
            String title = null;
            String own = null;
            if ("none".equals(code)) {
                title = LocaleController.getString(R.string.None);
            } else {
                for (TranslateController.Language language : languages) {
                    if (TextUtils.equals(language.code, code)) {
                        title = language.displayName;
                        own = language.ownDisplayName;
                        break;
                    }
                }
                if (title == null) {
                    final String normalized = normalizeLanguageCode(code);
                    title = TextUtils.isEmpty(normalized) ? "" : normalized.toUpperCase(Locale.US);
                }
            }
            result[i] = own == null ? title : title + " - " + own;
        }
        return result;
    }

    public static String getLanguageDisplayName(String code) {
        for (TranslateController.Language language : getLanguages()) {
            if (TextUtils.equals(language.code, code)) {
                return language.ownDisplayName;
            }
        }
        return null;
    }

    // endregion

    // region translate before send

    private static String getStoredSendTargetLanguage() {
        return ExteraConfig.getPreferences().getString("targetLangSend", null);
    }

    private static void storeRecentSendTargetLanguage(String code) {
        String normalized = normalizeLanguageCode(code);
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        ArrayList<String> recent = getRecentSendTargetLanguages();
        recent.removeIf(s -> TextUtils.equals(s, normalized));
        recent.add(0, normalized);
        while (recent.size() > 3) {
            recent.remove(recent.size() - 1);
        }
        ExteraConfig.getEditor().putString("targetLangSendRecent", TextUtils.join(",", recent)).apply();
    }

    public static ArrayList<String> getRecentSendTargetLanguages() {
        ArrayList<String> result = new ArrayList<>();
        String stored = ExteraConfig.getPreferences().getString("targetLangSendRecent", null);
        if (!TextUtils.isEmpty(stored)) {
            HashSet<String> seen = new HashSet<>();
            for (String s : stored.split(",")) {
                String normalized = normalizeLanguageCode(s);
                if (!TextUtils.isEmpty(normalized) && !seen.contains(normalized) && isTargetLanguageSupportedForCurrentProvider(normalized)) {
                    seen.add(normalized);
                    result.add(normalized);
                    if (result.size() >= 3) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    public static String getResolvedSendTargetLanguageCode() {
        String stored = getStoredSendTargetLanguage();
        if (TextUtils.isEmpty(stored)) {
            return "en";
        }
        return getResolvedTargetLanguageCode(stored);
    }

    public static int getSendTargetLanguageIndex() {
        String stored = getStoredSendTargetLanguage();
        return getTargetLanguageIndexByCode(TextUtils.isEmpty(stored) ? "en" : stored);
    }

    public static String getSendTargetLanguageTitle() {
        return getLanguageTitleSystem(getResolvedSendTargetLanguageCode());
    }

    public static boolean isSendTargetLanguageFollowApp() {
        return "app".equalsIgnoreCase(getStoredSendTargetLanguage());
    }

    public static void setSendTargetLanguage(String code) {
        String normalized = "app".equalsIgnoreCase(code) ? "app" : normalizeLanguageCode(code);
        if (TextUtils.isEmpty(normalized)) {
            ExteraConfig.getEditor().remove("targetLangSend").apply();
            return;
        }
        if (!TextUtils.equals(normalized, "app")) {
            storeRecentSendTargetLanguage(normalized);
        }
        if (TextUtils.equals(normalized, "en")) {
            ExteraConfig.getEditor().remove("targetLangSend").apply();
        } else {
            ExteraConfig.getEditor().putString("targetLangSend", normalized).apply();
        }
    }

    /**
     * Translates the text of an input field into the "translate before send" language and replaces the field text.
     * Used by {@link com.exteragram.messenger.components.TranslateBeforeSendWrapper} subclasses in the send menus.
     *
     * @param onFailed called on the UI thread when translation fails (show an error bulletin there)
     */
    public static void translateFieldBeforeSend(EditText field, int currentAccount, boolean supportsNewEntities, Runnable onSuccess, Runnable onFailed) {
        if (field == null || TextUtils.isEmpty(field.getText())) {
            return;
        }
        final AlertDialog progress = new AlertDialog(field.getContext(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(150);
        CharSequence[] message = {field.getText()};
        translate(message[0], getResolvedSendTargetLanguageCode(), MediaDataController.getInstance(currentAccount).getEntities(message, supportsNewEntities), new TranslateCallback() {
            @Override
            public void onSuccess(TLRPC.TL_textWithEntities text) {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                SpannableStringBuilder builder = SpannableStringBuilder.valueOf(text.text);
                MessageObject.addEntitiesToText(builder, text.entities, true, true, false, true);
                field.setText(builder);
                field.setSelection(builder.length());
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }

            @Override
            public void onFailed() {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (onFailed != null) {
                    onFailed.run();
                }
            }
        });
    }

    // endregion

    public static String formatUserAgent() {
        String android = String.valueOf(Utilities.random.nextInt(7) + 6);
        String device = DEVICE_MODELS[Utilities.random.nextInt(DEVICE_MODELS.length)];
        String chrome = CHROME_VERSIONS[Utilities.random.nextInt(CHROME_VERSIONS.length)];
        return String.format("Mozilla/5.0 (Linux; Android %s; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s Mobile Safari/537.36", android, device, chrome);
    }

    // region message language detection

    public static String getDetectedLanguage(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        if (messageObject.messageOwner != null && !TextUtils.isEmpty(messageObject.messageOwner.originalLanguage)) {
            return messageObject.messageOwner.originalLanguage;
        }
        return detectedLanguages.get(detectedLanguageKey(messageObject));
    }

    public static boolean isMessageLanguageRestricted(MessageObject messageObject) {
        String language = getDetectedLanguage(messageObject);
        return language != null && !"und".equals(language) && isRestrictedLanguage(language);
    }

    public static void detectMessageLanguage(MessageObject messageObject, MessageObject.GroupedMessages group, Utilities.Callback<String> callback) {
        if (messageObject == null) {
            if (callback != null) {
                callback.run(null);
            }
            return;
        }
        String detected = getDetectedLanguage(messageObject);
        if (detected != null) {
            if (callback != null) {
                callback.run(detected);
            }
            return;
        }
        CharSequence text = getMessageText(messageObject, group);
        if (!TextUtils.isEmpty(text) && LanguageDetector.hasSupport()) {
            final String key = detectedLanguageKey(messageObject);
            LanguageDetector.detectLanguage(text.toString(), lng -> AndroidUtilities.runOnUIThread(() -> {
                String result = TextUtils.isEmpty(lng) ? "und" : lng;
                detectedLanguages.put(key, result);
                if (callback != null) {
                    callback.run(result);
                }
            }), e -> AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) {
                    callback.run(null);
                }
            }));
        } else if (callback != null) {
            callback.run(null);
        }
    }

    private static String detectedLanguageKey(MessageObject messageObject) {
        return messageObject.getDialogId() + ":" + messageObject.getId();
    }

    /** Text to translate for a message (caption of an album, poll with options, or plain text). */
    public static CharSequence getMessageText(MessageObject messageObject, MessageObject.GroupedMessages group) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.translated || messageObject.isRestrictedMessage) {
            return null;
        }
        int type = messageObject.type;
        if (type == MessageObject.TYPE_EMOJIS || type == MessageObject.TYPE_ANIMATED_STICKER || type == MessageObject.TYPE_STICKER) {
            return null;
        }
        CharSequence text = null;
        MessageObject captionHolder = group != null ? group.findCaptionMessageObject() : null;
        if (captionHolder != null && !TextUtils.isEmpty(captionHolder.messageOwner.message)) {
            text = captionHolder.messageOwner.message;
        } else if (!TextUtils.isEmpty(messageObject.messageOwner.message)) {
            text = messageObject.messageOwner.message;
        } else if (messageObject.isPoll()) {
            try {
                TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
                StringBuilder sb = new StringBuilder(poll.question.text);
                sb.append("\n");
                for (TLRPC.PollAnswer answer : poll.answers) {
                    sb.append("\n🔘 ");
                    sb.append(answer.text == null ? "" : answer.text.text);
                }
                text = sb.toString();
            } catch (Exception ignore) {
            }
        }
        return text;
    }

    public static void translateWithAlert(MessageObject messageObject, MessageObject.GroupedMessages group, TLRPC.InputPeer peer, int msgId, BaseFragment fragment) {
        translateWithAlert(messageObject, group, peer, msgId, fragment, null);
    }

    /** Opens TranslateAlert2 for a message with auto-detected source language. */
    public static void translateWithAlert(MessageObject messageObject, MessageObject.GroupedMessages group, TLRPC.InputPeer peer, int msgId, BaseFragment fragment, Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress) {
        if (messageObject == null || fragment == null || fragment.getContext() == null) {
            return;
        }
        final ChatActivity chatActivity = fragment instanceof ChatActivity ? (ChatActivity) fragment : null;
        final ArrayList<TLRPC.MessageEntity> entities = messageObject.messageOwner != null ? messageObject.messageOwner.entities : null;
        final CharSequence text = getMessageText(messageObject, group);
        detectMessageLanguage(messageObject, group, lng -> {
            if (fragment.getContext() == null) {
                return;
            }
            TranslateAlert2.showAlert(fragment.getContext(), fragment, UserConfig.selectedAccount, peer, msgId, false, "und".equals(lng) ? null : lng, TranslateAlert2.getToLanguage(), text, entities, false, onLinkPress, () -> {
                if (chatActivity != null) {
                    chatActivity.dimBehindView(false);
                }
            });
        });
    }

    // endregion

    // region translation routing

    /** Telegram server translation (messages.translateText), with Telegram's own TRANSLATIONS_DISABLED_ALT fallback. */
    public static void translateWithDefault(CharSequence text, TLRPC.InputPeer peer, int msgId, String toLang, ArrayList<TLRPC.MessageEntity> entities, TranslateCallback callback) {
        final String target = getResolvedTargetLanguageCode(toLang);
        TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        final TLRPC.TL_textWithEntities source = new TLRPC.TL_textWithEntities();
        source.text = text == null ? "" : text.toString();
        if (entities != null) {
            source.entities = entities;
        }
        if (peer != null) {
            req.flags |= 1;
            req.peer = peer;
            req.id.add(msgId);
        } else {
            req.flags |= 2;
            req.text.add(source);
        }
        String lang = target != null ? target.trim() : null;
        if ("nb".equals(lang)) {
            lang = "no";
        }
        req.to_lang = lang;
        callback.onReqId(ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(req, (res, err) -> {
            if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {
                // The server asks clients to use the alternative (Google) translation, as stock Telegram does.
                GoogleTranslator.getInstance().translate(source.text, "auto", target, entities, callback);
                return;
            }
            if (res instanceof TLRPC.TL_messages_translateResult) {
                TLRPC.TL_messages_translateResult result = (TLRPC.TL_messages_translateResult) res;
                if (!result.result.isEmpty() && result.result.get(0) != null && result.result.get(0).text != null) {
                    final TLRPC.TL_textWithEntities received = result.result.get(0);
                    final TLRPC.TL_textWithEntities processed = TranslateAlert2.preprocess(source, received);
                    final String resultText = processed != null && processed.text != null ? processed.text : received.text;
                    if (TextUtils.isEmpty(resultText)) {
                        AndroidUtilities.runOnUIThread(callback::onFailed);
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        callback.onSuccess(result, err);
                        callback.onSuccess(processed != null ? processed : received);
                        callback.onSuccess(resultText);
                    });
                    return;
                }
            }
            AndroidUtilities.runOnUIThread(callback::onFailed);
        }));
    }

    /** Translate with language auto-detection through the current provider. */
    public static void translate(CharSequence text, String toLang, ArrayList<TLRPC.MessageEntity> entities, TranslateCallback callback) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        final String target = getResolvedTargetLanguageCode(toLang);
        if (LanguageDetector.hasSupport()) {
            LanguageDetector.detectLanguage(text.toString(),
                lng -> translate(text, lng == null || "und".equals(lng) ? "auto" : lng, target, entities, callback),
                e -> translate(text, "auto", target, entities, callback));
        } else {
            translate(text, "auto", target, entities, callback);
        }
    }

    public static void translate(CharSequence text, String fromLang, String toLang, TranslateCallback callback) {
        translate(text, fromLang, toLang, null, callback);
    }

    public static void translate(CharSequence text, String fromLang, String toLang, ArrayList<TLRPC.MessageEntity> entities, TranslateCallback callback) {
        BaseTranslator translator = getCurrentTranslator();
        if (translator == TelegramTranslator.getInstance()) {
            translateWithDefault(text, null, 0, toLang, entities, callback);
            return;
        }
        if (!translator.isLanguageSupported(toLang)) {
            // the user already chose an external provider; fall back to Google for unsupported targets (exteraGram)
            translator = GoogleTranslator.getInstance();
        }
        if (translator instanceof HttpTranslator && entities != null && !entities.isEmpty()) {
            // exteraGram 13.1.1: keep the formatting
            ((HttpTranslator) translator).translate(text.toString(), fromLang, toLang, entities, callback);
            return;
        }
        translator.translate(text.toString(), fromLang, toLang, new TranslateCallback() {
            @Override
            public void onSuccess(String result) {
                if (TextUtils.isEmpty(result)) {
                    callback.onFailed();
                    return;
                }
                callback.onSuccess(result);
                TLRPC.TL_textWithEntities resultText = new TLRPC.TL_textWithEntities();
                resultText.text = result;
                callback.onSuccess(resultText);
            }

            @Override
            public void onSuccess(TLObject response, TLRPC.TL_error error) {
                callback.onSuccess(response, error);
            }

            @Override
            public void onFailed() {
                callback.onFailed();
            }

            @Override
            public void onFailed(TranslationError error) {
                callback.onFailed(error);
            }

            @Override
            public void onReqId(int reqId) {
                callback.onReqId(reqId);
            }
        });
    }

    public static BaseTranslator getCurrentTranslator() {
        return TranslationProviders.current();
    }

    public static boolean isAlternativeProvider() {
        return TranslationProviders.isAlternative();
    }

    public static String getCurrentTranslatorName() {
        return getCurrentTranslator().getDisplayName();
    }

    // endregion
}
