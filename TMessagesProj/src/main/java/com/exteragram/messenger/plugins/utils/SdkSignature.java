package com.exteragram.messenger.plugins.utils;

import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import org.telegram.messenger.FileLog;

public final class SdkSignature {
    public static final SdkSignature INSTANCE = new SdkSignature();
    private static final String PAYLOAD_PREFIX = "extera-pysdk";
    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAER7oj20N/vwmRC/Zig0lX/xOQvh6kSkPOhuoZlIm7IYHyY0qo3YqMGVfGqjjqX4ZcsGiDy91ELUEElv3bCSfHTg==";

    private SdkSignature() {
    }

    public static final boolean verify(File archive, String version, String abi, String signature) {
        if (version == null || version.length() == 0 || abi == null || abi.length() == 0 || signature == null || signature.length() == 0) {
            FileLog.e("Python SDK update is not signed");
            return false;
        }
        if (!archive.exists()) {
            FileLog.e("Python SDK archive to verify does not exist");
            return false;
        }
        try {
            String str = "extera-pysdk|" + version + "|" + abi + "|" + INSTANCE.digest(archive);
            PublicKey publicKeyGeneratePublic = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, 0)));
            Signature signature2 = Signature.getInstance("SHA256withECDSA");
            signature2.initVerify(publicKeyGeneratePublic);
            signature2.update(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < signature.length(); i++) {
                char cCharAt = signature.charAt(i);
                if (!Character.isWhitespace(cCharAt)) {
                    sb.append(cCharAt);
                }
            }
            boolean zVerify = signature2.verify(Base64.decode(sb.toString(), 0));
            if (!zVerify) {
                FileLog.e("Python SDK signature does not match (" + version + ", " + abi + ")");
            }
            return zVerify;
        } catch (Throwable th) {
            FileLog.e("Failed to verify Python SDK signature", th);
            return false;
        }
    }

    private String digest(File file) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[1048576];
            int read;
            while ((read = fileInputStream.read(buffer)) > 0) {
                messageDigest.update(buffer, 0, read);
            }
        }
        byte[] bArrDigest = messageDigest.digest();
        StringBuilder sb = new StringBuilder(bArrDigest.length * 2);
        for (byte b2 : bArrDigest) {
            sb.append(String.format("%02x", b2));
        }
        return sb.toString();
    }
}
