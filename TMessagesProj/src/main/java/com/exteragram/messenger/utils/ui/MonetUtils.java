/*
 * This is the source code of exteraGram for Android.
 *
 * Monet (Material You dynamic color) helper, ported for exteraStatic from
 * exteraGram (com.exteragram.messenger.utils.ui.MonetUtils) with the receiver
 * idea from Cherrygram (uz.unnarsx.cherrygram.helpers.ui.MonetHelper,
 * Copyright github.com/arsLan4k1390, licensed under GNU GPL v. 2 or later).
 */

package com.exteragram.messenger.utils.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PatternMatcher;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves monet placeholders used in monet_*.attheme assets:
 * a1_10..a3_900, n1_10..n2_900 (system dynamic palette, Android 12+),
 * mBlack / mWhite / mRed200..800 / mGreen200..800 (fixed, red/green harmonized),
 * with optional parameters "(a=alpha%, s=saturation-blend%, l=lightness-blend%)".
 * A value ending with "h" in a theme file is harmonized to the system accent.
 */
public final class MonetUtils {

    private static final String ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED";
    private static final String[] ACCENT_PREFIXES = {"a1_", "a2_", "a3_"};
    private static final Pattern PARAM_PATTERN = Pattern.compile("^([^(]+)\\(([^)]+)\\)?$");
    private static final HashMap<String, Integer> COLOR_MAP = new HashMap<>();
    private static final OverlayChangeReceiver overlayChangeReceiver = new OverlayChangeReceiver();
    private static volatile int harmonizeContextColor;

    static {
        COLOR_MAP.put("mBlack", R.color.black);
        COLOR_MAP.put("mWhite", R.color.white);
        COLOR_MAP.put("mRed200", R.color.mRed200);
        COLOR_MAP.put("mRed500", R.color.mRed500);
        COLOR_MAP.put("mRed800", R.color.mRed800);
        COLOR_MAP.put("mGreen200", R.color.mGreen200);
        COLOR_MAP.put("mGreen500", R.color.mGreen500);
        COLOR_MAP.put("mGreen800", R.color.mGreen800);
        if (isSupported()) {
            initSystemColors();
        }
    }

    private MonetUtils() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static int getColor(String colorString) {
        if (colorString == null || colorString.length() == 0) {
            return 0;
        }
        try {
            int alpha = 100, saturation = 100, lightness = 100;
            String name = colorString.trim();
            Matcher matcher = PARAM_PATTERN.matcher(name);
            if (matcher.find()) {
                String base = matcher.group(1);
                if (base != null) {
                    name = base.trim();
                }
                String params = matcher.group(2);
                if (params != null) {
                    for (String param : params.split(",")) {
                        String[] kv = param.split("=");
                        if (kv.length != 2) {
                            continue;
                        }
                        try {
                            String k = kv[0].trim();
                            int v = Integer.parseInt(kv[1].trim());
                            if ("a".equals(k)) {
                                alpha = v;
                            } else if ("s".equals(k)) {
                                saturation = v;
                            } else if ("l".equals(k)) {
                                lightness = v;
                            }
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }
            Integer res = COLOR_MAP.get(name);
            if (res == null || res == 0) {
                return 0;
            }
            int color = ApplicationLoader.applicationContext.getColor(res);
            if (saturation != 100) {
                color = ColorUtils.blendARGB(0xffffffff, color, saturation / 100f);
            }
            if (lightness != 100) {
                color = ColorUtils.blendARGB(0xff000000, color, lightness / 100f);
            }
            if (alpha != 100) {
                color = ColorUtils.setAlphaComponent(color, (int) (alpha * 2.55f));
            }
            if (name.startsWith("mR") || name.startsWith("mG")) {
                return harmonize(color);
            }
            return color;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    public static int getSystemAccentColor(int index, boolean isDark) {
        if (!isSupported() || index < 0 || index >= ACCENT_PREFIXES.length) {
            return 0;
        }
        return getColor(ACCENT_PREFIXES[index] + (isDark ? 200 : 600));
    }

    // exteraGram 13.1.1 player
    public static int getSecondaryContainerColor(boolean isDark) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            return org.telegram.messenger.ApplicationLoader.applicationContext.getColor(isDark ? android.R.color.system_secondary_container_dark : android.R.color.system_secondary_container_light);
        }
        return getColor(isDark ? "a2_700" : "a2_100");
    }

    public static int getOnSecondaryContainerColor(boolean isDark) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            return org.telegram.messenger.ApplicationLoader.applicationContext.getColor(isDark ? android.R.color.system_on_secondary_container_dark : android.R.color.system_on_secondary_container_light);
        }
        return getColor(isDark ? "a2_100" : "a2_900");
    }

    public static int harmonize(int color) {
        int context = getHarmonizeContextColor();
        return context == 0 ? color : MaterialColors.harmonize(color, context);
    }

    @SuppressLint("NewApi")
    public static int getHarmonizeContextColor() {
        if (!isSupported()) {
            return 0;
        }
        int c = harmonizeContextColor;
        if (c != 0) {
            return c;
        }
        c = ApplicationLoader.applicationContext.getColor(android.R.color.system_accent1_600);
        harmonizeContextColor = c;
        return c;
    }

    public static void registerReceiver(Context context) {
        try {
            harmonizeContextColor = 0;
            overlayChangeReceiver.register(context);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void unregisterReceiver(Context context) {
        try {
            overlayChangeReceiver.unregister(context);
        } catch (Exception ignore) {
        }
    }

    private static class OverlayChangeReceiver extends BroadcastReceiver {
        private boolean isRegistered;

        void register(Context context) {
            if (isRegistered) {
                return;
            }
            IntentFilter filter = new IntentFilter(ACTION_OVERLAY_CHANGED);
            filter.addDataScheme("package");
            filter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
            ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isRegistered = true;
        }

        void unregister(Context context) {
            if (isRegistered) {
                context.unregisterReceiver(this);
                isRegistered = false;
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_OVERLAY_CHANGED.equals(intent.getAction())) {
                harmonizeContextColor = 0;
                Theme.refreshMonetColors();
                if (Theme.isCurrentThemeMonet()) {
                    Theme.applyTheme(Theme.getActiveTheme(), Theme.isCurrentThemeNight());
                }
            }
        }
    }

    @SuppressLint("InlinedApi")
    private static void initSystemColors() {
        int[] shades = {10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900};
        int[][] ids = {
                {android.R.color.system_accent1_10, android.R.color.system_accent1_50, android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900},
                {android.R.color.system_accent2_10, android.R.color.system_accent2_50, android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300, android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600, android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900},
                {android.R.color.system_accent3_10, android.R.color.system_accent3_50, android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300, android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600, android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900},
                {android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300, android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600, android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900},
                {android.R.color.system_neutral2_10, android.R.color.system_neutral2_50, android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600, android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900},
        };
        String[] prefixes = {"a1_", "a2_", "a3_", "n1_", "n2_"};
        for (int p = 0; p < prefixes.length; p++) {
            for (int s = 0; s < shades.length; s++) {
                COLOR_MAP.put(prefixes[p] + shades[s], ids[p][s]);
            }
        }
        COLOR_MAP.put("a1_0", android.R.color.system_accent1_0);
        COLOR_MAP.put("a1_1000", android.R.color.system_accent1_1000);
        COLOR_MAP.put("a2_0", android.R.color.system_accent2_0);
        COLOR_MAP.put("a2_1000", android.R.color.system_accent2_1000);
        COLOR_MAP.put("a3_0", android.R.color.system_accent3_0);
        COLOR_MAP.put("a3_1000", android.R.color.system_accent3_1000);
        COLOR_MAP.put("n1_0", android.R.color.system_neutral1_0);
        COLOR_MAP.put("n1_1000", android.R.color.system_neutral1_1000);
        COLOR_MAP.put("n2_0", android.R.color.system_neutral2_0);
        COLOR_MAP.put("n2_1000", android.R.color.system_neutral2_1000);
    }
}
