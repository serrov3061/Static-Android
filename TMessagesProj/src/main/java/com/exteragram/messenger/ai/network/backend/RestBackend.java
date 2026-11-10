package com.exteragram.messenger.ai.network.backend;

import android.text.TextUtils;
import android.util.Base64;

import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.translator.TranslatorUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * OpenAI-compatible REST backend (port of exteraGram's RestBackend, OkHttp replaced by HttpURLConnection).
 *
 * Endpoint: the service URL the user entered. "/chat/completions" is appended unless the URL already ends with
 * "/chat/completions", "/responses" or "/agent" (OpenAI Responses API / Perplexity Agent API).
 * Special cases kept from exteraGram: any generativelanguage.googleapis.com URL goes to Gemini's OpenAI-compatible
 * endpoint, api.perplexity.ai (except /router) goes to the Perplexity Agent API.
 * Auth: "Authorization: Bearer &lt;user key&gt;". Streaming: SSE, read line by line ("data: ..." until [DONE]).
 * The system DNS is used (exteraGram's DoH resolver is disabled).
 */
public class RestBackend implements Backend {
    private static final String[] ERROR_MESSAGE_KEYS = {"error", "message", "detail"};
    private static final int STREAM_SYMBOLS_LIMIT = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE ? 10 : 20;
    private static final int CONNECT_TIMEOUT_MS = 60_000;
    private static final int READ_TIMEOUT_MS = 5 * 60_000;

    @Override
    public int getHistoryCharsLimit() {
        return 24000;
    }

    @Override
    public int getHistoryMessagesLimit() {
        return 32;
    }

    @Override
    public int getMaxOutputTokens() {
        return 4096;
    }

    @Override
    public float getMaxTemperature() {
        return 2.0f;
    }

    @Override
    public boolean supportsImages() {
        return true;
    }

    @Override
    public void execute(BackendRequest request, BackendSink sink) throws Exception {
        Endpoint endpoint = resolveEndpoint(request.service().getUrl());
        byte[] body = endpoint != null ? createRequestBody(request, endpoint) : null;
        if (body == null) {
            sink.onError(500, "Failed to create request body");
            return;
        }
        if (!sink.isActive()) {
            return;
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.url()).openConnection();
        sink.onCancellable(connection::disconnect);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", request.stream() ? "text/event-stream" : "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + request.service().getKey());
            connection.setRequestProperty("User-Agent", TranslatorUtils.formatUserAgent());
            // OpenRouter app attribution headers (exteraGram sends exteragram.app / exteraStatic)
            connection.setRequestProperty("HTTP-Referer", "https://staticgram.top");
            connection.setRequestProperty("X-Title", "StaticGram");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = null;
                try {
                    errorBody = readAll(connection.getErrorStream());
                    FileLog.e("AI_ERROR_RESPONSE (" + code + "): " + (errorBody == null ? "" : errorBody.length() > 500 ? errorBody.substring(0, 500) : errorBody));
                } catch (Exception e) {
                    FileLog.e("AI_ERROR_READING_RESPONSE_BODY: ", e);
                }
                if (sink.isActive()) {
                    sink.onError(code, parseErrorMessage(errorBody, connection.getResponseMessage()));
                }
                return;
            }
            if (request.stream()) {
                handleStreamResponse(connection.getInputStream(), sink, endpoint.responsesApi());
            } else {
                String response = readAll(connection.getInputStream());
                if (!sink.isActive()) {
                    return;
                }
                String content = parseResponseContent(response, endpoint.responsesApi());
                if (TextUtils.isEmpty(content)) {
                    String error = extractErrorMessage(response);
                    if (!TextUtils.isEmpty(error)) {
                        sink.onError(500, error);
                        return;
                    }
                }
                if (content == null) {
                    sink.onError(500, "Failed to parse response");
                    return;
                }
                sink.onComplete(content);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Endpoint resolveEndpoint(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (isGeminiService(lower)) {
            return new Endpoint("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", false);
        }
        if (isPerplexityService(lower) && !lower.contains("/router")) {
            return new Endpoint("https://api.perplexity.ai/v1/agent", true);
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower2 = url.toLowerCase(Locale.ROOT);
        if (lower2.endsWith("/responses") || lower2.endsWith("/agent")) {
            return new Endpoint(url, true);
        }
        if (!lower2.endsWith("/chat/completions")) {
            url = url.concat("/chat/completions");
        }
        return new Endpoint(url, false);
    }

    private byte[] createRequestBody(BackendRequest request, Endpoint endpoint) {
        Service service = request.service();
        boolean responsesApi = endpoint.responsesApi();
        try {
            JSONObject json = new JSONObject();
            JSONArray messages = new JSONArray();
            Role role = request.role();
            if (role != null && !TextUtils.isEmpty(role.getPrompt())) {
                if (responsesApi) {
                    json.put("instructions", role.getPrompt());
                } else {
                    messages.put(new JSONObject().put("role", "system").put("content", role.getPrompt()));
                }
            }
            for (Message message : request.history()) {
                messages.put(createMessageObject(message, responsesApi));
            }
            messages.put(createMessageObject(request.currentMessage(), responsesApi));
            json.put("model", service.getModel());
            json.put(responsesApi ? "input" : "messages", messages);
            json.put("stream", request.stream());
            json.put("temperature", (double) request.temperature());
            applyReasoningConfig(json, service, service.getUrl(), responsesApi);
            json.put(responsesApi ? "max_output_tokens" : "max_tokens", request.maxOutputTokens());
            if (responsesApi) {
                json.put("store", false);
            }
            FileLog.d("AI_REQUEST_URL: " + endpoint.url());
            FileLog.d("AI_REQUEST_MODEL: " + service.getModel());
            FileLog.d("AI_REQUEST_MESSAGES: " + messages.length() + ", stream=" + request.stream() + ", image=" + (request.currentMessage().getImageData() != null));
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** When the user did not enable reasoning, ask known reasoning models for the lowest effort (as exteraGram). */
    private void applyReasoningConfig(JSONObject json, Service service, String url, boolean responsesApi) throws Exception {
        if (service.isReasoningEnabled()) {
            return;
        }
        String model = service.getModel() == null ? "" : service.getModel().toLowerCase(Locale.ROOT);
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String effort = null;
        if (lowerUrl.contains("openrouter.ai")) {
            json.put("reasoning", new JSONObject().put("effort", "none"));
        } else if (isGeminiService(lowerUrl) && isGeminiReasoningModel(model)) {
            effort = getGeminiReasoningEffort(model);
        } else if (isOpenAiService(lowerUrl) && isOpenAiReasoningModel(model)) {
            effort = getOpenAiReasoningEffort(model);
        } else if (isPerplexityService(lowerUrl) && (isGeminiReasoningModel(model) || isOpenAiReasoningModel(model))) {
            effort = "minimal";
        }
        if (effort == null) {
            return;
        }
        if (responsesApi) {
            json.put("reasoning", new JSONObject().put("effort", effort));
        } else {
            json.put("reasoning_effort", effort);
        }
    }

    private boolean isGeminiService(String url) {
        return url.contains("generativelanguage.googleapis");
    }

    private boolean isGeminiReasoningModel(String model) {
        String m = stripProviderPrefix(model);
        return m.startsWith("gemini-2.5") || m.startsWith("gemini-3") || m.contains("thinking");
    }

    private boolean isOpenAiService(String url) {
        return url.contains("api.openai.com");
    }

    private boolean isPerplexityService(String url) {
        return url.contains("api.perplexity.ai");
    }

    private String getGeminiReasoningEffort(String model) {
        if (model.contains("gemini-2.5") && !model.contains("pro")) {
            return "none";
        }
        return "minimal";
    }

    private boolean isOpenAiReasoningModel(String model) {
        String m = stripProviderPrefix(model);
        if (m.contains("gpt-5-chat")) {
            return false;
        }
        return m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4");
    }

    private String getOpenAiReasoningEffort(String model) {
        return supportsOpenAiNoReasoning(stripProviderPrefix(model)) ? "none" : "minimal";
    }

    private boolean supportsOpenAiNoReasoning(String m) {
        return m.startsWith("gpt-5.1") || m.startsWith("gpt-5.2") || m.startsWith("gpt-5.3") || m.startsWith("gpt-5.4") || m.startsWith("gpt-5.5");
    }

    private String stripProviderPrefix(String model) {
        int idx = model.indexOf('/');
        return idx >= 0 ? model.substring(idx + 1) : model;
    }

    private JSONObject createMessageObject(Message message, boolean responsesApi) throws Exception {
        JSONObject json = new JSONObject();
        if (responsesApi) {
            json.put("type", "message");
        }
        json.put("role", message.role());
        if (message.getImageData() != null && !TextUtils.isEmpty(message.getMimeType())) {
            JSONArray content = new JSONArray();
            if (!TextUtils.isEmpty(message.content())) {
                content.put(new JSONObject().put("type", responsesApi ? "input_text" : "text").put("text", message.content()));
            }
            String dataUrl = "data:" + message.getMimeType() + ";base64," + Base64.encodeToString(message.getImageData(), Base64.NO_WRAP);
            if (responsesApi) {
                content.put(new JSONObject().put("type", "input_image").put("image_url", dataUrl));
            } else {
                content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", dataUrl)));
            }
            json.put("content", content);
            return json;
        }
        json.put("content", message.content());
        return json;
    }

    private void handleStreamResponse(InputStream stream, BackendSink sink, boolean responsesApi) {
        StringBuilder text = new StringBuilder();
        ReasoningContentFilter filter = new ReasoningContentFilter();
        String error = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            int pending = 0;
            String line;
            while ((line = reader.readLine()) != null && sink.isActive()) {
                if (TextUtils.isEmpty(line) || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                StreamResponsePart part;
                if (data.equals("[DONE]")) {
                    part = StreamResponsePart.DONE;
                } else if (responsesApi) {
                    part = parseResponsesStreamPart(data);
                } else {
                    part = parseStreamResponsePart(data);
                }
                if (part.error() != null) {
                    error = part.error();
                    break;
                }
                if (part.hasReasoning()) {
                    sink.onThinking();
                }
                String filtered = filter.filter(part.content());
                if (filter.consumeReasoningSignal()) {
                    sink.onThinking();
                }
                if (!TextUtils.isEmpty(filtered)) {
                    text.append(filtered);
                    pending += filtered.length();
                    if (pending >= STREAM_SYMBOLS_LIMIT) {
                        sink.onChunk(text.toString());
                        pending = 0;
                    }
                }
                if (part.done()) {
                    if (pending > 0) {
                        sink.onChunk(text.toString());
                    }
                    break;
                }
            }
        } catch (Exception e) {
            if (sink.isActive()) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : "Unknown error";
            }
        }
        if (!sink.isActive()) {
            return;
        }
        if (error != null) {
            sink.onError(500, error);
            return;
        }
        String rest = filter.flush();
        if (!TextUtils.isEmpty(rest)) {
            text.append(rest);
            sink.onChunk(text.toString());
        }
        String result = trimTrailing(text.toString());
        if (!TextUtils.isEmpty(result)) {
            sink.onComplete(result);
        } else {
            sink.onError(204, "Response body is empty");
        }
    }

    private String parseResponseContent(String response, boolean responsesApi) {
        try {
            JSONObject json = new JSONObject(response);
            if (responsesApi) {
                String output = parseResponsesOutput(json);
                return output != null ? stripReasoningMarkup(output) : null;
            }
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() <= 0) {
                return null;
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null || !message.has("content") || message.isNull("content")) {
                return null;
            }
            Object content = message.opt("content");
            return content == null ? null : stripReasoningMarkup(content.toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private String parseResponsesOutput(JSONObject json) {
        JSONArray output = json.optJSONArray("output");
        if (output == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            JSONArray content = item != null && "message".equals(item.optString("type")) ? item.optJSONArray("content") : null;
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.length(); j++) {
                JSONObject c = content.optJSONObject(j);
                if (c != null && "output_text".equals(c.optString("type"))) {
                    sb.append(c.optString("text"));
                }
            }
        }
        return sb.toString();
    }

    private StreamResponsePart parseStreamResponsePart(String data) {
        try {
            JSONArray choices = new JSONObject(data).optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) {
                    return new StreamResponsePart("", false);
                }
                Object content = delta.opt("content");
                return new StreamResponsePart(content == null || content == JSONObject.NULL ? "" : content.toString(), hasReasoning(delta));
            }
        } catch (Exception ignore) {
        }
        return new StreamResponsePart("", false);
    }

    private StreamResponsePart parseResponsesStreamPart(String data) {
        try {
            JSONObject json = new JSONObject(data);
            String type = json.optString("type");
            switch (type) {
                case "response.output_item.added": {
                    JSONObject item = json.optJSONObject("item");
                    return new StreamResponsePart("", item != null && "reasoning".equals(item.optString("type")));
                }
                case "response.output_text.delta":
                    return new StreamResponsePart(json.optString("delta"), false);
                case "response.completed":
                case "response.incomplete":
                    return StreamResponsePart.DONE;
                case "error":
                case "response.failed":
                    return new StreamResponsePart("", false, true, getResponsesStreamError(json));
                default:
                    return new StreamResponsePart("", type.startsWith("response.reasoning"));
            }
        } catch (Exception e) {
            return new StreamResponsePart("", false);
        }
    }

    private String getResponsesStreamError(JSONObject json) {
        JSONObject error = json.optJSONObject("error");
        if (error == null) {
            JSONObject response = json.optJSONObject("response");
            error = response != null ? response.optJSONObject("error") : null;
        }
        String message = error != null ? error.optString("message") : json.optString("message");
        return TextUtils.isEmpty(message) ? "Unknown error" : message;
    }

    private boolean hasReasoning(JSONObject delta) {
        return hasValue(delta, "reasoning") || hasValue(delta, "reasoning_content") || hasValue(delta, "reasoning_details");
    }

    private boolean hasValue(JSONObject json, String key) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return false;
        }
        Object value = json.opt(key);
        if (value instanceof String) {
            return !TextUtils.isEmpty((String) value);
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).length() > 0;
        }
        return value != null && value != JSONObject.NULL;
    }

    private String stripReasoningMarkup(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            int open = lower.indexOf("<think>", i);
            if (open < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, open);
            int close = lower.indexOf("</think>", open + 7);
            if (close < 0) {
                break;
            }
            i = close + 8;
        }
        return trimLeading(sb.toString());
    }

    private static String trimLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static String trimTrailing(String s) {
        int length = s.length();
        while (length > 0 && Character.isWhitespace(s.charAt(length - 1))) {
            length--;
        }
        return s.substring(0, length);
    }

    private String parseErrorMessage(String body, String responseMessage) {
        String message = extractErrorMessage(body);
        if (!TextUtils.isEmpty(message)) {
            return message;
        }
        return !TextUtils.isEmpty(responseMessage) ? responseMessage.toLowerCase(Locale.ROOT) : "";
    }

    private String extractErrorMessage(String body) {
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    JSONObject error = item == null ? null : item.optJSONObject("error");
                    String message = error == null ? null : error.optString("message", null);
                    if (!TextUtils.isEmpty(message)) {
                        return message;
                    }
                }
                return null;
            }
            if (trimmed.startsWith("{")) {
                JSONObject json = new JSONObject(trimmed);
                JSONObject error = json.optJSONObject("error");
                if (error != null) {
                    return error.optString("message", null);
                }
                for (String key : ERROR_MESSAGE_KEYS) {
                    Object value = json.opt(key);
                    if (value instanceof String && !((String) value).isEmpty()) {
                        return (String) value;
                    }
                }
                return null;
            }
        } catch (Exception ignore) {
        }
        if (trimmed.startsWith("<") || trimmed.length() > 200 || trimmed.indexOf('\n') >= 0) {
            return null;
        }
        return trimmed;
    }

    /** Removes &lt;think&gt;...&lt;/think&gt; blocks from streamed text, handling tags split between chunks. */
    public static class ReasoningContentFilter {
        private boolean inReasoning;
        private String pending = "";
        private boolean reasoningSignal;

        public String filter(String chunk) {
            if (TextUtils.isEmpty(chunk)) {
                return null;
            }
            String text = pending + chunk;
            pending = "";
            StringBuilder sb = new StringBuilder(text.length());
            String lower = text.toLowerCase(Locale.ROOT);
            int i = 0;
            while (i < text.length()) {
                if (inReasoning) {
                    reasoningSignal = true;
                    int close = lower.indexOf("</think>", i);
                    if (close < 0) {
                        pending = getCloseTagPrefixSuffix(text, i);
                        return sb.toString();
                    }
                    i = close + 8;
                    inReasoning = false;
                } else {
                    int open = lower.indexOf("<think>", i);
                    if (open < 0) {
                        pending = getOpenTagPrefixSuffix(text, i);
                        reasoningSignal = !pending.isEmpty();
                        int end = text.length() - pending.length();
                        if (end > i) {
                            sb.append(text, i, end);
                        }
                        return sb.toString();
                    }
                    sb.append(text, i, open);
                    i = open + 7;
                    inReasoning = true;
                    reasoningSignal = true;
                }
            }
            return sb.toString();
        }

        public boolean consumeReasoningSignal() {
            boolean signal = reasoningSignal;
            reasoningSignal = false;
            return signal;
        }

        public String flush() {
            String rest = inReasoning ? "" : pending;
            pending = "";
            return rest;
        }

        private String getOpenTagPrefixSuffix(String text, int from) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int n = Math.min(6, text.length() - from); n > 0; n--) {
                if ("<think>".startsWith(lower.substring(text.length() - n))) {
                    return text.substring(text.length() - n);
                }
            }
            return "";
        }

        private String getCloseTagPrefixSuffix(String text, int from) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int n = Math.min(7, text.length() - from); n > 0; n--) {
                if ("</think>".startsWith(lower.substring(text.length() - n))) {
                    return text.substring(text.length() - n);
                }
            }
            return "";
        }
    }

    public static final class Endpoint {
        private final String url;
        private final boolean responsesApi;

        private Endpoint(String url, boolean responsesApi) {
            this.url = url;
            this.responsesApi = responsesApi;
        }

        public String url() {
            return url;
        }

        public boolean responsesApi() {
            return responsesApi;
        }
    }

    public static final class StreamResponsePart {
        static final StreamResponsePart DONE = new StreamResponsePart("", false, true, null);
        private final String content;
        private final boolean hasReasoning;
        private final boolean done;
        private final String error;

        private StreamResponsePart(String content, boolean hasReasoning, boolean done, String error) {
            this.content = content;
            this.hasReasoning = hasReasoning;
            this.done = done;
            this.error = error;
        }

        public StreamResponsePart(String content, boolean hasReasoning) {
            this(content, hasReasoning, false, null);
        }

        public String content() {
            return content;
        }

        public boolean hasReasoning() {
            return hasReasoning;
        }

        public boolean done() {
            return done;
        }

        public String error() {
            return error;
        }
    }
}
