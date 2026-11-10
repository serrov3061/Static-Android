package com.exteragram.messenger.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Replacements for Kotlin/R8 synthetic helpers left by the decompiler. */
public final class ExteraCompat {

    private ExteraCompat() {
    }

    /** Kotlin "when" without a matching branch. */
    public static void noWhenBranchMatched() {
        throw new IllegalStateException("No when branch matched");
    }

    /** Kotlin default-argument call with a super receiver. */
    public static void unsupported(String message) {
        throw new UnsupportedOperationException(message);
    }

    /** kotlin.text.contains(other, ignoreCase). */
    public static boolean contains(CharSequence text, CharSequence other, boolean ignoreCase) {
        if (text == null || other == null) {
            return false;
        }
        if (ignoreCase) {
            return text.toString().toLowerCase(Locale.ROOT).contains(other.toString().toLowerCase(Locale.ROOT));
        }
        return text.toString().contains(other);
    }

    /** kotlin.text.split(delimiter) without limit: keeps trailing empty parts. */
    public static List<String> split(CharSequence text, String delimiter) {
        return new ArrayList<>(Arrays.asList(text.toString().split(Pattern.quote(delimiter), -1)));
    }
}
