package com.exteragram.messenger.plugins;

import java.util.ArrayList;
import java.util.List;
import kotlin.jvm.internal.Intrinsics;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class Plugin {

    public static final Companion Companion = new Companion();
    private transient PluginsController.PluginsEngine cachedEngine;
    private String engine;
    private volatile Throwable error;
    // sg: last create_settings() failure; shown on the card, does not disable the plugin
    private volatile Throwable settingsError;
    private final String id;
    private volatile boolean isEnabled;
    private volatile boolean isNotResponding;
    private final String name;
    private String pack;
    private String appVersion = ">=12.10.6";
    private String sdkVersion = ">=" + PythonPluginsEngine.Companion.getSDK_VERSION();
    private String version = "1.0";
    private String description = LocaleController.getString(R.string.PluginNoDescription);
    private String author = LocaleController.getString(R.string.PluginNoAuthor);
    private int index = -1;
    private List<String> requirements = new ArrayList();

    public Plugin(String str, String str2) {
        this.id = str;
        this.name = str2;
    }

    public final PluginsController.PluginsEngine getCachedEngine() {
        return this.cachedEngine;
    }

    public final void setCachedEngine(PluginsController.PluginsEngine pluginsEngine) {
        this.cachedEngine = pluginsEngine;
    }

    public final String getId() {
        return this.id;
    }

    public final String getDescription() {
        return this.description;
    }

    public final void setDescription(String description) {
        this.description = description;
    }

    public final String getEngine() {
        return this.engine;
    }

    public final void setEngine(String engine) {
        this.engine = engine;
    }

    public final String getAuthor() {
        return this.author;
    }

    public final void setAuthor(String author) {
        this.author = author;
    }

    public final String getName() {
        return this.name;
    }

    public final boolean isEnabled() {
        return !hasError() && this.isEnabled;
    }

    public final void setEnabled(boolean enabled) {
        this.isEnabled = enabled && !hasError();
    }

    public final Throwable getError() {
        return this.error;
    }

    public final void setError(Throwable error) {
        this.error = error;
        if (hasError()) {
            this.isEnabled = false;
        }
    }

    public final Throwable getSettingsError() {
        return this.settingsError;
    }

    public final void setSettingsError(Throwable settingsError) {
        this.settingsError = settingsError;
    }

    public final boolean hasError() {
        return this.error != null;
    }

    public final String getPack() {
        return this.pack;
    }

    public final int getIndex() {
        return this.index;
    }

    public final String getVersion() {
        return this.version;
    }

    public final void setVersion(String version) {
        this.version = version;
    }

    public final List<String> getRequirements() {
        return this.requirements;
    }

    public final String getAppVersion() {
        return this.appVersion;
    }

    public final void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public final String getSdkVersion() {
        return this.sdkVersion;
    }

    public final void setSdkVersion(String sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public final void setRequirements(List<String> requirements) {
        this.requirements = requirements;
    }
    public final boolean isNotResponding() {
        return this.isNotResponding;
    }

    public final void setNotResponding(boolean notResponding) {
        this.isNotResponding = notResponding;
    }

    public final String getIcon() {
        int i;
        String str = this.pack;
        if (str == null || (i = this.index) < 0) {
            return null;
        }
        return str + "/" + i;
    }

    public final void setIcon(String link) {
        int slash;
        if (Companion.isIconValid(link) && (slash = link.lastIndexOf('/')) != -1) {
            this.pack = link.substring(0, slash);
            this.index = Integer.parseInt(link.substring(slash + 1));
        }
    }

    public boolean equals(Object other) {
        if (other instanceof Plugin) {
            return Intrinsics.areEqual(((Plugin) other).id, this.id);
        }
        return this == other;
    }

    public int hashCode() {
        return this.id.hashCode();
    }

    public static final class Companion {
        private Companion() {
        }

        public final boolean isIconValid(String input) {
            return input != null && new Regex("^[a-zA-Z][a-zA-Z0-9_]*/\\d+$").matches(input);
        }
    }
}
