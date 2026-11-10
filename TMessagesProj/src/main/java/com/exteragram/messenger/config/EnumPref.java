package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;

/** Enum preference stored as its ordinal (exteraGram format). */
public final class EnumPref<E extends Enum<E>> extends BasePref<E> {

    public EnumPref(E defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Integer.class;
    }

    @Override
    protected E fetch(String key, E defaultValue) {
        int ordinal = ExteraConfig.getPreferences().getInt(key, defaultValue.ordinal());
        try {
            E[] values = defaultValue.getDeclaringClass().getEnumConstants();
            if (values != null && ordinal >= 0 && ordinal < values.length) {
                return values[ordinal];
            }
        } catch (Exception ignore) {
        }
        return defaultValue;
    }

    @Override
    protected void save(String key, E value) {
        ExteraConfig.getEditor().putInt(key, value.ordinal()).apply();
    }
}
