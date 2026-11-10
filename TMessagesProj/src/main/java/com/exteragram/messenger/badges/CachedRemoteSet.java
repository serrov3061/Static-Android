package com.exteragram.messenger.badges;

import android.content.SharedPreferences;

import com.exteragram.messenger.utils.network.RemoteUtils;

import org.telegram.messenger.FileLog;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** exteraGram 12.10.6: a set of ids from the remote config (string set), with a default. */
public final class CachedRemoteSet {

    private volatile Set<Long> cachedValues;
    private SharedPreferences.OnSharedPreferenceChangeListener changeListener;
    private final Set<Long> defaultSet;
    private volatile boolean listenerInitialized = false;
    private final String remoteKey;

    public CachedRemoteSet(String remoteKey, Set<Long> defaultSet) {
        this.remoteKey = remoteKey;
        this.defaultSet = Collections.unmodifiableSet(new HashSet<>(defaultSet));
        initializeListener();
    }

    private void initializeListener() {
        if (listenerInitialized) {
            return;
        }
        RemoteUtils.initCached();
        if (RemoteUtils.sharedPreferences != null) {
            changeListener = (prefs, key) -> {
                if (remoteKey.equals(key)) {
                    cachedValues = null;
                }
            };
            RemoteUtils.sharedPreferences.registerOnSharedPreferenceChangeListener(changeListener);
            listenerInitialized = true;
        }
    }

    private Set<Long> getSet() {
        if (!listenerInitialized) {
            initializeListener();
        }
        Set<Long> values = cachedValues;
        if (values == null) {
            Set<Long> source;
            SharedPreferences prefs = RemoteUtils.sharedPreferences;
            if (prefs != null && prefs.contains(remoteKey)) {
                Set<String> strings = prefs.getStringSet(remoteKey, Collections.emptySet());
                source = new HashSet<>(strings.size());
                for (String s : strings) {
                    try {
                        source.add(Long.parseLong(s));
                    } catch (NumberFormatException e) {
                        FileLog.e("Failed to parse long from remote config for key " + remoteKey + ": " + s, e);
                    }
                }
            } else {
                source = defaultSet;
            }
            values = ConcurrentHashMap.newKeySet(source.size());
            values.addAll(source);
            cachedValues = values;
        }
        return Collections.unmodifiableSet(values);
    }

    public boolean contains(long id) {
        return getSet().contains(id);
    }
}
