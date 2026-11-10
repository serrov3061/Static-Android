package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

public final class HeaderSetting extends SettingItem {
    private String text;

    public final String getText() {
        return this.text;
    }

    public final void setText(String str) {
        this.text = str;
    }

    public HeaderSetting(String str) {
        super(PluginsConstants.Settings.TYPE_HEADER, null, null, null);
        this.text = str;
    }
}
