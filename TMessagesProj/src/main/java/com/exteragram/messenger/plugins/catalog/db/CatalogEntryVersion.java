package com.exteragram.messenger.plugins.catalog.db;

/** exteraGram 13.1.1 catalog/db/CatalogEntryVersion: the previous sync state of an entry. */
public final class CatalogEntryVersion {

    private final int entryId;
    private final int date;
    private final long documentId;
    private final int updatedAt;

    public CatalogEntryVersion(int entryId, int date, long documentId, int updatedAt) {
        this.entryId = entryId;
        this.date = date;
        this.documentId = documentId;
        this.updatedAt = updatedAt;
    }

    public int getEntryId() {
        return entryId;
    }

    public int getDate() {
        return date;
    }

    public long getDocumentId() {
        return documentId;
    }

    public int getUpdatedAt() {
        return updatedAt;
    }
}
