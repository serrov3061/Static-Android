package com.exteragram.messenger.translator.providers;

import android.util.Base64;

import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.ProviderLimits;
import com.exteragram.messenger.translator.core.ProviderResponse;
import com.exteragram.messenger.translator.core.TranslationError;
import com.exteragram.messenger.translator.core.TranslatorHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Microsoft Translator via the public endpoint of the Microsoft Translator Android app
 * (api.cognitive.microsofttranslator.com, X-Mt-Signature HMAC-SHA256). Key and signature format are taken verbatim
 * from exteraGram (smali MicrosoftTranslator.signature).
 */
public final class MicrosoftTranslator extends HttpTranslator {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final String SIGNATURE_KEY = "oik6PdDdMnOXemTbwvMn9de/h9lFnfBaCWbGMMZqqoSaQaqUOqjVGm5NqsmjcBI1x+sS9ugjB55HEJWRiFXYFw";

    private static volatile MicrosoftTranslator instance;

    public static MicrosoftTranslator getInstance() {
        MicrosoftTranslator local = instance;
        if (local == null) {
            synchronized (MicrosoftTranslator.class) {
                local = instance;
                if (local == null) {
                    instance = local = new MicrosoftTranslator();
                }
            }
        }
        return local;
    }

    private MicrosoftTranslator() {
    }

    @Override
    public String getDisplayName() {
        return "Microsoft";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Collections.emptySet();
    }

    @Override
    public ProviderLimits getLimits() {
        return ProviderLimits.MICROSOFT;
    }

    @Override
    public TranslatorHttp.Request buildRequest(List<String> texts, String fromLang, String toLang, boolean html) throws Exception {
        if ("auto".equalsIgnoreCase(fromLang)) {
            fromLang = "";
        }
        String path = "api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=" + fromLang + "&to=" + toLang + "&textType=" + (html ? "html" : "plain");
        JSONArray body = new JSONArray();
        for (String text : texts) {
            body.put(new JSONObject().put("Text", text));
        }
        return TranslatorHttp.Request.post("https://" + path, body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8")
            .header("X-Mt-Signature", signature(path))
            .header("User-Agent", "okhttp/4.5.0");
    }

    private static String signature(String path) throws Exception {
        String guid = UUID.randomUUID().toString().replace("-", "");
        Locale locale = Locale.US;
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", locale);
        format.setTimeZone(GMT);
        String date = format.format(new Date(Calendar.getInstance(GMT).getTimeInMillis())).toLowerCase(locale) + "GMT";
        String data = ("MSTranslatorAndroidApp" + URLEncoder.encode(path, "UTF-8") + date + guid).toLowerCase(locale);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.decode(SIGNATURE_KEY, Base64.DEFAULT), "HmacSHA256"));
        return "MSTranslatorAndroidApp::" + Base64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP) + "::" + date + "::" + guid;
    }

    @Override
    public ProviderResponse parseResponse(TranslatorHttp.Response response, int expected) throws Exception {
        String body = response.body();
        if (body.length() == 0) {
            return new ProviderResponse.Failure(response.isSuccessful() ? TranslationError.Transient.INSTANCE : httpError(response));
        }
        if (!body.startsWith("[")) {
            return new ProviderResponse.Failure(failure(response, body));
        }
        JSONArray array = new JSONArray(body);
        if (array.length() != expected) {
            return new ProviderResponse.Failure(TranslationError.Transient.INSTANCE);
        }
        ArrayList<String> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            result.add(array.getJSONObject(i).getJSONArray("translations").getJSONObject(0).optString("text"));
        }
        return new ProviderResponse.Success(result);
    }

    private TranslationError failure(TranslatorHttp.Response response, String body) throws Exception {
        JSONObject error = new JSONObject(body).optJSONObject("error");
        int code = error != null ? error.optInt("code") : 0;
        if (response.code() == 429) {
            return new TranslationError.RateLimited(retryAfterMs(response));
        }
        if (code / 1000 == 401) {
            FileLog.e("translator: Microsoft rejected the signature, invalid credentials");
            return TranslationError.Fatal.INSTANCE;
        }
        int http = response.code();
        return (http >= 500 && http < 600) ? TranslationError.Transient.INSTANCE : TranslationError.Fatal.INSTANCE;
    }
}
