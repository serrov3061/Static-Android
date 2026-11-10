package com.exteragram.messenger.config;

import com.exteragram.messenger.backup.PreferencesUtils;

import java.util.ArrayList;
import java.util.List;

/** Registry of all ExteraConfig preferences (used by backup and reloadConfig). */
public final class PrefClassesKt {

    private static final List<BasePref<?>> allDelegates = new ArrayList<>();
    private static final List<PreferencesUtils.BackupItem> registeredKeys = new ArrayList<>();

    private PrefClassesKt() {
    }

    public static List<BasePref<?>> getAllDelegates() {
        return allDelegates;
    }

    public static List<PreferencesUtils.BackupItem> getRegisteredKeys() {
        return registeredKeys;
    }
}
