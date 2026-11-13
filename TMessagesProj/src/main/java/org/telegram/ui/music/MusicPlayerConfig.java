package org.telegram.ui.music;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

/**
 * Settings of the cover-colored music player: which player opens music, cover colors in the
 * full player and in the mini player, and the online lyrics lookup.
 */
public final class MusicPlayerConfig {

    private static final String KEY_PLAYER = "sgMusicPlayer";
    private static final String KEY_COVER_FULL = "sgMusicCoverColorsFull";
    private static final String KEY_COVER_MINI = "sgMusicCoverColorsMini";
    private static final String KEY_GIFT_BUBBLES = "sgProfileGiftBubbles";
    private static final String KEY_ONLINE_LYRICS = "sgMusicOnlineLyrics";

    public static boolean musicPlayer = true;
    public static boolean musicCoverColorsFull = true;
    public static boolean musicCoverColorsMini = true;
    public static boolean musicOnlineLyrics = true;
    public static boolean profileGiftBubbles = true;

    private static boolean loaded;

    private MusicPlayerConfig() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            musicPlayer = preferences.getBoolean(KEY_PLAYER, true);
            musicCoverColorsFull = preferences.getBoolean(KEY_COVER_FULL, true);
            musicCoverColorsMini = preferences.getBoolean(KEY_COVER_MINI, true);
            musicOnlineLyrics = preferences.getBoolean(KEY_ONLINE_LYRICS, true);
            profileGiftBubbles = preferences.getBoolean(KEY_GIFT_BUBBLES, true);
        } catch (Throwable ignore) {
        }
    }

    private static void put(String key, boolean value) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(key, value).apply();
    }

    public static boolean isPlayerEnabled() {
        load();
        return musicPlayer;
    }

    public static boolean isCoverColorsFull() {
        load();
        return musicCoverColorsFull;
    }

    public static boolean isCoverColorsMini() {
        load();
        return musicCoverColorsMini;
    }

    public static boolean isOnlineLyrics() {
        load();
        return musicOnlineLyrics;
    }

    /** Profile header: gifts orbit the avatar in glass bubbles (on) or sit around it the standard way (off). */
    public static boolean isProfileGiftBubbles() {
        load();
        return profileGiftBubbles;
    }

    public static void setProfileGiftBubbles(boolean value) {
        load();
        profileGiftBubbles = value;
        put(KEY_GIFT_BUBBLES, value);
    }

    public static void setPlayerEnabled(boolean value) {
        load();
        musicPlayer = value;
        put(KEY_PLAYER, value);
    }

    public static boolean toggleCoverColorsFull() {
        load();
        musicCoverColorsFull = !musicCoverColorsFull;
        put(KEY_COVER_FULL, musicCoverColorsFull);
        return musicCoverColorsFull;
    }

    public static boolean toggleCoverColorsMini() {
        load();
        musicCoverColorsMini = !musicCoverColorsMini;
        put(KEY_COVER_MINI, musicCoverColorsMini);
        return musicCoverColorsMini;
    }

    public static boolean toggleOnlineLyrics() {
        load();
        musicOnlineLyrics = !musicOnlineLyrics;
        put(KEY_ONLINE_LYRICS, musicOnlineLyrics);
        return musicOnlineLyrics;
    }

    /** Toggles «Telegram's player»; returns whether Telegram's player is on now. */
    public static boolean toggleClassicPlayer() {
        load();
        setPlayerEnabled(!musicPlayer);
        return !musicPlayer;
    }
}
