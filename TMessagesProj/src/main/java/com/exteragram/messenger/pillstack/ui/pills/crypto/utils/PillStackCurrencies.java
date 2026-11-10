package com.exteragram.messenger.pillstack.ui.pills.crypto.utils;

import org.telegram.messenger.BillingController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** exteraGram: target currencies of the rate pills and fiat price formatting. */
public final class PillStackCurrencies {

    private static final HashSet<String> AMBIGUOUS_SYMBOLS = new HashSet<>(Arrays.asList("$", "kr", "Fr", "₩"));
    private static final Map<String, CurrencyInfo> CURRENCIES = new HashMap<>();
    public static final String[] TARGET_CURRENCIES = {"AUTO", "AED", "BYN", "CNY", "CZK", "EUR", "GBP", "ILS", "INR", "JPY", "KZT", "PLN", "RUB", "TRY", "UAH", "USD"};

    private PillStackCurrencies() {
    }

    private static final class CurrencyInfo {
        final String code;
        final int nameResId;
        final String symbolOverride;
        final boolean suffixSymbol;

        CurrencyInfo(String code, int nameResId, String symbolOverride, boolean suffixSymbol) {
            this.code = code;
            this.nameResId = nameResId;
            this.symbolOverride = symbolOverride;
            this.suffixSymbol = suffixSymbol;
        }
    }

    static {
        addCurrency("USD", R.string.CryptoCurrencyUsd, "$", false);
        addCurrency("EUR", R.string.CryptoCurrencyEur, null, false);
        addCurrency("RUB", R.string.CryptoCurrencyRub, "₽", true);
        addCurrency("GBP", R.string.CryptoCurrencyGbp, null, false);
        addCurrency("KZT", R.string.CryptoCurrencyKzt, "₸", true);
        addCurrency("TRY", R.string.CryptoCurrencyTry, "₺", true);
        addCurrency("UAH", R.string.CryptoCurrencyUah, "₴", true);
        addCurrency("PLN", R.string.CryptoCurrencyPln, "zł", true);
        addCurrency("AED", R.string.CryptoCurrencyAed, null, false);
        addCurrency("CNY", R.string.CryptoCurrencyCny, "CN¥", false);
        addCurrency("JPY", R.string.CryptoCurrencyJpy, null, false);
        addCurrency("BYN", R.string.CryptoCurrencyByn, "Br", true);
        addCurrency("ILS", R.string.CryptoCurrencyIls, "₪", false);
        addCurrency("CZK", R.string.CryptoCurrencyCzk, "Kč", true);
        addCurrency("INR", R.string.CryptoCurrencyInr, "₹", false);
    }

    private static void addCurrency(String code, int nameResId, String symbol, boolean suffix) {
        String normalized = normalize(code);
        if (normalized.isEmpty()) {
            return;
        }
        CURRENCIES.put(normalized, new CurrencyInfo(normalized, nameResId, symbol, suffix));
    }

    public static CharSequence getTargetCurrencyLabel(String code) {
        if (code == null || "AUTO".equalsIgnoreCase(code)) {
            return LocaleController.getString(R.string.QualityAuto);
        }
        return getCurrencyLabelWithCode(code);
    }

    public static CharSequence getTargetCurrencySubtext(String code) {
        if (code == null || "AUTO".equalsIgnoreCase(code)) {
            return LocaleController.getString(R.string.QualityAuto);
        }
        return getCurrencyName(code);
    }

    public static String getCurrencyName(String code) {
        String normalized = normalize(code);
        CurrencyInfo info = CURRENCIES.get(normalized);
        return info == null ? normalized : LocaleController.getString(info.nameResId);
    }

    public static String getCurrencyLabelWithCode(String code) {
        String normalized = normalize(code);
        CurrencyInfo info = CURRENCIES.get(normalized);
        if (info == null) {
            return normalized;
        }
        return LocaleController.getString(info.nameResId) + " — " + normalized;
    }

    public static String[] getTargetCurrencies(String exclude) {
        if (exclude == null || exclude.isEmpty()) {
            return TARGET_CURRENCIES;
        }
        return Arrays.stream(TARGET_CURRENCIES).filter(c -> !exclude.equalsIgnoreCase(c)).toArray(String[]::new);
    }

    public static String formatFiatPrice(BigDecimal value, String code) {
        if (value == null || code == null || code.isEmpty()) {
            return null;
        }
        try {
            int exp = Math.max(0, BillingController.getInstance().getCurrencyExp(code));
            BigDecimal scaled = value.setScale(exp, RoundingMode.HALF_UP);
            NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
            format.setGroupingUsed(true);
            format.setMinimumFractionDigits(exp);
            format.setMaximumFractionDigits(exp);
            String number = format.format(scaled);
            String normalized = normalize(code);
            CurrencyInfo info = CURRENCIES.get(normalized);
            String symbol = info != null ? info.symbolOverride : null;
            boolean overridden = symbol != null;
            if (!overridden) {
                try {
                    symbol = Currency.getInstance(normalized).getSymbol(Locale.US);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (symbol == null || symbol.isEmpty() || symbol.equalsIgnoreCase(normalized)) {
                return number + " " + code;
            }
            if (!overridden && AMBIGUOUS_SYMBOLS.contains(symbol)) {
                return number + " " + code;
            }
            if (info != null && info.suffixSymbol) {
                return number + " " + symbol;
            }
            return symbol + number;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
