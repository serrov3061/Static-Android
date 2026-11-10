package com.exteragram.messenger.feed;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * exteraGram feed: read state of the feed channels. Posts seen in the feed are marked as read in their
 * channels (regular messages.readHistory via MessagesController.markDialogAsRead), batched once a second.
 */
final class FeedUnreadTracker {

    private final int currentAccount;
    private boolean flushScheduled;
    private final ArrayList<MessageObject> timeline;
    private final LongSparseArray<Integer> readInboxMaxByDialog = new LongSparseArray<>();
    private final LongSparseArray<Integer> pendingMaxReadId = new LongSparseArray<>();
    private final Runnable flushRunnable = this::flush;

    FeedUnreadTracker(int account, ArrayList<MessageObject> timeline) {
        this.currentAccount = account;
        this.timeline = timeline;
    }

    public void clear() {
        if (flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushScheduled = false;
        }
        flush();
        readInboxMaxByDialog.clear();
    }

    public void applyReadInboxMax(long dialogId, int maxId) {
        if (maxId > readInboxMaxByDialog.get(dialogId, 0)) {
            readInboxMaxByDialog.put(dialogId, maxId);
        }
    }

    public boolean isUnread(MessageObject messageObject) {
        return messageObject != null && !messageObject.isSponsored() && messageObject.getRealId() > getEffectiveReadInboxMax(messageObject.getDialogId());
    }

    private int getEffectiveReadInboxMax(long dialogId) {
        return Math.max(readInboxMaxByDialog.get(dialogId, 0), pendingMaxReadId.get(dialogId, 0));
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        if (messages != null && !readInboxMaxByDialog.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (isUnread(messages.get(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int index) {
        if (messages == null || readInboxMaxByDialog.isEmpty()) {
            return 0;
        }
        int end = Math.min(index, messages.size());
        int count = 0;
        for (int i = 0; i < end; i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && !messageObject.isDateObject && messageObject.type != MessageObject.TYPE_LOADING && !messageObject.isSponsored() && isUnread(messageObject)) {
                count++;
            }
        }
        return count;
    }

    public void onPostSeen(long dialogId, int messageId) {
        if (dialogId == 0 || messageId <= 0 || messageId <= getEffectiveReadInboxMax(dialogId)) {
            return;
        }
        Integer pending = pendingMaxReadId.get(dialogId);
        if (pending == null || pending < messageId) {
            pendingMaxReadId.put(dialogId, messageId);
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
            AndroidUtilities.runOnUIThread(flushRunnable, 1000L);
        }
    }

    private void flush() {
        flushScheduled = false;
        if (pendingMaxReadId.isEmpty()) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        LongSparseArray<Integer> rowCounts = countPendingTimelineRows();
        for (int i = 0; i < pendingMaxReadId.size(); i++) {
            long dialogId = pendingMaxReadId.keyAt(i);
            int maxId = pendingMaxReadId.valueAt(i);
            if (maxId > readInboxMaxByDialog.get(dialogId, 0)) {
                readInboxMaxByDialog.put(dialogId, maxId);
                messagesController.markDialogAsRead(dialogId, maxId, 0, currentTime, false, 0, Math.max(rowCounts.get(dialogId, 0), 1), true, 0);
            }
        }
        pendingMaxReadId.clear();
    }

    private LongSparseArray<Integer> countPendingTimelineRows() {
        LongSparseArray<Integer> result = new LongSparseArray<>();
        for (int i = 0; i < timeline.size(); i++) {
            MessageObject messageObject = timeline.get(i);
            if (messageObject == null) {
                continue;
            }
            long dialogId = messageObject.getDialogId();
            int pending = pendingMaxReadId.get(dialogId, 0);
            int realId;
            if (pending != 0 && (realId = messageObject.getRealId()) > readInboxMaxByDialog.get(dialogId, 0) && realId <= pending) {
                result.put(dialogId, result.get(dialogId, 0) + 1);
            }
        }
        return result;
    }

    public void markAllRead() {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        HashSet<Long> handled = new HashSet<>();
        ArrayList<TLRPC.Dialog> dialogs = collectUnreadFeedDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            messagesController.markMentionsAsRead(dialog.id, 0);
            messagesController.markDialogAsRead(dialog.id, dialog.top_message, dialog.top_message, dialog.last_message_date, false, 0, 0, true, 0);
            readInboxMaxByDialog.put(dialog.id, dialog.top_message);
            handled.add(dialog.id);
        }
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        boolean includeArchived = feedConfig.getIncludeArchived();
        for (int i = 0; i < timeline.size(); i++) {
            MessageObject messageObject = timeline.get(i);
            if (messageObject == null) {
                continue;
            }
            long dialogId = messageObject.getDialogId();
            if (!feedConfig.isShownInFeed(dialogId)) {
                continue;
            }
            TLRPC.Dialog dialog;
            if (includeArchived || (dialog = messagesController.dialogs_dict.get(dialogId)) == null || dialog.folder_id != 1) {
                handled.add(dialogId);
                int realId = messageObject.getRealId();
                if (realId > readInboxMaxByDialog.get(dialogId, 0)) {
                    readInboxMaxByDialog.put(dialogId, realId);
                }
            }
        }
        for (Long dialogId : handled) {
            pendingMaxReadId.remove(dialogId);
        }
        if (pendingMaxReadId.isEmpty() && flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushScheduled = false;
        }
    }

    public int getUnreadCount() {
        ArrayList<TLRPC.Dialog> dialogs = collectUnreadFeedDialogs();
        int count = 0;
        for (int i = 0; i < dialogs.size(); i++) {
            count += dialogs.get(i).unread_count;
        }
        return count;
    }

    private ArrayList<TLRPC.Dialog> collectUnreadFeedDialogs() {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        boolean includeArchived = feedConfig.getIncludeArchived();
        LongSparseArray<TLRPC.Dialog> dialogsDict = messagesController.dialogs_dict;
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        for (int i = 0; i < dialogsDict.size(); i++) {
            TLRPC.Dialog dialog = dialogsDict.valueAt(i);
            if (dialog == null || dialog.unread_count <= 0) {
                continue;
            }
            long dialogId = dialog.id;
            if (DialogObject.isChatDialog(dialogId) && feedConfig.isShownInFeed(dialogId) && (includeArchived || dialog.folder_id != 1) && FeedController.isEligibleChannel(messagesController.getChat(-dialogId))) {
                result.add(dialog);
            }
        }
        return result;
    }
}
