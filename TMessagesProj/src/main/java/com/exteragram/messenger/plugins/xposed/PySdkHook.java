package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyObject;

import de.robv.android.xposed.XC_MethodHook;

import org.telegram.messenger.FileLog;

/**
 * Xposed callback owned by the plugin SDK itself (IntentsManager, FilesController): unlike
 * {@link PyMethodHook} it is not tied to a plugin id, so it keeps working while any plugin
 * that registered a handler is loaded. The Python object provides before_hooked_method and/or
 * after_hooked_method; errors are logged and never reach the hooked method.
 */
public final class PySdkHook extends XC_MethodHook {
    private final PyObject beforeHook;
    private final PyObject afterHook;

    public PySdkHook(PyObject callback, int priority) {
        super(priority);
        if (callback == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.beforeHook = callback.containsKey("before_hooked_method") ? callback.get((Object) "before_hooked_method") : null;
        this.afterHook = callback.containsKey("after_hooked_method") ? callback.get((Object) "after_hooked_method") : null;
    }

    @Override
    public void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
        if (beforeHook == null) {
            return;
        }
        try {
            beforeHook.call(param);
        } catch (Throwable t) {
            FileLog.e("plugin SDK hook failed in beforeHookedMethod", t);
        }
    }

    @Override
    public void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
        if (afterHook == null) {
            return;
        }
        try {
            afterHook.call(param);
        } catch (Throwable t) {
            FileLog.e("plugin SDK hook failed in afterHookedMethod", t);
        }
    }
}
