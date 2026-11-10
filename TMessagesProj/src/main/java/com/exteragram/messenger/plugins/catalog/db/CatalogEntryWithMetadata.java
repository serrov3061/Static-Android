package com.exteragram.messenger.plugins.catalog.db;

/** exteraGram 13.1.1 catalog/db/CatalogEntryWithMetadata (Room relation entries -> metadata). */
public final class CatalogEntryWithMetadata {

    private final CatalogEntryEntity entry;
    private final PluginMetadataEntity metadata;

    public CatalogEntryWithMetadata(CatalogEntryEntity entry, PluginMetadataEntity metadata) {
        this.entry = entry;
        this.metadata = metadata;
    }

    public CatalogEntryEntity getEntry() {
        return entry;
    }

    public PluginMetadataEntity getMetadata() {
        return metadata;
    }
}
