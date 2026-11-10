package com.exteragram.messenger.inline;

import android.os.SystemClock;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.inline.rates.CurrencyRates;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.utils.network.ExchangeRates;
import com.google.android.gms.cast.framework.media.NotificationOptions;
import java.math.BigDecimal;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.time.DurationKt;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

public final class InlineRates implements CurrencyRates {

    /* JADX INFO: renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE = new Companion(null);
    private int localAccount = -1;
    private String localCurrency;
    private final Runnable onUpdated;
    private long requestedAt;
    private boolean requesting;

    public InlineRates(Runnable runnable) {
        this.onUpdated = runnable;
    }
    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        public final String normalize(String code) {
            return Intrinsics.areEqual(code, "TON") ? "GRAM" : code;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public final String rateCode(String code) {
            return Intrinsics.areEqual(code, "GRAM") ? "TON" : code;
        }
    }

    @Override // com.exteragram.messenger.inline.rates.CurrencyRates
    public boolean supports(String code) {
        return Intrinsics.areEqual(code, "XTR") || ExchangeRates.isSupportedCurrency(INSTANCE.rateCode(code));
    }

    @Override // com.exteragram.messenger.inline.rates.CurrencyRates
    public Double rate(String from, String to) {
        Double dUsdRate = usdRate(from);
        if (dUsdRate != null) {
            double dDoubleValue = dUsdRate.doubleValue();
            Double dUsdRate2 = usdRate(to);
            if (dUsdRate2 != null) {
                double dDoubleValue2 = dUsdRate2.doubleValue();
                if (dDoubleValue2 == 0.0d) {
                    return null;
                }
                return Double.valueOf(dDoubleValue / dDoubleValue2);
            }
        }
        return null;
    }

    private final Double usdRate(String code) {
        if (Intrinsics.areEqual(code, "XTR")) {
            float f = MessagesController.getInstance(UserConfig.selectedAccount).starsUsdSellRate1000;
            if (f > 0.0f) {
                return Double.valueOf(((double) f) / 100000.0d);
            }
            return null;
        }
        ExchangeRates.State statePeekCached = ExchangeRates.peekCached();
        if (statePeekCached != null && System.currentTimeMillis() - statePeekCached.time() <= 86400000L) {
            BigDecimal usdRate = statePeekCached.getUsdRate(INSTANCE.rateCode(code));
            if (usdRate == null) {
                request(NotificationOptions.SKIP_STEP_THIRTY_SECONDS_IN_MS);
                return null;
            }
            request(300000L);
            return Double.valueOf(usdRate.doubleValue());
        }
        request(NotificationOptions.SKIP_STEP_THIRTY_SECONDS_IN_MS);
        return null;
    }

    public final String localCurrency() {
        String strNormalize = INSTANCE.normalize(ExteraConfig.getInlineTargetCurrency());
        if (supports(strNormalize)) {
            return strNormalize;
        }
        int i = UserConfig.selectedAccount;
        if (i != this.localAccount) {
            this.localAccount = i;
            this.localCurrency = ExchangeRates.getLocalCurrency(i);
        }
        return this.localCurrency;
    }

    private final void request(long interval) {
        long jElapsedRealtime = SystemClock.elapsedRealtime();
        if (this.requesting) {
            return;
        }
        long j = this.requestedAt;
        if (j == 0 || jElapsedRealtime - j >= interval) {
            this.requesting = true;
            this.requestedAt = jElapsedRealtime;
            Utilities.globalQueue.postRunnable(new Runnable() { // from class: com.exteragram.messenger.inline.InlineRates$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    InlineRates.$r8$lambda$yWUFhWyDLzDwP9o8ODAyGTM3ggw(InlineRates.this);
                }
            });
        }
    }

    public static void $r8$lambda$yWUFhWyDLzDwP9o8ODAyGTM3ggw(final InlineRates inlineRates) {
        ExchangeRates.getCached();
        AndroidUtilities.runOnUIThread(new Runnable() { // from class: com.exteragram.messenger.inline.InlineRates$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                ExchangeRates.fetch(new Utilities.Callback() { // from class: com.exteragram.messenger.inline.InlineRates$$ExternalSyntheticLambda2
                    @Override // org.telegram.messenger.Utilities.Callback
                    public final void run(Object obj) {
                        InlineRates.$r8$lambda$dU6anpoGdv6MDeTOASjEEliZ4JE(inlineRates, (ExchangeRates.State) obj);
                    }
                });
            }
        });
    }

    public static void $r8$lambda$dU6anpoGdv6MDeTOASjEEliZ4JE(InlineRates inlineRates, ExchangeRates.State state) {
        inlineRates.requesting = false;
        inlineRates.onUpdated.run();
    }
}
