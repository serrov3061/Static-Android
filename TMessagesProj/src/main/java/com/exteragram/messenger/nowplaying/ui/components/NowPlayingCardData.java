package com.exteragram.messenger.nowplaying.ui.components;

import android.graphics.Bitmap;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import com.exteragram.messenger.api.dto.NowPlayingDTO;
import com.exteragram.messenger.utils.ui.UIUtil;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicBoolean;

/** Loaded data of the "Now playing" card: track + cover + colors extracted from the cover. */
public final class NowPlayingCardData {

    public interface Callback {
        void onDataLoaded(NowPlayingCardData data);
    }

    private final NowPlayingDTO nowPlayingDTO;
    private final Integer backgroundColor;
    private final Integer accentColor;
    private final Bitmap coverBitmap;
    private final ImageLocation imageLocation;
    private long userEmoji;

    public NowPlayingCardData(NowPlayingDTO nowPlayingDTO, Integer backgroundColor, Integer accentColor, Bitmap coverBitmap, ImageLocation imageLocation, long userEmoji) {
        this.nowPlayingDTO = nowPlayingDTO;
        this.backgroundColor = backgroundColor;
        this.accentColor = accentColor;
        this.coverBitmap = coverBitmap;
        this.imageLocation = imageLocation;
        this.userEmoji = userEmoji;
    }

    public NowPlayingDTO getNowPlayingDTO() {
        return nowPlayingDTO;
    }

    public Integer getBackgroundColor() {
        return backgroundColor;
    }

    public Integer getAccentColor() {
        return accentColor;
    }

    public Bitmap getCoverBitmap() {
        return coverBitmap;
    }

    public ImageLocation getImageLocation() {
        return imageLocation;
    }

    public long getUserEmoji() {
        return userEmoji;
    }

    public void setUserEmoji(long userEmoji) {
        this.userEmoji = userEmoji;
    }

    /** Loads the cover (document thumb of saved music, else coverUrl) and extracts colors; callback on the UI thread. */
    public static void create(NowPlayingDTO dto, TLRPC.Document savedMusic, Callback callback) {
        ImageLocation location = null;
        if (dto.isTelegram() && savedMusic != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(savedMusic.thumbs, 1000);
            if (size != null) {
                location = ImageLocation.getForDocument(size, savedMusic);
            }
        }
        if (location == null && !TextUtils.isEmpty(dto.getCoverUrl())) {
            location = ImageLocation.getForPath(dto.getCoverUrl());
        }
        final ImageLocation imageLocation = location;
        if (imageLocation == null) {
            AndroidUtilities.runOnUIThread(() -> callback.onDataLoaded(new NowPlayingCardData(dto, null, null, null, null, -1)));
            return;
        }
        final ImageReceiver imageReceiver = new ImageReceiver(null);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable[] timeout = new Runnable[1];
        timeout[0] = () -> finish(finished, timeout[0], imageReceiver, callback, new NowPlayingCardData(dto, null, null, null, imageLocation, -1));
        AndroidUtilities.runOnUIThread(() -> {
            imageReceiver.onAttachedToWindow();
            imageReceiver.setDelegate((receiver, set, thumb, memCache) -> {
                if (!set || thumb) {
                    return;
                }
                final Bitmap bitmap = receiver.getBitmap();
                Utilities.themeQueue.postRunnable(() -> {
                    Integer[] colors = extractColors(bitmap);
                    AndroidUtilities.runOnUIThread(() -> finish(finished, timeout[0], imageReceiver, callback, new NowPlayingCardData(dto, colors[0], colors[1], bitmap, imageLocation, -1)));
                });
            });
            AndroidUtilities.runOnUIThread(timeout[0], 15000);
            imageReceiver.setImage(imageLocation, null, null, null, null, 0);
        });
    }

    private static void finish(AtomicBoolean finished, Runnable timeout, ImageReceiver imageReceiver, Callback callback, NowPlayingCardData data) {
        if (finished.compareAndSet(false, true)) {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            imageReceiver.setDelegate(null);
            imageReceiver.onDetachedFromWindow();
            callback.onDataLoaded(data);
        }
    }

    /** {background, accent}. */
    private static Integer[] extractColors(Bitmap bitmap) {
        if (bitmap == null) {
            return new Integer[]{null, null};
        }
        int color;
        try {
            Palette palette = Palette.from(bitmap).generate();
            Palette.Swatch swatch = palette.getDarkVibrantSwatch();
            if (swatch == null) swatch = palette.getMutedSwatch();
            if (swatch == null) swatch = palette.getDarkMutedSwatch();
            if (swatch == null) swatch = palette.getDominantSwatch();
            color = swatch != null ? swatch.getRgb() : AndroidUtilities.getDominantColor(bitmap);
        } catch (Exception e) {
            color = AndroidUtilities.getDominantColor(bitmap);
        }
        double contrast = ColorUtils.calculateContrast(0xFFFFFFFF, color | 0xFF000000);
        if (contrast > 15.0d) {
            color = UIUtil.INSTANCE.adjustHsl(color, 2.0f, -1.0f);
        } else if (contrast < 10.0d) {
            color = UIUtil.INSTANCE.adjustHsl(color, 0.5f, -1.0f);
        }
        if (ColorUtils.calculateContrast(0xFFFFFFFF, color | 0xFF000000) < 3.0d) {
            color = ColorUtils.blendARGB(color, 0xFF000000, 0.3f);
        }
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        float l = hsl[2];
        float factor;
        if (l <= 0.25f) {
            factor = 2.0f;
        } else if (l <= 0.5f) {
            factor = 1.5f;
        } else if (l <= 0.75f) {
            factor = 1.0f;
        } else {
            factor = 0.5f;
        }
        return new Integer[]{color, UIUtil.INSTANCE.adjustHsl(color, factor, -1.0f)};
    }
}
