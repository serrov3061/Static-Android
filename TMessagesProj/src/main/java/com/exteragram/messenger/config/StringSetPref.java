package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

import java.util.Set;

public final class StringSetPref extends BasePref<Set<String>> {

    public StringSetPref(Set<String> defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Set.class;
    }

    @Override
    protected Set<String> fetch(String key, Set<String> defaultValue) {
        Set<String> value = ExteraConfig.getPreferences().getStringSet(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    @Override
    protected void save(String key, Set<String> value) {
        ExteraConfig.getEditor().putStringSet(key, value).apply();
    }
}
