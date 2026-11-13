package com.exteragram.messenger.player;

import android.content.Context;
import com.exteragram.messenger.ExteraConfig;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;

public abstract class PlayerLauncher {
    public static BottomSheet create(Context context, Theme.ResourcesProvider resourcesProvider) {
        MessageObject playingMessageObject = MediaController.getInstance().getPlayingMessageObject();
        if (playingMessageObject == null || playingMessageObject.isMusic()) { // StaticGram cover-colored music player
            BottomSheet musicPlayer = org.telegram.ui.music.MusicPlayerAlert.createIfEnabled(context);
            if (musicPlayer != null) {
                return musicPlayer;
            }
        }
        if (ExteraConfig.getNewPlayerStyle() && (playingMessageObject == null || playingMessageObject.isMusic())) {
            return new MusicPlayerSheet(context, resourcesProvider);
        }
        return new AudioPlayerAlert(context, resourcesProvider);
    }

    public static boolean isShowing() {
        if (org.telegram.ui.music.MusicPlayerAlert.isShowingNow()) {
            return true;
        }
        MusicPlayerSheet musicPlayerSheet = MusicPlayerSheet.instance;
        if (musicPlayerSheet != null && musicPlayerSheet.isShowing()) {
            return true;
        }
        AudioPlayerAlert audioPlayerAlert = AudioPlayerAlert.instance;
        return audioPlayerAlert != null && audioPlayerAlert.isShowing();
    }

    public static void onPlaybackOrderChanged() {
        if (org.telegram.ui.music.MusicPlayerAlert.instance != null) {
            org.telegram.ui.music.MusicPlayerAlert.instance.updateRepeatButton();
        }
        MusicPlayerSheet musicPlayerSheet = MusicPlayerSheet.instance;
        if (musicPlayerSheet != null) {
            musicPlayerSheet.updateOrderButtons(true);
        }
    }
}
