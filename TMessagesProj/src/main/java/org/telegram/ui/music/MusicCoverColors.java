package org.telegram.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.PathParser;
import androidx.palette.graphics.Palette;
import androidx.palette.graphics.Target;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Colors picked from a track cover: a three stop background gradient (top, middle, bottom) and a
 * light accent for the progress. Cached per track; listeners hear when the current track's colors change.
 */
public final class MusicCoverColors {

    public static final MusicCoverColors DEFAULT = new MusicCoverColors(0xFF2B2A33, 0xFF1F1E26, 0xFF141319, 0xFFB8B2FF);

    private static final HashMap<String, MusicCoverColors> cache = new HashMap<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>();
    private static MusicCoverColors last;
    private static String lastKey;
    private static ImageReceiver loader;
    private static String loadingKey;

    public final int top;
    public final int middle;
    public final int bottom;
    public final int accent;

    private MusicCoverColors(int top, int middle, int bottom, int accent) {
        this.top = top;
        this.middle = middle;
        this.bottom = bottom;
        this.accent = accent;
    }

    /** Full screen background of the player: vertical gradient plus a soft accent glow near the top. */
    public static class BackgroundDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private MusicCoverColors from = DEFAULT;
        private MusicCoverColors to = DEFAULT;
        private MusicCoverColors shown = DEFAULT;
        private ValueAnimator animator;
        private int lastWidth;
        private int lastHeight;
        private Runnable onChange;
        private Runnable onUpdate;

        public void setOnChange(Runnable onChange) {
            this.onChange = onChange;
        }

        /** Called on every color step, including each frame of the cover-change animation. */
        public void setOnUpdate(Runnable onUpdate) {
            this.onUpdate = onUpdate;
        }

        private void notifyChange() {
            if (onChange != null) {
                onChange.run();
            }
        }

        public MusicCoverColors getColors() {
            return shown;
        }

        public void setColors(MusicCoverColors colors, boolean animated) {
            if (colors == null) {
                colors = DEFAULT;
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated) {
                shown = to = from = colors;
                updateShaders();
                notifyChange();
                return;
            }
            from = shown;
            to = colors;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(520);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            animator.addUpdateListener(a -> {
                shown = lerp(from, to, (float) a.getAnimatedValue());
                updateShaders();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    notifyChange();
                }
            });
            animator.start();
        }

        private void updateShaders() {
            if (onUpdate != null) {
                onUpdate.run();
            }
            final Rect bounds = getBounds();
            if (bounds.height() <= 0) {
                invalidateSelf();
                return;
            }
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[] { shown.top, shown.middle, shown.bottom }, new float[] { 0f, 0.48f, 1f }, Shader.TileMode.CLAMP));
            glowPaint.setShader(new RadialGradient(bounds.centerX(), bounds.top + bounds.height() * 0.22f,
                Math.max(bounds.width(), bounds.height()) * 0.55f,
                new int[] { ColorUtils.setAlphaComponent(shown.accent, 56), ColorUtils.setAlphaComponent(shown.accent, 0) },
                null, Shader.TileMode.CLAMP));
            lastHeight = bounds.height();
            lastWidth = bounds.width();
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            if (bounds.height() == lastHeight && bounds.width() == lastWidth) {
                return;
            }
            updateShaders();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect bounds = getBounds();
            canvas.drawRect(bounds, paint);
            canvas.drawRect(bounds, glowPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            glowPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /** Cover stand-in: gradient tile in the theme colors with a music note in the middle. */
    public static class PlaceholderDrawable extends Drawable {
        private static final String NOTE = "M21 3v12.5c0 1.93-1.57 3.5-3.5 3.5S14 17.43 14 15.5s1.57-3.5 3.5-3.5c.54 0 1.05.12 1.5.34V6.47L9 8.6v8.9C9 19.43 7.43 21 5.5 21S2 19.43 2 17.5 3.57 14 5.5 14c.54 0 1.05.12 1.5.34V5l14-2z";

        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path notePath;
        private final Path scaled = new Path();
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private final int light;
        private final int dark;
        private final int deep;
        private float radius;

        public PlaceholderDrawable() {
            final MusicCoverColors theme = theme();
            light = ColorUtils.blendARGB(theme.top, theme.accent, 0.35f);
            dark = theme.middle;
            deep = theme.bottom;
            Path path;
            try {
                path = PathParser.createPathFromPathData(NOTE);
            } catch (Throwable ignore) {
                path = new Path();
            }
            notePath = path;
            note.setColor(0x8CFFFFFF);
        }

        public void setRadius(float radius) {
            if (this.radius != radius) {
                this.radius = radius;
                invalidateSelf();
            }
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            super.onBoundsChange(bounds);
            background.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[] { light, dark, deep }, new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
            shine.setShader(new RadialGradient(bounds.left + bounds.width() * 0.25f, bounds.top + bounds.height() * 0.15f,
                Math.max(1f, bounds.width() * 0.8f), new int[] { 0x26FFFFFF, 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
            final float size = Math.min(bounds.width(), bounds.height()) * 0.44f;
            matrix.reset();
            final float scale = size / 24f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f);
            scaled.reset();
            notePath.transform(matrix, scaled);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            canvas.drawRoundRect(rect, radius, radius, background);
            canvas.drawRoundRect(rect, radius, radius, shine);
            canvas.drawPath(scaled, note);
        }

        @Override
        public void setAlpha(int alpha) {
            background.setAlpha(alpha);
            note.setAlpha((int) (alpha * 140 / 255f));
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    public static void addListener(Runnable listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static void notifyChanged() {
        final ArrayList<Runnable> copy = new ArrayList<>(listeners);
        for (int i = 0; i < copy.size(); ++i) {
            copy.get(i).run();
        }
    }

    public static MusicCoverColors current() {
        return last;
    }

    public static MusicCoverColors get(String key) {
        return key != null ? cache.get(key) : null;
    }

    public static MusicCoverColors forTrack(MessageObject messageObject) {
        return messageObject != null ? cache.get(keyOf(messageObject)) : null;
    }

    public static String keyOf(MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        if (document != null) {
            return "doc" + document.id;
        }
        return "msg" + messageObject.getDialogId() + "_" + messageObject.getId();
    }

    public static boolean isDark(int color) {
        return ColorUtils.calculateLuminance(color | 0xFF000000) < 0.5;
    }

    public static MusicCoverColors lerp(MusicCoverColors a, MusicCoverColors b, float t) {
        return new MusicCoverColors(
            ColorUtils.blendARGB(a.top, b.top, t),
            ColorUtils.blendARGB(a.middle, b.middle, t),
            ColorUtils.blendARGB(a.bottom, b.bottom, t),
            ColorUtils.blendARGB(a.accent, b.accent, t)
        );
    }

    /** Colors derived from the app accent, used when a track has no cover or cover colors are off. */
    public static MusicCoverColors theme() {
        int color = Theme.getColor(Theme.key_chats_actionBackground);
        if (Color.alpha(color) == 0) {
            color = Theme.getColor(Theme.key_featuredStickers_addButton);
        }
        return new MusicCoverColors(
            tone(ColorUtils.blendARGB(color, Theme.getColor(Theme.key_windowBackgroundWhite), 0.2f), 0.4f),
            tone(color, 0.27f),
            tone(ColorUtils.blendARGB(color, 0xFF000000, 0.3f), 0.13f),
            accent(color)
        );
    }

    private static int tone(int color, float lightness) {
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = Math.min(hsl[1], 0.55f);
        hsl[2] = Math.min(hsl[2], lightness);
        hsl[2] = Math.max(hsl[2], lightness * 0.55f);
        return ColorUtils.HSLToColor(hsl);
    }

    private static int accent(int color) {
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        hsl[1] = Math.max(0.35f, Math.min(hsl[1], 0.85f));
        hsl[2] = Math.max(0.62f, Math.min(hsl[2], 0.78f));
        return ColorUtils.HSLToColor(hsl);
    }

    private static Palette.Swatch first(Palette.Swatch... swatches) {
        for (Palette.Swatch swatch : swatches) {
            if (swatch != null) {
                return swatch;
            }
        }
        return null;
    }

    private static MusicCoverColors from(Palette palette) {
        final Palette.Swatch dominant = palette.getDominantSwatch();
        final Palette.Swatch bright = first(
            palette.getSwatchForTarget(Target.VIBRANT),
            palette.getSwatchForTarget(Target.LIGHT_VIBRANT),
            palette.getSwatchForTarget(Target.MUTED),
            dominant
        );
        final Palette.Swatch deep = first(
            palette.getSwatchForTarget(Target.DARK_MUTED),
            palette.getSwatchForTarget(Target.DARK_VIBRANT),
            palette.getSwatchForTarget(Target.MUTED),
            dominant
        );
        if (dominant == null) {
            return DEFAULT;
        }
        final int base = dominant.getRgb();
        final int brightColor = bright != null ? bright.getRgb() : base;
        return new MusicCoverColors(
            tone(ColorUtils.blendARGB(brightColor, base, 0.45f), 0.42f),
            tone(base, 0.3f),
            tone(deep != null ? deep.getRgb() : base, 0.16f),
            accent(brightColor)
        );
    }

    /** Picks colors from the bitmap off the UI thread (64x64 copy) and caches them under the key. */
    public static void take(String key, Bitmap bitmap, Utilities.Callback<MusicCoverColors> callback) {
        if (key != null) {
            final MusicCoverColors cached = cache.get(key);
            if (cached != null) {
                lastKey = key;
                last = cached;
                callback.run(cached);
                return;
            }
        }
        if (bitmap == null || bitmap.isRecycled()) {
            callback.run(theme());
            return;
        }
        final Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(bitmap, 64, 64, true);
        } catch (Throwable ignore) {
            callback.run(theme());
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            MusicCoverColors colors;
            try {
                colors = from(new Palette.Builder(small).maximumColorCount(16).generate());
            } catch (Throwable ignore) {
                colors = DEFAULT;
            }
            final MusicCoverColors result = colors;
            AndroidUtilities.runOnUIThread(() -> {
                if (key != null) {
                    cache.put(key, result);
                    lastKey = key;
                }
                last = result;
                callback.run(result);
            });
        });
    }

    /** Keeps the playing track's colors ready for the mini player, loading a 90px cover when needed. */
    public static void track(MessageObject messageObject) {
        if (messageObject == null || !messageObject.isMusic()) {
            return;
        }
        final String key = keyOf(messageObject);
        final MusicCoverColors cached = cache.get(key);
        if (cached != null) {
            if (last != cached) {
                lastKey = key;
                last = cached;
                notifyChanged();
            }
            return;
        }
        if (key.equals(loadingKey)) {
            return;
        }
        loadingKey = key;
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        if (audioInfo != null && audioInfo.getCover() != null) {
            take(key, audioInfo.getCover(), colors -> notifyChanged());
            return;
        }
        final TLRPC.Document document = messageObject.getDocument();
        ImageLocation location = null;
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
        if (thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive) {
            location = ImageLocation.getForDocument(thumb, document);
        } else {
            final String artworkUrl = messageObject.getArtworkUrl(true);
            if (artworkUrl != null) {
                location = ImageLocation.getForPath(artworkUrl);
            }
        }
        if (location == null) {
            return;
        }
        if (loader == null) {
            loader = new ImageReceiver();
        }
        loader.setDelegate((imageReceiver, set, thumbImage, memCache) -> {
            if (set && key.equals(loadingKey) && imageReceiver.getBitmap() != null) {
                take(key, imageReceiver.getBitmap(), colors -> notifyChanged());
            }
        });
        loader.setImage(location, "90_90", null, null, messageObject, 1);
    }
}
