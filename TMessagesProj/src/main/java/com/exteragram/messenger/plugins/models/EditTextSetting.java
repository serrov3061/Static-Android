package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

public final class EditTextSetting extends SettingItem {
    private String defaultValue;
    private String hint;
    private String key;
    private String mask;
    private int maxLength;
    private boolean multiline;
    private PyObject onChangeCallback;

    public final String getKey() {
        return this.key;
    }

    public final void setKey(String str) {
        this.key = str;
    }

    public final String getHint() {
        return this.hint;
    }

    public final void setHint(String str) {
        this.hint = str;
    }

    public final String getDefaultValue() {
        return this.defaultValue;
    }

    public final void setDefaultValue(String str) {
        this.defaultValue = str;
    }

    public final boolean getMultiline() {
        return this.multiline;
    }

    public final void setMultiline(boolean z) {
        this.multiline = z;
    }

    public final int getMaxLength() {
        return this.maxLength;
    }

    public final void setMaxLength(int i) {
        this.maxLength = i;
    }

    public final String getMask() {
        return this.mask;
    }

    public final void setMask(String str) {
        this.mask = str;
    }

    public final PyObject getOnChangeCallback() {
        return this.onChangeCallback;
    }

    public final void setOnChangeCallback(PyObject pyObject) {
        this.onChangeCallback = pyObject;
    }

    public EditTextSetting(String str, String str2, String str3, boolean z, int i, String str4, PyObject pyObject) {
        super(PluginsConstants.Settings.TYPE_EDIT_TEXT, null, null, null);
        this.key = str;
        this.hint = str2;
        this.defaultValue = str3;
        this.multiline = z;
        this.maxLength = i;
        this.mask = str4;
        this.onChangeCallback = pyObject;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(this.onChangeCallback);
        this.onChangeCallback = null;
    }
}
