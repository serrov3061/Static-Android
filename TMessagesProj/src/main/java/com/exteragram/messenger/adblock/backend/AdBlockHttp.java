package com.exteragram.messenger.adblock.backend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Blocking GET for filter lists and scriptlets (exteraGram uses OkHttp here). Call only from a background queue. */
final class AdBlockHttp {

    static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15";

    static final class Response {
        final int code;
        final byte[] body;

        Response(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }

        boolean isSuccessful() {
            return code >= 200 && code < 300 && body != null;
        }
    }

    private AdBlockHttp() {
    }

    static Response get(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return new Response(code, null);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return new Response(code, out.toByteArray());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
