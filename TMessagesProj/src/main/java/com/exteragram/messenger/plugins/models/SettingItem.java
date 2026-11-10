package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public abstract class SettingItem {
    private String icon;
    private String linkAlias;
    private PyObject onLongClickCallback;
    private String type;

    public SettingItem(String str, String str2, PyObject pyObject, String str3) {
        this.type = str;
        this.icon = str2;
        this.onLongClickCallback = pyObject;
        this.linkAlias = str3;
    }


    public final String getType() {
        return this.type;
    }

    public final void setType(String str) {
        this.type = str;
    }

    public final String getIcon() {
        return this.icon;
    }

    public final void setIcon(String str) {
        this.icon = str;
    }

    public final PyObject getOnLongClickCallback() {
        return this.onLongClickCallback;
    }

    public final void setOnLongClickCallback(PyObject pyObject) {
        this.onLongClickCallback = pyObject;
    }

    public final String getLinkAlias() {
        return this.linkAlias;
    }

    public final void setLinkAlias(String str) {
        this.linkAlias = str;
    }

    public final void closeCallback(PyObject callback) {
        if (callback != null) {
            try {
                callback.close();
            } catch (Exception unused) {
            }
        }
    }

    public void cleanup() {
        closeCallback(this.onLongClickCallback);
        this.onLongClickCallback = null;
    }

    public final String getLink(String pluginId, String prefix) {
        String str = this.linkAlias;
        if (str == null || str.length() == 0 || pluginId == null || pluginId.length() == 0) {
            return null;
        }
        String str2 = this.linkAlias;
        if (prefix != null) {
            str2 = prefix + ":" + str2;
        }
        return "https://t.me/exteraSettings?p=" + pluginId + "&s=" + str2;
    }
}
