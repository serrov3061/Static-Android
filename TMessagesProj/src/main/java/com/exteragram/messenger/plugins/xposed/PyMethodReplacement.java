package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.utils.PyObjectUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.InvocationTargetException;
import kotlin.text.StringsKt;
import org.telegram.messenger.FileLog;

public final class PyMethodReplacement extends XC_MethodReplacement implements AutoCloseable {
    private volatile boolean closed;
    private volatile boolean disabled;
    private final String pluginId;
    private final PyObject pythonCallback;
    private final PyObject replaceHook;

    public PyMethodReplacement(String str, PyObject pyObject) {
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        if (!pyObject.containsKey(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD)) {
            throw new IllegalArgumentException("Python callback object must contain a method named 'replaceHookedMethod'");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        Object obj = pyObject.get(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD);
        if (obj != null) {
            this.replaceHook = (PyObject) obj;
        } else {
            throw new IllegalArgumentException("Required value was null.");
        }
    }

    public PyMethodReplacement(String str, PyObject pyObject, int i) {
        super(i);
        if (pyObject == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        if (!pyObject.containsKey(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD)) {
            throw new IllegalArgumentException("Python callback object must contain a method named 'replaceHookedMethod'");
        }
        this.pluginId = str;
        this.pythonCallback = pyObject;
        Object obj = pyObject.get(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD);
        if (obj != null) {
            this.replaceHook = (PyObject) obj;
        } else {
            throw new IllegalArgumentException("Required value was null.");
        }
    }

    @Override
    public Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (this.disabled || !PluginsController.Companion.getInstance().isPluginActive(this.pluginId)) {
            try {
                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException == null) {
                    throw e;
                }
                throw targetException;
            }
        }
        PyObject pyObjectCall = null;
        Object java2 = null;
        try {
            pyObjectCall = this.replaceHook.call(param);
            if (pyObjectCall != null) {
                java2 = pyObjectCall.toJava(Object.class);
            }
            return java2;
        } catch (Throwable th) {
            handleHookError(th);
            java2 = null;
            return null;
        } finally {
            // shared values (primitives/strings) do not own the PyObject, everything else is released here
            if (!PyObjectUtils.isSharedValue(java2)) {
                PyObjectUtils.closeSafely(pyObjectCall);
            }
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.disabled = true;
        this.replaceHook.close();
    }

    private final void handleHookError(Throwable t) {
        if ((t instanceof PyException) && t.getMessage() != null && StringsKt.contains((CharSequence) t.getMessage(), (CharSequence) "closed", false)) {
            this.disabled = true;
            FileLog.e("Attempted to call a closed PyObject callback in " + this.pluginId);
            return;
        }
        FileLog.e("Plugin '" + this.pluginId + "' crashed in replaceHookedMethod: " + t.getMessage(), t);
    }
}
