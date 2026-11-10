package com.exteragram.messenger.plugins.hooks;

import com.exteragram.messenger.plugins.PluginsConstants;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.StringsKt;

public final class EventHookRecord implements HookRecord {
    private final String hookName;
    private final boolean matchSubstring;
    private final String pluginId;
    private final int priority;

    @Override
    public void cleanup() {
    }

    public EventHookRecord(String str, String str2, boolean z, int i) {
        this.pluginId = str;
        this.hookName = str2;
        this.matchSubstring = z;
        this.priority = i;
    }

    public final String getPluginId() {
        return this.pluginId;
    }

    public final String getHookName() {
        return this.hookName;
    }

    public final int getPriority() {
        return this.priority;
    }

    public final boolean isMatchSubstring() {
        return this.matchSubstring;
    }

    @Override
    public boolean matches(Object criteria) {
        String str;
        String str2 = criteria instanceof String ? (String) criteria : null;
        if (str2 == null || (str = this.hookName) == null) {
            return false;
        }
        if (this.matchSubstring) {
            return str.length() > 0 && StringsKt.contains((CharSequence) str2, (CharSequence) str, false);
        }
        return Intrinsics.areEqual(str, str2);
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other != null && Intrinsics.areEqual(EventHookRecord.class, other.getClass())) {
            EventHookRecord eventHookRecord = (EventHookRecord) other;
            if (this.matchSubstring == eventHookRecord.matchSubstring && Intrinsics.areEqual(this.pluginId, eventHookRecord.pluginId) && Intrinsics.areEqual(this.hookName, eventHookRecord.hookName)) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        String str = this.pluginId;
        int iHashCode = (str != null ? str.hashCode() : 0) * 31;
        String str2 = this.hookName;
        return ((iHashCode + (str2 != null ? str2.hashCode() : 0)) * 31) + Boolean.hashCode(this.matchSubstring);
    }
}
