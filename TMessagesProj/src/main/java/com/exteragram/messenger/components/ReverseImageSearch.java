package com.exteragram.messenger.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import androidx.annotation.NonNull;

import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * exteraGram 13.1.1 reverse image search: the photo is uploaded to Yandex CBIR (only after the
 * user picks an engine), the returned public image url is then opened in the chosen engine.
 */
public abstract class ReverseImageSearch {

    private static final String UPLOAD_URL = "https://yandex.com/images/touch/search?rpt=imageview&format=json&request=" + Uri.encode("{\"blocks\":[{\"block\":\"cbir-uploader__get-cbir-id\"}]}");

    public enum Provider {
        YANDEX,
        GOOGLE,
        BING,
        TINEYE
    }

    /**
     * Uploads {@code file} and delivers the search url (or null on failure) on the UI thread.
     *
     * @return a runnable that cancels the request
     */
    public static Runnable search(File file, Provider provider, Utilities.Callback<String> callback) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Call> callRef = new AtomicReference<>();
        Utilities.globalQueue.postRunnable(() -> {
            byte[] image;
            try {
                image = encodeImage(file);
            } catch (Throwable e) {
                FileLog.e(e);
                image = null;
            }
            if (cancelled.get()) {
                return;
            }
            if (image == null) {
                deliver(cancelled, callback, null);
                return;
            }
            Call call = ExteraHttpClient.INSTANCE.getClient().newCall(buildUploadRequest(image));
            callRef.set(call);
            if (cancelled.get()) {
                call.cancel();
                return;
            }
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    if (!call.isCanceled()) {
                        FileLog.e(e);
                    }
                    deliver(cancelled, callback, null);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    String url = null;
                    try (Response r = response) {
                        ResponseBody body = r.body();
                        if (r.isSuccessful() && body != null) {
                            JSONObject params = new JSONObject(body.string()).getJSONArray("blocks").getJSONObject(0).getJSONObject("params");
                            String cbirId = params.optString("cbirId");
                            String imageUrl = params.optString("originalImageUrl");
                            if (!TextUtils.isEmpty(cbirId) && !TextUtils.isEmpty(imageUrl)) {
                                url = buildUrl(provider, r.request().url().host(), cbirId, imageUrl);
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    deliver(cancelled, callback, url);
                }
            });
        });
        return () -> {
            cancelled.set(true);
            Call call = callRef.get();
            if (call != null) {
                call.cancel();
            }
        };
    }

    public static void open(Context context, String url) {
        seedConsentCookies();
        Browser.openUrl(context, url);
    }

    private static void deliver(AtomicBoolean cancelled, Utilities.Callback<String> callback, String url) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!cancelled.get()) {
                callback.run(url);
            }
        });
    }

    private static Request buildUploadRequest(byte[] image) {
        Request.Builder builder = new Request.Builder()
                .url(UPLOAD_URL)
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("upfile", "image.jpg", RequestBody.create(image, MediaType.get("image/jpeg")))
                        .build())
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", "gdpr=0");
        try {
            String userAgent = WebSettings.getDefaultUserAgent(ApplicationLoader.applicationContext);
            if (!TextUtils.isEmpty(userAgent)) {
                builder.header("User-Agent", userAgent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return builder.build();
    }

    private static String buildUrl(Provider provider, String host, String cbirId, String imageUrl) {
        String encodedUrl = Uri.encode(imageUrl);
        switch (provider) {
            case YANDEX:
                return "https://" + host + "/images/search?rpt=imageview&cbir_id=" + Uri.encode(cbirId) + "&url=" + encodedUrl;
            case GOOGLE:
                return "https://lens.google.com/uploadbyurl?url=" + encodedUrl;
            case BING:
                return "https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&sbisrc=UrlPaste&q=imgurl:" + encodedUrl;
            case TINEYE:
            default:
                return "https://tineye.com/search?url=" + encodedUrl;
        }
    }

    private static void seedConsentCookies() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setCookie("https://www.google.com", "SOCS=CAISHAgBEhJnd3NfMjAyNjA2MjYtMF9SQzEaAmVuIAEaBgiAjozSBg; Domain=.google.com; Path=/; Secure; SameSite=Lax");
            cookieManager.setCookie("https://www.bing.com", "BCP=AD=1&AL=1&SM=1; Domain=.bing.com; Path=/; Secure");
            cookieManager.setCookie("https://tineye.com", "cookie_consent=accepted; Path=/");
            cookieManager.setCookie("https://yandex.com", "gdpr=0; Domain=.yandex.com; Path=/; Secure");
            cookieManager.setCookie("https://yandex.ru", "gdpr=0; Domain=.yandex.ru; Path=/; Secure");
            cookieManager.flush();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static byte[] encodeImage(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2560) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int max = Math.max(width, height);
        if (max > 1280) {
            float scale = 1280f / max;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        bitmap.recycle();
        return stream.toByteArray();
    }
}
