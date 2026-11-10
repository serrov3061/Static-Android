package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

public final class InputSetting extends SettingItem {
    private String defaultValue;
    private String key;
    private PyObject onChangeCallback;
    private String subtext;
    private String text;

    public final String getKey() {
        return this.key;
    }

    public final void setKey(String str) {
        this.key = str;
    }

    public final String getText() {
        return this.text;
    }

    public final void setText(String str) {
        this.text = str;
    }

    public final String getDefaultValue() {
        return this.defaultValue;
    }

    public final void setDefaultValue(String str) {
        this.defaultValue = str;
    }

    public final String getSubtext() {
        return this.subtext;
    }

    public final void setSubtext(String str) {
        this.subtext = str;
    }

    public final PyObject getOnChangeCallback() {
        return this.onChangeCallback;
    }

    public final void setOnChangeCallback(PyObject pyObject) {
        this.onChangeCallback = pyObject;
    }

    public InputSetting(String str, String str2, String str3, String str4, String str5, PyObject pyObject, PyObject pyObject2, String str6) {
        super(PluginsConstants.Settings.TYPE_INPUT, str5, pyObject2, str6);
        this.key = str;
        this.text = str2;
        this.defaultValue = str3;
        this.subtext = str4;
        this.onChangeCallback = pyObject;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(this.onChangeCallback);
        this.onChangeCallback = null;
    }
}
