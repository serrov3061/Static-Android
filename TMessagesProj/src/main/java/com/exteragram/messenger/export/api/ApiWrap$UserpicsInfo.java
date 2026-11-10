package com.exteragram.messenger.export.api;


public final class ApiWrap$UserpicsInfo {
    private final int count;

    private /* synthetic */ boolean $record$equals(Object obj) {
        return (obj instanceof ApiWrap$UserpicsInfo) && this.count == ((ApiWrap$UserpicsInfo) obj).count;
    }

    private /* synthetic */ Object[] $record$getFieldsAsObjects() {
        return new Object[]{Integer.valueOf(this.count)};
    }

    public ApiWrap$UserpicsInfo(int i) {
        this.count = i;
    }

    public int count() {
        return this.count;
    }

    public final boolean equals(Object obj) {
        return $record$equals(obj);
    }

    public final int hashCode() {
        return ApiWrap$UserpicsInfo$$ExternalSyntheticRecord0.m(this.count);
    }

    public final String toString() {
        return com.exteragram.messenger.export.ExportCompat.recordToString($record$getFieldsAsObjects(), ApiWrap$UserpicsInfo.class, "count");
    }
}
