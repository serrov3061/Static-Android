package com.exteragram.messenger.translator.providers;

import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.ProviderLimits;
import com.exteragram.messenger.translator.core.ProviderResponse;
import com.exteragram.messenger.translator.core.TranslationError;
import com.exteragram.messenger.translator.core.TranslatorHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Public Yandex Translate mobile endpoint (translate.yandex.net), as in exteraGram. */
public final class YandexTranslator extends HttpTranslator {

    private static final String uuid = UUID.randomUUID().toString().replace("-", "");
    private static final Set<String> SUPPORTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "az", "sq", "am", "en", "ar", "hy", "af", "eu", "ba", "be", "bn", "my", "bg", "bs", "cv", "cy", "hu", "vi", "ht", "gl", "nl", "mrj", "el", "ka", "gu", "da", "he", "yi", "id", "ga", "it", "is", "es", "kk", "kn", "ca", "ky", "zh", "ko", "xh", "km", "lo", "la", "lv", "lt", "lb", "mg", "ms", "ml", "mt", "mk", "mi", "mr", "mhr", "mn", "de", "ne", "no", "pa", "pap", "fa", "pl", "pt", "ro", "ru", "ceb", "sr", "si", "sk", "sl", "sw", "su", "tg", "th", "tl", "ta", "tt", "te", "tr", "udm", "uz", "uk", "ur", "fi", "fr", "hi", "hr", "cs", "sv", "gd", "et", "eo", "jv", "ja"
    )));

    private static volatile YandexTranslator instance;

    public static YandexTranslator getInstance() {
        YandexTranslator local = instance;
        if (local == null) {
            synchronized (YandexTranslator.class) {
                local = instance;
                if (local == null) {
                    instance = local = new YandexTranslator();
                }
            }
        }
        return local;
    }

    private YandexTranslator() {
    }

    @Override
    public String getDisplayName() {
        return "Yandex";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return SUPPORTED;
    }

    @Override
    public ProviderLimits getLimits() {
        return ProviderLimits.YANDEX;
    }

    @Override
    public TranslatorHttp.Request buildRequest(List<String> texts, String fromLang, String toLang, boolean html) throws Exception {
        StringBuilder form = new StringBuilder();
        form.append("lang=").append(URLEncoder.encode(toLang == null ? "" : toLang, "UTF-8"));
        form.append("&format=").append(html ? "html" : "plain");
        for (String text : texts) {
            form.append("&text=").append(URLEncoder.encode(text == null ? "" : text, "UTF-8"));
        }
        return TranslatorHttp.Request.post(
                "https://translate.yandex.net/api/v1/tr.json/translate?&srv=android&id=" + uuid + "-0-0",
                form.toString().getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded"
            )
            .header("User-Agent", "ru.yandex.translate/21.15.4.21402814 (Xiaomi Redmi K20 Pro; Android 11)");
    }

    @Override
    public ProviderResponse parseResponse(TranslatorHttp.Response response, int expected) throws Exception {
        String body = response.body();
        if (body.length() == 0) {
            return new ProviderResponse.Failure(response.isSuccessful() ? TranslationError.Transient.INSTANCE : httpError(response));
        }
        JSONObject json = new JSONObject(body);
        int code = json.optInt("code", response.isSuccessful() ? 200 : response.code());
        if (code != 200 || !json.has("text")) {
            TranslationError error;
            if (code == 429 || code == 1052 || response.code() == 429) {
                error = new TranslationError.RateLimited(retryAfterMs(response));
            } else if (code == 501) {
                error = TranslationError.LanguageUnsupported.INSTANCE;
            } else {
                int http = response.code();
                error = (http >= 500 && http < 600) ? TranslationError.Transient.INSTANCE : TranslationError.Fatal.INSTANCE;
            }
            return new ProviderResponse.Failure(error);
        }
        JSONArray text = json.getJSONArray("text");
        if (text.length() != expected) {
            return new ProviderResponse.Failure(TranslationError.Transient.INSTANCE);
        }
        ArrayList<String> result = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            result.add(text.optString(i));
        }
        return new ProviderResponse.Success(result);
    }
}
