package com.exteragram.messenger.translator.core;

import com.exteragram.messenger.translator.TranslatorUtils;

import com.exteragram.messenger.translator.FormattedTranslation;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class HttpTranslator extends BaseTranslator {

    /** @param html texts are HTML markup ({@link com.exteragram.messenger.translator.FormattedTranslation}); the provider must keep the tags */
    public abstract TranslatorHttp.Request buildRequest(List<String> texts, String fromLang, String toLang, boolean html) throws Exception;

    public abstract ProviderLimits getLimits();

    public abstract ProviderResponse parseResponse(TranslatorHttp.Response response, int expected) throws Exception;

    @Override
    public final void translate(String text, String fromLang, String toLang, TranslatorUtils.TranslateCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onFailed(TranslationError.Fatal.INSTANCE);
            return;
        }
        TranslationDispatcher.enqueue(this, 0L, Collections.singletonList(text), fromLang, toLang, true, (texts, error) -> {
            String result = texts != null && !texts.isEmpty() ? texts.get(0) : null;
            if (result != null && result.length() != 0) {
                callback.onSuccess(result);
                return;
            }
            callback.onFailed(error == null ? TranslationError.Fatal.INSTANCE : error);
        });
    }

    /** exteraGram 13.1.1: translates a text keeping its formatting (entities go through HTML markup). */
    public final void translate(String text, String fromLang, String toLang, ArrayList<TLRPC.MessageEntity> entities, TranslatorUtils.TranslateCallback callback) {
        TLRPC.TL_textWithEntities source = new TLRPC.TL_textWithEntities();
        source.text = text;
        source.entities = entities == null ? new ArrayList<>() : entities;
        FormattedTranslation.translate(0L, Collections.singletonList(source), fromLang, toLang, this, true, (results, error) -> {
            TLRPC.TL_textWithEntities result = results != null && !results.isEmpty() ? results.get(0) : null;
            if (result != null && result.text != null && result.text.length() != 0) {
                callback.onSuccess(result.text);
                callback.onSuccess(result);
                return;
            }
            callback.onFailed(error == null ? TranslationError.Fatal.INSTANCE : error);
        });
    }

    public final TranslationError httpError(TranslatorHttp.Response response) {
        if (response.code() == 429) {
            return new TranslationError.RateLimited(retryAfterMs(response));
        }
        int code = response.code();
        return (code >= 500 && code < 600) ? TranslationError.Transient.INSTANCE : TranslationError.Fatal.INSTANCE;
    }

    public final long retryAfterMs(TranslatorHttp.Response response) {
        String header = response.header("Retry-After");
        if (header == null) {
            return 0L;
        }
        try {
            return Long.parseLong(header.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
