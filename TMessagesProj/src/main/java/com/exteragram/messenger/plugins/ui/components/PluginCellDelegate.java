package com.exteragram.messenger.plugins.ui.components;

import android.view.View;
import com.exteragram.messenger.plugins.PluginsConstants;

public interface PluginCellDelegate {
    boolean canOpenInExternalApp();

    void deletePlugin();

    void openInExternalApp();

    void openPluginSettings();

    void pinPlugin(View view);

    void sharePlugin();

    void togglePlugin(View view);
}
