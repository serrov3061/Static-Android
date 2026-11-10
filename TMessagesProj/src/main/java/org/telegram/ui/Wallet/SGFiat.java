package org.telegram.ui.Wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.exteragram.messenger.utils.network.ExchangeRates;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.tl.TL_sgcard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Locale;

/**
 * The fiat line under a card balance ("≈ 245,90 ₽"), in the user's currency like 13.1.1: the
 * wallet's currency setting, else the account's / device's local currency, else by language,
 * else USD. Same choice and rate sources as the send screen (SGSendMoneyView): the server's
 * wallet currency rate when it matches, else the cached exchange rates.
 */
public final class SGFiat {

    private SGFiat() {
    }

    private static final ArrayList<Runnable> waiting = new ArrayList<>();
    private static boolean fetching;

    /** The fiat currency code the user sees amounts in. */
    public static String preferredFiat(int account) {
        try {
            final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("gram_wallet", Context.MODE_PRIVATE);
            final String set = prefs.getString("currency", null);
            if (!TextUtils.isEmpty(set) && ExchangeRates.isSupportedCurrency(set)) return set;
        } catch (Exception ignore) {
        }
        final String local = ExchangeRates.getLocalCurrency(account);
        if (local != null) return local;
        final Locale l = LocaleController.getInstance().getCurrentLocale();
        final String lang = l == null ? "" : l.getLanguage();
        switch (lang) {
            case "ru": return "RUB";
            case "uk": return "UAH";
            case "be": return "BYN";
            case "kk": return "KZT";
            case "uz": return "UZS";
            default: return "USD";
        }
    }

    /** USD per one unit of the fiat currency, or null while unknown. */
    private static BigDecimal usdPerFiat(int account, String code) {
        if ("USD".equals(code)) return BigDecimal.ONE;
        try {
            final CurrenciesController currencies = GramWallet.getInstance(account).currencies;
            if (currencies != null && TextUtils.equals(currencies.getCurrency(), code)) {
                final double perUsd = currencies.getCurrencyRate();
                if (perUsd > 0 && !Double.isNaN(perUsd) && !Double.isInfinite(perUsd)) {
                    return BigDecimal.ONE.divide(BigDecimal.valueOf(perUsd), 16, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception ignore) {
        }
        final ExchangeRates.State state = ExchangeRates.getCached();
        final BigDecimal usd = state == null ? null : state.getUsdRate(code);
        return usd == null || usd.signum() <= 0 ? null : usd;
    }

    /** USD per GRAM (nanograms / 1e9) or per Star. */
    private static double usdPerUnit(int account, int kind) {
        final MessagesController mc = MessagesController.getInstance(account);
        if (kind == TL_sgcard.KIND_GRAM) return mc.config.tonUsdRate.get();
        return mc.starsUsdSellRate1000 / 100000.0;
    }

    /**
     * "≈ 245,90 ₽" for a card balance (GRAM in nanograms, Stars as is), or null when a rate is
     * not known yet (then {@link #ensureRates} brings it).
     */
    public static String balanceLine(int account, int kind, long balance) {
        final double perUnit = usdPerUnit(account, kind);
        if (perUnit <= 0 || Double.isNaN(perUnit) || Double.isInfinite(perUnit)) return null;
        final double units = kind == TL_sgcard.KIND_GRAM ? balance / (double) SGCardsController.NANO : balance;
        String code = preferredFiat(account);
        BigDecimal usdPerFiat = usdPerFiat(account, code);
        if (usdPerFiat == null) {
            code = "USD";
            usdPerFiat = BigDecimal.ONE;
        }
        final BigDecimal fiat = BigDecimal.valueOf(units * perUnit).divide(usdPerFiat, 12, RoundingMode.HALF_UP);
        return "≈ " + format(code, fiat);
    }

    private static String format(String code, BigDecimal value) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) locale = Locale.getDefault();
        try {
            final Currency currency = Currency.getInstance(code);
            final int digits = Math.max(0, currency.getDefaultFractionDigits());
            final NumberFormat nf = NumberFormat.getCurrencyInstance(locale);
            nf.setCurrency(currency);
            nf.setMinimumFractionDigits(digits);
            nf.setMaximumFractionDigits(digits);
            return nf.format(value);
        } catch (Exception e) {
            return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + code;
        }
    }

    /** Fetches the exchange rates when they are missing or stale; onUpdated runs on the UI thread after. */
    public static void ensureRates(Runnable onUpdated) {
        final ExchangeRates.State cached = ExchangeRates.getCached();
        if (onUpdated != null) waiting.add(onUpdated);
        if (fetching) return;
        fetching = true;
        ExchangeRates.fetch(state -> AndroidUtilities.runOnUIThread(() -> {
            fetching = false;
            final boolean changed = cached == null || state != cached;
            final ArrayList<Runnable> list = new ArrayList<>(waiting);
            waiting.clear();
            if (changed) for (Runnable r : list) r.run();
        }));
    }
}
