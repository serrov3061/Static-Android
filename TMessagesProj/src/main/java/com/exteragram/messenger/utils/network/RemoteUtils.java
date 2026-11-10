package com.exteragram.messenger.utils.network;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;

/**
 * exteraGram remote config ("exteraremoteconfig" SharedPreferences).
 * exteraGram refreshes it from its own Telegram channel; on StaticGram the network refresh is not ported yet
 * (stage 4), so only the cached values / defaults are served.
 */
public final class RemoteUtils {

    public static SharedPreferences sharedPreferences;

    private RemoteUtils() {
    }

    private static SharedPreferences getPrefs() {
        if (sharedPreferences == null) {
            initCached();
        }
        return sharedPreferences;
    }

    public static void initCached() {
        if (sharedPreferences == null && ApplicationLoader.applicationContext != null) {
            sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("exteraremoteconfig", 0);
        }
    }

    public static void init() {
        initCached();
    }

    public static void forceRefresh() {
        initCached();
    }

    public static Integer getIntConfigValue(String key, int defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return defaultValue;
            }
            Object value = prefs.getAll().get(key);
            if (value instanceof String) {
                return Integer.parseInt((String) value);
            }
            if (value instanceof Long) {
                return ((Long) value).intValue();
            }
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Exception e) {
            FileLog.e("Error getting int config value for key: " + key, e);
        }
        return defaultValue;
    }

    public static Float getFloatConfigValue(String key, float defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return defaultValue;
            }
            Object value = prefs.getAll().get(key);
            if (value instanceof String) {
                return Float.parseFloat((String) value);
            }
            if (value instanceof Float) {
                return (Float) value;
            }
            if (value instanceof Long) {
                return ((Long) value).floatValue();
            }
            if (value instanceof Integer) {
                return ((Integer) value).floatValue();
            }
        } catch (Exception e) {
            FileLog.e("Error getting value for key: " + key, e);
        }
        return defaultValue;
    }

    public static Boolean getBooleanConfigValue(String key, boolean defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) {
                return defaultValue;
            }
            try {
                return prefs.getBoolean(key, defaultValue);
            } catch (ClassCastException e) {
                Object value = prefs.getAll().get(key);
                return value instanceof String ? Boolean.parseBoolean((String) value) : defaultValue;
            }
        } catch (Exception e) {
            FileLog.e("Error getting value for key: " + key, e);
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    public static Set<String> getStringSetConfigValue(String key, Set<String> defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                Object value = prefs.getAll().get(key);
                if (value instanceof Set) {
                    return (Set<String>) value;
                }
                if (value instanceof String) {
                    return new HashSet<>(Arrays.asList(((String) value).split(",\\s*")));
                }
            }
        } catch (Exception e) {
            FileLog.e("Error getting value for key: " + key, e);
        }
        return defaultValue;
    }

    public static String getStringConfigValue(String key, String defaultValue) {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                Object value = prefs.getAll().get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        } catch (Exception e) {
            FileLog.e("Error getting value for key: " + key, e);
        }
        return defaultValue;
    }

    // exteraGram plugins: messages.search in the exteraGram service channel (used by the Python SDK updater)
    private static final long SERVICE_CHANNEL_ID = 2227431611L;
    private static final String SERVICE_CHANNEL_USERNAME = "XS6GEcz5ZXMu82UvXQc";

    public static void searchMessages(String query, TLRPC.MessagesFilter filter, Utilities.Callback2<TLRPC.messages_Messages, TLRPC.TL_error> callback, int timeoutMs) {
        searchMessages(50, query, filter, callback, timeoutMs);
    }

    /** Searches the service channel; retries up to 3 times on timeout, the callback is called exactly once. */
    public static void searchMessages(int limit, String query, TLRPC.MessagesFilter filter, Utilities.Callback2<TLRPC.messages_Messages, TLRPC.TL_error> callback, int timeoutMs) {
        if (true) {
            // StaticGram: the exteraGram service channel does not exist on our server (every request was a
            // PEER_ID_INVALID); the plugin SDK ships inside the APK, so report "no update" without a request.
            TLRPC.TL_error error = new TLRPC.TL_error();
            error.code = 400;
            error.text = "PEER_ID_INVALID";
            callback.run(null, error);
            return;
        }
        AccountInstance accountInstance = AccountInstance.getInstance(UserConfig.selectedAccount);
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.q = query;
        req.offset_id = 0;
        req.limit = limit;
        req.filter = filter;
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger reqId = new AtomicInteger();
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        Runnable fail = () -> {
            if (done.compareAndSet(false, true)) {
                TLRPC.TL_error error = new TLRPC.TL_error();
                error.code = 408;
                error.text = "REQUEST_TIMEOUT";
                callback.run(null, error);
            }
        };
        Runnable send = () -> {
            if (done.get()) {
                return;
            }
            attempts.incrementAndGet();
            reqId.set(accountInstance.getConnectionsManager().sendRequest(req, (response, error) -> {
                if (done.compareAndSet(false, true)) {
                    AndroidUtilities.cancelRunOnUIThread(timeout.get());
                    if (error != null || response == null) {
                        callback.run(null, error);
                    } else {
                        callback.run((TLRPC.messages_Messages) response, null);
                    }
                }
            }));
        };
        timeout.set(() -> {
            if (done.get()) {
                return;
            }
            accountInstance.getConnectionsManager().cancelRequest(reqId.get(), false);
            if (attempts.get() < 3) {
                AndroidUtilities.runOnUIThread(send);
                AndroidUtilities.runOnUIThread(timeout.get(), timeoutMs);
            } else {
                fail.run();
            }
        });
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.InputPeer inputPeer = accountInstance.getMessagesController().getInputPeer(-SERVICE_CHANNEL_ID);
            req.peer = inputPeer;
            if (inputPeer != null && inputPeer.access_hash != 0) {
                send.run();
                AndroidUtilities.runOnUIThread(timeout.get(), timeoutMs);
                return;
            }
            ChatUtils.getInstance().resolveChannel(SERVICE_CHANNEL_USERNAME, chat -> {
                if (chat != null && chat.id == SERVICE_CHANNEL_ID) {
                    TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
                    peer.channel_id = chat.id;
                    peer.access_hash = chat.access_hash;
                    req.peer = peer;
                    send.run();
                    AndroidUtilities.runOnUIThread(timeout.get(), timeoutMs);
                } else if (done.compareAndSet(false, true)) {
                    TLRPC.TL_error error = new TLRPC.TL_error();
                    error.code = 400;
                    error.text = "CHANNEL_RESOLVE_FAILED";
                    callback.run(null, error);
                }
            });
        });
    }
}
