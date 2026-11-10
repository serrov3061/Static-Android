package com.exteragram.messenger.config;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.utils.chats.DoubleTapUtils;

/** Integer preference whose value is passed through {@link DoubleTapUtils#sanitizeSetting(int)}. */
public final class SanitizedIntegerPref extends BasePref<Integer> {

    public SanitizedIntegerPref(int defaultValue, String key) {
        super(defaultValue, key);
    }

    @Override
    public Class<?> getBackupClass() {
        return Integer.class;
    }

    @Override
    protected Integer fetch(String key, Integer defaultValue) {
        return DoubleTapUtils.sanitizeSetting(ExteraConfig.getPreferences().getInt(key, defaultValue));
    }

    @Override
    protected void save(String key, Integer value) {
        ExteraConfig.getEditor().putInt(key, DoubleTapUtils.sanitizeSetting(value)).apply();
    }
}
