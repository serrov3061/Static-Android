package com.exteragram.messenger.utils.network;

import android.content.SharedPreferences;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


/**
 * exteraGram 13.1.1 exchange rates (USD-based, Coinbase), used by inline currency conversion and the rate pill.
 * Rewritten from bytecode: the state is cached in the pill stack preferences and refreshed at most every 5 minutes.
 */
public final class ExchangeRates {

    public static final List<String> FIAT_CURRENCIES = Collections.unmodifiableList(Arrays.asList(
            "AED", "AFN", "ALL", "AMD", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT", "BGN", "BHD", "BIF", "BMD", "BND",
            "BOB", "BRL", "BSD", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF", "CHF", "CLP", "CNY", "COP", "CRC", "CUP", "CVE", "CZK",
            "DJF", "DKK", "DOP", "DZD", "EGP", "ERN", "ETB", "EUR", "FJD", "FKP", "GBP", "GEL", "GHS", "GIP", "GMD", "GNF", "GTQ",
            "GYD", "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR", "IQD", "IRR", "ISK", "JMD", "JOD", "JPY", "KES", "KGS", "KHR",
            "KMF", "KPW", "KRW", "KWD", "KYD", "KZT", "LAK", "LBP", "LKR", "LRD", "LSL", "LYD", "MAD", "MDL", "MGA", "MKD", "MMK",
            "MNT", "MOP", "MRU", "MUR", "MVR", "MWK", "MXN", "MYR", "MZN", "NAD", "NGN", "NIO", "NOK", "NPR", "NZD", "OMR", "PAB",
            "PEN", "PGK", "PHP", "PKR", "PLN", "PYG", "QAR", "RON", "RSD", "RUB", "RWF", "SAR", "SBD", "SCR", "SDG", "SEK", "SGD",
            "SHP", "SLE", "SOS", "SRD", "SSP", "SYP", "SZL", "THB", "TJS", "TMT", "TND", "TOP", "TRY", "TTD", "TWD", "TZS", "UAH",
            "UGX", "USD", "UYU", "UZS", "VES", "VND", "VUV", "WST", "XAF", "XCD", "XOF", "XPF", "YER", "ZAR", "ZMW"));
    private static final Set<String> CURRENCIES = new HashSet<>(FIAT_CURRENCIES);

    static {
        CURRENCIES.addAll(Arrays.asList("TON", "BTC", "ETH", "SOL", "USDT"));
    }

    private static final long REFRESH_INTERVAL = 5 * 60 * 1000L;
    private static final String URL = "https://api.coinbase.com/v2/exchange-rates?currency=USD";
    private static final ArrayList<Utilities.Callback<State>> pending = new ArrayList<>();
    private static volatile State cacheValue;
    private static boolean inFlight;

    private ExchangeRates() {
    }

    public static final class State {
        @SerializedName("usdRates")
        private final Map<String, BigDecimal> usdRates;
        @SerializedName("time")
        private final long time;

        public State(Map<String, BigDecimal> usdRates, long time) {
            this.usdRates = usdRates;
            this.time = time;
        }

        public Map<String, BigDecimal> usdRates() {
            return usdRates;
        }

        public long time() {
            return time;
        }

        /** Value of one unit of the currency in USD. */
        public BigDecimal getUsdRate(String currency) {
            if (currency == null || usdRates == null) return null;
            return usdRates.get(normalize(currency));
        }

        /** How many {@code to} units one {@code from} unit is worth. */
        public BigDecimal getRate(String from, String to) {
            final BigDecimal fromUsd = getUsdRate(from);
            final BigDecimal toUsd = getUsdRate(to);
            if (fromUsd == null || toUsd == null || toUsd.signum() == 0) return null;
            return fromUsd.divide(toUsd, 12, RoundingMode.HALF_UP);
        }
    }

    private static final class CoinbaseResponse {
        Data data;
    }

    private static final class Data {
        String currency;
        Map<String, String> rates;
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isSupportedCurrency(String code) {
        return CURRENCIES.contains(normalize(code));
    }

    public static List<String> getSupportedCurrencies() {
        return new ArrayList<>(CURRENCIES);
    }

    public static String getLocalCurrency(int account) {
        String code = null;
        try {
            final Locale locale = org.telegram.messenger.LocaleController.getInstance().getSystemDefaultLocale();
            code = java.util.Currency.getInstance(locale != null ? locale : Locale.getDefault()).getCurrencyCode();
        } catch (Exception ignore) {
        }
        code = normalize(code);
        return CURRENCIES.contains(code) ? code : null;
    }

    public static State peekCached() {
        return cacheValue;
    }

    public static State getCached() {
        if (cacheValue == null) {
            try {
                final String json = PillStackConfig.getPreferences().getString("exchangeRatesCache", null);
                if (json != null) {
                    cacheValue = ExteraConfig.getGSON().fromJson(json, State.class);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return cacheValue;
    }

    public static void clearCache() {
        PillStackConfig.getEditor().putLong("exchangeRatesTimestamp", 0).apply();
    }

    public static void fetch(Utilities.Callback<State> callback) {
        if (callback == null) return;
        final State cached = getCached();
        final long last = PillStackConfig.getPreferences().getLong("exchangeRatesTimestamp", 0);
        if (cached != null && System.currentTimeMillis() - last < REFRESH_INTERVAL) {
            callback.run(cached);
            return;
        }
        synchronized (pending) {
            pending.add(callback);
            if (inFlight) return;
            inFlight = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            State state = null;
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(URL).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() == 200) {
                    try (java.io.InputStreamReader reader = new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                        state = parse(ExteraConfig.getGSON().fromJson(reader, CoinbaseResponse.class));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (state != null) {
                cacheValue = state;
                final SharedPreferences.Editor editor = PillStackConfig.getEditor();
                editor.putString("exchangeRatesCache", ExteraConfig.getGSON().toJson(state));
                editor.putLong("exchangeRatesTimestamp", System.currentTimeMillis());
                editor.apply();
            }
            complete(state != null ? state : getCached());
        });
    }

    private static void complete(State state) {
        final ArrayList<Utilities.Callback<State>> callbacks;
        synchronized (pending) {
            inFlight = false;
            callbacks = new ArrayList<>(pending);
            pending.clear();
        }
        for (Utilities.Callback<State> c : callbacks) {
            try {
                c.run(state);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static State parse(CoinbaseResponse response) {
        if (response == null || response.data == null || response.data.rates == null) return null;
        final HashMap<String, BigDecimal> rates = new HashMap<>();
        for (String code : CURRENCIES) {
            final BigDecimal usd = parseUsdRate(code, response.data.rates);
            if (usd != null) rates.put(code, usd);
        }
        return rates.isEmpty() ? null : new State(rates, System.currentTimeMillis());
    }

    private static BigDecimal parseUsdRate(String code, Map<String, String> rates) {
        if ("USD".equals(code)) return BigDecimal.ONE;
        final String value = rates.get(code);
        if (value == null) return null;
        try {
            final BigDecimal perUsd = new BigDecimal(value);
            if (perUsd.signum() == 0) return null;
            return BigDecimal.ONE.divide(perUsd, 16, RoundingMode.HALF_UP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
