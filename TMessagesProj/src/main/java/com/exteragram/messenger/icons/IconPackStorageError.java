package com.exteragram.messenger.icons;

/** exteraGram IconPackStorageError: why reading, installing or saving an icon pack failed. */
public enum IconPackStorageError {
    INVALID_ARCHIVE,
    MISSING_METADATA,
    METADATA_TOO_LARGE,
    INVALID_METADATA,
    TOO_MANY_FILES,
    ARCHIVE_TOO_LARGE,
    FILE_TOO_LARGE,
    COMPRESSION_RATIO_TOO_HIGH,
    STORAGE_ERROR,
    UNKNOWN
}
