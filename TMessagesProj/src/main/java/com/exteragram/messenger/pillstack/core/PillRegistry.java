package com.exteragram.messenger.pillstack.core;

import android.content.Context;

import androidx.annotation.Keep;

import com.exteragram.messenger.pillstack.ui.pills.BasePill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.BtcPill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.GramPill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.UsdPill;
import com.exteragram.messenger.pillstack.ui.pills.system.CachePill;
import com.exteragram.messenger.pillstack.ui.pills.system.ProxyPill;
import com.exteragram.messenger.pillstack.ui.pills.weather.WeatherPill;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** exteraGram: registry of available pills (built-in ones; plugins can register more in stage 5). */
public class PillRegistry {

    private static boolean batchRegistration;
    private static final Map<Integer, PillInfo> registry = new LinkedHashMap<>();

    public interface PillCreator {
        BasePill create(Context context, Theme.ResourcesProvider resourcesProvider);
    }

    public static final class PillInfo {
        private final int id;
        private final CharSequence name;
        private final int iconRes;
        private final int iconColorTop;
        private final int iconColorBottom;
        private final PillCreator creator;

        public PillInfo(int id, CharSequence name, int iconRes, int iconColorTop, int iconColorBottom, PillCreator creator) {
            this.id = id;
            this.name = name;
            this.iconRes = iconRes;
            this.iconColorTop = iconColorTop;
            this.iconColorBottom = iconColorBottom;
            this.creator = creator;
        }

        public int id() {
            return id;
        }

        public CharSequence name() {
            return name;
        }

        public int iconRes() {
            return iconRes;
        }

        public int iconColorTop() {
            return iconColorTop;
        }

        public int iconColorBottom() {
            return iconColorBottom;
        }

        public PillCreator creator() {
            return creator;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PillInfo)) {
                return false;
            }
            PillInfo p = (PillInfo) o;
            return id == p.id && iconRes == p.iconRes && iconColorTop == p.iconColorTop && iconColorBottom == p.iconColorBottom && Objects.equals(name, p.name) && Objects.equals(creator, p.creator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, iconRes, iconColorTop, iconColorBottom, name, creator);
        }
    }

    static {
        beginTransaction();
        registerDefaultPills();
        endTransaction();
    }

    @Keep
    public static void beginTransaction() {
        batchRegistration = true;
    }

    @Keep
    public static void endTransaction() {
        batchRegistration = false;
        if (PillStackConfig.getConfigLoaded()) {
            PillStackConfig.sanitizePills();
            PillStackConfig.postOnUiThread(NotificationCenter.pillStackLayoutChanged);
        }
    }

    private static void registerDefaultPills() {
        register(new PillInfo(PillType.WEATHER.getId(), LocaleController.getString(R.string.WeatherPill), R.drawable.weather_cloudy, IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom, WeatherPill::new));
        register(new PillInfo(PillType.GRAM.getId(), "GRAM", R.drawable.settings_gram_24, IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom, GramPill::new));
        register(new PillInfo(PillType.BTC.getId(), "BTC", R.drawable.pillstack_btc_settings, IconBackgroundColors.ORANGE_BRIGHT.top, IconBackgroundColors.ORANGE_BRIGHT.bottom, BtcPill::new));
        register(new PillInfo(PillType.USD.getId(), "USD", R.drawable.pillstack_usd_settings, IconBackgroundColors.GREEN_DEEP.top, IconBackgroundColors.GREEN_DEEP.bottom, UsdPill::new));
        register(new PillInfo(PillType.CACHE.getId(), LocaleController.getString(R.string.StorageUsage), R.drawable.msg_filled_storageusage, IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom, CachePill::new));
        register(new PillInfo(PillType.PROXY.getId(), LocaleController.getString(R.string.Proxy), R.drawable.drawer_proxy_on, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, ProxyPill::new));
    }

    public static void register(PillInfo info) {
        registry.put(info.id, info);
        if (batchRegistration) {
            return;
        }
        PillStackConfig.sanitizePills();
        PillStackConfig.postOnUiThread(NotificationCenter.pillStackLayoutChanged);
    }

    @Keep
    public static void activatePill(int id) {
        if (isRegistered(id) && !PillStackConfig.getActivePills().contains(id)) {
            PillStackConfig.getHiddenPills().remove(Integer.valueOf(id));
            PillStackConfig.getActivePills().add(id);
            PillStackConfig.savePillsLayout();
            PillStackConfig.postOnUiThread(NotificationCenter.pillStackLayoutChanged);
        }
    }

    public static PillInfo getPillInfo(int id) {
        return registry.get(id);
    }

    public static Collection<PillInfo> getRegisteredPills() {
        return registry.values();
    }

    public static boolean isRegistered(int id) {
        return registry.containsKey(id);
    }

    @Keep
    public static void unregister(int id) {
        if (registry.remove(id) == null || batchRegistration) {
            return;
        }
        PillStackConfig.sanitizePills();
        PillStackConfig.postOnUiThread(NotificationCenter.pillStackLayoutChanged);
    }
}
