package com.exteragram.messenger.config;

import com.exteragram.messenger.backup.PreferencesUtils;

import java.util.Objects;

/**
 * Lazily cached SharedPreferences value of {@link com.exteragram.messenger.ExteraConfig}.
 * Port of exteraGram's Kotlin property delegate: same keys, same storage ("exteraconfig").
 */
public abstract class BasePref<T> {

    private final String key;
    private final T defaultValue;
    private volatile T cache;
    private volatile boolean loaded;

    protected BasePref(T defaultValue, String key) {
        this.defaultValue = defaultValue;
        this.key = key;
        PrefClassesKt.getRegisteredKeys().add(new PreferencesUtils.BackupItem(key, getBackupClass()));
        PrefClassesKt.getAllDelegates().add(this);
    }

    protected abstract T fetch(String key, T defaultValue);

    protected abstract void save(String key, T value);

    public abstract Class<?> getBackupClass();

    public final String getKey() {
        return key;
    }

    public final T getDefaultValue() {
        return defaultValue;
    }

    public final T get() {
        if (!loaded) {
            cache = fetch(key, defaultValue);
            loaded = true;
        }
        return cache;
    }

    public final void set(T value) {
        if (loaded && Objects.equals(cache, value)) {
            return;
        }
        cache = value;
        loaded = true;
        save(key, value);
    }

    public final void invalidate() {
        loaded = false;
        cache = null;
    }
}
