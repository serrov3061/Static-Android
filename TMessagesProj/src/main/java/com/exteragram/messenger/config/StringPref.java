package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

public final class StringPref extends BasePref<String> {

    public StringPref(String defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return String.class;
    }

    @Override
    protected String fetch(String key, String defaultValue) {
        String value = ExteraConfig.getPreferences().getString(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    @Override
    protected void save(String key, String value) {
        ExteraConfig.getEditor().putString(key, value).apply();
    }
}
