package com.exteragram.messenger.pillstack.ui.pills.crypto;

import android.annotation.SuppressLint;
import android.content.Context;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.core.PillType;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ColoredBackground;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.PillStackCurrencies;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

@SuppressLint("ViewConstructor")
public class UsdPill extends RatePill {
    private static final RatePill.RateCache CACHE = new RatePill.RateCache();

    public UsdPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "USD", 2, R.drawable.pillstack_usd, new ColoredBackground(0xFF1D8B5D, 0xFF187B59));
    }

    @Override
    public int getPillId() {
        return PillType.USD.getId();
    }

    @Override
    public String getTargetSelection() {
        if ("USD".equalsIgnoreCase(PillStackConfig.getUsdTargetCurrency())) {
            return "AUTO";
        }
        return PillStackConfig.getUsdTargetCurrency();
    }

    @Override
    public void setTargetSelection(String value) {
        PillStackConfig.setUsdTargetCurrency(value);
    }

    @Override
    public String[] getTargetCurrencies() {
        return PillStackCurrencies.getTargetCurrencies("USD");
    }
}
