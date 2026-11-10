package com.exteragram.messenger.plugins.utils;

import android.text.TextUtils;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

public final class PyObjectUtils {
    public static final PyObjectUtils INSTANCE = new PyObjectUtils();

    private PyObjectUtils() {
    }

    public static final boolean isSharedValue(Object value) {
        return value == null || (value instanceof Boolean) || (value instanceof Number) || (value instanceof Character) || (value instanceof String);
    }

    public static final void closeSafely(PyObject pyObject) {
        if (pyObject == null) {
            return;
        }
        try {
            pyObject.close();
        } catch (Throwable unused) {
        }
    }

    public static final <T> T toJavaCompat(PyObject pyObject, Class<T> clazz) {
        if (pyObject == null) {
            return null;
        }
        try {
            return pyObject.toJava(clazz);
        } catch (PyException | ClassCastException unused) {
            try {
                PyObject pyObjectCallAttr = pyObject.callAttr("__getattribute__", "java");
                if (pyObjectCallAttr != null) {
                    return pyObjectCallAttr.toJava(clazz);
                }
            } catch (PyException | ClassCastException ignored) {
            }
            return null;
        }
    }

    public static final String getString(PyObject pyObject, String key, String defaultValue) {
        return getString(pyObject, key, defaultValue, false);
    }

    public static final String getString(PyObject pyObject, String key, String defaultValue, boolean fromMap) {
        String string;
        if (pyObject != null && !TextUtils.isEmpty(key)) {
            try {
                PyObject pyObjectCallAttr = fromMap ? pyObject.callAttr("get", key) : (PyObject) pyObject.get((Object) key);
                if (pyObjectCallAttr != null && (string = pyObjectCallAttr.toString()) != null) {
                    return string;
                }
            } catch (PyException | ClassCastException unused) {
            }
        }
        return defaultValue;
    }

    public static final boolean getBoolean(PyObject pyObject, String key, boolean defaultValue) {
        if (pyObject != null && !TextUtils.isEmpty(key)) {
            try {
                PyObject pyObject2 = (PyObject) pyObject.get((Object) key);
                if (pyObject2 != null) {
                    return pyObject2.toBoolean();
                }
            } catch (PyException | ClassCastException unused) {
            }
        }
        return defaultValue;
    }

    public static final int getInt(PyObject pyObject, String key, int defaultValue) {
        return getInt(pyObject, key, defaultValue, false);
    }

    public static final int getInt(PyObject pyObject, String key, int defaultValue, boolean fromMap) {
        if (pyObject != null && !TextUtils.isEmpty(key)) {
            try {
                PyObject pyObjectCallAttr = fromMap ? pyObject.callAttr("get", key) : (PyObject) pyObject.get((Object) key);
                if (pyObjectCallAttr != null) {
                    return pyObjectCallAttr.toInt();
                }
            } catch (PyException | ClassCastException unused) {
            }
        }
        return defaultValue;
    }

    public static final String[] getStringArray(PyObject pyObject, String key, String[] defaultValue) {
        String[] strArr;
        if (pyObject != null && !TextUtils.isEmpty(key)) {
            try {
                PyObject pyObject2 = (PyObject) pyObject.get((Object) key);
                return (pyObject2 == null || (strArr = (String[]) pyObject2.toJava(String[].class)) == null || strArr.length == 0) ? defaultValue : strArr;
            } catch (PyException | ClassCastException unused) {
            }
        }
        return defaultValue;
    }
}
