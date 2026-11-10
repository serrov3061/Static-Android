package com.exteragram.messenger.translator;

import android.text.Html;
import android.text.TextUtils;

import com.exteragram.messenger.translator.core.HttpTranslator;
import com.exteragram.messenger.translator.core.TranslationError;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * exteraGram 13.1.1 formatted translation: a text with entities is sent to the HTTP provider as HTML markup
 * (every run of formatted text becomes {@code <span id="tgN">}, code/links/mentions/custom emoji are marked
 * {@code translate="no"}), the translated markup is parsed back and the entities are moved onto the translated text.
 * Texts without entities go through the plain path of {@link ChatTranslator}.
 * Rewritten from the decompiled Kotlin (FormattedTranslation.kt); argument order checked against smali.
 */
public final class FormattedTranslation {

    public interface Callback {
        /** On the UI thread; results has one element (maybe null) per input. */
        void onResult(List<TLRPC.TL_textWithEntities> results, TranslationError error);
    }

    private static final HashSet<Job> jobs = new HashSet<>();

    private FormattedTranslation() {
    }

    private static final class Job {
        final long dialogId;
        boolean cancelled;

        Job(long dialogId) {
            this.dialogId = dialogId;
        }
    }

    /** A formatted run of the source text: its original text and the indices of the entities covering it. */
    private static final class Segment {
        final String text;
        final List<Integer> entities;
        final boolean isProtected;

        Segment(String text, List<Integer> entities, boolean isProtected) {
            this.text = text;
            this.entities = entities;
            this.isProtected = isProtected;
        }
    }

    /** One request text: a piece of a message as HTML, plus the untranslated whitespace around it. */
    private static final class Part {
        final int message;
        final String html;
        final List<Segment> segments;
        final Decoded leading;
        final Decoded trailing;

        Part(int message, String html, List<Segment> segments, Decoded leading, Decoded trailing) {
            this.message = message;
            this.html = html;
            this.segments = segments;
            this.leading = leading;
            this.trailing = trailing;
        }
    }

    private static final class Range {
        final int entity;
        final int start;
        int end;

        Range(int entity, int start, int end) {
            this.entity = entity;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Decoded {
        final String text;
        final List<Range> ranges;

        Decoded(String text, List<Range> ranges) {
            this.text = text;
            this.ranges = ranges;
        }
    }

    /** Cancels the jobs of a dialog, or all jobs when dialogId is null. UI thread. */
    static void cancel(Long dialogId) {
        for (Job job : jobs) {
            if (dialogId == null || job.dialogId == dialogId) {
                job.cancelled = true;
            }
        }
    }

    public static void translate(long dialogId, List<TLRPC.TL_textWithEntities> input, String fromLang, String toLang, HttpTranslator translator, boolean priority, Callback callback) {
        AndroidUtilities.runOnUIThread(() -> {
            final ArrayList<TLRPC.TL_textWithEntities> sources = new ArrayList<>(input.size());
            boolean hasEntities = false;
            for (TLRPC.TL_textWithEntities text : input) {
                TLRPC.TL_textWithEntities copy = new TLRPC.TL_textWithEntities();
                copy.text = text.text;
                copy.entities = text.entities == null ? new ArrayList<>() : new ArrayList<>(text.entities);
                sources.add(copy);
                hasEntities |= !copy.entities.isEmpty();
            }
            final Job job = new Job(dialogId);
            jobs.add(job);
            final boolean formatted = hasEntities;
            Utilities.globalQueue.postRunnable(() -> {
                final List<Part> parts;
                try {
                    if (formatted) {
                        int limit = Math.min(translator.getLimits().getMaxCharsPerText(), translator.getLimits().getMaxCharsPerRequest());
                        parts = new ArrayList<>();
                        for (int i = 0; i < sources.size(); i++) {
                            parts.addAll(split(i, sources.get(i), limit));
                        }
                    } else {
                        parts = Collections.emptyList();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> finish(job, nulls(sources.size()), TranslationError.Fatal.INSTANCE, callback));
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (job.cancelled) {
                        finish(job, nulls(sources.size()), TranslationError.Cancelled.INSTANCE, callback);
                        return;
                    }
                    ArrayList<String> texts = new ArrayList<>();
                    if (formatted) {
                        for (Part part : parts) {
                            texts.add(part.html);
                        }
                    } else {
                        for (TLRPC.TL_textWithEntities source : sources) {
                            texts.add(source.text == null ? "" : source.text);
                        }
                    }
                    ChatTranslator.translate(dialogId, texts, toLang, translator, fromLang, formatted, priority, (results, error) ->
                        Utilities.globalQueue.postRunnable(() -> assemble(error, results, sources, formatted, parts, translator, fromLang, toLang, job, callback)));
                });
            });
        });
    }

    private static ArrayList<TLRPC.TL_textWithEntities> nulls(int size) {
        ArrayList<TLRPC.TL_textWithEntities> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(null);
        }
        return list;
    }

    private static void assemble(TranslationError error, List<String> results, List<TLRPC.TL_textWithEntities> sources, boolean formatted, List<Part> parts, HttpTranslator translator, String fromLang, String toLang, Job job, Callback callback) {
        TranslationError firstError = error;
        ArrayList<Decoded> decoded = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            String result = results.get(i);
            Decoded d = null;
            if (result != null) {
                if (formatted) {
                    try {
                        d = decode(parts.get(i), result);
                    } catch (Exception e) {
                        FileLog.e(e);
                        ChatTranslator.invalidate(translator, fromLang, toLang, parts.get(i).html);
                        if (firstError == null) {
                            firstError = TranslationError.Transient.INSTANCE;
                        }
                    }
                } else {
                    d = new Decoded(result, Collections.emptyList());
                }
            }
            decoded.add(d);
        }
        final ArrayList<TLRPC.TL_textWithEntities> out = new ArrayList<>(sources.size());
        for (int m = 0; m < sources.size(); m++) {
            TLRPC.TL_textWithEntities source = sources.get(m);
            ArrayList<Integer> indices = new ArrayList<>();
            if (formatted) {
                for (int i = 0; i < parts.size(); i++) {
                    if (parts.get(i).message == m) {
                        indices.add(i);
                    }
                }
            } else {
                indices.add(m);
            }
            boolean complete = true;
            for (int index : indices) {
                if (index >= decoded.size() || decoded.get(index) == null) {
                    complete = false;
                    break;
                }
            }
            TLRPC.TL_textWithEntities translated = null;
            if (complete) {
                try {
                    StringBuilder sb = new StringBuilder();
                    ArrayList<Range> ranges = new ArrayList<>();
                    for (int index : indices) {
                        Decoded d = decoded.get(index);
                        for (Range r : d.ranges) {
                            ranges.add(new Range(r.entity, sb.length() + r.start, sb.length() + r.end));
                        }
                        sb.append(d.text);
                    }
                    translated = new TLRPC.TL_textWithEntities();
                    translated.text = sb.toString();
                    ranges.sort((a, b) -> a.entity != b.entity ? Integer.compare(a.entity, b.entity) : Integer.compare(a.start, b.start));
                    ArrayList<Range> merged = new ArrayList<>();
                    for (Range r : ranges) {
                        Range last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                        if (last != null && last.entity == r.entity && last.end <= r.start && sb.substring(last.end, r.start).trim().isEmpty()) {
                            last.end = r.end;
                        } else {
                            merged.add(r);
                        }
                    }
                    for (Range r : merged) {
                        if (r.end > r.start) {
                            translated.entities.add(copyEntity(source.entities.get(r.entity), r.start, r.end));
                        }
                    }
                    if (translated.entities.size() > 1) {
                        translated.entities.sort((a, b) -> Integer.compare(a.offset, b.offset));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    if (firstError == null) {
                        firstError = TranslationError.Fatal.INSTANCE;
                    }
                    translated = null;
                }
            }
            out.add(translated);
        }
        final TranslationError finalError = firstError;
        AndroidUtilities.runOnUIThread(() -> finish(job, out, finalError, callback));
    }

    private static void finish(Job job, List<TLRPC.TL_textWithEntities> results, TranslationError error, Callback callback) {
        jobs.remove(job);
        if (job.cancelled) {
            results = nulls(results.size());
            error = TranslationError.Cancelled.INSTANCE;
        }
        callback.onResult(results, error);
    }

    /** Splits a message into parts whose HTML fits the provider limit, cutting at line breaks or spaces. */
    private static List<Part> split(int message, TLRPC.TL_textWithEntities source, int limit) {
        String text = source.text == null ? "" : source.text;
        if (text.length() <= limit) {
            Part part = encode(message, source, 0, text.length());
            if (part.html.length() <= limit) {
                return Collections.singletonList(part);
            }
        }
        ArrayList<Part> parts = new ArrayList<>();
        int end;
        int start = 0;
        do {
            int lo = start;
            int hi = Math.min(text.length(), start + limit);
            while (lo < hi) {
                int mid = lo + (hi - lo + 1) / 2;
                if (encode(message, source, start, mid).html.length() <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            end = lo;
            if (end < text.length()) {
                int cut = text.lastIndexOf('\n', end - 1);
                int space = text.lastIndexOf(' ', end - 1);
                if (cut <= start) {
                    cut = space;
                }
                if (cut > start) {
                    end = cut + 1;
                }
                if (end > start && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
                    end--;
                }
            }
            if (end <= start && text.length() != 0) {
                throw new IllegalArgumentException("Failed requirement.");
            }
            parts.add(encode(message, source, start, end));
            start = end;
        } while (end < text.length());
        return parts;
    }

    private static boolean isTranslatable(TLRPC.MessageEntity entity) {
        return entity instanceof TLRPC.TL_messageEntityBold
            || entity instanceof TLRPC.TL_messageEntityItalic
            || entity instanceof TLRPC.TL_messageEntityUnderline
            || entity instanceof TLRPC.TL_messageEntityStrike
            || entity instanceof TLRPC.TL_messageEntitySpoiler
            || entity instanceof TLRPC.TL_messageEntityBlockquote
            || entity instanceof TLRPC.TL_messageEntityTextUrl;
    }

    private static Part encode(int message, TLRPC.TL_textWithEntities source, int start, int end) {
        String text = source.text == null ? "" : source.text;
        ArrayList<Integer> entities = new ArrayList<>();
        for (int i = 0; i < source.entities.size(); i++) {
            TLRPC.MessageEntity e = source.entities.get(i);
            if (e.offset >= 0 && e.length > 0 && (long) e.offset + e.length <= text.length() && e.offset < end && e.offset + e.length > start) {
                entities.add(i);
            }
        }
        int from = start;
        while (from < end && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        int to = end;
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        TreeSet<Integer> bounds = new TreeSet<>();
        bounds.add(from);
        bounds.add(to);
        for (int index : entities) {
            TLRPC.MessageEntity e = source.entities.get(index);
            bounds.add(Utilities.clamp(e.offset, to, from));
            bounds.add(Utilities.clamp(e.offset + e.length, to, from));
        }
        StringBuilder sb = new StringBuilder();
        ArrayList<Segment> segments = new ArrayList<>();
        Integer prev = null;
        for (int bound : bounds) {
            if (prev == null) {
                prev = bound;
                continue;
            }
            int a = prev;
            int b = bound;
            prev = bound;
            ArrayList<Integer> covering = new ArrayList<>();
            for (int index : entities) {
                TLRPC.MessageEntity e = source.entities.get(index);
                if (e.offset <= a && e.offset + e.length >= b) {
                    covering.add(index);
                }
            }
            String piece = text.substring(a, b);
            String escaped = TextUtils.htmlEncode(piece).replace("\n", "<br>").replace("\r", "&#13;").replace("\t", "&#9;");
            if (covering.isEmpty()) {
                sb.append(escaped);
                continue;
            }
            boolean isProtected = false;
            for (int index : covering) {
                if (!isTranslatable(source.entities.get(index))) {
                    isProtected = true;
                    break;
                }
            }
            sb.append("<span id=\"tg").append(segments.size()).append('"');
            if (isProtected) {
                sb.append(" translate=\"no\" class=\"notranslate\"");
            }
            sb.append('>').append(escaped).append("</span>");
            segments.add(new Segment(piece, covering, isProtected));
        }
        return new Part(message, sb.toString(), segments, originalRange(source, entities, start, from), originalRange(source, entities, to, end));
    }

    /** Untranslated slice of the source (the whitespace around a part) with its entity ranges. */
    private static Decoded originalRange(TLRPC.TL_textWithEntities source, List<Integer> entities, int start, int end) {
        ArrayList<Range> ranges = new ArrayList<>();
        for (int index : entities) {
            TLRPC.MessageEntity e = source.entities.get(index);
            int a = Math.max(start, e.offset);
            int b = Math.min(end, e.offset + e.length);
            if (a < b) {
                ranges.add(new Range(index, a - start, b - start));
            }
        }
        String text = source.text == null ? "" : source.text;
        return new Decoded(text.substring(start, end), ranges);
    }

    private static final class DecodeHandler extends DefaultHandler {
        private final Part part;
        private final StringBuilder sb;
        private final ArrayList<Range> ranges;
        private final HashSet<Integer> seen;
        Integer active;
        private int activeDepth;
        private int depth;
        private int start;

        DecodeHandler(Part part, StringBuilder sb, ArrayList<Range> ranges, HashSet<Integer> seen) {
            this.part = part;
            this.sb = sb;
            this.ranges = ranges;
            this.seen = seen;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            depth++;
            if ("script".equals(localName) || "style".equals(localName)) {
                throw new SAXException("Unexpected translation markup");
            }
            if ("br".equals(localName)) {
                sb.append('\n');
            }
            String id = attributes.getValue("id");
            if (id == null || !id.startsWith("tg")) {
                return;
            }
            Integer index;
            try {
                index = Integer.parseInt(id.substring(2));
            } catch (NumberFormatException e) {
                index = null;
            }
            if (index == null || index < 0 || index >= part.segments.size() || active != null) {
                throw new SAXException("Invalid translation span");
            }
            active = index;
            activeDepth = depth;
            start = sb.length();
            if (!seen.add(index)) {
                throw new SAXException("Duplicate translation span");
            }
        }

        @Override
        public void characters(char[] ch, int offset, int length) {
            sb.append(ch, offset, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (active != null && depth == activeDepth) {
                Segment segment = part.segments.get(active);
                if (segment.isProtected) {
                    sb.setLength(start);
                    sb.append(segment.text);
                }
                for (int entity : segment.entities) {
                    ranges.add(new Range(entity, start, sb.length()));
                }
                active = null;
            }
            depth--;
        }
    }

    private static Decoded decode(Part part, String html) {
        final StringBuilder sb = new StringBuilder();
        final ArrayList<Range> ranges = new ArrayList<>();
        final HashSet<Integer> seen = new HashSet<>();
        final DecodeHandler handler = new DecodeHandler(part, sb, ranges, seen);
        Html.fromHtml("<translation-root>" + html + "</translation-root>", 0, null, (opening, tag, output, xmlReader) -> {
            if (opening && "translation-root".equals(tag)) {
                xmlReader.setContentHandler(handler);
            }
        });
        if (handler.active != null || seen.size() != part.segments.size()) {
            throw new IllegalStateException("Failed requirement.");
        }
        if (part.html.length() != 0 && sb.toString().trim().isEmpty()) {
            throw new IllegalStateException("Failed requirement.");
        }
        int from = 0;
        while (from < sb.length() && Character.isWhitespace(sb.charAt(from))) {
            from++;
        }
        int to = sb.length();
        while (to > from && Character.isWhitespace(sb.charAt(to - 1))) {
            to--;
        }
        int lead = part.leading.text.length();
        ArrayList<Range> result = new ArrayList<>(part.leading.ranges);
        for (Range r : ranges) {
            int a = Utilities.clamp(r.start, to, from) - from + lead;
            int b = Utilities.clamp(r.end, to, from) - from + lead;
            if (a < b) {
                result.add(new Range(r.entity, a, b));
            }
        }
        int shift = lead + to - from;
        for (Range r : part.trailing.ranges) {
            result.add(new Range(r.entity, r.start + shift, r.end + shift));
        }
        return new Decoded(part.leading.text + sb.substring(from, to) + part.trailing.text, result);
    }

    private static TLRPC.MessageEntity copyEntity(TLRPC.MessageEntity source, int start, int end) {
        SerializedData out = new SerializedData();
        try {
            source.serializeToStream(out);
            SerializedData in = new SerializedData(out.toByteArray());
            try {
                TLRPC.MessageEntity copy = TLRPC.MessageEntity.TLdeserialize(in, in.readInt32(true), true);
                copy.offset = start;
                copy.length = end - start;
                if (copy instanceof TLRPC.TL_messageEntityCustomEmoji && source instanceof TLRPC.TL_messageEntityCustomEmoji) {
                    ((TLRPC.TL_messageEntityCustomEmoji) copy).document = ((TLRPC.TL_messageEntityCustomEmoji) source).document;
                    ((TLRPC.TL_messageEntityCustomEmoji) copy).local = ((TLRPC.TL_messageEntityCustomEmoji) source).local; // StaticGram: not serialized
                }
                return copy;
            } finally {
                in.cleanup();
            }
        } finally {
            out.cleanup();
        }
    }
}
