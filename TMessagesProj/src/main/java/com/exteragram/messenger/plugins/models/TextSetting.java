package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

public final class TextSetting extends SettingItem {
    private boolean accent;
    private PyObject createSubFragmentCallback;
    private PyObject onClickCallback;
    private boolean red;
    private String subtext;
    private String text;

    public final String getText() {
        return this.text;
    }

    public final void setText(String str) {
        this.text = str;
    }

    public final String getSubtext() {
        return this.subtext;
    }

    public final void setSubtext(String str) {
        this.subtext = str;
    }

    public final boolean getAccent() {
        return this.accent;
    }

    public final void setAccent(boolean z) {
        this.accent = z;
    }

    public final boolean getRed() {
        return this.red;
    }

    public final void setRed(boolean z) {
        this.red = z;
    }

    public final PyObject getOnClickCallback() {
        return this.onClickCallback;
    }

    public final void setOnClickCallback(PyObject pyObject) {
        this.onClickCallback = pyObject;
    }

    public final PyObject getCreateSubFragmentCallback() {
        return this.createSubFragmentCallback;
    }

    public final void setCreateSubFragmentCallback(PyObject pyObject) {
        this.createSubFragmentCallback = pyObject;
    }

    public TextSetting(String str, String str2, String str3, boolean z, boolean z2, PyObject pyObject, PyObject pyObject2, PyObject pyObject3, String str4) {
        super("text", str3, pyObject3, str4);
        this.text = str;
        this.subtext = str2;
        this.accent = z;
        this.red = z2;
        this.onClickCallback = pyObject;
        this.createSubFragmentCallback = pyObject2;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(this.onClickCallback);
        closeCallback(this.createSubFragmentCallback);
        this.onClickCallback = null;
        this.createSubFragmentCallback = null;
    }
}
