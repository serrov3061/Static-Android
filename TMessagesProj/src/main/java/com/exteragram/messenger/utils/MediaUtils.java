package com.exteragram.messenger.utils;

import androidx.exifinterface.media.ExifInterface;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// exteraGram: EXIF helpers (strip geolocation from a copy of a photo, guess the sending platform)
public abstract class MediaUtils {
    private static final String[] GEO_TAGS = {"GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod"};
    private static final String[] GEO_TAGS_API_24 = {"GPSAreaInformation", "GPSDOP", "GPSMeasureMode", "GPSSpeedRef", "GPSSpeed", "GPSStatus", "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef", "GPSDestBearing", "GPSDestBearingRef", "GPSDestDistance", "GPSDestDistanceRef"};

    public static boolean removeGeolocation(String sourcePath, String destPath) throws Throwable {
        File source = new File(sourcePath);
        if (!source.exists()) {
            return false;
        }
        File dest = new File(destPath);
        if (!AndroidUtilities.copyFile(source, dest)) {
            dest.delete();
            throw new IOException("failed to copy " + sourcePath + " to " + destPath);
        }
        List<String> tags = new ArrayList<>(Arrays.asList(GEO_TAGS));
        tags.addAll(Arrays.asList(GEO_TAGS_API_24));
        ExifInterface exif = new ExifInterface(dest.getAbsolutePath());
        boolean changed = false;
        for (String tag : tags) {
            if (exif.getAttribute(tag) != null) {
                try {
                    exif.setAttribute(tag, null);
                    changed = true;
                } catch (Exception ignore) {
                }
            }
        }
        if (changed) {
            try {
                exif.saveAttributes();
                return true;
            } catch (IOException ignore) {
                dest.delete();
            }
        }
        return false;
    }

    public static String getPhotoPlatform(JpegFingerprint fingerprint) {
        if (fingerprint == null || fingerprint.jfifUnits < 0) {
            return null;
        }
        boolean skiaIcc = (fingerprint.iccVersion == 1072 && fingerprint.iccYear == 2016) || (fingerprint.iccDescription != null && fingerprint.iccDescription.startsWith("Google/Skia/"));
        int units = fingerprint.jfifUnits;
        if (units == 0) {
            if (fingerprint.jfifDensityX != 1) {
                return null;
            }
            if (skiaIcc) {
                return "Android";
            }
            if (fingerprint.iccLength == 0) {
                if (fingerprint.quality == 72) {
                    return "iOS";
                }
                if (fingerprint.quality == 75) {
                    return "macOS";
                }
            }
            return null;
        }
        if (units != 1 || fingerprint.jfifDensityX != 72) {
            return fingerprint.iccVersion == 576 ? "macOS" : "Desktop";
        }
        if (skiaIcc) {
            return "Android";
        }
        if (fingerprint.iccVersion == 576) {
            return "macOS";
        }
        if (fingerprint.iccVersion == 1088 && fingerprint.iccLength == 480) {
            return "Desktop";
        }
        return null;
    }
}
