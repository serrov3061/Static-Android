package com.exteragram.messenger.utils.ui;

import org.telegram.messenger.AndroidUtilities;

/**
 * exteraGram 13.1.1: accumulates list scroll to decide when to hide (16dp down) or show (48dp up) the
 * floating button / floating bottom tabs. Reaching the end of the list always shows them.
 */
public final class HideOnScrollTracker {
    private int distance;

    /** @return TRUE to hide, FALSE to show, null to keep the current state */
    public Boolean onScrolled(int dy, boolean atEnd) {
        if (dy == 0) {
            return null;
        }
        if (atEnd) {
            distance = 0;
            return Boolean.FALSE;
        }
        if (distance != 0 && (dy > 0) != (distance > 0)) {
            distance = 0;
        }
        distance += dy;
        if (distance >= AndroidUtilities.dp(16.0f)) {
            return Boolean.TRUE;
        }
        if (distance <= -AndroidUtilities.dp(48.0f)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public void reset() {
        distance = 0;
    }
}
