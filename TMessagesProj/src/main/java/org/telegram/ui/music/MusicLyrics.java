package org.telegram.ui.music;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Track lyrics: parsed from the file tags (LRC with [mm:ss.xx] stamps or plain text) or looked up on LRCLIB.
 * Results are cached for the session; tracks LRCLIB has nothing for are remembered as missing.
 */
public final class MusicLyrics {

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("^\\[[a-zA-Z]+:.*]$");
    private static final HashMap<String, MusicLyrics> cache = new HashMap<>();
    private static final HashMap<String, Boolean> missing = new HashMap<>();

    public static final class Line {
        public final long time;
        public final String text;

        public Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    public final ArrayList<Line> lines;
    public final boolean synced;
    public final boolean fromFile;

    private MusicLyrics(ArrayList<Line> lines, boolean synced, boolean fromFile) {
        this.lines = lines;
        this.synced = synced;
        this.fromFile = fromFile;
    }

    /** Index of the line playing at the given time (ms), -1 before the first one or for plain text. */
    public int lineAt(long timeMs) {
        if (!synced) {
            return -1;
        }
        int index = -1;
        for (int i = 0; i < lines.size() && lines.get(i).time <= timeMs; ++i) {
            index = i;
        }
        return index;
    }

    public static String key(String title, String author, long duration) {
        return (title == null ? "" : title.toLowerCase(Locale.ROOT)) + "\u0001" + (author == null ? "" : author.toLowerCase(Locale.ROOT)) + "\u0001" + duration;
    }

    public static MusicLyrics cached(String key) {
        return cache.get(key);
    }

    public static boolean isKnownMissing(String key) {
        return missing.containsKey(key);
    }

    public static MusicLyrics parse(String text, boolean fromFile) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            return null;
        }
        long offset = 0;
        final Matcher offsetMatcher = OFFSET.matcher(text);
        if (offsetMatcher.find()) {
            try {
                offset = Long.parseLong(offsetMatcher.group(1));
            } catch (Exception ignore) {
            }
        }
        final String[] rows = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        final ArrayList<Line> timed = new ArrayList<>();
        final ArrayList<Line> plain = new ArrayList<>();
        final ArrayList<Long> stamps = new ArrayList<>();
        for (String row : rows) {
            final String line = row.trim();
            final Matcher matcher = TIME.matcher(line);
            stamps.clear();
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                final long minutes = Long.parseLong(matcher.group(1));
                final long seconds = Long.parseLong(matcher.group(2));
                final String fraction = matcher.group(3);
                long ms = 0;
                if (fraction != null) {
                    ms = Long.parseLong(fraction);
                    if (fraction.length() == 1) {
                        ms *= 100;
                    } else if (fraction.length() == 2) {
                        ms *= 10;
                    }
                }
                stamps.add(seconds * 1000 + minutes * 60000 + ms - offset);
                end = matcher.end();
            }
            final String content = line.substring(end).trim();
            if (stamps.isEmpty()) {
                if (!TAG.matcher(line).matches()) {
                    plain.add(new Line(-1, line));
                }
            } else {
                for (int i = 0; i < stamps.size(); ++i) {
                    timed.add(new Line(Math.max(0, stamps.get(i)), content));
                }
            }
        }
        if (!timed.isEmpty()) {
            Collections.sort(timed, (a, b) -> Long.compare(a.time, b.time));
            return new MusicLyrics(timed, true, fromFile);
        }
        while (!plain.isEmpty() && plain.get(0).text.isEmpty()) {
            plain.remove(0);
        }
        while (!plain.isEmpty() && plain.get(plain.size() - 1).text.isEmpty()) {
            plain.remove(plain.size() - 1);
        }
        if (plain.isEmpty()) {
            return null;
        }
        return new MusicLyrics(plain, false, fromFile);
    }

    /** Looks the track up on LRCLIB off the UI thread; the callback runs on the UI thread with null when not found. */
    public static void fetch(String title, String author, String album, long duration, Utilities.Callback<MusicLyrics> callback) {
        final String key = key(title, author, duration);
        if (cache.containsKey(key)) {
            callback.run(cache.get(key));
            return;
        }
        if (missing.containsKey(key) || TextUtils.isEmpty(title)) {
            callback.run(null);
            return;
        }
        Utilities.externalNetworkQueue.postRunnable(() -> {
            MusicLyrics result = null;
            boolean failed;
            try {
                if (!TextUtils.isEmpty(author) && !TextUtils.isEmpty(album) && duration > 0) {
                    final String json = getOrNull("https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(author) + "&album_name=" + enc(album) + "&duration=" + duration);
                    if (json != null) {
                        result = fromJson(new JSONObject(json));
                    }
                }
                if (result == null && !TextUtils.isEmpty(author)) {
                    final String json = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(author));
                    if (json != null) {
                        result = best(new JSONArray(json), duration);
                    }
                }
                if (result == null) {
                    final String query = TextUtils.isEmpty(author) ? title : author + " " + title;
                    final String json = get("https://lrclib.net/api/search?q=" + enc(query));
                    if (json != null) {
                        result = best(new JSONArray(json), duration);
                    }
                }
                failed = false;
            } catch (Exception e) {
                FileLog.e(e);
                failed = true;
            }
            final MusicLyrics lyrics = result;
            final boolean error = failed;
            AndroidUtilities.runOnUIThread(() -> {
                if (lyrics != null) {
                    cache.put(key, lyrics);
                } else if (!error) {
                    missing.put(key, Boolean.TRUE);
                }
                callback.run(lyrics);
            });
        });
    }

    private static MusicLyrics best(JSONArray array, long duration) {
        MusicLyrics fallback = null;
        for (int pass = 0; pass < 2; ++pass) {
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final double itemDuration = item.optDouble("duration", 0);
                if (pass == 0 && duration > 0 && itemDuration > 0 && Math.abs(itemDuration - duration) > 4) {
                    continue;
                }
                final MusicLyrics lyrics = fromJson(item);
                if (lyrics != null && lyrics.synced) {
                    return lyrics;
                }
                if (lyrics != null && fallback == null) {
                    fallback = lyrics;
                }
            }
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }

    private static MusicLyrics fromJson(JSONObject object) {
        if (object == null || object.optBoolean("instrumental", false)) {
            return null;
        }
        final MusicLyrics synced = parse(string(object, "syncedLyrics"), false);
        if (synced != null && synced.synced) {
            return synced;
        }
        final MusicLyrics plain = parse(string(object, "plainLyrics"), false);
        if (plain != null && synced == null) {
            return plain;
        }
        return synced;
    }

    private static String string(JSONObject object, String name) {
        if (object.isNull(name)) {
            return null;
        }
        return object.optString(name, null);
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String getOrNull(String url) {
        try {
            return get(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "StaticGram music player (Android)");
            connection.setRequestProperty("Accept", "application/json");
            final int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code / 100 != 2) {
                throw new IOException("LRCLIB HTTP " + code);
            }
            try (InputStream stream = connection.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}
