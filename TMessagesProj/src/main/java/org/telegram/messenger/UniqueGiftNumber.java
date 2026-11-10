package org.telegram.messenger;

import org.telegram.tgnet.tl.TL_stars;

/**
 * StaticGram: the number shown after "#" for a collectible (unique) gift.
 *
 * Operator-minted collectibles may carry a letter "number" ("#VIP", "#ЛЕВ").
 * StarGiftUnique.num is a TL int, so the server puts the label into the slug
 * and gives the gift a free negative num (what older clients show, e.g. "#-1").
 * Shape (server: domain.UniqueStarGiftNumLabel): num &lt; 0 and the slug's last
 * hyphen-separated group is "x" + the encoded label, where ASCII letters and
 * digits stand for themselves, "q" + two hex digits is the Cyrillic letter
 * U+0400 + n, and "qq" / "qQ" is a literal q / Q. At least one letter, at most
 * 16 characters; anything else is a plain number.
 */
public final class UniqueGiftNumber {

    private static final int MAX_LABEL = 16;

    private UniqueGiftNumber() {
    }

    /** The letter label of a collectible, or null when it has a plain number. */
    public static String label(int num, String slug) {
        if (num >= 0 || slug == null) {
            return null;
        }
        final int dash = slug.lastIndexOf('-');
        if (dash < 0) {
            return null;
        }
        final String group = slug.substring(dash + 1);
        if (group.length() < 2 || (group.charAt(0) != 'x' && group.charAt(0) != 'X')) {
            return null;
        }
        final StringBuilder out = new StringBuilder();
        boolean letter = false;
        int count = 0;
        for (int i = 1; i < group.length(); i++) {
            final char c = group.charAt(i);
            char ch;
            if (c == 'q' || c == 'Q') {
                if (i + 1 >= group.length()) {
                    return null;
                }
                final char n = group.charAt(i + 1);
                if (n == 'q' || n == 'Q') {
                    ch = n;
                    i++;
                } else {
                    if (i + 2 >= group.length()) {
                        return null;
                    }
                    final int hi = Character.digit(group.charAt(i + 1), 16);
                    final int lo = Character.digit(group.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) {
                        return null;
                    }
                    ch = (char) (0x400 + (hi << 4 | lo));
                    if (!Character.isLetter(ch)) {
                        return null;
                    }
                    i += 2;
                }
            } else if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                ch = c;
            } else {
                return null;
            }
            if (ch > '9') {
                letter = true;
            }
            count++;
            out.append(ch);
        }
        return letter && count <= MAX_LABEL ? out.toString() : null;
    }

    public static String label(TL_stars.StarGift gift) {
        return gift == null ? null : label(gift.num, gift.slug);
    }

    /** The label, or the number formatted like the official client ("1,234"). */
    public static String format(TL_stars.StarGift gift) {
        if (gift == null) {
            return "";
        }
        final String label = label(gift);
        return label != null ? label : LocaleController.formatNumber(gift.num, ',');
    }

    /** The label, or the plain number ("1234"). */
    public static String plain(TL_stars.StarGift gift) {
        if (gift == null) {
            return "";
        }
        final String label = label(gift);
        return label != null ? label : String.valueOf(gift.num);
    }

    /** "&lt;title&gt; #&lt;label or formatted number&gt;". */
    public static String title(TL_stars.StarGift gift) {
        return gift == null ? "" : gift.title + " #" + format(gift);
    }

    /**
     * A plural string whose %d is the gift number ("Collectible #%d"), with the
     * label put in place of the number when the gift has one.
     */
    public static String pluralNumber(String key, TL_stars.StarGift gift) {
        final int num = gift == null ? 0 : gift.num;
        final String text = LocaleController.formatPluralStringComma(key, num);
        final String label = label(gift);
        if (label == null) {
            return text;
        }
        // formatPluralStringComma groups the digits of String.format("%d") with ','.
        final StringBuilder grouped = new StringBuilder(String.format("%d", num));
        for (int a = grouped.length() - 3; a > 0; a -= 3) {
            grouped.insert(a, ',');
        }
        final String digits = grouped.toString();
        final int at = text.indexOf(digits);
        return at < 0 ? text : text.substring(0, at) + label + text.substring(at + digits.length());
    }
}
