package com.exteragram.messenger.nowplaying;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.collection.LruCache;

import com.exteragram.messenger.api.dto.NowPlayingDTO;
import com.exteragram.messenger.api.dto.NowPlayingInfoDTO;
import com.exteragram.messenger.api.model.NowPlayingServiceType;
import com.exteragram.messenger.debug.DebugConfig;
import com.exteragram.messenger.nowplaying.ui.components.NowPlayingCardData;
import com.exteragram.messenger.utils.network.RemoteUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ProfileMusicView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * "Now playing" card in the profile (port of exteraGram's Kotlin NowPlayingController, coroutines -> Executor).
 *
 * exteraGram sources of a track:
 *  1) live track from Last.fm / stats.fm via api.exteragram.app (ApiService.getCurrentPlayingTrack) — exteraGram service,
 *     DISABLED here ({@link #LIVE_API_ENABLED}); the scrobbling account is registered through exteraAuthBot (also disabled);
 *  2) profile music (UserFull.saved_music) — shown as a card, cover/album looked up in iTunes Search API
 *     (https://itunes.apple.com/search, external Apple service: title and performer are sent there).
 */
public final class NowPlayingController {

    /** exteraGram backend (api.exteragram.app) live tracks — off on StaticGram (EXTERA_PORT §7). */
    public static final boolean LIVE_API_ENABLED = false;
    /** Scrobbling service setup screen (UserInfoActivity -> SetupNowPlayingActivity): useless without the backend. */
    public static final boolean SETUP_ENABLED = false;

    private static final String ITUNES_SEARCH_URL = "https://itunes.apple.com/search";
    private static final int TIMEOUT_MS = 5000;
    private static final Pattern ARTISTS_SPLITTER = Pattern.compile("(?i)\\s*(?:,|&|\\bfeat\\b\\.?|\\bft\\b\\.?)\\s*");
    private static final LruCache<String, List<ItunesTrack>> itunesCache = new LruCache<>(50);
    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "NowPlaying");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private NowPlayingController() {
    }

    /** Handle of a running request (replacement of the coroutine Job). */
    public static final class Request {
        private volatile boolean cancelled;
        private volatile boolean done;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isActive() {
            return !cancelled && !done;
        }
    }

    public static boolean shouldShowCard(NowPlayingCardData data) {
        if (data == null) {
            return false;
        }
        return !(data.getNowPlayingDTO().isTelegram() && isSeparateStylesSupported());
    }

    /** true = profile music stays in Telegram's own strip under the name; false (exteraGram default) = shown as a card. */
    public static boolean isSeparateStylesSupported() {
        return DebugConfig.getForceCompactSavedMusic() || RemoteUtils.getBooleanConfigValue("separate_music_styles", false);
    }

    /**
     * Resolves the track for the profile card. The callback gets (track or null, elapsed ms) on the UI thread.
     */
    public static Request getCurrentPlayingTrack(long userId, TLRPC.Document savedMusic, boolean checkApi, BiConsumer<NowPlayingDTO, Long> callback) {
        final Request request = new Request();
        final long startTime = System.currentTimeMillis();
        executor.execute(() -> {
            NowPlayingDTO track = null;
            try {
                NowPlayingDTO live = checkApi && LIVE_API_ENABLED ? fetchLiveTrack(userId) : null;
                if (live != null && live.isPlaying()) {
                    track = live;
                } else if (!request.isCancelled()) {
                    track = processSavedMusic(savedMusic);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final NowPlayingDTO finalTrack = track;
            AndroidUtilities.runOnUIThread(() -> {
                request.done = true;
                if (!request.isCancelled()) {
                    callback.accept(finalTrack, System.currentTimeMillis() - startTime);
                }
            });
        });
        return request;
    }

    /** exteraGram: api.exteragram.app/api/v1/profiles/{id}/now-playing. Not available on StaticGram. */
    private static NowPlayingDTO fetchLiveTrack(long userId) {
        return null;
    }

    private static List<String> splitArtists(String artists) {
        ArrayList<String> result = new ArrayList<>();
        if (artists == null) {
            return result;
        }
        for (String s : ARTISTS_SPLITTER.split(artists)) {
            String trimmed = s.trim();
            if (trimmed.length() > 0) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static NowPlayingDTO processSavedMusic(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        CharSequence titleSeq = ProfileMusicView.getTitle(document);
        String title = titleSeq instanceof String ? (String) titleSeq : null;
        if (title == null) {
            title = LocaleController.getString(R.string.AudioUnknownTitle);
        }
        CharSequence authorSeq = ProfileMusicView.getAuthor(document);
        String author = authorSeq instanceof String ? (String) authorSeq : null;
        if (author == null) {
            author = LocaleController.getString(R.string.AudioUnknownArtist);
        }
        if (title == null || author == null) {
            return null;
        }
        NowPlayingDTO base = new NowPlayingDTO(title, splitArtists(author), null, null, null, null, true, null, NowPlayingDTO.PLATFORM_TELEGRAM, null);
        if (isSeparateStylesSupported()) {
            return base;
        }
        List<ItunesTrack> list = fetchItunesTrack(author, title);
        if (list == null || list.isEmpty()) {
            return base;
        }
        ItunesTrack match = null;
        for (ItunesTrack t : list) {
            boolean commonArtist = hasCommonArtist(base.getArtists(), t.artistName).first;
            if (t.trackName != null && t.trackName.equalsIgnoreCase(base.getTrackName()) && commonArtist) {
                match = t;
                break;
            }
        }
        if (match == null) {
            match = list.get(0);
        }
        List<String> artists = hasCommonArtist(base.getArtists(), match.artistName).second;
        String album = match.collectionName != null ? match.collectionName : base.getAlbumName();
        String cover = !TextUtils.isEmpty(match.artworkUrl100) ? match.artworkUrl100.replace("100x100", "300x300") : base.getCoverUrl();
        return base.withItunes(artists, album, cover);
    }

    private static final class Pair<A, B> {
        final A first;
        final B second;

        Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    private static Pair<Boolean, List<String>> hasCommonArtist(List<String> baseArtists, String itunesArtists) {
        if (itunesArtists == null || itunesArtists.trim().isEmpty()) {
            return new Pair<>(false, baseArtists);
        }
        List<String> itunesList = splitArtists(itunesArtists);
        if (baseArtists == null || baseArtists.isEmpty()) {
            return new Pair<>(false, itunesList);
        }
        boolean found = false;
        outer:
        for (String base : baseArtists) {
            String lower = base.toLowerCase(Locale.ROOT);
            String lowerTranslit = AndroidUtilities.translitSafe(lower);
            for (String it : itunesList) {
                String itLower = it.toLowerCase(Locale.ROOT);
                String itTranslit = AndroidUtilities.translitSafe(itLower);
                if (itLower.equals(lower) || itLower.equals(lowerTranslit) || itTranslit.equals(lower)) {
                    found = true;
                    break outer;
                }
            }
        }
        return new Pair<>(found, found ? itunesList : baseArtists);
    }

    private static final class ItunesTrack {
        String trackName;
        String artistName;
        String collectionName;
        String artworkUrl100;
        String previewUrl;
        Long trackTimeMillis;
    }

    /** iTunes Search API (external Apple service, no key): term = "title - performer", entity=song, limit=5. */
    private static List<ItunesTrack> fetchItunesTrack(String performer, String title) {
        if (TextUtils.isEmpty(performer == null ? null : performer.trim()) && TextUtils.isEmpty(title == null ? null : title.trim())) {
            return null;
        }
        String cacheKey = performer + " - " + title;
        List<ItunesTrack> cached;
        synchronized (itunesCache) {
            cached = itunesCache.get(cacheKey);
        }
        if (cached != null) {
            return cached;
        }
        HttpURLConnection connection = null;
        try {
            String url = ITUNES_SEARCH_URL + "?term=" + URLEncoder.encode(title + " - " + performer, "UTF-8") + "&entity=song&limit=5";
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                body = out.toString("UTF-8");
            }
            JSONArray results = new JSONObject(body).optJSONArray("results");
            ArrayList<ItunesTrack> list = new ArrayList<>();
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.optJSONObject(i);
                    if (o == null) continue;
                    ItunesTrack t = new ItunesTrack();
                    t.trackName = optString(o, "trackName");
                    t.artistName = optString(o, "artistName");
                    t.collectionName = optString(o, "collectionName");
                    t.artworkUrl100 = optString(o, "artworkUrl100");
                    t.previewUrl = optString(o, "previewUrl");
                    t.trackTimeMillis = o.has("trackTimeMillis") ? o.optLong("trackTimeMillis") : null;
                    list.add(t);
                }
            }
            synchronized (itunesCache) {
                itunesCache.put(cacheKey, list);
            }
            return list;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String optString(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    // ---- scrobbling account of the current user (exteraGram: Room DB + api.exteragram.app + exteraAuthBot) ----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("extera_nowplaying", Context.MODE_PRIVATE);
    }

    private static String prefKey() {
        return "info_" + UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
    }

    /** Callback on a background thread (as in exteraGram). Only the locally saved value: no exteraGram backend. */
    public static void getNowPlayingInfo(Consumer<NowPlayingInfoDTO> callback) {
        executor.execute(() -> {
            NowPlayingInfoDTO result = null;
            try {
                String raw = prefs().getString(prefKey(), null);
                if (raw != null) {
                    int sep = raw.indexOf('|');
                    NowPlayingServiceType type = NowPlayingServiceType.valueOf(sep < 0 ? raw : raw.substring(0, sep));
                    result = new NowPlayingInfoDTO(type, sep < 0 ? null : raw.substring(sep + 1));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            callback.accept(result);
        });
    }

    public static void updateNowPlayingInfo(NowPlayingInfoDTO info, Consumer<Boolean> callback) {
        updateNowPlayingInfo(info, true, callback);
    }

    /**
     * exteraGram sends "set_now_playing TYPE username" / "clear_now_playing" to exteraAuthBot, then caches in its DB.
     * On StaticGram the bot is off: the value is only stored locally (nobody else sees it). Callback on a background thread.
     */
    public static void updateNowPlayingInfo(NowPlayingInfoDTO info, boolean cache, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean ok;
            try {
                SharedPreferences.Editor editor = prefs().edit();
                if (info == null || info.getServiceType() == NowPlayingServiceType.NONE) {
                    editor.remove(prefKey());
                } else {
                    editor.putString(prefKey(), info.getServiceType().name() + "|" + (info.getUsername() == null ? "" : info.getUsername()));
                }
                ok = editor.commit();
            } catch (Exception e) {
                FileLog.e(e);
                ok = false;
            }
            callback.accept(ok);
        });
    }
}
