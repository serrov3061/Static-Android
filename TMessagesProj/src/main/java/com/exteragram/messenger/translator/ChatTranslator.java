package com.exteragram.messenger.translator;

import com.exteragram.messenger.translator.core.BaseTranslator;
import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.ProviderLimits;
import com.exteragram.messenger.translator.core.TranslationDispatcher;
import com.exteragram.messenger.translator.core.TranslationError;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch translation of chat messages through an HTTP provider: splits long texts, groups pieces into requests
 * according to provider limits, caches results (LRU, 300 entries). Results are delivered on the UI thread.
 * exteraGram 13.1.1: HTML mode for {@link FormattedTranslation} (texts are already split by it), source language
 * in the cache key, whitespace around plain pieces is kept.
 */
public final class ChatTranslator {

    public interface Callback {
        void onResult(List<String> results, TranslationError error);
    }

    private static final LinkedHashMap<String, String> cache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 300;
        }
    };

    private ChatTranslator() {
    }

    private static final class Piece {
        final int messageIndex;
        final String text;
        String translated;

        Piece(int messageIndex, String text) {
            this.messageIndex = messageIndex;
            this.text = text;
        }
    }

    public static void translate(long dialogId, List<String> texts, String toLang, Callback callback) {
        translate(dialogId, texts, toLang, TranslatorUtils.getCurrentTranslator(), callback);
    }

    public static void translate(long dialogId, List<String> texts, String toLang, BaseTranslator current, Callback callback) {
        translate(dialogId, texts, toLang, current, "auto", false, false, callback);
    }

    static void translate(long dialogId, List<String> texts, String toLang, BaseTranslator current, String fromLang, boolean html, boolean priority, Callback callback) {
        if (!(current instanceof HttpTranslator)) {
            final int size = texts.size();
            AndroidUtilities.runOnUIThread(() -> callback.onResult(Arrays.asList(new String[size]), TranslationError.Fatal.INSTANCE));
            return;
        }
        final HttpTranslator translator = (HttpTranslator) current;
        final ProviderLimits limits = translator.getLimits();
        final String[] results = new String[texts.size()];
        final ArrayList<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.trim().isEmpty()) {
                results[i] = text;
                continue;
            }
            String cached;
            synchronized (cache) {
                cached = cache.get(cacheKey(translator, fromLang, toLang, html, text));
            }
            if (cached != null) {
                results[i] = cached;
            } else {
                for (String part : html ? Collections.singletonList(text) : split(text, limits.getMaxCharsPerText())) {
                    pieces.add(new Piece(i, part));
                }
            }
        }
        if (pieces.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(Arrays.asList(results), null));
            return;
        }
        final List<List<Piece>> chunks = chunk(pieces, limits);
        final int[] remaining = {chunks.size()};
        final TranslationError[] firstError = {null};
        for (final List<Piece> chunk : chunks) {
            ArrayList<String> chunkTexts = new ArrayList<>(chunk.size());
            for (Piece piece : chunk) {
                chunkTexts.add(piece.text);
            }
            TranslationDispatcher.enqueue(translator, dialogId, chunkTexts, fromLang, toLang, priority, html, (translated, error) -> {
                // runs on the UI thread
                if (translated != null && translated.size() == chunk.size()) {
                    for (int i = 0; i < chunk.size(); i++) {
                        chunk.get(i).translated = translated.get(i);
                    }
                } else if (error instanceof TranslationError.Cancelled || firstError[0] == null) {
                    firstError[0] = error == null ? TranslationError.Transient.INSTANCE : error;
                }
                remaining[0]--;
                if (remaining[0] == 0) {
                    assemble(translator, fromLang, toLang, html, texts, pieces, results);
                    callback.onResult(Arrays.asList(results), firstError[0]);
                }
            });
        }
    }

    public static void cancel(long dialogId) {
        FormattedTranslation.cancel(dialogId);
        TranslationDispatcher.cancel(dialogId);
    }

    public static void cancelAll() {
        FormattedTranslation.cancel(null);
        TranslationDispatcher.cancelAll();
    }

    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Drops a cached HTML translation whose markup could not be decoded. */
    static void invalidate(BaseTranslator translator, String fromLang, String toLang, String text) {
        synchronized (cache) {
            cache.remove(cacheKey(translator, fromLang, toLang, true, text));
        }
    }

    private static void assemble(HttpTranslator translator, String fromLang, String toLang, boolean html, List<String> texts, List<Piece> pieces, String[] results) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pieces.size()) {
            int messageIndex = pieces.get(i).messageIndex;
            boolean complete = true;
            int end = i;
            while (end < pieces.size() && pieces.get(end).messageIndex == messageIndex) {
                if (pieces.get(end).translated == null) {
                    complete = false;
                }
                end++;
            }
            if (complete) {
                sb.setLength(0);
                for (int j = i; j < end; j++) {
                    String text = pieces.get(j).text;
                    String translated = pieces.get(j).translated;
                    if (html) {
                        sb.append(translated);
                    } else if (text.trim().isEmpty()) {
                        sb.append(text);
                    } else {
                        sb.append(text, 0, leadingWhitespace(text));
                        sb.append(translated.trim());
                        sb.append(text, text.length() - trailingWhitespace(text), text.length());
                    }
                }
                String joined = sb.toString();
                results[messageIndex] = joined;
                synchronized (cache) {
                    cache.put(cacheKey(translator, fromLang, toLang, html, texts.get(messageIndex)), joined);
                }
            }
            i = end;
        }
    }

    private static int leadingWhitespace(String text) {
        int n = 0;
        while (n < text.length() && Character.isWhitespace(text.charAt(n))) {
            n++;
        }
        return n;
    }

    private static int trailingWhitespace(String text) {
        int n = 0;
        while (n < text.length() && Character.isWhitespace(text.charAt(text.length() - 1 - n))) {
            n++;
        }
        return n;
    }

    private static List<String> split(String text, int limit) {
        if (text.length() <= limit) {
            return Collections.singletonList(text);
        }
        ArrayList<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            if (text.length() - start <= limit) {
                parts.add(text.substring(start));
                return parts;
            }
            int end = start + limit;
            int cut = text.lastIndexOf('\n', end - 1);
            if (cut <= start) {
                cut = text.lastIndexOf(' ', end - 1);
            }
            if (cut > start) {
                end = cut + 1;
            }
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    private static List<List<Piece>> chunk(List<Piece> pieces, ProviderLimits limits) {
        ArrayList<List<Piece>> chunks = new ArrayList<>();
        ArrayList<Piece> current = new ArrayList<>();
        int length = 0;
        for (Piece piece : pieces) {
            if (!current.isEmpty() && (current.size() >= limits.getMaxTextsPerRequest() || piece.text.length() + length > limits.getMaxCharsPerRequest())) {
                chunks.add(current);
                current = new ArrayList<>();
                length = 0;
            }
            current.add(piece);
            length += piece.text.length();
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static String cacheKey(BaseTranslator translator, String fromLang, String toLang, boolean html, String text) {
        return translator.getClass().getSimpleName() + " " + fromLang + " " + toLang + " " + html + " " + text;
    }
}
