package com.exteragram.messenger.speech.utils;

import android.text.TextUtils;

import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Makes sure the media files of the given messages are downloaded, then reports how many files exist.
 * Used before on-device recognition of a voice message.
 */
public class MediaLoader implements NotificationCenter.NotificationCenterDelegate {

    private static final long FILE_TIMEOUT_MINUTES = 5;

    private final AccountInstance accountInstance;
    private final ArrayList<MessageObject> messageObjects;
    private final MessagesStorage.IntCallback onFinish;
    private final HashMap<String, MessageObject> loadingMessageObjects = new HashMap<>();
    private volatile CountDownLatch waitingForFile;
    private int existingFiles;

    private MediaLoader(AccountInstance accountInstance, ArrayList<MessageObject> messageObjects, MessagesStorage.IntCallback onFinish) {
        this.accountInstance = accountInstance;
        this.messageObjects = messageObjects;
        this.onFinish = onFinish;
    }

    /** Must be called on the main thread. The callback is invoked on the main thread. */
    public static void loadFiles(AccountInstance accountInstance, ArrayList<MessageObject> messageObjects, MessagesStorage.IntCallback onFinish) {
        new MediaLoader(accountInstance, messageObjects, onFinish).start();
    }

    private void start() {
        accountInstance.getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
        accountInstance.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
        new Thread(this::run, "MediaLoader").start();
    }

    private void run() {
        try {
            for (MessageObject messageObject : messageObjects) {
                if (!TextUtils.isEmpty(ChatUtils.getInstance().getPathToMessage(messageObject))) {
                    existingFiles++;
                    continue;
                }
                TLRPC.Document document = messageObject.getDocument();
                if (document == null) {
                    continue;
                }
                CountDownLatch latch = new CountDownLatch(1);
                waitingForFile = latch;
                AndroidUtilities.runOnUIThread(() -> {
                    loadingMessageObjects.put(FileLoader.getAttachFileName(document), messageObject);
                    accountInstance.getFileLoader().loadFile(document, messageObject, FileLoader.PRIORITY_HIGH, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 0);
                });
                if (!latch.await(FILE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    FileLog.d("MediaLoader: timeout while loading " + messageObject.getId());
                }
                if (!TextUtils.isEmpty(ChatUtils.getInstance().getPathToMessage(messageObject))) {
                    existingFiles++;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(this::finish);
    }

    private void finish() {
        accountInstance.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
        accountInstance.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
        loadingMessageObjects.clear();
        if (onFinish != null) {
            try {
                onFinish.run(existingFiles);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            if (args.length > 0 && args[0] instanceof String && loadingMessageObjects.remove((String) args[0]) != null) {
                CountDownLatch latch = waitingForFile;
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }
}
