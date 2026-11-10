package com.exteragram.messenger.utils;

import org.telegram.messenger.FileLog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// exteraGram: lightweight JPEG header parser used to guess the platform that produced a photo
public class JpegFingerprint {
    public int components;
    public boolean exif;
    public boolean frameBeforeQuantization;
    public int height;
    public int horizontalSampling;
    public int huffmanTables;
    public String iccDescription;
    public int iccLength;
    public int iccVersion;
    public int iccYear;
    public int jfifDensityX;
    public int jfifDensityY;
    public boolean jfifThumbnail;
    public boolean progressive;
    public boolean quantizationTable;
    public boolean restartInterval;
    public int verticalSampling;
    public int width;
    public boolean xmp;
    private static final int[] ZIGZAG = {0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
    private static final int[] STANDARD_LUMA = {16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};
    public int jfifVersion = -1;
    public int jfifUnits = -1;
    public int quality = -1;
    public String markerOrder = "";

    public static JpegFingerprint parse(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 16384)) {
            if (in.read() != 255 || in.read() != 216) {
                return null;
            }
            JpegFingerprint fingerprint = new JpegFingerprint();
            StringBuilder order = new StringBuilder();
            int pos = 2;
            while (pos < 262144 && in.read() == 255) {
                int marker = in.read();
                while (marker == 255) {
                    marker = in.read();
                    pos++;
                }
                pos += 2;
                if (marker < 0 || marker == 217 || marker == 218) {
                    break;
                }
                if (marker == 1 || (marker >= 208 && marker <= 215)) {
                    continue;
                }
                int hi = in.read();
                int lo = in.read();
                if (hi < 0 || lo < 0) {
                    break;
                }
                int length = (hi << 8) | lo;
                int segmentLength = length - 2;
                if (segmentLength < 0) {
                    break;
                }
                pos += length;
                if (order.length() > 0) {
                    order.append('>');
                }
                order.append(String.format(Locale.US, "%02X", marker));
                if (marker != 224 && marker != 225 && marker != 226 && marker != 219 && marker != 196 && marker != 221 && (marker < 192 || marker > 194)) {
                    long skipped = 0;
                    while (skipped < segmentLength) {
                        long s = in.skip(segmentLength - skipped);
                        if (s <= 0) {
                            break;
                        }
                        skipped += s;
                    }
                    if (skipped < segmentLength) {
                        break;
                    }
                }
                byte[] data = new byte[segmentLength];
                int read = 0;
                while (read < segmentLength) {
                    int n = in.read(data, read, segmentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                if (read < segmentLength) {
                    break;
                }
                fingerprint.readSegment(marker, data);
            }
            fingerprint.markerOrder = order.toString();
            return fingerprint;
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        }
    }

    private void readSegment(int marker, byte[] data) {
        if (marker == 224) {
            if (data.length < 14 || !startsWith(data, "JFIF")) {
                return;
            }
            jfifVersion = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
            jfifUnits = data[7] & 0xFF;
            jfifDensityX = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
            jfifDensityY = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
            jfifThumbnail = !(data[12] == 0 && data[13] == 0);
            return;
        }
        if (marker == 225) {
            exif = exif || startsWith(data, "Exif");
            xmp = xmp || startsWith(data, "http://ns.adobe.com/xap/1.0/");
            return;
        }
        if (marker == 226) {
            if (iccLength == 0 && data.length >= 50 && startsWith(data, "ICC_PROFILE")) {
                iccLength = ((data[14] & 0xFF) << 24) | ((data[15] & 0xFF) << 16) | ((data[16] & 0xFF) << 8) | (data[17] & 0xFF);
                iccVersion = ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
                iccYear = ((data[38] & 0xFF) << 8) | (data[39] & 0xFF);
                iccDescription = readIccDescription(data, 14);
            }
            return;
        }
        if (marker == 219) {
            int i = 0;
            while (i < data.length) {
                int precision = (data[i] & 0xF0) >> 4;
                int tableId = data[i] & 0x0F;
                i++;
                int next = i + (precision == 0 ? 64 : 128);
                if (next > data.length) {
                    return;
                }
                if (tableId == 0 && precision == 0 && !quantizationTable) {
                    quantizationTable = true;
                    quality = standardQuality(data, i);
                }
                i = next;
            }
            return;
        }
        if (marker == 196) {
            int i = 0;
            while (i + 17 <= data.length) {
                int count = 0;
                for (int k = 0; k < 16; k++) {
                    count += data[i + 1 + k] & 0xFF;
                }
                huffmanTables++;
                i += count + 17;
            }
            return;
        }
        if (marker == 221) {
            restartInterval = data.length >= 2 && (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) > 0;
            return;
        }
        if (marker < 192 || marker > 194 || data.length < 9) {
            return;
        }
        progressive = marker == 194;
        frameBeforeQuantization = !quantizationTable;
        height = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        width = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        components = data[5] & 0xFF;
        byte sampling = data[7];
        horizontalSampling = (sampling & 0xF0) >> 4;
        verticalSampling = sampling & 0x0F;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset + 3] & 0xFF) | ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8);
    }

    private static String readIccDescription(byte[] data, int base) {
        try {
            int tagCount = readInt(data, base + 128);
            for (int t = 0; t < Math.min(tagCount, 64); t++) {
                int entry = base + 132 + (t * 12);
                if (entry + 12 > data.length) {
                    break;
                }
                if (data[entry] == 100 && data[entry + 1] == 101 && data[entry + 2] == 115 && data[entry + 3] == 99) {
                    int tagOffset = readInt(data, entry + 4) + base;
                    int tagSize = readInt(data, entry + 8);
                    if (tagOffset < base || tagSize < 12 || tagOffset + tagSize > data.length) {
                        break;
                    }
                    byte type = data[tagOffset];
                    if (type == 100) {
                        int len = Math.min(readInt(data, tagOffset + 8) - 1, tagSize - 12);
                        if (len > 0) {
                            return new String(data, tagOffset + 12, len, StandardCharsets.US_ASCII);
                        }
                        return null;
                    }
                    if (type != 109) {
                        break;
                    }
                    int len = readInt(data, tagOffset + 20);
                    int start = readInt(data, tagOffset + 24) + tagOffset;
                    if (len <= 0 || start < tagOffset || start + len > tagOffset + tagSize) {
                        break;
                    }
                    return new String(data, start, len, StandardCharsets.UTF_16BE);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static int standardQuality(byte[] data, int offset) {
        int q = 100;
        while (q >= 1) {
            int scale = q < 50 ? 5000 / q : 200 - q * 2;
            boolean matches = true;
            for (int i = 0; i < 64; i++) {
                int expected = (STANDARD_LUMA[ZIGZAG[i]] * scale + 50) / 100;
                if (expected < 1) {
                    expected = 1;
                } else if (expected > 255) {
                    expected = 255;
                }
                if (expected != (255 & data[offset + i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return q;
            }
            q--;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, String prefix) {
        if (data.length <= prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (jfifUnits >= 0) {
            sb.append(String.format(Locale.US, "jfif=%d.%02d/u%d/%dx%d", jfifVersion >> 8, jfifVersion & 255, jfifUnits, jfifDensityX, jfifDensityY));
        } else {
            sb.append("jfif=none");
        }
        if (jfifThumbnail) {
            sb.append("/thumb");
        }
        if (iccLength > 0) {
            String description = iccDescription != null ? iccDescription : "?";
            sb.append(String.format(Locale.US, " icc=%d/%04x/%d/%s", iccLength, iccVersion, iccYear, description));
        } else {
            sb.append(" icc=none");
        }
        if (quality > 0) {
            sb.append(String.format(Locale.US, " dqt=q%d", quality));
        } else {
            sb.append(quantizationTable ? " dqt=custom" : " dqt=none");
        }
        sb.append(String.format(Locale.US, " sof=%s/%dc/%dx%d/%dx%d", progressive ? "prog" : "base", components, horizontalSampling, verticalSampling, width, height));
        sb.append(String.format(Locale.US, " dht=%d", huffmanTables));
        if (exif) {
            sb.append(" exif");
        }
        if (xmp) {
            sb.append(" xmp");
        }
        if (restartInterval) {
            sb.append(" dri");
        }
        sb.append(" order=");
        sb.append(markerOrder);
        return sb.toString();
    }
}
