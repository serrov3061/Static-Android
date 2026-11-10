package com.exteragram.messenger.plugins.ui.components;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class RequiredPluginInfo {
    public static final int STATE_DISABLED = 1;
    public static final int STATE_MISSING = 0;
    public static final int STATE_OUTDATED = 2;
    public static final int STATE_SATISFIED = 3;

    public String currentVersion;

    public String downloadUrl;

    public final String id;

    public String name;

    public String requiredVersion;

    public int state;

    public RequiredPluginInfo(String str) {
        this.id = str;
    }

    public final boolean isSatisfied() {
        return this.state == 3;
    }

    public final CharSequence title() {
        String str = this.name;
        return str != null ? str : this.id;
    }

    public final CharSequence subtitle() {
        int i = this.state;
        if (i == 1) {
            return LocaleController.getString(R.string.PluginRequiredDisabled);
        }
        String str = "";
        if (i == 2) {
            int i2 = R.string.PluginRequiredOutdated;
            String str2 = this.currentVersion;
            if (str2 == null) {
                str2 = "";
            }
            String str3 = this.requiredVersion;
            if (str3 != null) {
                str = str3;
            }
            return LocaleController.formatString(i2, str2, str);
        }
        if (i == 3) {
            int i3 = R.string.PluginVersionValue;
            String str4 = this.currentVersion;
            if (str4 != null) {
                str = str4;
            }
            return LocaleController.formatString(i3, str);
        }
        String str5 = this.requiredVersion;
        if (str5 == null || str5.length() == 0) {
            return LocaleController.getString(R.string.PluginRequiredMissing);
        }
        return LocaleController.formatString(R.string.PluginRequiredVersion, this.requiredVersion);
    }

    public final CharSequence actionText() {
        int i = this.state;
        if (i == 1) {
            return LocaleController.getString(R.string.Enable);
        }
        if (i == 2) {
            return LocaleController.getString(R.string.PluginRequiredUpdate);
        }
        if (i == 3) {
            return LocaleController.getString(R.string.PluginRequiredInstalled);
        }
        return LocaleController.getString(R.string.BotDownloadFileDownload);
    }

    public final String contentKey() {
        return this.state + "|" + this.name + "|" + this.currentVersion + "|" + this.requiredVersion;
    }
}
