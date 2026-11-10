package com.exteragram.messenger.plugins.hooks;

import android.content.Context;
import android.text.TextUtils;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.utils.PyObjectUtils;
import java.io.Serializable;
import java.util.Map;
import java.util.UUID;
import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.LazyThreadSafetyMode;
import kotlin.jvm.internal.Intrinsics;
import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public final class MenuItemRecord {

    private final Lazy<Serializable> compiledCondition;
    private final String conditionString;
    private final String iconName;
    private final int iconResId;
    private final String itemId;
    private final String menuType;
    private final PyObject onClickCallback;
    private final String pluginId;
    private final int priority;
    private volatile boolean removed;
    private final String subtext;
    private final String text;

    public MenuItemRecord(String str, PyObject pyObject) {
        this.pluginId = str;
        this.menuType = PyObjectUtils.getString(pyObject, PluginsConstants.MenuItemProperties.MENU_TYPE, null, true);
        this.text = PyObjectUtils.getString(pyObject, "text", null, true);
        this.onClickCallback = pyObject.callAttr("get", "on_click");
        String string = PyObjectUtils.getString(pyObject, PluginsConstants.MenuItemProperties.ITEM_ID, null, true);
        string = (string == null || string.length() == 0) ? null : string;
        this.itemId = string == null ? UUID.randomUUID().toString() : string;
        String string2 = PyObjectUtils.getString(pyObject, "icon", null, true);
        this.iconName = string2;
        this.subtext = PyObjectUtils.getString(pyObject, "subtext", null, true);
        this.conditionString = PyObjectUtils.getString(pyObject, "condition", null, true);
        int identifier = 0;
        this.priority = PyObjectUtils.getInt(pyObject, PluginsConstants.MenuItemProperties.PRIORITY, 0, true);
        if (!TextUtils.isEmpty(string2)) {
            try {
                Context context = ApplicationLoader.applicationContext;
                identifier = context.getResources().getIdentifier(string2, "drawable", context.getPackageName());
            } catch (Exception unused) {
            }
        }
        this.iconResId = identifier;
        if (TextUtils.isEmpty(this.menuType) || TextUtils.isEmpty(this.text) || this.onClickCallback == null) {
            throw new IllegalArgumentException("MenuItemRecord missing essential fields: menuType, text, or onClickCallback.");
        }
        this.compiledCondition = LazyKt.lazy(LazyThreadSafetyMode.PUBLICATION, () -> compileCondition(this));
    }

    public final String getPluginId() {
        return this.pluginId;
    }

    public final String getItemId() {
        return this.itemId;
    }

    public final String getMenuType() {
        return this.menuType;
    }

    public final String getText() {
        return this.text;
    }

    public final PyObject getOnClickCallback() {
        return this.onClickCallback;
    }

    public final String getIconName() {
        return this.iconName;
    }

    public final int getIconResId() {
        return this.iconResId;
    }

    public final String getSubtext() {
        return this.subtext;
    }

    public final String getConditionString() {
        return this.conditionString;
    }

    public final int getPriority() {
        return this.priority;
    }

    public final void markRemoved() {
        this.removed = true;
    }

    public final void executeClick(Object contextData) {
        if (this.removed || !PluginsController.Companion.getInstance().isPluginActive(this.pluginId)) {
            return;
        }
        try {
            PyObject pyObject = this.onClickCallback;
            if (pyObject != null) {
                pyObject.call(contextData);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other != null && Intrinsics.areEqual(MenuItemRecord.class, other.getClass())) {
            MenuItemRecord menuItemRecord = (MenuItemRecord) other;
            if (Intrinsics.areEqual(this.itemId, menuItemRecord.itemId) && Intrinsics.areEqual(this.pluginId, menuItemRecord.pluginId)) {
                return true;
            }
        }
        return false;
    }

    public int hashCode() {
        return (this.itemId.hashCode() * 31) + this.pluginId.hashCode();
    }

    private final Serializable getCompiledCondition() {
        return (Serializable) this.compiledCondition.getValue();
    }

    private static Serializable compileCondition(MenuItemRecord menuItemRecord) {
        String str = menuItemRecord.conditionString;
        if (str == null || str.length() == 0) {
            return null;
        }
        try {
            return MVEL.compileExpression(str);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public final boolean checkCondition(Map<String, ? extends Object> contextData) {
        String str = this.conditionString;
        if (str == null || str.length() == 0 || contextData == null) {
            return true;
        }
        Serializable compiledCondition = getCompiledCondition();
        if (compiledCondition == null) {
            return false;
        }
        try {
            Boolean bool = MVEL.executeExpression((Object) compiledCondition, (Map) contextData, Boolean.class);
            if (bool != null) {
                return bool.booleanValue();
            }
        } catch (Exception unused) {
        }
        return false;
    }
}
