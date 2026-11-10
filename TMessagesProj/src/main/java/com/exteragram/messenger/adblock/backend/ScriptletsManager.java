package com.exteragram.messenger.adblock.backend;

import android.content.SharedPreferences;
import android.util.Base64;

import com.exteragram.messenger.backup.PreferencesUtils;

import org.json.JSONArray;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * uBlock Origin redirect/scriptlet resources (web_accessible_resources), cached base64-encoded
 * in the "ublock_scriptlets" preferences (same layout as exteraGram).
 */
public class ScriptletsManager {

    private static final String BASE_URL = "https://raw.githubusercontent.com/gorhill/uBlock/master/src/web_accessible_resources/";

    private static final Map<String, ScriptletInfo> SCRIPTLETS_MAP = new HashMap<>();

    static {
        put("1x1.gif", "1x1-transparent.gif");
        put("2x2.png", "2x2-transparent.png");
        put("3x2.png", "3x2-transparent.png");
        put("32x32.png", "32x32-transparent.png");
        put("amazon_ads.js", "amazon-adsystem.com/aax2/amzn_ads.js");
        put("amazon_apstag.js");
        put("ampproject_v0.js", "ampproject.org/v0.js");
        put("chartbeat.js", "static.chartbeat.com/chartbeat.js");
        put("doubleclick_instream_ad_status.js", "doubleclick.net/instream/ad_status.js");
        put("empty");
        put("fingerprint2.js");
        put("fingerprint3.js");
        put("google-analytics_analytics.js", "google-analytics.com/analytics.js", "googletagmanager_gtm.js", "googletagmanager.com/gtm.js");
        put("google-analytics_cx_api.js", "google-analytics.com/cx/api.js");
        put("google-analytics_ga.js", "google-analytics.com/ga.js");
        put("google-analytics_inpage_linkid.js", "google-analytics.com/inpage_linkid.js");
        put("google-ima.js", "google-ima3");
        put("googlesyndication_adsbygoogle.js", "googlesyndication.com/adsbygoogle.js", "googlesyndication-adsbygoogle");
        put("googletagservices_gpt.js", "googletagservices.com/gpt.js", "googletagservices-gpt");
        put("hd-main.js");
        put("nobab2.js");
        put("noeval.js");
        put("noeval-silent.js", "silent-noeval.js");
        put("nofab.js", "fuckadblock.js-3.2.0");
        put("noop-0.1s.mp3", "noopmp3-0.1s", "abp-resource:blank-mp3");
        put("noop-0.5s.mp3");
        put("noop-1s.mp4", "noopmp4-1s", "abp-resource:blank-mp4");
        put("noop.css");
        put("noop.html", "noopframe");
        put("noop.js", "noopjs", "abp-resource:blank-js");
        put("noop.json", "noopjson");
        put("noop.txt", "nooptext");
        put("noop-vast2.xml", "noopvast-2.0");
        put("noop-vast3.xml", "noopvast-3.0");
        put("noop-vast4.xml", "noopvast-4.0");
        put("noop-vmap1.xml", "noop-vmap1.0.xml", "noopvmap-1.0");
        put("outbrain-widget.js", "widgets.outbrain.com/outbrain.js");
        put("popads.js", "popads.net.js", "prevent-popads-net.js");
        put("popads-dummy.js");
        put("prebid-ads.js");
        put("scorecardresearch_beacon.js", "scorecardresearch.com/beacon.js");
        put("sensors-analytics.js");
        put("nitropay_ads.js");
    }

    private static void put(String name, String... aliases) {
        SCRIPTLETS_MAP.put(name, new ScriptletInfo(aliases));
    }

    private static volatile ScriptletsManager instance;

    private final Object lock = new Object();
    private final DispatchQueue queue = new DispatchQueue("ScriptletsManager");
    private final SharedPreferences prefs = PreferencesUtils.getPreferences("ublock_scriptlets");

    public static ScriptletsManager getInstance() {
        ScriptletsManager local = instance;
        if (local == null) {
            synchronized (ScriptletsManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new ScriptletsManager();
                }
            }
        }
        return local;
    }

    public static String getExtension(String name) {
        if (name.endsWith(".css")) return ".css";
        if (name.endsWith(".gif")) return ".gif";
        if (name.endsWith(".html")) return ".html";
        if (name.endsWith(".js")) return ".js";
        if (name.endsWith(".json")) return ".json";
        if (name.endsWith(".mp3")) return ".mp3";
        if (name.endsWith(".mp4")) return ".mp4";
        if (name.endsWith(".png")) return ".png";
        if (name.endsWith(".xml")) return ".xml";
        return ".txt";
    }

    public void download(Utilities.Callback<Boolean> callback) {
        queue.postRunnable(() -> {
            boolean ok = true;
            for (Map.Entry<String, ScriptletInfo> entry : SCRIPTLETS_MAP.entrySet()) {
                if (!downloadScriptlet(entry.getKey(), entry.getValue())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                synchronized (lock) {
                    prefs.edit().putBoolean("__downloaded", true).apply();
                }
            }
            if (callback != null) {
                callback.run(ok);
            }
        });
    }

    private boolean downloadScriptlet(String name, ScriptletInfo info) {
        synchronized (lock) {
            if (prefs.contains(name)) {
                return true;
            }
        }
        try {
            AdBlockHttp.Response response = AdBlockHttp.get(BASE_URL + name);
            if (response.code == 404) {
                return true; // resource removed upstream, skip it
            }
            if (!response.isSuccessful()) {
                return false;
            }
            String encoded = Base64.encodeToString(response.body, Base64.NO_WRAP);
            synchronized (lock) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(name, encoded);
                if (info.aliases.length > 0) {
                    JSONArray array = new JSONArray();
                    for (String alias : info.aliases) {
                        array.put(alias);
                    }
                    editor.putString(name + "_aliases", array.toString());
                }
                editor.apply();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isDownloaded() {
        synchronized (lock) {
            return prefs.getBoolean("__downloaded", false);
        }
    }

    public Collection<Scriptlet> iterScriptlets() {
        ArrayList<Scriptlet> result = new ArrayList<>();
        synchronized (lock) {
            for (String name : SCRIPTLETS_MAP.keySet()) {
                String content = prefs.getString(name, null);
                if (content == null) {
                    continue;
                }
                ArrayList<String> aliases = new ArrayList<>();
                String aliasesJson = prefs.getString(name + "_aliases", null);
                if (aliasesJson != null) {
                    try {
                        JSONArray array = new JSONArray(aliasesJson);
                        for (int i = 0; i < array.length(); i++) {
                            aliases.add(array.getString(i));
                        }
                    } catch (Exception ignore) {
                    }
                }
                result.add(new Scriptlet(name, aliases, content));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static class Scriptlet {
        public final String filename;
        public final List<String> aliases;
        public final String content;

        private Scriptlet(String filename, List<String> aliases, String content) {
            this.filename = filename;
            this.aliases = aliases;
            this.content = content;
        }
    }

    public static class ScriptletInfo {
        final String[] aliases;

        ScriptletInfo(String[] aliases) {
            this.aliases = aliases != null ? aliases : new String[0];
        }
    }
}
