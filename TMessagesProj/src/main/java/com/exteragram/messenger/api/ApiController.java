package com.exteragram.messenger.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.exteragram.messenger.api.db.DatabaseHelper;
import com.exteragram.messenger.api.db.ExteraDatabase;
import com.exteragram.messenger.api.dto.ProfileDTO;
import com.exteragram.messenger.badges.BadgesConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import android.os.SystemClock;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

/**
 * exteraGram 12.10.6 profiles/badges sync, rewritten without Retrofit/Room/coroutines.
 * Same protocol as exteraGram: GET profiles (If-None-Match/ETag) for the first sync, then
 * GET profiles/updates?since=&lt;X-Sync-Timestamp&gt; deltas with deleted=true tombstones;
 * same throttling (profiles every 10 min, 5 min back-off after a failure).
 * StaticGram: the base URL is the app config key sg_badges_api_url; without it nothing is requested.
 */
public final class ApiController {

    public static final ApiController INSTANCE = new ApiController();

    /** A continuation for calling the "suspend"-shaped methods from Java; they never suspend here. */
    public static final Continuation<Object> CONTINUATION = new Continuation<Object>() {
        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(Object o) {
        }
    };

    private static final long PROFILES_SYNC_INTERVAL = 60 * 1000L;
    private static final long FAILURE_BACKOFF = 5 * 60 * 1000L;
    private static final String PROFILES_SYNC_TIME_KEY = "lastProfilesSyncTime";
    private static final String SYNC_FAILURE_TIME_KEY = "lastSyncFailureTime";
    private static final String SYNC_TIMESTAMP_KEY = "lastSyncTimestamp";
    private static final String ETAG_KEY = "profilesEtag";
    private static final String BASE_URL_KEY = "profilesBaseUrl";
    private static final String SYNC_TIMESTAMP_HEADER = "X-Sync-Timestamp";
    private static final int TIMEOUT = 15000;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile DispatchQueue queue;
    private static final Gson gson = new Gson();

    private ApiController() {
    }

    public static DispatchQueue getQueue() {
        if (queue == null) {
            synchronized (ApiController.class) {
                if (queue == null) {
                    queue = new DispatchQueue("exteraApiQueue");
                }
            }
        }
        return queue;
    }

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("exteraapi", Context.MODE_PRIVATE);
    }

    public static void init() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        sync();
        try {
            ForegroundDetector detector = ForegroundDetector.getInstance();
            if (detector != null) {
                detector.addListener(new ForegroundDetector.Listener() {
                    @Override
                    public void onBecameForeground() {
                        sync();
                    }

                    @Override
                    public void onBecameBackground() {
                    }
                });
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void sync() {
        sync(null, false);
    }

    public static void sync(SharedPreferences prefs) {
        sync(prefs, false);
    }

    public static void sync(SharedPreferences prefs, boolean force) {
        sync(prefs, force, null);
    }

    private static final long SYNC_NOW_DEBOUNCE = 15 * 1000L;
    private static long lastSyncNow;
    private static long lastConfigRequest;
    private static Runnable awaitingConfig;

    /**
     * Immediate sync that ignores the 1-minute throttle (debounced to once per ~15 s), for screens that
     * show the current badge. If the app config has no sg_badges_api_url yet, a fresh config is requested
     * first and the sync runs when it arrives. onDone runs on the UI thread when finished (or skipped).
     */
    public static void syncNow(Runnable onDone) {
        AndroidUtilities.runOnUIThread(() -> {
            long now = SystemClock.elapsedRealtime();
            if (TextUtils.isEmpty(BadgesConfig.getApiUrl())) {
                FileLog.d("badges: syncNow, sg_badges_api_url absent in cached config, requesting app config");
                awaitingConfig = onDone;
                if (now - lastConfigRequest >= SYNC_NOW_DEBOUNCE) {
                    lastConfigRequest = now;
                    try {
                        MessagesController.getInstance(UserConfig.selectedAccount).loadAppConfig(true);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }
                return;
            }
            if (now - lastSyncNow < SYNC_NOW_DEBOUNCE) {
                FileLog.d("badges: syncNow debounced");
                if (onDone != null) {
                    onDone.run();
                }
                return;
            }
            lastSyncNow = now;
            sync(null, true, onDone);
        });
    }

    /** Called by MessagesController after help.getAppConfig was applied. */
    public static void onAppConfigLoaded() {
        try {
            String url = BadgesConfig.getApiUrl();
            FileLog.d("badges: app config loaded, sg_badges_api_url " + (TextUtils.isEmpty(url) ? "ABSENT" : "present=" + url)
                    + ", bot " + (TextUtils.isEmpty(BadgesConfig.getBotUsername()) ? "absent" : "present")
                    + ", shop " + (TextUtils.isEmpty(BadgesConfig.getShopUrl()) ? "absent" : "present")
                    + ", default doc " + BadgesConfig.getDefaultDocumentId() + ", developer doc " + BadgesConfig.getDeveloperDocumentId());
            if (TextUtils.isEmpty(url)) {
                return;
            }
            Runnable cb = awaitingConfig;
            awaitingConfig = null;
            if (cb != null) {
                lastSyncNow = SystemClock.elapsedRealtime();
                sync(null, true, cb);
            } else {
                sync(null, false, null);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void sync(SharedPreferences prefs, boolean force, Runnable onDone) {
        try {
            getQueue().postRunnable(() -> {
                try {
                    INSTANCE.performSync(prefs != null ? prefs : getPreferences(), force, CONTINUATION);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                AndroidUtilities.runOnUIThread(() -> com.exteragram.messenger.badges.BadgesController.INSTANCE.refreshAllowedFrames(null));
                if (onDone != null) {
                    AndroidUtilities.runOnUIThread(onDone);
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static final long PROFILE_OPEN_MIN_AGE = 30 * 1000L;

    /**
     * Called when a profile screen opens: syncs only if the last successful sync is older than 30 s
     * (and the error backoff has passed). Runs on the serial API queue, so never more than one request per 30 s.
     * Changed badges refresh the UI through the DB invalidation tracker -> BadgesController.notifyBadgesChanged().
     */
    public static void syncOnProfileOpen() {
        try {
            getQueue().postRunnable(() -> {
                try {
                    SharedPreferences prefs = getPreferences();
                    long now = System.currentTimeMillis();
                    if (elapsedSince(now, prefs.getLong(SYNC_FAILURE_TIME_KEY, 0)) < FAILURE_BACKOFF
                            || elapsedSince(now, prefs.getLong(PROFILES_SYNC_TIME_KEY, 0)) < PROFILE_OPEN_MIN_AGE) {
                        return;
                    }
                    INSTANCE.performSync(prefs, true, CONTINUATION);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean foregroundLoopRunning;
    private static final Runnable FOREGROUND_TICK = new Runnable() {
        @Override
        public void run() {
            if (org.telegram.messenger.ApplicationLoader.mainInterfacePaused) {
                foregroundLoopRunning = false;
                return;
            }
            // regular (throttled to 1 min, error backoff respected); UI refresh happens via the DB
            // invalidation tracker only when the set of profiles actually changed
            sync(null, false, null);
            AndroidUtilities.runOnUIThread(this, PROFILES_SYNC_INTERVAL);
        }
    };

    /** Starts the 1-minute periodic sync while the app is in the foreground (call from LaunchActivity.onResume). */
    public static void onForeground() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!foregroundLoopRunning) {
                foregroundLoopRunning = true;
                AndroidUtilities.runOnUIThread(FOREGROUND_TICK);
            }
        });
    }

    public static void resetSyncState() {
        resetSyncState(null);
    }

    public static void resetSyncState(SharedPreferences prefs) {
        SharedPreferences p = prefs != null ? prefs : getPreferences();
        p.edit()
                .remove(SYNC_TIMESTAMP_KEY)
                .remove(ETAG_KEY)
                .remove(PROFILES_SYNC_TIME_KEY)
                .remove(SYNC_FAILURE_TIME_KEY)
                .apply();
    }

    private static long elapsedSince(long now, long then) {
        return then <= 0 || then > now ? Long.MAX_VALUE : now - then;
    }

    /** One sync pass (runs on the API queue). Returns Unit like the original suspend function. */
    public final Object performSync(SharedPreferences prefs, boolean force, Continuation<? super Unit> continuation) {
        String baseUrl = BadgesConfig.getApiUrl();
        if (TextUtils.isEmpty(baseUrl)) {
            FileLog.d("badges: sync skipped, sg_badges_api_url absent");
            return Unit.INSTANCE;
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        if (!baseUrl.equals(prefs.getString(BASE_URL_KEY, null))) {
            resetSyncState(prefs);
            prefs.edit().putString(BASE_URL_KEY, baseUrl).apply();
            force = true;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            if (elapsedSince(now, prefs.getLong(SYNC_FAILURE_TIME_KEY, 0)) < FAILURE_BACKOFF) {
                return Unit.INSTANCE;
            }
            if (elapsedSince(now, prefs.getLong(PROFILES_SYNC_TIME_KEY, 0)) < PROFILES_SYNC_INTERVAL) {
                return Unit.INSTANCE;
            }
        }
        String since = prefs.getString(SYNC_TIMESTAMP_KEY, null);
        String etag = prefs.getString(ETAG_KEY, null);
        boolean full = TextUtils.isEmpty(since);
        FileLog.d("badges: sync start " + (full ? "full" : "updates since=" + since) + " force=" + force + " etag=" + etag);
        HttpURLConnection connection = null;
        try {
            String url = full ? baseUrl + "profiles" : baseUrl + "profiles/updates?since=" + URLEncoder.encode(since, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setRequestProperty("Accept", "application/json");
            if (!TextUtils.isEmpty(etag)) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int code = connection.getResponseCode();
            String watermark = connection.getHeaderField(SYNC_TIMESTAMP_HEADER);
            String newEtag = connection.getHeaderField("ETag");
            FileLog.d("badges: sync http " + code + " etag=" + newEtag + " watermark=" + watermark);
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                SharedPreferences.Editor editor = prefs.edit().putLong(PROFILES_SYNC_TIME_KEY, now).remove(SYNC_FAILURE_TIME_KEY);
                if (!TextUtils.isEmpty(watermark)) {
                    editor.putString(SYNC_TIMESTAMP_KEY, watermark.trim());
                }
                editor.apply();
                return Unit.INSTANCE;
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("badges sync http " + code);
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = readAll(in);
            }
            List<ProfileDTO> profiles = gson.fromJson(body, new TypeToken<List<ProfileDTO>>() {}.getType());
            if (profiles == null) {
                profiles = new ArrayList<>();
            }
            FileLog.d("badges: sync profiles=" + profiles.size());
            long me = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
            for (ProfileDTO p : profiles) {
                if (p != null && p.getId() == me) {
                    FileLog.d("badges: current user " + me + " found: status=" + p.getStatus() + " canChange=" + p.getCanChangeBadge()
                            + " badge=" + p.getBadge() + " deleted=" + p.getDeleted());
                }
            }
            ArrayList<ProfileDTO> live = new ArrayList<>();
            ArrayList<Long> deleted = new ArrayList<>();
            for (ProfileDTO p : profiles) {
                if (p == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(p.getDeleted())) {
                    deleted.add(p.getId());
                } else {
                    live.add(p);
                }
            }
            if (full) {
                DatabaseHelper.INSTANCE.replaceProfiles(live);
            } else {
                if (!deleted.isEmpty()) {
                    DatabaseHelper.INSTANCE.deleteProfiles(deleted, CONTINUATION);
                }
                if (!live.isEmpty()) {
                    DatabaseHelper.INSTANCE.insertProfiles(live, CONTINUATION);
                }
            }
            SharedPreferences.Editor editor = prefs.edit().putLong(PROFILES_SYNC_TIME_KEY, now).remove(SYNC_FAILURE_TIME_KEY);
            if (!TextUtils.isEmpty(watermark)) {
                editor.putString(SYNC_TIMESTAMP_KEY, watermark.trim());
            }
            if (!TextUtils.isEmpty(newEtag)) {
                editor.putString(ETAG_KEY, newEtag);
            } else {
                editor.remove(ETAG_KEY);
            }
            editor.apply();
            if (full || !live.isEmpty() || !deleted.isEmpty()) {
                ExteraDatabase.INSTANCE.getInstance().getInvalidationTracker().refreshAsync();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            prefs.edit().putLong(SYNC_FAILURE_TIME_KEY, now).apply();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return Unit.INSTANCE;
    }

    /**
     * exteraGram 13.1.1 ApiController.refreshProfiles(long...): re-reads single profiles (GET profiles/{id})
     * right after a change made by the client, e.g. a badge transfer between two own accounts. A 404 removes
     * the row (the badge is gone), any other failure is ignored (the regular sync catches up).
     */
    public static void refreshProfiles(long... ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        try {
            getQueue().postRunnable(() -> {
                String baseUrl = BadgesConfig.getApiUrl();
                if (TextUtils.isEmpty(baseUrl)) {
                    return;
                }
                if (!baseUrl.endsWith("/")) {
                    baseUrl += "/";
                }
                ArrayList<ProfileDTO> live = new ArrayList<>();
                ArrayList<Long> gone = new ArrayList<>();
                for (long id : ids) {
                    if (id == 0) {
                        continue;
                    }
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(baseUrl + "profiles/" + id).openConnection();
                        connection.setConnectTimeout(TIMEOUT);
                        connection.setReadTimeout(TIMEOUT);
                        connection.setRequestProperty("Accept", "application/json");
                        int code = connection.getResponseCode();
                        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                            gone.add(id);
                            continue;
                        }
                        if (code < 200 || code >= 300) {
                            continue;
                        }
                        String body;
                        try (InputStream in = connection.getInputStream()) {
                            body = readAll(in);
                        }
                        ProfileDTO p = gson.fromJson(body, ProfileDTO.class);
                        if (p == null || p.getId() != id) {
                            continue;
                        }
                        if (Boolean.TRUE.equals(p.getDeleted())) {
                            gone.add(id);
                        } else {
                            live.add(p);
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    } finally {
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }
                }
                FileLog.d("badges: refreshProfiles live=" + live.size() + " gone=" + gone.size());
                try {
                    if (!gone.isEmpty()) {
                        DatabaseHelper.INSTANCE.deleteProfiles(gone, CONTINUATION);
                    }
                    if (!live.isEmpty()) {
                        DatabaseHelper.INSTANCE.insertProfiles(live, CONTINUATION);
                    }
                    if (!gone.isEmpty() || !live.isEmpty()) {
                        ExteraDatabase.INSTANCE.getInstance().getInvalidationTracker().refreshAsync();
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                // the updates feed brings the same change later; force it so other rows stay consistent too
                sync(null, true, null);
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
