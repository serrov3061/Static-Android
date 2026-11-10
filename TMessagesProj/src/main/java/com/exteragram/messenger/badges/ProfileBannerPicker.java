package com.exteragram.messenger.badges;

import android.os.Build;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ChatAttachAlert;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * StaticGram profile banner picker: the same in-app gallery sheet as the avatar picker
 * (ChatAttachAlert with photos and videos, camera tile), opened with {@link ChatAttachAlert#sgBannerPicker}
 * so the photo editor crops freely instead of the square avatar crop. A photo goes to
 * {@link ProfileBannerUpload#startFile} as edited; a video is trimmed to 15 s, muted and re-encoded to
 * an MP4 (MediaController, like an avatar video) before the upload.
 */
public final class ProfileBannerPicker {

    /** Called on the UI thread when a file starts processing (the caller shows "uploading"). */
    public interface Started {
        void run();
    }

    private static final long MAX_VIDEO_US = ProfileBanners.MAX_VIDEO_SECONDS * 1_000_000L;
    private static final int MAX_VIDEO_SIDE = 1280;
    private static final int VIDEO_BITRATE = 3_000_000;

    private ProfileBannerPicker() {
    }

    public static void open(BaseFragment fragment, Started started, ProfileBannerUpload.Done done) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        ChatAttachAlert alert = new ChatAttachAlert(fragment.getParentActivity(), fragment, false, false);
        alert.sgBannerPicker = true;
        alert.setAvatarPicker(2, false, null);
        alert.setDelegate(new ChatAttachAlert.ChatAttachViewDelegate() {
            @Override
            public void didPressedButton(int button, boolean arg, boolean notify, int scheduleDate, int scheduleRepeatPeriod, long effectId, boolean invertMedia, boolean forceDocument, long payStars) {
                if (button != 7 && button != 8) {
                    alert.dismissWithButtonClick(button);
                    return;
                }
                HashMap<Object, Object> photos = alert.getPhotoLayout().getSelectedPhotos();
                ArrayList<Object> order = alert.getPhotoLayout().getSelectedPhotosOrder();
                Object picked = order.isEmpty() ? null : photos.get(order.get(0));
                if (button != 8) {
                    alert.dismiss(true);
                }
                if (!(picked instanceof MediaController.PhotoEntry)) {
                    return;
                }
                MediaController.PhotoEntry entry = (MediaController.PhotoEntry) picked;
                started.run();
                if (entry.isVideo || entry.editedInfo != null && !entry.editedInfo.isPhoto) {
                    convertVideo(account, entry, done);
                } else {
                    ProfileBannerUpload.startFile(account, entry.imagePath != null ? entry.imagePath : entry.path, false, done);
                }
            }

            @Override
            public View getRevealView() {
                return null;
            }

            @Override
            public void didSelectBot(TLRPC.User user) {
            }

            @Override
            public void onCameraOpened() {
                if (fragment.getFragmentView() != null) {
                    AndroidUtilities.hideKeyboard(fragment.getFragmentView().findFocus());
                }
            }

            @Override
            public boolean needEnterComment() {
                return false;
            }

            @Override
            public void doOnIdle(Runnable runnable) {
                runnable.run();
            }
        });
        alert.setMaxSelectedPhotos(1, false);
        alert.getPhotoLayout().loadGalleryPhotos();
        if ((Build.VERSION.SDK_INT == 21 || Build.VERSION.SDK_INT == 22) && fragment.getFragmentView() != null) {
            AndroidUtilities.hideKeyboard(fragment.getFragmentView().findFocus());
        }
        alert.init();
        alert.getSelectedTextView().setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.SgBannerChoose));
        fragment.showDialog(alert);
    }

    /** Trims to 15 s, mutes, bounds the size and re-encodes the video, then uploads the MP4. */
    private static void convertVideo(int account, MediaController.PhotoEntry entry, ProfileBannerUpload.Done done) {
        VideoEditedInfo info = entry.editedInfo;
        if (info == null) {
            // not opened in the editor: a plain MP4 within the limits goes as is
            ProfileBannerUpload.startFile(account, entry.path, true, done);
            return;
        }
        long start = Math.max(0, info.startTime);
        long end = info.endTime > 0 ? info.endTime : info.originalDuration;
        if (end <= start) {
            end = start + MAX_VIDEO_US;
        }
        if (end - start > MAX_VIDEO_US) {
            end = start + MAX_VIDEO_US;
        }
        info.startTime = start;
        info.endTime = end;
        info.estimatedDuration = (end - start) / 1000;
        info.muted = true;
        int w = info.resultWidth, h = info.resultHeight;
        if (w > 0 && h > 0 && Math.max(w, h) > MAX_VIDEO_SIDE) {
            float scale = MAX_VIDEO_SIDE / (float) Math.max(w, h);
            info.resultWidth = Math.max(16, Math.round(w * scale / 16f) * 16);
            info.resultHeight = Math.max(16, Math.round(h * scale / 16f) * 16);
        }
        info.bitrate = info.originalBitrate > 0 ? Math.min(info.originalBitrate, VIDEO_BITRATE) : VIDEO_BITRATE;
        info.estimatedSize = (long) (info.bitrate / 8.0 * (end - start) / 1_000_000.0);

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = 0;
        message.message = "";
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.action = new TLRPC.TL_messageActionEmpty();
        message.dialog_id = 0;
        MessageObject object = new MessageObject(UserConfig.selectedAccount, message, false, false);
        object.messageOwner.attachPath = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), SharedConfig.getLastLocalId() + "_sgbanner.mp4").getAbsolutePath();
        object.videoEditedInfo = info;

        NotificationCenter nc = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        holder[0] = (id, acc, args) -> {
            if (args.length == 0 || args[0] != object) {
                return;
            }
            if (id == NotificationCenter.filePreparingFailed) {
                nc.removeObserver(holder[0], NotificationCenter.filePreparingFailed);
                nc.removeObserver(holder[0], NotificationCenter.fileNewChunkAvailable);
                done.run("video");
            } else if (id == NotificationCenter.fileNewChunkAvailable) {
                long finalSize = (Long) args[3];
                if (finalSize != 0) {
                    nc.removeObserver(holder[0], NotificationCenter.filePreparingFailed);
                    nc.removeObserver(holder[0], NotificationCenter.fileNewChunkAvailable);
                    ProfileBannerUpload.startFile(account, (String) args[1], true, done);
                }
            }
        };
        nc.addObserver(holder[0], NotificationCenter.filePreparingFailed);
        nc.addObserver(holder[0], NotificationCenter.fileNewChunkAvailable);
        try {
            if (!MediaController.getInstance().scheduleVideoConvert(object, false, true, false)) {
                nc.removeObserver(holder[0], NotificationCenter.filePreparingFailed);
                nc.removeObserver(holder[0], NotificationCenter.fileNewChunkAvailable);
                done.run("video");
            }
        } catch (Throwable t) {
            FileLog.e(t);
            nc.removeObserver(holder[0], NotificationCenter.filePreparingFailed);
            nc.removeObserver(holder[0], NotificationCenter.fileNewChunkAvailable);
            done.run("video");
        }
    }
}
