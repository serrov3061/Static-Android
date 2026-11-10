package com.exteragram.messenger.badges;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * StaticGram profile banner upload: a picture from the gallery is re-encoded to a JPEG of at most
 * 1920 px width (and 5 MB), a video is checked against the limits (MP4, 15 s, 10 MB) and sent as
 * is; the file is uploaded as a document to the badge bot's peer (messages.uploadMedia, nothing is
 * sent to the chat) and set with {@link BadgesController#updateBanner}.
 */
public final class ProfileBannerUpload {

    /** Finished: error is null on success, otherwise a short user-facing reason. */
    public interface Done {
        void run(String error);
    }

    private static final int MAX_PHOTO_WIDTH = 1920;

    private ProfileBannerUpload() {
    }

    public static void start(int account, Uri uri, Done done) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                prepareAndUpload(account, uri, done);
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> done.run(t.getMessage()));
            }
        });
    }

    /**
     * Uploads a local file picked in the in-app gallery (already cropped by the photo editor, or a
     * video already converted to a silent MP4).
     */
    public static void startFile(int account, String path, boolean video, Done done) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File src = new File(path);
                if (!src.exists() || src.length() == 0) {
                    fail(done, video ? "video" : "photo");
                    return;
                }
                if (video && src.length() > ProfileBanners.MAX_VIDEO_BYTES) {
                    fail(done, "video");
                    return;
                }
                prepareFile(account, src, video, video ? videoMime(src) : null, false, done);
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> done.run(t.getMessage()));
            }
        });
    }

    private static String videoMime(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            return mime != null ? mime : (f.getName().toLowerCase().endsWith(".mp4") ? "video/mp4" : null);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void prepareAndUpload(int account, Uri uri, Done done) throws Exception {
        ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
        String mime = cr.getType(uri);
        boolean video = mime != null && mime.startsWith("video/");
        File dir = new File(AndroidUtilities.getCacheDir(), "sg_banners");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File src = new File(dir, "upload_src" + (video ? ".mp4" : ".img"));
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(src)) {
            if (in == null) {
                throw new IllegalStateException("cannot open the file");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > (video ? ProfileBanners.MAX_VIDEO_BYTES : 64L << 20)) {
                    fail(done, video ? "video" : "photo");
                    return;
                }
                out.write(buf, 0, n);
            }
        }
        prepareFile(account, src, video, mime, true, done);
    }

    private static void prepareFile(int account, File src, boolean video, String mime, boolean deleteSrc, Done done) throws Exception {
        File dir = new File(AndroidUtilities.getCacheDir(), "sg_banners");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        File upload;
        if (video) {
            if (!"video/mp4".equals(mime)) {
                fail(done, "video");
                return;
            }
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            int w, h;
            double seconds;
            try {
                r.setDataSource(src.getPath());
                seconds = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0;
                w = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                h = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                int rotation = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                if (rotation == 90 || rotation == 270) {
                    int t = w;
                    w = h;
                    h = t;
                }
            } finally {
                r.release();
            }
            if (seconds <= 0 || seconds > ProfileBanners.MAX_VIDEO_SECONDS + 0.4) {
                fail(done, "video");
                return;
            }
            upload = src;
            media.mime_type = "video/mp4";
            media.nosound_video = true;
            TLRPC.TL_documentAttributeVideo attr = new TLRPC.TL_documentAttributeVideo();
            attr.duration = seconds;
            attr.w = w;
            attr.h = h;
            attr.supports_streaming = true;
            media.attributes.add(attr);
            TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
            name.file_name = "banner.mp4";
            media.attributes.add(name);
        } else {
            // ImageLoader.loadBitmap applies the EXIF orientation (gallery originals are often rotated)
            Bitmap bmp = ImageLoader.loadBitmap(src.getPath(), null, MAX_PHOTO_WIDTH, MAX_PHOTO_WIDTH, true);
            if (bmp == null) {
                fail(done, "photo");
                return;
            }
            if (bmp.getWidth() > MAX_PHOTO_WIDTH) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, MAX_PHOTO_WIDTH, Math.round(bmp.getHeight() * (MAX_PHOTO_WIDTH / (float) bmp.getWidth())), true);
                bmp.recycle();
                bmp = scaled;
            }
            upload = new File(dir, "upload_" + System.currentTimeMillis() + ".jpg");
            for (int quality = 88; ; quality -= 12) {
                try (OutputStream out = new FileOutputStream(upload)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
                }
                if (upload.length() <= ProfileBanners.MAX_PHOTO_BYTES || quality <= 52) {
                    break;
                }
            }
            bmp.recycle();
            if (deleteSrc) {
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
            if (upload.length() > ProfileBanners.MAX_PHOTO_BYTES) {
                fail(done, "photo");
                return;
            }
            media.mime_type = "image/jpeg";
            media.force_file = true;
            TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
            name.file_name = "banner.jpg";
            media.attributes.add(name);
        }
        final String path = upload.getPath();
        final String kind = video ? ProfileBanners.VIDEO : ProfileBanners.PHOTO;
        AndroidUtilities.runOnUIThread(() -> withBot(account, bot -> {
            if (bot == null) {
                done.run("bot");
                return;
            }
            FileLoader.getInstance(account).uploadFile(path, inputFile -> {
                if (inputFile == null) {
                    done.run("upload");
                    return;
                }
                media.file = inputFile;
                TLRPC.TL_messages_uploadMedia req = new TLRPC.TL_messages_uploadMedia();
                req.peer = MessagesController.getInputPeer(bot);
                req.media = media;
                ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                    TLRPC.Document doc = res instanceof TLRPC.TL_messageMediaDocument ? ((TLRPC.TL_messageMediaDocument) res).document : null;
                    if (doc == null) {
                        done.run(err != null ? err.text : "upload");
                        return;
                    }
                    BadgesController.INSTANCE.updateBanner(doc.id, doc.access_hash, kind, result -> AndroidUtilities.runOnUIThread(() ->
                            done.run("ok".equals(result) ? null : (result == null ? "bot" : result))));
                }));
            });
        }));
    }

    private static void withBot(int account, Utilities.Callback<TLRPC.User> cb) {
        String username = BadgesConfig.getBotUsername();
        if (TextUtils.isEmpty(username)) {
            cb.run(null);
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLObject cached = mc.getUserOrChat(username);
        if (cached instanceof TLRPC.User) {
            cb.run((TLRPC.User) cached);
            return;
        }
        mc.getUserNameResolver().resolve(username, id -> cb.run(id != null && id > 0 ? mc.getUser(id) : null));
    }

    private static void fail(Done done, String what) {
        AndroidUtilities.runOnUIThread(() -> done.run(what));
    }

    private static double parse(String v) {
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
