package com.exteragram.messenger.appicons;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * exteraGram 13.1.1 AppIconPreviewLoader: renders {@link AppIcon} previews (background + foreground, 108dp adaptive
 * layers cropped to the visible 72dp) into bitmaps off the UI thread, caches them and extracts an accent color
 * used to tint the picker header.
 */
public abstract class AppIconPreviewLoader {

    private static LruCache<String, Bitmap> cache;
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Map<String, List<Callback>> pending = new HashMap<>();
    private static final List<Integer> knownSizes = new ArrayList<>();
    private static final Map<String, Integer> accents = new HashMap<>();

    public interface Callback {
        void onPreviewReady(AppIcon icon, int size, Bitmap bitmap);
    }

    private static LruCache<String, Bitmap> getCache() {
        if (cache == null) {
            cache = new LruCache<String, Bitmap>((int) Math.min(12L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
        }
        return cache;
    }

    private static String key(AppIcon icon, int size) {
        return icon.id + "@" + size;
    }

    public static Bitmap getCached(AppIcon icon, int size) {
        return getCache().get(key(icon, size));
    }

    /** Largest cached preview of the icon at any size, or null. */
    public static Bitmap getAnyCached(AppIcon icon) {
        Bitmap best = null;
        for (int i = 0; i < knownSizes.size(); i++) {
            Bitmap bitmap = getCache().get(key(icon, knownSizes.get(i)));
            if (bitmap != null && (best == null || bitmap.getWidth() > best.getWidth())) {
                best = bitmap;
            }
        }
        return best;
    }

    /** Drops cached previews of at least the given size (the big hero previews). */
    public static void trimAbove(int size) {
        for (Map.Entry<String, Bitmap> entry : getCache().snapshot().entrySet()) {
            if (entry.getValue().getWidth() >= size) {
                getCache().remove(entry.getKey());
            }
        }
    }

    public static int getAccent(AppIcon icon) {
        if (icon.color != 0) {
            return icon.color;
        }
        Integer cached = accents.get(icon.id);
        if (cached != null) {
            return cached;
        }
        if (!icon.isBackgroundColor()) {
            return 0;
        }
        int color = getBackgroundColor(icon);
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        if (isNeutral(hsl)) {
            return 0;
        }
        accents.put(icon.id, color);
        return color;
    }

    private static int getBackgroundColor(AppIcon icon) {
        try {
            return ContextCompat.getColor(ApplicationLoader.applicationContext, icon.getBackground());
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    public static int getAccentTextColor(AppIcon icon, int fallbackKey, Theme.ResourcesProvider resourcesProvider) {
        int accent = icon == null ? 0 : getAccent(icon);
        if (accent != 0) {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(accent, hsl);
            if (hsl[1] >= 0.15f) {
                if (Theme.isCurrentThemeDark()) {
                    hsl[2] = Math.max(hsl[2], 0.72f);
                    return clampTone(hsl, 70f, 100f);
                }
                hsl[2] = Math.min(hsl[2], 0.42f);
                return clampTone(hsl, 0f, 42f);
            }
        }
        return Theme.getColor(fallbackKey, resourcesProvider);
    }

    public static int getAccentTint(AppIcon icon) {
        int accent = icon == null ? 0 : getAccent(icon);
        if (accent == 0) {
            return 0;
        }
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        hsl[1] = Math.min(hsl[1], 0.6f);
        if (Theme.isCurrentThemeDark()) {
            hsl[2] = 0.22f;
            return clampTone(hsl, 20f, 100f);
        }
        hsl[2] = 0.86f;
        return clampTone(hsl, 0f, 88f);
    }

    /** Adjusts HSL lightness so that the CIELAB L* of the color is within [min, max]. */
    private static int clampTone(float[] hsl, float min, float max) {
        double[] lab = new double[3];
        ColorUtils.colorToLAB(ColorUtils.HSLToColor(hsl), lab);
        double l = lab[0];
        if (l >= min && l <= max) {
            return ColorUtils.HSLToColor(hsl);
        }
        float target = l >= min ? max : min;
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 16; i++) {
            hsl[2] = (lo + hi) / 2f;
            ColorUtils.colorToLAB(ColorUtils.HSLToColor(hsl), lab);
            if (lab[0] < target) {
                lo = hsl[2];
            } else {
                hi = hsl[2];
            }
        }
        hsl[2] = (lo + hi) / 2f;
        return ColorUtils.HSLToColor(hsl);
    }

    public static void load(AppIcon icon, int size, Callback callback) {
        if (size <= 0) {
            return;
        }
        if (!knownSizes.contains(size)) {
            knownSizes.add(size);
        }
        String key = key(icon, size);
        Bitmap cached = getCache().get(key);
        if (cached != null) {
            callback.onPreviewReady(icon, size, cached);
            return;
        }
        List<Callback> callbacks = pending.get(key);
        if (callbacks != null) {
            callbacks.add(callback);
            return;
        }
        callbacks = new ArrayList<>();
        callbacks.add(callback);
        pending.put(key, callbacks);
        boolean extract = !accents.containsKey(icon.id) && getAccent(icon) == 0;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = render(icon, size);
            int accent = 0;
            if (bitmap != null && extract) {
                accent = extractAccent(bitmap, icon.isBackgroundColor() ? getBackgroundColor(icon) : 0);
            }
            int finalAccent = accent;
            AndroidUtilities.runOnUIThread(() -> {
                if (bitmap != null) {
                    getCache().put(key, bitmap);
                }
                if (finalAccent != 0 && !accents.containsKey(icon.id)) {
                    accents.put(icon.id, finalAccent);
                }
                List<Callback> list = pending.remove(key);
                if (list == null || bitmap == null) {
                    return;
                }
                for (Callback c : list) {
                    c.onPreviewReady(icon, size, bitmap);
                }
            });
        });
    }

    private static Bitmap render(AppIcon icon, int size) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int extra = size / 4;
            Rect rect = new Rect(-extra, -extra, size + extra, size + extra);
            if (icon.isBackgroundColor()) {
                canvas.drawColor(ContextCompat.getColor(ApplicationLoader.applicationContext, icon.getBackground()));
            } else {
                draw(canvas, icon.getBackground(), rect);
            }
            draw(canvas, icon.getForeground(), rect);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean isNeutral(float[] hsl) {
        return hsl[1] < 0.15f || hsl[2] < 0.06f || hsl[2] > 0.94f;
    }

    /** Dominant saturated hue of a 24x24 sample grid, or the average gray when the icon is mostly neutral. */
    private static int extractAccent(Bitmap bitmap, int ignoreColor) {
        float stepX = (bitmap.getWidth() - 1) / 23f;
        float stepY = (bitmap.getHeight() - 1) / 23f;
        float[] hsl = new float[3];
        float[] weight = new float[12];
        float[] hue = new float[12];
        float[] sat = new float[12];
        float[] light = new float[12];
        float neutralCount = 0, neutralLight = 0, colorCount = 0;
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                int pixel = bitmap.getPixel((int) (x * stepX), (int) (y * stepY));
                if (Color.alpha(pixel) < 200 || pixel == ignoreColor) {
                    continue;
                }
                ColorUtils.colorToHSL(pixel, hsl);
                if (isNeutral(hsl)) {
                    neutralCount++;
                    neutralLight += hsl[2];
                } else {
                    int bucket = Math.min(11, (int) (hsl[0] / 360f * 12f));
                    float w = hsl[1] * (1f - Math.abs(hsl[2] * 2f - 1f));
                    weight[bucket] += w;
                    hue[bucket] += hsl[0] * w;
                    sat[bucket] += hsl[1] * w;
                    light[bucket] += hsl[2] * w;
                    colorCount++;
                }
            }
        }
        int best = -1;
        for (int i = 0; i < 12; i++) {
            if (best == -1 || weight[i] > weight[best]) {
                best = i;
            }
        }
        if (best != -1 && weight[best] > 0 && colorCount >= 0.15f * neutralCount) {
            hsl[0] = hue[best] / weight[best];
            hsl[1] = sat[best] / weight[best];
            hsl[2] = light[best] / weight[best];
            return ColorUtils.HSLToColor(hsl);
        }
        if (neutralCount <= 0) {
            return 0;
        }
        hsl[0] = 0;
        hsl[1] = 0;
        hsl[2] = neutralLight / neutralCount;
        return ColorUtils.HSLToColor(hsl);
    }

    private static void draw(Canvas canvas, int res, Rect rect) {
        if (res == 0) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        Resources resources = context.getResources();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inScaled = false;
        BitmapFactory.decodeResource(resources, res, options);
        if (options.outWidth > 0 && options.outHeight > 0) {
            int sample = 1;
            while (options.outWidth / (sample * 2) >= rect.width()) {
                sample *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeResource(resources, res, options);
            if (decoded != null) {
                canvas.drawBitmap(decoded, null, rect, paint);
                return;
            }
        }
        Drawable drawable = ContextCompat.getDrawable(context, res);
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            canvas.drawBitmap(((BitmapDrawable) drawable).getBitmap(), null, rect, paint);
            return;
        }
        if (drawable != null) {
            drawable.setBounds(rect);
            drawable.draw(canvas);
        }
    }
}
