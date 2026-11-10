package com.exteragram.messenger.api.model;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/** Scrobbling service of the "Now playing" card (exteraGram). */
public enum NowPlayingServiceType {
    NONE(null),
    LAST_FM("Last.fm"),
    STATS_FM("Stats.fm");

    private final String displayName;

    NowPlayingServiceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : LocaleController.getString(R.string.None);
    }
}
