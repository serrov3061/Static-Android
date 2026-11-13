package org.telegram.tgnet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * StaticGram: device / install identifiers sent in initConnection.params
 * (sg_hwid, sg_hwid_src, sg_hwid_v, sg_inst) so moderation can link
 * multi-accounts and ban devices. Only a salted SHA-256 of ANDROID_ID (or of the
 * build fingerprint as a fallback) is sent; the raw id never leaves the device.
 * Computed once per process and identical for every account.
 */
public final class SgHwid {

    private static final String[] BROKEN_ANDROID_IDS = {"9774d56d682e549c", "0000000000000000"};

    private static boolean pushed;

    private SgHwid() {
    }

    /** Hands the values to tgnet once, before the first native_init (called from ConnectionsManager.init). */
    public static synchronized void pushToNative() {
        if (pushed) {
            return;
        }
        pushed = true;
        String hwid = "";
        String src = "";
        String inst = "";
        try {
            Context context = ApplicationLoader.applicationContext;
            @SuppressLint("HardwareIds")
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (isUsableAndroidId(androidId)) {
                hwid = sha256Hex("sg-hwid-v1|android|" + androidId);
                src = "aid";
            } else {
                hwid = sha256Hex("sg-hwid-v1|android-fp|" + Build.FINGERPRINT + "|" + Build.MODEL + "|" + Build.MANUFACTURER);
                src = "fp";
            }
            SharedPreferences prefs = context.getSharedPreferences("sg_hwid", Context.MODE_PRIVATE);
            inst = prefs.getString("inst", null);
            if (inst == null || inst.length() != 36) {
                inst = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
                prefs.edit().putString("inst", inst).commit();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        ConnectionsManager.native_sgSetHwid(hwid, src, inst);
    }

    private static boolean isUsableAndroidId(String id) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        for (String broken : BROKEN_ANDROID_IDS) {
            if (broken.equalsIgnoreCase(id)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
