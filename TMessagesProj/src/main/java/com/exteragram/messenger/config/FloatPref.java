package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

/** Float preference; falls back to an int value stored by older versions. */
public final class FloatPref extends BasePref<Float> {

    public FloatPref(float defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Float.class;
    }

    @Override
    protected Float fetch(String key, Float defaultValue) {
        try {
            return ExteraConfig.getPreferences().getFloat(key, defaultValue);
        } catch (ClassCastException e) {
            try {
                return (float) ExteraConfig.getPreferences().getInt(key, (int) (float) defaultValue);
            } catch (Exception ignore) {
                return defaultValue;
            }
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    protected void save(String key, Float value) {
        ExteraConfig.getEditor().putFloat(key, value).apply();
    }
}
