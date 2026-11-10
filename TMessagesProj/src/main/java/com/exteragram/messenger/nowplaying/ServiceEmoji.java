package com.exteragram.messenger.nowplaying;

/**
 * Custom emoji used as the card pattern per platform (exteraGram document ids).
 * Note: these ids belong to the Telegram network; on gramsrv they may not resolve and the pattern stays empty.
 */
public enum ServiceEmoji {
    MUSIC(5271627010681108586L),
    SPOTIFY(5271857023359681001L),
    TELEGRAM(5325674462522144646L);

    private final long documentId;

    ServiceEmoji(long documentId) {
        this.documentId = documentId;
    }

    public long getDocumentId() {
        return documentId;
    }

    public static ServiceEmoji fromString(String name) {
        for (ServiceEmoji e : values()) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return MUSIC;
    }
}
