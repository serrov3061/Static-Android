package com.exteragram.messenger.plugins.catalog.db;

import java.util.Objects;

/**
 * A plugin repository (exteraGram 13.1.1 catalog/db/PluginRepositoryEntity, Room entity "repositories").
 * StaticGram keeps only index (Git / HTTPS JSON) repositories: their ids are negative, like exteraGram's
 * Git repositories. {@link #SOURCE_FOLDER} is reused for the built-in StaticGram catalog (it cannot be
 * removed), {@link #SOURCE_USER} for repositories the user added.
 */
public final class PluginRepositoryEntity {

    public static final int SOURCE_FOLDER = 0;
    public static final int SOURCE_USER = 1;

    private final long repositoryId;
    private final String username;
    private final String title;
    private final int source;
    private final int sortOrder;
    private final long addedAt;
    private final long syncedAt;
    private final int pluginsCount;
    private final String url;
    private final String homepage;

    public PluginRepositoryEntity(long repositoryId, String username, String title, int source, int sortOrder, long addedAt, long syncedAt, int pluginsCount, String url, String homepage) {
        this.repositoryId = repositoryId;
        this.username = username;
        this.title = title;
        this.source = source;
        this.sortOrder = sortOrder;
        this.addedAt = addedAt;
        this.syncedAt = syncedAt;
        this.pluginsCount = pluginsCount;
        this.url = url;
        this.homepage = homepage;
    }

    public static boolean isGitId(long repositoryId) {
        return repositoryId < 0;
    }

    public long getRepositoryId() {
        return repositoryId;
    }

    public String getUsername() {
        return username;
    }

    public String getTitle() {
        return title;
    }

    public int getSource() {
        return source;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public long getAddedAt() {
        return addedAt;
    }

    public long getSyncedAt() {
        return syncedAt;
    }

    public int getPluginsCount() {
        return pluginsCount;
    }

    public String getUrl() {
        return url;
    }

    public String getHomepage() {
        return homepage;
    }

    public boolean isGit() {
        return url != null;
    }

    public PluginRepositoryEntity withUsername(String username) {
        return new PluginRepositoryEntity(repositoryId, username, title, source, sortOrder, addedAt, syncedAt, pluginsCount, url, homepage);
    }

    public PluginRepositoryEntity copy(String title, String url, String homepage, long syncedAt, int pluginsCount) {
        return new PluginRepositoryEntity(repositoryId, username, title, source, sortOrder, addedAt, syncedAt, pluginsCount, url, homepage);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginRepositoryEntity)) return false;
        PluginRepositoryEntity e = (PluginRepositoryEntity) o;
        return repositoryId == e.repositoryId && source == e.source && sortOrder == e.sortOrder && addedAt == e.addedAt
                && syncedAt == e.syncedAt && pluginsCount == e.pluginsCount && Objects.equals(username, e.username)
                && Objects.equals(title, e.title) && Objects.equals(url, e.url) && Objects.equals(homepage, e.homepage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryId, username, title, source, sortOrder, addedAt, syncedAt, pluginsCount, url, homepage);
    }
}
