package com.exteragram.messenger.icons;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * exteraGram IconPackProvider: read-only content://&lt;applicationId&gt;.icon_pack_provider/icon/&lt;packId&gt;/&lt;resource&gt;
 * URIs that hand an icon of a custom pack (rasterized to PNG in cache/notification_icons) to other processes,
 * e.g. the "notification" icon to SystemUI on Android 12+ (IconManager.getNotificationIcon).
 * Unknown icons fall back to the app's own notification icon.
 */
public final class IconPackProvider extends ContentProvider {

    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList("png", "webp", "jpg", "jpeg", "svg"));

    public static Uri getIconUri(String packId, String resourceName) {
        File file = IconPackStorage.INSTANCE.resolveIconFile(packId, resourceName);
        if (file == null || !EXTENSIONS.contains(IconPackStorage.lowerExtension(file))) {
            return null;
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(ApplicationLoader.getApplicationId() + ".icon_pack_provider")
                .appendPath("icon")
                .appendPath(packId)
                .appendPath(resourceName)
                .appendQueryParameter("v", file.lastModified() + "_" + file.length())
                .build();
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        Context app = context != null ? context.getApplicationContext() : null;
        if (app != null && ApplicationLoader.applicationContext == null) {
            ApplicationLoader.applicationContext = app;
        }
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return isIconUri(uri) ? "image/png" : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("Icon pack files are read-only");
        }
        if (!isIconUri(uri)) {
            throw new FileNotFoundException(uri.toString());
        }
        File file = null;
        try {
            File source = resolveSource(uri);
            file = source != null ? getRasterizedIcon(source) : null;
        } catch (Throwable ignore) {
        }
        if (file == null && (file = getFallbackIcon()) == null) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private boolean isIconUri(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.size() == 3 && "icon".equals(segments.get(0));
    }

    private File resolveSource(Uri uri) {
        if (!isIconUri(uri)) {
            return null;
        }
        List<String> segments = uri.getPathSegments();
        File file = IconPackStorage.INSTANCE.resolveIconFile(segments.get(1), segments.get(2));
        return file != null && EXTENSIONS.contains(IconPackStorage.lowerExtension(file)) ? file : null;
    }

    private interface Writer {
        boolean write(Context context, FileOutputStream out) throws Exception;
    }

    private File getFallbackIcon() {
        Context context = getContext();
        DisplayMetrics metrics = context != null ? context.getResources().getDisplayMetrics() : null;
        if (metrics == null) {
            return null;
        }
        return materializeCacheFile("default_" + metrics.densityDpi + ".png", (ctx, out) -> {
            Bitmap bitmap = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.notification);
            if (bitmap == null) {
                return false;
            }
            try {
                return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                bitmap.recycle();
            }
        });
    }

    private File getRasterizedIcon(File source) throws Exception {
        Context context = getContext();
        DisplayMetrics metrics = context != null ? context.getResources().getDisplayMetrics() : null;
        if (metrics == null) {
            return null;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((source.getCanonicalPath() + ":" + source.lastModified() + ":" + source.length() + ":" + metrics.densityDpi).getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : digest) {
            name.append(String.format("%02x", b & 255));
        }
        name.append(".png");
        return materializeCacheFile(name.toString(), (ctx, out) -> {
            Bitmap bitmap = IconManager.INSTANCE.createBitmapFromFile(source.getAbsolutePath(), R.drawable.notification, ctx.getResources().getDisplayMetrics().densityDpi, null);
            if (bitmap == null) {
                return false;
            }
            try {
                return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                bitmap.recycle();
            }
        });
    }

    private File materializeCacheFile(String name, Writer writer) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        File dir = new File(context.getCacheDir(), "notification_icons");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File file = new File(dir, name);
        if (file.isFile() && file.length() > 0) {
            return file;
        }
        synchronized (IconPackProvider.class) {
            if (file.isFile() && file.length() > 0) {
                return file;
            }
            File tmp = new File(dir, name + ".tmp");
            try {
                boolean written;
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    written = writer.write(context, out);
                }
                if (!written) {
                    return null;
                }
                if (!tmp.renameTo(file)) {
                    try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                return file;
            } catch (Exception e) {
                return null;
            } finally {
                if (tmp.exists()) {
                    tmp.delete();
                }
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
