package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

public final class DividerSetting extends SettingItem {
    private String text;

    public final String getText() {
        return this.text;
    }

    public final void setText(String str) {
        this.text = str;
    }

    public DividerSetting(String str) {
        super(PluginsConstants.Settings.TYPE_DIVIDER, null, null, null);
        this.text = str;
    }
}
