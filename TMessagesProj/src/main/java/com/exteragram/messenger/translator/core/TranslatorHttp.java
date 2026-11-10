package com.exteragram.messenger.translator.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal blocking HTTP client on HttpURLConnection, replacing exteraGram's OkHttp usage in the translator.
 * Calls are executed on the dispatcher's worker threads, never on the UI thread.
 */
public final class TranslatorHttp {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private TranslatorHttp() {
    }

    public static final class Request {
        public final String url;
        public final String method;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final byte[] body;

        private Request(String url, String method, byte[] body) {
            this.url = url;
            this.method = method;
            this.body = body;
        }

        public static Request get(String url) {
            return new Request(url, "GET", null);
        }

        public static Request post(String url, byte[] body, String contentType) {
            Request request = new Request(url, "POST", body);
            request.headers.put("Content-Type", contentType);
            return request;
        }

        public Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    public static final class Response {
        private final int code;
        private final Map<String, String> headers;
        private final String body;

        Response(int code, Map<String, String> headers, String body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        public int code() {
            return code;
        }

        public boolean isSuccessful() {
            return code >= 200 && code < 300;
        }

        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        public String body() {
            return body == null ? "" : body;
        }
    }

    /** One cancellable request. */
    public static final class Call {
        private final Request request;
        private volatile boolean canceled;
        private volatile HttpURLConnection connection;

        public Call(Request request) {
            this.request = request;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public void cancel() {
            canceled = true;
            HttpURLConnection c = connection;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignore) {
                }
            }
        }

        public Response execute() throws IOException {
            if (canceled) {
                throw new IOException("Canceled");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(request.url).openConnection();
            connection = c;
            try {
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setInstanceFollowRedirects(true);
                c.setUseCaches(false);
                c.setRequestMethod(request.method);
                for (Map.Entry<String, String> e : request.headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
                if (request.body != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(request.body.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(request.body);
                    }
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = "";
                if (is != null) {
                    try (InputStream in = is) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (canceled) {
                                throw new IOException("Canceled");
                            }
                            bos.write(buf, 0, n);
                        }
                        body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                        headers.put(e.getKey(), e.getValue().get(0));
                    }
                }
                return new Response(code, headers, body);
            } finally {
                connection = null;
                try {
                    c.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
