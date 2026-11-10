package com.exteragram.messenger.translator.providers;

import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.ProviderLimits;
import com.exteragram.messenger.translator.core.ProviderResponse;
import com.exteragram.messenger.translator.core.TranslationError;
import com.exteragram.messenger.translator.core.TranslatorHttp;

import org.json.JSONArray;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Public Google Translate endpoint (translate.googleapis.com, client=gtx), as in exteraGram. */
public final class GoogleTranslator extends HttpTranslator {

    private static volatile GoogleTranslator instance;

    public static GoogleTranslator getInstance() {
        GoogleTranslator local = instance;
        if (local == null) {
            synchronized (GoogleTranslator.class) {
                local = instance;
                if (local == null) {
                    instance = local = new GoogleTranslator();
                }
            }
        }
        return local;
    }

    private GoogleTranslator() {
    }

    @Override
    public String getDisplayName() {
        return "Google";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Collections.emptySet();
    }

    @Override
    public ProviderLimits getLimits() {
        return ProviderLimits.GOOGLE;
    }

    @Override
    public TranslatorHttp.Request buildRequest(List<String> texts, String fromLang, String toLang, boolean html) throws Exception {
        // exteraGram 13.1.1: texts go in a POST form body (long HTML markup does not fit into the URL)
        String url = "https://translate.googleapis.com/translate_a/t?client=gtx"
            + "&sl=" + enc(fromLang)
            + "&tl=" + enc(toLang)
            + "&dt=t&format=" + (html ? "html" : "text") + "&ie=UTF-8&oe=UTF-8";
        StringBuilder form = new StringBuilder();
        for (String text : texts) {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append("q=").append(enc(text));
        }
        return TranslatorHttp.Request.post(url, form.toString().getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded")
            .header("User-Agent", TranslatorUtils.formatUserAgent());
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    @Override
    public ProviderResponse parseResponse(TranslatorHttp.Response response, int expected) throws Exception {
        if (!response.isSuccessful()) {
            return new ProviderResponse.Failure(httpError(response));
        }
        String body = response.body();
        if (body.length() == 0) {
            return new ProviderResponse.Failure(TranslationError.Transient.INSTANCE);
        }
        JSONArray array = new JSONArray(body);
        ArrayList<String> result = new ArrayList<>(expected);
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            result.add(item instanceof JSONArray ? ((JSONArray) item).optString(0) : item.toString());
        }
        if (result.size() != expected) {
            return new ProviderResponse.Failure(TranslationError.Transient.INSTANCE);
        }
        return new ProviderResponse.Success(result);
    }
}
