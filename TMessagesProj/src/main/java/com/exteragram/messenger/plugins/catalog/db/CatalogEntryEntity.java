package com.exteragram.messenger.plugins.catalog.db;

/**
 * One file of a repository (exteraGram 13.1.1 catalog/db/CatalogEntryEntity, Room entity "entries").
 * exteraGram's channel repositories also kept the Telegram message (caption, serialized message);
 * StaticGram has index repositories only, so every entry has {@link #getFileUrl()} and {@link #getSha256()}.
 */
public final class CatalogEntryEntity {

    private final long repositoryId;
    private final int entryId;
    private int date;
    private final long documentId;
    private final String fileName;
    private final long size;
    private final String caption;
    private final int views;
    private int updatedAt;
    private final String fileUrl;
    private final String sha256;

    public CatalogEntryEntity(long repositoryId, int entryId, int date, long documentId, String fileName, long size, String caption, int views, int updatedAt, String fileUrl, String sha256) {
        this.repositoryId = repositoryId;
        this.entryId = entryId;
        this.date = date;
        this.documentId = documentId;
        this.fileName = fileName;
        this.size = size;
        this.caption = caption;
        this.views = views;
        this.updatedAt = updatedAt;
        this.fileUrl = fileUrl;
        this.sha256 = sha256;
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

    public void setDate(int date) {
        this.date = date;
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

    public int getViews() {
        return views;
    }

    public int getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(int updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getSha256() {
        return sha256;
    }
}
