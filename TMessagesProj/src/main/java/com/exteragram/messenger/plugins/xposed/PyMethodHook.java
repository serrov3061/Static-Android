package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.hooks.HookFilter;
import de.robv.android.xposed.XC_MethodHook;
import java.util.ArrayList;
import java.util.Iterator;
import kotlin.text.StringsKt;
import org.telegram.messenger.FileLog;

public final class PyMethodHook extends XC_MethodHook implements AutoCloseable {
    private final PyObject afterHook;
    private ArrayList<HookFilter> afterHookedFilters;
    private final PyObject beforeHook;
    private ArrayList<HookFilter> beforeHookedFilters;
    private volatile boolean closed;
    private volatile boolean disabled;
    private final String pluginId;
    private final PyObject pythonCallback;

    public PyMethodHook(String str, PyObject pyObject) {
        this(str, pyObject, true, true);
    }

    public PyMethodHook(String str, PyObject pyObject, int i) {
        this(str, pyObject, i, true, true);
    }

    public PyMethodHook(String str, PyObject pyObject, boolean z, boolean z2) {
        this.beforeHookedFilters = new ArrayList<>();
        this.afterHookedFilters = new ArrayList<>();
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        this.beforeHook = getCallbackIfPresent(pyObject, PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, z);
        this.afterHook = getCallbackIfPresent(pyObject, PluginsConstants.Xposed.AFTER_HOOKED_METHOD, z2);
    }

    public PyMethodHook(String str, PyObject pyObject, int i, boolean z, boolean z2) {
        super(i);
        this.beforeHookedFilters = new ArrayList<>();
        this.afterHookedFilters = new ArrayList<>();
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        this.beforeHook = getCallbackIfPresent(pyObject, PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, z);
        this.afterHook = getCallbackIfPresent(pyObject, PluginsConstants.Xposed.AFTER_HOOKED_METHOD, z2);
    }

    public final void setBeforeHookedFilters(ArrayList<HookFilter> beforeHookedFilters) {
        this.beforeHookedFilters = beforeHookedFilters;
    }

    public final void setAfterHookedFilters(ArrayList<HookFilter> afterHookedFilters) {
        this.afterHookedFilters = afterHookedFilters;
    }

    public final ArrayList<HookFilter> getBeforeHookedFilters() {
        return this.beforeHookedFilters;
    }

    public final ArrayList<HookFilter> getAfterHookedFilters() {
        return this.afterHookedFilters;
    }

    @Override
    public void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
        PyObject pyObject;
        if (this.disabled || (pyObject = this.beforeHook) == null || !PluginsController.Companion.getInstance().isPluginActive(this.pluginId)) {
            return;
        }
        try {
            if (executeFilters(this.beforeHookedFilters, param, true)) {
                pyObject.call(param);
            }
        } catch (Throwable th) {
            handleHookError("beforeHookedMethod", th);
        }
    }

    @Override
    public void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
        PyObject pyObject;
        if (this.disabled || (pyObject = this.afterHook) == null || !PluginsController.Companion.getInstance().isPluginActive(this.pluginId)) {
            return;
        }
        try {
            if (executeFilters(this.afterHookedFilters, param, false)) {
                pyObject.call(param);
            }
        } catch (Throwable th) {
            handleHookError("afterHookedMethod", th);
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.disabled = true;
        PyObject pyObject = this.beforeHook;
        if (pyObject != null) {
            pyObject.close();
        }
        PyObject pyObject2 = this.afterHook;
        if (pyObject2 != null) {
            pyObject2.close();
        }
    }

    private final boolean executeFilters(ArrayList<HookFilter> filters, XC_MethodHook.MethodHookParam param, boolean isBefore) {
        Iterator<HookFilter> it = filters.iterator();
        while (it.hasNext()) {
            if (!it.next().execute(param, isBefore)) {
                return false;
            }
        }
        return true;
    }

    private final PyObject getCallbackIfPresent(PyObject callbackObject, String name, boolean enabled) {
        if (enabled && callbackObject.containsKey(name)) {
            return (PyObject) callbackObject.get((Object) name);
        }
        return null;
    }

    private final void handleHookError(String hookMethodName, Throwable t) {
        if ((t instanceof PyException) && t.getMessage() != null && StringsKt.contains((CharSequence) t.getMessage(), (CharSequence) "closed", false)) {
            this.disabled = true;
            FileLog.e("Attempted to call a closed PyObject callback in " + this.pluginId);
            return;
        }
        FileLog.e("Plugin '" + this.pluginId + "' crashed in " + hookMethodName + ": " + t.getMessage(), t);
    }
}
