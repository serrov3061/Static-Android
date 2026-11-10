package com.exteragram.messenger.plugins.catalog.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * exteraGram 13.1.1 catalog/db/PluginCatalogDao with the same queries, served from
 * {@link PluginCatalogDatabase} (small JSON files) instead of Room. All methods are synchronized;
 * PluginCatalogController calls them from its catalog queue only.
 */
public final class PluginCatalogDao {

    private final PluginCatalogDatabase db;

    PluginCatalogDao(PluginCatalogDatabase db) {
        this.db = db;
    }

    // region repositories

    /** SELECT * FROM repositories ORDER BY source, sortOrder, addedAt */
    public synchronized List<PluginRepositoryEntity> getRepositories() {
        ArrayList<PluginRepositoryEntity> list = new ArrayList<>(db.repositories().values());
        Collections.sort(list, (a, b) -> {
            int c = Integer.compare(a.getSource(), b.getSource());
            if (c != 0) return c;
            c = Integer.compare(a.getSortOrder(), b.getSortOrder());
            if (c != 0) return c;
            return Long.compare(a.getAddedAt(), b.getAddedAt());
        });
        return list;
    }

    public synchronized PluginRepositoryEntity getRepository(long repositoryId) {
        return db.repositories().get(repositoryId);
    }

    public synchronized PluginRepositoryEntity getRepositoryByUrl(String url) {
        if (url == null) {
            return null;
        }
        for (PluginRepositoryEntity repository : db.repositories().values()) {
            if (url.equals(repository.getUrl())) {
                return repository;
            }
        }
        return null;
    }

    public synchronized Long getMinRepositoryId() {
        Long min = null;
        for (Long id : db.repositories().keySet()) {
            if (min == null || id < min) {
                min = id;
            }
        }
        return min;
    }

    public synchronized void upsertRepositories(List<PluginRepositoryEntity> repositories) {
        for (PluginRepositoryEntity repository : repositories) {
            db.repositories().put(repository.getRepositoryId(), repository);
        }
        db.saveRepositories();
    }

    public synchronized void updatePluginsCount(long repositoryId, int pluginsCount) {
        PluginRepositoryEntity r = db.repositories().get(repositoryId);
        if (r != null && r.getPluginsCount() != pluginsCount) {
            db.repositories().put(repositoryId, r.copy(r.getTitle(), r.getUrl(), r.getHomepage(), r.getSyncedAt(), pluginsCount));
            db.saveRepositories();
        }
    }

    public synchronized void updateSyncState(long repositoryId, long syncedAt, int pluginsCount) {
        PluginRepositoryEntity r = db.repositories().get(repositoryId);
        if (r != null) {
            db.repositories().put(repositoryId, r.copy(r.getTitle(), r.getUrl(), r.getHomepage(), syncedAt, pluginsCount));
            db.saveRepositories();
        }
    }

    public synchronized void updateGitInfo(long repositoryId, String title, String homepage, String channel) {
        PluginRepositoryEntity r = db.repositories().get(repositoryId);
        if (r != null) {
            PluginRepositoryEntity updated = r.copy(title, r.getUrl(), homepage, r.getSyncedAt(), r.getPluginsCount());
            // the built-in catalog takes its channel from sg_plugins_catalog_channel, not from the index
            if (channel != null && !com.exteragram.messenger.plugins.catalog.PluginCatalogController.isTrustedRepository(repositoryId)) {
                updated = updated.withUsername(channel);
            }
            db.repositories().put(repositoryId, updated);
            db.saveRepositories();
        }
    }

    /** removeRepository = deleteRepository + deleteEntries + pruneMetadata */
    public synchronized void removeRepository(long repositoryId) {
        if (db.repositories().remove(repositoryId) != null) {
            db.saveRepositories();
        }
        db.deleteEntries(repositoryId);
    }

    // endregion

    // region entries

    /** entries LEFT JOIN metadata ORDER BY date DESC, entryId DESC */
    public synchronized List<CatalogEntryWithMetadata> getEntries(long repositoryId) {
        PluginCatalogDatabase.Entries entries = db.entries(repositoryId);
        ArrayList<CatalogEntryWithMetadata> list = new ArrayList<>(entries.entries.size());
        for (CatalogEntryEntity entry : entries.entries) {
            list.add(new CatalogEntryWithMetadata(entry, entries.metadata.get(entry.getDocumentId())));
        }
        Collections.sort(list, (a, b) -> {
            int c = Integer.compare(b.getEntry().getDate(), a.getEntry().getDate());
            return c != 0 ? c : Integer.compare(b.getEntry().getEntryId(), a.getEntry().getEntryId());
        });
        return list;
    }

    public synchronized CatalogEntryEntity getEntry(long repositoryId, int entryId) {
        for (CatalogEntryEntity entry : db.entries(repositoryId).entries) {
            if (entry.getEntryId() == entryId) {
                return entry;
            }
        }
        return null;
    }

    public synchronized PluginMetadataEntity getMetadata(long repositoryId, long documentId) {
        return db.entries(repositoryId).metadata.get(documentId);
    }

    /** SELECT entryId, date, documentId, updatedAt FROM entries WHERE repositoryId = ? */
    public synchronized List<CatalogEntryVersion> getEntryVersions(long repositoryId) {
        ArrayList<CatalogEntryVersion> list = new ArrayList<>();
        for (CatalogEntryEntity entry : db.entries(repositoryId).entries) {
            list.add(new CatalogEntryVersion(entry.getEntryId(), entry.getDate(), entry.getDocumentId(), entry.getUpdatedAt()));
        }
        return list;
    }

    /** replaceEntries = deleteEntries + insertEntries + upsertMetadata + pruneMetadata */
    public synchronized void replaceEntries(long repositoryId, List<CatalogEntryEntity> entries, List<PluginMetadataEntity> metadata) {
        PluginCatalogDatabase.Entries data = new PluginCatalogDatabase.Entries();
        HashMap<Integer, CatalogEntryEntity> byId = new HashMap<>();
        for (CatalogEntryEntity entry : entries) {
            byId.put(entry.getEntryId(), entry); // INSERT OR REPLACE on (repositoryId, entryId)
        }
        data.entries.addAll(byId.values());
        HashMap<Long, Boolean> documents = new HashMap<>();
        for (CatalogEntryEntity entry : data.entries) {
            documents.put(entry.getDocumentId(), Boolean.TRUE);
        }
        for (PluginMetadataEntity m : metadata) {
            if (documents.containsKey(m.getDocumentId())) {
                data.metadata.put(m.getDocumentId(), m);
            }
        }
        db.putEntries(repositoryId, data);
    }

    // endregion
}
