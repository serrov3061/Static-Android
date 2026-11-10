package com.exteragram.messenger.plugins.catalog.db;

import java.util.Objects;

/**
 * Plugin details of one file (exteraGram 13.1.1 catalog/db/PluginMetadataEntity, Room entity "metadata").
 * For index repositories they come straight from the index; {@link #getError()} is non-null only for
 * entries whose details could not be read (exteraGram's channel repositories).
 */
public final class PluginMetadataEntity {

    private final long documentId;
    private final String pluginId;
    private final String name;
    private final String author;
    private final String description;
    private final String version;
    private final String icon;
    private final String appVersion;
    private final String sdkVersion;
    private final String requirements;
    private final String categories;
    private final String error;
    private final long parsedAt;
    private final String parsedWithSdk;

    public PluginMetadataEntity(long documentId, String pluginId, String name, String author, String description, String version, String icon, String appVersion, String sdkVersion, String requirements, String categories, String error, long parsedAt, String parsedWithSdk) {
        this.documentId = documentId;
        this.pluginId = pluginId;
        this.name = name;
        this.author = author;
        this.description = description;
        this.version = version;
        this.icon = icon;
        this.appVersion = appVersion;
        this.sdkVersion = sdkVersion;
        this.requirements = requirements;
        this.categories = categories;
        this.error = error;
        this.parsedAt = parsedAt;
        this.parsedWithSdk = parsedWithSdk;
    }

    public long getDocumentId() {
        return documentId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public String getIcon() {
        return icon;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getSdkVersion() {
        return sdkVersion;
    }

    public String getRequirements() {
        return requirements;
    }

    public String getCategories() {
        return categories;
    }

    public String getError() {
        return error;
    }

    public long getParsedAt() {
        return parsedAt;
    }

    public String getParsedWithSdk() {
        return parsedWithSdk;
    }

    // parsedAt is left out: re-reading the same index must not make every row "changed"
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginMetadataEntity)) return false;
        PluginMetadataEntity m = (PluginMetadataEntity) o;
        return documentId == m.documentId && Objects.equals(pluginId, m.pluginId) && Objects.equals(name, m.name)
                && Objects.equals(author, m.author) && Objects.equals(description, m.description)
                && Objects.equals(version, m.version) && Objects.equals(icon, m.icon)
                && Objects.equals(appVersion, m.appVersion) && Objects.equals(sdkVersion, m.sdkVersion)
                && Objects.equals(requirements, m.requirements) && Objects.equals(categories, m.categories)
                && Objects.equals(error, m.error) && Objects.equals(parsedWithSdk, m.parsedWithSdk);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, pluginId, name, author, description, version, icon, appVersion, sdkVersion, requirements, categories, error, parsedWithSdk);
    }
}
