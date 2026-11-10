package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

public final class NullableStringPref extends BasePref<String> {

    public NullableStringPref(String defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return String.class;
    }

    @Override
    protected String fetch(String key, String defaultValue) {
        return ExteraConfig.getPreferences().getString(key, defaultValue);
    }

    @Override
    protected void save(String key, String value) {
        ExteraConfig.getEditor().putString(key, value).apply();
    }
}
