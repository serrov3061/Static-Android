package com.exteragram.messenger.pillstack.ui.pills.crypto.utils;

import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.core.PillType;
import com.google.gson.annotations.SerializedName;

import org.telegram.PhoneFormat.CallingCodeInfo;
import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.CacheFetcher;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * exteraGram: exchange rates for the GRAM/BTC/USD pills from the public Coinbase API
 * (https://api.coinbase.com/v2/exchange-rates). exteraGram uses OkHttp; here a plain HttpURLConnection
 * on Utilities.globalQueue is used instead (no extra dependency). Cached in "pillstackconfig".
 */
public final class ExchangeRates {

    private static final String RATES_URL = "https://api.coinbase.com/v2/exchange-rates?currency=USD";

    public static final String[] MAIN_CURRENCIES = {"USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN", "AED", "CNY", "JPY", "BYN", "ILS", "CZK", "INR", "TON", "BTC", "ETH", "SOL"};
    public static final String[] CRYPTO_CURRENCIES = {"BTC", "ETH", "SOL", "TON", "USD", "EUR"};

    private static volatile State cacheValue;
    private static boolean remoteInFlight;
    private static final ArrayList<Utilities.Callback4<Boolean, State, Long, Boolean>> pendingRemote = new ArrayList<>();
    private static final CacheFetcher<Integer, State> fetcher;

    private ExchangeRates() {
    }

    static {
        NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> {
            if (id == NotificationCenter.pillStackSettingsChanged && PillStackConfig.shouldUpdatePill(args, PillType.GRAM.getId(), PillType.BTC.getId(), PillType.USD.getId())) {
                clearCache();
            }
        }, NotificationCenter.pillStackSettingsChanged);
        fetcher = new CacheFetcher<Integer, State>(300000) {
            @Override
            protected boolean saveLastTimeRequested() {
                return true;
            }

            @Override
            protected void getLocal(int currentAccount, Integer args, Utilities.Callback2<Long, State> onResult) {
                onResult.run(0L, getCached());
            }

            @Override
            protected void setLocal(int currentAccount, Integer args, State data, long hash) {
                if (data == null) {
                    return;
                }
                cacheValue = data;
                try {
                    PillStackConfig.getEditor().putString("exchangeRatesCache", ExteraConfig.getGSON().toJson(data)).apply();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            protected void getRemote(int currentAccount, Integer args, long hash, Utilities.Callback4<Boolean, State, Long, Boolean> onResult) {
                synchronized (pendingRemote) {
                    pendingRemote.add(onResult);
                    if (remoteInFlight) {
                        return;
                    }
                    remoteInFlight = true;
                }
                Utilities.globalQueue.postRunnable(() -> completeRemote(requestRates()));
            }

            @Override
            protected long getSavedLastTimeRequested(int hashCode) {
                return PillStackConfig.getPreferences().getLong("exchangeRatesTimestamp", 0L);
            }

            @Override
            protected void setSavedLastTimeRequested(int hashCode, long time) {
                PillStackConfig.getEditor().putLong("exchangeRatesTimestamp", time).apply();
            }
        };
    }

    private static State requestRates() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RATES_URL).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                FileLog.e("exchange rates: unexpected code " + code);
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                return parseState(ExteraConfig.getGSON().fromJson(reader, CoinbaseResponse.class));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static class CoinbaseResponse {
        @SerializedName("data")
        Data data;
    }

    public static class Data {
        @SerializedName("currency")
        String currency;

        @SerializedName("rates")
        Map<String, String> rates;
    }

    public static final class State {
        private final Map<String, BigDecimal> usdRates;

        public State(Map<String, BigDecimal> usdRates) {
            this.usdRates = usdRates;
        }

        public Map<String, BigDecimal> usdRates() {
            return usdRates;
        }

        public BigDecimal getUsdRate(String currency) {
            if (currency == null || usdRates == null) {
                return null;
            }
            // StaticGram: the cached State is read back by Gson; after R8 the map's generic type is
            // erased, so values come back as Double/String instead of BigDecimal (ClassCastException).
            Object value = ((Map<String, ?>) usdRates).get(normalize(currency));
            if (value == null || value instanceof BigDecimal) {
                return (BigDecimal) value;
            }
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public BigDecimal getRate(String base, String target) {
            BigDecimal baseRate = getUsdRate(base);
            BigDecimal targetRate = getUsdRate(target);
            if (baseRate == null || targetRate == null || targetRate.signum() == 0) {
                return null;
            }
            return baseRate.divide(targetRate, 12, RoundingMode.HALF_UP);
        }
    }

    private static void completeRemote(State state) {
        ArrayList<Utilities.Callback4<Boolean, State, Long, Boolean>> callbacks;
        synchronized (pendingRemote) {
            remoteInFlight = false;
            callbacks = new ArrayList<>(pendingRemote);
            pendingRemote.clear();
        }
        for (Utilities.Callback4<Boolean, State, Long, Boolean> callback : callbacks) {
            if (state == null) {
                callback.run(true, null, 0L, false);
            } else {
                callback.run(false, state, 0L, true);
            }
        }
    }

    public static State getCached() {
        if (cacheValue == null) {
            try {
                String json = PillStackConfig.getPreferences().getString("exchangeRatesCache", null);
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
        PillStackConfig.getEditor().putLong("exchangeRatesTimestamp", 0L).apply();
        fetcher.forceRequest(0, 0);
    }

    public static boolean isSupportedCurrency(String currency) {
        if (currency == null) {
            return false;
        }
        String normalized = normalize(currency);
        for (String c : MAIN_CURRENCIES) {
            if (c.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static String resolveTargetCurrency(int account, String selection) {
        String normalized = normalize(selection);
        if (!"AUTO".equals(normalized)) {
            return (TextUtils.isEmpty(normalized) || !isSupportedCurrency(normalized)) ? "USD" : normalized;
        }
        String target = getCurrencyByPhone(account);
        if (target == null) {
            return "USD";
        }
        target = normalize(target);
        return isSupportedCurrency(target) ? target : "USD";
    }

    /** Currency of the account's phone country (BillingController.getTargetCurrency of Telegram 12.10.6). */
    private static String getCurrencyByPhone(int account) {
        try {
            String phone = UserConfig.getInstance(account).getClientPhone();
            if (TextUtils.isEmpty(phone)) {
                return null;
            }
            String digits = PhoneFormat.stripExceptNumbers(phone);
            if (TextUtils.isEmpty(digits)) {
                return null;
            }
            CallingCodeInfo info = PhoneFormat.getInstance().findCallingCodeInfo(digits);
            if (info == null || info.countries == null || info.countries.isEmpty()) {
                return null;
            }
            String country = info.countries.get(0);
            String localeCountry = Locale.getDefault().getCountry();
            if (!TextUtils.isEmpty(localeCountry)) {
                for (String c : info.countries) {
                    if (localeCountry.equalsIgnoreCase(c)) {
                        country = c;
                        break;
                    }
                }
            }
            if (TextUtils.isEmpty(country) || country.charAt(0) == '_') {
                return null;
            }
            Currency currency = Currency.getInstance(new Locale("", country.toUpperCase(Locale.ROOT)));
            return currency != null ? currency.getCurrencyCode() : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void fetch(Utilities.Callback<State> callback) {
        if (callback == null) {
            return;
        }
        fetcher.fetch(0, 0, callback);
    }

    private static State parseState(CoinbaseResponse response) {
        if (response == null || response.data == null || response.data.rates == null) {
            return null;
        }
        HashMap<String, BigDecimal> map = new HashMap<>();
        for (String currency : MAIN_CURRENCIES) {
            BigDecimal rate = parseUsdRate(currency, response.data.rates);
            if (rate != null) {
                map.put(currency, rate);
            }
        }
        return map.isEmpty() ? null : new State(map);
    }

    private static BigDecimal parseUsdRate(String currency, Map<String, String> rates) {
        if ("USD".equals(currency)) {
            return BigDecimal.ONE;
        }
        String value = rates.get(currency);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal rate = new BigDecimal(value);
            if (rate.signum() == 0) {
                return null;
            }
            return BigDecimal.ONE.divide(rate, 16, RoundingMode.HALF_UP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
