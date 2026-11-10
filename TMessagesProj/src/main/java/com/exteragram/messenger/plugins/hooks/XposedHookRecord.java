package com.exteragram.messenger.plugins.hooks;

import de.robv.android.xposed.XC_MethodHook;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.jvm.internal.Intrinsics;
import org.telegram.messenger.FileLog;

public final class XposedHookRecord implements HookRecord {

    public static final Companion Companion = new Companion();
    private static final Map<AutoCloseable, Integer> callbackReferences = Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicBoolean cleanedUp = new AtomicBoolean();
    private final XC_MethodHook.Unhook unhookObject;

    public XposedHookRecord(XC_MethodHook.Unhook unhook) {
        this.unhookObject = unhook;
        Object callback = unhook != null ? unhook.getCallback() : null;
        Companion.retainCallback(callback instanceof AutoCloseable ? (AutoCloseable) callback : null);
    }

    @Override
    public void cleanup() {
        XC_MethodHook.Unhook unhook = this.unhookObject;
        if (!this.cleanedUp.compareAndSet(false, true) || unhook == null) {
            return;
        }
        try {
            unhook.unhook();
        } catch (Throwable th) {
            FileLog.e("Error during Xposed unhook cleanup", th);
        }
        try {
            Object callback = unhook.getCallback();
            Companion.releaseCallback(callback instanceof AutoCloseable ? (AutoCloseable) callback : null);
        } catch (Throwable th) {
            FileLog.e("Error during Xposed hook callback cleanup", th);
        }
    }

    /** exteraGram 13.1.1: the hooked method or constructor, or null. */
    public java.lang.reflect.Member getHookedMethod() {
        try {
            return unhookObject != null ? unhookObject.getHookedMethod() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public boolean matches(Object criteria) {
        return (criteria instanceof XC_MethodHook.Unhook) && this.unhookObject == criteria;
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other != null && Intrinsics.areEqual(XposedHookRecord.class, other.getClass()) && this.unhookObject == ((XposedHookRecord) other).unhookObject;
    }

    public int hashCode() {
        XC_MethodHook.Unhook unhook = this.unhookObject;
        if (unhook != null) {
            return unhook.hashCode();
        }
        return 0;
    }

    public static final class Companion {
        private Companion() {
        }

        public final void retainCallback(AutoCloseable callback) {
            if (callback == null) {
                return;
            }
            synchronized (callbackReferences) {
                Integer num = callbackReferences.get(callback);
                callbackReferences.put(callback, (num != null ? num : 0) + 1);
            }
        }

        public final void releaseCallback(AutoCloseable callback) throws Exception {
            boolean z;
            if (callback == null) {
                return;
            }
            synchronized (callbackReferences) {
                Integer num = callbackReferences.get(callback);
                int count = num != null ? num : 0;
                z = count <= 1;
                if (z) {
                    callbackReferences.remove(callback);
                } else {
                    callbackReferences.put(callback, count - 1);
                }
            }
            if (z) {
                callback.close();
            }
        }
    }
}
