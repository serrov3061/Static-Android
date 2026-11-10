package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

public final class LongPref extends BasePref<Long> {

    public LongPref(long defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Long.class;
    }

    @Override
    protected Long fetch(String key, Long defaultValue) {
        return ExteraConfig.getPreferences().getLong(key, defaultValue);
    }

    @Override
    protected void save(String key, Long value) {
        ExteraConfig.getEditor().putLong(key, value).apply();
    }
}
