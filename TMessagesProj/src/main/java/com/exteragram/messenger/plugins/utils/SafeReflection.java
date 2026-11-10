package com.exteragram.messenger.plugins.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XposedBridge;

/**
 * Class.getDeclaredMethods() resolves every parameter/return type and throws NoClassDefFoundError
 * when one method of the class mentions a class missing on this Android version (e.g. LaunchActivity
 * overrides taking android.window.SplashScreenView / android.app.PictureInPictureUiState on Android 11
 * and older). Plugins then fail to hook any method of that class. This variant skips only the methods
 * that cannot be resolved.
 */
public final class SafeReflection {

    private static Method uncheckedMethod;
    private static boolean uncheckedLookupDone;

    private SafeReflection() {
    }

    public static Method[] getDeclaredMethods(Class<?> cls) {
        try {
            return cls.getDeclaredMethods();
        } catch (LinkageError e) {
            Method[] all = getDeclaredMethodsUnchecked(cls);
            if (all == null) {
                throw e;
            }
            ArrayList<Method> ok = new ArrayList<>(all.length);
            for (Method m : all) {
                try {
                    m.getReturnType();
                    m.getParameterTypes();
                    ok.add(m);
                } catch (LinkageError ignore) {
                }
            }
            return ok.toArray(new Method[0]);
        }
    }

    private static synchronized Method[] getDeclaredMethodsUnchecked(Class<?> cls) {
        if (!uncheckedLookupDone) {
            uncheckedLookupDone = true;
            try {
                XposedBridge.disableHiddenApiRestrictions();
            } catch (Throwable ignore) {
            }
            try {
                Method m = Class.class.getDeclaredMethod("getDeclaredMethodsUnchecked", boolean.class);
                m.setAccessible(true);
                uncheckedMethod = m;
            } catch (Throwable ignore) {
            }
        }
        if (uncheckedMethod == null) {
            return null;
        }
        try {
            return (Method[]) uncheckedMethod.invoke(cls, false);
        } catch (Throwable t) {
            return null;
        }
    }
}
