package org.telegram.ui.Wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Shared formatter for balances that may be huge: full number while it fits, compact (K/M/B/T/Q) above. */
public final class SGAmounts {
    private SGAmounts() {}

    private static final long COMPACT_FROM = 1_000_000L;
    private static final String[] UNITS = {"", "K", "M", "B", "T", "Q"};

    /** 1234567 -> "1.2M", 36011002073262544 -> "36Q". Truncates, so the shown value is never above the real one. */
    public static String compact(BigDecimal value) {
        final boolean neg = value.signum() < 0;
        BigDecimal v = value.abs();
        int unit = 0;
        final BigDecimal thousand = BigDecimal.valueOf(1000);
        while (v.compareTo(thousand) >= 0 && unit < UNITS.length - 1) {
            v = v.divide(thousand);
            unit++;
        }
        final int scale = v.compareTo(BigDecimal.valueOf(100)) >= 0 ? 0 : 1;
        String s = v.setScale(scale, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        return (neg ? "-" : "") + s + UNITS[unit];
    }

    /** Stars: full grouped number below 1 000 000, compact above. */
    public static String stars(long stars, char sep) {
        if (Math.abs(stars) < COMPACT_FROM) {
            final DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
            sym.setGroupingSeparator(sep);
            return new DecimalFormat("#,##0", sym).format(stars);
        }
        return compact(BigDecimal.valueOf(stars));
    }

    /** GRAM (nanotons): at most 2 decimals (4 for dust), trailing zeros trimmed, compact from 1 000 000. */
    public static String gram(long nanotons) {
        final BigDecimal v = BigDecimal.valueOf(nanotons, 9);
        if (v.abs().compareTo(BigDecimal.valueOf(COMPACT_FROM)) >= 0) {
            return compact(v);
        }
        final int decimals = v.signum() != 0 && v.abs().compareTo(new BigDecimal("0.01")) < 0 ? 4 : 2;
        final BigDecimal r = v.setScale(decimals, RoundingMode.DOWN);
        final DecimalFormat f = new DecimalFormat(decimals == 2 ? "#,##0.##" : "#,##0.####", new DecimalFormatSymbols(Locale.US));
        f.setRoundingMode(RoundingMode.DOWN);
        return f.format(r);
    }
}
