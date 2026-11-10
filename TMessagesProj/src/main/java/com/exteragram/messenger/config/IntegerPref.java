package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

public final class IntegerPref extends BasePref<Integer> {

    public IntegerPref(int defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Integer.class;
    }

    @Override
    protected Integer fetch(String key, Integer defaultValue) {
        return ExteraConfig.getPreferences().getInt(key, defaultValue);
    }

    @Override
    protected void save(String key, Integer value) {
        ExteraConfig.getEditor().putInt(key, value).apply();
    }
}
