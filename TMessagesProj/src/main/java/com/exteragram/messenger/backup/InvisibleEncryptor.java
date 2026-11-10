package com.exteragram.messenger.backup;

import android.text.TextUtils;

import java.nio.charset.StandardCharsets;

/**
 * Port of exteraGram's InvisibleEncryptor. Not encryption: it encodes every UTF-8 byte of the backup JSON
 * as a base-11 number written with invisible/whitespace Unicode characters, prefixed with U+2001 U+2002.
 * Used for settings backup files (format compatible with exteraGram's ".extera").
 */
public abstract class InvisibleEncryptor {

    private static final String ALPHABET = "\u200a\u200b\u200c\u200f\u202f\u206a\u206b\u206c\u206d\u206e\u206f"; // U+202F (narrow no-break space), as in exteraGram - not a plain space
    private static final String PREFIX = "\u2001\u2002";
    private static final String SEPARATOR = "\u2000";

    private static String toStr(int i) {
        StringBuilder sb = new StringBuilder();
        while (i > 0) {
            sb.insert(0, ALPHABET.charAt(i % 11));
            i /= 11;
        }
        return sb.toString();
    }

    private static int toNum(String str) {
        int result = 0;
        for (int i = 0; i < str.length(); i++) {
            int length = str.length() - i;
            char c = str.charAt(length - 1);
            // files written by earlier StaticGram builds used a plain space for digit 4 (exteraGram uses U+202F)
            int digit = c == ' ' ? 4 : ALPHABET.indexOf(c);
            result = (int) (result + (digit * Math.pow(11.0d, i)));
        }
        return result;
    }

    public static String encode(String str) {
        try {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(PREFIX);
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    sb.append(SEPARATOR);
                }
                sb.append(toStr(bytes[i] & 255));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return str;
        }
    }

    public static String decode(String str) {
        try {
            String[] parts = str.replaceFirst("^" + PREFIX, "").split(SEPARATOR);
            byte[] bytes = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                bytes[i] = (byte) toNum(parts[i]);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return str;
        }
    }

    public static boolean isEncrypted(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        return str.matches("^" + PREFIX + "([" + ALPHABET + SEPARATOR + " \\s]*)");
    }
}
