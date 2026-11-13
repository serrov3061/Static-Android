package com.exteragram.messenger.badges;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * StaticGram avatar frames v2: a style plus its optional settings (frames-demo/CONTRACT.md).
 * Wire form: profile list {@code "frame_params": {"palette":"blue","speed":"1.5"}} (strings) and
 * the badge bot query {@code frame <style> key=value ...}. A missing key is the default; unknown
 * keys and values are ignored. Pure Java, no Android dependencies.
 */
public final class FrameParams {

    public static final String INFERNO = "inferno";
    public static final String PIXEL = "pixel";
    public static final String SOLITAIRE = "solitaire";
    public static final String COSMOS = "cosmos";
    public static final String FROST = "frost";
    public static final String NEKO = "neko";
    public static final String ANGEL = "angel";
    public static final String DEVIL = "devil";

    /** Picker order (same as the server). */
    public static final String[] STYLES = {INFERNO, PIXEL, SOLITAIRE, COSMOS, FROST, NEKO, ANGEL, DEVIL};

    public static final String K_PALETTE = "palette";
    public static final String K_METAL = "metal";
    public static final String K_GEM = "gem";
    public static final String K_GEMPOS = "gempos";
    public static final String K_EMBERS = "embers";
    public static final String K_STARS = "stars";
    public static final String K_PLANET = "planet";
    public static final String K_METEORS = "meteors";
    public static final String K_SNOW = "snow";
    public static final String K_CAP = "cap";
    public static final String K_WHISKERS = "whiskers";
    public static final String K_PAW = "paw";
    public static final String K_WINGS = "wings";
    public static final String K_SPARKLE = "sparkle";
    public static final String K_TAIL = "tail";
    public static final String K_PULSE = "pulse";
    public static final String K_SPEED = "speed";
    public static final String K_ANIM = "anim";
    public static final String K_GLOW = "glow";

    public static final String[] SPEEDS = {"0.5", "1", "1.5", "2"};
    public static final int DEFAULT_GLOW = 70;

    public static final String[] INFERNO_PALETTES = {"fire", "blue", "green", "purple", "pink"};
    public static final String[] PIXEL_PALETTES = {"rainbow", "pastel", "neon", "mono", "gold"};
    public static final String[] METALS = {"platinum", "gold", "rose", "black"};
    public static final String[] GEMS = {"diamond", "ruby", "emerald", "sapphire", "amethyst", "none"};
    public static final String[] GEM_POSITIONS = {"bottom", "top", "right"};
    public static final String[] COSMOS_PALETTES = {"violet", "blue", "red", "teal", "gold"};
    public static final String[] FROST_PALETTES = {"ice", "snow", "aurora", "rose"};
    public static final String[] NEKO_PALETTES = {"pink", "black", "white", "ginger", "lilac"};
    public static final String[] ANGEL_PALETTES = {"gold", "silver", "rose", "sky"};
    public static final String[] DEVIL_PALETTES = {"red", "purple", "black", "green"};

    public final String style;
    /** Index into the style's palette list (metal for solitaire). */
    public final int palette;
    public final int gem;
    public final int gemPos;
    /** Style toggle 1 (embers / stars / planet / snow / whiskers / wings / tail) and 2 (meteors / cap / paw / sparkle / pulse). */
    public final boolean toggle1;
    public final boolean toggle2;
    public final float speed;
    public final boolean anim;
    public final int glow;
    private final String key;

    private FrameParams(String style, Map<String, String> values) {
        this.style = style;
        this.palette = indexOf(paletteValues(style), values.get(paletteKey(style)));
        this.gem = SOLITAIRE.equals(style) ? indexOf(GEMS, values.get(K_GEM)) : 0;
        this.gemPos = SOLITAIRE.equals(style) ? indexOf(GEM_POSITIONS, values.get(K_GEMPOS)) : 0;
        String[] toggles = toggleKeys(style);
        this.toggle1 = toggles.length < 1 || !"0".equals(values.get(toggles[0]));
        this.toggle2 = toggles.length < 2 || !"0".equals(values.get(toggles[1]));
        int s = indexOf(SPEEDS, values.get(K_SPEED));
        this.speed = s == 0 && !"0.5".equals(values.get(K_SPEED)) ? 1f : Float.parseFloat(SPEEDS[s]);
        this.anim = !"0".equals(values.get(K_ANIM));
        this.glow = parseGlow(values.get(K_GLOW));
        this.key = style + "|" + palette + "|" + gem + "|" + gemPos + "|" + (toggle1 ? 1 : 0) + (toggle2 ? 1 : 0) + "|" + glow;
    }

    public static boolean isKnownStyle(String style) {
        if (style == null) {
            return false;
        }
        for (String s : STYLES) {
            if (s.equals(style)) {
                return true;
            }
        }
        return false;
    }

    /** Parses a style and a raw param map; null for an unknown style. Unknown keys/values fall back to defaults. */
    public static FrameParams of(String style, Map<String, String> raw) {
        if (!isKnownStyle(style)) {
            return null;
        }
        return new FrameParams(style, sanitize(style, raw));
    }

    public static FrameParams defaults(String style) {
        return of(style, null);
    }

    /** Keeps only the keys and values this style accepts (so the map is safe to store, compare and send). */
    public static Map<String, String> sanitize(String style, Map<String, String> raw) {
        TreeMap<String, String> out = new TreeMap<>();
        if (raw == null || !isKnownStyle(style)) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k == null || v == null) {
                continue;
            }
            k = k.trim();
            v = v.trim();
            String[] allowed = allowedValues(style, k);
            if (allowed != null && indexOfExact(allowed, v) >= 0) {
                out.put(k, v);
            } else if (K_GLOW.equals(k) && parseGlowStrict(v) >= 0) {
                out.put(k, String.valueOf(parseGlowStrict(v)));
            }
        }
        return out;
    }

    /** Values allowed for {@code key} of {@code style}, or null for unknown keys (and for glow, which is a number). */
    public static String[] allowedValues(String style, String key) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case K_SPEED:
                return SPEEDS;
            case K_ANIM:
                return new String[]{"1", "0"};
            case K_GLOW:
                return null;
            default:
                break;
        }
        if (key.equals(paletteKey(style))) {
            return paletteValues(style);
        }
        if (SOLITAIRE.equals(style)) {
            if (K_GEM.equals(key)) {
                return GEMS;
            }
            if (K_GEMPOS.equals(key)) {
                return GEM_POSITIONS;
            }
        }
        for (String t : toggleKeys(style)) {
            if (t.equals(key)) {
                return new String[]{"1", "0"};
            }
        }
        return null;
    }

    public static String paletteKey(String style) {
        return SOLITAIRE.equals(style) ? K_METAL : K_PALETTE;
    }

    public static String[] paletteValues(String style) {
        if (style == null) {
            return new String[]{""};
        }
        switch (style) {
            case INFERNO:
                return INFERNO_PALETTES;
            case PIXEL:
                return PIXEL_PALETTES;
            case SOLITAIRE:
                return METALS;
            case COSMOS:
                return COSMOS_PALETTES;
            case FROST:
                return FROST_PALETTES;
            case NEKO:
                return NEKO_PALETTES;
            case ANGEL:
                return ANGEL_PALETTES;
            case DEVIL:
                return DEVIL_PALETTES;
            default:
                return new String[]{""};
        }
    }

    public static String[] toggleKeys(String style) {
        if (style == null) {
            return new String[0];
        }
        switch (style) {
            case INFERNO:
                return new String[]{K_EMBERS};
            case PIXEL:
                return new String[]{K_STARS};
            case COSMOS:
                return new String[]{K_PLANET, K_METEORS};
            case FROST:
                return new String[]{K_SNOW, K_CAP};
            case NEKO:
                return new String[]{K_WHISKERS, K_PAW};
            case ANGEL:
                return new String[]{K_WINGS, K_SPARKLE};
            case DEVIL:
                return new String[]{K_TAIL, K_PULSE};
            default:
                return new String[0];
        }
    }

    /** Default string value of a key (first allowed value; glow 70). */
    public static String defaultValue(String style, String key) {
        if (K_GLOW.equals(key)) {
            return String.valueOf(DEFAULT_GLOW);
        }
        if (K_SPEED.equals(key)) {
            return "1";
        }
        String[] allowed = allowedValues(style, key);
        return allowed != null && allowed.length > 0 ? allowed[0] : null;
    }

    /** {@code frame <style> key=value ...} with only non-default keys, in a stable order. */
    public static String toCommand(String style, Map<String, String> values) {
        if (!isKnownStyle(style)) {
            return "frame none";
        }
        StringBuilder sb = new StringBuilder("frame ").append(style);
        for (Map.Entry<String, String> e : nonDefault(style, values).entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** The sanitized map without default values (what is sent and stored). */
    public static Map<String, String> nonDefault(String style, Map<String, String> values) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sanitize(style, values).entrySet()) {
            if (!e.getValue().equals(defaultValue(style, e.getKey()))) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out.isEmpty() ? Collections.emptyMap() : out;
    }

    /** Cache key of everything that changes the look of a frame (speed and anim do not). */
    public String key() {
        return key;
    }

    // StaticGram (perf): the last bitmap-cache key with size and theme, so drawing a frame (up to 30 fps
    // in several list cells) does not build a new String on every draw. Used on the UI thread only.
    private String sizedKey;
    private int sizedSide;
    private boolean sizedLight;

    /** {@link #key()} plus the bitmap side and theme: the key of the AvatarFrames layer cache. */
    String sizedKey(int side, boolean light) {
        String k = sizedKey;
        if (k == null || sizedSide != side || sizedLight != light) {
            k = key + "|" + side + (light ? "|l" : "|d");
            sizedKey = k;
            sizedSide = side;
            sizedLight = light;
        }
        return k;
    }

    public String paletteName() {
        return paletteValues(style)[palette];
    }

    private static int indexOf(String[] values, String v) {
        int i = indexOfExact(values, v == null ? null : v.trim());
        return Math.max(0, i);
    }

    private static int indexOfExact(String[] values, String v) {
        if (v == null) {
            return -1;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(v)) {
                return i;
            }
        }
        return -1;
    }

    private static int parseGlow(String v) {
        int g = parseGlowStrict(v);
        return g < 0 ? DEFAULT_GLOW : g;
    }

    private static int parseGlowStrict(String v) {
        if (v == null) {
            return -1;
        }
        try {
            int g = Integer.parseInt(v.trim());
            return g >= 0 && g <= 100 ? g : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
