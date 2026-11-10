package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

public final class BooleanPref extends BasePref<Boolean> {

    public BooleanPref(boolean defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Boolean.class;
    }

    @Override
    protected Boolean fetch(String key, Boolean defaultValue) {
        return ExteraConfig.getPreferences().getBoolean(key, defaultValue);
    }

    @Override
    protected void save(String key, Boolean value) {
        ExteraConfig.getEditor().putBoolean(key, value).apply();
    }
}
