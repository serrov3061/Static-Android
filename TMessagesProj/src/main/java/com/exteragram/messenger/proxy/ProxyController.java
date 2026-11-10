package com.exteragram.messenger.proxy;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * exteraGram proxy manager: names, pins (with order), sorting, share links with title, country lookup.
 * <p>
 * StaticGram port: the proxy list itself stays in stock {@link SharedConfig} (12.10.5 storage format,
 * StaticGram links), this class only keeps metadata in mainconfig: {@code proxy_names},
 * {@code proxy_pinned}, {@code proxy_pinned_order} (same keys as exteraGram). Without names/pins
 * the behaviour is stock Telegram. No exteraGram servers are contacted.
 */
public final class ProxyController {

    private static final ProxyController INSTANCE = new ProxyController();
    private static final int MAX_PINNED = 10;

    private boolean loaded;
    private final HashSet<String> pinnedProxies = new HashSet<>();
    private final ArrayList<String> pinnedProxyOrder = new ArrayList<>();
    private final HashMap<String, String> proxyNames = new HashMap<>();

    public enum PinOperationResult {
        NO_CHANGE,
        CHANGED,
        LIMIT_REACHED
    }

    public interface ProxyCountryCallback {
        void onCountryResolved(String country);
    }

    public static ProxyController getInstance() {
        return INSTANCE;
    }

    private ProxyController() {
    }

    public int getMaxPinnedProxies() {
        return MAX_PINNED;
    }

    // ----- list (delegates to SharedConfig) -----

    public synchronized ArrayList<SharedConfig.ProxyInfo> getProxyList() {
        ensureLoaded();
        return new ArrayList<>(SharedConfig.proxyList);
    }

    public synchronized SharedConfig.ProxyInfo getCurrentProxy() {
        ensureLoaded();
        return SharedConfig.currentProxy;
    }

    public synchronized void setCurrentProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        SharedConfig.currentProxy = proxyInfo;
    }

    public synchronized void loadProxyList() {
        ensureLoaded();
    }

    public synchronized void saveProxyList() {
        ensureLoaded();
        SharedConfig.saveProxyList();
    }

    public synchronized SharedConfig.ProxyInfo addProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        return SharedConfig.addProxy(proxyInfo);
    }

    /**
     * Adds/keeps the proxy in the list; moves name/pin from {@code oldLink} when the proxy was edited,
     * and sets the name when {@code name != null}.
     */
    public synchronized SharedConfig.ProxyInfo saveProxy(SharedConfig.ProxyInfo proxyInfo, String oldLink, String name) {
        ensureLoaded();
        String newLink = getProxyKey(proxyInfo);
        if (!TextUtils.isEmpty(oldLink) && !TextUtils.equals(oldLink, newLink)) {
            moveMetadata(oldLink, newLink);
        }
        SharedConfig.ProxyInfo result = SharedConfig.addProxy(proxyInfo);
        if (name != null) {
            setName(result, name);
        }
        return result;
    }

    public synchronized void deleteProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        SharedConfig.deleteProxy(proxyInfo);
        removeProxy(proxyInfo);
    }

    // ----- names -----

    public synchronized String buildShareLink(SharedConfig.ProxyInfo proxyInfo) {
        return buildShareLink(proxyInfo, null);
    }

    public synchronized String buildShareLink(SharedConfig.ProxyInfo proxyInfo, String name) {
        if (proxyInfo == null) {
            return "";
        }
        String link = proxyInfo.settings.getLink();
        String title = normalizeName(name);
        if (TextUtils.isEmpty(title)) {
            title = getName(proxyInfo);
        }
        if (TextUtils.isEmpty(title)) {
            return link;
        }
        try {
            return link + "&title=" + URLEncoder.encode(title, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return link;
        }
    }

    public synchronized String getDisplayName(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return "";
        }
        String name = getName(proxyInfo);
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        return proxyInfo.settings.getAddress() + ":" + proxyInfo.settings.getPort();
    }

    public synchronized String getProxyTypeName(SharedConfig.ProxyInfo proxyInfo) {
        return LocaleController.getString(proxyInfo != null && proxyInfo.settings.getType() == org.telegram.utils.proxy.ProxySettings.Type.WEB ? R.string.UseProxyWeb : isTelegramProxy(proxyInfo) ? R.string.UseProxyTelegram : R.string.UseProxySocks5);
    }

    public synchronized String getName(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return "";
        }
        return getName(getProxyKey(proxyInfo));
    }

    public synchronized String getName(String key) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        String name = proxyNames.get(key);
        return name == null ? "" : name;
    }

    public synchronized void setName(SharedConfig.ProxyInfo proxyInfo, String name) {
        setName(getProxyKey(proxyInfo), name);
    }

    public synchronized void setName(String key, String name) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return;
        }
        String normalized = normalizeName(name);
        boolean changed;
        if (TextUtils.isEmpty(normalized)) {
            changed = proxyNames.remove(key) != null;
        } else {
            changed = !TextUtils.equals(proxyNames.put(key, normalized), normalized);
        }
        if (changed) {
            save();
        }
    }

    public synchronized void moveMetadata(String from, String to) {
        ensureLoaded();
        if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to) || TextUtils.equals(from, to)) {
            return;
        }
        boolean changed = false;
        String name = proxyNames.remove(from);
        if (!TextUtils.isEmpty(name)) {
            proxyNames.put(to, name);
            changed = true;
        }
        int index = pinnedProxyOrder.indexOf(from);
        if (index >= 0) {
            pinnedProxyOrder.remove(index);
            pinnedProxies.remove(from);
            if (!pinnedProxyOrder.contains(to)) {
                pinnedProxyOrder.add(Math.min(index, pinnedProxyOrder.size()), to);
            }
            pinnedProxies.add(to);
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    // ----- pins -----

    public synchronized boolean isPinned(SharedConfig.ProxyInfo proxyInfo) {
        return proxyInfo != null && isPinned(getProxyKey(proxyInfo));
    }

    public synchronized boolean isPinned(String key) {
        ensureLoaded();
        return !TextUtils.isEmpty(key) && pinnedProxies.contains(key);
    }

    public synchronized int getPinnedIndex(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        if (proxyInfo == null) {
            return -1;
        }
        String key = getProxyKey(proxyInfo);
        return TextUtils.isEmpty(key) ? -1 : pinnedProxyOrder.indexOf(key);
    }

    public synchronized int getPinnedCount() {
        ensureLoaded();
        return pinnedProxyOrder.size();
    }

    /** 0 - mixed / nothing, 1 - pin all, 2 - unpin all. */
    public synchronized int getSelectedPinAction(List<SharedConfig.ProxyInfo> list) {
        ensureLoaded();
        boolean hasPinned = false;
        boolean hasUnpinned = false;
        for (SharedConfig.ProxyInfo info : list) {
            if (isPinned(info)) {
                hasPinned = true;
            } else {
                hasUnpinned = true;
            }
            if (hasPinned && hasUnpinned) {
                return 0;
            }
        }
        if (hasPinned) {
            return 2;
        }
        return hasUnpinned ? 1 : 0;
    }

    public synchronized PinOperationResult applySelectedPinAction(List<SharedConfig.ProxyInfo> list) {
        ensureLoaded();
        int action = getSelectedPinAction(list);
        if (action == 0) {
            return PinOperationResult.NO_CHANGE;
        }
        boolean changed = false;
        if (action == 1) {
            ArrayList<String> keys = new ArrayList<>();
            for (SharedConfig.ProxyInfo info : list) {
                String key = getProxyKey(info);
                if (!TextUtils.isEmpty(key) && !pinnedProxies.contains(key) && !keys.contains(key)) {
                    keys.add(key);
                }
            }
            if (pinnedProxyOrder.size() + keys.size() > MAX_PINNED) {
                return PinOperationResult.LIMIT_REACHED;
            }
            for (String key : keys) {
                if (pinnedProxies.add(key)) {
                    pinnedProxyOrder.add(key);
                    changed = true;
                }
            }
        } else {
            for (SharedConfig.ProxyInfo info : list) {
                String key = getProxyKey(info);
                if (!TextUtils.isEmpty(key)) {
                    if (pinnedProxies.remove(key)) {
                        changed = true;
                    }
                    if (pinnedProxyOrder.remove(key)) {
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            save();
            return PinOperationResult.CHANGED;
        }
        return PinOperationResult.NO_CHANGE;
    }

    public synchronized boolean movePinnedProxy(SharedConfig.ProxyInfo from, SharedConfig.ProxyInfo to) {
        ensureLoaded();
        String fromKey = getProxyKey(from);
        String toKey = getProxyKey(to);
        if (TextUtils.isEmpty(fromKey) || TextUtils.isEmpty(toKey) || TextUtils.equals(fromKey, toKey)) {
            return false;
        }
        if (!pinnedProxies.contains(fromKey) || !pinnedProxies.contains(toKey)) {
            return false;
        }
        int fromIndex = pinnedProxyOrder.indexOf(fromKey);
        int toIndex = pinnedProxyOrder.indexOf(toKey);
        if (fromIndex < 0 || toIndex < 0) {
            return false;
        }
        pinnedProxyOrder.remove(fromIndex);
        pinnedProxyOrder.add(toIndex, fromKey);
        save();
        return true;
    }

    /** Removes the proxy's metadata (name / pin). */
    public synchronized void removeProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        String key = getProxyKey(proxyInfo);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        boolean changed = pinnedProxyOrder.remove(key);
        changed |= proxyNames.remove(key) != null;
        changed |= pinnedProxies.remove(key);
        if (changed) {
            save();
        }
    }

    public synchronized void clearAll() {
        ensureLoaded();
        if (pinnedProxies.isEmpty() && pinnedProxyOrder.isEmpty() && proxyNames.isEmpty()) {
            return;
        }
        pinnedProxies.clear();
        pinnedProxyOrder.clear();
        proxyNames.clear();
        save();
    }

    /**
     * Pinned proxies first (in pin order), then the stock Telegram order: current first, available
     * by ping, unavailable last; while the list is being checked, keeps the stored order.
     */
    public synchronized void sortProxyList(List<SharedConfig.ProxyInfo> list, boolean checking, SharedConfig.ProxyInfo current) {
        ensureLoaded();
        final ArrayList<SharedConfig.ProxyInfo> stored = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(list, (o1, o2) -> {
            int p1 = getPinnedIndex(o1);
            int p2 = getPinnedIndex(o2);
            boolean pinned1 = p1 >= 0;
            if (pinned1 != (p2 >= 0)) {
                return pinned1 ? -1 : 1;
            }
            if (pinned1) {
                return Integer.compare(p1, p2);
            }
            long bias1 = current == o1 ? -200000 : 0;
            if (!o1.available) {
                bias1 += 100000;
            }
            long bias2 = current == o2 ? -200000 : 0;
            if (!o2.available) {
                bias2 += 100000;
            }
            return Long.compare(checking && o1 != current ? stored.indexOf(o1) * 10000L : o1.ping + bias1,
                    checking && o2 != current ? stored.indexOf(o2) * 10000L : o2.ping + bias2);
        });
    }

    // ----- country (ipwho.is, only on explicit request) -----

    public void requestProxyCountry(SharedConfig.ProxyInfo proxyInfo, final ProxyCountryCallback callback) {
        if (callback == null) {
            return;
        }
        final String unknown = LocaleController.getString(R.string.Unknown);
        final String host = getProxyCountryKey(proxyInfo);
        if (TextUtils.isEmpty(host)) {
            AndroidUtilities.runOnUIThread(() -> callback.onCountryResolved(unknown));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String ip = resolveProxyIpAddress(host);
            if (TextUtils.isEmpty(ip)) {
                AndroidUtilities.runOnUIThread(() -> callback.onCountryResolved(unknown));
                return;
            }
            IpAddressInfoController.requestIpAddressInfo(ip, info -> {
                String result = info != null ? getCountryDisplayName(info.countryCode) : unknown;
                AndroidUtilities.runOnUIThread(() -> callback.onCountryResolved(result));
            });
        });
    }

    // ----- storage -----

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private void load() {
        SharedConfig.loadProxyList();
        pinnedProxies.clear();
        pinnedProxyOrder.clear();
        proxyNames.clear();
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        try {
            String order = preferences.getString("proxy_pinned_order", preferences.getString("proxy_pinned_links_order", null));
            if (!TextUtils.isEmpty(order)) {
                for (String key : order.split("\\n")) {
                    if (!TextUtils.isEmpty(key) && pinnedProxies.add(key)) {
                        pinnedProxyOrder.add(key);
                    }
                }
            }
            Set<String> pinned = preferences.getStringSet("proxy_pinned", preferences.getStringSet("proxy_pinned_links", null));
            if (pinned != null) {
                for (String key : pinned) {
                    if (!TextUtils.isEmpty(key) && pinnedProxies.add(key)) {
                        pinnedProxyOrder.add(key);
                    }
                }
            }
            String names = preferences.getString("proxy_names", null);
            if (!TextUtils.isEmpty(names)) {
                for (String line : names.split("\\n")) {
                    int tab;
                    if (!TextUtils.isEmpty(line) && (tab = line.indexOf('\t')) > 0 && tab < line.length() - 1) {
                        String key = decode(line.substring(0, tab));
                        String name = decode(line.substring(tab + 1));
                        if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(name)) {
                            proxyNames.put(key, name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        loaded = true;
    }

    private void save() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        if (pinnedProxies.isEmpty()) {
            editor.remove("proxy_pinned");
        } else {
            editor.putStringSet("proxy_pinned", new HashSet<>(pinnedProxies));
        }
        if (pinnedProxyOrder.isEmpty()) {
            editor.remove("proxy_pinned_order");
        } else {
            editor.putString("proxy_pinned_order", TextUtils.join("\n", pinnedProxyOrder));
        }
        editor.remove("proxy_pinned_links");
        editor.remove("proxy_pinned_links_order");
        if (proxyNames.isEmpty()) {
            editor.remove("proxy_names");
        } else {
            ArrayList<String> lines = new ArrayList<>(proxyNames.size());
            for (Map.Entry<String, String> entry : proxyNames.entrySet()) {
                lines.add(encode(entry.getKey()) + "\t" + encode(entry.getValue()));
            }
            editor.putString("proxy_names", TextUtils.join("\n", lines));
        }
        editor.apply();
    }

    private static String getProxyKey(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return null;
        }
        String link = proxyInfo.settings.getLink();
        return TextUtils.isEmpty(link) ? null : link;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private static String encode(String value) {
        return Base64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        try {
            return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String resolveProxyIpAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(IDN.toASCII(host));
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                return null;
            }
            return address.getHostAddress();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String getProxyCountryKey(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null || TextUtils.isEmpty(proxyInfo.settings.getAddress())) {
            return "";
        }
        String host = proxyInfo.settings.getAddress().trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.US);
    }

    private static String getCountryDisplayName(String countryCode) {
        if (TextUtils.isEmpty(countryCode)) {
            return LocaleController.getString(R.string.Unknown);
        }
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        String name = new Locale("", countryCode.toUpperCase(Locale.US)).getDisplayCountry(locale);
        return TextUtils.isEmpty(name) ? LocaleController.getString(R.string.Unknown) : name;
    }

    private static boolean isTelegramProxy(SharedConfig.ProxyInfo proxyInfo) {
        return proxyInfo != null && !TextUtils.isEmpty(proxyInfo.settings.getSecret());
    }
}
