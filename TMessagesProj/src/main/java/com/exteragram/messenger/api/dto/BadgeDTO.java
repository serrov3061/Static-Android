package com.exteragram.messenger.api.dto;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * exteraGram 12.10.6 badge: a custom emoji document plus an optional caption.
 * Kept 1:1 (constructor, getters, setText) because Mandre modules use it by reflection.
 */
public final class BadgeDTO {

    @SerializedName("documentId")
    private final long documentId;

    @SerializedName("text")
    private String text;

    public BadgeDTO(long documentId, String text) {
        this.documentId = documentId;
        this.text = text;
    }

    public static BadgeDTO copy$default(BadgeDTO badgeDTO, long documentId, String text, int mask, Object unused) {
        if ((mask & 1) != 0) {
            documentId = badgeDTO.documentId;
        }
        if ((mask & 2) != 0) {
            text = badgeDTO.text;
        }
        return badgeDTO.copy(documentId, text);
    }

    public final long component1() {
        return documentId;
    }

    public final String component2() {
        return text;
    }

    public final BadgeDTO copy(long documentId, String text) {
        return new BadgeDTO(documentId, text);
    }

    public final long getDocumentId() {
        return documentId;
    }

    public final String getText() {
        return text;
    }

    public final void setText(String text) {
        this.text = text;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BadgeDTO)) {
            return false;
        }
        BadgeDTO badgeDTO = (BadgeDTO) other;
        return documentId == badgeDTO.documentId && Objects.equals(text, badgeDTO.text);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(documentId) * 31 + (text == null ? 0 : text.hashCode());
    }

    @Override
    public String toString() {
        return "BadgeDTO(documentId=" + documentId + ", text=" + text + ")";
    }
}
