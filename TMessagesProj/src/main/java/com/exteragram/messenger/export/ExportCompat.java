package com.exteragram.messenger.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Replacements for R8 outlines and desugared-record helpers that JADX left in exteraGram's export package
 * (synthetic classes of DexMaker/okio/okhttp/etc. that do not exist in our build).
 */
public final class ExportCompat {

    private ExportCompat() {
    }

    public static String recordToString(Object[] values, Class<?> clazz, String names) {
        StringBuilder sb = new StringBuilder(clazz.getSimpleName()).append('[');
        String[] split = names.isEmpty() ? new String[0] : names.split(";");
        for (int i = 0; i < split.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(split[i]).append('=').append(i < values.length ? values[i] : null);
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> listOf(Object... values) {
        ArrayList list = new ArrayList<>(values.length);
        for (Object value : values) {
            Objects.requireNonNull(value);
            list.add((T) value);
        }
        return Collections.unmodifiableList(list);
    }

    public static String join(CharSequence delimiter, Iterable<?> items) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = items.iterator();
        while (it.hasNext()) {
            sb.append((CharSequence) it.next());
            if (it.hasNext()) {
                sb.append(delimiter);
            }
        }
        return sb.toString();
    }

    public static String repeat(String s, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static void illegalState(String message, Object value) {
        throw new IllegalStateException(message + value);
    }

    public static void illegalState(Throwable t) {
        throw new IllegalStateException(t);
    }

    public static void illegalStateSb(StringBuilder sb, Object a, Object b) {
        sb.append(a);
        sb.append(b);
        throw new IllegalStateException(sb.toString());
    }

    public static void illegalArgument(String message, Object value) {
        throw new IllegalArgumentException(message + value);
    }

    public static void runtime(String message, Object value) {
        throw new RuntimeException(message + value);
    }

    public static void runtime(Throwable t) {
        throw new RuntimeException(t);
    }

    public static void runtime(String message, Throwable t) {
        throw new RuntimeException(message, t);
    }

    private static volatile org.telegram.messenger.time.FastDateFormat exportFullDateFormatter;
    private static volatile org.telegram.messenger.time.FastDateFormat exportFileFormatter;

    /** exteraGram adds these to LocaleController; kept here to avoid touching it. */
    public static org.telegram.messenger.time.FastDateFormat exportFullDateFormatter() {
        if (exportFullDateFormatter == null) {
            exportFullDateFormatter = org.telegram.messenger.time.FastDateFormat.getInstance("dd MMM yyyy HH:mm:ss", java.util.Locale.getDefault());
        }
        return exportFullDateFormatter;
    }

    public static org.telegram.messenger.time.FastDateFormat exportFileFormatter() {
        if (exportFileFormatter == null) {
            exportFileFormatter = org.telegram.messenger.time.FastDateFormat.getInstance("dd-MM-yyyy_HH-mm", java.util.Locale.getDefault());
        }
        return exportFileFormatter;
    }

    public static void throwCCEIfNotNull(Object value) {
        if (value != null) {
            throw new ClassCastException();
        }
    }
}
