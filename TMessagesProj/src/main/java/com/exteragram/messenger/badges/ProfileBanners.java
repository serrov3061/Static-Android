package com.exteragram.messenger.badges;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * StaticGram profile banners: a picture or a short silent video a badge holder shows behind their
 * profile header. The public profile list carries {@code banner_id} / {@code banner_type}; the file
 * is {@code <sg_badges_api_url>banners/<id>} (the URL ends in api/v1/). Files are cached by document id (an id never
 * changes its bytes) under the cache dir; a removed banner simply stops being listed.
 */
public final class ProfileBanners {

    public static final String PHOTO = "photo";
    public static final String VIDEO = "video";
    /** Upload limits, the same as the server's (domain.MaxProfileBanner*). */
    public static final long MAX_PHOTO_BYTES = 5L << 20;
    public static final long MAX_VIDEO_BYTES = 10L << 20;
    public static final int MAX_VIDEO_SECONDS = 15;
    private static final long MAX_DOWNLOAD = 11L << 20;

    private static final Set<String> inFlight = new HashSet<>();
    private static final HashMap<String, Long> failedAt = new HashMap<>();
    private static final android.util.LruCache<String, Bitmap> photos = new android.util.LruCache<String, Bitmap>(24 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ProfileBanners() {
    }

    /** The banner {documentId, kind} of a user, or null. */
    public static String[] of(long userId) {
        if (userId <= 0) {
            return null;
        }
        String[] b = BadgesController.INSTANCE.getBanner(userId);
        if (b == null || TextUtils.isEmpty(b[0]) || !(PHOTO.equals(b[1]) || VIDEO.equals(b[1]))) {
            return null;
        }
        return b;
    }

    public static File file(String id, boolean video) {
        File dir = new File(AndroidUtilities.getCacheDir(), "sg_banners");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, id + (video ? ".mp4" : ".jpg"));
    }

    /** A decoded photo banner from memory, or null. */
    public static Bitmap cachedPhoto(String id) {
        return photos.get(id);
    }

    /**
     * Makes sure the banner file is on disk (and, for a photo, decoded to at most {@code maxWidth}
     * px wide); {@code onReady} runs on the UI thread once it is. Failed downloads are retried after
     * a minute at the earliest.
     */
    public static void load(String id, boolean video, int maxWidth, Runnable onReady) {
        if (TextUtils.isEmpty(id)) {
            return;
        }
        if (!video && photos.get(id) != null) {
            AndroidUtilities.runOnUIThread(onReady);
            return;
        }
        synchronized (inFlight) {
            Long failed = failedAt.get(id);
            if (failed != null && System.currentTimeMillis() - failed < 60_000) {
                return;
            }
            if (!inFlight.add(id)) {
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                File f = file(id, video);
                if (!f.exists() || f.length() == 0) {
                    download(id, f);
                }
                if (!video) {
                    Bitmap bmp = decode(f, Math.max(maxWidth, 360));
                    if (bmp != null) {
                        photos.put(id, bmp);
                        ok = true;
                    }
                } else {
                    ok = f.exists() && f.length() > 0;
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            synchronized (inFlight) {
                inFlight.remove(id);
                if (!ok) {
                    failedAt.put(id, System.currentTimeMillis());
                }
            }
            if (ok) {
                AndroidUtilities.runOnUIThread(onReady);
            }
        });
    }

    private static void download(String id, File dest) throws Exception {
        String base = BadgesConfig.getApiUrl();
        if (TextUtils.isEmpty(base)) {
            throw new IllegalStateException("badges api url is unknown");
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        // sg_badges_api_url is the API root and already ends in "api/v1/" (ApiController syncs
        // <base>profiles); 12.10.6 appended another "api/v1/" here and every download was a 404.
        HttpURLConnection c = (HttpURLConnection) new URL(base + "banners/" + id).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("banner " + id + ": HTTP " + c.getResponseCode());
            }
            File tmp = new File(dest.getPath() + ".part");
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD) {
                        throw new IllegalStateException("banner " + id + " is too large");
                    }
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(dest)) {
                throw new IllegalStateException("banner " + id + ": cannot store");
            }
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap decode(File f, int maxWidth) {
        if (!f.exists()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
