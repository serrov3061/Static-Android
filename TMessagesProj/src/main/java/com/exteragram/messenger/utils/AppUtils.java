package com.exteragram.messenger.utils;

import android.util.Log;

import androidx.annotation.Keep;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

/**
 * exteraGram plugins: the helpers of exteraGram's AppUtils used by the plugin engine and by the Python SDK
 * (logging and reflection helpers are called from Python, so their names and signatures must stay as they are).
 */
public class AppUtils {

    private static Gson gson;

    public static Gson getGson() {
        if (gson == null) {
            gson = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes fieldAttributes) {
                    Package pkg = fieldAttributes.getDeclaringClass().getPackage();
                    return pkg != null && isAndroidPackage(pkg.getName());
                }

                @Override
                public boolean shouldSkipClass(Class<?> cls) {
                    Package pkg = cls.getPackage();
                    return pkg != null && isAndroidPackage(pkg.getName());
                }
            }).create();
        }
        return gson;
    }

    private static boolean isAndroidPackage(String name) {
        return name.startsWith("android.") || name.startsWith("androidx.");
    }

    @Keep
    /** exteraGram: December, January and February (gates the Winter app icon). */
    public static boolean isWinter() {
        int month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH);
        return month == java.util.Calendar.DECEMBER || month == java.util.Calendar.JANUARY || month == java.util.Calendar.FEBRUARY;
    }

    public static void log(String str) {
        logInternal(str, null, 5);
    }

    @Keep
    public static void log(Throwable th) {
        logInternal("", th, 5);
    }

    @Keep
    public static void log(String str, Throwable th) {
        logInternal(str, th, 5);
    }

    private static void logInternal(String str, Throwable th, int depth) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StackTraceElement element = stackTrace[Math.max(3, Math.min(depth, stackTrace.length - 1))];
        String className = element.getClassName();
        if (className.contains(".")) {
            className = className.substring(className.lastIndexOf('.') + 1);
        }
        if (className.contains("$")) {
            className = className.substring(className.lastIndexOf('$') + 1);
        }
        String tag = "[" + className + "]";
        String message = String.format("[%s] %s", element.getMethodName(), str);
        if (th != null) {
            Log.e(tag, message, th);
        } else {
            Log.d(tag, message);
        }
    }

    @Keep
    public static void printObjectDetails(Object obj) {
        if (obj == null) {
            return;
        }
        try {
            logInternal(obj.getClass().getName() + ": " + getGson().toJson(obj), null, 6);
        } catch (Exception e) {
            logInternal(obj.getClass().getName(), e, 6);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    @Keep
    public static Object getPrivateField(Object obj, String str) {
        try {
            Field field = findField(obj.getClass(), str);
            if (field != null) {
                field.setAccessible(true);
                return field.get(obj);
            }
        } catch (Exception e) {
            logInternal(obj.getClass().getName(), e, 6);
        }
        return null;
    }

    @Keep
    public static void setPrivateField(Object obj, String str, Object value) {
        try {
            Field field = findField(obj.getClass(), str);
            if (field != null) {
                field.setAccessible(true);
                field.set(obj, value);
            }
        } catch (Exception e) {
            logInternal(obj.getClass().getName(), e, 6);
        }
    }

    @Keep
    public static Object getPrivateStaticField(Class<?> cls, String str) {
        try {
            Field field = findField(cls, str);
            if (field != null) {
                field.setAccessible(true);
                return field.get(null);
            }
        } catch (Exception e) {
            logInternal(cls.getName(), e, 6);
        }
        return null;
    }

    @Keep
    public static void setPrivateStaticField(Class<?> cls, String str, Object value) {
        try {
            Field field = findField(cls, str);
            if (field != null) {
                field.setAccessible(true);
                field.set(null, value);
            }
        } catch (Exception e) {
            logInternal(cls.getName(), e, 6);
        }
    }

    public static String stackTraceToString(Throwable th) {
        StringWriter stringWriter = new StringWriter();
        th.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    public static boolean compareVersions(String operator, String version1, String version2) {
        return compare(operator, compareVersionValues(version1, version2));
    }

    public static boolean compareVersions(String operator, int version1, int version2) {
        return compare(operator, Integer.compare(version1, version2));
    }

    private static boolean compare(String operator, int result) {
        switch (operator) {
            case "<":
                return result < 0;
            case ">":
                return result > 0;
            case "<=":
                return result <= 0;
            case "==":
                return result == 0;
            case ">=":
                return result >= 0;
            default:
                throw new IllegalArgumentException("Unsupported operator: " + operator);
        }
    }

    /** "3-beta1" -> 3: a non-numeric suffix on a version part (e.g. in a plugin __app_version__) does not break comparisons. */
    private static int leadingNumber(String part) {
        int end = 0;
        String p = part.trim();
        while (end < p.length() && Character.isDigit(p.charAt(end))) {
            end++;
        }
        if (end == 0) {
            throw new NumberFormatException("Invalid version part: " + part);
        }
        return Integer.parseInt(p.substring(0, end));
    }

    public static int compareVersionValues(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int v1 = i < parts1.length ? leadingNumber(parts1[i]) : 0;
            int v2 = i < parts2.length ? leadingNumber(parts2[i]) : 0;
            if (v1 != v2) {
                return Integer.compare(v1, v2);
            }
        }
        return 0;
    }
}
