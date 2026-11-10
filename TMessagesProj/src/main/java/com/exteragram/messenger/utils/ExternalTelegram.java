package com.exteragram.messenger.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.telegram.messenger.FileLog;

import java.util.List;
import java.util.Locale;

/**
 * StaticGram: opens t.me links in the real Telegram app (or a browser) instead of resolving them on
 * the StaticGram server, where exteraGram's channels and our Telegram channels do not exist.
 */
public final class ExternalTelegram {

    private static final String[] TELEGRAM_PACKAGES = {
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
        "com.exteragram.messenger", "org.thunderdog.challegram", "org.telegram.plus", "tw.nekomimi.nekogram",
    };

    private ExternalTelegram() {
    }

    /** Usernames that live in the real Telegram (exteraGram's channels), not on our server. */
    public static boolean isExternalUsername(String username) {
        if (username == null) return false;
        String u = username.startsWith("@") ? username.substring(1) : username;
        return u.toLowerCase(Locale.ROOT).startsWith("extera");
    }

    public static boolean isTelegramUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("https://t.me/") || u.startsWith("http://t.me/") || u.startsWith("t.me/")
            || u.startsWith("https://telegram.me/") || u.startsWith("tg://");
    }

    public static void openUsername(Context context, String username) {
        String u = username.startsWith("@") ? username.substring(1) : username;
        open(context, "https://t.me/" + u);
    }

    /** Opens a t.me link in an installed Telegram client, else in a browser. */
    public static void open(Context context, String url) {
        if (context == null || url == null) return;
        if (url.startsWith("t.me/")) url = "https://" + url;
        Uri uri = Uri.parse(url);
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        try {
            for (String pkg : TELEGRAM_PACKAGES) {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!pkg.equals(self) && intent.resolveActivity(pm) != null) {
                    context.startActivity(intent);
                    return;
                }
            }
            // no Telegram app: the default browser (never ourselves, we also handle t.me)
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
            List<ResolveInfo> browsers = pm.queryIntentActivities(probe, 0);
            for (ResolveInfo info : browsers) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(self) && !pkg.startsWith("com.staticgram")) {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
            context.startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Browser.openUrl replacement for plugin UI: t.me links go to the real Telegram. */
    public static void openUrl(Context context, String url) {
        if (isTelegramUrl(url)) {
            open(context, url);
        } else {
            org.telegram.messenger.browser.Browser.openUrl(context, url);
        }
    }
}
