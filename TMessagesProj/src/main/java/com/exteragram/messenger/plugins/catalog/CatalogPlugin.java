package com.exteragram.messenger.plugins.catalog;

import com.exteragram.messenger.plugins.catalog.db.CatalogEntryEntity;
import com.exteragram.messenger.plugins.catalog.db.CatalogEntryWithMetadata;
import com.exteragram.messenger.plugins.catalog.db.PluginMetadataEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** A plugin shown in the catalog (exteraGram 13.1.1 catalog/CatalogPlugin). */
public final class CatalogPlugin {

    private final long repositoryId;
    private final int entryId;
    private final int date;
    private final int updatedAt;
    private final long documentId;
    private final String fileName;
    private final long size;
    private final String caption;
    private final PluginMetadataEntity metadata;
    private final int views;
    private final int publishedAt;

    public CatalogPlugin(long repositoryId, int entryId, int date, int updatedAt, long documentId, String fileName, long size, String caption, PluginMetadataEntity metadata, int views, int publishedAt) {
        this.repositoryId = repositoryId;
        this.entryId = entryId;
        this.date = date;
        this.updatedAt = updatedAt;
        this.documentId = documentId;
        this.fileName = fileName;
        this.size = size;
        this.caption = caption;
        this.metadata = metadata;
        this.views = views;
        this.publishedAt = publishedAt;
    }

    public long getRepositoryId() {
        return repositoryId;
    }

    public int getEntryId() {
        return entryId;
    }

    public int getDate() {
        return date;
    }

    public int getUpdatedAt() {
        return updatedAt;
    }

    public long getDocumentId() {
        return documentId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSize() {
        return size;
    }

    public String getCaption() {
        return caption;
    }

    public PluginMetadataEntity getMetadata() {
        return metadata;
    }

    public int getViews() {
        return views;
    }

    public int getPublishedAt() {
        return publishedAt;
    }

    private PluginMetadataEntity validMetadata() {
        return metadata != null && metadata.getError() == null ? metadata : null;
    }

    public String getPluginId() {
        PluginMetadataEntity m = validMetadata();
        return m != null ? m.getPluginId() : null;
    }

    public boolean isPending() {
        return metadata == null;
    }

    public boolean isFailed() {
        return metadata != null && metadata.getError() != null;
    }

    public List<PluginCategory> getCategories() {
        PluginMetadataEntity m = validMetadata();
        return PluginCategory.parse(m != null ? m.getCategories() : null);
    }

    public static CatalogPlugin from(CatalogEntryWithMetadata row) {
        CatalogEntryEntity e = row.getEntry();
        return new CatalogPlugin(e.getRepositoryId(), e.getEntryId(), e.getDate(), e.getUpdatedAt(), e.getDocumentId(), e.getFileName(), e.getSize(), e.getCaption(), row.getMetadata(), e.getViews(), e.getDate());
    }

    /**
     * One row per plugin: the newest version (by updatedAt, then entryId) wins, with the highest view count
     * and the earliest publication date of all its versions. Files without a plugin id are grouped by file name.
     */
    public static List<CatalogPlugin> latestVersions(List<CatalogPlugin> plugins) {
        ArrayList<CatalogPlugin> sorted = new ArrayList<>(plugins);
        Collections.sort(sorted, (a, b) -> {
            int c = Integer.compare(b.getUpdatedAt(), a.getUpdatedAt());
            return c != 0 ? c : Integer.compare(b.getEntryId(), a.getEntryId());
        });
        HashSet<String> namesWithId = new HashSet<>();
        for (CatalogPlugin p : sorted) {
            if (p.getPluginId() != null) {
                namesWithId.add(p.getFileName().toLowerCase(Locale.ROOT));
            }
        }
        LinkedHashMap<String, ArrayList<CatalogPlugin>> groups = new LinkedHashMap<>();
        for (CatalogPlugin p : sorted) {
            String key = p.getPluginId();
            if (key == null) {
                String name = p.getFileName().toLowerCase(Locale.ROOT);
                key = namesWithId.contains(name) ? null : "file:" + name;
            }
            ArrayList<CatalogPlugin> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(p);
        }
        ArrayList<CatalogPlugin> result = new ArrayList<>(groups.size());
        for (ArrayList<CatalogPlugin> group : groups.values()) {
            CatalogPlugin first = group.get(0);
            int views = 0;
            int published = Integer.MAX_VALUE;
            for (CatalogPlugin p : group) {
                views = Math.max(views, p.getViews());
                published = Math.min(published, p.getDate());
            }
            result.add(new CatalogPlugin(first.repositoryId, first.entryId, first.date, first.updatedAt, first.documentId, first.fileName, first.size, first.caption, first.metadata, views, published));
        }
        return result;
    }
}
